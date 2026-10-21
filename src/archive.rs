//! Look inside archives and unpack them. zip, tar, tar.gz/tgz and gz are handled natively; other formats
//! (7z, rar, tar.xz, tar.bz2, iso…) go through `bsdtar` when it is installed.
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs::File,
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    process::Command,
};

const MAX_ENTRIES: usize = 200_000;
const MAX_UNPACKED: u64 = 32 << 30; // zip-bomb guard
const MAX_ENTRY_PREVIEW: u64 = 64 << 20;

#[derive(PartialEq, Clone, Copy)]
pub enum Kind {
    Zip,
    Tar,
    TarGz,
    Gz,
    Other,
}

pub struct Entry {
    pub path: String,
    pub size: u64,
    pub dir: bool,
}

pub fn kind(path: &Path) -> Kind {
    let n = path.file_name().map(|s| s.to_string_lossy().to_lowercase()).unwrap_or_default();
    if n.ends_with(".tar.gz") || n.ends_with(".tgz") {
        Kind::TarGz
    } else if n.ends_with(".tar") {
        Kind::Tar
    } else if n.ends_with(".gz") {
        Kind::Gz
    } else if [".zip", ".jar", ".apk", ".cbz", ".epub", ".docx", ".xlsx", ".pptx", ".odt", ".ods"].iter().any(|e| n.ends_with(e)) {
        Kind::Zip
    } else {
        Kind::Other
    }
}

/// Entry names must stay inside the destination: no absolute paths, no "..".
fn clean(name: &str) -> Option<PathBuf> {
    let p = Path::new(name);
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::Normal(x) => out.push(x),
            Component::CurDir => {}
            _ => return None,
        }
    }
    (!out.as_os_str().is_empty()).then_some(out)
}

fn bsdtar() -> Option<&'static str> {
    ["bsdtar", "tar"].into_iter().find(|b| Command::new(b).arg("--version").output().is_ok_and(|o| o.status.success()))
}

pub fn list(path: &Path) -> Result<Vec<Entry>, String> {
    let mut out = vec![];
    match kind(path) {
        Kind::Zip => {
            let mut z = zip::ZipArchive::new(File::open(path).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
            for i in 0..z.len().min(MAX_ENTRIES) {
                let f = z.by_index_raw(i).map_err(|e| e.to_string())?;
                if let Some(p) = f.enclosed_name() {
                    out.push(Entry { path: p.to_string_lossy().into_owned(), size: f.size(), dir: f.is_dir() });
                }
            }
        }
        Kind::Tar | Kind::TarGz => {
            let f = File::open(path).map_err(|e| e.to_string())?;
            let r: Box<dyn Read> = if kind(path) == Kind::TarGz { Box::new(flate2::read::GzDecoder::new(f)) } else { Box::new(f) };
            let mut a = tar::Archive::new(r);
            for e in a.entries().map_err(|e| e.to_string())?.take(MAX_ENTRIES) {
                let e = e.map_err(|e| e.to_string())?;
                let p = e.path().map_err(|e| e.to_string())?.to_string_lossy().into_owned();
                if clean(&p).is_some() {
                    out.push(Entry { path: p.trim_end_matches('/').to_string(), size: e.header().size().unwrap_or(0), dir: e.header().entry_type().is_dir() });
                }
            }
        }
        Kind::Gz => {
            let name = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
            let mut f = File::open(path).map_err(|e| e.to_string())?;
            let len = f.metadata().map_err(|e| e.to_string())?.len();
            let mut size = 0;
            if len >= 4 {
                use std::io::{Seek, SeekFrom};
                f.seek(SeekFrom::End(-4)).ok();
                let mut b = [0u8; 4];
                if f.read_exact(&mut b).is_ok() {
                    size = u32::from_le_bytes(b) as u64; // the gzip trailer stores the size modulo 2^32
                }
            }
            out.push(Entry { path: name, size, dir: false });
        }
        Kind::Other => {
            let tool = bsdtar().ok_or("no tool to read this format (install libarchive: bsdtar)")?;
            let o = Command::new(tool).arg("-tvf").arg(path).output().map_err(|e| e.to_string())?;
            if !o.status.success() {
                return Err(String::from_utf8_lossy(&o.stderr).trim().to_string());
            }
            for l in String::from_utf8_lossy(&o.stdout).lines().take(MAX_ENTRIES) {
                // perms links owner group size mon day time/year name…  (the name may contain spaces)
                let parts: Vec<&str> = l.split_whitespace().collect();
                if parts.len() >= 9 {
                    let size = parts[4].parse().unwrap_or(0);
                    let name_start = l.match_indices(parts[7]).next().map(|(i, _)| i + parts[7].len()).unwrap_or(0);
                    let name = l[name_start..].trim().trim_end_matches('/').to_string();
                    if clean(&name).is_some() {
                        out.push(Entry { path: name, size, dir: parts[0].starts_with('d') });
                    }
                }
            }
        }
    }
    Ok(out)
}

pub struct Summary {
    pub dest: PathBuf,
    pub files: u64,
    pub bytes: u64,
}

fn unique_dir(base: PathBuf) -> PathBuf {
    if !base.exists() {
        return base;
    }
    (2..1000).map(|n| PathBuf::from(format!("{} ({n})", base.display()))).find(|p| !p.exists()).unwrap_or(base)
}

fn copy_limited(r: &mut impl Read, w: &mut impl Write, total: &mut u64) -> Result<(), String> {
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = r.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            return Ok(());
        }
        *total += n as u64;
        if *total > MAX_UNPACKED {
            return Err("unpacked size limit reached (archive too large or a zip bomb)".into());
        }
        w.write_all(&buf[..n]).map_err(|e| e.to_string())?;
    }
}

/// Unpack into a new folder (next to the archive unless `dest` is given). `only` limits it to those entry paths
/// (and anything below a chosen folder).
pub fn extract(path: &Path, dest: Option<&Path>, only: Option<&HashSet<String>>) -> Result<Summary, String> {
    let stem = path.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "archive".into());
    let stem = [".tar.gz", ".tgz", ".tar", ".zip", ".gz", ".7z", ".rar", ".xz", ".bz2", ".iso"].iter().find_map(|e| stem.strip_suffix(e)).unwrap_or(&stem).to_string();
    let dest = match dest {
        Some(d) => d.to_path_buf(),
        None => unique_dir(path.parent().unwrap_or(Path::new(".")).join(&stem)),
    };
    std::fs::create_dir_all(&dest).map_err(|e| e.to_string())?;
    let wanted = |name: &str| only.map_or(true, |o| o.iter().any(|w| name == w || name.starts_with(&format!("{}/", w.trim_end_matches('/')))));
    let (mut files, mut bytes) = (0u64, 0u64);
    match kind(path) {
        Kind::Zip => {
            let mut z = zip::ZipArchive::new(File::open(path).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
            for i in 0..z.len() {
                let mut f = z.by_index(i).map_err(|e| e.to_string())?;
                let Some(rel) = f.enclosed_name() else { continue };
                if !wanted(&rel.to_string_lossy()) {
                    continue;
                }
                let target = dest.join(&rel);
                if f.is_dir() {
                    std::fs::create_dir_all(&target).map_err(|e| e.to_string())?;
                    continue;
                }
                if let Some(p) = target.parent() {
                    std::fs::create_dir_all(p).map_err(|e| e.to_string())?;
                }
                let mode = f.unix_mode();
                let mut out = File::create(&target).map_err(|e| e.to_string())?;
                copy_limited(&mut f, &mut out, &mut bytes)?;
                if let Some(m) = mode {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(&target, std::fs::Permissions::from_mode(m & 0o777));
                }
                files += 1;
                if files as usize > MAX_ENTRIES {
                    return Err("too many entries".into());
                }
            }
        }
        Kind::Tar | Kind::TarGz => {
            let f = File::open(path).map_err(|e| e.to_string())?;
            let r: Box<dyn Read> = if kind(path) == Kind::TarGz { Box::new(flate2::read::GzDecoder::new(f)) } else { Box::new(f) };
            let mut a = tar::Archive::new(r);
            a.set_preserve_permissions(false);
            for e in a.entries().map_err(|e| e.to_string())? {
                let mut e = e.map_err(|e| e.to_string())?;
                let name = e.path().map_err(|e| e.to_string())?.to_string_lossy().trim_end_matches('/').to_string();
                if !wanted(&name) || e.header().entry_type().is_symlink() || e.header().entry_type().is_hard_link() {
                    continue;
                }
                bytes += e.header().size().unwrap_or(0);
                if bytes > MAX_UNPACKED {
                    return Err("unpacked size limit reached".into());
                }
                if e.unpack_in(&dest).map_err(|e| e.to_string())? && !e.header().entry_type().is_dir() {
                    files += 1;
                }
            }
        }
        Kind::Gz => {
            let name = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
            let mut d = flate2::read::GzDecoder::new(File::open(path).map_err(|e| e.to_string())?);
            let mut out = File::create(dest.join(clean(&name).ok_or("bad name")?)).map_err(|e| e.to_string())?;
            copy_limited(&mut d, &mut out, &mut bytes)?;
            files = 1;
        }
        Kind::Other => {
            let tool = bsdtar().ok_or("no tool to unpack this format (install libarchive: bsdtar)")?;
            let o = Command::new(tool).arg("-xf").arg(path).arg("-C").arg(&dest).output().map_err(|e| e.to_string())?;
            if !o.status.success() {
                return Err(String::from_utf8_lossy(&o.stderr).trim().to_string());
            }
            files = walk_count(&dest);
        }
    }
    Ok(Summary { dest, files, bytes })
}

fn walk_count(p: &Path) -> u64 {
    std::fs::read_dir(p).map_or(0, |d| d.flatten().map(|e| if e.path().is_dir() { walk_count(&e.path()) } else { 1 }).sum())
}

/// Bytes of one entry (zip/tar/tar.gz), capped, for previewing without unpacking everything.
pub fn read_entry(path: &Path, entry: &str) -> Result<Vec<u8>, String> {
    let mut out = vec![];
    match kind(path) {
        Kind::Zip => {
            let mut z = zip::ZipArchive::new(File::open(path).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
            let mut f = z.by_name(entry).map_err(|e| e.to_string())?;
            if f.size() > MAX_ENTRY_PREVIEW {
                return Err("entry too large to preview — extract it instead".into());
            }
            f.read_to_end(&mut out).map_err(|e| e.to_string())?;
        }
        Kind::Tar | Kind::TarGz => {
            let f = File::open(path).map_err(|e| e.to_string())?;
            let r: Box<dyn Read> = if kind(path) == Kind::TarGz { Box::new(flate2::read::GzDecoder::new(f)) } else { Box::new(f) };
            let mut a = tar::Archive::new(r);
            for e in a.entries().map_err(|e| e.to_string())? {
                let mut e = e.map_err(|e| e.to_string())?;
                if e.path().map_err(|e| e.to_string())?.to_string_lossy().trim_end_matches('/') == entry {
                    if e.header().size().unwrap_or(0) > MAX_ENTRY_PREVIEW {
                        return Err("entry too large to preview — extract it instead".into());
                    }
                    e.read_to_end(&mut out).map_err(|e| e.to_string())?;
                    return Ok(out);
                }
            }
            return Err("entry not found".into());
        }
        Kind::Gz => {
            let mut d = flate2::read::GzDecoder::new(File::open(path).map_err(|e| e.to_string())?).take(MAX_ENTRY_PREVIEW);
            d.read_to_end(&mut out).map_err(|e| e.to_string())?;
        }
        Kind::Other => {
            let tool = bsdtar().ok_or("cannot read this format")?;
            let o = Command::new(tool).arg("-xOf").arg(path).arg(entry).output().map_err(|e| e.to_string())?;
            if !o.status.success() || o.stdout.len() as u64 > MAX_ENTRY_PREVIEW {
                return Err("cannot read entry".into());
            }
            out = o.stdout;
        }
    }
    Ok(out)
}

pub fn list_json(path: &Path) -> Result<Value, String> {
    let entries = list(path)?;
    let truncated = entries.len() >= MAX_ENTRIES;
    Ok(json!({
        "name": path.file_name().map(|s| s.to_string_lossy().into_owned()),
        "entries": entries.iter().map(|e| json!({"path": e.path, "size": e.size, "dir": e.dir})).collect::<Vec<_>>(),
        "truncated": truncated,
    }))
}

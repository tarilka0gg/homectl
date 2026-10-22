//! File browsing for the app: list directories, download (with Range for resume), upload, make folders.
use axum::{
    body::Body,
    extract::Query,
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use std::{os::unix::fs::MetadataExt, path::PathBuf};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;

fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/".into()))
}

fn resolve(p: Option<&str>) -> PathBuf {
    match p.map(str::trim).filter(|s| !s.is_empty()) {
        None | Some("~") => home(),
        Some(s) if s.starts_with("~/") => home().join(&s[2..]),
        Some(s) => PathBuf::from(s),
    }
}

#[derive(Deserialize)]
pub struct PathQ {
    path: Option<String>,
}

pub async fn list(Query(q): Query<PathQ>) -> Response {
    let p = resolve(q.path.as_deref());
    let Ok(p) = tokio::fs::canonicalize(&p).await else { return StatusCode::NOT_FOUND.into_response() };
    let Ok(mut rd) = tokio::fs::read_dir(&p).await else { return StatusCode::FORBIDDEN.into_response() };
    let mut entries = vec![];
    let owners: std::collections::HashMap<u32, String> = std::fs::read_to_string("/etc/passwd")
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split(':').collect();
            Some((f.get(2)?.parse().ok()?, f[0].to_string()))
        })
        .collect();
    while let Ok(Some(e)) = rd.next_entry().await {
        let name = e.file_name().to_string_lossy().into_owned();
        let Ok(lm) = e.metadata().await else { continue };
        let link = lm.file_type().is_symlink();
        let m = if link { tokio::fs::metadata(e.path()).await.unwrap_or(lm) } else { lm };
        entries.push(json!({"name": name, "dir": m.is_dir(), "link": link, "size": if m.is_dir() { 0 } else { m.len() }, "mtime": m.mtime(), "hidden": name.starts_with('.'),
            "perms": rwx(m.mode(), if link { 'l' } else if m.is_dir() { 'd' } else { '-' }), "owner": owners.get(&m.uid()).cloned().unwrap_or_else(|| m.uid().to_string())}));
    }
    // dirs first, then names A→Z
    entries.sort_by(|a, b| b["dir"].as_bool().cmp(&a["dir"].as_bool()).then_with(|| a["name"].as_str().map(str::to_lowercase).cmp(&b["name"].as_str().map(str::to_lowercase))));
    let parent = p.parent().map(|x| x.display().to_string());
    Json(json!({"path": p.display().to_string(), "parent": parent, "home": home().display().to_string(), "entries": entries})).into_response()
}

pub async fn download(Query(q): Query<PathQ>, headers: HeaderMap) -> Response {
    let p = resolve(q.path.as_deref());
    let Ok(mut f) = tokio::fs::File::open(&p).await else { return StatusCode::NOT_FOUND.into_response() };
    let Ok(meta) = f.metadata().await else { return StatusCode::INTERNAL_SERVER_ERROR.into_response() };
    if !meta.is_file() {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let len = meta.len();
    let range = headers.get(header::RANGE).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("bytes="));
    let start = range.and_then(|v| v.split('-').next()).and_then(|v| v.parse::<u64>().ok()).filter(|s| *s < len);
    // bytes=a-b: inclusive end, so a preview can fetch just the beginning of a big file
    let end = range
        .and_then(|v| v.split('-').nth(1))
        .and_then(|v| v.parse::<u64>().ok())
        .map_or(len - 1, |e| e.min(len - 1));
    if let Some(s) = start {
        if f.seek(std::io::SeekFrom::Start(s)).await.is_err() {
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    let name = p.file_name().map_or("file".into(), |n| n.to_string_lossy().replace('"', ""));
    let mut r = Response::builder()
        .status(if start.is_some() { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK })
        .header(header::CONTENT_TYPE, media_type(&p))
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_DISPOSITION, format!("attachment; filename=\"{name}\""))
        .header(header::CONTENT_LENGTH, end + 1 - start.unwrap_or(0));
    if let Some(s) = start {
        r = r.header(header::CONTENT_RANGE, format!("bytes {s}-{end}/{len}"));
    }
    r.body(Body::from_stream(ReaderStream::with_capacity(f.take(end + 1 - start.unwrap_or(0)), 256 * 1024))).unwrap()
}

#[derive(Deserialize)]
pub struct UploadQ {
    dir: Option<String>,
    name: String,
}

impl UploadQ {
    pub fn new(dir: String, name: String) -> Self {
        Self { dir: Some(dir), name }
    }
}

/// Streams the body to disk (never holds a whole file in memory); refuses to overwrite.
pub async fn upload(Query(q): Query<UploadQ>, body: Body) -> StatusCode {
    let safe: String = q.name.chars().filter(|c| *c != '/' && *c != '\0').collect();
    if safe.is_empty() || safe == "." || safe == ".." {
        return StatusCode::BAD_REQUEST;
    }
    let path = resolve(q.dir.as_deref()).join(&safe);
    let Ok(mut f) = tokio::fs::OpenOptions::new().write(true).create_new(true).open(&path).await else { return StatusCode::CONFLICT };
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let Ok(c) = chunk else {
            let _ = tokio::fs::remove_file(&path).await;
            return StatusCode::BAD_REQUEST;
        };
        if f.write_all(&c).await.is_err() {
            let _ = tokio::fs::remove_file(&path).await;
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
    }
    StatusCode::CREATED
}

#[derive(Deserialize)]
pub struct Mk {
    path: String,
}

pub async fn mkdir(Json(m): Json<Mk>) -> StatusCode {
    if tokio::fs::create_dir(resolve(Some(&m.path))).await.is_ok() { StatusCode::NO_CONTENT } else { StatusCode::INTERNAL_SERVER_ERROR }
}


#[derive(Deserialize)]
pub struct ThumbQ {
    path: String,
    size: Option<u32>,
}

/// A small JPEG of an image, cached on disk so browsing a photo folder stays quick.
pub async fn thumb(Query(q): Query<ThumbQ>) -> Response {
    let size = q.size.unwrap_or(128).clamp(48, 512);
    let p = resolve(Some(&q.path));
    let Ok(meta) = tokio::fs::metadata(&p).await else { return StatusCode::NOT_FOUND.into_response() };
    if !meta.is_file() || meta.len() > 96 << 20 {
        return StatusCode::UNSUPPORTED_MEDIA_TYPE.into_response();
    }
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    (p.display().to_string(), meta.len(), meta.mtime(), size).hash(&mut h);
    let cache = home().join(".cache/homectl/thumbs").join(format!("{:016x}.jpg", h.finish()));
    if let Ok(b) = tokio::fs::read(&cache).await {
        return ([(header::CONTENT_TYPE, "image/jpeg"), (header::CACHE_CONTROL, "max-age=86400")], b).into_response();
    }
    let made = tokio::task::spawn_blocking(move || -> Option<Vec<u8>> {
        use image::{ImageDecoder, ImageReader};
        let reader = ImageReader::open(&p).ok()?.with_guessed_format().ok()?;
        let mut dec = reader.into_decoder().ok()?;
        let orientation = dec.orientation().ok();
        let mut img = image::DynamicImage::from_decoder(dec).ok()?;
        if let Some(o) = orientation {
            img.apply_orientation(o);
        }
        let t = img.thumbnail(size, size).to_rgb8();
        let mut out = vec![];
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 78).encode_image(&t).ok()?;
        Some(out)
    })
    .await
    .ok()
    .flatten();
    match made {
        Some(b) => {
            if let Some(d) = cache.parent() {
                let _ = tokio::fs::create_dir_all(d).await;
            }
            let _ = tokio::fs::write(&cache, &b).await;
            ([(header::CONTENT_TYPE, "image/jpeg"), (header::CACHE_CONTROL, "max-age=86400")], b).into_response()
        }
        None => StatusCode::UNSUPPORTED_MEDIA_TYPE.into_response(),
    }
}

// ---- archives ----

fn err(e: String) -> Response {
    (StatusCode::UNPROCESSABLE_ENTITY, Json(json!({ "error": e }))).into_response()
}

pub async fn archive_list(Query(q): Query<PathQ>) -> Response {
    let p = resolve(q.path.as_deref());
    match tokio::task::spawn_blocking(move || crate::archive::list_json(&p)).await {
        Ok(Ok(v)) => Json(v).into_response(),
        Ok(Err(e)) => err(e),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
pub struct ExtractReq {
    path: String,
    dest: Option<String>,
    entries: Option<Vec<String>>,
}

pub async fn archive_extract(Json(r): Json<ExtractReq>) -> Response {
    let p = resolve(Some(&r.path));
    let dest = r.dest.as_deref().map(|d| resolve(Some(d)));
    let only: Option<std::collections::HashSet<String>> = r.entries.map(|v| v.into_iter().collect());
    match tokio::task::spawn_blocking(move || crate::archive::extract(&p, dest.as_deref(), only.as_ref())).await {
        Ok(Ok(s)) => Json(json!({"dest": s.dest.display().to_string(), "files": s.files, "bytes": s.bytes})).into_response(),
        Ok(Err(e)) => err(e),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
pub struct EntryQ {
    path: String,
    entry: String,
}

pub async fn archive_file(Query(q): Query<EntryQ>) -> Response {
    let p = resolve(Some(&q.path));
    let name = q.entry.rsplit('/').next().unwrap_or("file").replace('"', "");
    match tokio::task::spawn_blocking(move || crate::archive::read_entry(&p, &q.entry)).await {
        Ok(Ok(b)) => Response::builder()
            .header(header::CONTENT_TYPE, "application/octet-stream")
            .header(header::CONTENT_LENGTH, b.len())
            .header(header::CONTENT_DISPOSITION, format!("attachment; filename=\"{name}\""))
            .body(Body::from(b))
            .unwrap(),
        Ok(Err(e)) => err(e),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

/// Content type for what the app can play in place; everything else downloads as raw bytes.
fn media_type(p: &std::path::Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).map(str::to_lowercase).as_deref() {
        Some("mp4" | "m4v") => "video/mp4",
        Some("mkv") => "video/x-matroska",
        Some("webm") => "video/webm",
        Some("mov") => "video/quicktime",
        Some("3gp") => "video/3gpp",
        Some("mp3") => "audio/mpeg",
        Some("m4a" | "aac") => "audio/mp4",
        Some("ogg" | "opus" | "oga") => "audio/ogg",
        Some("flac") => "audio/flac",
        Some("wav") => "audio/wav",
        _ => "application/octet-stream",
    }
}

#[derive(Deserialize)]
pub struct SearchQ {
    path: Option<String>,
    q: String,
    hidden: Option<bool>,
}

/// Recursive, case-insensitive name search below a folder (capped by time and result count).
pub async fn search(Query(q): Query<SearchQ>) -> Response {
    let root = resolve(q.path.as_deref());
    let needle = q.q.trim().to_lowercase();
    if needle.is_empty() {
        return Json(json!({"entries": [], "truncated": false})).into_response();
    }
    let hidden = q.hidden.unwrap_or(false);
    let r = tokio::task::spawn_blocking(move || {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(6);
        let (mut out, mut stack, mut truncated) = (vec![], vec![root], false);
        while let Some(d) = stack.pop() {
            if out.len() >= 300 || std::time::Instant::now() > deadline {
                truncated = true;
                break;
            }
            let Ok(rd) = std::fs::read_dir(&d) else { continue };
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().into_owned();
                if !hidden && name.starts_with('.') {
                    continue;
                }
                let Ok(ft) = e.file_type() else { continue };
                let is_dir = ft.is_dir();
                if is_dir {
                    stack.push(e.path());
                }
                if name.to_lowercase().contains(&needle) {
                    let m = e.metadata().ok();
                    out.push(json!({"name": name, "path": e.path().display().to_string(), "dir": is_dir, "size": m.as_ref().filter(|_| !is_dir).map_or(0, |m| m.len()), "mtime": m.map_or(0, |m| m.mtime())}));
                }
            }
        }
        json!({"entries": out, "truncated": truncated})
    })
    .await;
    match r {
        Ok(v) => Json(v).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

struct Kid {
    name: String,
    path: PathBuf,
    dir: bool,
    size: std::sync::atomic::AtomicU64,
}

/// Disk usage of each direct child of [`root`], walking all folders on many threads (like `dust`),
/// staying on one filesystem. Gives up after `limit` and reports what it has so far.
fn scan(root: &std::path::Path, limit: std::time::Duration) -> Option<serde_json::Value> {
    use std::sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering::Relaxed},
        Condvar, Mutex,
    };
    let started = std::time::Instant::now();
    let dev = std::fs::symlink_metadata(root).ok()?.dev();
    let mut kids = vec![];
    for e in std::fs::read_dir(root).ok()?.flatten() {
        let Ok(m) = e.metadata() else { continue };
        if m.is_dir() && m.dev() != dev {
            continue; // another filesystem mounted below
        }
        kids.push(Kid { name: e.file_name().to_string_lossy().into_owned(), path: e.path(), dir: m.is_dir(), size: (m.blocks() * 512).into() });
    }
    let queue: Mutex<Vec<(usize, PathBuf)>> = Mutex::new(kids.iter().enumerate().filter(|(_, k)| k.dir).map(|(i, k)| (i, k.path.clone())).collect());
    let pending = AtomicUsize::new(queue.lock().unwrap().len()); // folders queued or being read
    let cv = Condvar::new();
    let timed_out = AtomicBool::new(false);
    let workers = std::thread::available_parallelism().map_or(4, |n| n.get()).clamp(2, 16);
    std::thread::scope(|sc| {
        for _ in 0..workers {
            sc.spawn(|| loop {
                let item = {
                    let mut q = queue.lock().unwrap();
                    loop {
                        if timed_out.load(Relaxed) {
                            break None;
                        }
                        if let Some(i) = q.pop() {
                            break Some(i);
                        }
                        if pending.load(Relaxed) == 0 {
                            break None;
                        }
                        q = cv.wait_timeout(q, std::time::Duration::from_millis(20)).unwrap().0;
                    }
                };
                let Some((k, dir)) = item else {
                    cv.notify_all();
                    return;
                };
                if started.elapsed() > limit {
                    timed_out.store(true, Relaxed);
                }
                if let Ok(rd) = std::fs::read_dir(&dir) {
                    for e in rd.flatten() {
                        let Ok(m) = e.metadata() else { continue };
                        kids[k].size.fetch_add(m.blocks() * 512, Relaxed);
                        if m.is_dir() && m.dev() == dev {
                            pending.fetch_add(1, Relaxed);
                            queue.lock().unwrap().push((k, e.path()));
                            cv.notify_one();
                        }
                    }
                }
                if pending.fetch_sub(1, Relaxed) == 1 {
                    cv.notify_all();
                }
            });
        }
    });
    let mut rows: Vec<(u64, &Kid)> = kids.iter().map(|k| (k.size.load(Relaxed), k)).collect();
    rows.sort_by(|a, b| b.0.cmp(&a.0));
    let total: u64 = rows.iter().map(|r| r.0).sum();
    let entries: Vec<_> = rows.into_iter().take(80).map(|(size, k)| json!({"name": k.name, "path": k.path.display().to_string(), "dir": k.dir, "size": size})).collect();
    Some(json!({"path": root.display().to_string(), "entries": entries, "total": total, "partial": timed_out.load(Relaxed), "ms": started.elapsed().as_millis() as u64}))
}

// ---- scan cache: results survive app restarts and are shared by every client ----

type UsageCache = std::sync::Mutex<std::collections::HashMap<String, (u64, serde_json::Value)>>;

fn now_s() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn cache_file() -> PathBuf {
    home().join(".cache/homectl/usage.json")
}

fn usage_cache() -> &'static UsageCache {
    static C: std::sync::OnceLock<UsageCache> = std::sync::OnceLock::new();
    C.get_or_init(|| {
        let loaded: std::collections::HashMap<String, (u64, serde_json::Value)> =
            std::fs::read_to_string(cache_file()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        std::sync::Mutex::new(loaded)
    })
}

fn cache_put(key: &str, v: &serde_json::Value) {
    let mut c = usage_cache().lock().unwrap();
    c.insert(key.to_string(), (now_s(), v.clone()));
    while c.len() > 150 {
        let Some(oldest) = c.iter().min_by_key(|(_, (t, _))| *t).map(|(k, _)| k.clone()) else { break };
        c.remove(&oldest);
    }
    if let Ok(t) = serde_json::to_string(&*c) {
        let _ = std::fs::create_dir_all(cache_file().parent().unwrap());
        let _ = std::fs::write(cache_file(), t);
    }
}

/// Forget cached sizes of this path and of every folder above it (they just changed).
fn cache_forget_around(p: &std::path::Path) {
    let mut c = usage_cache().lock().unwrap();
    let before = c.len();
    c.retain(|k, _| !p.starts_with(k.as_str()) && !std::path::Path::new(k).starts_with(p));
    if c.len() != before {
        if let Ok(t) = serde_json::to_string(&*c) {
            let _ = std::fs::write(cache_file(), t);
        }
    }
}

fn scanning() -> &'static std::sync::Mutex<std::collections::HashSet<String>> {
    static S: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<String>>> = std::sync::OnceLock::new();
    S.get_or_init(Default::default)
}

#[derive(Deserialize)]
pub struct UsageQ {
    path: Option<String>,
    /// 1 = ignore the cache and measure again
    fresh: Option<u8>,
}

/// What takes the space below a folder: its biggest folders and files. Cached: a repeat request answers at once with
/// the saved result and its age; results older than 10 minutes are refreshed in the background.
pub async fn usage(Query(q): Query<UsageQ>) -> Response {
    let root = resolve(q.path.as_deref());
    let key = root.display().to_string();
    if q.fresh != Some(1) {
        let hit = usage_cache().lock().unwrap().get(&key).cloned();
        if let Some((at, mut v)) = hit {
            let age = now_s().saturating_sub(at);
            let stale = age > 600;
            if stale && scanning().lock().unwrap().insert(key.clone()) {
                let (r, k) = (root.clone(), key.clone());
                tokio::task::spawn_blocking(move || {
                    if let Some(v) = scan(&r, std::time::Duration::from_secs(120)) {
                        cache_put(&k, &v);
                    }
                    scanning().lock().unwrap().remove(&k);
                });
            }
            v["cached"] = json!(true);
            v["age_s"] = json!(age);
            v["refreshing"] = json!(stale);
            return Json(v).into_response();
        }
    }
    scanning().lock().unwrap().insert(key.clone());
    let k = key.clone();
    let res = tokio::task::spawn_blocking(move || {
        let v = scan(&root, std::time::Duration::from_secs(120));
        if let Some(v) = &v {
            cache_put(&k, v);
        }
        scanning().lock().unwrap().remove(&k);
        v
    })
    .await;
    match res {
        Ok(Some(mut v)) => {
            v["cached"] = json!(false);
            v["age_s"] = json!(0);
            Json(v).into_response()
        }
        Ok(None) => StatusCode::NOT_FOUND.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
pub struct DelReq {
    path: String,
}

/// Deletes a file or a whole folder. Refuses the filesystem root, top-level system folders, the home folder
/// itself and mount points, so a mistaken tap can't empty the machine.
pub async fn delete(Json(r): Json<DelReq>) -> Response {
    if std::env::var("HOMECTL_NO_DELETE").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    let p = resolve(Some(&r.path));
    let Ok(p) = tokio::fs::canonicalize(&p).await else { return StatusCode::NOT_FOUND.into_response() };
    if p.components().count() < 3 || p == home() || home().starts_with(&p) {
        return err("Refusing to delete a system or home folder.".into());
    }
    let res = tokio::task::spawn_blocking(move || -> Result<u64, String> {
        cache_forget_around(&p);
        let m = std::fs::symlink_metadata(&p).map_err(|e| e.to_string())?;
        if m.is_dir() {
            let parent = p.parent().and_then(|x| std::fs::metadata(x).ok());
            if parent.is_some_and(|pm| pm.dev() != m.dev()) {
                return Err("That folder is a mount point.".into());
            }
            std::fs::remove_dir_all(&p).map_err(|e| e.to_string())?;
        } else {
            std::fs::remove_file(&p).map_err(|e| e.to_string())?;
        }
        Ok(m.blocks() * 512)
    })
    .await;
    match res {
        Ok(Ok(_)) => StatusCode::NO_CONTENT.into_response(),
        Ok(Err(e)) => err(e),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
pub struct RenameReq {
    path: String,
    name: String,
}

/// Renames within the same folder; never overwrites.
pub async fn rename(Json(r): Json<RenameReq>) -> Response {
    let name = r.name.trim();
    if name.is_empty() || name == "." || name == ".." || name.contains('/') || name.contains('\0') {
        return err("That isn't a valid name.".into());
    }
    let from = resolve(Some(&r.path));
    let Some(parent) = from.parent() else { return err("Can't rename this.".into()) };
    if from.components().count() < 3 || from == home() {
        return err("Refusing to rename a system or home folder.".into());
    }
    let to = parent.join(name);
    if tokio::fs::symlink_metadata(&to).await.is_ok() {
        return err("Something with that name already exists.".into());
    }
    match tokio::fs::rename(&from, &to).await {
        Ok(()) => Json(json!({"path": to.display().to_string()})).into_response(),
        Err(e) => err(e.to_string()),
    }
}

#[derive(Deserialize)]
pub struct WriteReq {
    path: String,
    content: String,
    /// mtime of the version that was opened; refuse if the file changed since (unless `force`)
    mtime: Option<i64>,
    force: Option<bool>,
}

/// Saves edited text over an existing regular file (small files only), atomically and keeping permissions.
pub async fn write(Json(r): Json<WriteReq>) -> Response {
    if std::env::var("HOMECTL_NO_EDIT").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    if r.content.len() > 2 << 20 {
        return err("Too large to edit here (limit 2 MB).".into());
    }
    let Ok(p) = tokio::fs::canonicalize(resolve(Some(&r.path))).await else { return StatusCode::NOT_FOUND.into_response() };
    let res = tokio::task::spawn_blocking(move || -> Result<i64, (StatusCode, String)> {
        let m = std::fs::metadata(&p).map_err(|e| (StatusCode::UNPROCESSABLE_ENTITY, e.to_string()))?;
        if !m.is_file() {
            return Err((StatusCode::UNPROCESSABLE_ENTITY, "Not a regular file.".into()));
        }
        if let (Some(t), false) = (r.mtime.filter(|t| *t > 0), r.force == Some(true)) {
            if t != m.mtime() {
                return Err((StatusCode::CONFLICT, "This file changed on the machine since you opened it.".into()));
            }
        }
        let tmp = p.with_file_name(format!(".{}.homectl-tmp", p.file_name().map_or("file".into(), |n| n.to_string_lossy().into_owned())));
        let done = (|| -> std::io::Result<()> {
            std::fs::write(&tmp, r.content.as_bytes())?;
            std::fs::set_permissions(&tmp, m.permissions())?;
            std::fs::rename(&tmp, &p)
        })();
        if let Err(e) = done {
            let _ = std::fs::remove_file(&tmp);
            return Err((StatusCode::UNPROCESSABLE_ENTITY, e.to_string()));
        }
        Ok(std::fs::metadata(&p).map(|m| m.mtime()).unwrap_or(0))
    })
    .await;
    match res {
        Ok(Ok(mtime)) => Json(json!({"mtime": mtime})).into_response(),
        Ok(Err((code, e))) => (code, Json(json!({ "error": e }))).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

fn name_of(file: &str, id: u32) -> String {
    std::fs::read_to_string(file)
        .ok()
        .and_then(|t| t.lines().find_map(|l| {
            let f: Vec<&str> = l.split(':').collect();
            (f.len() > 2 && f[2].parse::<u32>().ok() == Some(id)).then(|| f[0].to_string())
        }))
        .unwrap_or_else(|| id.to_string())
}

fn rwx(mode: u32, kind: char) -> String {
    let b = |bit: u32, c: char| if mode & bit != 0 { c } else { '-' };
    let x = |bit: u32, special: u32, lower: char, upper: char| match (mode & bit != 0, mode & special != 0) {
        (true, true) => lower,
        (false, true) => upper,
        (true, false) => 'x',
        _ => '-',
    };
    format!(
        "{kind}{}{}{}{}{}{}{}{}{}",
        b(0o400, 'r'), b(0o200, 'w'), x(0o100, 0o4000, 's', 'S'),
        b(0o040, 'r'), b(0o020, 'w'), x(0o010, 0o2000, 's', 'S'),
        b(0o004, 'r'), b(0o002, 'w'), x(0o001, 0o1000, 't', 'T'),
    )
}

/// Everything worth knowing about one file or folder (what `stat` + `file` + a peek inside would say).
pub async fn stat(Query(q): Query<PathQ>) -> Response {
    let p = resolve(q.path.as_deref());
    let Ok(lm) = tokio::fs::symlink_metadata(&p).await else { return StatusCode::NOT_FOUND.into_response() };
    let is_link = lm.file_type().is_symlink();
    let target = if is_link { tokio::fs::read_link(&p).await.ok().map(|t| t.display().to_string()) } else { None };
    let m = if is_link { tokio::fs::metadata(&p).await.unwrap_or(lm.clone()) } else { lm.clone() };
    let kind = if m.is_dir() { "folder" } else if m.is_file() { "file" } else { "special" };
    let kind_char = if is_link { 'l' } else if m.is_dir() { 'd' } else if m.is_file() { '-' } else { 's' };
    let mut j = json!({
        "path": p.display().to_string(),
        "name": p.file_name().map_or(String::new(), |n| n.to_string_lossy().into_owned()),
        "kind": kind,
        "link": target,
        "size": if m.is_dir() { Value::Null } else { json!(m.len()) },
        "on_disk": m.blocks() * 512,
        "mode": format!("{:04o}", m.mode() & 0o7777),
        "perms": rwx(m.mode(), kind_char),
        "owner": name_of("/etc/passwd", m.uid()),
        "group": name_of("/etc/group", m.gid()),
        "uid": m.uid(), "gid": m.gid(),
        "links": m.nlink(),
        "inode": m.ino(),
        "mtime": m.mtime(),
        "atime": m.atime(),
        "ctime": m.ctime(),
        "birth": m.created().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()),
        "hidden": p.file_name().is_some_and(|n| n.to_string_lossy().starts_with('.')),
    });
    if m.is_dir() {
        let mut files = 0u64;
        let mut dirs = 0u64;
        let mut links = 0u64;
        let mut newest = 0i64;
        if let Ok(mut rd) = tokio::fs::read_dir(&p).await {
            while let Ok(Some(e)) = rd.next_entry().await {
                if let Ok(t) = e.file_type().await {
                    if t.is_dir() { dirs += 1 } else if t.is_symlink() { links += 1 } else { files += 1 }
                }
                if let Ok(em) = e.metadata().await {
                    newest = newest.max(em.mtime());
                }
            }
        }
        j["items"] = json!({"files": files, "folders": dirs, "links": links, "newest": newest});
    } else if m.is_file() {
        let pp = p.clone();
        let extra = tokio::task::spawn_blocking(move || {
            let mut o = serde_json::Map::new();
            if let Ok(out) = std::process::Command::new("file").args(["-b", "--mime-type", "--"]).arg(&pp).output() {
                let t = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if !t.is_empty() {
                    o.insert("mime".into(), json!(t));
                }
            }
            if let Ok(out) = std::process::Command::new("file").args(["-b", "--"]).arg(&pp).output() {
                let t = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if !t.is_empty() {
                    o.insert("description".into(), json!(t.chars().take(200).collect::<String>()));
                }
            }
            if let Ok(r) = image::ImageReader::open(&pp).and_then(|r| r.with_guessed_format()) {
                if let Ok((w, h)) = r.into_dimensions() {
                    o.insert("image".into(), json!({"width": w, "height": h}));
                }
            }
            // lines + words of reasonably small text files
            if let Ok(meta) = std::fs::metadata(&pp) {
                if meta.len() <= 8 << 20 {
                    if let Ok(b) = std::fs::read(&pp) {
                        if !b.iter().take(8192).any(|c| *c == 0) {
                            let lines = b.iter().filter(|c| **c == b'\n').count() + usize::from(!b.is_empty() && b.last() != Some(&b'\n'));
                            let words = String::from_utf8_lossy(&b).split_whitespace().count();
                            o.insert("text".into(), json!({"lines": lines, "words": words, "crlf": b.windows(2).any(|w| w == b"\r\n")}));
                        }
                    }
                }
            }
            o
        })
        .await
        .unwrap_or_default();
        for (k, v) in extra {
            j[k] = v;
        }
    }
    Json(j).into_response()
}

#[derive(Deserialize)]
pub struct HashQ {
    path: String,
    algo: Option<String>,
}

/// SHA-256 (default) / SHA-1 / MD5 of a file, computed by the system tools.
pub async fn hash(Query(q): Query<HashQ>) -> Response {
    let tool = match q.algo.as_deref().unwrap_or("sha256") {
        "sha256" => "sha256sum",
        "sha1" => "sha1sum",
        "md5" => "md5sum",
        _ => return StatusCode::BAD_REQUEST.into_response(),
    };
    let p = resolve(Some(&q.path));
    if !tokio::fs::metadata(&p).await.is_ok_and(|m| m.is_file()) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let run = tokio::process::Command::new(tool).arg("--").arg(&p).output();
    match tokio::time::timeout(std::time::Duration::from_secs(300), run).await {
        Ok(Ok(o)) if o.status.success() => {
            let s = String::from_utf8_lossy(&o.stdout);
            Json(json!({"algo": q.algo.unwrap_or("sha256".into()), "hash": s.split_whitespace().next().unwrap_or("")})).into_response()
        }
        _ => err("Couldn't compute the checksum.".into()),
    }
}

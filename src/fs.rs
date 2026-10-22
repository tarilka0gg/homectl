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
use serde_json::json;
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
    while let Ok(Some(e)) = rd.next_entry().await {
        let name = e.file_name().to_string_lossy().into_owned();
        let Ok(lm) = e.metadata().await else { continue };
        let link = lm.file_type().is_symlink();
        let m = if link { tokio::fs::metadata(e.path()).await.unwrap_or(lm) } else { lm };
        entries.push(json!({"name": name, "dir": m.is_dir(), "link": link, "size": if m.is_dir() { 0 } else { m.len() }, "mtime": m.mtime(), "hidden": name.starts_with('.')}));
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

/// What takes the space below a folder: its biggest folders and files.
pub async fn usage(Query(q): Query<PathQ>) -> Response {
    let root = resolve(q.path.as_deref());
    match tokio::task::spawn_blocking(move || scan(&root, std::time::Duration::from_secs(60))).await {
        Ok(Some(v)) => Json(v).into_response(),
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

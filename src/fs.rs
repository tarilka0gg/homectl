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
        .header(header::CONTENT_TYPE, "application/octet-stream")
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

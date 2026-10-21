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
    let start = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("bytes="))
        .and_then(|v| v.split('-').next())
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|s| *s < len);
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
        .header(header::CONTENT_LENGTH, len - start.unwrap_or(0));
    if let Some(s) = start {
        r = r.header(header::CONTENT_RANGE, format!("bytes {s}-{}/{len}", len - 1));
    }
    r.body(Body::from_stream(ReaderStream::with_capacity(f.take(len - start.unwrap_or(0)), 256 * 1024))).unwrap()
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

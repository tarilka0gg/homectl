//! The phone's window list: what is open on this machine's desktop, as data rather than pixels.
//!
//! Window titles, app ids, workspaces and focus come from the niri compositor's IPC socket. Terminals started through
//! `homectl-pty` (see zig/pty.zig) also serve their screen on a unix socket, so the phone gets real text, colours and
//! scrollback and can type into them. A terminal that was not started that way still shows up, with a title and an icon.
//! The format is described in docs/WINDOWS.md.

use axum::{
    extract::{Path, Query},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::HashMap, os::unix::fs::MetadataExt, path::PathBuf, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::UnixStream,
    time::timeout,
};

fn uid() -> u32 {
    std::fs::metadata("/proc/self").map(|m| m.uid()).unwrap_or(0)
}

fn runtime_dir() -> PathBuf {
    std::env::var("XDG_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from(format!("/run/user/{}", uid())))
}

fn win_dir() -> PathBuf {
    let d = runtime_dir().join("homectl/win");
    if d.is_dir() {
        d
    } else {
        PathBuf::from(format!("/tmp/homectl-{}/win", uid()))
    }
}

// ---- niri ----

fn niri_socket() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("NIRI_SOCKET") {
        return Some(p.into());
    }
    let mut socks: Vec<_> = std::fs::read_dir(runtime_dir()).ok()?.flatten().filter(|e| e.file_name().to_string_lossy().starts_with("niri.") && e.file_name().to_string_lossy().ends_with(".sock")).collect();
    socks.sort_by_key(|e| std::cmp::Reverse(e.metadata().and_then(|m| m.modified()).ok()));
    socks.first().map(|e| e.path())
}

async fn niri_call(req: Value) -> Option<Value> {
    let path = niri_socket()?;
    let work = async {
        let mut s = UnixStream::connect(path).await.ok()?;
        s.write_all(format!("{req}\n").as_bytes()).await.ok()?;
        let mut buf = Vec::new();
        let mut chunk = [0u8; 8192];
        loop {
            let n = s.read(&mut chunk).await.ok()?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
            if buf.contains(&b'\n') {
                break;
            }
        }
        serde_json::from_slice::<Value>(buf.split(|b| *b == b'\n').next()?).ok()
    };
    let v = timeout(Duration::from_secs(2), work).await.ok()??;
    v.get("Ok").cloned()
}

// ---- terminals started through homectl-pty ----

struct Frame {
    json: Value,
}

async fn read_frame(s: &mut UnixStream) -> Option<Frame> {
    let mut h = [0u8; 5];
    s.read_exact(&mut h).await.ok()?;
    let n = u32::from_le_bytes([h[1], h[2], h[3], h[4]]) as usize;
    if n > 8_000_000 || h[0] != b'S' {
        return None;
    }
    let mut d = vec![0u8; n];
    s.read_exact(&mut d).await.ok()?;
    Some(Frame { json: serde_json::from_slice(&d).ok()? })
}

async fn send_frame(s: &mut UnixStream, kind: u8, payload: &[u8]) -> bool {
    let mut f = vec![kind];
    f.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    f.extend_from_slice(payload);
    s.write_all(&f).await.is_ok()
}

fn term_path(pid: u32) -> PathBuf {
    win_dir().join(format!("{pid}.sock"))
}

/// pids of live wrapped terminals; sockets whose wrapper has died are removed.
fn wrapped_pids() -> Vec<u32> {
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return vec![];
    }
    let Ok(rd) = std::fs::read_dir(win_dir()) else { return vec![] };
    let mut out = vec![];
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Some(pid) = name.strip_suffix(".sock").and_then(|p| p.parse::<u32>().ok()) else { continue };
        if std::path::Path::new(&format!("/proc/{pid}")).exists() {
            out.push(pid);
        } else {
            let _ = std::fs::remove_file(e.path());
        }
    }
    out
}

async fn snapshot(pid: u32, back: u16) -> Option<Value> {
    let work = async {
        let mut s = UnixStream::connect(term_path(pid)).await.ok()?;
        if !send_frame(&mut s, b'G', &back.to_le_bytes()).await {
            return None;
        }
        Some(read_frame(&mut s).await?.json)
    };
    timeout(Duration::from_millis(600), work).await.ok()?
}

fn ppid(pid: u32) -> Option<u32> {
    let s = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    s.rsplit_once(')')?.1.split_whitespace().nth(1)?.parse().ok()
}

/// The ancestors of `pid`, nearest first.
fn ancestors(pid: u32) -> Vec<u32> {
    let mut v = vec![];
    let mut p = pid;
    while let Some(pp) = ppid(p) {
        if pp <= 1 || v.len() > 32 {
            break;
        }
        v.push(pp);
        p = pp;
    }
    v
}

fn plain(line: &Value) -> String {
    line.as_array().map(|runs| runs.iter().filter_map(|r| r.get(0)?.as_str()).collect::<String>()).unwrap_or_default()
}

/// The last few non-empty lines of a snapshot, as plain text, for the window list.
fn preview(snap: &Value) -> Vec<String> {
    let lines: Vec<String> = snap["lines"].as_array().map(|a| a.iter().map(plain).collect()).unwrap_or_default();
    let mut tail: Vec<String> = lines.into_iter().rev().skip_while(|l| l.trim().is_empty()).take(6).collect();
    tail.reverse();
    tail
}

// ---- the list ----

pub async fn list() -> impl IntoResponse {
    let (wins, spaces) = tokio::join!(niri_call(json!("Windows")), niri_call(json!("Workspaces")));
    let niri = wins.is_some();
    let space_name: HashMap<u64, String> = spaces
        .as_ref()
        .and_then(|v| v.get("Workspaces")?.as_array().cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(|w| Some((w["id"].as_u64()?, w["name"].as_str().map(str::to_string).unwrap_or_else(|| w["idx"].to_string()))))
        .collect();

    let wrapped = wrapped_pids();
    let snaps: Vec<(u32, Option<Value>)> = futures_util::future::join_all(wrapped.iter().map(|p| async move { (*p, snapshot(*p, 0).await) })).await;
    let mut free: HashMap<u32, Option<Value>> = snaps.into_iter().collect();
    // the wrapper's ancestors say which window it lives in
    let anc: HashMap<u32, Vec<u32>> = free.keys().map(|p| (*p, ancestors(*p))).collect();

    let mut cards = vec![];
    for w in wins.as_ref().and_then(|v| v.get("Windows")?.as_array().cloned()).unwrap_or_default() {
        let wpid = w["pid"].as_u64().unwrap_or(0) as u32;
        let hit = anc.iter().find(|(_, a)| wpid != 0 && a.contains(&wpid)).map(|(p, _)| *p);
        let snap = hit.and_then(|p| free.remove(&p)).flatten();
        let mut card = json!({
            "id": format!("n{}", w["id"]),
            "title": w["title"],
            "app_id": w["app_id"],
            "workspace": w["workspace_id"].as_u64().and_then(|i| space_name.get(&i).cloned()),
            "focused": w["is_focused"],
            "floating": w["is_floating"],
            "kind": "window",
        });
        if let (Some(p), Some(s)) = (hit, snap) {
            card["kind"] = json!("terminal");
            card["term"] = json!(format!("p{p}"));
            card["preview"] = json!(preview(&s));
            card["cwd"] = s["cwd"].clone();
            card["cmd"] = s["cmd"].clone();
            card["cols"] = s["cols"].clone();
            card["rows"] = s["rows"].clone();
        }
        cards.push(card);
    }
    // wrapped terminals the compositor does not know (no niri, or another compositor)
    for (p, s) in free {
        let Some(s) = s else { continue };
        cards.push(json!({
            "id": format!("p{p}"), "title": if s["title"].as_str().unwrap_or("").is_empty() { s["cmd"].clone() } else { s["title"].clone() },
            "app_id": "terminal", "workspace": null, "focused": false, "floating": false, "kind": "terminal",
            "term": format!("p{p}"), "preview": preview(&s), "cwd": s["cwd"], "cmd": s["cmd"], "cols": s["cols"], "rows": s["rows"],
        }));
    }
    Json(json!({"niri": niri, "windows": cards}))
}

fn term_pid(id: &str) -> Option<u32> {
    id.strip_prefix('p')?.parse().ok()
}

#[derive(Deserialize)]
pub struct ScreenQ {
    since: Option<u64>,
    back: Option<u16>,
    /// seconds to wait for a change; the answer comes sooner if the screen changes
    wait: Option<u64>,
}

/// The live screen of a wrapped terminal. With `since`, waits (up to `wait` seconds, at most 20) until it differs.
pub async fn screen(Path(id): Path<String>, Query(q): Query<ScreenQ>) -> Response {
    let Some(pid) = term_pid(&id) else { return StatusCode::NOT_FOUND.into_response() };
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    let back = q.back.unwrap_or(200).min(1000);
    let Ok(mut s) = UnixStream::connect(term_path(pid)).await else { return StatusCode::NOT_FOUND.into_response() };
    if !send_frame(&mut s, b'W', &back.to_le_bytes()).await {
        return StatusCode::BAD_GATEWAY.into_response();
    }
    let since = q.since.unwrap_or(0);
    let deadline = Duration::from_secs(q.wait.unwrap_or(0).min(20)) + Duration::from_millis(800);
    let work = async {
        loop {
            let f = read_frame(&mut s).await?;
            if f.json["seq"].as_u64().unwrap_or(0) != since {
                return Some(f.json);
            }
        }
    };
    match timeout(deadline, work).await {
        Ok(Some(v)) => Json(v).into_response(),
        Ok(None) => StatusCode::BAD_GATEWAY.into_response(),
        Err(_) => Json(json!({"same": true, "seq": since})).into_response(),
    }
}

#[derive(Deserialize)]
pub struct InputB {
    text: String,
}

pub async fn input(Path(id): Path<String>, Json(b): Json<InputB>) -> StatusCode {
    let Some(pid) = term_pid(&id) else { return StatusCode::NOT_FOUND };
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return StatusCode::FORBIDDEN;
    }
    if b.text.len() > 8192 {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let Ok(mut s) = UnixStream::connect(term_path(pid)).await else { return StatusCode::NOT_FOUND };
    if send_frame(&mut s, b'I', b.text.as_bytes()).await {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::BAD_GATEWAY
    }
}

async fn niri_window_for(id: &str) -> Option<u64> {
    if let Some(n) = id.strip_prefix('n') {
        return n.parse().ok();
    }
    let pid = term_pid(id)?;
    let anc = ancestors(pid);
    let wins = niri_call(json!("Windows")).await?;
    wins.get("Windows")?.as_array()?.iter().find(|w| w["pid"].as_u64().is_some_and(|p| anc.contains(&(p as u32))))?["id"].as_u64()
}

pub async fn focus(Path(id): Path<String>) -> StatusCode {
    let Some(n) = niri_window_for(&id).await else { return StatusCode::NOT_FOUND };
    match niri_call(json!({"Action": {"FocusWindow": {"id": n}}})).await {
        Some(_) => StatusCode::NO_CONTENT,
        None => StatusCode::BAD_GATEWAY,
    }
}

pub async fn close(Path(id): Path<String>) -> StatusCode {
    if std::env::var("HOMECTL_NO_DELETE").is_ok() {
        return StatusCode::FORBIDDEN;
    }
    let Some(n) = niri_window_for(&id).await else { return StatusCode::NOT_FOUND };
    match niri_call(json!({"Action": {"CloseWindow": {"id": n}}})).await {
        Some(_) => StatusCode::NO_CONTENT,
        None => StatusCode::BAD_GATEWAY,
    }
}

// ---- icons ----

fn desktop_icon(app_id: &str) -> Option<String> {
    let home = std::env::var("HOME").unwrap_or_default();
    let dirs = [format!("{home}/.local/share/applications"), "/usr/share/applications".into(), "/usr/local/share/applications".into(), "/var/lib/flatpak/exports/share/applications".into()];
    let want = app_id.to_lowercase();
    for d in dirs {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_lowercase();
            let stem = n.strip_suffix(".desktop").unwrap_or("");
            let matches = stem == want || stem.ends_with(&format!(".{want}"));
            if !matches {
                continue;
            }
            if let Ok(t) = std::fs::read_to_string(e.path()) {
                if let Some(l) = t.lines().find_map(|l| l.strip_prefix("Icon=")) {
                    return Some(l.trim().to_string());
                }
            }
        }
    }
    None
}

fn find_icon(name: &str) -> Option<PathBuf> {
    if name.starts_with('/') {
        return name.ends_with(".png").then(|| PathBuf::from(name)).filter(|p| p.is_file());
    }
    if name.contains('/') || name.contains("..") {
        return None;
    }
    let home = std::env::var("HOME").unwrap_or_default();
    let roots = [format!("{home}/.local/share/icons"), "/usr/share/icons".to_string(), "/usr/share/pixmaps".to_string()];
    let mut best: Option<(u32, PathBuf)> = None;
    for root in roots {
        let mut stack = vec![PathBuf::from(&root)];
        let mut seen = 0;
        while let Some(d) = stack.pop() {
            seen += 1;
            if seen > 4000 {
                break;
            }
            let Ok(rd) = std::fs::read_dir(&d) else { continue };
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() {
                    stack.push(p);
                } else if p.file_name().is_some_and(|f| f.to_string_lossy() == format!("{name}.png")) {
                    // prefer a size close to 128 px
                    let size = p.components().filter_map(|c| c.as_os_str().to_str()?.split('x').next()?.parse::<u32>().ok()).next().unwrap_or(48);
                    let score = size.abs_diff(128);
                    if best.as_ref().is_none_or(|(s, _)| score < *s) {
                        best = Some((score, p));
                    }
                }
            }
        }
        if best.is_some() {
            break;
        }
    }
    best.map(|b| b.1)
}

pub async fn icon(Path(app_id): Path<String>) -> Response {
    if app_id.len() > 120 || app_id.contains('/') {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let found = tokio::task::spawn_blocking(move || {
        let name = desktop_icon(&app_id).unwrap_or_else(|| app_id.clone());
        find_icon(&name).or_else(|| find_icon(&app_id.to_lowercase()))
    })
    .await
    .ok()
    .flatten();
    match found.and_then(|p| std::fs::read(p).ok()) {
        Some(b) => ([(header::CONTENT_TYPE, "image/png"), (header::CACHE_CONTROL, "max-age=3600")], b).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_round_trip() {
        assert_eq!(term_pid("p4242"), Some(4242));
        assert_eq!(term_pid("n7"), None);
        assert_eq!(term_pid("p../../x"), None);
    }

    #[test]
    fn preview_keeps_the_last_lines_without_trailing_blanks() {
        let snap = json!({"lines": [[["a",-1,-1,0]], [["b",-1,-1,0],["c",1,-1,0]], [], [["  ",-1,-1,0]]]});
        assert_eq!(preview(&snap), vec!["a".to_string(), "bc".to_string()]);
    }

    #[test]
    fn icon_names_cannot_escape_the_icon_folders() {
        assert!(find_icon("../../etc/passwd").is_none());
        assert!(find_icon("/etc/passwd").is_none());
    }
}

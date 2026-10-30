//! The phone's window list: what is open on this machine's desktop, as data rather than pixels.
//!
//! Window titles and app ids come from the compositor through the standard wlroots protocol (see toplevels.rs), so nothing
//! here depends on a particular compositor. Terminals started through `homectl-pty` (see zig/pty.zig) also serve their screen
//! on a unix socket, so the phone gets real text, colours and scrollback and can type into them; a terminal that was not
//! started that way still shows up, with a title and an icon.
//!
//! Which window is which wrapper is settled by a name that only the two of them know: the wrapper writes an invisible name
//! (24 bits as zero-width characters) into every title its shell sets, and the compositor hands that title back. Process ids
//! cannot do this job, because one ghostty process owns all of its windows.
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

/// The invisible name a wrapper put into a window title (see `Tagger` in zig/pty.zig): U+2060, 24 zero-width bits, U+2060.
fn wid_of(title: &str) -> Option<String> {
    let mut it = title.chars().skip_while(|c| *c != '\u{2060}').skip(1);
    let mut id: u32 = 0;
    for _ in 0..24 {
        id = id << 1 | match it.next()? {
            '\u{200b}' => 0,
            '\u{200c}' => 1,
            _ => return None,
        };
    }
    (it.next()? == '\u{2060}').then(|| format!("{id:06x}"))
}

/// The title as people should see it, without the invisible name.
fn clean_title(title: &str) -> String {
    title.chars().filter(|c| !matches!(*c, '\u{2060}' | '\u{200b}' | '\u{200c}')).collect()
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

/// The same lines with their colours (runs of [text, fg, bg, attrs]), for the cards in the window list.
fn preview_runs(snap: &Value) -> Vec<Value> {
    let lines = snap["lines"].as_array().cloned().unwrap_or_default();
    let mut tail: Vec<Value> = lines.into_iter().rev().skip_while(|l| plain(l).trim().is_empty()).take(6).collect();
    tail.reverse();
    tail
}

// ---- where niri has put each window ----

pub(crate) fn niri_socket() -> Option<PathBuf> {
    if let Ok(s) = std::env::var("NIRI_SOCKET") {
        return Some(PathBuf::from(s));
    }
    // niri names its socket after the display it serves (niri.wayland-1.<pid>.sock); a nested niri for testing has its own, so
    // the one for this daemon's display wins, and only without a display is the newest taken
    let display = std::env::var("WAYLAND_DISPLAY").unwrap_or_default();
    let display = display.rsplit('/').next().unwrap_or("").to_string();
    let rd = std::fs::read_dir(runtime_dir()).ok()?;
    let socks: Vec<_> = rd.flatten().filter(|e| e.file_name().to_string_lossy().starts_with("niri.") && e.file_name().to_string_lossy().ends_with(".sock")).collect();
    let newest = |it: Vec<&std::fs::DirEntry>| it.into_iter().max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok()).map(|e| e.path());
    let mine: Vec<_> = socks.iter().filter(|e| !display.is_empty() && e.file_name().to_string_lossy().starts_with(&format!("niri.{display}."))).collect();
    if !mine.is_empty() { newest(mine) } else { newest(socks.iter().collect()) }
}

/// One request on niri's IPC socket ("Windows", "Workspaces", "Outputs"); None when niri is not the compositor.
async fn niri(req: &str) -> Option<Value> {
    let path = niri_socket()?;
    let work = async {
        let mut s = UnixStream::connect(path).await.ok()?;
        s.write_all(format!("\"{req}\"\n").as_bytes()).await.ok()?;
        let mut buf = Vec::with_capacity(16 * 1024);
        let mut r = tokio::io::BufReader::new(s);
        tokio::io::AsyncBufReadExt::read_until(&mut r, b'\n', &mut buf).await.ok()?;
        let v: Value = serde_json::from_slice(&buf).ok()?;
        Some(v["Ok"][req].clone())
    };
    timeout(Duration::from_millis(800), work).await.ok().flatten().filter(|v| !v.is_null())
}

/// For each niri window, keyed by (app id, title): which screen it is on, whether it is visible there now, its size, and for
/// floating windows where on the screen it is (as fractions), so the phone can show just that part.
async fn placements() -> Vec<((String, String), Value)> {
    let (Some(wins), Some(spaces), Some(outs)) = (niri("Windows").await, niri("Workspaces").await, niri("Outputs").await) else { return vec![] };
    let mut out = vec![];
    for w in wins.as_array().into_iter().flatten() {
        let ws = spaces.as_array().into_iter().flatten().find(|x| x["id"] == w["workspace_id"]);
        let output = ws.and_then(|x| x["output"].as_str()).unwrap_or("").to_string();
        let logical = &outs[&output]["logical"];
        let mut p = json!({
            "niri": w["id"], "output": output,
            "visible": ws.is_some_and(|x| x["is_active"] == true),
            "active": ws.is_some_and(|x| x["active_window_id"] == w["id"]),
            "floating": w["is_floating"], "size": w["layout"]["window_size"],
        });
        if let (Some(pos), Some(ow), Some(oh)) = (w["layout"]["tile_pos_in_workspace_view"].as_array(), logical["width"].as_f64(), logical["height"].as_f64()) {
            let (x, y) = (pos[0].as_f64().unwrap_or(0.0), pos[1].as_f64().unwrap_or(0.0));
            let off = &w["layout"]["window_offset_in_tile"];
            let (dx, dy) = (off[0].as_f64().unwrap_or(0.0), off[1].as_f64().unwrap_or(0.0));
            let size = &w["layout"]["window_size"];
            let (sw, sh) = (size[0].as_f64().unwrap_or(0.0), size[1].as_f64().unwrap_or(0.0));
            if sw > 0.0 && sh > 0.0 {
                p["rect"] = json!([(x + dx) / ow, (y + dy) / oh, sw / ow, sh / oh]);
            }
        }
        out.push(((w["app_id"].as_str().unwrap_or("").to_string(), w["title"].as_str().unwrap_or("").to_string()), p));
    }
    out
}

// ---- the list ----

pub async fn list() -> impl IntoResponse {
    let tops = crate::toplevels::list();
    let connected = tops.is_some();

    let wrapped = wrapped_pids();
    let snaps: Vec<(u32, Option<Value>)> = futures_util::future::join_all(wrapped.iter().map(|p| async move { (*p, snapshot(*p, 0).await) })).await;
    // wrapper name -> (pid, snapshot); a wrapper that is not matched to a window is listed on its own below
    let mut by_wid: HashMap<String, (u32, Value)> = HashMap::new();
    let mut nameless: Vec<(u32, Value)> = vec![];
    for (p, s) in snaps {
        let Some(s) = s else { continue };
        match s["wid"].as_str().filter(|w| !w.is_empty() && *w != "000000") {
            Some(w) => {
                by_wid.insert(w.to_string(), (p, s));
            }
            None => nameless.push((p, s)),
        }
    }

    let (mut places, claude, capture) = tokio::join!(placements(), crate::claude::sessions(), crate::screen::window_capture_ok());
    // with the patched niri every window can be pictured on its own, wherever it is
    for (_, p) in places.iter_mut() {
        p["capture"] = json!(capture);
    }
    let mut cards = vec![];
    for t in tops.unwrap_or_default() {
        let mut card = json!({
            "id": format!("w{}", t.n),
            "title": clean_title(&t.title),
            "app_id": t.app_id,
            "workspace": null,
            "focused": false,
            "floating": false,
            "kind": "window",
        });
        if let Some((p, s)) = wid_of(&t.title).and_then(|w| by_wid.remove(&w)) {
            fill_terminal(&mut card, p, &s);
        }
        // the same window in niri's list: same app id and title (the invisible name makes terminal titles unique)
        if let Some(i) = places.iter().position(|(k, _)| k.0 == t.app_id && k.1 == t.title) {
            card["place"] = places.swap_remove(i).1;
        }
        cards.push(card);
    }
    // wrapped terminals no window title points to (no compositor, a terminal that hides its title, or an older wrapper)
    let mut rest: Vec<(u32, Value)> = by_wid.into_values().chain(nameless).collect();
    rest.sort_by_key(|(p, _)| *p); // a stable order, or the cards would shuffle between refreshes
    for (p, s) in rest {
        let mut card = json!({
            "id": format!("p{p}"), "title": if s["title"].as_str().unwrap_or("").is_empty() { s["cmd"].clone() } else { s["title"].clone() },
            "app_id": "terminal", "workspace": null, "focused": false, "floating": false, "kind": "window",
        });
        fill_terminal(&mut card, p, &s);
        cards.push(card);
    }
    // a terminal whose shell runs Claude Code: its session (name, status, last message), so the phone can show the conversation
    for card in cards.iter_mut() {
        let Some(pid) = card["term"].as_str().and_then(term_pid) else { continue };
        if let Some(c) = claude.iter().find(|c| c["wrapper"].as_u64() == Some(pid as u64)) {
            card["claude"] = c.clone();
        }
    }
    Json(json!({"compositor": connected, "windows": cards}))
}

#[derive(Deserialize)]
pub struct ChatQ {
    after: Option<u64>,
    limit: Option<usize>,
}

/// The conversation of the Claude Code session in a mirrored terminal (see claude.rs).
pub async fn chat(Path(id): Path<String>, Query(q): Query<ChatQ>) -> Response {
    let Some(pid) = term_pid(&id) else { return StatusCode::NOT_FOUND.into_response() };
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    let sessions = crate::claude::sessions().await;
    let Some(sid) = sessions.iter().find(|c| c["wrapper"].as_u64() == Some(pid as u64)).and_then(|c| c["session"].as_str()) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    match crate::claude::chat_of(sid.to_string(), q.after, q.limit.unwrap_or(300).min(1000)).await {
        Some(v) => Json(v).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

fn fill_terminal(card: &mut Value, pid: u32, s: &Value) {
    card["kind"] = json!("terminal");
    card["term"] = json!(format!("p{pid}"));
    card["preview"] = json!(preview(s));
    card["pv"] = json!(preview_runs(s));
    card["cwd"] = s["cwd"].clone();
    card["cmd"] = s["cmd"].clone();
    card["cols"] = s["cols"].clone();
    card["rows"] = s["rows"].clone();
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
    let back = q.back.unwrap_or(200).min(5000);
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

/// The compositor's window number for a card id: `w<n>` is one directly, `p<pid>` is found through the name its wrapper wrote
/// into the window title.
async fn window_for(id: &str) -> Option<u64> {
    if let Some(n) = id.strip_prefix('w') {
        return n.parse().ok();
    }
    let wid = snapshot(term_pid(id)?, 0).await?["wid"].as_str()?.to_string();
    crate::toplevels::list()?.into_iter().find(|t| wid_of(&t.title).as_deref() == Some(wid.as_str())).map(|t| t.n)
}

pub async fn focus(Path(id): Path<String>) -> StatusCode {
    let Some(n) = window_for(&id).await else { return StatusCode::NOT_FOUND };
    // the only place homectl ever changes focus; logged so an unexpected switch can be traced to the phone or ruled out
    eprintln!("windows: bring-to-front requested for {id} (window {n})");
    if crate::toplevels::focus(n) { StatusCode::NO_CONTENT } else { StatusCode::BAD_GATEWAY }
}

pub async fn close(Path(id): Path<String>) -> StatusCode {
    if std::env::var("HOMECTL_NO_DELETE").is_ok() {
        return StatusCode::FORBIDDEN;
    }
    if let Some(n) = window_for(&id).await {
        return if crate::toplevels::close(n) { StatusCode::NO_CONTENT } else { StatusCode::BAD_GATEWAY };
    }
    // A wrapped terminal that no window title points to (a wrapper from before the window names, or a terminal that hides its
    // title): hang it up. Its shell ends and an ordinary terminal closes the window. Only pids that are live wrappers qualify.
    if let Some(pid) = term_pid(&id).filter(|p| wrapped_pids().contains(p)) {
        unsafe { libc::kill(pid as i32, libc::SIGHUP) };
        return StatusCode::NO_CONTENT;
    }
    StatusCode::NOT_FOUND
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
        assert_eq!(term_pid("w7"), None);
        assert_eq!(term_pid("p../../x"), None);
    }

    #[test]
    fn the_invisible_window_name_is_read_back_from_a_title() {
        // 0xA5C3F0 as the wrapper writes it
        let bits = "101001011100001111110000";
        let mark: String = std::iter::once('\u{2060}').chain(bits.chars().map(|b| if b == '1' { '\u{200c}' } else { '\u{200b}' })).chain(std::iter::once('\u{2060}')).collect();
        let title = format!("README.md{mark}");
        assert_eq!(wid_of(&title).as_deref(), Some("a5c3f0"));
        assert_eq!(clean_title(&title), "README.md");
        assert_eq!(wid_of("plain title"), None);
        assert_eq!(wid_of("cut\u{2060}\u{200b}\u{200c}"), None);
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

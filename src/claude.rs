//! Claude Code sessions in the phone's window list: the whole conversation, not just the part of it on the screen.
//!
//! Claude Code draws on the terminal's alternate screen, so a mirrored terminal has no history at all: the conversation lives
//! in Claude Code's own transcript (`<claude dir>/projects/<project>/<session>.jsonl`, one JSON record per line). Every running
//! Claude Code also writes `<claude dir>/sessions/<pid>.json` with its session id, name and status, which is how a terminal is
//! matched to its transcript: the terminal's `homectl-pty` wrapper is an ancestor of that pid.
//!
//! Sessions of the daemon's own user are read directly. Sessions started as root (`doas claude`) are root-only files, so a small
//! helper runs as root (`homectl claude-helper --user NAME`, see packaging/homectl-claude.initd) and answers on a unix socket that
//! only that user can open. It serves a session only while its Claude Code runs inside one of that user's `homectl-pty`
//! terminals, and only the parsed conversation, never the files themselves.

use serde_json::{json, Value};
use std::{
    collections::HashMap,
    io::{BufRead, BufReader, Read, Seek, SeekFrom, Write},
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::Duration,
};

pub const HELPER_SOCKET: &str = "/run/homectl-claude.sock";

// How much of each part is kept: enough to read on a phone, not a copy of every tool's output.
const MAX_USER: usize = 8_000;
const MAX_TEXT: usize = 24_000;
const MAX_THINK: usize = 1_500;
const MAX_RESULT: usize = 1_500;
const MAX_SUMMARY: usize = 300;
/// Items kept per session; older ones are dropped (their numbers stay, so the phone can tell).
const MAX_ITEMS: usize = 3_000;

/// A running Claude Code that sits inside a homectl-pty terminal.
#[derive(Clone)]
pub struct Session {
    pub pid: u32,
    /// pid of the homectl-pty wrapper the window list knows the terminal by
    pub wrapper: u32,
    pub id: String,
    pub name: String,
    pub status: String,
    pub waiting: String,
    pub cwd: String,
    dir: PathBuf,
}

fn stat_fields(pid: u32) -> Option<(String, u32, u64)> {
    // comm is in parentheses and may contain spaces; the fields after it are space separated
    let s = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let open = s.find('(')?;
    let close = s.rfind(')')?;
    let comm = s[open + 1..close].to_string();
    let rest: Vec<&str> = s[close + 2..].split(' ').collect();
    Some((comm, rest.get(1)?.parse().ok()?, rest.get(19)?.parse().ok()?))
}

/// The homectl-pty wrapper above `pid` that belongs to `uid` (None = any owner).
fn wrapper_of(pid: u32, uid: Option<u32>) -> Option<u32> {
    let mut p = pid;
    for _ in 0..16 {
        let (comm, ppid, _) = stat_fields(p)?;
        if comm == "homectl-pty" {
            let owner = std::fs::metadata(format!("/proc/{p}")).ok()?.uid();
            return uid.is_none_or(|u| u == owner).then_some(p);
        }
        if ppid <= 1 {
            return None;
        }
        p = ppid;
    }
    None
}

/// The running sessions recorded under `dir` (a Claude config dir such as ~/.claude) that are inside a wrapper of `uid`.
pub fn sessions_in(dir: &Path, uid: Option<u32>) -> Vec<Session> {
    let Ok(rd) = std::fs::read_dir(dir.join("sessions")) else { return vec![] };
    let mut out = vec![];
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        let Some(pid) = name.strip_suffix(".json").and_then(|p| p.parse::<u32>().ok()) else { continue };
        let Ok(text) = std::fs::read_to_string(e.path()) else { continue };
        let Ok(v) = serde_json::from_str::<Value>(&text) else { continue };
        // the file outlives a crashed Claude Code, and pids are reused: the process start time must still match
        let Some((_, _, start)) = stat_fields(pid) else { continue };
        if v["procStart"].as_str().and_then(|s| s.parse::<u64>().ok()).is_some_and(|s| s != start) {
            continue;
        }
        let Some(wrapper) = wrapper_of(pid, uid) else { continue };
        let Some(id) = v["sessionId"].as_str().filter(|s| s.len() <= 64 && s.chars().all(|c| c.is_ascii_hexdigit() || c == '-')) else { continue };
        out.push(Session {
            pid,
            wrapper,
            id: id.to_string(),
            name: v["name"].as_str().unwrap_or("").to_string(),
            status: v["status"].as_str().unwrap_or("").to_string(),
            waiting: v["waitingFor"].as_str().unwrap_or("").to_string(),
            cwd: v["cwd"].as_str().unwrap_or("").to_string(),
            dir: dir.to_path_buf(),
        });
    }
    out.sort_by_key(|s| s.wrapper);
    out
}

// ---- the transcript, read as it grows ----

struct Cache {
    path: PathBuf,
    /// bytes of the file already read (only whole lines)
    offset: u64,
    /// number given to the next item
    next: u64,
    items: Vec<Value>,
    title: String,
    /// tool_use id -> tool name, so a result can say what it belongs to
    tools: HashMap<String, String>,
}

static CACHES: OnceLock<Mutex<HashMap<String, Cache>>> = OnceLock::new();

fn transcript_path(s: &Session) -> Option<PathBuf> {
    let file = format!("{}.jsonl", s.id);
    let rd = std::fs::read_dir(s.dir.join("projects")).ok()?;
    rd.flatten().map(|e| e.path().join(&file)).find(|p| p.is_file())
}

fn cut(s: &str, max: usize) -> String {
    if s.len() <= max {
        return s.to_string();
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…", &s[..end])
}

/// One line saying what a tool call does: the command, the file, the pattern.
fn tool_summary(name: &str, input: &Value) -> String {
    let pick = |k: &str| input[k].as_str().map(|s| s.to_string());
    let s = match name {
        "Bash" => pick("description").or_else(|| pick("command")),
        "Read" | "Write" | "Edit" | "NotebookEdit" => pick("file_path").or_else(|| pick("notebook_path")),
        "Grep" | "Glob" => pick("pattern"),
        "WebFetch" => pick("url"),
        "WebSearch" => pick("query"),
        "Agent" | "Task" => pick("description"),
        "Skill" => pick("skill"),
        _ => None,
    }
    .or_else(|| input.as_object()?.values().find_map(|v| v.as_str().map(|s| s.to_string())))
    .unwrap_or_default();
    cut(s.lines().next().unwrap_or(""), MAX_SUMMARY)
}

fn tool_detail(name: &str, input: &Value) -> String {
    match name {
        "Bash" => input["command"].as_str().map(|s| cut(s, MAX_RESULT)).unwrap_or_default(),
        _ => String::new(),
    }
}

fn result_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(parts) => parts.iter().filter_map(|p| p["text"].as_str()).collect::<Vec<_>>().join("\n"),
        _ => String::new(),
    }
}

/// What a person typed, without the wrappers Claude Code stores around commands; None for lines that are not for reading.
fn user_text(s: &str) -> Option<(&'static str, String)> {
    let t = s.trim();
    if t.starts_with("<local-command-caveat>") || t.starts_with("<local-command-stdout>") || t.starts_with("<system-reminder>") || t.is_empty() {
        return None;
    }
    if let Some(rest) = t.strip_prefix("<command-name>") {
        let name = rest.split("</command-name>").next().unwrap_or("").trim();
        let args = t.split("<command-args>").nth(1).and_then(|a| a.split("</command-args>").next()).unwrap_or("").trim();
        return Some(("cmd", if args.is_empty() { name.to_string() } else { format!("{name} {args}") }));
    }
    Some(("user", cut(t, MAX_USER)))
}

fn push(c: &mut Cache, mut v: Value, ts: &Value) {
    v["i"] = json!(c.next);
    if !ts.is_null() {
        v["ts"] = ts.clone();
    }
    c.next += 1;
    c.items.push(v);
}

fn take_line(c: &mut Cache, d: &Value) {
    if d["isSidechain"] == true || d["isMeta"] == true {
        return;
    }
    let ts = &d["timestamp"];
    match d["type"].as_str().unwrap_or("") {
        "ai-title" => c.title = d["aiTitle"].as_str().unwrap_or("").to_string(),
        "user" => {
            let m = &d["message"];
            // a message that came from somewhere else (another session, a background agent) rather than from the person
            // (what you type is marked {"kind":"human"} by newer Claude Code, older versions leave it out)
            let other = d.get("origin").is_some_and(|o| !o.is_null() && o["kind"] != "human");
            match &m["content"] {
                Value::String(s) => {
                    if let Some((k, t)) = user_text(s) {
                        push(c, json!({ "k": if other { "note" } else { k }, "t": if other { cut(&t, MAX_RESULT) } else { t } }), ts);
                    }
                }
                Value::Array(parts) => {
                    for p in parts {
                        match p["type"].as_str().unwrap_or("") {
                            "text" => {
                                if let Some((k, t)) = p["text"].as_str().and_then(user_text) {
                                    push(c, json!({ "k": if other { "note" } else { k }, "t": t }), ts);
                                }
                            }
                            "tool_result" => {
                                let id = p["tool_use_id"].as_str().unwrap_or("").to_string();
                                let name = c.tools.get(&id).cloned().unwrap_or_default();
                                let t = cut(result_text(&p["content"]).trim(), MAX_RESULT);
                                push(c, json!({ "k": "result", "id": id, "name": name, "t": t, "err": p["is_error"] == true }), ts);
                            }
                            "image" => push(c, json!({ "k": "user", "t": "[image]" }), ts),
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        "assistant" => {
            let Some(parts) = d["message"]["content"].as_array() else { return };
            for p in parts {
                match p["type"].as_str().unwrap_or("") {
                    "text" => {
                        let t = p["text"].as_str().unwrap_or("").trim();
                        if !t.is_empty() {
                            push(c, json!({ "k": "text", "t": cut(t, MAX_TEXT) }), ts);
                        }
                    }
                    "thinking" => {
                        let t = p["thinking"].as_str().unwrap_or("").trim();
                        push(c, json!({ "k": "think", "t": cut(t, MAX_THINK) }), ts);
                    }
                    "tool_use" => {
                        let name = p["name"].as_str().unwrap_or("tool").to_string();
                        let id = p["id"].as_str().unwrap_or("").to_string();
                        c.tools.insert(id.clone(), name.clone());
                        let sum = tool_summary(&name, &p["input"]);
                        let detail = tool_detail(&name, &p["input"]);
                        push(c, json!({ "k": "tool", "id": id, "name": name, "sum": sum, "detail": detail }), ts);
                    }
                    _ => {}
                }
            }
        }
        "system" => {
            if let Some(t) = d["content"].as_str().filter(|_| matches!(d["subtype"].as_str(), Some("away_summary" | "compact_boundary"))) {
                push(c, json!({ "k": "note", "t": cut(t, MAX_RESULT) }), ts);
            }
        }
        _ => {}
    }
}

/// Reads what was added to the transcript since the last call.
fn refresh(c: &mut Cache) {
    let Ok(mut f) = std::fs::File::open(&c.path) else { return };
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    if len < c.offset {
        // rewritten (or a different file): start over
        c.offset = 0;
        c.items.clear();
        c.tools.clear();
    }
    if len == c.offset || f.seek(SeekFrom::Start(c.offset)).is_err() {
        return;
    }
    let mut r = BufReader::with_capacity(64 * 1024, f.take(len - c.offset));
    let mut line = Vec::with_capacity(4096);
    loop {
        line.clear();
        let Ok(n) = r.read_until(b'\n', &mut line) else { break };
        if n == 0 || line.last() != Some(&b'\n') {
            break; // a line still being written is read next time
        }
        c.offset += n as u64;
        // most lines are request bookkeeping; only the ones that can matter are parsed
        if n > 2 && memchr_any(&line) {
            if let Ok(d) = serde_json::from_slice::<Value>(&line) {
                take_line(c, &d);
            }
        }
    }
    if c.items.len() > MAX_ITEMS {
        let drop = c.items.len() - MAX_ITEMS;
        c.items.drain(..drop);
    }
}

/// Whether a line is one of the record types that are shown. Request records can be megabytes; they are skipped unparsed.
fn memchr_any(line: &[u8]) -> bool {
    const WANT: [&[u8]; 4] = [b"\"type\":\"user\"", b"\"type\":\"assistant\"", b"\"type\":\"ai-title\"", b"\"type\":\"system\""];
    WANT.iter().any(|w| line.windows(w.len()).any(|x| x == *w))
}

fn with_cache<T>(s: &Session, f: impl FnOnce(&mut Cache) -> T) -> Option<T> {
    let path = transcript_path(s)?;
    let mut all = CACHES.get_or_init(Default::default).lock().ok()?;
    let c = all.entry(s.id.clone()).or_insert_with(|| Cache { path: path.clone(), offset: 0, next: 0, items: vec![], title: String::new(), tools: HashMap::new() });
    if c.path != path {
        c.path = path;
        c.offset = 0;
        c.items.clear();
    }
    refresh(c);
    Some(f(c))
}

/// The session as the window list shows it: name, status and the last thing said.
pub fn describe(s: &Session) -> Value {
    let (title, last, count) = with_cache(s, |c| {
        let last = c.items.iter().rev().find(|i| matches!(i["k"].as_str(), Some("text" | "user"))).map(|i| {
            json!({ "k": i["k"], "t": cut(i["t"].as_str().unwrap_or(""), 240) })
        });
        (c.title.clone(), last, c.next)
    })
    .unwrap_or_default();
    let name = if !title.is_empty() && !s.name.starts_with(&title) { title } else { s.name.clone() };
    json!({ "session": s.id, "pid": s.pid, "wrapper": s.wrapper, "name": name, "status": s.status, "waiting": s.waiting, "cwd": s.cwd, "last": last, "count": count })
}

/// Items numbered after `after` (all of the last `limit` when there are more, with `gap` set).
pub fn chat(s: &Session, after: Option<u64>, limit: usize) -> Value {
    with_cache(s, |c| {
        let first = c.items.first().and_then(|i| i["i"].as_u64()).unwrap_or(0);
        let newer: Vec<&Value> = c.items.iter().filter(|i| after.is_none_or(|a| i["i"].as_u64().unwrap_or(0) > a)).collect();
        let gap = newer.len() > limit || after.is_some_and(|a| a + 1 < first);
        let items: Vec<Value> = newer[newer.len().saturating_sub(limit)..].iter().map(|v| (*v).clone()).collect();
        json!({ "session": s.id, "title": c.title, "status": s.status, "waiting": s.waiting, "next": c.next, "gap": gap, "items": items })
    })
    .unwrap_or_else(|| json!({ "session": s.id, "next": 0, "items": [] }))
}

// ---- the root helper ----

fn uid_of(user: &str) -> Option<(u32, u32)> {
    let c = std::ffi::CString::new(user).ok()?;
    let pw = unsafe { libc::getpwnam(c.as_ptr()) };
    (!pw.is_null()).then(|| unsafe { ((*pw).pw_uid, (*pw).pw_gid) })
}

/// `homectl claude-helper --user NAME [--dir /root/.claude]`: runs as root, answers the daemon of NAME (see the module docs).
pub fn helper_main(args: &[String]) {
    let mut user = None;
    let mut dirs: Vec<PathBuf> = vec![];
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--user" => user = it.next().cloned(),
            "--dir" => dirs.extend(it.next().map(PathBuf::from)),
            _ => {}
        }
    }
    if dirs.is_empty() {
        dirs.push(PathBuf::from("/root/.claude"));
    }
    let Some((uid, gid)) = user.as_deref().and_then(uid_of) else {
        eprintln!("usage: homectl claude-helper --user NAME [--dir CLAUDE_DIR]");
        std::process::exit(2);
    };
    let _ = std::fs::remove_file(HELPER_SOCKET);
    let listener = match std::os::unix::net::UnixListener::bind(HELPER_SOCKET) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("claude-helper: {HELPER_SOCKET}: {e}");
            std::process::exit(1);
        }
    };
    // only that user may connect
    let c = std::ffi::CString::new(HELPER_SOCKET).unwrap();
    unsafe {
        libc::chown(c.as_ptr(), uid, gid);
        libc::chmod(c.as_ptr(), 0o600);
    }
    eprintln!("claude-helper: serving Claude Code sessions in {dirs:?} to uid {uid}");
    for conn in listener.incoming().flatten() {
        // the peer must be that user (or root): the socket mode already says so, this checks it once more
        let mut cred = libc::ucred { pid: 0, uid: u32::MAX, gid: 0 };
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let ok = unsafe { libc::getsockopt(std::os::fd::AsRawFd::as_raw_fd(&conn), libc::SOL_SOCKET, libc::SO_PEERCRED, &mut cred as *mut _ as *mut libc::c_void, &mut len) } == 0;
        if !ok || (cred.uid != uid && cred.uid != 0) {
            continue;
        }
        let _ = conn.set_read_timeout(Some(Duration::from_secs(2)));
        let _ = conn.set_write_timeout(Some(Duration::from_secs(5)));
        let mut line = String::new();
        let mut r = BufReader::new(&conn);
        if r.read_line(&mut line).is_err() {
            continue;
        }
        let answer = answer(&dirs, uid, &line);
        let mut w = &conn;
        let _ = w.write_all(answer.to_string().as_bytes());
        let _ = w.write_all(b"\n");
    }
}

fn answer(dirs: &[PathBuf], uid: u32, req: &str) -> Value {
    let Ok(q) = serde_json::from_str::<Value>(req) else { return json!({"error": "bad request"}) };
    let all: Vec<Session> = dirs.iter().flat_map(|d| sessions_in(d, Some(uid))).collect();
    match q["op"].as_str() {
        Some("sessions") => json!(all.iter().map(describe).collect::<Vec<_>>()),
        Some("chat") => match all.iter().find(|s| Some(s.id.as_str()) == q["session"].as_str()) {
            Some(s) => chat(s, q["after"].as_u64(), q["limit"].as_u64().unwrap_or(300).min(1000) as usize),
            None => json!({"error": "no such session"}),
        },
        _ => json!({"error": "unknown op"}),
    }
}

// ---- the daemon's side ----

async fn ask_helper(req: Value) -> Option<Value> {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
    let work = async {
        let mut s = tokio::net::UnixStream::connect(HELPER_SOCKET).await.ok()?;
        s.write_all(format!("{req}\n").as_bytes()).await.ok()?;
        let mut line = String::new();
        tokio::io::BufReader::new(s).read_line(&mut line).await.ok()?;
        serde_json::from_str::<Value>(&line).ok()
    };
    tokio::time::timeout(Duration::from_secs(3), work).await.ok()?
}

fn own_dir() -> Option<PathBuf> {
    std::env::var("HOME").ok().map(|h| PathBuf::from(h).join(".claude"))
}

fn own_sessions() -> Vec<Session> {
    let uid = unsafe { libc::getuid() };
    own_dir().map(|d| sessions_in(&d, Some(uid))).unwrap_or_default()
}

/// Every Claude Code session in this user's mirrored terminals: their own, and through the helper the ones started as root.
pub async fn sessions() -> Vec<Value> {
    let mut out: Vec<Value> = tokio::task::spawn_blocking(|| own_sessions().iter().map(describe).collect()).await.unwrap_or_default();
    if let Some(Value::Array(more)) = ask_helper(json!({"op": "sessions"})).await {
        out.extend(more);
    }
    out
}

pub async fn chat_of(session: String, after: Option<u64>, limit: usize) -> Option<Value> {
    let sid = session.clone();
    let mine = tokio::task::spawn_blocking(move || own_sessions().into_iter().find(|s| s.id == sid).map(|s| chat(&s, after, limit))).await.ok().flatten();
    if mine.is_some() {
        return mine;
    }
    let mut q = json!({"op": "chat", "session": session, "limit": limit});
    if let Some(a) = after {
        q["after"] = json!(a);
    }
    ask_helper(q).await.filter(|v| v.get("error").is_none())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cache() -> Cache {
        Cache { path: PathBuf::new(), offset: 0, next: 0, items: vec![], title: String::new(), tools: HashMap::new() }
    }

    #[test]
    fn a_conversation_becomes_items() {
        let mut c = cache();
        for l in [
            r#"{"type":"user","message":{"role":"user","content":"fix the build"},"timestamp":"t1"}"#,
            r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"<system-reminder>x</system-reminder>"}}"#,
            r#"{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"Looking."}]}}"#,
            r#"{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu1","name":"Bash","input":{"command":"cargo build","description":"Build it"}}]}}"#,
            r#"{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu1","content":"ok","is_error":false}]}}"#,
            r#"{"type":"user","message":{"content":"<command-name>/model</command-name>\n<command-args>opus</command-args>"}}"#,
            r#"{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"subagent"}]}}"#,
            r#"{"type":"ai-title","aiTitle":"Build fix"}"#,
        ] {
            take_line(&mut c, &serde_json::from_str(l).unwrap());
        }
        let kinds: Vec<&str> = c.items.iter().map(|i| i["k"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["user", "think", "text", "tool", "result", "cmd"]);
        assert_eq!(c.items[3]["sum"], "Build it");
        assert_eq!(c.items[4]["name"], "Bash");
        assert_eq!(c.items[5]["t"], "/model opus");
        assert_eq!(c.title, "Build fix");
        assert_eq!(c.items[0]["ts"], "t1");
    }

    #[test]
    fn what_you_type_is_yours_and_other_origins_are_notes() {
        let mut c = cache();
        for l in [
            r#"{"type":"user","origin":{"kind":"human"},"message":{"content":"typed"}}"#,
            r#"{"type":"user","origin":{"kind":"task-notification"},"message":{"content":"agent done"}}"#,
        ] {
            take_line(&mut c, &serde_json::from_str(l).unwrap());
        }
        let kinds: Vec<&str> = c.items.iter().map(|i| i["k"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["user", "note"]);
    }

    #[test]
    fn long_text_is_cut_on_a_character_boundary() {
        let s = "ж".repeat(10);
        let t = cut(&s, 5);
        assert!(t.ends_with('…'));
        assert!(t.len() <= 5 + '…'.len_utf8());
    }

    #[test]
    fn the_transcript_is_read_as_it_grows() {
        let dir = std::env::temp_dir().join(format!("homectl-claude-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.jsonl");
        std::fs::write(&path, "{\"type\":\"user\",\"message\":{\"content\":\"one\"}}\n{\"type\":\"user\",\"mess").unwrap();
        let mut c = cache();
        c.path = path.clone();
        refresh(&mut c);
        assert_eq!(c.items.len(), 1);
        let mut f = std::fs::OpenOptions::new().append(true).open(&path).unwrap();
        f.write_all(b"age\":{\"content\":\"two\"}}\n").unwrap();
        refresh(&mut c);
        assert_eq!(c.items.iter().map(|i| i["t"].as_str().unwrap()).collect::<Vec<_>>(), ["one", "two"]);
        std::fs::remove_dir_all(dir).unwrap();
    }
}

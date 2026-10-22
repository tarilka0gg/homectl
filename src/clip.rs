//! Clipboard history and change tracking for the app's pickers and "common clipboard" sync.
//! While someone is using the clipboard features (a request in the last two minutes) a watcher notes each change of the
//! machine's clipboard. Only text is kept, in memory only (never written to disk, cleared on restart or on request).
use serde_json::{json, Value};
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex, OnceLock,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const KEEP: usize = 30;
const MAX_LEN: usize = 200_000;

#[derive(Clone)]
struct Entry {
    id: u64,
    text: String,
    at: u64,
    source: &'static str, // "machine" (copied here) or "phone" (sent by the app)
}

struct State {
    list: VecDeque<Entry>,
    next: u64,
    last: Option<String>,
    kind: String,
}

static STATE: OnceLock<Mutex<State>> = OnceLock::new();
static REV: AtomicU64 = AtomicU64::new(1);
static LAST_USE: AtomicU64 = AtomicU64::new(0);

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn st() -> &'static Mutex<State> {
    STATE.get_or_init(|| Mutex::new(State { list: VecDeque::new(), next: 1, last: None, kind: "empty".into() }))
}

/// Starts the background watcher (idles unless the clipboard features were used recently).
pub fn start() {
    tokio::spawn(async {
        loop {
            tokio::time::sleep(Duration::from_millis(1500)).await;
            if now().saturating_sub(LAST_USE.load(Ordering::Relaxed)) > 120 {
                continue;
            }
            poll().await;
        }
    });
}

pub fn touch() {
    LAST_USE.store(now(), Ordering::Relaxed);
}

async fn poll() {
    let types = crate::ctl::clipboard_types().await;
    let has_text = types.iter().any(|t| t.starts_with("text/") || t == "UTF8_STRING" || t == "STRING");
    let kind = if has_text {
        "text".to_string()
    } else if let Some(i) = types.iter().find(|t| t.starts_with("image/")) {
        i.clone()
    } else if types.is_empty() {
        "empty".into()
    } else {
        "other".into()
    };
    let text = if has_text { crate::ctl::clipboard_get().await } else { None };
    let mut s = st().lock().unwrap();
    let changed = s.kind != kind || (has_text && text.as_deref() != s.last.as_deref());
    if !changed {
        return;
    }
    s.kind = kind;
    if let Some(t) = text.filter(|t| !t.is_empty() && t.len() <= MAX_LEN) {
        record(&mut s, t, "machine");
    } else if !has_text {
        s.last = None;
    }
    REV.fetch_add(1, Ordering::Relaxed);
}

fn record(s: &mut State, text: String, source: &'static str) {
    s.last = Some(text.clone());
    if s.list.front().is_some_and(|e| e.text == text) {
        return;
    }
    s.list.retain(|e| e.text != text);
    let id = s.next;
    s.next += 1;
    s.list.push_front(Entry { id, text, at: now(), source });
    s.list.truncate(KEEP);
}

/// The app just set the machine's clipboard: remember it without waiting for the watcher.
pub fn sent_by_phone(text: &str) {
    if text.is_empty() || text.len() > MAX_LEN {
        return;
    }
    let mut s = st().lock().unwrap();
    s.kind = "text".into();
    record(&mut s, text.to_string(), "phone");
    REV.fetch_add(1, Ordering::Relaxed);
}

/// `rev` changes whenever the machine's clipboard does, so clients can poll this cheaply and fetch the text only then.
pub fn state() -> Value {
    touch();
    let s = st().lock().unwrap();
    json!({"rev": REV.load(Ordering::Relaxed), "type": s.kind, "len": s.last.as_ref().map_or(0, |t| t.len())})
}

pub fn history() -> Value {
    touch();
    let s = st().lock().unwrap();
    json!(s.list.iter().map(|e| {
        let preview: String = e.text.chars().take(300).collect();
        json!({"id": e.id, "preview": preview, "len": e.text.len(), "lines": e.text.lines().count(), "at": e.at, "source": e.source})
    }).collect::<Vec<_>>())
}

pub fn get(id: u64) -> Option<String> {
    st().lock().unwrap().list.iter().find(|e| e.id == id).map(|e| e.text.clone())
}

pub fn forget(id: u64) {
    st().lock().unwrap().list.retain(|e| e.id != id);
}

pub fn clear() {
    let mut s = st().lock().unwrap();
    s.list.clear();
    REV.fetch_add(1, Ordering::Relaxed);
}

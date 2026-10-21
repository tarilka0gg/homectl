//! Persistent terminal sessions: each is a PTY running the user's shell that keeps running when no client is
//! attached. Clients (the app) attach over a WebSocket (binary = bytes, text = JSON such as {"resize":[c,r]}),
//! first get the recent output replayed, then the live stream. Sessions end when the shell exits or on DELETE.
use axum::extract::ws::{Message, WebSocket};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    ffi::{c_char, c_int, CString},
    os::fd::{AsRawFd, RawFd},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex,
    },
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::{io::unix::AsyncFd, sync::broadcast};

extern "C" {
    fn hc_pty_spawn(cols: c_int, rows: c_int, shell: *const c_char, home: *const c_char, pid: *mut c_int) -> c_int;
    fn hc_pty_resize(fd: c_int, cols: c_int, rows: c_int) -> c_int;
}

const RING: usize = 1 << 20; // replay buffer per session
const MAX_SESSIONS: usize = 16;

struct Pty(RawFd);

impl Drop for Pty {
    fn drop(&mut self) {
        unsafe { libc::close(self.0) };
    }
}

struct PtyRef(Arc<Pty>);

impl AsRawFd for PtyRef {
    fn as_raw_fd(&self) -> RawFd {
        self.0 .0
    }
}

pub struct Session {
    id: String,
    name: Mutex<String>,
    created: u64,
    pid: i32,
    pty: Arc<Pty>,
    ring: Mutex<VecDeque<u8>>,
    tx: broadcast::Sender<Option<Vec<u8>>>,
    alive: AtomicBool,
    clients: AtomicUsize,
}

#[derive(Default)]
pub struct Sessions(Mutex<HashMap<String, Arc<Session>>>);

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 32 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

impl Sessions {
    pub fn list(&self) -> Value {
        let map = self.0.lock().unwrap();
        let mut v: Vec<&Arc<Session>> = map.values().collect();
        v.sort_by_key(|s| s.created);
        json!(v.iter().map(|s| json!({"id": s.id, "name": *s.name.lock().unwrap(), "created": s.created, "clients": s.clients.load(Ordering::Relaxed)})).collect::<Vec<_>>())
    }

    pub fn rename(&self, id: &str, name: &str) -> bool {
        self.0.lock().unwrap().get(id).map(|s| *s.name.lock().unwrap() = name.chars().take(40).collect()).is_some()
    }

    pub fn kill(&self, id: &str) -> bool {
        let s = self.0.lock().unwrap().remove(id);
        s.map(|s| unsafe { libc::kill(s.pid, libc::SIGHUP) }).is_some()
    }

    fn get_or_create(self: &Arc<Self>, id: &str, cols: u16, rows: u16) -> Option<Arc<Session>> {
        let mut map = self.0.lock().unwrap();
        if let Some(s) = map.get(id) {
            return Some(s.clone());
        }
        if map.len() >= MAX_SESSIONS || !valid_id(id) {
            return None;
        }
        let shell = CString::new(std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".into())).ok()?;
        let home = CString::new(std::env::var("HOME").unwrap_or_else(|_| "/".into())).ok()?;
        let mut pid: c_int = 0;
        let fd = unsafe { hc_pty_spawn(cols.max(10) as c_int, rows.max(3) as c_int, shell.as_ptr(), home.as_ptr(), &mut pid) };
        if fd < 0 {
            return None;
        }
        let pty = Arc::new(Pty(fd));
        let (tx, _) = broadcast::channel(256);
        let s = Arc::new(Session {
            id: id.to_string(),
            name: Mutex::new(String::new()),
            created: SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()),
            pid,
            pty: pty.clone(),
            ring: Mutex::new(VecDeque::new()),
            tx,
            alive: AtomicBool::new(true),
            clients: AtomicUsize::new(0),
        });
        map.insert(id.to_string(), s.clone());
        let (sess, reg) = (s.clone(), self.clone());
        tokio::spawn(async move {
            #[allow(deprecated)]
            let Ok(afd) = AsyncFd::new(PtyRef(pty)) else { return };
            let mut buf = vec![0u8; 16384];
            loop {
                let Ok(mut g) = afd.readable().await else { break };
                match g.try_io(|i| {
                    let n = unsafe { libc::read(i.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len()) };
                    if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
                }) {
                    Ok(Ok(0)) | Ok(Err(_)) => break,
                    Ok(Ok(n)) => {
                        let data = buf[..n].to_vec();
                        // ring + broadcast under one lock, so an attaching client never misses or repeats bytes
                        let mut ring = sess.ring.lock().unwrap();
                        ring.extend(data.iter());
                        let excess = ring.len().saturating_sub(RING);
                        ring.drain(..excess);
                        let _ = sess.tx.send(Some(data));
                    }
                    Err(_) => continue,
                }
            }
            sess.alive.store(false, Ordering::Relaxed);
            let _ = sess.tx.send(None);
            reg.0.lock().unwrap().remove(&sess.id);
            let pid = sess.pid;
            tokio::task::spawn_blocking(move || {
                let mut st = 0;
                for _ in 0..20 {
                    if unsafe { libc::waitpid(pid, &mut st, libc::WNOHANG) } != 0 {
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
                unsafe {
                    libc::kill(pid, libc::SIGKILL);
                    libc::waitpid(pid, &mut st, 0);
                }
            });
        });
        Some(s)
    }
}

async fn write_all(pty: &Pty, data: &[u8]) {
    let mut off = 0;
    while off < data.len() {
        let n = unsafe { libc::write(pty.0, data[off..].as_ptr().cast(), data.len() - off) };
        if n >= 0 {
            off += n as usize;
        } else if std::io::Error::last_os_error().kind() == std::io::ErrorKind::WouldBlock {
            tokio::time::sleep(std::time::Duration::from_millis(2)).await;
        } else {
            return;
        }
    }
}

pub async fn attach(reg: Arc<Sessions>, mut ws: WebSocket, id: String, cols: u16, rows: u16) {
    let Some(s) = reg.get_or_create(&id, cols, rows) else { return };
    s.clients.fetch_add(1, Ordering::Relaxed);
    let (snapshot, mut rx) = {
        let ring = s.ring.lock().unwrap();
        (ring.iter().copied().collect::<Vec<u8>>(), s.tx.subscribe())
    };
    unsafe { hc_pty_resize(s.pty.0, cols.max(10) as c_int, rows.max(3) as c_int) };
    if !snapshot.is_empty() && ws.send(Message::Binary(snapshot.into())).await.is_err() {
        s.clients.fetch_sub(1, Ordering::Relaxed);
        return;
    }
    loop {
        tokio::select! {
            out = rx.recv() => match out {
                Ok(Some(d)) => { if ws.send(Message::Binary(d.into())).await.is_err() { break } }
                Ok(None) | Err(broadcast::error::RecvError::Closed) => { let _ = ws.send(Message::Close(None)).await; break }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
            },
            msg = ws.recv() => match msg {
                Some(Ok(Message::Binary(b))) => write_all(&s.pty, &b).await,
                Some(Ok(Message::Text(t))) => {
                    if let Ok(v) = serde_json::from_str::<Value>(t.as_str()) {
                        if let Some(a) = v["resize"].as_array() {
                            let (c, r) = (a.first().and_then(|x| x.as_i64()).unwrap_or(80), a.get(1).and_then(|x| x.as_i64()).unwrap_or(24));
                            unsafe { hc_pty_resize(s.pty.0, c as c_int, r as c_int) };
                        }
                    } else {
                        write_all(&s.pty, t.as_str().as_bytes()).await;
                    }
                }
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                _ => {}
            },
        }
    }
    s.clients.fetch_sub(1, Ordering::Relaxed);
}

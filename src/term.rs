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
        atomic::{AtomicU64, Ordering},
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

#[derive(Clone)]
enum Ev {
    Data(Vec<u8>),
    Size(u16, u16),
    End,
}

#[derive(Clone)]
enum Chunk {
    Data(Vec<u8>),
    Size(u16, u16),
}

/// Recent output, kept together with the terminal sizes it was written at, so a client that attaches later
/// can replay it at the right widths (and let its own terminal reflow) instead of seeing garbled wrapping.
#[derive(Default)]
struct Ring {
    chunks: VecDeque<Chunk>,
    bytes: usize,
    base: Option<(u16, u16)>,
}

impl Ring {
    fn push_data(&mut self, d: Vec<u8>) {
        self.bytes += d.len();
        // a shell writes a few bytes at a time (each echoed key): small pieces join the last one, so the ring holds about RING bytes
        // of output and not a million tiny allocations that each cost more than their bytes
        if let Some(Chunk::Data(last)) = self.chunks.back_mut() {
            if last.len() + d.len() <= 4096 {
                last.extend_from_slice(&d);
                return;
            }
        }
        self.chunks.push_back(Chunk::Data(d));
        while self.bytes > RING {
            match self.chunks.pop_front() {
                Some(Chunk::Data(d)) => self.bytes -= d.len(),
                Some(Chunk::Size(c, r)) => self.base = Some((c, r)),
                None => break,
            }
        }
    }
    fn snapshot(&self) -> Vec<Chunk> {
        self.base.map(|(c, r)| Chunk::Size(c, r)).into_iter().chain(self.chunks.iter().cloned()).collect()
    }
}

pub struct Session {
    id: String,
    name: Mutex<String>,
    created: u64,
    pid: i32,
    pty: Arc<Pty>,
    ring: Mutex<Ring>,
    tx: broadcast::Sender<Ev>,
    /// What each attached client's screen can hold; the shell gets the smallest of them (like tmux).
    clients: Mutex<HashMap<u64, (u16, u16)>>,
    size: Mutex<(u16, u16)>,
}

static NEXT_CLIENT: AtomicU64 = AtomicU64::new(1);

impl Session {
    fn recompute(&self) {
        let want = {
            let c = self.clients.lock().unwrap();
            if c.is_empty() {
                return;
            }
            (c.values().map(|x| x.0).min().unwrap(), c.values().map(|x| x.1).min().unwrap())
        };
        let mut ring = self.ring.lock().unwrap();
        let mut size = self.size.lock().unwrap();
        if *size != want {
            *size = want;
            unsafe { hc_pty_resize(self.pty.0, want.0 as c_int, want.1 as c_int) };
            ring.chunks.push_back(Chunk::Size(want.0, want.1));
            let _ = self.tx.send(Ev::Size(want.0, want.1));
        }
    }
}

#[derive(Default)]
pub struct Sessions(Mutex<HashMap<String, Arc<Session>>>);

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 32 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// The login shell from the passwd database (what the user actually uses), else $SHELL.
fn login_shell() -> Option<String> {
    let uid = unsafe { libc::getuid() }.to_string();
    std::fs::read_to_string("/etc/passwd")
        .unwrap_or_default()
        .lines()
        .find_map(|l| {
            let f: Vec<&str> = l.split(':').collect();
            (f.get(2) == Some(&uid.as_str())).then(|| f.get(6).map(|s| s.to_string())).flatten()
        })
        .or_else(|| std::env::var("SHELL").ok())
}

/// Shells a session may run: the login shell first, then everything in /etc/shells that exists
/// (one entry per real binary, so /bin and /usr/bin aliases are not listed twice).
pub fn shells() -> Vec<String> {
    let mut v: Vec<String> = vec![];
    let mut real: Vec<std::path::PathBuf> = vec![];
    let mut add = |p: String| {
        let Ok(c) = std::fs::canonicalize(&p) else { return };
        if p.starts_with('/') && !real.contains(&c) {
            real.push(c);
            v.push(p);
        }
    };
    if let Some(s) = login_shell() {
        add(s);
    }
    for l in std::fs::read_to_string("/etc/shells").unwrap_or_default().lines() {
        let l = l.trim();
        if !l.is_empty() && !l.starts_with('#') {
            add(l.to_string());
        }
    }
    if v.is_empty() {
        v.push("/bin/sh".into());
    }
    v
}

pub fn shells_json() -> Value {
    let list = shells();
    json!(list.iter().enumerate().map(|(i, p)| json!({"path": p, "name": p.rsplit('/').next().unwrap_or(p), "default": i == 0})).collect::<Vec<_>>())
}

impl Sessions {
    pub fn list(&self) -> Value {
        let map = self.0.lock().unwrap();
        let mut v: Vec<&Arc<Session>> = map.values().collect();
        v.sort_by_key(|s| s.created);
        json!(v.iter().map(|s| json!({"id": s.id, "name": *s.name.lock().unwrap(), "created": s.created, "clients": s.clients.lock().unwrap().len()})).collect::<Vec<_>>())
    }

    pub fn rename(&self, id: &str, name: &str) -> bool {
        self.0.lock().unwrap().get(id).map(|s| *s.name.lock().unwrap() = name.chars().take(40).collect()).is_some()
    }

    pub fn kill(&self, id: &str) -> bool {
        let s = self.0.lock().unwrap().remove(id);
        s.map(|s| unsafe { libc::kill(s.pid, libc::SIGHUP) }).is_some()
    }

    fn get_or_create(self: &Arc<Self>, id: &str, cols: u16, rows: u16, shell: Option<&str>) -> Option<Arc<Session>> {
        let mut map = self.0.lock().unwrap();
        if let Some(s) = map.get(id) {
            return Some(s.clone());
        }
        if map.len() >= MAX_SESSIONS || !valid_id(id) {
            return None;
        }
        let allowed = shells();
        let chosen = shell.filter(|s| allowed.iter().any(|a| a == s)).map_or_else(|| allowed[0].clone(), String::from);
        let shell = CString::new(chosen).ok()?;
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
            ring: Mutex::new(Ring { chunks: VecDeque::from([Chunk::Size(cols.max(10), rows.max(3))]), ..Default::default() }),
            tx,
            clients: Mutex::new(HashMap::new()),
            size: Mutex::new((cols.max(10), rows.max(3))),
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
                        ring.push_data(data.clone());
                        let _ = sess.tx.send(Ev::Data(data));
                    }
                    Err(_) => continue,
                }
            }
            let _ = sess.tx.send(Ev::End);
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

pub async fn attach(reg: Arc<Sessions>, mut ws: WebSocket, id: String, cols: u16, rows: u16, shell: Option<String>) {
    let Some(s) = reg.get_or_create(&id, cols, rows, shell.as_deref()) else { return };
    let cid = NEXT_CLIENT.fetch_add(1, Ordering::Relaxed);
    s.clients.lock().unwrap().insert(cid, (cols.max(10), rows.max(3)));
    let (snapshot, mut rx) = {
        let ring = s.ring.lock().unwrap();
        (ring.snapshot(), s.tx.subscribe())
    };
    let mut ok = true;
    for ch in snapshot {
        let m = match ch {
            Chunk::Data(d) => Message::Binary(d.into()),
            Chunk::Size(c, r) => Message::Text(json!({"size": [c, r]}).to_string().into()),
        };
        if ws.send(m).await.is_err() {
            ok = false;
            break;
        }
    }
    if ok {
        s.recompute();
        loop {
            tokio::select! {
                out = rx.recv() => match out {
                    Ok(Ev::Data(d)) => { if ws.send(Message::Binary(d.into())).await.is_err() { break } }
                    Ok(Ev::Size(c, r)) => { if ws.send(Message::Text(json!({"size": [c, r]}).to_string().into())).await.is_err() { break } }
                    Ok(Ev::End) | Err(broadcast::error::RecvError::Closed) => { let _ = ws.send(Message::Close(None)).await; break }
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                },
                msg = ws.recv() => match msg {
                    Some(Ok(Message::Binary(b))) => write_all(&s.pty, &b).await,
                    Some(Ok(Message::Text(t))) => {
                        if let Ok(v) = serde_json::from_str::<Value>(t.as_str()) {
                            if let Some(a) = v["resize"].as_array() {
                                let c = a.first().and_then(|x| x.as_i64()).unwrap_or(80).clamp(10, 500) as u16;
                                let r = a.get(1).and_then(|x| x.as_i64()).unwrap_or(24).clamp(3, 300) as u16;
                                s.clients.lock().unwrap().insert(cid, (c, r));
                                s.recompute();
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
    }
    s.clients.lock().unwrap().remove(&cid);
    s.recompute();
}

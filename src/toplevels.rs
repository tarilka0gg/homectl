//! The open windows of the desktop, from the standard wlroots protocol `zwlr_foreign_toplevel_manager_v1`.
//!
//! Any compositor that offers it works (niri, sway, Hyprland, river, …); nothing here is specific to one of them. The protocol
//! gives a title and an app id per window and lets a client ask for it to be activated or closed. That is all homectl uses:
//! no workspaces, no focus state, no geometry. A background thread keeps the connection and the list; it reconnects by itself
//! when the compositor starts late or restarts.

use std::{
    collections::HashMap,
    os::fd::AsRawFd,
    path::PathBuf,
    sync::{mpsc, Mutex, OnceLock},
    time::Duration,
};
use wayland_client::{
    event_created_child,
    globals::{registry_queue_init, GlobalListContents},
    protocol::{wl_registry, wl_seat},
    Connection, Dispatch, Proxy, QueueHandle,
};
use wayland_protocols_wlr::foreign_toplevel::v1::client::{
    zwlr_foreign_toplevel_handle_v1::{self as handle, ZwlrForeignToplevelHandleV1},
    zwlr_foreign_toplevel_manager_v1::{self as manager, ZwlrForeignToplevelManagerV1},
};

#[derive(Clone, Debug)]
pub struct Top {
    /// Stable while the daemon runs; the protocol itself has no numeric ids.
    pub n: u64,
    pub title: String,
    pub app_id: String,
}

enum Cmd {
    Focus(u64),
    Close(u64),
}

struct Shared {
    tops: Mutex<Option<Vec<Top>>>,
    tx: Mutex<mpsc::Sender<Cmd>>,
}

static SHARED: OnceLock<Shared> = OnceLock::new();

/// Starts the background thread (once).
pub fn start() {
    SHARED.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        std::thread::Builder::new().name("toplevels".into()).spawn(move || run(rx)).ok();
        Shared { tops: Mutex::new(None), tx: Mutex::new(tx) }
    });
}

/// The windows, or None when no compositor offering the protocol is connected.
pub fn list() -> Option<Vec<Top>> {
    start();
    SHARED.get()?.tops.lock().ok()?.clone()
}

pub fn focus(n: u64) -> bool {
    start();
    SHARED.get().and_then(|s| s.tx.lock().ok()?.send(Cmd::Focus(n)).ok()).is_some()
}

pub fn close(n: u64) -> bool {
    start();
    SHARED.get().and_then(|s| s.tx.lock().ok()?.send(Cmd::Close(n)).ok()).is_some()
}

struct Entry {
    n: u64,
    handle: ZwlrForeignToplevelHandleV1,
    title: String,
    app_id: String,
    done: bool,
}

#[derive(Default)]
struct State {
    next: u64,
    entries: HashMap<u32, Entry>,
}

impl State {
    fn snapshot(&self) -> Vec<Top> {
        let mut v: Vec<Top> = self.entries.values().filter(|e| e.done).map(|e| Top { n: e.n, title: e.title.clone(), app_id: e.app_id.clone() }).collect();
        v.sort_by_key(|t| t.n);
        v
    }
}

impl Dispatch<wl_registry::WlRegistry, GlobalListContents> for State {
    fn event(_: &mut Self, _: &wl_registry::WlRegistry, _: wl_registry::Event, _: &GlobalListContents, _: &Connection, _: &QueueHandle<Self>) {}
}

impl Dispatch<wl_seat::WlSeat, ()> for State {
    fn event(_: &mut Self, _: &wl_seat::WlSeat, _: wl_seat::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {}
}

impl Dispatch<ZwlrForeignToplevelManagerV1, ()> for State {
    fn event(st: &mut Self, _: &ZwlrForeignToplevelManagerV1, ev: manager::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {
        if let manager::Event::Toplevel { toplevel } = ev {
            st.next += 1;
            st.entries.insert(toplevel.id().protocol_id(), Entry { n: st.next, handle: toplevel, title: String::new(), app_id: String::new(), done: false });
        }
    }

    event_created_child!(State, ZwlrForeignToplevelManagerV1, [
        manager::EVT_TOPLEVEL_OPCODE => (ZwlrForeignToplevelHandleV1, ()),
    ]);
}

impl Dispatch<ZwlrForeignToplevelHandleV1, ()> for State {
    fn event(st: &mut Self, h: &ZwlrForeignToplevelHandleV1, ev: handle::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {
        let id = h.id().protocol_id();
        match ev {
            handle::Event::Title { title } => {
                if let Some(e) = st.entries.get_mut(&id) {
                    e.title = title;
                }
            }
            handle::Event::AppId { app_id } => {
                if let Some(e) = st.entries.get_mut(&id) {
                    e.app_id = app_id;
                }
            }
            handle::Event::Done => {
                if let Some(e) = st.entries.get_mut(&id) {
                    e.done = true;
                }
            }
            handle::Event::Closed => {
                st.entries.remove(&id);
                h.destroy();
            }
            _ => {}
        }
    }
}

fn socket_path() -> Option<PathBuf> {
    let rt = std::env::var("XDG_RUNTIME_DIR").map(PathBuf::from).ok().or_else(|| Some(PathBuf::from(format!("/run/user/{}", unsafe { libc::getuid() }))))?;
    if let Ok(d) = std::env::var("WAYLAND_DISPLAY") {
        let p = if d.starts_with('/') { PathBuf::from(d) } else { rt.join(d) };
        if p.exists() {
            return Some(p);
        }
    }
    let mut socks: Vec<_> = std::fs::read_dir(&rt).ok()?.flatten().filter(|e| {
        let n = e.file_name().to_string_lossy().into_owned();
        n.starts_with("wayland-") && !n.ends_with(".lock")
    }).collect();
    socks.sort_by_key(|e| e.file_name());
    socks.first().map(|e| e.path())
}

fn publish(tops: Option<Vec<Top>>) {
    if let Some(s) = SHARED.get() {
        if let Ok(mut g) = s.tops.lock() {
            *g = tops;
        }
    }
}

/// One connection's lifetime; returns when it breaks.
fn session(rx: &mpsc::Receiver<Cmd>) -> Option<()> {
    let stream = std::os::unix::net::UnixStream::connect(socket_path()?).ok()?;
    let conn = Connection::from_socket(stream).ok()?;
    let (globals, mut queue) = registry_queue_init::<State>(&conn).ok()?;
    let qh = queue.handle();
    let _mgr: ZwlrForeignToplevelManagerV1 = globals.bind(&qh, 1..=3, ()).ok()?;
    let seat: Option<wl_seat::WlSeat> = globals.bind(&qh, 1..=7, ()).ok();
    let mut st = State::default();
    queue.roundtrip(&mut st).ok()?;
    publish(Some(st.snapshot()));
    loop {
        queue.dispatch_pending(&mut st).ok()?;
        while let Ok(cmd) = rx.try_recv() {
            match cmd {
                Cmd::Focus(n) => {
                    if let (Some(e), Some(seat)) = (st.entries.values().find(|e| e.n == n), seat.as_ref()) {
                        e.handle.activate(seat);
                    }
                }
                Cmd::Close(n) => {
                    if let Some(e) = st.entries.values().find(|e| e.n == n) {
                        e.handle.close();
                    }
                }
            }
        }
        conn.flush().ok()?;
        if let Some(guard) = queue.prepare_read() {
            let mut p = libc::pollfd { fd: guard.connection_fd().as_raw_fd(), events: libc::POLLIN, revents: 0 };
            let r = unsafe { libc::poll(&mut p, 1, 150) };
            if r > 0 {
                guard.read().ok()?;
            } else {
                drop(guard);
            }
        }
        queue.dispatch_pending(&mut st).ok()?;
        publish(Some(st.snapshot()));
    }
}

fn run(rx: mpsc::Receiver<Cmd>) {
    loop {
        session(&rx);
        publish(None);
        std::thread::sleep(Duration::from_secs(5));
    }
}

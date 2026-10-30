//! The desktop's screens for the phone: a picture or a live stream of one screen (or of one window on it), and the pointer and
//! keyboard, so any window can be seen and used, not only mirrored terminals.
//!
//! Only standard wlroots protocols are used: `zwlr_screencopy_manager_v1` for pictures, `zwlr_virtual_pointer_manager_v1` for
//! taps and scrolling, and `zwp_virtual_keyboard_manager_v1` for typing (see protocols/). The compositor must offer them; niri,
//! sway, Hyprland and river do. Every call opens its own short connection, except a stream, which keeps one while it runs.
//!
//! A compositor can only copy what is on a screen, so a window on a hidden workspace is brought to the front before it is shown.

use image::{imageops, RgbImage};
use std::{
    collections::HashMap,
    os::fd::{AsFd, FromRawFd, OwnedFd},
    time::{Duration, Instant},
};
use wayland_client::{
    globals::{registry_queue_init, GlobalList, GlobalListContents},
    protocol::{wl_buffer, wl_output, wl_pointer, wl_registry, wl_seat, wl_shm, wl_shm_pool},
    Connection, Dispatch, EventQueue, Proxy, QueueHandle, WEnum,
};
use wayland_protocols_wlr::{
    screencopy::v1::client::{zwlr_screencopy_frame_v1 as frame, zwlr_screencopy_manager_v1 as copyman},
    virtual_pointer::v1::client::{zwlr_virtual_pointer_manager_v1 as vpman, zwlr_virtual_pointer_v1 as vp},
};

#[allow(dead_code, non_camel_case_types, unused_unsafe, unused_variables, non_upper_case_globals, non_snake_case, unused_imports, clippy::all)]
pub mod vkbd {
    use wayland_client;
    use wayland_client::protocol::*;
    pub mod __interfaces {
        use wayland_client::protocol::__interfaces::*;
        wayland_scanner::generate_interfaces!("protocols/virtual-keyboard-unstable-v1.xml");
    }
    use self::__interfaces::*;
    wayland_scanner::generate_client_code!("protocols/virtual-keyboard-unstable-v1.xml");
}
use vkbd::{zwp_virtual_keyboard_manager_v1 as vkman, zwp_virtual_keyboard_v1 as vk};

#[derive(Default, Clone)]
struct Out {
    name: String,
    /// current mode, in the panel's own orientation
    w: i32,
    h: i32,
    transform: u32,
}

#[derive(Default)]
struct State {
    outs: HashMap<u32, Out>,
    // the frame being copied
    fmt: Option<(wl_shm::Format, u32, u32, u32)>,
    buffer_done: bool,
    y_invert: bool,
    ready: bool,
    failed: bool,
}

impl Dispatch<wl_registry::WlRegistry, GlobalListContents> for State {
    fn event(_: &mut Self, _: &wl_registry::WlRegistry, _: wl_registry::Event, _: &GlobalListContents, _: &Connection, _: &QueueHandle<Self>) {}
}

impl Dispatch<wl_output::WlOutput, u32> for State {
    fn event(s: &mut Self, _: &wl_output::WlOutput, e: wl_output::Event, id: &u32, _: &Connection, _: &QueueHandle<Self>) {
        let o = s.outs.entry(*id).or_default();
        match e {
            wl_output::Event::Geometry { transform: WEnum::Value(t), .. } => o.transform = t as u32,
            wl_output::Event::Mode { flags: WEnum::Value(f), width, height, .. } if f.contains(wl_output::Mode::Current) => {
                o.w = width;
                o.h = height;
            }
            wl_output::Event::Name { name } => o.name = name,
            _ => {}
        }
    }
}

impl Dispatch<frame::ZwlrScreencopyFrameV1, ()> for State {
    fn event(s: &mut Self, _: &frame::ZwlrScreencopyFrameV1, e: frame::Event, _: &(), _: &Connection, _: &QueueHandle<Self>) {
        match e {
            frame::Event::Buffer { format: WEnum::Value(f), width, height, stride } => s.fmt = Some((f, width, height, stride)),
            frame::Event::BufferDone => s.buffer_done = true,
            frame::Event::Flags { flags: WEnum::Value(f) } => s.y_invert = f.contains(frame::Flags::YInvert),
            frame::Event::Ready { .. } => s.ready = true,
            frame::Event::Failed => s.failed = true,
            _ => {}
        }
    }
}

wayland_client::delegate_noop!(State: ignore wl_shm::WlShm);
wayland_client::delegate_noop!(State: ignore wl_shm_pool::WlShmPool);
wayland_client::delegate_noop!(State: ignore wl_buffer::WlBuffer);
wayland_client::delegate_noop!(State: ignore wl_seat::WlSeat);
wayland_client::delegate_noop!(State: copyman::ZwlrScreencopyManagerV1);
wayland_client::delegate_noop!(State: vpman::ZwlrVirtualPointerManagerV1);
wayland_client::delegate_noop!(State: vp::ZwlrVirtualPointerV1);
wayland_client::delegate_noop!(State: vkman::ZwpVirtualKeyboardManagerV1);
wayland_client::delegate_noop!(State: vk::ZwpVirtualKeyboardV1);

struct Wl {
    conn: Connection,
    globals: GlobalList,
    q: EventQueue<State>,
    st: State,
    outputs: Vec<(u32, wl_output::WlOutput)>,
}

fn connect() -> Option<Wl> {
    let conn = Connection::connect_to_env().ok()?;
    let (globals, mut q) = registry_queue_init::<State>(&conn).ok()?;
    let qh = q.handle();
    let outputs: Vec<(u32, wl_output::WlOutput)> = globals
        .contents()
        .clone_list()
        .into_iter()
        .filter(|g| g.interface == "wl_output")
        .map(|g| (g.name, globals.registry().bind::<wl_output::WlOutput, _, _>(g.name, g.version.min(4), &qh, g.name)))
        .collect();
    let mut st = State::default();
    q.roundtrip(&mut st).ok()?;
    Some(Wl { conn, globals, q, st, outputs })
}

impl Wl {
    fn output(&self, name: &str) -> Option<(wl_output::WlOutput, Out)> {
        self.outputs.iter().find_map(|(id, o)| self.st.outs.get(id).filter(|x| x.name == name).map(|x| (o.clone(), x.clone())))
    }
}

/// Logical size of a screen as the person sees it: the panel's mode turned by its transform.
fn turned(o: &Out) -> (i32, i32) {
    if o.transform % 2 == 1 { (o.h, o.w) } else { (o.w, o.h) }
}

/// The screens: name and pixel size the way they are seen (a monitor on its side is taller than wide).
pub fn outputs() -> Vec<serde_json::Value> {
    let Some(wl) = connect() else { return vec![] };
    let mut v: Vec<serde_json::Value> = wl
        .st
        .outs
        .values()
        .filter(|o| !o.name.is_empty())
        .map(|o| {
            let (w, h) = turned(o);
            serde_json::json!({ "name": o.name, "width": w, "height": h })
        })
        .collect();
    v.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
    v
}

// ---- pictures ----

/// Shared memory for one frame, kept between the frames of a stream.
struct Shm {
    fd: OwnedFd,
    ptr: *mut u8,
    len: usize,
}

impl Shm {
    fn new(len: usize) -> Option<Shm> {
        let name = c"homectl-screen";
        let raw = unsafe { libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC) };
        if raw < 0 {
            return None;
        }
        let fd = unsafe { OwnedFd::from_raw_fd(raw) };
        if unsafe { libc::ftruncate(raw, len as libc::off_t) } != 0 {
            return None;
        }
        let ptr = unsafe { libc::mmap(std::ptr::null_mut(), len, libc::PROT_READ | libc::PROT_WRITE, libc::MAP_SHARED, raw, 0) };
        if ptr == libc::MAP_FAILED {
            return None;
        }
        Some(Shm { fd, ptr: ptr as *mut u8, len })
    }
    fn bytes(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }
}

impl Drop for Shm {
    fn drop(&mut self) {
        unsafe { libc::munmap(self.ptr as *mut libc::c_void, self.len) };
    }
}

/// Part of a screen to keep, in the screen's logical coordinates as fractions (0..1).
#[derive(Clone, Copy)]
pub struct Crop {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// Copies screens, again and again if asked; holds the connection and the frame memory.
pub struct Grabber {
    wl: Wl,
    copy: copyman::ZwlrScreencopyManagerV1,
    shm: wl_shm::WlShm,
    mem: Option<(Shm, wl_shm_pool::WlShmPool, wl_buffer::WlBuffer, (wl_shm::Format, u32, u32, u32))>,
    rgb: Vec<u8>,
    jpeg: Vec<u8>,
    /// a sample of the last frame's pixels, to skip frames where nothing changed
    last_sum: u64,
}

impl Grabber {
    pub fn new() -> Option<Grabber> {
        let wl = connect()?;
        let qh = wl.q.handle();
        let copy = wl.globals.bind::<copyman::ZwlrScreencopyManagerV1, _, _>(&qh, 1..=3, ()).ok()?;
        let shm = wl.globals.bind::<wl_shm::WlShm, _, _>(&qh, 1..=1, ()).ok()?;
        Some(Grabber { wl, copy, shm, mem: None, rgb: vec![], jpeg: vec![], last_sum: 0 })
    }

    /// One JPEG of screen `name` (or part of it), at most `max_w` pixels wide. With `skip_same`, Some(None) when the screen
    /// looks exactly as it did last time (nothing is encoded then).
    pub fn grab(&mut self, name: &str, crop: Option<Crop>, max_w: u32, quality: u8, skip_same: bool) -> Option<Option<&[u8]>> {
        let (out, info) = self.wl.output(name)?;
        let qh = self.wl.q.handle();
        let st = &mut self.wl.st;
        st.fmt = None;
        st.buffer_done = false;
        st.ready = false;
        st.failed = false;
        st.y_invert = false;
        let fr = self.copy.capture_output(0, &out, &qh, ());
        let version = self.copy.version();
        let deadline = Instant::now() + Duration::from_secs(2);
        // the compositor first says what buffer it wants (and, from version 3, that it has said everything)
        while !(st.fmt.is_some() && (version < 3 || st.buffer_done)) && !st.failed {
            if Instant::now() > deadline || self.wl.q.blocking_dispatch(st).is_err() {
                fr.destroy();
                return None;
            }
        }
        let want = st.fmt?;
        let (fmt, w, h, stride) = want;
        if self.mem.as_ref().is_none_or(|m| m.3 != want) {
            if let Some((_, pool, buf, _)) = self.mem.take() {
                buf.destroy();
                pool.destroy();
            }
            let len = (stride * h) as usize;
            let mem = Shm::new(len)?;
            let pool = self.shm.create_pool(mem.fd.as_fd(), len as i32, &qh, ());
            let buf = pool.create_buffer(0, w as i32, h as i32, stride as i32, fmt, &qh, ());
            self.mem = Some((mem, pool, buf, want));
        }
        let (mem, _, buf, _) = self.mem.as_ref()?;
        fr.copy(buf);
        while !st.ready && !st.failed {
            if Instant::now() > deadline || self.wl.q.blocking_dispatch(st).is_err() {
                fr.destroy();
                return None;
            }
        }
        fr.destroy();
        if st.failed {
            return None;
        }
        // 32-bit little-endian formats: bytes are B G R x (Xrgb/Argb) or R G B x (Xbgr/Abgr)
        let swap = match fmt {
            wl_shm::Format::Xrgb8888 | wl_shm::Format::Argb8888 => true,
            wl_shm::Format::Xbgr8888 | wl_shm::Format::Abgr8888 => false,
            _ => return None,
        };
        let px = mem.bytes();
        self.rgb.clear();
        self.rgb.reserve((w * h * 3) as usize);
        for row in 0..h {
            let r = if st.y_invert { h - 1 - row } else { row };
            let line = &px[(r * stride) as usize..(r * stride + w * 4) as usize];
            for p in line.chunks_exact(4) {
                if swap {
                    self.rgb.extend_from_slice(&[p[2], p[1], p[0]]);
                } else {
                    self.rgb.extend_from_slice(&[p[0], p[1], p[2]]);
                }
            }
        }
        // every 61st byte is enough to see that anything moved, and costs nothing next to the copy itself
        let sum = self.rgb.iter().step_by(61).fold(0xcbf2_9ce4_8422_2325u64, |a, b| (a ^ *b as u64).wrapping_mul(0x100_0000_01b3));
        if skip_same && sum == self.last_sum {
            return Some(None);
        }
        self.last_sum = sum;
        let img = RgbImage::from_raw(w, h, std::mem::take(&mut self.rgb))?;
        // the panel's orientation -> the orientation people see (wl_output transforms turn counter-clockwise; 4-7 are the same
        // turns of a mirrored picture, and a nested compositor's window is often "flipped 180", i.e. upside down)
        let img = if info.transform >= 4 { imageops::flip_horizontal(&img) } else { img };
        let img = match info.transform % 4 {
            1 => imageops::rotate90(&img),
            2 => imageops::rotate180(&img),
            3 => imageops::rotate270(&img),
            _ => img,
        };
        let img = encode(img, crop, max_w, quality, &mut self.jpeg)?;
        // give the pixel buffer back for the next frame
        self.rgb = img.into_raw();
        Some(Some(&self.jpeg))
    }
}

/// Crops, shrinks to `max_w` and encodes a picture into `out`; hands the picture back so its memory can be used again.
fn encode(mut img: RgbImage, crop: Option<Crop>, max_w: u32, quality: u8, out: &mut Vec<u8>) -> Option<RgbImage> {
        if let Some(c) = crop {
            let (iw, ih) = (img.width() as f32, img.height() as f32);
            let x = (c.x.clamp(0.0, 1.0) * iw) as u32;
            let y = (c.y.clamp(0.0, 1.0) * ih) as u32;
            let cw = ((c.w * iw) as u32).clamp(1, img.width() - x.min(img.width() - 1));
            let chh = ((c.h * ih) as u32).clamp(1, img.height() - y.min(img.height() - 1));
            img = imageops::crop_imm(&img, x, y, cw, chh).to_image();
        }
        // shrinking by a few percent costs more than it saves: the phone scales the picture anyway
        let img = if img.width() as u64 * 4 > max_w as u64 * 5 {
            let nh = (img.height() as u64 * max_w as u64 / img.width() as u64).max(1) as u32;
            imageops::thumbnail(&img, max_w, nh)
        } else {
            img
        };
        out.clear();
        // jpeg-encoder is several times faster than image's own encoder, which matters at video rates
        let (w16, h16) = (u16::try_from(img.width()).ok()?, u16::try_from(img.height()).ok()?);
        jpeg_encoder::Encoder::new(&mut *out, quality).encode(img.as_raw(), w16, h16, jpeg_encoder::ColorType::Rgb).ok()?;
        Some(img)
}

// ---- one window, wherever it is: niri's CaptureWindow action (packaging/niri/26.04-capture-window.patch) ----

/// A PAM picture (P7, RGB_ALPHA, as the niri patch writes it) as RGB.
fn pam_rgb(b: &[u8]) -> Option<RgbImage> {
    let end = b.windows(7).position(|x| x == b"ENDHDR\n")? + 7;
    let head = std::str::from_utf8(&b[..end]).ok()?;
    let field = |k: &str| head.lines().find_map(|l| l.strip_prefix(k)).and_then(|v| v.trim().parse::<u32>().ok());
    let (w, h) = (field("WIDTH ")?, field("HEIGHT ")?);
    if field("DEPTH ")? != 4 || w == 0 || h == 0 {
        return None;
    }
    let px = b.get(end..end + (w as usize * h as usize * 4))?;
    let mut rgb = Vec::with_capacity(w as usize * h as usize * 3);
    for p in px.chunks_exact(4) {
        rgb.extend_from_slice(&p[..3]);
    }
    RgbImage::from_raw(w, h, rgb)
}

/// Asks niri for a picture of window `id`; None when it can't (an unpatched niri refuses the action, so nothing else happens).
fn capture_window(sock: &std::path::Path, id: u64, file: &std::path::Path) -> Option<RgbImage> {
    use std::io::{BufRead, Write};
    let _ = std::fs::remove_file(file);
    let mut s = std::os::unix::net::UnixStream::connect(sock).ok()?;
    s.set_read_timeout(Some(Duration::from_millis(800))).ok()?;
    let req = serde_json::json!({ "Action": { "CaptureWindow": { "id": id, "path": file.to_str()? } } });
    s.write_all(format!("{req}\n").as_bytes()).ok()?;
    let mut line = String::new();
    std::io::BufReader::new(&s).read_line(&mut line).ok()?;
    if !line.starts_with("{\"Ok\"") {
        return None;
    }
    // niri writes the file from a thread and renames it into place when complete
    let t = Instant::now();
    let bytes = loop {
        if let Ok(b) = std::fs::read(file) {
            break b;
        }
        if t.elapsed() > Duration::from_secs(1) {
            return None;
        }
        std::thread::sleep(Duration::from_millis(3));
    };
    let _ = std::fs::remove_file(file);
    pam_rgb(&bytes)
}

fn capture_file(id: u64) -> Option<std::path::PathBuf> {
    let dir = std::path::PathBuf::from(std::env::var_os("XDG_RUNTIME_DIR")?).join("homectl");
    std::fs::create_dir_all(&dir).ok()?;
    Some(dir.join(format!("window-{id}-{}.pam", std::process::id())))
}

impl Drop for Grabber {
    fn drop(&mut self) {
        if let Some((_, pool, buf, _)) = self.mem.take() {
            buf.destroy();
            pool.destroy();
        }
        let _ = self.wl.conn.flush();
    }
}

// ---- pointer ----

pub enum Pointer {
    /// a left click at (x, y), as fractions of the screen
    Tap(f64, f64),
    /// a right click
    Right(f64, f64),
    /// press and hold the left button there (for dragging)
    Down(f64, f64),
    Move(f64, f64),
    Up(f64, f64),
    /// scroll at (x, y) by (dx, dy) screen pixels
    Scroll(f64, f64, f64, f64),
}

const BTN_LEFT: u32 = 0x110;
const BTN_RIGHT: u32 = 0x111;
const EXTENT: u32 = 10_000;

fn now_ms() -> u32 {
    let mut t = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut t) };
    (t.tv_sec as u64 * 1000 + t.tv_nsec as u64 / 1_000_000) as u32
}

pub fn pointer(name: &str, ev: Pointer) -> bool {
    let Some(mut wl) = connect() else { return false };
    let qh = wl.q.handle();
    let Some((out, _)) = wl.output(name) else { return false };
    let Ok(man) = wl.globals.bind::<vpman::ZwlrVirtualPointerManagerV1, _, _>(&qh, 1..=2, ()) else { return false };
    let seat = wl.globals.bind::<wl_seat::WlSeat, _, _>(&qh, 1..=1, ()).ok();
    // version 2 maps absolute positions onto one screen; version 1 only onto the whole desktop (then only one screen works right)
    let p = if man.version() >= 2 { man.create_virtual_pointer_with_output(seat.as_ref(), Some(&out), &qh, ()) } else { man.create_virtual_pointer(seat.as_ref(), &qh, ()) };
    let at = |x: f64, y: f64| {
        p.motion_absolute(now_ms(), (x.clamp(0.0, 1.0) * EXTENT as f64) as u32, (y.clamp(0.0, 1.0) * EXTENT as f64) as u32, EXTENT, EXTENT);
        p.frame();
    };
    let button = |b: u32, down: bool| {
        p.button(now_ms(), b, if down { wl_pointer::ButtonState::Pressed } else { wl_pointer::ButtonState::Released });
        p.frame();
    };
    let pause = |st: &mut State, q: &mut EventQueue<State>| {
        let _ = q.roundtrip(st);
        std::thread::sleep(Duration::from_millis(12));
    };
    match ev {
        Pointer::Tap(x, y) | Pointer::Right(x, y) => {
            let b = if matches!(ev, Pointer::Right(..)) { BTN_RIGHT } else { BTN_LEFT };
            at(x, y);
            pause(&mut wl.st, &mut wl.q);
            button(b, true);
            pause(&mut wl.st, &mut wl.q);
            button(b, false);
        }
        Pointer::Down(x, y) => {
            at(x, y);
            pause(&mut wl.st, &mut wl.q);
            button(BTN_LEFT, true);
        }
        Pointer::Move(x, y) => at(x, y),
        Pointer::Up(x, y) => {
            at(x, y);
            pause(&mut wl.st, &mut wl.q);
            button(BTN_LEFT, false);
        }
        Pointer::Scroll(x, y, dx, dy) => {
            at(x, y);
            pause(&mut wl.st, &mut wl.q);
            p.axis_source(wl_pointer::AxisSource::Finger);
            if dy != 0.0 {
                p.axis(now_ms(), wl_pointer::Axis::VerticalScroll, dy);
            }
            if dx != 0.0 {
                p.axis(now_ms(), wl_pointer::Axis::HorizontalScroll, dx);
            }
            p.frame();
            p.axis_source(wl_pointer::AxisSource::Finger);
            p.axis_stop(now_ms(), wl_pointer::Axis::VerticalScroll);
            p.axis_stop(now_ms(), wl_pointer::Axis::HorizontalScroll);
            p.frame();
        }
    }
    let ok = wl.q.roundtrip(&mut wl.st).is_ok();
    p.destroy();
    let _ = wl.conn.flush();
    ok
}

// ---- keyboard ----

/// What to type: text, or named keys such as "Return", "BackSpace", "ctrl+l", "alt+Tab".
pub enum Keys {
    Text(String),
    Named(Vec<String>),
}

/// Named keys the phone may send (X keysym names), so a request cannot put arbitrary text into the keymap.
const NAMED: &[&str] = &[
    "Return", "BackSpace", "Tab", "Escape", "Delete", "Left", "Right", "Up", "Down", "Home", "End", "Page_Up", "Page_Down", "space", "Insert",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
];

/// One key press: the keysym name and the modifier mask held with it (shift 1, control 4, alt 8, super 64).
fn plan(keys: &Keys) -> Option<Vec<(String, u32)>> {
    let mut out = vec![];
    match keys {
        Keys::Text(t) => {
            for ch in t.chars() {
                let sym = match ch {
                    '\n' | '\r' => "Return".to_string(),
                    '\t' => "Tab".to_string(),
                    c if (c as u32) < 0x20 => continue,
                    c => format!("U{:04X}", c as u32),
                };
                out.push((sym, 0));
            }
        }
        Keys::Named(list) => {
            for k in list {
                let mut mask = 0;
                let mut parts: Vec<&str> = k.split('+').collect();
                let key = parts.pop()?;
                for m in parts {
                    mask |= match m.to_ascii_lowercase().as_str() {
                        "shift" => 1,
                        "ctrl" | "control" => 4,
                        "alt" => 8,
                        "super" | "logo" => 64,
                        _ => return None,
                    };
                }
                let sym = if NAMED.contains(&key) {
                    key.to_string()
                } else if key.chars().count() == 1 {
                    format!("U{:04X}", key.chars().next()? as u32)
                } else {
                    return None;
                };
                out.push((sym, mask));
            }
        }
    }
    (out.len() <= 4000).then_some(out)
}

/// A keymap holding exactly the keys to be pressed, one key per symbol (what wtype does).
fn keymap(syms: &[String]) -> String {
    let mut k = String::from("xkb_keymap {\nxkb_keycodes \"homectl\" {\nminimum = 8;\nmaximum = 255;\n");
    for i in 0..syms.len() {
        k.push_str(&format!("<K{i}> = {};\n", i + 9));
    }
    k.push_str("};\nxkb_types \"homectl\" { include \"complete\" };\nxkb_compatibility \"homectl\" { include \"complete\" };\nxkb_symbols \"homectl\" {\n");
    for (i, s) in syms.iter().enumerate() {
        k.push_str(&format!("key <K{i}> {{[ {s} ]}};\n"));
    }
    k.push_str("};\n};\n");
    k
}

pub fn keys(keys: Keys) -> bool {
    let Some(presses) = plan(&keys) else { return false };
    if presses.is_empty() {
        return true;
    }
    let Some(mut wl) = connect() else { return false };
    let qh = wl.q.handle();
    let Ok(man) = wl.globals.bind::<vkman::ZwpVirtualKeyboardManagerV1, _, _>(&qh, 1..=1, ()) else { return false };
    let Ok(seat) = wl.globals.bind::<wl_seat::WlSeat, _, _>(&qh, 1..=1, ()) else { return false };
    let kb = man.create_virtual_keyboard(&seat, &qh, ());
    // a keymap has at most 247 keys; long text goes out in pieces, each with its own keymap
    for chunk in presses.chunks(200) {
        let mut syms: Vec<String> = vec![];
        for (s, _) in chunk {
            if !syms.contains(s) {
                syms.push(s.clone());
            }
        }
        let text = keymap(&syms);
        let Some(mem) = Shm::new(text.len() + 1) else { return false };
        unsafe { std::ptr::copy_nonoverlapping(text.as_ptr(), mem.ptr, text.len()) };
        kb.keymap(1, mem.fd.as_fd(), (text.len() + 1) as u32);
        let _ = wl.q.roundtrip(&mut wl.st);
        for (s, mask) in chunk {
            let code = syms.iter().position(|x| x == s).unwrap_or(0) as u32 + 1;
            if *mask != 0 {
                kb.modifiers(*mask, 0, 0, 0);
            }
            kb.key(now_ms(), code, 1);
            kb.key(now_ms(), code, 0);
            if *mask != 0 {
                kb.modifiers(0, 0, 0, 0);
            }
            let _ = wl.conn.flush();
            std::thread::sleep(Duration::from_millis(3));
        }
        let _ = wl.q.roundtrip(&mut wl.st);
    }
    kb.destroy();
    wl.q.roundtrip(&mut wl.st).is_ok()
}

// ---- HTTP ----

use axum::{
    extract::{Path, Query},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde::Deserialize;

#[derive(Deserialize)]
pub struct ShotQ {
    /// widest picture wanted, in pixels (the phone's screen width)
    w: Option<u32>,
    q: Option<u8>,
    /// part of the screen: "x,y,w,h" as fractions
    crop: Option<String>,
    fps: Option<u32>,
}

fn crop_of(q: &ShotQ) -> Option<Crop> {
    let v: Vec<f32> = q.crop.as_deref()?.split(',').filter_map(|x| x.parse().ok()).collect();
    (v.len() == 4 && v[2] > 0.0 && v[3] > 0.0).then(|| Crop { x: v[0], y: v[1], w: v[2], h: v[3] })
}

fn valid_output(name: &str) -> bool {
    !name.is_empty() && name.len() <= 64 && name.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
}

fn screens_off() -> bool {
    std::env::var("HOMECTL_NO_SCREEN").is_ok()
}

pub async fn list() -> Response {
    if screens_off() {
        return StatusCode::FORBIDDEN.into_response();
    }
    Json(tokio::task::spawn_blocking(outputs).await.unwrap_or_default()).into_response()
}

pub async fn frame(Path(name): Path<String>, Query(q): Query<ShotQ>) -> Response {
    if screens_off() || !valid_output(&name) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let crop = crop_of(&q);
    let (w, quality) = (q.w.unwrap_or(1280).clamp(160, 2560), q.q.unwrap_or(75).clamp(30, 95));
    let jpg = tokio::task::spawn_blocking(move || Grabber::new()?.grab(&name, crop, w, quality, false)?.map(|b| b.to_vec())).await.ok().flatten();
    match jpg {
        Some(b) => ([(header::CONTENT_TYPE, "image/jpeg"), (header::CACHE_CONTROL, "no-store")], b).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Motion JPEG of one screen, as fast as `fps` (at most 30, for video); frames where nothing changed are not sent, except one every few
/// seconds so the phone knows the stream is alive. Stops when the phone goes away.
pub async fn stream(Path(name): Path<String>, Query(q): Query<ShotQ>) -> Response {
    if screens_off() || !valid_output(&name) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let crop = crop_of(&q);
    let (w, quality) = (q.w.unwrap_or(1280).clamp(160, 2560), q.q.unwrap_or(65).clamp(30, 95));
    let gap = Duration::from_millis(1000 / q.fps.unwrap_or(6).clamp(1, 30) as u64);
    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2);
    std::thread::Builder::new()
        .name("screen-stream".into())
        .spawn(move || {
            let Some(mut g) = Grabber::new() else { return };
            let mut last_sent = Instant::now() - Duration::from_secs(60);
            loop {
                let t0 = Instant::now();
                let keepalive = last_sent.elapsed() > Duration::from_secs(4);
                match g.grab(&name, crop, w, quality, !keepalive) {
                    Some(Some(jpg)) => {
                        let mut part = format!("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n", jpg.len()).into_bytes();
                        part.extend_from_slice(jpg);
                        part.extend_from_slice(b"\r\n");
                        if tx.blocking_send(part).is_err() {
                            return;
                        }
                        last_sent = Instant::now();
                    }
                    Some(None) => {
                        if tx.is_closed() {
                            return;
                        }
                    }
                    None => return,
                }
                if let Some(rest) = gap.checked_sub(t0.elapsed()) {
                    std::thread::sleep(rest);
                }
            }
        })
        .ok();
    let body = axum::body::Body::from_stream(futures_util::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|b| (Ok::<_, std::io::Error>(b), rx)) }));
    ([(header::CONTENT_TYPE, "multipart/x-mixed-replace; boundary=frame"), (header::CACHE_CONTROL, "no-store")], body).into_response()
}

/// Whether niri can picture single windows (the patched niri); the phone then shows windows that are not on a screen too.
pub async fn window_capture_ok() -> bool {
    tokio::task::spawn_blocking(|| {
        let sock = crate::windows::niri_socket()?;
        // an id no window has: a patched niri answers Ok (and writes nothing), an unpatched one refuses the action
        let mut s = std::os::unix::net::UnixStream::connect(sock).ok()?;
        use std::io::{BufRead, Write};
        s.set_read_timeout(Some(Duration::from_millis(800))).ok()?;
        s.write_all(b"{\"Action\":{\"CaptureWindow\":{\"id\":18446744073709551615,\"path\":\"/nonexistent/x\"}}}\n").ok()?;
        let mut line = String::new();
        std::io::BufReader::new(&s).read_line(&mut line).ok()?;
        Some(line.starts_with("{\"Ok\""))
    })
    .await
    .ok()
    .flatten()
    .unwrap_or(false)
}

/// Motion JPEG of one niri window (by niri's id), on any workspace and with the monitors off; only sent when it changed.
pub async fn window_stream(Path(id): Path<u64>, Query(q): Query<ShotQ>) -> Response {
    if screens_off() {
        return StatusCode::FORBIDDEN.into_response();
    }
    let (Some(sock), Some(file)) = (crate::windows::niri_socket(), capture_file(id)) else { return StatusCode::NOT_FOUND.into_response() };
    let (w, quality) = (q.w.unwrap_or(1280).clamp(160, 2560), q.q.unwrap_or(65).clamp(30, 95));
    let gap = Duration::from_millis(1000 / q.fps.unwrap_or(6).clamp(1, 30) as u64);
    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2);
    std::thread::Builder::new()
        .name("window-stream".into())
        .spawn(move || {
            let (mut jpeg, mut last_sum, mut misses) = (Vec::new(), 0u64, 0);
            let mut last_sent = Instant::now() - Duration::from_secs(60);
            loop {
                let t0 = Instant::now();
                match capture_window(&sock, id, &file) {
                    Some(img) => {
                        misses = 0;
                        let sum = img.as_raw().iter().step_by(61).fold(0xcbf2_9ce4_8422_2325u64, |a, b| (a ^ *b as u64).wrapping_mul(0x100_0000_01b3));
                        if sum != last_sum || last_sent.elapsed() > Duration::from_secs(4) {
                            last_sum = sum;
                            if encode(img, None, w, quality, &mut jpeg).is_some() {
                                let mut part = format!("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n", jpeg.len()).into_bytes();
                                part.extend_from_slice(&jpeg);
                                part.extend_from_slice(b"\r\n");
                                if tx.blocking_send(part).is_err() {
                                    break;
                                }
                                last_sent = Instant::now();
                            }
                        } else if tx.is_closed() {
                            break;
                        }
                    }
                    // the window closed, or niri cannot do this
                    None => {
                        misses += 1;
                        if misses >= 3 || tx.is_closed() {
                            break;
                        }
                    }
                }
                if let Some(rest) = gap.checked_sub(t0.elapsed()) {
                    std::thread::sleep(rest);
                }
            }
            let _ = std::fs::remove_file(&file);
        })
        .ok();
    let body = axum::body::Body::from_stream(futures_util::stream::unfold(rx, |mut rx| async move { rx.recv().await.map(|b| (Ok::<_, std::io::Error>(b), rx)) }));
    ([(header::CONTENT_TYPE, "multipart/x-mixed-replace; boundary=frame"), (header::CACHE_CONTROL, "no-store")], body).into_response()
}

#[derive(Deserialize)]
pub struct PointerB {
    op: String,
    x: f64,
    y: f64,
    #[serde(default)]
    dx: f64,
    #[serde(default)]
    dy: f64,
}

pub async fn pointer_post(Path(name): Path<String>, Json(b): Json<PointerB>) -> StatusCode {
    if screens_off() || std::env::var("HOMECTL_NO_INPUT").is_ok() || !valid_output(&name) {
        return StatusCode::FORBIDDEN;
    }
    let ev = match b.op.as_str() {
        "tap" => Pointer::Tap(b.x, b.y),
        "right" => Pointer::Right(b.x, b.y),
        "down" => Pointer::Down(b.x, b.y),
        "move" => Pointer::Move(b.x, b.y),
        "up" => Pointer::Up(b.x, b.y),
        "scroll" => Pointer::Scroll(b.x, b.y, b.dx.clamp(-5000.0, 5000.0), b.dy.clamp(-5000.0, 5000.0)),
        _ => return StatusCode::BAD_REQUEST,
    };
    let ok = tokio::task::spawn_blocking(move || pointer(&name, ev)).await.unwrap_or(false);
    if ok { StatusCode::NO_CONTENT } else { StatusCode::BAD_GATEWAY }
}

#[derive(Deserialize)]
pub struct KeysB {
    text: Option<String>,
    keys: Option<Vec<String>>,
}

pub async fn keys_post(Json(b): Json<KeysB>) -> StatusCode {
    if screens_off() || std::env::var("HOMECTL_NO_INPUT").is_ok() {
        return StatusCode::FORBIDDEN;
    }
    let k = match (b.text, b.keys) {
        (Some(t), _) if t.len() <= 16_000 => Keys::Text(t),
        (None, Some(k)) if k.len() <= 64 => Keys::Named(k),
        _ => return StatusCode::BAD_REQUEST,
    };
    let ok = tokio::task::spawn_blocking(move || keys(k)).await.unwrap_or(false);
    if ok { StatusCode::NO_CONTENT } else { StatusCode::BAD_REQUEST }
}

#[cfg(test)]
mod tests {
    #[test]
    fn a_pam_picture_becomes_rgb() {
        let mut b = b"P7\nWIDTH 2\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".to_vec();
        b.extend_from_slice(&[1, 2, 3, 255, 4, 5, 6, 255]);
        let img = super::pam_rgb(&b).unwrap();
        assert_eq!((img.width(), img.height()), (2, 1));
        assert_eq!(img.as_raw(), &[1, 2, 3, 4, 5, 6]);
        assert!(super::pam_rgb(&b[..b.len() - 1]).is_none());
    }

    use super::*;

    #[test]
    fn text_becomes_unicode_keysyms() {
        let p = plan(&Keys::Text("aЖ\n".into())).unwrap();
        assert_eq!(p, vec![("U0061".to_string(), 0), ("U0416".to_string(), 0), ("Return".to_string(), 0)]);
    }

    #[test]
    fn named_keys_with_modifiers() {
        let p = plan(&Keys::Named(vec!["ctrl+l".into(), "alt+Tab".into(), "Return".into()])).unwrap();
        assert_eq!(p, vec![("U006C".to_string(), 4), ("Tab".to_string(), 8), ("Return".to_string(), 0)]);
    }

    #[test]
    fn unknown_names_are_refused() {
        // a name is put into the keymap text, so only known names (or one character) may pass
        assert!(plan(&Keys::Named(vec!["};evil".into()])).is_none());
        assert!(plan(&Keys::Named(vec!["hyper+a".into()])).is_none());
    }

    #[test]
    fn the_keymap_lists_each_symbol_once() {
        let k = keymap(&["U0061".into(), "Return".into()]);
        assert!(k.contains("<K0> = 9;") && k.contains("<K1> = 10;"));
        assert!(k.contains("key <K1> {[ Return ]};"));
    }
}

//! Pairing by short code, like Bluetooth: the phone asks, the machine shows a 6-digit code
//! (desktop notification + log + `homectl pair`), the phone types it in and receives the token.
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, Instant},
};

const TTL: Duration = Duration::from_secs(120);
const MAX_TRIES: u8 = 5;

struct Req {
    code: String,
    device: String,
    at: Instant,
    tries: u8,
}

#[derive(Default)]
pub struct Pairing {
    reqs: Mutex<HashMap<String, Req>>,
    last_request: Mutex<Option<Instant>>,
}

pub fn rand_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| std::io::Read::read_exact(&mut f, &mut b))
        .expect("read /dev/urandom");
    b
}

impl Pairing {
    /// Returns the request id, or None if requests come too fast.
    pub fn request(&self, device: &str) -> Option<String> {
        {
            let mut last = self.last_request.lock().unwrap();
            if last.is_some_and(|t| t.elapsed() < Duration::from_secs(3)) {
                return None;
            }
            *last = Some(Instant::now());
        }
        let id: String = rand_bytes::<6>().iter().map(|b| format!("{b:02x}")).collect();
        let n = u32::from_le_bytes(rand_bytes::<4>()) % 1_000_000;
        let code = format!("{n:06}");
        let device: String = device.chars().filter(|c| c.is_alphanumeric() || matches!(c, ' ' | '-' | '_' | '.')).take(40).collect();
        let mut reqs = self.reqs.lock().unwrap();
        reqs.retain(|_, r| r.at.elapsed() < TTL);
        if reqs.len() >= 5 {
            return None;
        }
        eprintln!("PAIRING: code {code} requested by \"{device}\"");
        notify(&code, &device);
        reqs.insert(id.clone(), Req { code, device, at: Instant::now(), tries: 0 });
        Some(id)
    }

    /// True if the code is right. Wrong guesses burn the request after MAX_TRIES.
    pub fn confirm(&self, id: &str, code: &str) -> bool {
        let mut reqs = self.reqs.lock().unwrap();
        let Some(r) = reqs.get_mut(id) else { return false };
        if r.at.elapsed() >= TTL {
            reqs.remove(id);
            return false;
        }
        if r.code == code.trim() {
            reqs.remove(id);
            return true;
        }
        r.tries += 1;
        if r.tries >= MAX_TRIES {
            reqs.remove(id);
        }
        false
    }

    pub fn pending(&self) -> Value {
        let reqs = self.reqs.lock().unwrap();
        json!(reqs
            .values()
            .filter(|r| r.at.elapsed() < TTL)
            .map(|r| json!({"device": r.device, "code": r.code, "expires_in": (TTL - r.at.elapsed()).as_secs()}))
            .collect::<Vec<_>>())
    }
}

fn notify(code: &str, device: &str) {
    let uid = unsafe { libc::getuid() };
    let mut c = std::process::Command::new("notify-send");
    c.args(["-u", "critical", "-a", "homectl", "homectl pairing", &format!("Code {code} for {device}")]);
    if std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_none() {
        c.env("DBUS_SESSION_BUS_ADDRESS", format!("unix:path=/run/user/{uid}/bus"));
    }
    let _ = c.spawn().map(|mut ch| std::thread::spawn(move || ch.wait()));
}

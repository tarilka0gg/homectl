//! Running things as root with the user's own `doas` password, for machines that have no `permit nopass` rule.
//! The app keeps the password in the phone's keyring and sends it with a single request over the encrypted link;
//! here it is typed into a pseudo-terminal exactly as a person would, used once, and never stored, logged or put on
//! a command line.
use serde_json::{json, Value};
use std::{
    os::{fd::FromRawFd, unix::process::CommandExt},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

pub const HEADER: &str = "x-homectl-pass";

/// Base64 (standard alphabet) of the password, as the app sends it in the `X-Homectl-Pass` header.
pub fn password_from(headers: &axum::http::HeaderMap) -> Option<String> {
    let raw = headers.get(HEADER)?.to_str().ok()?;
    let mut bits = 0u32;
    let mut n = 0;
    let mut out = vec![];
    for c in raw.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' => break,
            _ => return None,
        } as u32;
        bits = bits << 6 | v;
        n += 6;
        if n >= 8 {
            n -= 8;
            out.push((bits >> n) as u8);
            bits &= (1 << n) - 1;
        }
    }
    String::from_utf8(out).ok().filter(|p| !p.is_empty() && p.len() < 512)
}

pub struct Out {
    pub code: i32,
    pub text: String,
}

impl Out {
    pub fn wrong_password(&self) -> bool {
        self.code != 0 && self.text.contains("Authorization failed")
    }
}

/// Runs `program args…` on a fresh pseudo-terminal and answers a `password:` prompt with [password].
#[allow(unused_assignments)]
pub fn run_pty(program: &str, args: &[String], password: &str, timeout: Duration) -> Result<Out, String> {
    unsafe {
        let (mut master, mut slave) = (-1, -1);
        if libc::openpty(&mut master, &mut slave, std::ptr::null_mut(), std::ptr::null(), std::ptr::null()) != 0 {
            return Err("couldn't open a terminal".into());
        }
        let (s2, s3) = (libc::dup(slave), libc::dup(slave));
        let mut cmd = Command::new(program);
        cmd.args(args).stdin(Stdio::from_raw_fd(slave)).stdout(Stdio::from_raw_fd(s2)).stderr(Stdio::from_raw_fd(s3));
        cmd.pre_exec(|| {
            libc::setsid();
            libc::ioctl(0, libc::TIOCSCTTY as _, 0);
            Ok(())
        });
        let spawned = cmd.spawn();
        drop(cmd); // closes our copies of the slave so the master sees the end of the session
        let mut child = match spawned {
            Ok(c) => c,
            Err(e) => {
                libc::close(master);
                return Err(e.to_string());
            }
        };
        let started = Instant::now();
        let mut buf: Vec<u8> = vec![];
        let mut sent = false;
        let mut status = None;
        let mut chunk = [0u8; 4096];
        loop {
            let mut pfd = libc::pollfd { fd: master, events: libc::POLLIN, revents: 0 };
            let ready = libc::poll(&mut pfd, 1, 100);
            if ready > 0 && pfd.revents & (libc::POLLIN | libc::POLLHUP) != 0 {
                let n = libc::read(master, chunk.as_mut_ptr().cast(), chunk.len());
                if n > 0 {
                    buf.extend_from_slice(&chunk[..n as usize]);
                } else if n <= 0 && pfd.revents & libc::POLLHUP != 0 {
                    // the other side closed: collect the exit status below
                }
            }
            if !sent && String::from_utf8_lossy(&buf).to_lowercase().contains("password:") {
                let line = format!("{password}\n");
                libc::write(master, line.as_ptr().cast(), line.len());
                sent = true;
            }
            if let Ok(Some(s)) = child.try_wait() {
                status = Some(s);
                // drain what is left
                loop {
                    let mut pfd = libc::pollfd { fd: master, events: libc::POLLIN, revents: 0 };
                    if libc::poll(&mut pfd, 1, 50) <= 0 {
                        break;
                    }
                    let n = libc::read(master, chunk.as_mut_ptr().cast(), chunk.len());
                    if n <= 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n as usize]);
                }
                break;
            }
            if started.elapsed() > timeout {
                let _ = child.kill();
                let _ = child.wait();
                libc::close(master);
                return Err("timed out".into());
            }
        }
        libc::close(master);
        let mut text = String::from_utf8_lossy(&buf).replace('\r', "");
        if sent {
            text = text.replace(password, "");
        }
        // drop the prompt itself
        let text: String = text.lines().filter(|l| !l.to_lowercase().contains("password:")).collect::<Vec<_>>().join("\n");
        Ok(Out { code: status.and_then(|s| s.code()).unwrap_or(-1), text: text.trim().to_string() })
    }
}

/// `doas args…` with the password (blocking; call from `spawn_blocking`).
pub fn doas(args: Vec<String>, password: &str, timeout: Duration) -> Result<Out, String> {
    run_pty("doas", &args, password, timeout)
}

/// Checks a password without changing anything: `doas true`.
pub async fn test(password: Option<String>) -> Value {
    let Some(p) = password else {
        let ok = tokio::process::Command::new("doas").args(["-n", "true"]).output().await.is_ok_and(|o| o.status.success());
        return json!({"ok": ok, "needs_password": !ok});
    };
    match tokio::task::spawn_blocking(move || doas(vec!["true".into()], &p, Duration::from_secs(15))).await {
        Ok(Ok(o)) if o.code == 0 => json!({"ok": true}),
        Ok(Ok(o)) if o.wrong_password() => json!({"ok": false, "wrong_password": true}),
        Ok(Ok(o)) => json!({"ok": false, "error": o.text}),
        Ok(Err(e)) => json!({"ok": false, "error": e}),
        Err(_) => json!({"ok": false, "error": "failed"}),
    }
}

/// Lets this user read the CPU energy counters (root-only since the PLATYPUS fix) until the next reboot.
pub async fn allow_rapl(password: Option<String>) -> Value {
    let script = "chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj".to_string();
    match password {
        None => {
            let o = tokio::process::Command::new("doas").args(["-n", "sh", "-c", &script]).output().await;
            match o {
                Ok(o) if o.status.success() => json!({"ok": true}),
                Ok(o) => json!({"ok": false, "needs_password": String::from_utf8_lossy(&o.stderr).contains("Authorization required"), "error": String::from_utf8_lossy(&o.stderr).trim()}),
                Err(e) => json!({"ok": false, "error": e.to_string()}),
            }
        }
        Some(p) => match tokio::task::spawn_blocking(move || doas(vec!["sh".into(), "-c".into(), script], &p, Duration::from_secs(20))).await {
            Ok(Ok(o)) if o.code == 0 => json!({"ok": true}),
            Ok(Ok(o)) if o.wrong_password() => json!({"ok": false, "wrong_password": true}),
            Ok(Ok(o)) => json!({"ok": false, "error": o.text}),
            Ok(Err(e)) => json!({"ok": false, "error": e}),
            Err(_) => json!({"ok": false, "error": "failed"}),
        },
    }
}

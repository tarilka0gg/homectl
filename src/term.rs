//! Web terminal: a PTY running the user's shell, bridged to a WebSocket (binary = bytes,
//! text = JSON control such as {"resize":[cols,rows]}).
use axum::extract::ws::{Message, WebSocket};
use std::{
    ffi::{c_char, c_int, CString},
    os::fd::{AsRawFd, RawFd},
};
use tokio::io::unix::AsyncFd;

extern "C" {
    fn hc_pty_spawn(cols: c_int, rows: c_int, shell: *const c_char, home: *const c_char, pid: *mut c_int) -> c_int;
    fn hc_pty_resize(fd: c_int, cols: c_int, rows: c_int) -> c_int;
}

struct Pty(RawFd);

impl AsRawFd for Pty {
    fn as_raw_fd(&self) -> RawFd {
        self.0
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        unsafe { libc::close(self.0) };
    }
}

pub async fn run(mut ws: WebSocket, cols: u16, rows: u16) {
    let shell = CString::new(std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".into())).unwrap();
    let home = CString::new(std::env::var("HOME").unwrap_or_else(|_| "/".into())).unwrap();
    let mut pid: c_int = 0;
    let fd = unsafe { hc_pty_spawn(cols.max(10) as c_int, rows.max(3) as c_int, shell.as_ptr(), home.as_ptr(), &mut pid) };
    if fd < 0 {
        return;
    }
    #[allow(deprecated)]
    let Ok(afd) = AsyncFd::new(Pty(fd)) else { return };
    let mut buf = vec![0u8; 16384];
    'outer: loop {
        tokio::select! {
            r = afd.readable() => {
                let Ok(mut g) = r else { break };
                match g.try_io(|i| {
                    let n = unsafe { libc::read(i.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len()) };
                    if n < 0 { Err(std::io::Error::last_os_error()) } else { Ok(n as usize) }
                }) {
                    Ok(Ok(0)) | Ok(Err(_)) => break,
                    Ok(Ok(n)) => { if ws.send(Message::Binary(buf[..n].to_vec().into())).await.is_err() { break } }
                    Err(_) => continue,
                }
            }
            msg = ws.recv() => {
                let data: Vec<u8> = match msg {
                    Some(Ok(Message::Binary(b))) => b.to_vec(),
                    Some(Ok(Message::Text(t))) => {
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(t.as_str()) {
                            if let Some(a) = v["resize"].as_array() {
                                let (c, r) = (a.first().and_then(|x| x.as_i64()).unwrap_or(80), a.get(1).and_then(|x| x.as_i64()).unwrap_or(24));
                                unsafe { hc_pty_resize(fd, c as c_int, r as c_int) };
                            }
                            continue;
                        }
                        t.as_str().as_bytes().to_vec()
                    }
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    _ => continue,
                };
                let mut off = 0;
                while off < data.len() {
                    let n = unsafe { libc::write(fd, data[off..].as_ptr().cast(), data.len() - off) };
                    if n >= 0 { off += n as usize; continue }
                    if std::io::Error::last_os_error().kind() != std::io::ErrorKind::WouldBlock { break 'outer }
                    let Ok(mut g) = afd.writable().await else { break 'outer };
                    g.clear_ready();
                }
            }
        }
    }
    unsafe { libc::kill(pid, libc::SIGHUP) };
    tokio::task::spawn_blocking(move || {
        let mut status = 0;
        for _ in 0..20 {
            if unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) } != 0 {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        unsafe {
            libc::kill(pid, libc::SIGKILL);
            libc::waitpid(pid, &mut status, 0);
        }
    });
}

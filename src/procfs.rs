//! Small reads of /proc and /sys files without heap churn. The kernel reports most sysfs attributes as 4096 bytes long, so
//! `std::fs::read_to_string` reserves 4 KB for every one of them; these helpers read into a buffer on the stack instead.
use std::{fs::File, io::Read};

/// Fills `buf` from the file (until the end of the file or the end of the buffer); None when it cannot be opened or read.
fn fill(path: &str, buf: &mut [u8]) -> Option<(usize, File)> {
    let mut f = File::open(path).ok()?;
    let mut n = 0;
    while n < buf.len() {
        match f.read(&mut buf[n..]) {
            Ok(0) => break,
            Ok(k) => n += k,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return None,
        }
    }
    Some((n, f))
}

/// The start of a small file (at most `buf.len()` bytes), borrowed from `buf`; no allocation.
pub fn read_into<'a>(path: &str, buf: &'a mut [u8]) -> Option<&'a str> {
    let (n, _) = fill(path, buf)?;
    std::str::from_utf8(&buf[..n]).ok()
}

/// The whole file as a String (empty when it cannot be read), allocating only what the content needs.
pub fn read(path: &str) -> String {
    let mut b = [0u8; 512];
    let Some((n, mut f)) = fill(path, &mut b) else { return String::new() };
    if n < b.len() {
        return String::from_utf8(b[..n].to_vec()).unwrap_or_default();
    }
    // longer than the buffer: take the rest the ordinary way
    let mut v = b.to_vec();
    if f.read_to_end(&mut v).is_err() {
        return String::new();
    }
    String::from_utf8(v).unwrap_or_default()
}

/// Calls `f` with the id of every process in /proc. Walks the directory with readdir directly: `std::fs::read_dir` makes an
/// owned name for every entry (about 600 allocations on a normal desktop, every sample).
pub fn for_each_pid(mut f: impl FnMut(u32)) {
    // SAFETY: opendir/readdir/closedir on a path we own; the dirent is only read before the next readdir call.
    unsafe {
        let dir = libc::opendir(c"/proc".as_ptr());
        if dir.is_null() {
            return;
        }
        loop {
            let e = libc::readdir(dir);
            if e.is_null() {
                break;
            }
            let name = std::ffi::CStr::from_ptr((*e).d_name.as_ptr()).to_bytes();
            if name.is_empty() || !name.iter().all(u8::is_ascii_digit) {
                continue;
            }
            if let Some(pid) = std::str::from_utf8(name).ok().and_then(|n| n.parse::<u32>().ok()) {
                f(pid);
            }
        }
        libc::closedir(dir);
    }
}

/// A number from a small file such as a sysfs attribute.
pub fn num(path: &str) -> Option<f64> {
    let mut b = [0u8; 64];
    read_into(path, &mut b)?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn small_and_large_files_read_back_whole() {
        let d = std::env::temp_dir().join(format!("homectl-procfs-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let (small, big, n) = (d.join("small"), d.join("big"), d.join("num"));
        std::fs::write(&small, "hello\n").unwrap();
        let text: String = (0..300).map(|i| format!("line {i}\n")).collect();
        std::fs::write(&big, &text).unwrap();
        std::fs::write(&n, " 42500\n").unwrap();
        assert_eq!(read(small.to_str().unwrap()), "hello\n");
        assert_eq!(read(big.to_str().unwrap()), text);
        assert_eq!(read("/nonexistent/x"), "");
        assert_eq!(num(n.to_str().unwrap()), Some(42500.0));
        assert_eq!(num("/nonexistent/x"), None);
        let mut b = [0u8; 16];
        assert_eq!(read_into(small.to_str().unwrap(), &mut b), Some("hello\n"));
        let mut pids = vec![];
        for_each_pid(|p| pids.push(p));
        assert!(pids.contains(&std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
    }
}

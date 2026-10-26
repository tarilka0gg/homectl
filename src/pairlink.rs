//! Shared by the daemon (`homectl pair`) and the window (`homectl-pair`): the pairing link a phone reads from a QR code, and a
//! small client for the daemon's API on localhost. The link carries the access token, so it is a password.
#![allow(dead_code)] // the daemon uses `link` only; the window uses the rest

use serde_json::Value;
use std::{
    io::{Read, Write},
    net::TcpStream,
    time::Duration,
};

/// The `homectl://pair` link for the endpoints the daemon reports (`/api/endpoints`).
pub fn link(endpoints: &Value, token: &str) -> String {
    let mut link = format!("homectl://pair?t={token}&n={}", endpoints["name"].as_str().unwrap_or("homectl"));
    for u in endpoints["urls"].as_array().into_iter().flatten() {
        link.push_str(&format!("&u={}", u["url"].as_str().unwrap_or("")));
    }
    if let Some(fp) = endpoints["fp"].as_str().filter(|f| !f.is_empty()) {
        link.push_str(&format!("&fp={fp}"));
    }
    if let Some(rv) = endpoints["rv"].as_str().filter(|f| !f.is_empty()) {
        link.push_str(&format!("&rv={rv}"));
    }
    link
}

/// GET `path` on the daemon at 127.0.0.1:`port` and parse the JSON body; None if the daemon does not answer with 200.
pub fn get(port: u16, path: &str, token: Option<&str>) -> Option<Value> {
    let mut s = TcpStream::connect(("127.0.0.1", port)).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(3))).ok()?;
    s.set_write_timeout(Some(Duration::from_secs(3))).ok()?;
    let auth = token.map(|t| format!("Authorization: Bearer {t}\r\n")).unwrap_or_default();
    write!(s, "GET {path} HTTP/1.1\r\nHost: x\r\n{auth}Connection: close\r\n\r\n").ok()?;
    let mut buf = String::new();
    s.read_to_string(&mut buf).ok()?;
    if !buf.lines().next()?.contains(" 200 ") {
        return None;
    }
    serde_json::from_str(buf.split("\r\n\r\n").nth(1)?).ok()
}

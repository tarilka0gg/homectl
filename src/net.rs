//! Reachability: Tailscale/LAN addresses, optional Cloudflare quick tunnel, pairing link.
use serde_json::{json, Value};
use std::sync::{Arc, RwLock};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::Command,
};

pub type TunnelUrl = Arc<RwLock<Option<String>>>;

/// Keeps `cloudflared tunnel --url` running and records the public URL it prints.
/// Quick tunnels get a new random URL on every start; clients learn it from /api/endpoints.
pub fn spawn_tunnel(port: u16, url: TunnelUrl) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let bin = std::env::var("HOMECTL_CLOUDFLARED").unwrap_or_else(|_| "cloudflared".into());
        loop {
            let child = Command::new(&bin)
                .args(["tunnel", "--no-autoupdate", "--url", &format!("http://127.0.0.1:{port}")])
                .stderr(std::process::Stdio::piped())
                .kill_on_drop(true)
                .spawn();
            match child {
                Ok(mut c) => {
                    let mut lines = BufReader::new(c.stderr.take().unwrap()).lines();
                    while let Ok(Some(l)) = lines.next_line().await {
                        if let Some(u) = find_tunnel_url(&l) {
                            eprintln!("tunnel up: {u}");
                            *url.write().unwrap() = Some(u);
                        }
                    }
                    let _ = c.wait().await;
                    *url.write().unwrap() = None;
                    eprintln!("tunnel exited, restarting in 10s");
                }
                Err(e) => eprintln!("cannot start {bin}: {e}"),
            }
            tokio::time::sleep(std::time::Duration::from_secs(10)).await;
        }
    })
}

pub async fn tunnel_available() -> bool {
    let bin = std::env::var("HOMECTL_CLOUDFLARED").unwrap_or_else(|_| "cloudflared".into());
    Command::new(bin).arg("--version").output().await.is_ok_and(|o| o.status.success())
}

fn find_tunnel_url(line: &str) -> Option<String> {
    let start = line.find("https://")?;
    let end = line[start..].find(|c: char| c.is_whitespace() || c == '|').map_or(line.len(), |e| start + e);
    let u = &line[start..end];
    u.ends_with(".trycloudflare.com").then(|| u.to_string())
}

async fn out(cmd: &str, args: &[&str]) -> String {
    match Command::new(cmd).args(args).output().await {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout).into_owned(),
        _ => String::new(),
    }
}

pub async fn endpoints(port: u16, tunnel: &TunnelUrl, fp: &str) -> Value {
    let mut urls: Vec<Value> = vec![];
    if let Some(ip) = out("tailscale", &["ip", "-4"]).await.lines().next() {
        urls.push(json!({"kind": "tailscale", "url": format!("http://{ip}:{port}")}));
    }
    for l in out("ip", &["-4", "-o", "addr", "show", "scope", "global"]).await.lines() {
        let f: Vec<&str> = l.split_whitespace().collect();
        if let (Some(dev), Some(cidr)) = (f.get(1), f.get(3)) {
            if !dev.starts_with("tailscale") && !dev.starts_with("docker") && !dev.starts_with("veth") && !dev.starts_with("br-") {
                let ip = cidr.split('/').next().unwrap_or("");
                urls.push(json!({"kind": "lan", "url": format!("http://{ip}:{port}")}));
            }
        }
    }
    if let Some(u) = tunnel.read().unwrap().clone() {
        urls.push(json!({"kind": "tunnel", "url": u}));
    }
    let name = std::fs::read_to_string("/proc/sys/kernel/hostname").unwrap_or_default();
    json!({ "name": name.trim(), "urls": urls, "fp": fp })
}

/// `homectl pair`: print a homectl://pair link (and QR) for the app, using the running daemon's endpoint list.
pub async fn pair(port: u16, token: &str) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut s = match tokio::net::TcpStream::connect(("127.0.0.1", port)).await {
        Ok(s) => s,
        Err(_) => return eprintln!("daemon not running on port {port}"),
    };
    let req = format!("GET /api/endpoints HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {token}\r\nConnection: close\r\n\r\n");
    s.write_all(req.as_bytes()).await.ok();
    let mut buf = String::new();
    s.read_to_string(&mut buf).await.ok();
    let body = buf.split("\r\n\r\n").nth(1).unwrap_or("");
    let v: Value = serde_json::from_str(body).unwrap_or_default();
    let mut link = format!("homectl://pair?t={token}&n={}", v["name"].as_str().unwrap_or("homectl"));
    for u in v["urls"].as_array().into_iter().flatten() {
        link.push_str(&format!("&u={}", u["url"].as_str().unwrap_or("")));
    }
    if let Some(fp) = v["fp"].as_str().filter(|f| !f.is_empty()) {
        link.push_str(&format!("&fp={fp}"));
    }
    println!("{link}\n");
    let _ = std::process::Command::new("qrencode").args(["-t", "ANSIUTF8", &link]).status();
    // Phones that asked to pair by code appear here (also useful on servers without a desktop).
    if let Ok(mut s2) = tokio::net::TcpStream::connect(("127.0.0.1", port)).await {
        s2.write_all(b"GET /api/pair/pending HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").await.ok();
        let mut b2 = String::new();
        s2.read_to_string(&mut b2).await.ok();
        let pend: Value = serde_json::from_str(b2.split("\r\n\r\n").nth(1).unwrap_or("")).unwrap_or_default();
        for p in pend.as_array().into_iter().flatten() {
            println!("pairing request from {}: code {} (expires in {}s)", p["device"], p["code"], p["expires_in"]);
        }
    }
}

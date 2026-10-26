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
pub fn spawn_tunnel(port: u16, url: TunnelUrl, token: String) -> tokio::task::JoinHandle<()> {
    announce_loop(url.clone(), token);
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

// ---- hubs: servers that relay to this machine, so it needs no tunnel of its own while one of them is up ----
//
// The app tells a machine which server relays to it (a list of that server's LAN / tailnet addresses, kept in
// `~/.config/homectl/hubs.json`). With `HOMECTL_TUNNEL=auto` the machine then keeps its own tunnel off while a hub answers and
// has a tunnel of its own, and starts it when none does.

fn hubs_file() -> std::path::PathBuf {
    std::path::PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config/homectl/hubs.json")
}

pub fn load_hubs() -> Vec<String> {
    std::fs::read_to_string(hubs_file()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

pub fn save_hubs(list: &[String]) {
    if list.is_empty() {
        let _ = std::fs::remove_file(hubs_file());
        return;
    }
    if let Ok(t) = serde_json::to_string_pretty(list) {
        let f = hubs_file();
        let _ = std::fs::write(&f, t);
        let _ = std::fs::set_permissions(&f, std::os::unix::fs::PermissionsExt::from_mode(0o600));
    }
}

/// Only `http://<private or tailnet IPv4>:<port>`: the daemon fetches these, so nothing else is accepted.
pub fn valid_hub(u: &str) -> bool {
    let Some(rest) = u.strip_prefix("http://") else { return false };
    let Some((host, port)) = rest.split_once(':') else { return false };
    if port.parse::<u16>().is_err() {
        return false;
    }
    let Ok(ip) = host.parse::<std::net::Ipv4Addr>() else { return false };
    let o = ip.octets();
    ip.is_private() || (o[0] == 100 && (64..=127).contains(&o[1]))
}

/// A hub counts as up when it answers `/api/ping` and does not say its tunnel is down (an older daemon says nothing: assumed up).
pub async fn hub_ok(url: &str) -> bool {
    let out = Command::new("curl").args(["-s", "-m", "3", &format!("{url}/api/ping")]).stderr(std::process::Stdio::null()).output().await;
    let Ok(o) = out else { return false };
    let Ok(v) = serde_json::from_slice::<Value>(&o.stdout) else { return false };
    v["homectl"] == 1 && v["tunnel"] != false
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
    json!({ "name": name.trim(), "urls": urls, "fp": fp, "rv": rendezvous_topic(), "version": env!("CARGO_PKG_VERSION") })
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
    if let Some(rv) = v["rv"].as_str().filter(|f| !f.is_empty()) {
        link.push_str(&format!("&rv={rv}"));
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

// ---- rendezvous: how a phone that is away learns the tunnel's current address ----
//
// A free Cloudflare quick tunnel gets a new random address every time it restarts, and the phone can only be told while it is
// on the same network. So the daemon posts its current address to a private topic on the public ntfy.sh service, signed with
// the machine's access token (HMAC-SHA256 over "url|timestamp"). The phone polls that topic when nothing else answers and
// only accepts messages whose signature checks out and that are newer than the last one it used — so someone who learns the
// topic name can neither point the phone elsewhere nor make it send the token anywhere.

pub fn rendezvous_topic() -> String {
    let dir = std::path::PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config/homectl");
    let f = dir.join("rendezvous");
    if let Ok(t) = std::fs::read_to_string(&f) {
        let t = t.trim().to_string();
        if t.starts_with("homectl-") && t.len() >= 32 {
            return t;
        }
    }
    let t = format!("homectl-{}", crate::pair::rand_bytes::<16>().iter().map(|b| format!("{b:02x}")).collect::<String>());
    let _ = std::fs::create_dir_all(&dir);
    let _ = std::fs::write(&f, &t);
    let _ = std::fs::set_permissions(&f, std::os::unix::fs::PermissionsExt::from_mode(0o600));
    t
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn sign_announcement(token: &str, url: &str, ts: u64) -> String {
    let key = ring::hmac::Key::new(ring::hmac::HMAC_SHA256, token.as_bytes());
    hex(ring::hmac::sign(&key, format!("{url}|{ts}").as_bytes()).as_ref())
}

async fn announce(topic: &str, token: &str, url: &str) {
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let body = json!({"v": 1, "url": url, "ts": ts, "sig": sign_announcement(token, url, ts)}).to_string();
    let r = Command::new("curl")
        .args(["-s", "-m", "20", "-o", "/dev/null", "-w", "%{http_code}", "-H", "Title: homectl", "-H", "Priority: min", "-d", &body, &format!("https://ntfy.sh/{topic}")])
        .output()
        .await;
    match r {
        Ok(o) if o.stdout == b"200" => eprintln!("tunnel address announced"),
        Ok(o) => eprintln!("announcing the tunnel address failed (HTTP {})", String::from_utf8_lossy(&o.stdout)),
        Err(e) => eprintln!("announcing the tunnel address failed: {e}"),
    }
}

/// Posts the tunnel address when it appears or changes, and again every 25 minutes (ntfy keeps messages for 12 hours).
fn announce_loop(url: TunnelUrl, token: String) {
    static ONCE: std::sync::Once = std::sync::Once::new();
    if std::env::var("HOMECTL_NO_RENDEZVOUS").is_ok() || ONCE.is_completed() {
        return;
    }
    ONCE.call_once(|| {});
    tokio::spawn(async move {
        let topic = rendezvous_topic();
        let (mut last, mut at) = (None::<String>, std::time::Instant::now());
        loop {
            let cur = url.read().unwrap().clone();
            if let Some(u) = &cur {
                if last.as_deref() != Some(u.as_str()) || at.elapsed() > std::time::Duration::from_secs(25 * 60) {
                    announce(&topic, &token, u).await;
                    last = cur.clone();
                    at = std::time::Instant::now();
                }
            }
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_private_and_tailnet_addresses_are_accepted_as_hubs() {
        assert!(valid_hub("http://192.168.1.66:7878"));
        assert!(valid_hub("http://100.64.0.1:7878"));
        assert!(valid_hub("http://10.0.0.5:7878"));
        assert!(!valid_hub("http://8.8.8.8:7878"));
        assert!(!valid_hub("https://192.168.1.66:7878"));
        assert!(!valid_hub("http://192.168.1.66"));
        assert!(!valid_hub("http://example.com:7878"));
        assert!(!valid_hub("http://100.200.0.1:7878"));
        assert!(!valid_hub("http://192.168.1.66:7878/x"));
    }

    #[test]
    fn announcement_signature_matches_the_app_and_depends_on_everything() {
        // known answer: HMAC-SHA256(key "tok", "https://a-b.trycloudflare.com|1700000000000"), computed independently
        let s = sign_announcement("tok", "https://a-b.trycloudflare.com", 1_700_000_000_000);
        assert_eq!(s, "f58b83504398af3da478c34380cec40e24e6e570aae5b8840f78a0944ba5cd35");
        assert_ne!(s, sign_announcement("tok2", "https://a-b.trycloudflare.com", 1_700_000_000_000));
        assert_ne!(s, sign_announcement("tok", "https://evil.trycloudflare.com", 1_700_000_000_000));
        assert_ne!(s, sign_announcement("tok", "https://a-b.trycloudflare.com", 1_700_000_000_001));
    }

    #[test]
    fn only_quick_tunnel_addresses_are_picked_out_of_cloudflared_output() {
        assert_eq!(find_tunnel_url("2026 INF |  https://fun-name-here.trycloudflare.com  |").as_deref(), Some("https://fun-name-here.trycloudflare.com"));
        assert_eq!(find_tunnel_url("INF visit https://example.com for docs"), None);
        assert_eq!(find_tunnel_url("INF nothing here"), None);
    }
}

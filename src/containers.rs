//! Host mode: one daemon on a container host (Proxmox, LXC, Incus) serves every container to its own people.
//!
//! Each container gets its own access token, handed out with a one-time invite code. A phone holding it sees that container
//! as a whole machine (Monitor, Terminal, Files, start/stop) and nothing else: every other request is refused here, on the host.
//! The container's side is served by an agent, a copy of this program that joins the container's namespaces the way
//! `lxc-attach` does and drops to the container's root. So what a container's phone can reach is decided by the kernel, not by
//! path checks: the agent sees the container's files, processes and network, never the host's. The owner (the host's own
//! token) can reach all containers the same way, but only after switching that on.
//!
//! Privileged containers are refused: their root is the host's root, so nothing inside them can be kept apart from the host.
use axum::{
    body::Body,
    extract::{Path, Request, State},
    http::{header, HeaderValue, Method, StatusCode, Version},
    response::{IntoResponse, Response},
    Json,
};
use hyper_util::rt::TokioIo;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock},
    time::{Duration, Instant},
};

type S = State<Arc<crate::App>>;

const INVITE_TTL: Duration = Duration::from_secs(15 * 60);
/// Where the agents' sockets live: only root can enter it.
const RUN_DIR: &str = "/run/homectl";

#[derive(Clone, Copy, PartialEq, Debug)]
enum Kind {
    Pct,
    Lxc,
    Incus,
}

impl Kind {
    fn tag(self) -> &'static str {
        match self {
            Kind::Pct => "pct",
            Kind::Lxc => "lxc",
            Kind::Incus => "incus",
        }
    }
}

#[derive(Clone, Debug)]
struct Ct {
    /// `pct-101`, `lxc-web`, `incus-db`: stable, and safe in a URL
    id: String,
    kind: Kind,
    /// what the container manager calls it (Proxmox: the VMID)
    name: String,
    /// shown to people (Proxmox: the hostname)
    label: String,
    running: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct Tenant {
    id: String,
    token: String,
}

struct Invite {
    id: String,
    code: String,
    at: Instant,
}

struct Agent {
    sock: PathBuf,
    token: String,
    init: i32,
    alive: Arc<AtomicBool>,
}

pub struct Containers {
    /// which managers this host has (empty: host mode is off)
    kinds: Vec<Kind>,
    file: PathBuf,
    owner_marker: PathBuf,
    tenants: RwLock<Vec<Tenant>>,
    invites: Mutex<Vec<Invite>>,
    agents: tokio::sync::Mutex<HashMap<String, Agent>>,
}

fn on_path(bin: &str) -> bool {
    std::env::var("PATH").unwrap_or_else(|_| "/usr/sbin:/usr/bin:/sbin:/bin".into()).split(':').any(|d| std::path::Path::new(d).join(bin).is_file())
}

fn hex<const N: usize>() -> String {
    crate::pair::rand_bytes::<N>().iter().map(|b| format!("{b:02x}")).collect()
}

impl Containers {
    pub fn load(dir: PathBuf) -> Self {
        let mut kinds = vec![];
        // only root can enter containers; a daemon running as a user stays an ordinary machine
        if unsafe { libc::geteuid() } == 0 {
            if on_path("pct") {
                kinds.push(Kind::Pct); // Proxmox: its containers are also LXC ones, so lxc-ls would list them twice
            } else if on_path("lxc-ls") {
                kinds.push(Kind::Lxc);
            }
            if on_path("incus") {
                kinds.push(Kind::Incus);
            }
        }
        let file = dir.join("containers.json");
        let tenants = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        if !kinds.is_empty() {
            eprintln!("host mode: containers from {}", kinds.iter().map(|k| k.tag()).collect::<Vec<_>>().join(", "));
        }
        Containers { kinds, file, owner_marker: dir.join("containers-owner"), tenants: RwLock::new(tenants), invites: Default::default(), agents: Default::default() }
    }

    /// Host mode off (a container's agent never manages containers itself, even nested ones).
    pub fn off() -> Self {
        Containers { kinds: vec![], file: PathBuf::from("/nonexistent"), owner_marker: PathBuf::from("/nonexistent"), tenants: Default::default(), invites: Default::default(), agents: Default::default() }
    }

    pub fn enabled(&self) -> bool {
        !self.kinds.is_empty()
    }

    fn owner_on(&self) -> bool {
        self.owner_marker.exists()
    }

    fn save(&self, list: &[Tenant]) {
        if let Ok(t) = serde_json::to_string_pretty(list) {
            let tmp = self.file.with_extension("tmp");
            let ok = std::fs::write(&tmp, t).is_ok() && std::fs::set_permissions(&tmp, std::os::unix::fs::PermissionsExt::from_mode(0o600)).is_ok();
            if ok {
                let _ = std::fs::rename(&tmp, &self.file);
            }
        }
    }

    /// The container a token belongs to.
    pub fn tenant(&self, token: &str) -> Option<String> {
        if !self.enabled() {
            return None;
        }
        self.tenants.read().unwrap().iter().find(|t| crate::token_eq(token, &t.token)).map(|t| t.id.clone())
    }

    fn token_for(&self, id: &str) -> String {
        let mut l = self.tenants.write().unwrap();
        if let Some(t) = l.iter().find(|t| t.id == id) {
            return t.token.clone();
        }
        let token = hex::<24>();
        l.push(Tenant { id: id.to_string(), token: token.clone() });
        self.save(&l);
        token
    }

    /// A one-time code that gives a phone this container (and only it). Older codes for the same container stay valid until used or expired.
    fn invite(&self, id: &str) -> String {
        let mut inv = self.invites.lock().unwrap();
        inv.retain(|i| i.at.elapsed() < INVITE_TTL);
        loop {
            let code = format!("{:06}", u32::from_le_bytes(crate::pair::rand_bytes::<4>()) % 1_000_000);
            if !inv.iter().any(|i| i.code == code) {
                inv.push(Invite { id: id.to_string(), code: code.clone(), at: Instant::now() });
                return code;
            }
        }
    }

    /// Pairing with an invite code: the container's token and name. The code is used up.
    pub async fn redeem(&self, code: &str) -> Option<(String, String)> {
        let id = {
            let mut inv = self.invites.lock().unwrap();
            inv.retain(|i| i.at.elapsed() < INVITE_TTL);
            let at = inv.iter().position(|i| i.code == code.trim())?;
            inv.remove(at).id
        };
        let label = self.find(&id).await.map_or_else(|| id.clone(), |c| c.label);
        eprintln!("host mode: a phone paired with container {id}");
        Some((self.token_for(&id), label))
    }

    /// Takes the container away from every phone that has it (they need a new invite).
    fn reset(&self, id: &str) {
        let mut l = self.tenants.write().unwrap();
        l.retain(|t| t.id != id);
        self.save(&l);
        self.invites.lock().unwrap().retain(|i| i.id != id);
    }

    async fn list(&self) -> Vec<Ct> {
        let mut v = vec![];
        for k in &self.kinds {
            match k {
                Kind::Pct => {
                    // VMID Status Lock Name (Lock is usually empty)
                    for l in out("pct", &["list"]).await.lines().skip(1) {
                        let f: Vec<&str> = l.split_whitespace().collect();
                        if f.len() >= 3 && f[0].bytes().all(|b| b.is_ascii_digit()) {
                            v.push(Ct { id: format!("pct-{}", f[0]), kind: *k, name: f[0].into(), label: f[f.len() - 1].into(), running: f[1] == "running" });
                        }
                    }
                }
                Kind::Lxc => {
                    for l in out("lxc-ls", &["-f", "-F", "NAME,STATE"]).await.lines().skip(1) {
                        let f: Vec<&str> = l.split_whitespace().collect();
                        if f.len() >= 2 {
                            v.push(Ct { id: format!("lxc-{}", f[0]), kind: *k, name: f[0].into(), label: f[0].into(), running: f[1] == "RUNNING" });
                        }
                    }
                }
                Kind::Incus => {
                    for l in out("incus", &["list", "type=container", "--format", "csv", "-c", "ns"]).await.lines() {
                        let mut f = l.split(',');
                        if let (Some(n), Some(s)) = (f.next(), f.next()) {
                            v.push(Ct { id: format!("incus-{n}"), kind: *k, name: n.into(), label: n.into(), running: s == "RUNNING" });
                        }
                    }
                }
            }
        }
        // ids go into URLs and file names; anything odd is left out rather than escaped
        v.retain(|c| !c.name.is_empty() && c.id.len() <= 64 && c.id.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.')) && !c.name.starts_with('-'));
        v
    }

    async fn find(&self, id: &str) -> Option<Ct> {
        self.list().await.into_iter().find(|c| c.id == id)
    }

    async fn init_pid(&self, c: &Ct) -> Option<i32> {
        let pid = match c.kind {
            Kind::Pct | Kind::Lxc => out("lxc-info", &["-n", &c.name, "-p", "-H"]).await.trim().parse().ok(),
            Kind::Incus => serde_json::from_str::<Value>(&out("incus", &["query", &format!("/1.0/instances/{}/state", c.name)]).await).ok().and_then(|v| v["pid"].as_i64()).map(|p| p as i32),
        };
        pid.filter(|p| *p > 1)
    }

    async fn power(&self, c: &Ct, action: &str) -> bool {
        let n = c.name.as_str();
        let (cmd, args): (&str, Vec<&str>) = match (c.kind, action) {
            (Kind::Pct, "start" | "shutdown" | "reboot" | "stop") => ("pct", vec![action, n]),
            (Kind::Lxc, "start") => ("lxc-start", vec!["-n", n]),
            (Kind::Lxc, "shutdown") => ("lxc-stop", vec!["-n", n]),
            (Kind::Lxc, "reboot") => ("lxc-stop", vec!["-r", "-n", n]),
            (Kind::Lxc, "stop") => ("lxc-stop", vec!["-k", "-n", n]),
            (Kind::Incus, "start") => ("incus", vec!["start", n]),
            (Kind::Incus, "shutdown") => ("incus", vec!["stop", n]),
            (Kind::Incus, "reboot") => ("incus", vec!["restart", n]),
            (Kind::Incus, "stop") => ("incus", vec!["stop", "-f", n]),
            _ => return false,
        };
        if std::env::var("HOMECTL_DRYRUN").is_ok_and(|v| v == "1") {
            eprintln!("DRYRUN container {action} {}", c.id);
            return true;
        }
        eprintln!("host mode: {action} {}", c.id);
        let ok = tokio::process::Command::new(cmd).args(&args).stdin(std::process::Stdio::null()).status().await.is_ok_and(|s| s.success());
        // the old agent belongs to the old container
        self.agents.lock().await.remove(&c.id);
        ok
    }

    /// The agent serving this container, started when needed. Err says why there is none.
    async fn agent(&self, c: &Ct) -> Result<(PathBuf, String), String> {
        if !c.running {
            return Err("the container is not running".into());
        }
        let init = self.init_pid(c).await.ok_or("the container's first process wasn't found")?;
        let mut agents = self.agents.lock().await;
        if let Some(a) = agents.get(&c.id) {
            if a.alive.load(Ordering::Relaxed) && a.init == init {
                return Ok((a.sock.clone(), a.token.clone()));
            }
        }
        agents.remove(&c.id);
        if same_ns(init, "user") {
            return Err("this is a privileged container: its root is the host's root, so it can't be kept apart from the host".into());
        }
        let dir = PathBuf::from(RUN_DIR);
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        std::fs::set_permissions(&dir, std::os::unix::fs::PermissionsExt::from_mode(0o700)).map_err(|e| e.to_string())?;
        let sock = dir.join(format!("ct-{}.sock", c.id));
        let _ = std::fs::remove_file(&sock);
        let listener = std::os::unix::net::UnixListener::bind(&sock).map_err(|e| e.to_string())?;
        let token = hex::<24>();
        let lfd = std::os::fd::AsRawFd::as_raw_fd(&listener);
        let mut cmd = std::process::Command::new("/proc/self/exe");
        cmd.arg("ct-agent")
            .arg(init.to_string())
            .env_clear()
            .env("HOMECTL_AGENT_TOKEN", &token)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped());
        // the listening socket becomes the agent's fd 3 (dup2 clears close-on-exec on the copy)
        unsafe {
            std::os::unix::process::CommandExt::pre_exec(&mut cmd, move || if libc::dup2(lfd, 3) < 0 { Err(std::io::Error::last_os_error()) } else { Ok(()) });
        }
        let mut child = cmd.spawn().map_err(|e| e.to_string())?;
        drop(listener); // the agent holds it now; the socket stops answering when the agent ends
        // The agent's stderr is a pipe of ours, not the daemon's log: whatever runs inside a container (even code that took the agent over)
        // can say only a few cleaned-up words, labelled with its container, and cannot touch the log file.
        if let Some(mut err) = child.stderr.take() {
            let id = c.id.clone();
            std::thread::spawn(move || {
                use std::io::Read;
                let (mut buf, mut budget) = ([0u8; 1024], 4096usize);
                while let Ok(n) = err.read(&mut buf) {
                    if n == 0 {
                        break;
                    }
                    if budget > 0 {
                        let said: String = String::from_utf8_lossy(&buf[..n.min(budget)]).chars().map(|ch| if ch.is_control() { ' ' } else { ch }).collect();
                        eprintln!("host mode: agent {id}: {}", said.trim());
                    }
                    budget = budget.saturating_sub(n);
                }
            });
        }
        let alive = Arc::new(AtomicBool::new(true));
        let flag = alive.clone();
        let id = c.id.clone();
        std::thread::spawn(move || {
            let st = child.wait();
            flag.store(false, Ordering::Relaxed);
            eprintln!("host mode: agent for {id} ended ({})", st.map_or_else(|e| e.to_string(), |s| s.to_string()));
        });
        agents.insert(c.id.clone(), Agent { sock: sock.clone(), token: token.clone(), init, alive });
        Ok((sock, token))
    }
}

async fn out(cmd: &str, args: &[&str]) -> String {
    let r = tokio::time::timeout(Duration::from_secs(15), tokio::process::Command::new(cmd).args(args).stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null()).output()).await;
    match r {
        Ok(Ok(o)) if o.status.success() => String::from_utf8_lossy(&o.stdout).into_owned(),
        _ => String::new(),
    }
}

/// Is process `pid` in the same namespace of this type as we are?
fn same_ns(pid: i32, ns: &str) -> bool {
    use std::os::unix::fs::MetadataExt;
    match (std::fs::metadata(format!("/proc/{pid}/ns/{ns}")), std::fs::metadata(format!("/proc/self/ns/{ns}"))) {
        (Ok(a), Ok(b)) => a.ino() == b.ino() && a.dev() == b.dev(),
        _ => true, // can't tell: treat as unsafe
    }
}

fn ct_json(c: &Ct) -> Value {
    json!({"id": c.id, "kind": c.kind.tag(), "name": c.label, "running": c.running})
}

fn refuse(code: StatusCode, why: &str) -> Response {
    (code, Json(json!({"error": why}))).into_response()
}

/// Sends `req` to the agent as `pq` (path and query), with the agent's token. WebSockets (the terminal) pass straight through.
async fn forward(sock: PathBuf, token: String, mut req: Request, pq: String) -> Response {
    let upgrade = req.headers().get(header::UPGRADE).is_some_and(|v| v.as_bytes().eq_ignore_ascii_case(b"websocket"));
    // the terminal socket authenticates by query: swap the phone's token for the agent's
    let (path, query) = pq.split_once('?').unwrap_or((&pq, ""));
    let mut kept: Vec<String> = query.split('&').filter(|kv| !kv.is_empty() && !kv.starts_with("t=")).map(String::from).collect();
    if upgrade {
        kept.push(format!("t={token}"));
    }
    let uri = format!("{path}{}{}", if kept.is_empty() { "" } else { "?" }, kept.join("&"));
    let Ok(uri) = uri.parse() else { return StatusCode::BAD_REQUEST.into_response() };
    *req.uri_mut() = uri;
    *req.version_mut() = Version::HTTP_11;
    let drop: Vec<_> = req.headers().keys().filter(|k| { let k = k.as_str(); k.starts_with("cf-") || k.starts_with("x-forwarded-") || k.starts_with("x-homectl-") || k == "cdn-loop" || k == "true-client-ip" || k == "x-real-ip" || k == "forwarded" }).cloned().collect();
    for k in drop {
        req.headers_mut().remove(k);
    }
    req.headers_mut().insert(header::HOST, HeaderValue::from_static("agent"));
    if let Ok(v) = HeaderValue::from_str(&format!("Bearer {token}")) {
        req.headers_mut().insert(header::AUTHORIZATION, v);
    }
    let Ok(stream) = tokio::net::UnixStream::connect(&sock).await else { return refuse(StatusCode::BAD_GATEWAY, "the container's agent isn't answering") };
    let Ok((mut sender, conn)) = hyper::client::conn::http1::handshake::<_, Body>(TokioIo::new(stream)).await else { return StatusCode::BAD_GATEWAY.into_response() };
    tokio::spawn(conn.with_upgrades());
    let client_side = upgrade.then(|| hyper::upgrade::on(&mut req));
    let mut res = match sender.send_request(req).await {
        Ok(r) => r,
        Err(e) => return refuse(StatusCode::BAD_GATEWAY, &format!("the container's agent failed: {e}")),
    };
    if let (StatusCode::SWITCHING_PROTOCOLS, Some(client_side)) = (res.status(), client_side) {
        let agent_side = hyper::upgrade::on(&mut res);
        tokio::spawn(async move {
            if let (Ok(c), Ok(a)) = (client_side.await, agent_side.await) {
                let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(c), &mut TokioIo::new(a)).await;
            }
        });
    }
    res.map(Body::new)
}

async fn to_agent(app: &crate::App, id: &str, req: Request, pq: String) -> Response {
    let Some(c) = app.containers.find(id).await else { return refuse(StatusCode::NOT_FOUND, "no such container") };
    match app.containers.agent(&c).await {
        Ok((sock, token)) => forward(sock, token, req, pq).await,
        Err(why) => refuse(StatusCode::SERVICE_UNAVAILABLE, &why),
    }
}

/// What a container's phone may ask the agent for: the container seen as a machine.
fn agent_path(path: &str) -> bool {
    matches!(path, "/api/ping" | "/api/monitor" | "/api/glance" | "/api/stats" | "/api/procs" | "/api/term" | "/api/term/sessions" | "/api/term/shells")
        || path.starts_with("/api/procs/")
        || path.starts_with("/api/term/sessions/")
        || path.starts_with("/api/fs/")
}

/// The container itself, answered by the host: its state, and starting or stopping it (the machine's power buttons).
fn own_path(m: &Method, p: &str) -> bool {
    (m == Method::GET && p == "/api/container") || (m == Method::POST && (p.starts_with("/api/container/") || p.starts_with("/api/power/")))
}

async fn own(app: &crate::App, id: &str, p: &str) -> Response {
    let Some(c) = app.containers.find(id).await else { return refuse(StatusCode::NOT_FOUND, "the container is gone") };
    if p == "/api/container" {
        return Json(ct_json(&c)).into_response();
    }
    let action = match p.rsplit('/').next().unwrap_or("") {
        "poweroff" | "shutdown" => "shutdown",
        "reboot" => "reboot",
        "start" => "start",
        "stop" => "stop",
        _ => return refuse(StatusCode::BAD_REQUEST, "a container can start, shut down, restart or stop"),
    };
    crate::status(app.containers.power(&c, action).await).into_response()
}

/// Every request made with a container's token. `next` answers the few host routes a phone needs to connect at all.
pub async fn tenant_request(app: Arc<crate::App>, id: String, req: Request, _next: axum::middleware::Next) -> Response {
    let pq = req.extensions().get::<axum::extract::OriginalUri>().map_or_else(|| req.uri().to_string(), |o| o.0.path_and_query().map_or(o.0.path().to_string(), |p| p.as_str().to_string()));
    let path = pq.split('?').next().unwrap_or("").to_string();
    let m = req.method().clone();
    // the public tunnel is the owner's: a container's phone has the home network and Tailscale
    if crate::device::via_tunnel(req.headers()) {
        return refuse(StatusCode::FORBIDDEN, "a container can't be reached through the tunnel");
    }
    match (m, path.as_str()) {
        (Method::GET, "/api/endpoints") => {
            let mut v = crate::net::endpoints(app.port, &app.tunnel, &app.tls_fp).await;
            let name = app.containers.find(&id).await.map_or_else(|| id.clone(), |c| c.label);
            if let Some(o) = v.as_object_mut() {
                o.insert("name".into(), json!(name));
                o.insert("container".into(), json!(true));
                // the address announcements are signed with the host's token: no use to a container's phone
                o.remove("rv");
            }
            Json(v).into_response()
        }
        (m, p) if own_path(&m, p) => own(&app, &id, p).await,
        (_, p) if agent_path(p) => to_agent(&app, &id, req, pq).await,
        _ => refuse(StatusCode::FORBIDDEN, "not available for a container"),
    }
}

// ---- the owner's side ----

/// `/peer/ct-<id>/...` with the host's token: the owner reaching a container, when that is switched on.
pub async fn owner_proxy(app: Arc<crate::App>, id: &str, rest: &str, req: Request) -> Response {
    if !app.containers.enabled() {
        return StatusCode::NOT_FOUND.into_response();
    }
    if !app.containers.owner_on() {
        return refuse(StatusCode::FORBIDDEN, "the host's owner hasn't switched on access to containers");
    }
    let pq = format!("/{rest}{}", req.uri().query().map(|q| format!("?{q}")).unwrap_or_default());
    let path = pq.split('?').next().unwrap_or("").to_string();
    if own_path(req.method(), &path) {
        return own(&app, id, &path).await;
    }
    if !agent_path(&path) && path != "/api/endpoints" {
        return refuse(StatusCode::FORBIDDEN, "not available for a container");
    }
    to_agent(&app, id, req, pq).await
}

pub async fn list(State(app): S) -> Response {
    if !app.containers.enabled() {
        return refuse(StatusCode::NOT_FOUND, "this machine isn't a container host (or the daemon doesn't run as root)");
    }
    let shared: Vec<String> = app.containers.tenants.read().unwrap().iter().map(|t| t.id.clone()).collect();
    let list: Vec<Value> = app.containers.list().await.iter().map(|c| { let mut v = ct_json(c); v["shared"] = json!(shared.contains(&c.id)); v }).collect();
    Json(json!({"containers": list, "owner_access": app.containers.owner_on()})).into_response()
}

#[derive(Deserialize)]
pub struct OwnerIn {
    on: bool,
}

pub async fn owner_set(State(app): S, headers: axum::http::HeaderMap, Json(o): Json<OwnerIn>) -> StatusCode {
    // like the other security settings: never from outside, never through another machine
    if !crate::direct(&headers) || !app.containers.enabled() {
        return StatusCode::FORBIDDEN;
    }
    let m = &app.containers.owner_marker;
    let ok = if o.on { std::fs::write(m, "").is_ok() } else { !m.exists() || std::fs::remove_file(m).is_ok() };
    crate::status(ok)
}

pub async fn action(State(app): S, headers: axum::http::HeaderMap, Path((id, action)): Path<(String, String)>) -> Response {
    let Some(c) = app.containers.find(&id).await else { return refuse(StatusCode::NOT_FOUND, "no such container") };
    match action.as_str() {
        "invite" => {
            if !crate::direct(&headers) {
                return StatusCode::FORBIDDEN.into_response();
            }
            let code = app.containers.invite(&c.id);
            eprintln!("host mode: invite code made for {}", c.id);
            Json(json!({"code": code, "expires_in": INVITE_TTL.as_secs()})).into_response()
        }
        "reset" => {
            app.containers.reset(&c.id);
            StatusCode::NO_CONTENT.into_response()
        }
        "start" | "shutdown" | "reboot" | "stop" => crate::status(app.containers.power(&c, &action).await).into_response(),
        _ => StatusCode::BAD_REQUEST.into_response(),
    }
}

/// `homectl containers [invite|reset <id>]` on the host: the same as the app's owner screen, through the running daemon.
pub async fn cli(port: u16, token: &str, args: &[String]) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let (method, path) = match args {
        [] => ("GET", "/api/containers".to_string()),
        [a, id] if matches!(a.as_str(), "invite" | "reset" | "start" | "shutdown" | "reboot" | "stop") => ("POST", format!("/api/containers/{id}/{a}")),
        [a] if a == "owner-on" || a == "owner-off" => ("POST", "/api/containers/owner".to_string()),
        _ => return eprintln!("usage: homectl containers [invite|reset|start|shutdown|reboot|stop <id> | owner-on | owner-off]"),
    };
    let body = match args.first().map(String::as_str) {
        Some("owner-on") => r#"{"on":true}"#,
        Some("owner-off") => r#"{"on":false}"#,
        _ => "",
    };
    let Ok(mut s) = tokio::net::TcpStream::connect(("127.0.0.1", port)).await else { return eprintln!("daemon not running on port {port}") };
    let req = format!("{method} {path} HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
    s.write_all(req.as_bytes()).await.ok();
    let mut buf = String::new();
    s.read_to_string(&mut buf).await.ok();
    let status = buf.split_whitespace().nth(1).unwrap_or("?").to_string();
    let v: Value = serde_json::from_str(buf.split("\r\n\r\n").nth(1).unwrap_or("")).unwrap_or_default();
    if let Some(e) = v["error"].as_str() {
        return eprintln!("{e}");
    }
    if let Some(list) = v["containers"].as_array() {
        println!("owner access to containers: {}", if v["owner_access"] == true { "on" } else { "off" });
        for c in list {
            println!("{:<20} {:<20} {:<8} {}", c["id"].as_str().unwrap_or(""), c["name"].as_str().unwrap_or(""), if c["running"] == true { "running" } else { "stopped" }, if c["shared"] == true { "has phones" } else { "" });
        }
    } else if let Some(code) = v["code"].as_str() {
        println!("invite code {code}: on the phone, add this machine and enter it (valid {} min, once)", v["expires_in"].as_u64().unwrap_or(0) / 60);
    } else if !status.starts_with('2') {
        eprintln!("failed (HTTP {status})");
    }
}

// ---- the agent: runs inside one container ----

/// `homectl ct-agent <pid>`, started by the host daemon with the listening socket as fd 3. Joins the namespaces of the container
/// whose first process is `pid` and becomes its root, then serves the machine-like API on that socket. Must run before any
/// thread exists (joining a user namespace needs a single-threaded process). Returns when the agent should start serving.
pub fn agent_enter(args: &[String]) -> Result<(), String> {
    use std::os::fd::AsRawFd;
    let pid: i32 = args.first().and_then(|p| p.parse().ok()).filter(|p| *p > 1).ok_or("usage: homectl ct-agent <pid>")?;
    if same_ns(pid, "user") {
        return Err("refusing a privileged container".into());
    }
    // open them all first: once inside the container's mounts, the host's /proc is gone
    let order = ["user", "cgroup", "ipc", "uts", "net", "pid", "mnt"];
    let mut fds = vec![];
    for ns in order {
        if same_ns(pid, ns) {
            continue;
        }
        let f = std::fs::File::open(format!("/proc/{pid}/ns/{ns}")).map_err(|e| format!("{ns} namespace: {e}"))?;
        fds.push((ns, f));
    }
    // the container's own root, which is not always its mount namespace's (a container started by chroot keeps the host's tree there)
    let root = std::fs::File::open(format!("/proc/{pid}/root")).map_err(|e| format!("the container's root: {e}"))?;
    // count the agent's work against the container's own limits
    if let Some(cg) = std::fs::read_to_string(format!("/proc/{pid}/cgroup")).ok().and_then(|t| t.lines().find_map(|l| l.strip_prefix("0::").map(str::to_string))) {
        let _ = std::fs::write(format!("/sys/fs/cgroup{}/cgroup.procs", cg.trim()), std::process::id().to_string());
    }
    // the host root's extra groups go first, while that is still allowed (a namespace may forbid setgroups)
    if unsafe { libc::setgroups(0, std::ptr::null()) } != 0 {
        return Err(format!("dropping the host's groups: {}", std::io::Error::last_os_error()));
    }
    for (ns, f) in &fds {
        if unsafe { libc::setns(f.as_raw_fd(), 0) } != 0 {
            return Err(format!("joining the {ns} namespace: {}", std::io::Error::last_os_error()));
        }
    }
    drop(fds);
    unsafe {
        if libc::fchdir(root.as_raw_fd()) != 0 || libc::chroot(c".".as_ptr()) != 0 || libc::chdir(c"/".as_ptr()) != 0 {
            return Err(format!("entering the container's root: {}", std::io::Error::last_os_error()));
        }
    }
    drop(root);
    unsafe {
        if libc::setresgid(0, 0, 0) != 0 || libc::setresuid(0, 0, 0) != 0 {
            return Err(format!("becoming the container's root: {}", std::io::Error::last_os_error()));
        }
        // after the uid change (which clears it): end with the host daemon
        libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL);
        // The container's root may be the same uid as the agent now. Not dumpable means it can't ptrace it or read its memory, environment,
        // descriptors or /proc/<pid>/exe (the host's own binary), whatever fs.suid_dumpable says.
        libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0);
        if libc::getppid() == 1 {
            return Err("the host daemon is gone".into());
        }
        // the process ids it reports and signals must be the container's own, so the serving part lives inside its pid namespace
        match libc::fork() {
            -1 => return Err(std::io::Error::last_os_error().to_string()),
            0 => {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL);
                libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0);
            }
            child => {
                let mut st = 0;
                libc::waitpid(child, &mut st, 0);
                libc::_exit(if libc::WIFEXITED(st) { libc::WEXITSTATUS(st) } else { 1 });
            }
        }
    }
    let _ = std::env::set_current_dir("/");
    let token = std::env::var("HOMECTL_AGENT_TOKEN").map_err(|_| "no agent token")?;
    for (k, _) in std::env::vars_os().collect::<Vec<_>>() {
        std::env::remove_var(k);
    }
    std::env::set_var("HOMECTL_AGENT_TOKEN", token);
    std::env::set_var("HOME", "/root");
    std::env::set_var("USER", "root");
    std::env::set_var("LOGNAME", "root");
    std::env::set_var("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    std::env::set_var("LANG", "C.UTF-8");
    Ok(())
}

/// The agent's server loop on fd 3.
pub async fn agent_serve(router: axum::Router) {
    use std::os::fd::FromRawFd;
    let std_l = unsafe { std::os::unix::net::UnixListener::from_raw_fd(3) };
    let _ = std_l.set_nonblocking(true);
    let Ok(l) = tokio::net::UnixListener::from_std(std_l) else { return };
    loop {
        let Ok((s, _)) = l.accept().await else { return };
        let router = router.clone();
        tokio::spawn(async move {
            let peer: std::net::SocketAddr = ([127, 0, 0, 1], 0).into();
            let svc = router.layer(axum::Extension(axum::extract::ConnectInfo(peer)));
            let service = hyper::service::service_fn(move |req: axum::http::Request<hyper::body::Incoming>| tower::ServiceExt::oneshot(svc.clone(), req));
            let _ = hyper_util::server::conn::auto::Builder::new(hyper_util::rt::TokioExecutor::new()).serve_connection_with_upgrades(TokioIo::new(s), service).await;
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_container_reaches_only_its_machine_view() {
        for ok in ["/api/monitor", "/api/fs/list", "/api/fs/download", "/api/term", "/api/term/sessions/main", "/api/procs/12/term", "/api/ping"] {
            assert!(agent_path(ok), "{ok}");
        }
        for no in ["/api/windows", "/api/power/reboot", "/api/peers", "/api/containers", "/api/clipboard", "/api/screens", "/api/fsx", "/api/termx", "/api/security"] {
            assert!(!agent_path(no), "{no}");
        }
    }
}

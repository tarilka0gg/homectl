mod actions;
mod archive;
mod clip;
mod ctl;
mod device;
mod display;
mod fs;
mod govee;
mod monitor;
mod net;
mod pair;
mod privileged;
mod relay;
mod sensors;
mod swr;
mod sys;
mod tls;
mod term;
mod toplevels;
mod windows;

use axum::{
    extract::{ConnectInfo, Path, Query, Request, State, WebSocketUpgrade},
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    net::SocketAddr,
    path::PathBuf,
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, Mutex,
    },
};

pub(crate) struct App {
    pub(crate) token: String,
    inbox: PathBuf,
    port: u16,
    tunnel: net::TunnelUrl,
    tunnel_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    pub(crate) failures: AtomicU32,
    pub(crate) peers: relay::Peers,
    pub(crate) devices: device::Devices,
    pub(crate) device_always: std::sync::atomic::AtomicBool,
    pub(crate) tls_fp: String,
    pub(crate) tls_only: std::sync::atomic::AtomicBool,
    mon: monitor::Monitor,
    sessions: Arc<term::Sessions>,
    pairing: pair::Pairing,
}

type S = State<Arc<App>>;

fn config_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
    PathBuf::from(home).join(".config/homectl")
}

fn load_token() -> String {
    let dir = config_dir();
    let path = dir.join("token");
    if let Ok(t) = std::fs::read_to_string(&path) {
        return t.trim().to_string();
    }
    let token: String = pair::rand_bytes::<24>().iter().map(|b| format!("{b:02x}")).collect();
    std::fs::create_dir_all(&dir).expect("create config dir");
    std::fs::write(&path, &token).expect("write token");
    std::fs::set_permissions(&path, std::os::unix::fs::PermissionsExt::from_mode(0o600)).ok();
    eprintln!("generated new token at {}", path.display());
    token
}

/// Compares without short-circuiting so response time doesn't leak how much of the token matched.
pub(crate) fn token_eq(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub(crate) async fn slow_down(app: &App) {
    // The tunnel makes this reachable from the internet: slow down guessing as failures pile up.
    let n = app.failures.fetch_add(1, Ordering::Relaxed).min(20);
    tokio::time::sleep(std::time::Duration::from_millis(100 * n as u64)).await;
}

/// Requests from outside (the public tunnel), or from anywhere when "always" is on, must carry an enrolled phone's signature.
/// `path_query` is the request target as the client signed it (the signature parameters of a WebSocket URL already removed).
pub(crate) fn device_gate(app: &App, headers: &HeaderMap, peer: Option<SocketAddr>, method: &str, path_query: &str, ws_sig: Option<device::Sig>) -> Result<(), Response> {
    let outside = device::via_tunnel(headers);
    let always = app.device_always.load(Ordering::Relaxed) && !peer.is_some_and(|p| p.ip().is_loopback()) && !device::via_hub(headers);
    if !outside && !always {
        return Ok(());
    }
    // "Always" needs at least one registered phone: with none (the list was lost or wiped) nothing could ever sign in again, so a
    // phone that holds the access token may register from the home network, exactly as before "always" was switched on.
    if !outside && app.devices.count() == 0 && method == "POST" && path_query.split('?').next() == Some("/api/devices") {
        return Ok(());
    }
    let deny = |why: &str| {
        // the path only: never the query (it can hold the access token) or any signature
        eprintln!("refused {method} {}: {why}{}", path_query.split('?').next().unwrap_or(""), if outside { " (through the tunnel)" } else { "" });
        Err((StatusCode::UNAUTHORIZED, Json(json!({"error": "device_required", "why": why}))).into_response())
    };
    if app.devices.count() == 0 {
        return deny("no phone has been registered with this machine yet — open the app once on the home network");
    }
    let Some(sig) = device::sig_from_headers(headers).or(ws_sig) else { return deny("this request isn't signed by a registered phone") };
    match app.devices.verify(method, path_query, &sig) {
        Ok(()) => Ok(()),
        // the id is a public fingerprint of the phone's key, shown in the app under System → Security
        Err(why) => deny(&format!("{why} {}", sig.dev.chars().take(16).collect::<String>())),
    }
}

async fn auth(State(app): S, ConnectInfo(peer): ConnectInfo<SocketAddr>, req: Request, next: Next) -> Response {
    let ok = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| token_eq(t, &app.token));
    if ok {
        app.failures.store(0, Ordering::Relaxed);
        let pq = req.extensions().get::<axum::extract::OriginalUri>().map_or_else(|| req.uri().to_string(), |o| o.0.path_and_query().map_or(o.0.path().to_string(), |p| p.as_str().to_string()));
        if let Err(r) = device_gate(&app, req.headers(), Some(peer), req.method().as_str(), &pq, None) {
            return r;
        }
        next.run(req).await
    } else {
        slow_down(&app).await;
        StatusCode::UNAUTHORIZED.into_response()
    }
}

fn status(ok: bool) -> StatusCode {
    if ok {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    }
}

fn hostname() -> String {
    std::fs::read_to_string("/proc/sys/kernel/hostname").unwrap_or_default().trim().to_string()
}

async fn ping() -> impl IntoResponse {
    Json(json!({ "homectl": 1, "name": hostname() }))
}

async fn endpoints(State(app): S) -> impl IntoResponse {
    Json(net::endpoints(app.port, &app.tunnel, &app.tls_fp).await)
}

/// Whether encrypted links are available, the certificate fingerprint to pin, and the encrypted-only switch.
async fn security_get(State(app): S) -> impl IntoResponse {
    Json(json!({"tls": !app.tls_fp.is_empty(), "fp": app.tls_fp, "tls_only": app.tls_only.load(Ordering::Relaxed), "device_always": app.device_always.load(Ordering::Relaxed), "devices": app.devices.count()}))
}

#[derive(Deserialize)]
struct SecurityIn {
    tls_only: Option<bool>,
    device_always: Option<bool>,
}

fn set_marker(name: &str, on: bool) -> bool {
    let marker = config_dir().join(name);
    if on { std::fs::write(&marker, "").is_ok() } else { !marker.exists() || std::fs::remove_file(&marker).is_ok() }
}

async fn security_set(State(app): S, headers: HeaderMap, Json(s): Json<SecurityIn>) -> StatusCode {
    // security settings can't be changed from outside, or through another machine
    if !direct(&headers) {
        return StatusCode::FORBIDDEN;
    }
    if let Some(v) = s.tls_only {
        if v && app.tls_fp.is_empty() {
            return StatusCode::CONFLICT;
        }
        if !set_marker("tls-only", v) {
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
        app.tls_only.store(v, Ordering::Relaxed);
    }
    if let Some(v) = s.device_always {
        if v && app.devices.count() == 0 {
            return StatusCode::CONFLICT; // would lock everyone out
        }
        if !set_marker("device-always", v) {
            return StatusCode::INTERNAL_SERVER_ERROR;
        }
        app.device_always.store(v, Ordering::Relaxed);
    }
    StatusCode::NO_CONTENT
}

#[derive(Deserialize)]
struct EnrolIn {
    name: String,
    key: String,
}

/// Registers a phone's public key. Only from the home network: never through the tunnel or another machine.
async fn device_enrol(State(app): S, headers: HeaderMap, Json(e): Json<EnrolIn>) -> Response {
    if !direct(&headers) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match app.devices.enrol(&e.name, &e.key) {
        Ok((id, fp)) => Json(json!({"id": id, "fingerprint": fp})).into_response(),
        Err(why) => (StatusCode::BAD_REQUEST, Json(json!({"error": why}))).into_response(),
    }
}

async fn device_list(State(app): S) -> impl IntoResponse {
    Json(app.devices.list_json())
}

async fn device_remove(State(app): S, headers: HeaderMap, Path(id): Path<String>) -> StatusCode {
    if !direct(&headers) {
        return StatusCode::FORBIDDEN;
    }
    if app.devices.remove(&id) { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND }
}

async fn stats() -> impl IntoResponse {
    Json(sys::stats())
}

#[derive(Deserialize)]
struct MonQ {
    gpu: Option<u8>,
    /// ms between fresh samples the client wants (250–10000)
    every: Option<u64>,
    /// include the per-core CPU history (bigger reply)
    cores: Option<u8>,
    /// the `tick` of the last reply the client has: only newer samples are sent (`hist_delta`)
    since: Option<u64>,
}

async fn monitor(State(app): S, Query(q): Query<MonQ>) -> impl IntoResponse {
    app.mon.touch(q.every);
    if q.gpu == Some(1) {
        app.mon.force_gpu();
    }
    let mut v = app.mon.snapshot.read().unwrap().clone();
    let tick = v["tick"].as_u64().unwrap_or(0);
    let since = q.since.unwrap_or(0);
    let delta = since > 0 && since <= tick;
    if let Some(o) = v.as_object_mut() {
        let ticks = o.remove("history_t");
        if q.cores != Some(1) {
            o.remove("core_history");
        }
        if delta {
            // history: only the samples taken after `since`; every client keeps the older ones from before
            if let (Some(Value::Object(h)), Some(Value::Object(t))) = (o.get_mut("history"), ticks) {
                for (k, vals) in h.iter_mut() {
                    let n = t.get(k).and_then(Value::as_array).map_or(0, |ts| ts.iter().filter(|x| x.as_u64().is_some_and(|x| x > since)).count());
                    if let Some(a) = vals.as_array_mut() {
                        let keep = a.len().saturating_sub(n);
                        a.drain(..keep);
                    }
                }
            }
            if let Some(Value::Array(cores)) = o.get_mut("core_history") {
                let n = (tick - since) as usize;
                for c in cores.iter_mut().filter_map(Value::as_array_mut) {
                    let keep = c.len().saturating_sub(n);
                    c.drain(..keep);
                }
            }
            o.insert("hist_delta".into(), Value::Bool(true));
        }
    }
    Json(v)
}

/// A few numbers per machine for the overview cards; unlike /monitor it does not make the sampler run faster.
async fn glance(State(app): S) -> impl IntoResponse {
    let v = app.mon.snapshot.read().unwrap();
    let mem = &v["mem"];
    let used = match (mem["total"].as_f64(), mem["available"].as_f64()) {
        (Some(t), Some(a)) if t > 0.0 => Some(100.0 * (1.0 - a / t)),
        _ => None,
    };
    Json(json!({"cpu": v["cpu"]["total"], "temp": v["cpu"]["temp"], "mem": used, "name": v["info"]["hostname"]}))
}

#[derive(Deserialize)]
struct ProcQ {
    sort: Option<String>,
    order: Option<String>,
    q: Option<String>,
    limit: Option<usize>,
}

async fn procs(State(app): S, Query(q): Query<ProcQ>) -> impl IntoResponse {
    app.mon.touch(None);
    let desc = q.order.as_deref() != Some("asc");
    Json(app.mon.query_procs(q.sort.as_deref().unwrap_or("cpu"), desc, q.q.as_deref().unwrap_or(""), q.limit.unwrap_or(80).min(2000)))
}

async fn term_list(State(app): S) -> impl IntoResponse {
    Json(app.sessions.list())
}

async fn displays_get() -> impl IntoResponse {
    Json(swr::get("displays", std::time::Duration::from_secs(20), || display::list()).await)
}

#[derive(Deserialize)]
struct DispQ {
    compositor: Option<u8>,
}

async fn displays_set(Path((id, state)): Path<(String, String)>, Query(q): Query<DispQ>) -> Response {
    let on = match state.as_str() {
        "on" => true,
        "off" => false,
        _ => return StatusCode::BAD_REQUEST.into_response(),
    };
    swr::forget("displays");
    match display::set(&id, on, q.compositor != Some(0)).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(e) => (StatusCode::BAD_GATEWAY, Json(json!({ "error": e }))).into_response(),
    }
}

async fn actions_get() -> impl IntoResponse {
    Json(actions::list().await)
}

async fn actions_run(Path((id, state)): Path<(String, String)>, headers: HeaderMap) -> Response {
    let on = match state.as_str() {
        "on" => true,
        "off" => false,
        _ => return StatusCode::BAD_REQUEST.into_response(),
    };
    match actions::run(&id, on, privileged::password_from(&headers)).await {
        Ok(v) => Json(v).into_response(),
        Err((code, e)) => (StatusCode::from_u16(code).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR), Json(json!({ "error": e }))).into_response(),
    }
}

async fn term_shells() -> impl IntoResponse {
    Json(term::shells_json())
}

async fn term_kill(State(app): S, Path(id): Path<String>) -> StatusCode {
    if app.sessions.kill(&id) { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND }
}

async fn term_rename(State(app): S, Path(id): Path<String>, Json(t): Json<NameBody>) -> StatusCode {
    if app.sessions.rename(&id, &t.name) { StatusCode::NO_CONTENT } else { StatusCode::NOT_FOUND }
}

#[derive(Deserialize)]
struct NameBody {
    name: String,
}

#[derive(Deserialize)]
struct Percent {
    percent: u8,
}

async fn get_brightness() -> impl IntoResponse {
    Json(json!({ "percent": sys::brightness_get().await }))
}

async fn set_brightness(Json(p): Json<Percent>) -> StatusCode {
    status(sys::brightness_set(p.percent.min(100)).await)
}

#[derive(Deserialize)]
struct Rescan {
    rescan: Option<u8>,
}

async fn lights(Query(q): Query<Rescan>) -> impl IntoResponse {
    if q.rescan.is_some() {
        let v = json!(govee::lights(true).await);
        swr::put("lights", &v);
        return Json(v);
    }
    Json(swr::get("lights", std::time::Duration::from_secs(6), || async { json!(govee::lights(false).await) }).await)
}

#[derive(Deserialize)]
struct LightCmd {
    on: Option<bool>,
    brightness: Option<u8>,
    rgb: Option<[u8; 3]>,
    kelvin: Option<u16>,
}

async fn light_set(Path(ip): Path<String>, Json(c): Json<LightCmd>) -> StatusCode {
    let Ok(ip) = ip.parse() else { return StatusCode::BAD_REQUEST };
    swr::forget("lights");
    if govee::command(ip, c.on, c.brightness, c.rgb, c.kelvin).await.is_ok() {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::BAD_GATEWAY
    }
}

async fn upload(State(app): S, Path(name): Path<String>, body: axum::body::Body) -> StatusCode {
    let safe: String = name.chars().filter(|c| c.is_alphanumeric() || matches!(c, '.' | '-' | '_')).collect();
    if safe.is_empty() || safe.starts_with('.') {
        return StatusCode::BAD_REQUEST;
    }
    fs::upload(Query(fs::UploadQ::new(app.inbox.display().to_string(), safe)), body).await
}

#[derive(Deserialize)]
struct AudioCmd {
    percent: Option<u16>,
    muted: Option<bool>,
    default: Option<bool>,
}

async fn audio_get() -> impl IntoResponse {
    Json(ctl::audio().await)
}

async fn audio_post(Path(target): Path<String>, Json(c): Json<AudioCmd>) -> StatusCode {
    status(ctl::audio_set(&target, c.percent.map(|p| p.min(150) as u8), c.muted).await)
}

async fn mixer_get() -> impl IntoResponse {
    Json(ctl::mixer().await)
}

async fn mixer_post(Path((kind, id)): Path<(String, String)>, Json(c): Json<AudioCmd>) -> StatusCode {
    status(ctl::mixer_set(&kind, &id, c.percent, c.muted, c.default.unwrap_or(false)).await)
}

#[derive(Deserialize)]
struct On {
    on: bool,
}

async fn bt_get() -> impl IntoResponse {
    Json(ctl::bluetooth().await)
}
async fn bt_power(Json(c): Json<On>) -> StatusCode {
    status(ctl::bluetooth_power(c.on).await)
}
async fn bt_link(Path((mac, action)): Path<(String, String)>) -> StatusCode {
    match action.as_str() {
        "connect" | "disconnect" => status(ctl::bluetooth_link(&mac, action == "connect").await),
        _ => StatusCode::BAD_REQUEST,
    }
}
async fn wifi_get() -> impl IntoResponse {
    Json(swr::get("wifi", std::time::Duration::from_secs(8), || ctl::wifi()).await)
}
async fn wifi_power(Json(c): Json<On>) -> StatusCode {
    swr::forget("wifi");
    status(ctl::wifi_power(c.on).await)
}
async fn services_get() -> impl IntoResponse {
    Json(ctl::services().await)
}
async fn service_post(Path((name, action)): Path<(String, String)>, headers: HeaderMap) -> Response {
    match ctl::service_action(&name, &action, privileged::password_from(&headers)).await {
        ctl::Priv::Done => StatusCode::NO_CONTENT.into_response(),
        ctl::Priv::NeedsPassword => (StatusCode::FORBIDDEN, Json(json!({"error": "password_required"}))).into_response(),
        ctl::Priv::WrongPassword => (StatusCode::FORBIDDEN, Json(json!({"error": "wrong_password"}))).into_response(),
        ctl::Priv::Failed => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}
async fn monitors_get() -> impl IntoResponse {
    Json(swr::get("monitors", std::time::Duration::from_secs(120), || ctl::monitors()).await)
}
async fn monitor_get(Path(d): Path<u8>) -> impl IntoResponse {
    Json(json!({ "percent": ctl::monitor_brightness_get(d).await }))
}
async fn monitor_post(Path(d): Path<u8>, Json(p): Json<Percent>) -> StatusCode {
    status(ctl::monitor_brightness_set(d, p.percent).await)
}
async fn clip_get() -> impl IntoResponse {
    clip::touch();
    let types = ctl::clipboard_types().await;
    let has_text = types.iter().any(|t| t.starts_with("text/") || t == "UTF8_STRING" || t == "STRING");
    let image = types.iter().find(|t| t.starts_with("image/")).cloned();
    if !has_text {
        if let Some(img) = image {
            let size = ctl::clipboard_image(&img).await.map_or(0, |b| b.len());
            return Json(json!({"text": "", "type": img, "size": size}));
        }
        return Json(json!({"text": "", "type": if types.is_empty() { "empty" } else { "other" }, "types": types}));
    }
    Json(json!({ "text": ctl::clipboard_get().await, "type": "text" }))
}

/// The phone sends an image (raw bytes, `?mime=image/png`) to become the machine's clipboard.
async fn clip_image_set(Query(q): Query<std::collections::HashMap<String, String>>, body: axum::body::Bytes) -> StatusCode {
    clip::touch();
    let mime = q.get("mime").map(String::as_str).unwrap_or("image/png");
    if !mime.starts_with("image/") || body.is_empty() || body.len() > 32 << 20 {
        return StatusCode::BAD_REQUEST;
    }
    status(ctl::clipboard_set_bytes(mime, &body).await)
}

async fn clip_image() -> Response {
    let types = ctl::clipboard_types().await;
    let Some(mime) = types.iter().find(|t| t.starts_with("image/")).cloned() else { return StatusCode::NOT_FOUND.into_response() };
    match ctl::clipboard_image(&mime).await {
        Some(b) => ([(header::CONTENT_TYPE, mime)], b).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[derive(Deserialize)]
struct Text {
    text: String,
}

async fn clip_post(Json(t): Json<Text>) -> StatusCode {
    clip::touch();
    let ok = ctl::clipboard_set(&t.text).await;
    if ok {
        clip::sent_by_phone(&t.text);
    }
    status(ok)
}

/// What Noctalia's clipboard panel holds (empty list if it isn't installed or the clipboard is disabled there).
async fn clip_noctalia() -> impl IntoResponse {
    clip::touch();
    Json(clip::noctalia_list())
}

/// Puts a Noctalia history entry (text or image) back on the machine's clipboard.
async fn clip_noctalia_use(Path(id): Path<String>) -> StatusCode {
    let Some((mime, bytes)) = clip::noctalia_payload(&id) else { return StatusCode::NOT_FOUND };
    let ok = if mime.starts_with("text/") {
        let t = String::from_utf8_lossy(&bytes).into_owned();
        let ok = ctl::clipboard_set(&t).await;
        if ok {
            clip::sent_by_phone(&t);
        }
        ok
    } else {
        ctl::clipboard_set_bytes(&mime, &bytes).await
    };
    status(ok)
}

async fn clip_state() -> impl IntoResponse {
    Json(clip::state())
}

async fn clip_history() -> impl IntoResponse {
    Json(clip::history())
}

async fn clip_history_clear() -> StatusCode {
    clip::clear();
    StatusCode::NO_CONTENT
}

async fn clip_item(Path(id): Path<u64>) -> Response {
    match clip::get(id) {
        Some(t) => Json(json!({"text": t})).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Makes a history entry the machine's current clipboard again.
async fn clip_item_use(Path(id): Path<u64>) -> StatusCode {
    match clip::get(id) {
        Some(t) => {
            let ok = ctl::clipboard_set(&t).await;
            if ok {
                clip::sent_by_phone(&t);
            }
            status(ok)
        }
        None => StatusCode::NOT_FOUND,
    }
}

async fn clip_item_forget(Path(id): Path<u64>) -> StatusCode {
    clip::forget(id);
    StatusCode::NO_CONTENT
}

async fn cam_get(Path(n): Path<u8>) -> Response {
    match ctl::webcam_controls(n) {
        Some(v) => Json(v).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[derive(Deserialize)]
struct CamSet {
    id: u32,
    value: i32,
}

async fn cam_post(Path(n): Path<u8>, Json(c): Json<CamSet>) -> StatusCode {
    status(ctl::webcam_set(n, c.id, c.value))
}

async fn power_post(Path(action): Path<String>, headers: HeaderMap) -> Response {
    if !matches!(action.as_str(), "poweroff" | "reboot" | "suspend" | "lock") {
        return StatusCode::BAD_REQUEST.into_response();
    }
    // Decide before doing anything, and say so: a power action that cannot be done without a password used to answer "done" and
    // then silently not happen. The app answers `password_required` by asking for the doas password.
    match privileged::password_from(&headers) {
        None if !ctl::power_without_password(&action).await => {
            return (StatusCode::FORBIDDEN, Json(json!({"error": "password_required"}))).into_response();
        }
        Some(pw) => {
            // `doas true` with that password: changes nothing
            if privileged::test(Some(pw)).await["wrong_password"] == true {
                return (StatusCode::FORBIDDEN, Json(json!({"error": "wrong_password"}))).into_response();
            }
        }
        None => {}
    }
    status(ctl::power(&action, privileged::password_from(&headers))).into_response()
}

/// Checks that the machine accepts the doas password the app holds (or that none is needed).
async fn priv_test(headers: HeaderMap) -> impl IntoResponse {
    Json(privileged::test(privileged::password_from(&headers)).await)
}

async fn priv_rapl(headers: HeaderMap) -> impl IntoResponse {
    Json(privileged::allow_rapl(privileged::password_from(&headers)).await)
}

async fn proc_signal(Path((pid, sig)): Path<(i32, String)>) -> StatusCode {
    status(ctl::signal(pid, &sig))
}

async fn radios_get() -> impl IntoResponse {
    Json(ctl::radios().await)
}

async fn tunnel_get(State(app): S) -> impl IntoResponse {
    Json(json!({
        "available": net::tunnel_available().await,
        "on": app.tunnel_task.lock().unwrap().is_some(),
        "url": app.tunnel.read().unwrap().clone(),
    }))
}

async fn tunnel_post(State(app): S, Json(c): Json<On>) -> StatusCode {
    let mut task = app.tunnel_task.lock().unwrap();
    if c.on && task.is_none() {
        *task = Some(net::spawn_tunnel(app.port, app.tunnel.clone(), app.token.clone()));
    } else if !c.on {
        if let Some(t) = task.take() {
            t.abort();
        }
        *app.tunnel.write().unwrap() = None;
    }
    StatusCode::NO_CONTENT
}

// ---- pairing (no token yet; LAN / tailnet only) ----

fn direct(h: &HeaderMap) -> bool {
    !device::via_tunnel(h) && !device::via_hub(h)
}

#[derive(Deserialize)]
struct PairReq {
    device: Option<String>,
}

async fn pair_request(State(app): S, headers: HeaderMap, Json(r): Json<PairReq>) -> Response {
    if !direct(&headers) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match app.pairing.request(r.device.as_deref().unwrap_or("phone")) {
        Some(id) => Json(json!({ "id": id })).into_response(),
        None => StatusCode::TOO_MANY_REQUESTS.into_response(),
    }
}

#[derive(Deserialize)]
struct PairConfirm {
    id: String,
    code: String,
}

async fn pair_confirm(State(app): S, headers: HeaderMap, Json(r): Json<PairConfirm>) -> Response {
    if !direct(&headers) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if app.pairing.confirm(&r.id, &r.code) {
        Json(json!({ "token": app.token, "name": hostname(), "fp": app.tls_fp })).into_response()
    } else {
        slow_down(&app).await;
        StatusCode::UNAUTHORIZED.into_response()
    }
}

async fn pair_pending(State(app): S, ConnectInfo(peer): ConnectInfo<SocketAddr>, headers: HeaderMap) -> Response {
    if peer.ip().is_loopback() && direct(&headers) {
        Json(app.pairing.pending()).into_response()
    } else {
        StatusCode::FORBIDDEN.into_response()
    }
}

// ---- terminal ----

#[derive(Deserialize)]
struct TermQ {
    t: String,
    s: Option<String>,
    sh: Option<String>,
    cols: Option<u16>,
    rows: Option<u16>,
}

async fn term_ws(State(app): S, ConnectInfo(peer): ConnectInfo<SocketAddr>, uri: axum::http::Uri, headers: HeaderMap, Query(q): Query<TermQ>, ws: WebSocketUpgrade) -> Response {
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !token_eq(&q.t, &app.token) {
        slow_down(&app).await;
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let (clean, ws_sig) = device::split_query(uri.query().unwrap_or(""));
    let pq = if clean.is_empty() { uri.path().to_string() } else { format!("{}?{clean}", uri.path()) };
    if let Err(r) = device_gate(&app, &headers, Some(peer), "GET", &pq, ws_sig) {
        return r;
    }
    let (c, r) = (q.cols.unwrap_or(80), q.rows.unwrap_or(24));
    let id = q.s.unwrap_or_else(|| "main".into());
    let reg = app.sessions.clone();
    let sh = q.sh;
    ws.on_upgrade(move |sock| term::attach(reg, sock, id, c, r, sh))
}

async fn index() -> Html<&'static str> {
    Html(include_str!("../web/index.html"))
}

#[tokio::main]
async fn main() {
    let addr = std::env::var("HOMECTL_ADDR").unwrap_or_else(|_| "0.0.0.0:7878".into());
    let port: u16 = addr.rsplit(':').next().and_then(|p| p.parse().ok()).unwrap_or(7878);
    let token = load_token();
    if std::env::args().nth(1).as_deref() == Some("pair") {
        return net::pair(port, &token).await;
    }
    let tls = tls::load_or_create(&config_dir()).map_err(|e| eprintln!("encrypted connections unavailable: {e}")).ok();
    let inbox = PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join("homectl-inbox");
    std::fs::create_dir_all(&inbox).expect("create inbox");
    let tunnel: net::TunnelUrl = Default::default();
    let tunnel_task = std::env::var("HOMECTL_TUNNEL").is_ok_and(|v| v == "quick").then(|| net::spawn_tunnel(port, tunnel.clone(), token.clone()));
    let app = Arc::new(App {
        token,
        inbox,
        port,
        tunnel,
        tunnel_task: Mutex::new(tunnel_task),
        failures: AtomicU32::new(0),
        peers: relay::Peers::load(config_dir()),
        devices: device::Devices::load(config_dir()),
        device_always: std::sync::atomic::AtomicBool::new(config_dir().join("device-always").exists()),
        tls_fp: tls.as_ref().map(|t| t.fingerprint.clone()).unwrap_or_default(),
        tls_only: std::sync::atomic::AtomicBool::new(config_dir().join("tls-only").exists()),
        mon: monitor::start(),
        sessions: Default::default(),
        pairing: Default::default(),
    });

    let api = Router::new()
        .route("/endpoints", get(endpoints))
        .route("/stats", get(stats))
        .route("/monitor", get(monitor))
        .route("/glance", get(glance))
        .route("/brightness", get(get_brightness).post(set_brightness))
        .route("/lights", get(lights))
        .route("/lights/{ip}", post(light_set))
        .route("/files/{name}", post(upload))
        .route("/audio", get(audio_get))
        .route("/audio/{target}", post(audio_post))
        .route("/mixer", get(mixer_get))
        .route("/mixer/{kind}/{id}", post(mixer_post))
        .route("/bluetooth", get(bt_get))
        .route("/bluetooth/power", post(bt_power))
        .route("/bluetooth/{mac}/{action}", post(bt_link))
        .route("/wifi", get(wifi_get))
        .route("/wifi/power", post(wifi_power))
        .route("/radios", get(radios_get))
        .route("/services", get(services_get))
        .route("/services/{name}/{action}", post(service_post))
        .route("/monitors", get(monitors_get))
        .route("/monitors/{d}/brightness", get(monitor_get).post(monitor_post))
        .route("/clipboard", get(clip_get).post(clip_post))
        .route("/clipboard/image", get(clip_image).post(clip_image_set))
        .route("/clipboard/noctalia", get(clip_noctalia))
        .route("/clipboard/noctalia/{id}/use", post(clip_noctalia_use))
        .route("/clipboard/state", get(clip_state))
        .route("/clipboard/history", get(clip_history).delete(clip_history_clear))
        .route("/clipboard/history/{id}", get(clip_item).delete(clip_item_forget))
        .route("/clipboard/history/{id}/use", post(clip_item_use))
        .route("/webcam/{n}", get(cam_get).post(cam_post))
        .route("/power/{action}", post(power_post))
        .route("/procs", get(procs))
        .route("/procs/{pid}/{sig}", post(proc_signal))
        .route("/windows", get(windows::list))
        .route("/windows/{id}/screen", get(windows::screen))
        .route("/windows/{id}/input", post(windows::input))
        .route("/windows/{id}/focus", post(windows::focus))
        .route("/windows/{id}/close", post(windows::close))
        .route("/window-icon/{app_id}", get(windows::icon))
        .route("/term/sessions", get(term_list))
        .route("/term/shells", get(term_shells))
        .route("/displays", get(displays_get))
        .route("/displays/{id}/{state}", post(displays_set))
        .route("/actions", get(actions_get))
        .route("/actions/{id}/{state}", post(actions_run))
        .route("/term/sessions/{id}", axum::routing::delete(term_kill))
        .route("/term/sessions/{id}/name", post(term_rename))
        .route("/fs/list", get(fs::list))
        .route("/fs/download", get(fs::download))
        .route("/fs/upload", post(fs::upload))
        .route("/fs/mkdir", post(fs::mkdir))
        .route("/fs/thumb", get(fs::thumb))
        .route("/fs/search", get(fs::search))
        .route("/fs/usage", get(fs::usage))
        .route("/fs/stat", get(fs::stat))
        .route("/fs/hash", get(fs::hash))
        .route("/fs/delete", post(fs::delete))
        .route("/fs/rename", post(fs::rename))
        .route("/fs/write", post(fs::write))
        .route("/fs/archive/list", get(fs::archive_list))
        .route("/fs/archive/extract", post(fs::archive_extract))
        .route("/fs/archive/file", get(fs::archive_file))
        .route("/security", get(security_get).post(security_set))
        .route("/devices", get(device_list).post(device_enrol))
        .route("/devices/{id}", axum::routing::delete(device_remove))
        .route("/priv/test", post(priv_test))
        .route("/priv/rapl", post(priv_rapl))
        .route("/tunnel", get(tunnel_get).post(tunnel_post))
        .route("/peers", get(relay::list).post(relay::upsert))
        .route("/peers/{id}", axum::routing::delete(relay::remove))
        .layer(axum::extract::DefaultBodyLimit::disable())
        .layer(middleware::from_fn_with_state(app.clone(), auth))
        .with_state(app.clone());

    let open = Router::new()
        .route("/", get(index))
        .route("/api/ping", get(ping))
        .route("/api/pair/request", post(pair_request))
        .route("/api/pair/confirm", post(pair_confirm))
        .route("/api/pair/pending", get(pair_pending))
        .route("/api/term", get(term_ws))
        .route("/peer/{id}/{*rest}", axum::routing::any(relay::proxy).layer(axum::extract::DefaultBodyLimit::disable()))
        .with_state(app.clone());

    let router = open.nest("/api", api);
    clip::start();
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("homectl {} listening on {addr} ({})", env!("CARGO_PKG_VERSION"), if tls.is_some() { "plain and encrypted" } else { "plain only" });
    let acceptor = tls.map(|t| t.acceptor);
    loop {
        let (tcp, peer) = match listener.accept().await {
            Ok(c) => c,
            Err(_) => {
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                continue;
            }
        };
        let (router, acceptor, state) = (router.clone(), acceptor.clone(), app.clone());
        tokio::spawn(async move {
            // one port, two protocols: a TLS ClientHello starts with 0x16, plain HTTP with a letter
            let mut first = [0u8; 1];
            if !matches!(tokio::time::timeout(std::time::Duration::from_secs(10), tcp.peek(&mut first)).await, Ok(Ok(1))) {
                return;
            }
            let svc = router.layer(axum::Extension(ConnectInfo(peer)));
            let service = hyper::service::service_fn(move |req: axum::http::Request<hyper::body::Incoming>| tower::ServiceExt::oneshot(svc.clone(), req));
            let builder = hyper_util::server::conn::auto::Builder::new(hyper_util::rt::TokioExecutor::new());
            if first[0] == 0x16 {
                let Some(acc) = acceptor else { return };
                let Ok(stream) = acc.accept(tcp).await else { return };
                let _ = builder.serve_connection_with_upgrades(hyper_util::rt::TokioIo::new(stream), service).await;
            } else {
                // "encrypted only": plain HTTP stays open for this machine itself and for the tailnet (already WireGuard-encrypted)
                let ip = peer.ip();
                let trusted = ip.is_loopback() || matches!(ip, std::net::IpAddr::V4(v4) if v4.octets()[0] == 100 && (64..128).contains(&v4.octets()[1]));
                if state.tls_only.load(Ordering::Relaxed) && !trusted {
                    let _ = tcp.try_write(b"HTTP/1.1 426 Upgrade Required\r\ncontent-length: 41\r\nconnection: close\r\n\r\nthis machine only accepts encrypted links");
                    return;
                }
                let _ = builder.serve_connection_with_upgrades(hyper_util::rt::TokioIo::new(tcp), service).await;
            }
        });
    }
}

mod archive;
mod ctl;
mod fs;
mod govee;
mod monitor;
mod net;
mod pair;
mod sys;
mod term;

use axum::{
    extract::{ConnectInfo, Path, Query, Request, State, WebSocketUpgrade},
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::json;
use std::{
    net::SocketAddr,
    path::PathBuf,
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, Mutex,
    },
};

struct App {
    token: String,
    inbox: PathBuf,
    port: u16,
    tunnel: net::TunnelUrl,
    tunnel_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    failures: AtomicU32,
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
fn token_eq(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn slow_down(app: &App) {
    // The tunnel makes this reachable from the internet: slow down guessing as failures pile up.
    let n = app.failures.fetch_add(1, Ordering::Relaxed).min(20);
    tokio::time::sleep(std::time::Duration::from_millis(100 * n as u64)).await;
}

async fn auth(State(app): S, req: Request, next: Next) -> Response {
    let ok = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| token_eq(t, &app.token));
    if ok {
        app.failures.store(0, Ordering::Relaxed);
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
    Json(net::endpoints(app.port, &app.tunnel).await)
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
}

async fn monitor(State(app): S, Query(q): Query<MonQ>) -> impl IntoResponse {
    app.mon.touch(q.every);
    if q.gpu == Some(1) {
        app.mon.force_gpu();
    }
    let mut v = app.mon.snapshot.read().unwrap().clone();
    if q.cores != Some(1) {
        if let Some(o) = v.as_object_mut() {
            o.remove("core_history");
        }
    }
    Json(v)
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
    Json(govee::lights(q.rescan.is_some()).await)
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
    Json(ctl::wifi().await)
}
async fn wifi_power(Json(c): Json<On>) -> StatusCode {
    status(ctl::wifi_power(c.on).await)
}
async fn services_get() -> impl IntoResponse {
    Json(ctl::services().await)
}
async fn service_post(Path((name, action)): Path<(String, String)>) -> StatusCode {
    status(ctl::service_action(&name, &action).await)
}
async fn monitors_get() -> impl IntoResponse {
    Json(ctl::monitors().await)
}
async fn monitor_get(Path(d): Path<u8>) -> impl IntoResponse {
    Json(json!({ "percent": ctl::monitor_brightness_get(d).await }))
}
async fn monitor_post(Path(d): Path<u8>, Json(p): Json<Percent>) -> StatusCode {
    status(ctl::monitor_brightness_set(d, p.percent).await)
}
async fn clip_get() -> impl IntoResponse {
    Json(json!({ "text": ctl::clipboard_get().await }))
}

#[derive(Deserialize)]
struct Text {
    text: String,
}

async fn clip_post(Json(t): Json<Text>) -> StatusCode {
    status(ctl::clipboard_set(&t.text).await)
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

async fn power_post(Path(action): Path<String>) -> StatusCode {
    status(ctl::power(&action))
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
        *task = Some(net::spawn_tunnel(app.port, app.tunnel.clone()));
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
    !h.contains_key("cf-connecting-ip") && !h.contains_key("x-forwarded-for")
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
        Json(json!({ "token": app.token, "name": hostname() })).into_response()
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

async fn term_ws(State(app): S, Query(q): Query<TermQ>, ws: WebSocketUpgrade) -> Response {
    if std::env::var("HOMECTL_NO_TERMINAL").is_ok() {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !token_eq(&q.t, &app.token) {
        slow_down(&app).await;
        return StatusCode::UNAUTHORIZED.into_response();
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
    let inbox = PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join("homectl-inbox");
    std::fs::create_dir_all(&inbox).expect("create inbox");
    let tunnel: net::TunnelUrl = Default::default();
    let tunnel_task = std::env::var("HOMECTL_TUNNEL").is_ok_and(|v| v == "quick").then(|| net::spawn_tunnel(port, tunnel.clone()));
    let app = Arc::new(App {
        token,
        inbox,
        port,
        tunnel,
        tunnel_task: Mutex::new(tunnel_task),
        failures: AtomicU32::new(0),
        mon: monitor::start(),
        sessions: Default::default(),
        pairing: Default::default(),
    });

    let api = Router::new()
        .route("/endpoints", get(endpoints))
        .route("/stats", get(stats))
        .route("/monitor", get(monitor))
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
        .route("/webcam/{n}", get(cam_get).post(cam_post))
        .route("/power/{action}", post(power_post))
        .route("/procs", get(procs))
        .route("/procs/{pid}/{sig}", post(proc_signal))
        .route("/term/sessions", get(term_list))
        .route("/term/shells", get(term_shells))
        .route("/term/sessions/{id}", axum::routing::delete(term_kill))
        .route("/term/sessions/{id}/name", post(term_rename))
        .route("/fs/list", get(fs::list))
        .route("/fs/download", get(fs::download))
        .route("/fs/upload", post(fs::upload))
        .route("/fs/mkdir", post(fs::mkdir))
        .route("/fs/thumb", get(fs::thumb))
        .route("/fs/archive/list", get(fs::archive_list))
        .route("/fs/archive/extract", post(fs::archive_extract))
        .route("/fs/archive/file", get(fs::archive_file))
        .route("/tunnel", get(tunnel_get).post(tunnel_post))
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
        .with_state(app);

    let router = open.nest("/api", api);
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("homectl listening on {addr}");
    axum::serve(listener, router.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap();
}

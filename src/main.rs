mod ctl;
mod govee;
mod sys;

use axum::{
    body::Bytes,
    extract::{Path, Request, State},
    http::{header, StatusCode},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use std::{path::PathBuf, sync::Arc};

struct App {
    token: String,
    inbox: PathBuf,
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
    let mut buf = [0u8; 24];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| std::io::Read::read_exact(&mut f, &mut buf))
        .expect("read /dev/urandom");
    let token: String = buf.iter().map(|b| format!("{b:02x}")).collect();
    std::fs::create_dir_all(&dir).expect("create config dir");
    std::fs::write(&path, &token).expect("write token");
    std::fs::set_permissions(&path, std::os::unix::fs::PermissionsExt::from_mode(0o600)).ok();
    eprintln!("generated new token at {}", path.display());
    token
}

async fn auth(State(app): S, req: Request, next: Next) -> Response {
    let ok = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .is_some_and(|t| t == app.token);
    if ok {
        next.run(req).await
    } else {
        StatusCode::UNAUTHORIZED.into_response()
    }
}

async fn stats() -> impl IntoResponse {
    Json(sys::stats())
}

#[derive(Deserialize)]
struct Percent {
    percent: u8,
}

async fn get_brightness() -> impl IntoResponse {
    Json(serde_json::json!({ "percent": sys::brightness_get().await }))
}

async fn set_brightness(Json(p): Json<Percent>) -> StatusCode {
    if sys::brightness_set(p.percent.min(100)).await {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    }
}

async fn lights() -> impl IntoResponse {
    Json(govee::scan().await)
}

#[derive(Deserialize)]
struct LightCmd {
    on: Option<bool>,
    brightness: Option<u8>,
    rgb: Option<[u8; 3]>,
    kelvin: Option<u16>,
}

async fn light_set(Path(ip): Path<String>, Json(c): Json<LightCmd>) -> StatusCode {
    let Ok(ip) = ip.parse() else {
        return StatusCode::BAD_REQUEST;
    };
    let r = govee::command(ip, c.on, c.brightness, c.rgb, c.kelvin).await;
    if r.is_ok() {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::BAD_GATEWAY
    }
}

async fn upload(State(app): S, Path(name): Path<String>, body: Bytes) -> StatusCode {
    let safe: String = name
        .chars()
        .filter(|c| c.is_alphanumeric() || matches!(c, '.' | '-' | '_'))
        .collect();
    if safe.is_empty() || safe.starts_with('.') {
        return StatusCode::BAD_REQUEST;
    }
    match tokio::fs::write(app.inbox.join(safe), body).await {
        Ok(()) => StatusCode::CREATED,
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

fn status(ok: bool) -> StatusCode {
    if ok { StatusCode::NO_CONTENT } else { StatusCode::INTERNAL_SERVER_ERROR }
}

async fn audio_get() -> impl IntoResponse { Json(ctl::audio().await) }

#[derive(Deserialize)]
struct AudioCmd { percent: Option<u8>, muted: Option<bool> }

async fn audio_post(Path(target): Path<String>, Json(c): Json<AudioCmd>) -> StatusCode {
    status(ctl::audio_set(&target, c.percent, c.muted).await)
}

async fn bt_get() -> impl IntoResponse { Json(ctl::bluetooth().await) }

#[derive(Deserialize)]
struct On { on: bool }

async fn bt_power(Json(c): Json<On>) -> StatusCode { status(ctl::bluetooth_power(c.on).await) }

async fn bt_link(Path((mac, action)): Path<(String, String)>) -> StatusCode {
    match action.as_str() {
        "connect" | "disconnect" => status(ctl::bluetooth_link(&mac, action == "connect").await),
        _ => StatusCode::BAD_REQUEST,
    }
}

async fn wifi_get() -> impl IntoResponse { Json(ctl::wifi().await) }
async fn wifi_power(Json(c): Json<On>) -> StatusCode { status(ctl::wifi_power(c.on).await) }

async fn services_get() -> impl IntoResponse { Json(ctl::services().await) }

async fn service_post(Path((name, action)): Path<(String, String)>) -> StatusCode {
    status(ctl::service_action(&name, &action).await)
}

async fn monitors_get() -> impl IntoResponse { Json(ctl::monitors().await) }

async fn monitor_get(Path(d): Path<u8>) -> impl IntoResponse {
    Json(serde_json::json!({ "percent": ctl::monitor_brightness_get(d).await }))
}

async fn monitor_post(Path(d): Path<u8>, Json(p): Json<Percent>) -> StatusCode {
    status(ctl::monitor_brightness_set(d, p.percent).await)
}

async fn clip_get() -> impl IntoResponse { Json(serde_json::json!({ "text": ctl::clipboard_get().await })) }

#[derive(Deserialize)]
struct Text { text: String }

async fn clip_post(Json(t): Json<Text>) -> StatusCode { status(ctl::clipboard_set(&t.text).await) }

async fn cam_get(Path(n): Path<u8>) -> Response {
    match ctl::webcam_controls(n) {
        Some(v) => Json(v).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[derive(Deserialize)]
struct CamSet { id: u32, value: i32 }

async fn cam_post(Path(n): Path<u8>, Json(c): Json<CamSet>) -> StatusCode {
    status(ctl::webcam_set(n, c.id, c.value))
}

async fn index() -> Html<&'static str> {
    Html(include_str!("../web/index.html"))
}

#[tokio::main]
async fn main() {
    let addr = std::env::var("HOMECTL_ADDR").unwrap_or_else(|_| "0.0.0.0:7878".into());
    let inbox = PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join("homectl-inbox");
    std::fs::create_dir_all(&inbox).expect("create inbox");
    let app = Arc::new(App { token: load_token(), inbox });

    let api = Router::new()
        .route("/stats", get(stats))
        .route("/brightness", get(get_brightness).post(set_brightness))
        .route("/lights", get(lights))
        .route("/lights/{ip}", post(light_set))
        .route("/files/{name}", post(upload))
        .route("/audio", get(audio_get))
        .route("/audio/{target}", post(audio_post))
        .route("/bluetooth", get(bt_get))
        .route("/bluetooth/power", post(bt_power))
        .route("/bluetooth/{mac}/{action}", post(bt_link))
        .route("/wifi", get(wifi_get))
        .route("/wifi/power", post(wifi_power))
        .route("/services", get(services_get))
        .route("/services/{name}/{action}", post(service_post))
        .route("/monitors", get(monitors_get))
        .route("/monitors/{d}/brightness", get(monitor_get).post(monitor_post))
        .route("/clipboard", get(clip_get).post(clip_post))
        .route("/webcam/{n}", get(cam_get).post(cam_post))
        .layer(axum::extract::DefaultBodyLimit::max(512 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(app.clone(), auth))
        .with_state(app);

    let router = Router::new().route("/", get(index)).nest("/api", api);
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("homectl listening on {addr}");
    axum::serve(listener, router).await.unwrap();
}

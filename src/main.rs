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
        .layer(axum::extract::DefaultBodyLimit::max(512 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(app.clone(), auth))
        .with_state(app);

    let router = Router::new().route("/", get(index)).nest("/api", api);
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("homectl listening on {addr}");
    axum::serve(listener, router).await.unwrap();
}

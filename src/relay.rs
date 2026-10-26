//! Peer relay: an always-on daemon (the "hub", e.g. the home server) forwards the app's requests to other
//! homectl daemons it can reach (e.g. the laptop), so the app only needs to talk to the hub. Plain HTTP with
//! streamed bodies (Range downloads, chunked uploads) and WebSocket upgrades (terminal) pass straight through.
use axum::{
    body::Body,
    extract::{Path, Request, State},
    http::{header, HeaderValue, StatusCode, Version},
    response::{IntoResponse, Response},
    Json,
};
use hyper_util::{
    client::legacy::{connect::HttpConnector, Client},
    rt::{TokioExecutor, TokioIo},
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{path::PathBuf, sync::{atomic::Ordering, Arc, RwLock}, time::Duration};

#[derive(Clone, Serialize, Deserialize)]
pub struct Peer {
    pub id: String,
    pub name: String,
    /// `http://host:port` as seen from this machine
    pub url: String,
    pub token: String,
}

pub struct Peers {
    file: PathBuf,
    list: RwLock<Vec<Peer>>,
    client: Client<HttpConnector, Body>,
}

impl Peers {
    pub fn load(dir: PathBuf) -> Self {
        let file = dir.join("peers.json");
        let list = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        let mut http = HttpConnector::new();
        http.set_connect_timeout(Some(Duration::from_secs(5)));
        Peers { file, list: RwLock::new(list), client: Client::builder(TokioExecutor::new()).build(http) }
    }

    fn save(&self, list: &[Peer]) {
        if let Ok(t) = serde_json::to_string_pretty(list) {
            let _ = std::fs::write(&self.file, t);
            let _ = std::fs::set_permissions(&self.file, std::os::unix::fs::PermissionsExt::from_mode(0o600));
        }
    }

    /// True when this machine relays for others (it is somebody's server).
    pub fn any(&self) -> bool {
        !self.list.read().unwrap().is_empty()
    }

    fn get(&self, id: &str) -> Option<Peer> {
        self.list.read().unwrap().iter().find(|p| p.id == id).cloned()
    }
}

pub async fn list(State(app): State<Arc<crate::App>>) -> impl IntoResponse {
    let v: Vec<_> = app.peers.list.read().unwrap().iter().map(|p| json!({"id": p.id, "name": p.name, "url": p.url})).collect();
    Json(v)
}

pub async fn upsert(State(app): State<Arc<crate::App>>, Json(p): Json<Peer>) -> StatusCode {
    let host_ok = p.url.strip_prefix("http://").is_some_and(|h| !h.is_empty() && !h.contains('/') && !h.contains('@') && h.contains(':'));
    let id_ok = !p.id.is_empty() && p.id.len() <= 40 && p.id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    if !host_ok || !id_ok || p.token.is_empty() {
        return StatusCode::BAD_REQUEST;
    }
    let mut l = app.peers.list.write().unwrap();
    l.retain(|x| x.id != p.id);
    l.push(p);
    app.peers.save(&l);
    StatusCode::NO_CONTENT
}

pub async fn remove(State(app): State<Arc<crate::App>>, Path(id): Path<String>) -> StatusCode {
    let mut l = app.peers.list.write().unwrap();
    l.retain(|x| x.id != id);
    app.peers.save(&l);
    StatusCode::NO_CONTENT
}

/// `/peer/{id}/{*rest}`: forwards to `{peer}/{rest}` with the peer's own token. The hub's token (Bearer, or `t=`
/// for WebSockets) is required, except for `api/ping` so the app can tell whether the peer is reachable.
pub async fn proxy(State(app): State<Arc<crate::App>>, axum::extract::ConnectInfo(peer_addr): axum::extract::ConnectInfo<std::net::SocketAddr>, Path((id, rest)): Path<(String, String)>, mut req: Request) -> Response {
    let query = req.uri().query().unwrap_or("").to_string();
    let (clean_query, ws_sig) = crate::device::split_query(&query);
    let signed_pq = if clean_query.is_empty() { req.uri().path().to_string() } else { format!("{}?{clean_query}", req.uri().path()) };
    // the phone's signature is checked here, on the machine that faces the Internet; the peer then trusts this machine
    if rest != "api/ping" {
        if let Err(r) = crate::device_gate(&app, req.headers(), Some(peer_addr), req.method().as_str(), &signed_pq, ws_sig) {
            return r;
        }
    }
    let mut hub_t = None;
    let mut kept = vec![];
    for kv in clean_query.split('&').filter(|s| !s.is_empty()) {
        match kv.strip_prefix("t=") {
            Some(t) => hub_t = Some(t.to_string()),
            None => kept.push(kv.to_string()),
        }
    }
    let bearer = req.headers().get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer ")).map(str::to_string);
    let authed = rest == "api/ping" || [bearer, hub_t].into_iter().flatten().any(|t| crate::token_eq(&t, &app.token));
    if !authed {
        crate::slow_down(&app).await;
        return StatusCode::UNAUTHORIZED.into_response();
    }
    app.failures.store(0, Ordering::Relaxed);
    let Some(peer) = app.peers.get(&id) else { return StatusCode::NOT_FOUND.into_response() };

    let upgrade = req.headers().get(header::UPGRADE).is_some_and(|v| v.as_bytes().eq_ignore_ascii_case(b"websocket"));
    if upgrade {
        kept.push(format!("t={}", peer.token)); // the terminal socket authenticates by query
    }
    let uri = format!("{}/{}{}{}", peer.url, rest, if kept.is_empty() { "" } else { "?" }, kept.join("&"));
    let Ok(uri) = uri.parse() else { return StatusCode::BAD_REQUEST.into_response() };
    *req.uri_mut() = uri;
    *req.version_mut() = Version::HTTP_11;
    req.headers_mut().remove(header::HOST);
    let drop: Vec<_> = req.headers().keys().filter(|k| { let k = k.as_str(); k.starts_with("cf-") || k.starts_with("x-forwarded-") || k.starts_with("x-homectl-") || k == "cdn-loop" || k == "true-client-ip" || k == "x-real-ip" || k == "forwarded" }).cloned().collect();
    for k in drop {
        req.headers_mut().remove(k);
    }
    req.headers_mut().insert("x-homectl-via", HeaderValue::from_static("hub"));
    if let Ok(v) = HeaderValue::from_str(&format!("Bearer {}", peer.token)) {
        req.headers_mut().insert(header::AUTHORIZATION, v);
    }
    let client_side = upgrade.then(|| hyper::upgrade::on(&mut req));

    let mut res = match app.peers.client.request(req).await {
        Ok(r) => r,
        Err(e) => return (StatusCode::BAD_GATEWAY, format!("{} is not reachable from here: {e}", peer.name)).into_response(),
    };
    if let (StatusCode::SWITCHING_PROTOCOLS, Some(client_side)) = (res.status(), client_side) {
        let peer_side = hyper::upgrade::on(&mut res);
        tokio::spawn(async move {
            if let (Ok(c), Ok(p)) = (client_side.await, peer_side.await) {
                let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(c), &mut TokioIo::new(p)).await;
            }
        });
    }
    res.map(Body::new)
}

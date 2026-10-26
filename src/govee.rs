//! Govee LAN API: multicast scan on 239.255.255.250:4001 (replies on 4002), per-device status and
//! control by UDP to the device on 4003. Needs "LAN Control" enabled per device in the Govee app.
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    net::Ipv4Addr,
    sync::{Mutex as StdMutex, OnceLock},
    time::{Duration, Instant},
};
use tokio::{net::UdpSocket, sync::Mutex, time::timeout};

#[derive(Serialize, Clone)]
pub struct Light {
    /// The address to control it by; for a light reached through the Govee cloud it is `cloud-<device id>`.
    ip: String,
    sku: String,
    pub(crate) device: String,
    on: Option<bool>,
    brightness: Option<u64>,
    rgb: Option<[u64; 3]>,
    kelvin: Option<u64>,
    /// "lan" or "cloud"
    via: &'static str,
    name: Option<String>,
    online: Option<bool>,
}

impl Light {
    #[allow(clippy::too_many_arguments)]
    pub fn cloud(id: String, sku: String, device: String, name: Option<String>, online: bool, on: Option<bool>, brightness: Option<u64>, rgb: Option<[u64; 3]>, kelvin: Option<u64>) -> Self {
        Light { ip: id, sku, device, on, brightness, rgb, kelvin, via: "cloud", name, online: Some(online) }
    }
}

static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static CACHE: OnceLock<StdMutex<Option<(Instant, Vec<Light>)>>> = OnceLock::new();

async fn socket() -> Option<UdpSocket> {
    let sock = UdpSocket::bind("0.0.0.0:4002").await.ok()?;
    sock.join_multicast_v4(Ipv4Addr::new(239, 255, 255, 250), Ipv4Addr::UNSPECIFIED).ok();
    Some(sock)
}

async fn discover(sock: &UdpSocket) -> Vec<Light> {
    let req = json!({"msg": {"cmd": "scan", "data": {"account_topic": "reserve"}}}).to_string();
    sock.send_to(req.as_bytes(), "239.255.255.250:4001").await.ok();
    let mut found: Vec<Light> = vec![];
    let mut buf = [0u8; 2048];
    while let Ok(Ok(n)) = timeout(Duration::from_millis(1500), sock.recv(&mut buf)).await {
        let Ok(v) = serde_json::from_slice::<Value>(&buf[..n]) else { continue };
        let d = &v["msg"]["data"];
        let (Some(ip), Some(sku), Some(device)) = (d["ip"].as_str(), d["sku"].as_str(), d["device"].as_str()) else { continue };
        if !found.iter().any(|l| l.ip == ip) {
            found.push(Light { ip: ip.into(), sku: sku.into(), device: device.into(), on: None, brightness: None, rgb: None, kelvin: None, via: "lan", name: None, online: None });
        }
    }
    found
}

/// All lights with their current on/off, brightness and colour. Discovery results are cached for 5 minutes
/// (or refreshed with `rescan`); the state of each light is queried on every call.
pub async fn lights(rescan: bool) -> Vec<Light> {
    let _guard = LOCK.get_or_init(|| Mutex::new(())).lock().await;
    let Some(sock) = socket().await else { return vec![] };
    let cache = CACHE.get_or_init(Default::default);
    let cached = cache.lock().unwrap().clone().filter(|(t, l)| !rescan && !l.is_empty() && t.elapsed() < Duration::from_secs(300));
    let mut list = match cached {
        Some((_, l)) => l,
        None => {
            let l = discover(&sock).await;
            *cache.lock().unwrap() = Some((Instant::now(), l.clone()));
            l
        }
    };
    let q = json!({"msg": {"cmd": "devStatus", "data": {}}}).to_string();
    for l in &list {
        if let Ok(ip) = l.ip.parse::<Ipv4Addr>() {
            sock.send_to(q.as_bytes(), (ip, 4003)).await.ok();
        }
    }
    let mut buf = [0u8; 2048];
    let deadline = Instant::now() + Duration::from_millis(800);
    let mut answered = 0;
    while answered < list.len() {
        let left = deadline.saturating_duration_since(Instant::now());
        let Ok(Ok((n, from))) = timeout(left, sock.recv_from(&mut buf)).await else { break };
        let Ok(v) = serde_json::from_slice::<Value>(&buf[..n]) else { continue };
        let d = &v["msg"]["data"];
        if v["msg"]["cmd"] != "devStatus" {
            continue;
        }
        let from_ip = from.ip().to_string();
        if let Some(l) = list.iter_mut().find(|l| l.ip == from_ip) {
            l.on = d["onOff"].as_u64().map(|x| x == 1);
            l.brightness = d["brightness"].as_u64();
            l.rgb = Some([d["color"]["r"].as_u64().unwrap_or(0), d["color"]["g"].as_u64().unwrap_or(0), d["color"]["b"].as_u64().unwrap_or(0)]);
            l.kelvin = d["colorTemInKelvin"].as_u64();
            answered += 1;
        }
    }
    list
}

async fn send(ip: Ipv4Addr, cmd: &str, data: Value) -> std::io::Result<()> {
    let sock = UdpSocket::bind("0.0.0.0:0").await?;
    let msg = json!({"msg": {"cmd": cmd, "data": data}}).to_string();
    sock.send_to(msg.as_bytes(), (ip, 4003)).await?;
    Ok(())
}

pub async fn command(ip: Ipv4Addr, on: Option<bool>, brightness: Option<u8>, rgb: Option<[u8; 3]>, kelvin: Option<u16>) -> std::io::Result<()> {
    if let Some(on) = on {
        send(ip, "turn", json!({"value": on as u8})).await?;
    }
    if let Some(b) = brightness {
        send(ip, "brightness", json!({"value": b.clamp(1, 100)})).await?;
    }
    if rgb.is_some() || kelvin.is_some() {
        let [r, g, b] = rgb.unwrap_or([0, 0, 0]);
        send(ip, "colorwc", json!({"color": {"r": r, "g": g, "b": b}, "colorTemInKelvin": kelvin.unwrap_or(0)})).await?;
    }
    Ok(())
}

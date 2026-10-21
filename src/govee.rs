//! Govee LAN API: multicast scan on 239.255.255.250:4001 (replies on 4002),
//! control by UDP to the device on 4003. Needs "LAN Control" enabled per device in the Govee app.
use serde::Serialize;
use serde_json::{json, Value};
use std::{net::Ipv4Addr, time::Duration};
use tokio::{net::UdpSocket, time::timeout};

#[derive(Serialize)]
pub struct Light {
    ip: String,
    sku: String,
    device: String,
}

pub async fn scan() -> Vec<Light> {
    let Ok(sock) = UdpSocket::bind("0.0.0.0:4002").await else {
        return vec![];
    };
    sock.join_multicast_v4(Ipv4Addr::new(239, 255, 255, 250), Ipv4Addr::UNSPECIFIED).ok();
    let req = json!({"msg": {"cmd": "scan", "data": {"account_topic": "reserve"}}}).to_string();
    sock.send_to(req.as_bytes(), "239.255.255.250:4001").await.ok();

    let mut found: Vec<Light> = vec![];
    let mut buf = [0u8; 2048];
    while let Ok(Ok(n)) = timeout(Duration::from_millis(1500), sock.recv(&mut buf)).await {
        let Ok(v) = serde_json::from_slice::<Value>(&buf[..n]) else { continue };
        let d = &v["msg"]["data"];
        let (Some(ip), Some(sku), Some(device)) = (d["ip"].as_str(), d["sku"].as_str(), d["device"].as_str()) else {
            continue;
        };
        if !found.iter().any(|l| l.ip == ip) {
            found.push(Light { ip: ip.into(), sku: sku.into(), device: device.into() });
        }
    }
    found
}

async fn send(ip: Ipv4Addr, cmd: &str, data: Value) -> std::io::Result<()> {
    let sock = UdpSocket::bind("0.0.0.0:0").await?;
    let msg = json!({"msg": {"cmd": cmd, "data": data}}).to_string();
    sock.send_to(msg.as_bytes(), (ip, 4003)).await?;
    Ok(())
}

pub async fn command(
    ip: Ipv4Addr,
    on: Option<bool>,
    brightness: Option<u8>,
    rgb: Option<[u8; 3]>,
    kelvin: Option<u16>,
) -> std::io::Result<()> {
    if let Some(on) = on {
        send(ip, "turn", json!({"value": on as u8})).await?;
    }
    if let Some(b) = brightness {
        send(ip, "brightness", json!({"value": b.clamp(1, 100)})).await?;
    }
    if rgb.is_some() || kelvin.is_some() {
        let [r, g, b] = rgb.unwrap_or([0, 0, 0]);
        send(
            ip,
            "colorwc",
            json!({"color": {"r": r, "g": g, "b": b}, "colorTemInKelvin": kelvin.unwrap_or(0)}),
        )
        .await?;
    }
    Ok(())
}

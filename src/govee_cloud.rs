//! Govee cloud API (openapi.api.govee.com) for lights that have no LAN Control (some bulbs, e.g. H6009, never answer the LAN
//! protocol). Needs an API key from the Govee Home app (Profile → Settings → Apply for API Key). The key is stored in
//! `~/.config/homectl/govee.key` (mode 600), is never sent back by this daemon's API and only ever goes to the Govee host.
//! Lights that also answer on the LAN stay on the LAN path; only the rest are driven through the cloud. Govee limits requests
//! (10 control calls a minute per device), so states are cached and the device list is read rarely.
use crate::govee::Light;
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    process::Stdio,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
use tokio::{io::AsyncWriteExt, process::Command};

const DEVICES_TTL: Duration = Duration::from_secs(600);
const STATE_TTL: Duration = Duration::from_secs(45);

/// The id the app uses for a cloud light in `/api/lights/{id}`: the device id without its colons.
pub const PREFIX: &str = "cloud-";

#[derive(Clone)]
struct Dev {
    sku: String,
    device: String,
    name: String,
    kelvin: (u16, u16),
}

#[derive(Clone, Default)]
struct State {
    online: bool,
    on: Option<bool>,
    brightness: Option<u64>,
    rgb: Option<[u64; 3]>,
    kelvin: Option<u64>,
}

type Devices = Mutex<Option<(Instant, Vec<Dev>)>>;
type States = Mutex<std::collections::HashMap<String, (Instant, State)>>;

fn devices_cache() -> &'static Devices {
    static C: OnceLock<Devices> = OnceLock::new();
    C.get_or_init(Default::default)
}

fn states_cache() -> &'static States {
    static C: OnceLock<States> = OnceLock::new();
    C.get_or_init(Default::default)
}

fn key_path() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config/homectl/govee.key")
}

pub fn has_key() -> bool {
    key().is_some()
}

fn key() -> Option<String> {
    let k = std::fs::read_to_string(key_path()).ok()?.trim().to_string();
    valid_key(&k).then_some(k)
}

/// Govee keys are UUID-like; anything else (spaces, quotes, newlines) is refused so it can never end up in a header line.
fn valid_key(k: &str) -> bool {
    (8..=128).contains(&k.len()) && k.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

pub fn set_key(k: &str) -> Result<(), &'static str> {
    let k = k.trim();
    if !valid_key(k) {
        return Err("that does not look like a Govee API key");
    }
    let p = key_path();
    if let Some(dir) = p.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&p).map_err(|_| "could not save the key")?;
    std::io::Write::write_all(&mut f, k.as_bytes()).map_err(|_| "could not save the key")?;
    reset();
    Ok(())
}

pub fn clear_key() {
    let _ = std::fs::remove_file(key_path());
    reset();
}

fn reset() {
    *devices_cache().lock().unwrap() = None;
    states_cache().lock().unwrap().clear();
}

fn base() -> String {
    std::env::var("HOMECTL_GOVEE_API").unwrap_or_else(|_| "https://openapi.api.govee.com".into())
}

fn request_id() -> String {
    crate::pair::rand_bytes::<8>().iter().map(|b| format!("{b:02x}")).collect()
}

/// One call to the Govee API through `curl` (the same way the daemon already announces its tunnel). The key goes to curl on
/// its standard input as a header line, so it never shows up in the process list.
async fn call(method: &str, path: &str, body: Option<Value>) -> Option<Value> {
    let key = key()?;
    let mut cmd = Command::new("curl");
    cmd.args(["-s", "-m", "12", "-X", method, "-H", "@-", "-H", "Content-Type: application/json"]);
    if let Some(b) = &body {
        cmd.args(["-d", &b.to_string()]);
    }
    cmd.arg(format!("{}{path}", base()));
    cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut child = cmd.spawn().ok()?;
    if let Some(mut si) = child.stdin.take() {
        si.write_all(format!("Govee-API-Key: {key}\n").as_bytes()).await.ok()?;
    }
    let out = child.wait_with_output().await.ok()?;
    let v: Value = serde_json::from_slice(&out.stdout).ok()?;
    if v["code"].as_i64() == Some(200) {
        Some(v)
    } else {
        eprintln!("Govee cloud: {} {path} refused (code {})", method, v["code"]);
        None
    }
}

fn parse_devices(v: &Value) -> Vec<Dev> {
    v["data"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|d| {
                    let caps = d["capabilities"].as_array()?;
                    // lights have an on/off switch and a brightness or colour; sockets, sensors and appliances are left to the Govee app
                    let is_light = d["type"].as_str() == Some("devices.types.light");
                    if !is_light || !caps.iter().any(|c| c["instance"] == "powerSwitch") {
                        return None;
                    }
                    let kelvin = caps
                        .iter()
                        .find(|c| c["instance"] == "colorTemperatureK")
                        .map(|c| (c["parameters"]["range"]["min"].as_u64().unwrap_or(2000) as u16, c["parameters"]["range"]["max"].as_u64().unwrap_or(9000) as u16))
                        .unwrap_or((2000, 9000));
                    Some(Dev { sku: d["sku"].as_str()?.into(), device: d["device"].as_str()?.into(), name: d["deviceName"].as_str().unwrap_or("").into(), kelvin })
                })
                .collect()
        })
        .unwrap_or_default()
}

async fn devices(force: bool) -> Vec<Dev> {
    if !force {
        if let Some((at, d)) = devices_cache().lock().unwrap().clone() {
            if at.elapsed() < DEVICES_TTL {
                return d;
            }
        }
    }
    let Some(v) = call("GET", "/router/api/v1/user/devices", None).await else { return devices_cache().lock().unwrap().clone().map(|c| c.1).unwrap_or_default() };
    let d = parse_devices(&v);
    *devices_cache().lock().unwrap() = Some((Instant::now(), d.clone()));
    d
}

fn parse_state(v: &Value) -> State {
    let mut s = State { online: true, ..Default::default() };
    for c in v["payload"]["capabilities"].as_array().into_iter().flatten() {
        let val = &c["state"]["value"];
        match c["instance"].as_str() {
            Some("online") => s.online = val.as_bool().unwrap_or(true),
            Some("powerSwitch") => s.on = val.as_u64().map(|x| x == 1),
            Some("brightness") => s.brightness = val.as_u64(),
            Some("colorRgb") => s.rgb = val.as_u64().map(unpack_rgb),
            Some("colorTemperatureK") => s.kelvin = val.as_u64().filter(|k| *k > 0),
            _ => {}
        }
    }
    s
}

pub fn pack_rgb(r: u8, g: u8, b: u8) -> u32 {
    (r as u32) << 16 | (g as u32) << 8 | b as u32
}

fn unpack_rgb(n: u64) -> [u64; 3] {
    [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

fn norm(id: &str) -> String {
    id.chars().filter(|c| c.is_ascii_alphanumeric()).collect::<String>().to_ascii_uppercase()
}

async fn state_of(d: &Dev) -> State {
    let key = norm(&d.device);
    if let Some((at, s)) = states_cache().lock().unwrap().get(&key) {
        if at.elapsed() < STATE_TTL {
            return s.clone();
        }
    }
    let body = json!({"requestId": request_id(), "payload": {"sku": d.sku, "device": d.device}});
    let s = match call("POST", "/router/api/v1/device/state", Some(body)).await {
        Some(v) => parse_state(&v),
        None => states_cache().lock().unwrap().get(&key).map(|x| x.1.clone()).unwrap_or(State { online: false, ..Default::default() }),
    };
    states_cache().lock().unwrap().insert(key, (Instant::now(), s.clone()));
    s
}

/// The cloud-only lights: those in the Govee account that did not answer on the LAN (`lan` is what the LAN scan found).
pub async fn lights(lan: &[Light], rescan: bool) -> Vec<Light> {
    if !has_key() {
        return vec![];
    }
    let found: Vec<String> = lan.iter().map(|l| norm(&l.device)).collect();
    let list: Vec<Dev> = devices(rescan).await.into_iter().filter(|d| !found.contains(&norm(&d.device))).collect();
    let mut out = vec![];
    for d in list {
        let s = state_of(&d).await;
        out.push(Light::cloud(format!("{PREFIX}{}", norm(&d.device)), d.sku.clone(), d.device.clone(), (!d.name.is_empty()).then(|| d.name.clone()), s.online, s.on, s.brightness, s.rgb, s.kelvin));
    }
    out
}

async fn control(d: &Dev, ty: &str, instance: &str, value: Value) -> bool {
    let body = json!({"requestId": request_id(), "payload": {"sku": d.sku, "device": d.device, "capability": {"type": ty, "instance": instance, "value": value}}});
    call("POST", "/router/api/v1/device/control", Some(body)).await.is_some()
}

/// Same shape as the LAN command: any of power, brightness, colour or white temperature. `id` is `cloud-<device id>`.
pub async fn command(id: &str, on: Option<bool>, brightness: Option<u8>, rgb: Option<[u8; 3]>, kelvin: Option<u16>) -> bool {
    let Some(want) = id.strip_prefix(PREFIX) else { return false };
    let Some(d) = devices(false).await.into_iter().find(|d| norm(&d.device) == want) else { return false };
    let mut ok = true;
    let mut st = states_cache().lock().unwrap().get(&norm(&d.device)).map(|x| x.1.clone()).unwrap_or(State { online: true, ..Default::default() });
    if let Some(on) = on {
        if control(&d, "devices.capabilities.on_off", "powerSwitch", json!(on as u8)).await {
            st.on = Some(on);
        } else {
            ok = false;
        }
    }
    if let Some(b) = brightness {
        let b = b.clamp(1, 100);
        if control(&d, "devices.capabilities.range", "brightness", json!(b)).await {
            st.brightness = Some(b as u64);
        } else {
            ok = false;
        }
    }
    if let Some([r, g, b]) = rgb {
        if control(&d, "devices.capabilities.color_setting", "colorRgb", json!(pack_rgb(r, g, b))).await {
            st.rgb = Some([r as u64, g as u64, b as u64]);
            st.kelvin = None;
        } else {
            ok = false;
        }
    }
    if let Some(k) = kelvin {
        let k = k.clamp(d.kelvin.0, d.kelvin.1);
        if control(&d, "devices.capabilities.color_setting", "colorTemperatureK", json!(k)).await {
            st.kelvin = Some(k as u64);
        } else {
            ok = false;
        }
    }
    // keep what was just set for the next reads: Govee's state can lag a few seconds behind a control call
    states_cache().lock().unwrap().insert(norm(&d.device), (Instant::now(), st));
    ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_are_checked_before_they_are_stored() {
        assert!(valid_key("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        assert!(!valid_key("short"));
        assert!(!valid_key("has space in it 12345"));
        assert!(!valid_key("quote\"inside12345"));
        assert!(!valid_key("line\nbreak12345678"));
    }

    #[test]
    fn colours_pack_into_the_number_the_api_wants() {
        assert_eq!(pack_rgb(255, 0, 0), 0xFF0000);
        assert_eq!(pack_rgb(1, 2, 3), 0x010203);
        assert_eq!(unpack_rgb(0x010203), [1, 2, 3]);
        assert_eq!(unpack_rgb(16777215), [255, 255, 255]);
    }

    #[test]
    fn device_ids_compare_without_colons_or_case() {
        assert_eq!(norm("D0:C9:07:05:9D:1E"), norm("d0c907059d1e"));
    }

    #[test]
    fn only_lights_with_a_power_switch_are_listed() {
        let v = json!({"data": [
            {"sku": "H6009", "device": "AA:BB:CC:DD:EE:FF:00:11", "deviceName": "Bulb", "type": "devices.types.light",
             "capabilities": [{"type": "devices.capabilities.on_off", "instance": "powerSwitch"},
                              {"type": "devices.capabilities.color_setting", "instance": "colorTemperatureK", "parameters": {"range": {"min": 2200, "max": 6500}}}]},
            {"sku": "H7143", "device": "11:22", "type": "devices.types.humidifier", "capabilities": [{"instance": "powerSwitch"}]},
            {"sku": "H6601", "device": "33:44", "type": "devices.types.light", "capabilities": [{"instance": "brightness"}]}
        ]});
        let d = parse_devices(&v);
        assert_eq!(d.len(), 1);
        assert_eq!((d[0].sku.as_str(), d[0].kelvin), ("H6009", (2200, 6500)));
    }

    #[test]
    fn a_state_answer_is_read_like_the_documented_example() {
        let v = json!({"payload": {"capabilities": [
            {"instance": "online", "state": {"value": false}},
            {"instance": "powerSwitch", "state": {"value": 1}},
            {"instance": "brightness", "state": {"value": 5}},
            {"instance": "colorRgb", "state": {"value": 16777215}}]}});
        let s = parse_state(&v);
        assert!(!s.online);
        assert_eq!((s.on, s.brightness, s.rgb), (Some(true), Some(5), Some([255, 255, 255])));
    }
}

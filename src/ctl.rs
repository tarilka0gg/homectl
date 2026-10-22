//! Thin wrappers over the system tools on this machine (wpctl, nmcli, bluetoothctl, ddcutil, OpenRC...).
use serde::Serialize;
use serde_json::{json, Value};
use std::ffi::{c_char, c_int, c_uint, CString};
use tokio::process::Command;

async fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(cmd).args(args).output().await.ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

async fn ok(cmd: &str, args: &[&str]) -> bool {
    run(cmd, args).await.is_some()
}

/// Names and addresses go to CLIs as arguments; keep them to a conservative charset.
pub fn valid_name(s: &str) -> bool {
    !s.is_empty() && s.len() < 80 && s.chars().all(|c| c.is_alphanumeric() || matches!(c, '.' | '-' | '_' | ':' | '@'))
}

// ---- audio (PipeWire) ----
pub async fn audio() -> Value {
    let parse = |s: Option<String>| {
        let s = s.unwrap_or_default();
        let vol = s.split_whitespace().nth(1).and_then(|v| v.parse::<f32>().ok()).map(|v| (v * 100.0).round() as u32);
        json!({ "percent": vol, "muted": s.contains("[MUTED]") })
    };
    json!({
        "output": parse(run("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"]).await),
        "mic": parse(run("wpctl", &["get-volume", "@DEFAULT_AUDIO_SOURCE@"]).await),
    })
}

pub async fn audio_set(target: &str, percent: Option<u8>, muted: Option<bool>) -> bool {
    let node = match target {
        "output" => "@DEFAULT_AUDIO_SINK@",
        "mic" => "@DEFAULT_AUDIO_SOURCE@",
        _ => return false,
    };
    let mut good = true;
    if let Some(p) = percent {
        good &= ok("wpctl", &["set-volume", node, &format!("{}%", p.min(150))]).await;
    }
    if let Some(m) = muted {
        good &= ok("wpctl", &["set-mute", node, if m { "1" } else { "0" }]).await;
    }
    good
}

// ---- bluetooth ----
#[derive(Serialize)]
pub struct BtDevice {
    mac: String,
    name: String,
    connected: bool,
}

pub async fn bluetooth() -> Value {
    let powered = run("bluetoothctl", &["show"]).await.is_some_and(|s| s.contains("Powered: yes"));
    let mut devices = vec![];
    for line in run("bluetoothctl", &["devices"]).await.unwrap_or_default().lines() {
        let mut it = line.splitn(3, ' ');
        if let (Some("Device"), Some(mac), Some(name)) = (it.next(), it.next(), it.next()) {
            let connected = run("bluetoothctl", &["info", mac]).await.is_some_and(|s| s.contains("Connected: yes"));
            devices.push(BtDevice { mac: mac.into(), name: name.into(), connected });
        }
    }
    json!({ "powered": powered, "devices": devices })
}

pub async fn bluetooth_power(on: bool) -> bool {
    ok("bluetoothctl", &["power", if on { "on" } else { "off" }]).await
}

pub async fn bluetooth_link(mac: &str, connect: bool) -> bool {
    valid_name(mac) && ok("bluetoothctl", &[if connect { "connect" } else { "disconnect" }, mac]).await
}

// ---- wifi (NetworkManager) ----
pub async fn wifi() -> Value {
    let enabled = run("nmcli", &["radio", "wifi"]).await.is_some_and(|s| s.trim() == "enabled");
    let active = run("nmcli", &["-t", "-f", "ACTIVE,SSID,SIGNAL", "dev", "wifi"]).await.unwrap_or_default();
    let nets: Vec<Value> = active
        .lines()
        .filter_map(|l| {
            let p: Vec<&str> = l.split(':').collect();
            (p.len() >= 3 && !p[1].is_empty()).then(|| json!({"active": p[0] == "yes", "ssid": p[1], "signal": p[2].parse::<u8>().ok()}))
        })
        .collect();
    json!({ "enabled": enabled, "networks": nets })
}

pub async fn wifi_power(on: bool) -> bool {
    ok("nmcli", &["radio", "wifi", if on { "on" } else { "off" }]).await
}

// ---- services (OpenRC; privileged actions go through doas -n) ----
pub async fn services() -> Value {
    let out = run("rc-status", &["-a", "-C"]).await.unwrap_or_default();
    let list: Vec<Value> = out
        .lines()
        .filter_map(|l| {
            let (name, state) = l.split_once('[')?;
            Some(json!({"name": name.trim(), "state": state.trim_end_matches(']').trim()}))
        })
        .collect();
    json!(list)
}

pub enum Priv {
    Done,
    NeedsPassword,
    WrongPassword,
    Failed,
}

pub async fn service_action(name: &str, action: &str, password: Option<String>) -> Priv {
    if !(matches!(action, "start" | "stop" | "restart") && valid_name(name)) {
        return Priv::Failed;
    }
    match password {
        None => match Command::new("doas").args(["-n", "rc-service", name, action]).output().await {
            Ok(o) if o.status.success() => Priv::Done,
            Ok(o) if String::from_utf8_lossy(&o.stderr).contains("Authorization required") => Priv::NeedsPassword,
            _ => Priv::Failed,
        },
        Some(pw) => {
            let args = vec!["rc-service".to_string(), name.to_string(), action.to_string()];
            match tokio::task::spawn_blocking(move || crate::privileged::doas(args, &pw, std::time::Duration::from_secs(60))).await {
                Ok(Ok(o)) if o.code == 0 => Priv::Done,
                Ok(Ok(o)) if o.wrong_password() => Priv::WrongPassword,
                _ => Priv::Failed,
            }
        }
    }
}

// ---- external monitors (DDC/CI) ----
pub async fn monitors() -> Value {
    let out = run("ddcutil", &["detect", "--brief"]).await.unwrap_or_default();
    let mut list = vec![];
    let mut cur: Option<(String, String)> = None;
    for l in out.lines() {
        if let Some(n) = l.strip_prefix("Display ") {
            cur = Some((n.trim().to_string(), String::new()));
        } else if let (Some(m), true) = (cur.as_mut(), l.trim_start().starts_with("Monitor:")) {
            m.1 = l.trim_start()[8..].trim().to_string();
            list.push(json!({"display": m.0, "model": m.1}));
        }
    }
    json!(list)
}

pub async fn monitor_brightness_get(display: u8) -> Option<u8> {
    let out = run("ddcutil", &["--display", &display.to_string(), "-t", "getvcp", "10"]).await?;
    out.split_whitespace().nth(3)?.parse().ok()
}

pub async fn monitor_brightness_set(display: u8, percent: u8) -> bool {
    ok("ddcutil", &["--display", &display.to_string(), "setvcp", "10", &percent.min(100).to_string(), "--noverify"]).await
}

// ---- clipboard (Wayland) ----
fn wayland_env(c: &mut Command) -> &mut Command {
    if std::env::var_os("WAYLAND_DISPLAY").is_none() {
        c.env("WAYLAND_DISPLAY", "wayland-1");
    }
    c
}

pub async fn clipboard_get() -> Option<String> {
    let out = wayland_env(&mut Command::new("wl-paste")).arg("-n").output().await.ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

pub async fn clipboard_set(text: &str) -> bool {
    use tokio::io::AsyncWriteExt;
    let Ok(mut child) = wayland_env(&mut Command::new("wl-copy")).stdin(std::process::Stdio::piped()).spawn() else {
        return false;
    };
    if let Some(mut si) = child.stdin.take() {
        if si.write_all(text.as_bytes()).await.is_err() {
            return false;
        }
    }
    child.wait().await.is_ok_and(|s| s.success())
}

// ---- webcam controls (C shim, V4L2) ----
extern "C" {
    fn hc_v4l2_list(dev: *const c_char, buf: *mut c_char, cap: c_int) -> c_int;
    fn hc_v4l2_set(dev: *const c_char, id: c_uint, value: c_int) -> c_int;
}

fn video_dev(n: u8) -> CString {
    CString::new(format!("/dev/video{n}")).unwrap()
}

pub fn webcam_controls(n: u8) -> Option<Value> {
    let mut buf = vec![0 as c_char; 8192];
    let len = unsafe { hc_v4l2_list(video_dev(n).as_ptr(), buf.as_mut_ptr(), buf.len() as c_int) };
    if len < 0 {
        return None;
    }
    let bytes: Vec<u8> = buf[..len as usize].iter().map(|&c| c as u8).collect();
    let rows: Vec<Value> = String::from_utf8_lossy(&bytes)
        .lines()
        .filter_map(|l| {
            let p: Vec<&str> = l.split('\t').collect();
            (p.len() == 5).then(|| json!({"name": p[0], "id": p[1].parse::<u32>().ok(), "min": p[2].parse::<i32>().ok(), "max": p[3].parse::<i32>().ok(), "value": p[4].parse::<i32>().ok()}))
        })
        .collect();
    Some(json!(rows))
}

pub fn webcam_set(n: u8, id: u32, value: i32) -> bool {
    unsafe { hc_v4l2_set(video_dev(n).as_ptr(), id, value) == 0 }
}

// ---- per-app audio mixer (PipeWire via pactl) ----
fn pct(v: &Value) -> u32 {
    v["volume"]
        .as_object()
        .and_then(|o| o.values().next())
        .and_then(|c| c["value_percent"].as_str())
        .and_then(|s| s.trim_end_matches('%').parse().ok())
        .unwrap_or(0)
}

async fn pactl_json(what: &str) -> Vec<Value> {
    run("pactl", &["-f", "json", "list", what])
        .await
        .and_then(|s| serde_json::from_str::<Vec<Value>>(&s).ok())
        .unwrap_or_default()
}

pub async fn mixer() -> Value {
    let def_sink = run("pactl", &["get-default-sink"]).await.unwrap_or_default().trim().to_string();
    let def_src = run("pactl", &["get-default-source"]).await.unwrap_or_default().trim().to_string();
    // A device is usable unless its active port reports "not available" (e.g. an HDMI output with nothing plugged in).
    let usable = |v: &Value| {
        let active = v["active_port"].as_str().unwrap_or("");
        v["ports"].as_array().and_then(|ps| ps.iter().find(|p| p["name"] == active)).map_or(true, |p| p["availability"] != "not available")
    };
    let dev = |v: &Value, default: &str| {
        json!({"name": v["name"], "desc": v["description"], "percent": pct(v), "muted": v["mute"], "default": v["name"] == default, "available": usable(v), "state": v["state"]})
    };
    let sinks: Vec<Value> = pactl_json("sinks").await.iter().map(|s| dev(s, &def_sink)).collect();
    let sources: Vec<Value> = pactl_json("sources")
        .await
        .iter()
        .filter(|s| s["monitor_of_sink"].is_null() && !s["name"].as_str().unwrap_or("").ends_with(".monitor"))
        .map(|s| dev(s, &def_src))
        .collect();
    let app = |v: &Value| {
        let p = &v["properties"];
        let name = ["application.name", "application.process.binary", "media.name"]
            .iter()
            .filter_map(|k| p[*k].as_str())
            .find(|s| !s.is_empty() && *s != "(null)")
            .unwrap_or("Unknown");
        json!({"id": v["index"], "app": name, "title": p["media.name"], "percent": pct(v), "muted": v["mute"], "playing": !v["corked"].as_bool().unwrap_or(false)})
    };
    let streams: Vec<Value> = pactl_json("sink-inputs").await.iter().map(app).collect();
    let recording: Vec<Value> = pactl_json("source-outputs").await.iter().map(app).collect();
    json!({"sinks": sinks, "sources": sources, "streams": streams, "recording": recording})
}

pub async fn mixer_set(kind: &str, id: &str, percent: Option<u16>, muted: Option<bool>, default: bool) -> bool {
    let (vol, mute, def) = match kind {
        "stream" => ("set-sink-input-volume", "set-sink-input-mute", None),
        "recording" => ("set-source-output-volume", "set-source-output-mute", None),
        "sink" => ("set-sink-volume", "set-sink-mute", Some("set-default-sink")),
        "source" => ("set-source-volume", "set-source-mute", Some("set-default-source")),
        _ => return false,
    };
    if !valid_name(id) {
        return false;
    }
    let mut good = true;
    if let Some(p) = percent {
        good &= ok("pactl", &[vol, id, &format!("{}%", p.min(150))]).await;
    }
    if let Some(m) = muted {
        good &= ok("pactl", &[mute, id, if m { "1" } else { "0" }]).await;
    }
    if let (true, Some(d)) = (default, def) {
        good &= ok("pactl", &[d, id]).await;
    }
    good
}

// ---- power ----
/// Runs after a short delay so the HTTP reply gets out first. HOMECTL_DRYRUN=1 only logs.
pub fn power(action: &str, password: Option<String>) -> bool {
    let (login, plain): (&[&str], &str) = match action {
        "poweroff" => (&["poweroff"], "poweroff"),
        "reboot" => (&["reboot"], "reboot"),
        "suspend" => (&["suspend"], "pm-suspend"),
        "lock" => (&["lock-session"], ""),
        _ => return false,
    };
    let action = action.to_string();
    let (login, plain): (Vec<String>, String) = (login.iter().map(|s| s.to_string()).collect(), plain.to_string());
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
        if std::env::var("HOMECTL_DRYRUN").is_ok_and(|v| v == "1") {
            eprintln!("DRYRUN power {action}");
            return;
        }
        let l: Vec<&str> = login.iter().map(|s| s.as_str()).collect();
        if ok("loginctl", &l).await || plain.is_empty() {
            return;
        }
        // Hosts without logind (e.g. Alpine): doas (with the user's password if the app sent it), then direct.
        if let Some(pw) = password {
            let p = plain.clone();
            let done = tokio::task::spawn_blocking(move || crate::privileged::doas(vec![p], &pw, std::time::Duration::from_secs(30))).await;
            if matches!(done, Ok(Ok(ref o)) if o.code == 0) {
                return;
            }
        }
        if !ok("doas", &["-n", &plain]).await {
            ok(&plain, &[]).await;
        }
    });
    true
}

// ---- process control ----
pub fn signal(pid: i32, sig: &str) -> bool {
    let s = match sig {
        "term" => libc::SIGTERM,
        "kill" => libc::SIGKILL,
        "stop" => libc::SIGSTOP,
        "cont" => libc::SIGCONT,
        _ => return false,
    };
    pid > 1 && pid != std::process::id() as i32 && unsafe { libc::kill(pid, s) } == 0
}

// ---- airplane mode (all radios) ----
pub async fn radios() -> Value {
    let wifi = run("nmcli", &["radio", "wifi"]).await.is_some_and(|s| s.trim() == "enabled");
    let bt = run("bluetoothctl", &["show"]).await.is_some_and(|s| s.contains("Powered: yes"));
    json!({"wifi": wifi, "bluetooth": bt})
}

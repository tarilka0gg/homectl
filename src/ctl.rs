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
            Ok(o) if crate::privileged::says_required(&String::from_utf8_lossy(&o.stderr)) => Priv::NeedsPassword,
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

/// What the clipboard holds: the MIME types on offer (empty = nothing).
pub async fn clipboard_types() -> Vec<String> {
    let Ok(o) = wayland_env(&mut Command::new("wl-paste")).arg("-l").output().await else { return vec![] };
    if !o.status.success() {
        return vec![];
    }
    String::from_utf8_lossy(&o.stdout).lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
}

pub async fn clipboard_get() -> Option<String> {
    let out = wayland_env(&mut Command::new("wl-paste")).args(["-n", "-t", "text"]).output().await.ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// The image on the clipboard, if any (png/jpeg/…), at most 32 MB.
pub async fn clipboard_image(mime: &str) -> Option<Vec<u8>> {
    if !mime.starts_with("image/") || !mime.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '+' | '-')) {
        return None;
    }
    let out = wayland_env(&mut Command::new("wl-paste")).args(["-n", "-t", mime]).output().await.ok()?;
    (out.status.success() && out.stdout.len() <= 32 << 20).then_some(out.stdout)
}

/// Puts raw bytes of a given type (e.g. a screenshot) on the clipboard.
pub async fn clipboard_set_bytes(mime: &str, data: &[u8]) -> bool {
    use tokio::io::AsyncWriteExt;
    if !mime.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '+' | '-' | ';' | '=')) {
        return false;
    }
    let Ok(mut child) = wayland_env(&mut Command::new("wl-copy")).args(["--type", mime]).stdin(std::process::Stdio::piped()).spawn() else { return false };
    if let Some(mut si) = child.stdin.take() {
        if si.write_all(data).await.is_err() {
            return false;
        }
    }
    child.wait().await.is_ok_and(|s| s.success())
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
/// Whether a power action can be done without a password: the daemon runs as root, logind lets it (polkit is only asked, nothing is
/// changed), or a doas rule says `nopass` for it. When none of these holds the app is told to ask for the doas password.
pub async fn power_without_password(action: &str) -> bool {
    let (polkit, plain) = match action {
        "poweroff" => ("org.freedesktop.login1.power-off", "poweroff"),
        "reboot" => ("org.freedesktop.login1.reboot", "reboot"),
        "suspend" => ("org.freedesktop.login1.suspend", "pm-suspend"),
        _ => return true, // locking the screen needs nothing special
    };
    if unsafe { libc::geteuid() } == 0 {
        return true;
    }
    let pid = std::process::id().to_string();
    if ok("pkcheck", &["--action-id", polkit, "--process", &pid]).await {
        return true;
    }
    run("doas", &["-C", "/etc/doas.conf", plain]).await.is_some_and(|s| s.contains("nopass"))
}

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
        eprintln!("power {action}: logind refused, trying doas");
        // Hosts without logind (e.g. Alpine): doas (with the user's password if the app sent it), then direct.
        if let Some(pw) = password {
            let p = plain.clone();
            let done = tokio::task::spawn_blocking(move || crate::privileged::doas(vec![p], &pw, std::time::Duration::from_secs(30))).await;
            if matches!(done, Ok(Ok(ref o)) if o.code == 0) {
                return;
            }
            eprintln!("power {action}: doas with the password did not work");
        }
        if !ok("doas", &["-n", &plain]).await && !ok(&plain, &[]).await {
            eprintln!("power {action}: no way to do it without a password (see System → doas)");
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

// ---- Tailscale (the `tailscale` CLI; devices of this tailnet) ----
/// The machines of this tailnet, this one excluded: name, address, whether it is online, and its system.
pub async fn tailscale_peers() -> Value {
    let Some(text) = run("tailscale", &["status", "--json"]).await else {
        return json!({ "available": false, "peers": [] });
    };
    let v: Value = serde_json::from_str(&text).unwrap_or_default();
    let mut peers: Vec<Value> = v["Peer"]
        .as_object()
        .into_iter()
        .flat_map(|m| m.values())
        .map(|p| {
            json!({
                "name": p["HostName"].as_str().unwrap_or(""),
                "dns": p["DNSName"].as_str().unwrap_or("").trim_end_matches('.'),
                "ip": p["TailscaleIPs"][0].as_str().unwrap_or(""),
                "online": p["Online"].as_bool().unwrap_or(false),
                "os": p["OS"].as_str().unwrap_or(""),
            })
        })
        .collect();
    peers.sort_by(|a, b| b["online"].as_bool().cmp(&a["online"].as_bool()).then_with(|| a["name"].as_str().cmp(&b["name"].as_str())));
    json!({ "available": true, "state": v["BackendState"].as_str().unwrap_or(""), "peers": peers })
}

// ---- webcam preview (one frame, as JPEG) ----
/// One frame of webcam `n`, scaled down so it is quick to send to the phone. None if there is no such camera or no frame.
pub async fn webcam_frame(n: u8) -> Option<Vec<u8>> {
    let dev = format!("/dev/video{n}");
    let out = tokio::time::timeout(
        std::time::Duration::from_secs(8),
        Command::new("ffmpeg")
            .args(["-loglevel", "error", "-f", "v4l2", "-i", &dev, "-frames:v", "1", "-vf", "scale=640:-2", "-f", "image2", "-c:v", "mjpeg", "-"])
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    (out.status.success() && out.stdout.len() > 100).then_some(out.stdout)
}

// ---- Bluetooth scan and pairing ----
/// Scans for nearby devices for eight seconds, then returns the list (what was seen or paired before).
pub async fn bluetooth_scan() -> Value {
    let _ = Command::new("bluetoothctl").args(["--timeout", "8", "scan", "on"]).output().await;
    bluetooth().await
}

/// Pairs with a device, trusts it and connects. Each step must succeed.
pub async fn bluetooth_pair(mac: &str) -> bool {
    valid_name(mac)
        && ok("bluetoothctl", &["pair", mac]).await
        && ok("bluetoothctl", &["trust", mac]).await
        && ok("bluetoothctl", &["connect", mac]).await
}

// ---- wired network (NetworkManager) ----
/// The wired connections: device, state, and the connection name.
pub async fn ethernet() -> Value {
    let out = run("nmcli", &["-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device"]).await.unwrap_or_default();
    let list: Vec<Value> = out
        .lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.splitn(4, ':').collect();
            (f.len() == 4 && f[1] == "ethernet").then(|| json!({ "device": f[0], "state": f[2], "connection": f[3] }))
        })
        .collect();
    json!(list)
}

// ---- ACPI platform profile (low-power / balanced / performance) ----
const PROFILE: &str = "/sys/firmware/acpi/platform_profile";

/// The firmware's power profile: what it is now and what it offers. `available: false` on machines without one.
pub fn profile() -> Value {
    let cur = std::fs::read_to_string(PROFILE).map(|s| s.trim().to_string());
    let choices = std::fs::read_to_string(format!("{PROFILE}_choices")).unwrap_or_default();
    match cur {
        Ok(cur) => json!({ "available": true, "current": cur, "choices": choices.split_whitespace().collect::<Vec<_>>() }),
        Err(_) => json!({ "available": false }),
    }
}

/// Writes the profile: directly when the file is writable (root), otherwise through doas (a nopass rule, or the password the app sends).
pub async fn set_profile(name: &str, password: Option<String>) -> Priv {
    let choices = std::fs::read_to_string(format!("{PROFILE}_choices")).unwrap_or_default();
    if !choices.split_whitespace().any(|c| c == name) {
        return Priv::Failed;
    }
    if std::fs::write(PROFILE, name).is_ok() {
        return Priv::Done;
    }
    let script = format!("echo {name} > {PROFILE}");
    match password {
        None => match Command::new("doas").args(["-n", "sh", "-c", &script]).output().await {
            Ok(o) if o.status.success() => Priv::Done,
            Ok(o) if crate::privileged::says_required(&String::from_utf8_lossy(&o.stderr)) => Priv::NeedsPassword,
            _ => Priv::Failed,
        },
        Some(pw) => {
            let args = vec!["sh".to_string(), "-c".to_string(), script];
            match tokio::task::spawn_blocking(move || crate::privileged::doas(args, &pw, std::time::Duration::from_secs(30))).await {
                Ok(Ok(o)) if o.code == 0 => Priv::Done,
                Ok(Ok(o)) if o.wrong_password() => Priv::WrongPassword,
                _ => Priv::Failed,
            }
        }
    }
}

// ---- Wake-on-LAN ----
/// The hardware addresses of this machine's real network cards (Ethernet and Wi-Fi), so the phone can wake it later.
pub fn macs() -> Value {
    let mut out = vec![];
    if let Ok(rd) = std::fs::read_dir("/sys/class/net") {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            let p = e.path();
            if !p.join("device").exists() {
                continue; // virtual: lo, bridges, tunnels, veth
            }
            if let Ok(m) = std::fs::read_to_string(p.join("address")) {
                let m = m.trim().to_string();
                if m.len() == 17 && m != "00:00:00:00:00:00" {
                    out.push(json!({ "device": name, "mac": m, "wireless": p.join("wireless").exists() }));
                }
            }
        }
    }
    json!(out)
}

/// Sends a Wake-on-LAN magic packet (6 x 0xFF, then the address 16 times) to the LAN's broadcast address.
pub async fn wake(mac: &str) -> bool {
    let bytes: Vec<u8> = mac.split(':').filter_map(|h| u8::from_str_radix(h, 16).ok()).collect();
    if bytes.len() != 6 {
        return false;
    }
    let mut pkt = vec![0xffu8; 6];
    for _ in 0..16 {
        pkt.extend_from_slice(&bytes);
    }
    let Ok(sock) = tokio::net::UdpSocket::bind("0.0.0.0:0").await else { return false };
    if sock.set_broadcast(true).is_err() {
        return false;
    }
    let mut sent = false;
    for port in [9u16, 7] {
        sent |= sock.send_to(&pkt, ("255.255.255.255", port)).await.is_ok();
    }
    sent
}

// ---- live webcam (MJPEG) ----
/// ffmpeg writing a motion-JPEG stream to its stdout, until it is dropped. The caller frames it as multipart/x-mixed-replace.
pub fn webcam_stream(n: u8) -> Option<tokio::process::Child> {
    Command::new("ffmpeg")
        .args(["-loglevel", "error", "-f", "v4l2", "-framerate", "15", "-i", &format!("/dev/video{n}"), "-vf", "scale=640:-2", "-q:v", "7", "-f", "mpjpeg", "-boundary_tag", "frame", "-"])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .ok()
}

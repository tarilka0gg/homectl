//! Turning screens off and on: the built-in panel by backlight, external monitors by DDC/CI power mode
//! (DPMS can leave some monitors cycling test patterns), optionally also disabling the compositor output
//! (niri) so the GPU stops scanning out and can sleep. Method taken from the machine owner's own notes.
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    process::Stdio,
    sync::Mutex,
    time::{Duration, Instant},
};
use tokio::process::Command;

struct Ext {
    n: u32,
    connector: String,
    model: String,
}

static DETECT: Mutex<Option<(Instant, Vec<Ext>)>> = Mutex::new(None);

fn dry() -> bool {
    std::env::var("HOMECTL_DRYRUN").is_ok_and(|v| v == "1")
}

async fn out(cmd: &str, args: &[&str]) -> Option<String> {
    let o = Command::new(cmd).args(args).stdin(Stdio::null()).output().await.ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

fn state_file() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".cache/homectl/backlight-prev")
}

/// (device, current, percent, max) for the first backlight, via brightnessctl's machine-readable output.
async fn backlight() -> Option<(String, u64, u64, u64)> {
    let s = out("brightnessctl", &["-m", "-c", "backlight"]).await?;
    let f: Vec<&str> = s.lines().next()?.split(',').collect();
    Some((f.first()?.to_string(), f.get(2)?.parse().ok()?, f.get(3)?.trim_end_matches('%').parse().ok()?, f.get(4)?.parse().ok()?))
}

async fn detect(force: bool) -> Vec<(u32, String, String)> {
    let cached = DETECT.lock().unwrap().as_ref().filter(|(t, _)| !force && t.elapsed() < Duration::from_secs(300)).map(|(_, v)| v.iter().map(|e| (e.n, e.connector.clone(), e.model.clone())).collect::<Vec<_>>());
    if let Some(c) = cached {
        return c;
    }
    let text = out("ddcutil", &["detect"]).await.unwrap_or_default();
    let mut list: Vec<Ext> = vec![];
    let mut cur: Option<Ext> = None;
    for l in text.lines() {
        if let Some(n) = l.strip_prefix("Display ").and_then(|x| x.trim().parse::<u32>().ok()) {
            if let Some(e) = cur.take() {
                list.push(e);
            }
            cur = Some(Ext { n, connector: String::new(), model: String::new() });
        } else if l.starts_with("Invalid display") {
            if let Some(e) = cur.take() {
                list.push(e);
            }
        } else if let Some(e) = cur.as_mut() {
            let t = l.trim();
            if let Some(v) = t.strip_prefix("DRM_connector:").or_else(|| t.strip_prefix("DRM connector:")) {
                // "card1-DP-1" → "DP-1"
                e.connector = v.trim().splitn(2, '-').nth(1).unwrap_or(v.trim()).to_string();
            } else if let Some(v) = t.strip_prefix("Model:") {
                if !v.trim().is_empty() {
                    e.model = v.trim().to_string();
                }
            } else if let Some(v) = t.strip_prefix("Monitor:") {
                if e.model.is_empty() {
                    e.model = v.trim().trim_matches(':').to_string();
                }
            }
        }
    }
    if let Some(e) = cur.take() {
        list.push(e);
    }
    let res = list.iter().map(|e| (e.n, e.connector.clone(), e.model.clone())).collect();
    *DETECT.lock().unwrap() = Some((Instant::now(), list));
    res
}

/// DDC/CI power mode: x01 on, x04 standby, x05 off. None if the monitor didn't answer.
async fn ext_on(n: u32) -> Option<bool> {
    let s = out("ddcutil", &["getvcp", "d6", "--display", &n.to_string(), "-t"]).await?;
    let v = s.split_whitespace().last()?.trim_start_matches('x');
    Some(v == "01")
}

pub async fn list() -> Value {
    let internal = backlight().await.map(|(dev, cur, pct, _)| json!({"id": "internal", "title": "Built-in screen", "detail": dev, "on": cur > 0, "percent": pct}));
    let mut ext = vec![];
    for (n, connector, model) in detect(false).await {
        let on = ext_on(n).await;
        ext.push(json!({"id": n.to_string(), "title": if model.is_empty() { format!("External monitor ({})", if connector.is_empty() { n.to_string() } else { connector.clone() }) } else { model }, "detail": connector, "on": on}));
    }
    json!({"internal": internal, "external": ext, "compositor": niri_available()})
}

fn niri_sock() -> Option<PathBuf> {
    let uid = unsafe { libc::getuid() };
    std::fs::read_dir(format!("/run/user/{uid}")).ok()?.flatten().map(|e| e.path()).find(|p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("niri.wayland-") && n.to_string_lossy().ends_with(".sock")))
}

fn niri_available() -> bool {
    niri_sock().is_some()
}

async fn niri_output(name: &str, on: bool) -> bool {
    if name.is_empty() {
        return false;
    }
    let Some(sock) = niri_sock() else { return false };
    if dry() {
        eprintln!("DRYRUN niri msg output {name} {}", if on { "on" } else { "off" });
        return true;
    }
    Command::new("niri")
        .args(["msg", "output", name, if on { "on" } else { "off" }])
        .env("NIRI_SOCKET", sock)
        .stdin(Stdio::null())
        .output()
        .await
        .is_ok_and(|o| o.status.success())
}

async fn set_internal(on: bool) -> Result<(), String> {
    let (dev, cur, _, max) = backlight().await.ok_or("no backlight")?;
    if dry() {
        eprintln!("DRYRUN backlight {} ({dev})", if on { "on" } else { "off" });
        return Ok(());
    }
    let target = if on {
        std::fs::read_to_string(state_file()).ok().and_then(|s| s.trim().parse::<u64>().ok()).filter(|v| *v > 0).unwrap_or(max)
    } else {
        if cur > 0 {
            if let Some(d) = state_file().parent() {
                let _ = std::fs::create_dir_all(d);
            }
            let _ = std::fs::write(state_file(), cur.to_string());
        }
        0
    };
    let o = Command::new("brightnessctl").args(["-d", &dev, "set", &target.to_string()]).stdin(Stdio::null()).output().await.map_err(|e| e.to_string())?;
    if o.status.success() { Ok(()) } else { Err(String::from_utf8_lossy(&o.stderr).trim().to_string()) }
}

async fn set_external(n: u32, connector: &str, on: bool, compositor: bool) -> Result<(), String> {
    // Order matters: wake the compositor output before the monitor, and put the monitor to sleep before dropping the output.
    if on && compositor {
        niri_output(connector, true).await;
    }
    if dry() {
        eprintln!("DRYRUN ddcutil setvcp d6 {} --display {n}", if on { 1 } else { 4 });
    } else {
        let o = Command::new("ddcutil")
            .args(["setvcp", "d6", if on { "1" } else { "4" }, "--display", &n.to_string(), "--noverify"])
            .stdin(Stdio::null())
            .output()
            .await
            .map_err(|e| e.to_string())?;
        if !o.status.success() {
            return Err(String::from_utf8_lossy(&o.stderr).trim().to_string());
        }
    }
    if !on && compositor {
        niri_output(connector, false).await;
    }
    Ok(())
}

/// `which`: "internal", a ddcutil display number, or "all".
pub async fn set(which: &str, on: bool, compositor: bool) -> Result<(), String> {
    let ext = detect(false).await;
    match which {
        "internal" => set_internal(on).await,
        "all" => {
            let mut err = None;
            for (n, c, _) in &ext {
                if let Err(e) = set_external(*n, c, on, compositor).await {
                    err = Some(e);
                }
            }
            if let Err(e) = set_internal(on).await {
                err = Some(e);
            }
            err.map_or(Ok(()), Err)
        }
        n => {
            let n: u32 = n.parse().map_err(|_| "bad display id")?;
            let (_, c, _) = ext.iter().find(|(m, _, _)| *m == n).ok_or("unknown display")?;
            set_external(n, c, on, compositor).await
        }
    }
}

use serde::Serialize;
use tokio::process::Command;

#[derive(Serialize)]
pub struct Stats {
    hostname: String,
    uptime_s: u64,
    load: [f32; 3],
    mem_total_kb: u64,
    mem_available_kb: u64,
}

fn read(path: &str) -> String {
    std::fs::read_to_string(path).unwrap_or_default()
}

pub fn stats() -> Stats {
    let load: Vec<f32> = read("/proc/loadavg")
        .split_whitespace()
        .take(3)
        .filter_map(|v| v.parse().ok())
        .collect();
    let meminfo = read("/proc/meminfo");
    let field = |name: &str| {
        meminfo
            .lines()
            .find_map(|l| l.strip_prefix(name)?.trim_start_matches(':').split_whitespace().next()?.parse().ok())
            .unwrap_or(0)
    };
    Stats {
        hostname: read("/proc/sys/kernel/hostname").trim().to_string(),
        uptime_s: read("/proc/uptime")
            .split('.')
            .next()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0),
        load: [
            load.first().copied().unwrap_or(0.0),
            load.get(1).copied().unwrap_or(0.0),
            load.get(2).copied().unwrap_or(0.0),
        ],
        mem_total_kb: field("MemTotal"),
        mem_available_kb: field("MemAvailable"),
    }
}

/// Backlight level in percent, via brightnessctl's machine-readable output.
pub async fn brightness_get() -> Option<u8> {
    let out = Command::new("brightnessctl").arg("-m").output().await.ok()?;
    let line = String::from_utf8(out.stdout).ok()?;
    line.split(',').nth(3)?.trim_end_matches('%').parse().ok()
}

pub async fn brightness_set(percent: u8) -> bool {
    Command::new("brightnessctl")
        .args(["set", &format!("{percent}%")])
        .output()
        .await
        .is_ok_and(|o| o.status.success())
}

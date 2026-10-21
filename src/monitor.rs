//! btop-style system monitor: a 1 Hz sampler keeps the latest snapshot plus short history,
//! read from /proc and /sys only (no GPU wake-ups, see `gpu`).
use serde_json::{json, Value};
use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, RwLock},
    time::{Duration, Instant},
};

pub type Snapshot = Arc<RwLock<Value>>;

const HISTORY: usize = 120;
const CLK_TCK: f64 = 100.0;

fn read(p: &str) -> String {
    std::fs::read_to_string(p).unwrap_or_default()
}

fn num(p: &str) -> Option<f64> {
    read(p).trim().parse().ok()
}

fn cpu_times() -> Vec<(u64, u64)> {
    read("/proc/stat")
        .lines()
        .filter(|l| l.starts_with("cpu"))
        .map(|l| {
            let v: Vec<u64> = l.split_whitespace().skip(1).filter_map(|x| x.parse().ok()).collect();
            let idle = v.get(3).copied().unwrap_or(0) + v.get(4).copied().unwrap_or(0);
            (v.iter().take(8).sum(), idle)
        })
        .collect()
}

fn net_bytes() -> (u64, u64) {
    let (mut rx, mut tx) = (0, 0);
    for l in read("/proc/net/dev").lines().skip(2) {
        let Some((name, rest)) = l.split_once(':') else { continue };
        let n = name.trim();
        if n == "lo" || n.starts_with("veth") || n.starts_with("docker") || n.starts_with("br-") || n.starts_with("tailscale") {
            continue;
        }
        let f: Vec<u64> = rest.split_whitespace().filter_map(|x| x.parse().ok()).collect();
        rx += f.first().copied().unwrap_or(0);
        tx += f.get(8).copied().unwrap_or(0);
    }
    (rx, tx)
}

/// Sectors read/written on whole disks (512 B each).
fn disk_sectors() -> (u64, u64) {
    let (mut r, mut w) = (0, 0);
    for l in read("/proc/diskstats").lines() {
        let f: Vec<&str> = l.split_whitespace().collect();
        let Some(name) = f.get(2) else { continue };
        let whole = (name.starts_with("nvme") && name.contains('n') && !name.contains('p'))
            || ((name.starts_with("sd") || name.starts_with("vd")) && name.len() == 3)
            || (name.starts_with("mmcblk") && !name.contains('p'));
        if whole {
            r += f.get(5).and_then(|x| x.parse::<u64>().ok()).unwrap_or(0);
            w += f.get(9).and_then(|x| x.parse::<u64>().ok()).unwrap_or(0);
        }
    }
    (r, w)
}

struct Proc {
    pid: u32,
    name: String,
    ticks: u64,
    rss: u64,
    state: char,
}

fn procs() -> Vec<Proc> {
    let Ok(dir) = std::fs::read_dir("/proc") else { return vec![] };
    dir.filter_map(|e| {
        let pid: u32 = e.ok()?.file_name().to_str()?.parse().ok()?;
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let (l, r) = (stat.find('(')?, stat.rfind(')')?);
        let f: Vec<&str> = stat[r + 2..].split_whitespace().collect();
        Some(Proc {
            pid,
            name: stat[l + 1..r].to_string(),
            state: f.first()?.chars().next()?,
            ticks: f.get(11)?.parse::<u64>().ok()? + f.get(12)?.parse::<u64>().ok()?,
            rss: f.get(21)?.parse::<u64>().ok()? * 4096,
        })
    })
    .collect()
}

fn hwmon_temp(name: &str, label_contains: Option<&str>) -> Option<f64> {
    for d in std::fs::read_dir("/sys/class/hwmon").ok()?.flatten() {
        let p = d.path();
        if read(&format!("{}/name", p.display())).trim() != name {
            continue;
        }
        for i in 1..8 {
            let lab = read(&format!("{}/temp{i}_label", p.display()));
            if label_contains.is_none_or(|l| lab.contains(l)) {
                if let Some(t) = num(&format!("{}/temp{i}_input", p.display())) {
                    return Some(t / 1000.0);
                }
            }
        }
    }
    None
}

fn cpu_freq_mhz() -> (f64, f64) {
    let mut v = vec![];
    for i in 0..256 {
        match num(&format!("/sys/devices/system/cpu/cpu{i}/cpufreq/scaling_cur_freq")) {
            Some(k) => v.push(k / 1000.0),
            None if i > 0 => break,
            None => {}
        }
    }
    let avg = if v.is_empty() { 0.0 } else { v.iter().sum::<f64>() / v.len() as f64 };
    (avg, v.iter().cloned().fold(0.0, f64::max))
}

fn battery() -> Value {
    for i in 0..4 {
        let b = format!("/sys/class/power_supply/BAT{i}");
        if let Some(cap) = num(&format!("{b}/capacity")) {
            let watts = num(&format!("{b}/power_now")).map(|p| p / 1e6);
            let ac = ["AC", "ACAD", "AC0", "ADP1"].iter().any(|a| num(&format!("/sys/class/power_supply/{a}/online")) == Some(1.0));
            return json!({"percent": cap, "status": read(&format!("{b}/status")).trim(), "watts": watts, "ac": ac});
        }
    }
    Value::Null
}

/// NVIDIA GPU state from sysfs only. Asking the driver (nvidia-smi/NVML) would wake a sleeping dGPU
/// and burn battery, so utilisation is only read when HOMECTL_GPU=1 *and* the card is already active.
fn gpu() -> Value {
    let Ok(dir) = std::fs::read_dir("/sys/bus/pci/devices") else { return Value::Null };
    for d in dir.flatten() {
        let p = d.path();
        let (vendor, class) = (read(&format!("{}/vendor", p.display())), read(&format!("{}/class", p.display())));
        if vendor.trim() == "0x10de" && class.starts_with("0x03") {
            let state = read(&format!("{}/power/runtime_status", p.display())).trim().to_string();
            let mut out = json!({"state": state});
            if state == "active" && std::env::var("HOMECTL_GPU").is_ok_and(|v| v == "1") {
                if let Ok(o) = std::process::Command::new("nvidia-smi")
                    .args(["--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw", "--format=csv,noheader,nounits"])
                    .output()
                {
                    let s = String::from_utf8_lossy(&o.stdout);
                    let f: Vec<&str> = s.trim().split(", ").collect();
                    if f.len() == 6 {
                        out = json!({"state": state, "name": f[0], "util": f[1].parse::<f64>().ok(), "mem_used_mb": f[2].parse::<f64>().ok(),
                                     "mem_total_mb": f[3].parse::<f64>().ok(), "temp": f[4].parse::<f64>().ok(), "watts": f[5].parse::<f64>().ok()});
                    }
                }
            }
            return out;
        }
    }
    Value::Null
}

fn disks() -> Value {
    const REAL: &[&str] = &["ext2", "ext3", "ext4", "btrfs", "xfs", "vfat", "f2fs", "zfs", "ntfs", "ntfs3", "fuseblk", "exfat", "bcachefs"];
    let mut seen = std::collections::HashSet::new();
    let mut list = vec![];
    for l in read("/proc/mounts").lines() {
        let f: Vec<&str> = l.split_whitespace().collect();
        if f.len() < 3 || !REAL.contains(&f[2]) || !seen.insert(f[0].to_string()) {
            continue;
        }
        let mount = f[1].replace("\\040", " ");
        let Ok(c) = std::ffi::CString::new(mount.clone()) else { continue };
        let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
        if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
            continue;
        }
        let (bs, total, free) = (st.f_frsize as u64, st.f_blocks as u64, st.f_bfree as u64);
        if total > 0 {
            list.push(json!({"mount": mount, "dev": f[0], "total": total * bs, "used": (total - free) * bs}));
        }
    }
    json!(list)
}

fn push(h: &mut VecDeque<f64>, v: f64) {
    if h.len() == HISTORY {
        h.pop_front();
    }
    h.push_back(v);
}

pub fn start() -> Snapshot {
    let snap: Snapshot = Arc::new(RwLock::new(Value::Null));
    let out = snap.clone();
    std::thread::spawn(move || {
        let info = json!({
            "hostname": read("/proc/sys/kernel/hostname").trim(),
            "kernel": read("/proc/sys/kernel/osrelease").trim(),
            "cpu_model": read("/proc/cpuinfo").lines().find(|l| l.starts_with("model name")).and_then(|l| l.split(':').nth(1)).unwrap_or("").trim(),
            "os": read("/etc/os-release").lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).unwrap_or("").trim_matches(|c| c == '"' || c == '\''),
        });
        let (mut cpu_h, mut mem_h, mut rx_h, mut tx_h) = (VecDeque::new(), VecDeque::new(), VecDeque::new(), VecDeque::new());
        let mut prev_cpu = cpu_times();
        let (mut prev_rx, mut prev_tx) = net_bytes();
        let (mut prev_dr, mut prev_dw) = disk_sectors();
        let mut prev_ticks: HashMap<u32, u64> = procs().into_iter().map(|p| (p.pid, p.ticks)).collect();
        let mut last = Instant::now();
        let mut disk_cache = disks();
        let mut disk_at = Instant::now();
        loop {
            std::thread::sleep(Duration::from_secs(1));
            let now = Instant::now();
            let dt = now.duration_since(last).as_secs_f64().max(0.001);
            last = now;

            let cur = cpu_times();
            let pct: Vec<f64> = cur
                .iter()
                .zip(&prev_cpu)
                .map(|(c, p)| {
                    let (dt_, di) = (c.0.saturating_sub(p.0) as f64, c.1.saturating_sub(p.1) as f64);
                    if dt_ > 0.0 { 100.0 * (dt_ - di) / dt_ } else { 0.0 }
                })
                .collect();
            prev_cpu = cur;
            let total = pct.first().copied().unwrap_or(0.0);

            let mem = read("/proc/meminfo");
            let m = |k: &str| -> u64 {
                mem.lines().find_map(|l| l.strip_prefix(k)?.trim_start_matches(':').split_whitespace().next()?.parse::<u64>().ok()).unwrap_or(0) * 1024
            };
            let (mt, ma) = (m("MemTotal"), m("MemAvailable"));
            let mem_pct = if mt > 0 { 100.0 * (mt - ma) as f64 / mt as f64 } else { 0.0 };

            let (rx, tx) = net_bytes();
            let (rxr, txr) = (rx.saturating_sub(prev_rx) as f64 / dt, tx.saturating_sub(prev_tx) as f64 / dt);
            (prev_rx, prev_tx) = (rx, tx);
            let (dr, dw) = disk_sectors();
            let (drr, dwr) = (dr.saturating_sub(prev_dr) as f64 * 512.0 / dt, dw.saturating_sub(prev_dw) as f64 * 512.0 / dt);
            (prev_dr, prev_dw) = (dr, dw);

            let ps = procs();
            let mut rows: Vec<(f64, &Proc)> = ps
                .iter()
                .map(|p| (prev_ticks.get(&p.pid).map_or(0.0, |&t| 100.0 * p.ticks.saturating_sub(t) as f64 / (CLK_TCK * dt)), p))
                .collect();
            let nproc = rows.len();
            rows.sort_by(|a, b| b.0.total_cmp(&a.0));
            let mut pick: Vec<(f64, &Proc)> = rows.iter().take(25).cloned().collect();
            let mut by_mem = rows.clone();
            by_mem.sort_by_key(|r| std::cmp::Reverse(r.1.rss));
            for r in by_mem.into_iter().take(25) {
                if !pick.iter().any(|x| x.1.pid == r.1.pid) {
                    pick.push(r);
                }
            }
            let top: Vec<Value> = pick.iter().map(|(c, p)| json!({"pid": p.pid, "name": p.name, "cpu": (c * 10.0).round() / 10.0, "mem": p.rss, "state": p.state.to_string()})).collect();
            prev_ticks = ps.iter().map(|p| (p.pid, p.ticks)).collect();

            if disk_at.elapsed() > Duration::from_secs(10) {
                disk_cache = disks();
                disk_at = Instant::now();
            }
            push(&mut cpu_h, total);
            push(&mut mem_h, mem_pct);
            push(&mut rx_h, rxr);
            push(&mut tx_h, txr);

            let (f_avg, f_max) = cpu_freq_mhz();
            let load: Vec<f64> = read("/proc/loadavg").split_whitespace().take(3).filter_map(|x| x.parse().ok()).collect();
            let snapshot = json!({
                "info": info,
                "uptime_s": read("/proc/uptime").split('.').next().and_then(|x| x.parse::<u64>().ok()).unwrap_or(0),
                "load": load,
                "cpu": {"total": total, "cores": pct.iter().skip(1).collect::<Vec<_>>(), "freq_mhz": f_avg, "freq_max_mhz": f_max,
                        "temp": hwmon_temp("coretemp", Some("Package")).or_else(|| hwmon_temp("k10temp", None))},
                "mem": {"total": mt, "available": ma, "cached": m("Cached"), "swap_total": m("SwapTotal"), "swap_free": m("SwapFree")},
                "net": {"rx_bps": rxr, "tx_bps": txr},
                "disk_io": {"read_bps": drr, "write_bps": dwr},
                "disks": disk_cache,
                "temps": {"nvme": hwmon_temp("nvme", Some("Composite"))},
                "battery": battery(),
                "gpu": gpu(),
                "procs": {"count": nproc, "top": top},
                "history": {"cpu": cpu_h, "mem": mem_h, "rx": rx_h, "tx": tx_h},
            });
            *out.write().unwrap() = snapshot;
        }
    });
    snap
}

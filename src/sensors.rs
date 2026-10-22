//! Everything beyond the basics for the Monitor: every hwmon sensor (temperatures, fans, voltages, currents, power),
//! CPU package power (RAPL), battery health, pressure-stall info, CPU governor/boost/throttling and kernel rates,
//! memory details, Wi-Fi signal. All read from /proc and /sys on each sample; nothing here wakes a device.
use serde_json::{json, Map, Value};
use std::{collections::HashMap, time::Instant};

fn read(p: &str) -> String {
    std::fs::read_to_string(p).unwrap_or_default()
}

fn num(p: &str) -> Option<f64> {
    read(p).trim().parse().ok()
}

fn round(v: f64, places: i32) -> f64 {
    let k = 10f64.powi(places);
    (v * k).round() / k
}

pub struct Sensors {
    rapl: HashMap<String, (u64, Instant)>,
    stat: Option<(u64, u64, u64, Instant)>,
}

impl Sensors {
    pub fn new() -> Self {
        Sensors { rapl: HashMap::new(), stat: None }
    }

    /// One reading of everything; the keys are merged into the monitor snapshot.
    pub fn sample(&mut self) -> Map<String, Value> {
        let mut out = Map::new();
        out.insert("power".into(), self.power());
        out.insert("battery_info".into(), battery_info());
        out.insert("pressure".into(), pressure());
        out.insert("sensors".into(), hwmon_sensors());
        out.insert("cpu_info".into(), self.cpu_info());
        out.insert("mem_info".into(), mem_info());
        out.insert("wifi_info".into(), wifi());
        out.insert("misc".into(), misc());
        out
    }

    /// Package/cores/graphics/memory watts from the RAPL energy counters.
    fn power(&mut self) -> Value {
        let now = Instant::now();
        let mut rapl = vec![];
        let mut denied = false;
        let Ok(dir) = std::fs::read_dir("/sys/class/powercap") else { return json!({"rapl": rapl}) };
        let mut zones: Vec<_> = dir.flatten().map(|e| e.path()).filter(|p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("intel-rapl:"))).collect();
        zones.sort();
        for z in zones {
            let d = z.display().to_string();
            let name = read(&format!("{d}/name")).trim().to_string();
            let Some(uj) = read(&format!("{d}/energy_uj")).trim().parse::<u64>().ok() else {
                // the counter exists but this user may not read it (root-only since the PLATYPUS side-channel fix)
                denied |= std::fs::metadata(format!("{d}/energy_uj")).is_ok() && !d.contains("mmio");
                continue;
            };
            let range = num(&format!("{d}/max_energy_range_uj")).unwrap_or(0.0) as u64;
            if let Some((prev, at)) = self.rapl.insert(d.clone(), (uj, now)) {
                let dt = now.duration_since(at).as_secs_f64();
                if dt > 0.05 {
                    let du = if uj >= prev { uj - prev } else { range.saturating_sub(prev) + uj };
                    let label = match name.as_str() {
                        n if n.starts_with("package") => "CPU package",
                        "core" => "CPU cores",
                        "uncore" => "Graphics / uncore",
                        "dram" => "Memory",
                        "psys" => "Whole platform",
                        other => other,
                    };
                    rapl.push(json!({"name": label, "w": round(du as f64 / 1e6 / dt, 2)}));
                }
            }
        }
        json!({"rapl": rapl, "rapl_denied": denied && rapl.is_empty()})
    }

    fn cpu_info(&mut self) -> Value {
        let c = "/sys/devices/system/cpu";
        let throttle: u64 = (0..256)
            .map_while(|i| std::fs::metadata(format!("{c}/cpu{i}")).ok().map(|_| i))
            .filter_map(|i| num(&format!("{c}/cpu{i}/thermal_throttle/core_throttle_count")))
            .map(|v| v as u64)
            .sum();
        let stat = read("/proc/stat");
        let field = |k: &str| stat.lines().find_map(|l| l.strip_prefix(k)?.split_whitespace().next()?.parse::<u64>().ok()).unwrap_or(0);
        let (ctxt, intr, forks) = (field("ctxt "), field("intr "), field("processes "));
        let now = Instant::now();
        let rates = self.stat.replace((ctxt, intr, forks, now)).and_then(|(c0, i0, f0, t0)| {
            let dt = now.duration_since(t0).as_secs_f64();
            (dt > 0.05).then(|| json!({"ctx": round(ctxt.saturating_sub(c0) as f64 / dt, 0), "irq": round(intr.saturating_sub(i0) as f64 / dt, 0), "forks": round(forks.saturating_sub(f0) as f64 / dt, 1)}))
        });
        let no_turbo = num(&format!("{c}/intel_pstate/no_turbo"));
        json!({
            "governor": read(&format!("{c}/cpu0/cpufreq/scaling_governor")).trim(),
            "epp": read(&format!("{c}/cpu0/cpufreq/energy_performance_preference")).trim(),
            "driver": read(&format!("{c}/cpu0/cpufreq/scaling_driver")).trim(),
            "boost": no_turbo.map(|n| n == 0.0),
            "throttle_events": throttle,
            "rates": rates,
            "running": field("procs_running "),
            "blocked": field("procs_blocked "),
        })
    }
}

fn battery_info() -> Value {
    for i in 0..4 {
        let b = format!("/sys/class/power_supply/BAT{i}");
        let Some(cap) = num(&format!("{b}/capacity")) else { continue };
        let status = read(&format!("{b}/status")).trim().to_string();
        let now = num(&format!("{b}/energy_now")).map(|e| e / 1e6);
        let full = num(&format!("{b}/energy_full")).map(|e| e / 1e6);
        let design = num(&format!("{b}/energy_full_design")).map(|e| e / 1e6);
        let watts = num(&format!("{b}/power_now")).map(|p| p / 1e6).filter(|w| *w > 0.05);
        let hours = match (status.as_str(), now, full, watts) {
            ("Discharging", Some(n), _, Some(w)) => Some(n / w),
            ("Charging", Some(n), Some(f), Some(w)) => Some((f - n).max(0.0) / w),
            _ => None,
        };
        return json!({
            "percent": cap,
            "status": status,
            "cycles": num(&format!("{b}/cycle_count")),
            "health": full.zip(design).map(|(f, d)| round(100.0 * f / d, 1)),
            "wh_now": now.map(|v| round(v, 1)), "wh_full": full.map(|v| round(v, 1)), "wh_design": design.map(|v| round(v, 1)),
            "volts": num(&format!("{b}/voltage_now")).map(|v| round(v / 1e6, 2)),
            "watts": watts.map(|w| round(w, 1)),
            "hours": hours.map(|h| round(h, 2)),
            "model": read(&format!("{b}/model_name")).trim(),
            "maker": read(&format!("{b}/manufacturer")).trim(),
            "tech": read(&format!("{b}/technology")).trim(),
        });
    }
    Value::Null
}

/// Pressure-stall information: the share of the last 10 s that tasks were waiting for CPU, memory or disk.
fn pressure() -> Value {
    let mut out = Map::new();
    for r in ["cpu", "memory", "io"] {
        let t = read(&format!("/proc/pressure/{r}"));
        if t.is_empty() {
            continue;
        }
        let mut o = Map::new();
        for l in t.lines() {
            let mut it = l.split_whitespace();
            let kind = it.next().unwrap_or("");
            for kv in it {
                if let Some((k, v)) = kv.split_once('=') {
                    if k == "avg10" || k == "avg60" {
                        o.insert(format!("{kind}_{k}"), json!(v.parse::<f64>().unwrap_or(0.0)));
                    }
                }
            }
        }
        out.insert(r.into(), Value::Object(o));
    }
    Value::Object(out)
}

fn mem_info() -> Value {
    let t = read("/proc/meminfo");
    let kb = |k: &str| t.lines().find_map(|l| l.strip_prefix(k)?.trim_start_matches(':').split_whitespace().next()?.parse::<u64>().ok()).unwrap_or(0) * 1024;
    let zram: Vec<Value> = std::fs::read_dir("/sys/block")
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with("zram"))
        .filter_map(|e| {
            let m = read(&format!("{}/mm_stat", e.path().display()));
            let f: Vec<u64> = m.split_whitespace().filter_map(|x| x.parse().ok()).collect();
            (f.len() >= 3 && f[0] > 0).then(|| json!({"name": e.file_name().to_string_lossy(), "orig": f[0], "compressed": f[1], "used": f[2]}))
        })
        .collect();
    json!({
        "anon": kb("AnonPages"), "mapped": kb("Mapped"), "shmem": kb("Shmem"), "slab": kb("Slab"),
        "dirty": kb("Dirty"), "writeback": kb("Writeback"), "swap_cached": kb("SwapCached"), "committed": kb("Committed_AS"),
        "zram": zram,
    })
}

fn wifi() -> Value {
    for l in read("/proc/net/wireless").lines().skip(2) {
        let Some((iface, rest)) = l.split_once(':') else { continue };
        let f: Vec<f64> = rest.split_whitespace().skip(1).filter_map(|x| x.trim_end_matches('.').parse().ok()).collect();
        if f.len() >= 2 {
            return json!({"iface": iface.trim(), "link": f[0], "dbm": f[1]});
        }
    }
    Value::Null
}

fn misc() -> Value {
    let fnr: Vec<u64> = read("/proc/sys/fs/file-nr").split_whitespace().filter_map(|x| x.parse().ok()).collect();
    json!({
        "files_open": fnr.first(), "files_max": fnr.get(2),
        "entropy": num("/proc/sys/kernel/random/entropy_avail"),
        "tainted": num("/proc/sys/kernel/tainted"),
    })
}

/// Every hwmon chip with all of its temperature, fan, voltage, current and power inputs, plus thermal zones no chip covers.
fn hwmon_sensors() -> Value {
    let mut chips: Vec<Value> = vec![];
    let mut names = std::collections::HashSet::new();
    let mut dirs: Vec<_> = std::fs::read_dir("/sys/class/hwmon").into_iter().flatten().flatten().map(|e| e.path()).collect();
    dirs.sort();
    for d in dirs {
        let d = d.display().to_string();
        let chip = read(&format!("{d}/name")).trim().to_string();
        let mut items = vec![];
        // input numbers are not contiguous (coretemp has temp1, temp2, temp6, temp10 …): list the files instead of counting
        let files: Vec<String> = std::fs::read_dir(&d).into_iter().flatten().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        let mut add = |kind: &str, prefix: &str, scale: f64, places: i32, unit_suffix: &str| {
            let mut idx: Vec<u32> = files
                .iter()
                .filter_map(|f| f.strip_prefix(prefix)?.strip_suffix("_input").or_else(|| f.strip_prefix(prefix)?.strip_suffix("_average"))?.parse().ok())
                .collect();
            idx.sort_unstable();
            idx.dedup();
            for i in idx {
                let Some(v) = num(&format!("{d}/{prefix}{i}_input")).or_else(|| num(&format!("{d}/{prefix}{i}_average"))) else { continue };
                let label = read(&format!("{d}/{prefix}{i}_label")).trim().to_string();
                let max = num(&format!("{d}/{prefix}{i}_max")).map(|m| round(m / scale, places));
                let crit = num(&format!("{d}/{prefix}{i}_crit")).map(|m| round(m / scale, places));
                items.push(json!({"kind": kind, "label": if label.is_empty() { format!("{prefix}{i}{unit_suffix}") } else { label }, "value": round(v / scale, places), "max": max, "crit": crit}));
            }
        };
        add("temp", "temp", 1000.0, 1, "");
        add("fan", "fan", 1.0, 0, "");
        add("volt", "in", 1000.0, 3, "");
        add("curr", "curr", 1000.0, 3, "");
        add("power", "power", 1e6, 2, "");
        if !items.is_empty() {
            names.insert(chip.clone());
            chips.push(json!({"chip": chip, "items": items}));
        }
    }
    for z in std::fs::read_dir("/sys/class/thermal").into_iter().flatten().flatten() {
        let p = z.path().display().to_string();
        if !z.file_name().to_string_lossy().starts_with("thermal_zone") {
            continue;
        }
        let ty = read(&format!("{p}/type")).trim().to_string();
        if names.contains(&ty) || ty.starts_with("acpitz") {
            continue;
        }
        if let Some(t) = num(&format!("{p}/temp")) {
            chips.push(json!({"chip": format!("thermal: {ty}"), "items": [{"kind": "temp", "label": ty, "value": round(t / 1000.0, 1), "max": null, "crit": null}]}));
        }
    }
    json!(chips)
}

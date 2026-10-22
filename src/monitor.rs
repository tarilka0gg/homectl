//! btop-style system monitor: a 1 Hz sampler keeps the latest snapshot, short histories and the full process
//! table, read from /proc and /sys. NVIDIA is only queried while the card is already awake (see `Gpus`).
use serde_json::{json, Map, Value};
use std::{
    collections::{HashMap, VecDeque},
    os::unix::fs::MetadataExt,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, RwLock,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const HISTORY: usize = 120;
const CLK_TCK: f64 = 100.0;

#[derive(Clone)]
pub struct ProcRow {
    pub pid: u32,
    pub name: String,
    pub cmd: String,
    pub user: String,
    pub cpu: f64,
    pub mem: u64,
    pub state: char,
    pub threads: u32,
}

#[derive(Default)]
pub struct Shared {
    pub snapshot: RwLock<Value>,
    pub procs: RwLock<Vec<ProcRow>>,
    /// Unix seconds until which the app asked for NVIDIA readings even on battery.
    pub gpu_force_until: AtomicU64,
    /// Unix ms of the last request for data. With nobody watching, sampling slows down to save power.
    pub last_request_ms: AtomicU64,
    /// Sampling interval the app asked for (ms), and when it asked.
    pub every_ms: AtomicU64,
    pub every_set_ms: AtomicU64,
}

pub type Monitor = Arc<Shared>;

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

const DEFAULT_EVERY_MS: u64 = 1000;
const IDLE_EVERY_MS: u64 = 5000;
const IDLE_AFTER_MS: u64 = 30_000;

impl Shared {
    /// A client asked for data. `every` (ms) is how fast it wants fresh samples; the fastest recent ask wins.
    pub fn touch(&self, every: Option<u64>) {
        let now = now_ms();
        self.last_request_ms.store(now, Ordering::Relaxed);
        if let Some(e) = every {
            let e = e.clamp(250, 10_000);
            let (cur, at) = (self.every_ms.load(Ordering::Relaxed), self.every_set_ms.load(Ordering::Relaxed));
            if e <= cur || now.saturating_sub(at) > 6000 {
                self.every_ms.store(e, Ordering::Relaxed);
                self.every_set_ms.store(now, Ordering::Relaxed);
            }
        }
    }

    pub fn force_gpu(&self) {
        self.gpu_force_until.store(now_s() + 15, Ordering::Relaxed);
    }

    pub fn query_procs(&self, sort: &str, desc: bool, q: &str, limit: usize) -> Value {
        let all = self.procs.read().unwrap();
        let ql = q.to_lowercase();
        let mut rows: Vec<&ProcRow> = all
            .iter()
            .filter(|p| ql.is_empty() || p.name.to_lowercase().contains(&ql) || p.cmd.to_lowercase().contains(&ql) || p.pid.to_string() == ql || p.user.to_lowercase().contains(&ql))
            .collect();
        rows.sort_by(|a, b| {
            let o = match sort {
                "pid" => a.pid.cmp(&b.pid),
                "name" => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
                "user" => a.user.cmp(&b.user),
                "mem" => a.mem.cmp(&b.mem),
                _ => a.cpu.total_cmp(&b.cpu),
            };
            if desc { o.reverse() } else { o }
        });
        let total = rows.len();
        let list: Vec<Value> = rows
            .into_iter()
            .take(limit)
            .map(|p| json!({"pid": p.pid, "name": p.name, "cmd": p.cmd, "user": p.user, "cpu": (p.cpu * 10.0).round() / 10.0, "mem": p.mem, "state": p.state.to_string(), "threads": p.threads}))
            .collect();
        json!({"total": all.len(), "matched": total, "procs": list})
    }
}

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

struct Iface {
    name: String,
    rx: u64,
    tx: u64,
}

fn net_ifaces() -> Vec<Iface> {
    read("/proc/net/dev")
        .lines()
        .skip(2)
        .filter_map(|l| {
            let (name, rest) = l.split_once(':')?;
            let n = name.trim();
            if n == "lo" || n.starts_with("veth") || n.starts_with("docker") || n.starts_with("br-") || n.starts_with("virbr") {
                return None;
            }
            let f: Vec<u64> = rest.split_whitespace().filter_map(|x| x.parse().ok()).collect();
            Some(Iface { name: n.to_string(), rx: *f.first()?, tx: *f.get(8)? })
        })
        .collect()
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


/// Raw /proc/diskstats counters of one whole disk.
#[derive(Clone, Copy, Default)]
struct DiskRaw {
    reads: u64,
    sect_r: u64,
    writes: u64,
    sect_w: u64,
    busy_ms: u64,
}

fn is_whole_disk(name: &str) -> bool {
    (name.starts_with("nvme") && name.contains('n') && !name.contains('p'))
        || ((name.starts_with("sd") || name.starts_with("vd") || name.starts_with("xvd")) && !name.chars().last().is_some_and(|c| c.is_ascii_digit()))
        || (name.starts_with("mmcblk") && !name.contains('p'))
}

fn disk_devs() -> Vec<(String, DiskRaw)> {
    read("/proc/diskstats")
        .lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split_whitespace().collect();
            let name = *f.get(2)?;
            if !is_whole_disk(name) {
                return None;
            }
            let g = |i: usize| f.get(i).and_then(|x| x.parse::<u64>().ok()).unwrap_or(0);
            Some((name.to_string(), DiskRaw { reads: g(3), sect_r: g(5), writes: g(7), sect_w: g(9), busy_ms: g(12) }))
        })
        .collect()
}

/// Model, size and kind of a disk from sysfs.
fn disk_info(name: &str) -> Value {
    let b = format!("/sys/block/{name}");
    let model = ["device/model", "device/name"].iter().map(|p| read(&format!("{b}/{p}")).trim().to_string()).find(|m| !m.is_empty()).unwrap_or_default();
    let size = num(&format!("{b}/size")).unwrap_or(0.0) * 512.0;
    let rotational = num(&format!("{b}/queue/rotational")) == Some(1.0);
    let kind = if name.starts_with("nvme") { "NVMe SSD" } else if rotational { "HDD" } else if name.starts_with("mmcblk") { "SD/eMMC" } else { "SSD" };
    json!({"model": model, "size": size, "kind": kind})
}

fn disk_temp(name: &str) -> Option<f64> {
    std::fs::read_dir(format!("/sys/block/{name}/device")).ok()?.flatten().filter(|e| e.file_name().to_string_lossy().starts_with("hwmon")).find_map(|e| num(&format!("{}/temp1_input", e.path().display())).map(|t| t / 1000.0))
}

/// Processes' cumulative disk bytes (only those we are allowed to read, i.e. our own).
fn proc_io() -> HashMap<u32, (String, u64, u64)> {
    let Ok(dir) = std::fs::read_dir("/proc") else { return HashMap::new() };
    dir.flatten()
        .filter_map(|e| {
            let pid: u32 = e.file_name().to_str()?.parse().ok()?;
            let t = std::fs::read_to_string(format!("/proc/{pid}/io")).ok()?;
            let get = |k: &str| t.lines().find_map(|l| l.strip_prefix(k)?.trim().parse::<u64>().ok()).unwrap_or(0);
            let name = read(&format!("/proc/{pid}/comm")).trim().to_string();
            Some((pid, (name, get("read_bytes:"), get("write_bytes:"))))
        })
        .collect()
}

struct RawProc {
    pid: u32,
    name: String,
    ticks: u64,
    rss: u64,
    state: char,
    threads: u32,
    uid: u32,
}

fn raw_procs() -> Vec<RawProc> {
    let Ok(dir) = std::fs::read_dir("/proc") else { return vec![] };
    dir.filter_map(|e| {
        let e = e.ok()?;
        let pid: u32 = e.file_name().to_str()?.parse().ok()?;
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let (l, r) = (stat.find('(')?, stat.rfind(')')?);
        let f: Vec<&str> = stat[r + 2..].split_whitespace().collect();
        Some(RawProc {
            pid,
            name: stat[l + 1..r].to_string(),
            state: f.first()?.chars().next()?,
            ticks: f.get(11)?.parse::<u64>().ok()? + f.get(12)?.parse::<u64>().ok()?,
            threads: f.get(17)?.parse().ok()?,
            rss: f.get(21)?.parse::<u64>().ok()? * 4096,
            uid: e.metadata().map_or(0, |m| m.uid()),
        })
    })
    .collect()
}

fn passwd() -> HashMap<u32, String> {
    read("/etc/passwd")
        .lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split(':').collect();
            Some((f.get(2)?.parse().ok()?, f.first()?.to_string()))
        })
        .collect()
}

fn cmdline(pid: u32) -> String {
    let b = std::fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
    let s: String = String::from_utf8_lossy(&b).split('\0').filter(|x| !x.is_empty()).collect::<Vec<_>>().join(" ");
    s.chars().take(160).collect()
}

// ---- sensors ----

struct Hwmon {
    chip: String,
    dir: String,
}

fn hwmons() -> Vec<Hwmon> {
    let Ok(d) = std::fs::read_dir("/sys/class/hwmon") else { return vec![] };
    d.flatten().map(|e| Hwmon { chip: read(&format!("{}/name", e.path().display())).trim().to_string(), dir: e.path().display().to_string() }).collect()
}

fn temps_of(h: &Hwmon) -> Vec<(String, f64)> {
    (1..80)
        .filter_map(|i| {
            let v = num(&format!("{}/temp{i}_input", h.dir))? / 1000.0;
            let label = read(&format!("{}/temp{i}_label", h.dir)).trim().to_string();
            Some((if label.is_empty() { format!("temp{i}") } else { label }, v))
        })
        .collect()
}

fn fans_of(h: &Hwmon) -> Vec<Value> {
    (1..8)
        .filter_map(|i| {
            let rpm = num(&format!("{}/fan{i}_input", h.dir))?;
            let label = read(&format!("{}/fan{i}_label", h.dir)).trim().to_string();
            let pwm = num(&format!("{}/pwm{i}", h.dir)).map(|p| (p / 2.55).round());
            Some(json!({"name": format!("{} {}", h.chip, if label.is_empty() { format!("fan{i}") } else { label }), "rpm": rpm, "pwm": pwm}))
        })
        .collect()
}

fn cpu_freqs_mhz() -> Vec<f64> {
    let mut v = vec![];
    for i in 0..512 {
        match num(&format!("/sys/devices/system/cpu/cpu{i}/cpufreq/scaling_cur_freq")) {
            Some(k) => v.push(k / 1000.0),
            None => break,
        }
    }
    v
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
            list.push(json!({"mount": mount, "dev": f[0], "fs": f[2], "total": total * bs, "used": (total - free) * bs}));
        }
    }
    json!(list)
}

// ---- GPUs ----

#[derive(Clone, PartialEq)]
enum Vendor {
    Nvidia,
    Intel,
    Amd,
    Other,
}

struct Gpu {
    card: String,
    pci: String,
    vendor: Vendor,
    name: String,
    discrete: bool,
    last: Map<String, Value>,
    prev_idle: Option<(u64, Instant)>,
    last_poll: Instant,
}

fn pci_name(vendor: &str, device: &str) -> Option<String> {
    for p in ["/usr/share/hwdata/pci.ids", "/usr/share/misc/pci.ids", "/usr/share/pci.ids"] {
        let t = read(p);
        if t.is_empty() {
            continue;
        }
        let (v, d) = (vendor.trim_start_matches("0x"), device.trim_start_matches("0x"));
        let mut in_v = false;
        for l in t.lines() {
            if !l.starts_with('\t') && !l.starts_with('#') {
                in_v = l.starts_with(v);
                continue;
            }
            if in_v && l.starts_with('\t') && !l.starts_with("\t\t") && l[1..].starts_with(d) {
                let n = l[1..].splitn(2, "  ").nth(1)?.trim();
                return Some(n.to_string());
            }
        }
    }
    None
}

fn find_gpus() -> Vec<Gpu> {
    let Ok(d) = std::fs::read_dir("/sys/class/drm") else { return vec![] };
    let mut cards: Vec<String> = d.flatten().filter_map(|e| e.file_name().to_str().map(String::from)).filter(|n| n.starts_with("card") && n[4..].bytes().all(|b| b.is_ascii_digit())).collect();
    cards.sort();
    cards
        .into_iter()
        .filter_map(|card| {
            let dev = format!("/sys/class/drm/{card}/device");
            let vendor_id = read(&format!("{dev}/vendor")).trim().to_string();
            let device_id = read(&format!("{dev}/device")).trim().to_string();
            let vendor = match vendor_id.as_str() {
                "0x10de" => Vendor::Nvidia,
                "0x8086" => Vendor::Intel,
                "0x1002" => Vendor::Amd,
                _ => Vendor::Other,
            };
            let pci = std::fs::canonicalize(&dev).ok()?.file_name()?.to_str()?.to_string();
            let name = pci_name(&vendor_id, &device_id).unwrap_or_else(|| format!("{vendor_id}:{device_id}"));
            // Intel iGPUs sit at 00:02.0; AMD/NVIDIA behind a bridge are discrete (APUs are on bus 00 too).
            let discrete = !pci.starts_with("0000:00:");
            Some(Gpu { card, pci, vendor, name, discrete, last: Map::new(), prev_idle: None, last_poll: Instant::now() - Duration::from_secs(60) })
        })
        .collect()
}

/// Reads one GPU. Returns the values and also keeps them as "last known" for when the card sleeps.
fn sample_gpu(g: &mut Gpu, ac: bool, forced: bool, pkg_temp: Option<f64>) -> Value {
    let dev = format!("/sys/class/drm/{}/device", g.card);
    let state = read(&format!("/sys/bus/pci/devices/{}/power/runtime_status", g.pci)).trim().to_string();
    let mut m = Map::new();
    m.insert("state".into(), json!(state));
    let mut live = false;
    match g.vendor {
        Vendor::Nvidia => {
            // nvidia-smi would wake a sleeping dGPU and keep it from sleeping again, so only ask while it is
            // already awake, and (to protect battery life) only on AC power or when the app explicitly asks.
            let due = g.last_poll.elapsed() >= Duration::from_millis(1900);
            if state == "active" && (ac || forced) && due {
                g.last_poll = Instant::now();
                if let Ok(o) = std::process::Command::new("nvidia-smi")
                    .args(["--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit,clocks.gr,clocks.mem,clocks.max.gr,fan.speed,pstate", "--format=csv,noheader,nounits"])
                    .output()
                {
                    let s = String::from_utf8_lossy(&o.stdout);
                    let f: Vec<&str> = s.trim().split(", ").collect();
                    if f.len() == 12 {
                        let n = |i: usize| f[i].trim().parse::<f64>().ok();
                        let mut v = Map::new();
                        v.insert("name".into(), json!(f[0]));
                        v.insert("util".into(), json!(n(1)));
                        v.insert("mem_used".into(), json!(n(2).map(|x| x * 1048576.0)));
                        v.insert("mem_total".into(), json!(n(3).map(|x| x * 1048576.0)));
                        v.insert("temp".into(), json!(n(4)));
                        v.insert("power_w".into(), json!(n(5)));
                        v.insert("power_limit_w".into(), json!(n(6)));
                        v.insert("clock_mhz".into(), json!(n(7)));
                        v.insert("mem_clock_mhz".into(), json!(n(8)));
                        v.insert("clock_max_mhz".into(), json!(n(9)));
                        v.insert("fan_pct".into(), json!(n(10)));
                        v.insert("pstate".into(), json!(f[11]));
                        g.last = v;
                    }
                }
            }
            live = state == "active" && (ac || forced) && g.last_poll.elapsed() < Duration::from_secs(5);
            for (k, v) in &g.last {
                m.insert(k.clone(), v.clone());
            }
            if state != "active" {
                m.insert("note".into(), json!("sleeping — not queried so it can stay asleep"));
            } else if !(ac || forced) {
                m.insert("note".into(), json!("awake, but not queried on battery"));
            }
        }
        Vendor::Intel => {
            let xe = format!("{dev}/tile0/gt0/freq0");
            let (cur, act, max) = if std::path::Path::new(&xe).exists() {
                (num(&format!("{xe}/cur_freq")), num(&format!("{xe}/act_freq")), num(&format!("{xe}/max_freq")))
            } else {
                let gt = format!("/sys/class/drm/{}", g.card);
                (num(&format!("{gt}/gt_cur_freq_mhz")), num(&format!("{gt}/gt_act_freq_mhz")), num(&format!("{gt}/gt_max_freq_mhz")))
            };
            m.insert("clock_mhz".into(), json!(act.filter(|a| *a > 0.0).or(cur)));
            m.insert("clock_max_mhz".into(), json!(max));
            // Busy % from the share of time the GT was *not* in its idle (RC6) state.
            let idle_path = [format!("{dev}/tile0/gt0/gtidle/idle_residency_ms"), format!("/sys/class/drm/{}/gt/gt0/rc6_residency_ms", g.card), format!("/sys/class/drm/{}/power/rc6_residency_ms", g.card)]
                .into_iter()
                .find(|p| std::path::Path::new(p).exists());
            if let Some(p) = idle_path {
                if let Some(idle) = num(&p) {
                    let idle = idle as u64;
                    if let Some((pi, pt)) = g.prev_idle {
                        let dt = pt.elapsed().as_millis() as f64;
                        if dt > 0.0 {
                            m.insert("util".into(), json!((100.0 * (1.0 - idle.saturating_sub(pi) as f64 / dt)).clamp(0.0, 100.0)));
                        }
                    }
                    g.prev_idle = Some((idle, Instant::now()));
                }
            }
            m.insert("shared_memory".into(), json!(true));
            m.insert("temp".into(), json!(pkg_temp)); // integrated: shares the CPU package sensor
            live = true;
        }
        Vendor::Amd => {
            m.insert("util".into(), json!(num(&format!("{dev}/gpu_busy_percent"))));
            m.insert("mem_used".into(), json!(num(&format!("{dev}/mem_info_vram_used"))));
            m.insert("mem_total".into(), json!(num(&format!("{dev}/mem_info_vram_total"))));
            if let Ok(h) = std::fs::read_dir(format!("{dev}/hwmon")) {
                for e in h.flatten() {
                    let p = e.path().display().to_string();
                    m.insert("temp".into(), json!(num(&format!("{p}/temp1_input")).map(|t| t / 1000.0)));
                    m.insert("power_w".into(), json!(num(&format!("{p}/power1_average")).or(num(&format!("{p}/power1_input"))).map(|w| w / 1e6)));
                    m.insert("fan_rpm".into(), json!(num(&format!("{p}/fan1_input"))));
                }
            }
            m.insert("clock_mhz".into(), json!(read(&format!("{dev}/pp_dpm_sclk")).lines().find(|l| l.contains('*')).and_then(|l| l.split_whitespace().nth(1)).and_then(|x| x.trim_end_matches("Mhz").parse::<f64>().ok())));
            live = true;
        }
        Vendor::Other => {}
    }
    m.insert("live".into(), json!(live));
    m.insert("id".into(), json!(g.card));
    m.insert("kind".into(), json!(if g.discrete { "discrete" } else { "integrated" }));
    m.entry("name").or_insert(json!(g.name));
    if g.vendor == Vendor::Nvidia && !m.contains_key("name") {
        m.insert("name".into(), json!(g.name));
    }
    m.insert("vendor".into(), json!(match g.vendor { Vendor::Nvidia => "nvidia", Vendor::Intel => "intel", Vendor::Amd => "amd", Vendor::Other => "other" }));
    Value::Object(m)
}

// ---- sampler ----

#[derive(Default)]
struct Hist {
    series: HashMap<String, VecDeque<f64>>,
    /// the sample number (tick) each value was taken at, parallel to `series`; lets a client ask for only what is new
    ticks: HashMap<String, VecDeque<u64>>,
    tick: u64,
}

impl Hist {
    fn push(&mut self, k: &str, v: Option<f64>) {
        if let Some(v) = v {
            let h = self.series.entry(k.to_string()).or_default();
            let t = self.ticks.entry(k.to_string()).or_default();
            if h.len() == HISTORY {
                h.pop_front();
                t.pop_front();
            }
            h.push_back(v);
            t.push_back(self.tick);
        }
    }
    fn to_json(&self) -> Value {
        json!(self.series.iter().filter(|(k, _)| !k.starts_with("core.")).map(|(k, v)| (k.clone(), v.iter().collect::<Vec<_>>())).collect::<HashMap<_, _>>())
    }
    fn ticks_json(&self) -> Value {
        json!(self.ticks.iter().filter(|(k, _)| !k.starts_with("core.")).map(|(k, v)| (k.clone(), v.iter().collect::<Vec<_>>())).collect::<HashMap<_, _>>())
    }
    /// One series per CPU thread, in thread order.
    fn cores_json(&self, n: usize) -> Value {
        json!((0..n).map(|i| self.series.get(&format!("core.{i}")).map(|v| v.iter().collect::<Vec<_>>()).unwrap_or_default()).collect::<Vec<_>>())
    }
}

fn core_id_map(n: usize) -> Vec<u32> {
    (0..n).map(|i| num(&format!("/sys/devices/system/cpu/cpu{i}/topology/core_id")).map_or(i as u32, |c| c as u32)).collect()
}

pub fn start() -> Monitor {
    let shared: Monitor = Arc::new(Shared::default());
    shared.every_ms.store(DEFAULT_EVERY_MS, Ordering::Relaxed);
    shared.last_request_ms.store(now_ms(), Ordering::Relaxed);
    let out = shared.clone();
    std::thread::spawn(move || {
        let info = json!({
            "hostname": read("/proc/sys/kernel/hostname").trim(),
            "kernel": read("/proc/sys/kernel/osrelease").trim(),
            "cpu_model": read("/proc/cpuinfo").lines().find(|l| l.starts_with("model name")).and_then(|l| l.split(':').nth(1)).unwrap_or("").trim(),
            "os": read("/etc/os-release").lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).unwrap_or("").trim_matches(|c| c == '"' || c == '\''),
        });
        let users = passwd();
        let mut gpus = find_gpus();
        let mut hist = Hist::default();
        let mut prev_cpu = cpu_times();
        let core_ids = core_id_map(prev_cpu.len().saturating_sub(1));
        let mut prev_net: HashMap<String, (u64, u64)> = net_ifaces().into_iter().map(|i| (i.name, (i.rx, i.tx))).collect();
        let (mut prev_dr, mut prev_dw) = disk_sectors();
        let mut prev_dev: HashMap<String, DiskRaw> = disk_devs().into_iter().collect();
        let mut prev_pio = proc_io();
        let mut dev_info: HashMap<String, Value> = HashMap::new();
        let mut prev_ticks: HashMap<u32, u64> = raw_procs().into_iter().map(|p| (p.pid, p.ticks)).collect();
        let mut cmds: HashMap<u32, String> = HashMap::new();
        let (mut rx_peak, mut tx_peak, mut dr_peak, mut dw_peak) = (0f64, 0f64, 0f64, 0f64);
        let mut last = Instant::now();
        let mut disk_cache = disks();
        let mut disk_at = Instant::now();
        loop {
            // Nobody watching → sample slowly (saves CPU/battery); otherwise at the rate the app asked for.
            let idle = now_ms().saturating_sub(out.last_request_ms.load(Ordering::Relaxed)) > IDLE_AFTER_MS;
            let asked = out.every_ms.load(Ordering::Relaxed);
            let interval = if idle { IDLE_EVERY_MS } else if now_ms().saturating_sub(out.every_set_ms.load(Ordering::Relaxed)) > 15_000 { DEFAULT_EVERY_MS } else { asked };
            std::thread::sleep(Duration::from_millis(interval));
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

            // network, per interface and total
            let ifs = net_ifaces();
            let (mut rxr, mut txr, mut rx_tot, mut tx_tot) = (0f64, 0f64, 0u64, 0u64);
            let mut iface_json = vec![];
            for i in &ifs {
                let (prx, ptx) = prev_net.get(&i.name).copied().unwrap_or((i.rx, i.tx));
                let (r, t) = (i.rx.saturating_sub(prx) as f64 / dt, i.tx.saturating_sub(ptx) as f64 / dt);
                if !i.name.starts_with("tailscale") {
                    rxr += r;
                    txr += t;
                    rx_tot += i.rx;
                    tx_tot += i.tx;
                }
                iface_json.push(json!({"name": i.name, "rx_bps": r, "tx_bps": t, "rx_total": i.rx, "tx_total": i.tx}));
            }
            prev_net = ifs.into_iter().map(|i| (i.name, (i.rx, i.tx))).collect();
            rx_peak = rx_peak.max(rxr);
            tx_peak = tx_peak.max(txr);

            let (dr, dw) = disk_sectors();
            let (drr, dwr) = (dr.saturating_sub(prev_dr) as f64 * 512.0 / dt, dw.saturating_sub(prev_dw) as f64 * 512.0 / dt);
            (prev_dr, prev_dw) = (dr, dw);
            dr_peak = dr_peak.max(drr);
            dw_peak = dw_peak.max(dwr);

            // per physical disk: throughput, IOPS and how busy it is (io_ticks = ms with I/O in flight)
            let mut dev_json = vec![];
            for (name, c) in disk_devs() {
                let p = prev_dev.get(&name).copied().unwrap_or(c);
                let (rb, wb) = (c.sect_r.saturating_sub(p.sect_r) as f64 * 512.0 / dt, c.sect_w.saturating_sub(p.sect_w) as f64 * 512.0 / dt);
                let (ri, wi) = (c.reads.saturating_sub(p.reads) as f64 / dt, c.writes.saturating_sub(p.writes) as f64 / dt);
                let busy = (c.busy_ms.saturating_sub(p.busy_ms) as f64 / (dt * 10.0)).min(100.0);
                hist.push(&format!("disk.{name}.r"), Some(rb));
                hist.push(&format!("disk.{name}.w"), Some(wb));
                hist.push(&format!("disk.{name}.busy"), Some(busy));
                let info = dev_info.entry(name.clone()).or_insert_with(|| disk_info(&name)).clone();
                let parts: Vec<Value> = disk_cache
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|d| d["dev"].as_str().and_then(|x| x.strip_prefix("/dev/")).is_some_and(|b| b == name || b.strip_prefix(name.as_str()).is_some_and(|r| r.trim_start_matches('p').chars().all(|ch| ch.is_ascii_digit()))))
                    .cloned()
                    .collect();
                dev_json.push(json!({"name": name, "model": info["model"], "size": info["size"], "kind": info["kind"], "temp": disk_temp(&name),
                    "read_bps": rb, "write_bps": wb, "read_iops": ri, "write_iops": wi, "busy": busy, "parts": parts}));
                prev_dev.insert(name, c);
            }

            // which processes are reading/writing right now (own processes unless running as root)
            let pio = proc_io();
            let mut io_rows: Vec<(f64, Value)> = pio
                .iter()
                .filter_map(|(pid, (name, r, w))| {
                    let (pr, pw) = prev_pio.get(pid).map(|(_, a, b)| (*a, *b))?;
                    let (rr, ww) = (r.saturating_sub(pr) as f64 / dt, w.saturating_sub(pw) as f64 / dt);
                    (rr + ww >= 1024.0).then(|| (rr + ww, json!({"pid": pid, "name": name, "read_bps": rr, "write_bps": ww})))
                })
                .collect();
            io_rows.sort_by(|a, b| b.0.total_cmp(&a.0));
            let disk_procs: Vec<Value> = io_rows.into_iter().take(8).map(|x| x.1).collect();
            prev_pio = pio;

            // processes
            let ps = raw_procs();
            let mut rows: Vec<ProcRow> = ps
                .iter()
                .map(|p| {
                    let cpu = prev_ticks.get(&p.pid).map_or(0.0, |&t| 100.0 * p.ticks.saturating_sub(t) as f64 / (CLK_TCK * dt));
                    let cmd = cmds.entry(p.pid).or_insert_with(|| cmdline(p.pid)).clone();
                    ProcRow { pid: p.pid, name: p.name.clone(), cmd: if cmd.is_empty() { format!("[{}]", p.name) } else { cmd }, user: users.get(&p.uid).cloned().unwrap_or_else(|| p.uid.to_string()), cpu, mem: p.rss, state: p.state, threads: p.threads }
                })
                .collect();
            prev_ticks = ps.iter().map(|p| (p.pid, p.ticks)).collect();
            cmds.retain(|pid, _| prev_ticks.contains_key(pid));
            let nproc = rows.len();
            rows.sort_by(|a, b| b.cpu.total_cmp(&a.cpu));
            let top: Vec<Value> = rows.iter().take(25).map(|p| json!({"pid": p.pid, "name": p.name, "cpu": (p.cpu * 10.0).round() / 10.0, "mem": p.mem, "state": p.state.to_string()})).collect();
            *out.procs.write().unwrap() = rows;

            if disk_at.elapsed() > Duration::from_secs(10) {
                disk_cache = disks();
                disk_at = Instant::now();
            }

            // temperatures and fans
            let hw = hwmons();
            let mut core_temp_by_id: HashMap<u32, f64> = HashMap::new();
            let mut pkg_temp = None;
            let mut temps = vec![];
            let mut fans = vec![];
            for h in &hw {
                match h.chip.as_str() {
                    "coretemp" | "k10temp" => {
                        for (label, v) in temps_of(h) {
                            if let Some(id) = label.strip_prefix("Core ").and_then(|x| x.parse::<u32>().ok()) {
                                core_temp_by_id.insert(id, v);
                            } else if label.starts_with("Package") || label == "Tctl" {
                                pkg_temp = Some(v);
                            }
                        }
                    }
                    "acpitz" | "nvme" | "spd5118" | "iwlwifi_1" | "pch_cannonlake" => {
                        for (label, v) in temps_of(h) {
                            temps.push(json!({"label": format!("{} {}", h.chip, label), "value": v}));
                        }
                    }
                    _ => {}
                }
                fans.extend(fans_of(h));
            }
            let core_temps: Vec<Value> = core_ids.iter().map(|id| json!(core_temp_by_id.get(id))).collect();
            let freqs = cpu_freqs_mhz();
            let (f_avg, f_max) = (if freqs.is_empty() { 0.0 } else { freqs.iter().sum::<f64>() / freqs.len() as f64 }, freqs.iter().cloned().fold(0.0, f64::max));
            let bat = battery();
            let ac = bat["ac"].as_bool().unwrap_or(true);
            let forced = now_s() < out.gpu_force_until.load(Ordering::Relaxed);
            let gpu_json: Vec<Value> = gpus.iter_mut().map(|g| sample_gpu(g, ac, forced, pkg_temp)).collect();

            hist.tick += 1;
            hist.push("cpu", Some(total));
            for (i, c) in pct.iter().skip(1).enumerate() {
                hist.push(&format!("core.{i}"), Some(*c));
            }
            hist.push("mem", Some(mem_pct));
            hist.push("rx", Some(rxr));
            hist.push("tx", Some(txr));
            hist.push("dr", Some(drr));
            hist.push("dw", Some(dwr));
            hist.push("temp.cpu", pkg_temp);
            hist.push("freq", Some(f_avg));
            for (i, f) in fans.iter().enumerate() {
                hist.push(&format!("fan.{i}"), f["rpm"].as_f64());
            }
            for g in &gpu_json {
                let id = g["id"].as_str().unwrap_or("gpu");
                if g["live"].as_bool() == Some(true) {
                    for (k, hk) in [("util", "util"), ("temp", "temp"), ("power_w", "power"), ("clock_mhz", "clock"), ("mem_used", "vram")] {
                        hist.push(&format!("{id}.{hk}"), g[k].as_f64());
                    }
                }
            }

            let snapshot = json!({
                "info": info,
                "uptime_s": read("/proc/uptime").split('.').next().and_then(|x| x.parse::<u64>().ok()).unwrap_or(0),
                "load": read("/proc/loadavg").split_whitespace().take(3).filter_map(|x| x.parse::<f64>().ok()).collect::<Vec<_>>(),
                "cpu": {"total": total, "cores": pct.iter().skip(1).collect::<Vec<_>>(), "core_temps": core_temps, "core_freqs": freqs,
                        "freq_mhz": f_avg, "freq_max_mhz": f_max, "temp": pkg_temp},
                "mem": {"total": mt, "available": ma, "cached": m("Cached"), "buffers": m("Buffers"), "swap_total": m("SwapTotal"), "swap_free": m("SwapFree")},
                "net": {"rx_bps": rxr, "tx_bps": txr, "rx_peak": rx_peak, "tx_peak": tx_peak, "rx_total": rx_tot, "tx_total": tx_tot, "ifaces": iface_json},
                "disk_io": {"read_bps": drr, "write_bps": dwr, "read_peak": dr_peak, "write_peak": dw_peak},
                "disks": disk_cache,
                "disk_devs": dev_json,
                "disk_procs": disk_procs,
                "disk_procs_scope": if unsafe { libc::geteuid() } == 0 { "all" } else { "own" },
                "temps": temps,
                "fans": fans,
                "battery": bat,
                "gpus": gpu_json,
                "procs": {"count": nproc, "top": top},
                "tick": hist.tick,
                "history": hist.to_json(),
                "history_t": hist.ticks_json(),
                "core_history": hist.cores_json(pct.len().saturating_sub(1)),
                "sample_ms": interval,
            });
            *out.snapshot.write().unwrap() = snapshot;
        }
    });
    shared
}

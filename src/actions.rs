//! "Power modes": user scripts the app can switch on and off — e.g. a deep-idle mode with `idle-on.sh` / `idle-off.sh`.
//! Defined in ~/.config/homectl/actions.json and/or found automatically as `<name>-on.sh` + `<name>-off.sh` pairs.
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    process::Stdio,
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::process::Command;

#[derive(Deserialize, Clone)]
struct Def {
    id: String,
    title: String,
    desc: Option<String>,
    on: String,
    off: String,
    /// shell command; exit status 0 means "currently on"
    state: Option<String>,
    confirm_on: Option<String>,
    confirm_off: Option<String>,
    #[serde(skip)]
    hint_path: Option<(PathBuf, bool)>,
}

static LAST: Mutex<Option<HashMap<String, (bool, u64)>>> = Mutex::new(None);
static RUNNING: Mutex<Option<HashSet<String>>> = Mutex::new(None);

fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into()))
}

fn dry() -> bool {
    std::env::var("HOMECTL_DRYRUN").is_ok_and(|v| v == "1")
}

fn memo_file() -> PathBuf {
    home().join(".cache/homectl/actions-last.json")
}

fn recall() -> HashMap<String, (bool, u64)> {
    let mut g = LAST.lock().unwrap();
    g.get_or_insert_with(|| {
        std::fs::read_to_string(memo_file())
            .ok()
            .and_then(|s| serde_json::from_str::<HashMap<String, (bool, u64)>>(&s).ok())
            .unwrap_or_default()
    })
    .clone()
}

fn remember(id: &str, on: bool) {
    let mut m = recall();
    m.insert(id.to_string(), (on, SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())));
    *LAST.lock().unwrap() = Some(m.clone());
    if let Some(d) = memo_file().parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let _ = std::fs::write(memo_file(), serde_json::to_string(&m).unwrap_or_default());
}

fn first_comment(p: &Path) -> Option<String> {
    std::fs::read_to_string(p).ok()?.lines().skip(1).take(6).find_map(|l| l.strip_prefix('#').map(|c| c.trim().to_string()).filter(|c| !c.is_empty()))
}

fn quote(p: &Path) -> String {
    format!("'{}'", p.display().to_string().replace('\'', "'\\''"))
}

/// The command for a discovered script: through `doas -n` (these scripts change system settings), via `sh` unless executable.
fn script_cmd(p: &Path) -> (String, bool) {
    let exec = std::fs::metadata(p).is_ok_and(|m| m.permissions().mode() & 0o111 != 0);
    (format!("doas -n {}{}", if exec { "" } else { "sh " }, quote(p)), exec)
}

fn load() -> Vec<Def> {
    let mut defs: Vec<Def> = std::fs::read_to_string(home().join(".config/homectl/actions.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    let known: HashSet<String> = defs.iter().map(|d| d.id.clone()).collect();
    let dirs = [home(), home().join(".local/bin"), home().join("bin"), PathBuf::from("/usr/local/bin"), PathBuf::from("/usr/local/sbin")];
    let mut seen = HashSet::new();
    for d in dirs {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        let mut names: Vec<String> = rd.flatten().filter_map(|e| e.file_name().into_string().ok()).collect();
        names.sort();
        for n in &names {
            let Some(base) = n.strip_suffix("-on.sh") else { continue };
            let off = d.join(format!("{base}-off.sh"));
            if !off.exists() || known.contains(base) || !seen.insert(base.to_string()) {
                continue;
            }
            let on_p = d.join(n);
            let (on_cmd, exec) = script_cmd(&on_p);
            let (off_cmd, _) = script_cmd(&off);
            let title = base.split(['-', '_']).enumerate().map(|(i, w)| if i == 0 { w[..1].to_uppercase() + &w[1..] } else { w.to_string() }).collect::<Vec<_>>().join(" ");
            defs.push(Def {
                id: base.to_string(),
                title,
                desc: first_comment(&on_p).or_else(|| Some(format!("{} / {}", on_p.display(), off.display()))),
                on: on_cmd,
                off: off_cmd,
                state: None,
                confirm_on: Some(format!("{}This runs {} as root.", first_comment(&on_p).map(|c| format!("{c}\n\n")).unwrap_or_default(), on_p.display())),
                confirm_off: None,
                hint_path: Some((on_p, exec)),
            });
        }
    }
    defs
}

/// Is it on? The configured check, a built-in check for well-known names, else None (we only know what we last did).
async fn state_of(d: &Def) -> Option<bool> {
    if let Some(cmd) = &d.state {
        return Command::new("sh").args(["-c", cmd]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status().await.ok().map(|s| s.success());
    }
    if d.id == "idle" {
        // deep idle takes every CPU but cpu0 offline
        return std::fs::read_to_string("/sys/devices/system/cpu/online").ok().map(|s| s.trim() == "0");
    }
    None
}

fn builtin_warning(id: &str) -> Option<&'static str> {
    (id == "idle").then_some("Deep idle keeps one CPU core, stops the fans and turns the screens off. Don't start builds, games or anything CPU-heavy until you turn it off again — it can hang the machine.")
}

pub async fn list() -> Value {
    let memo = recall();
    let running = RUNNING.lock().unwrap().clone().unwrap_or_default();
    let mut v = vec![];
    for d in load() {
        let known = state_of(&d).await;
        let (state, assumed) = match known {
            Some(s) => (Some(s), false),
            None => (memo.get(&d.id).map(|m| m.0), true),
        };
        v.push(json!({
            "id": d.id, "title": d.title, "desc": d.desc,
            "state": state, "assumed": assumed && state.is_some(),
            "last": memo.get(&d.id).map(|m| m.1),
            "confirm_on": d.confirm_on.clone().or_else(|| builtin_warning(&d.id).map(String::from)),
            "confirm_off": d.confirm_off,
            "running": running.contains(&d.id),
        }));
    }
    json!(v)
}

pub async fn run(id: &str, on: bool) -> Result<Value, (u16, String)> {
    let d = load().into_iter().find(|d| d.id == id).ok_or((404, "unknown power mode".to_string()))?;
    {
        let mut g = RUNNING.lock().unwrap();
        if !g.get_or_insert_with(HashSet::new).insert(d.id.clone()) {
            return Err((409, "already running".into()));
        }
    }
    let cmd = if on { d.on.clone() } else { d.off.clone() };
    let res = if dry() {
        eprintln!("DRYRUN action {id} {} -> {cmd}", if on { "on" } else { "off" });
        Ok((0, format!("dry run: {cmd}")))
    } else {
        match tokio::time::timeout(Duration::from_secs(180), Command::new("sh").args(["-c", &cmd]).stdin(Stdio::null()).output()).await {
            Ok(Ok(o)) => {
                let mut text = String::from_utf8_lossy(&o.stdout).into_owned() + &String::from_utf8_lossy(&o.stderr);
                if text.len() > 2000 {
                    text = format!("…{}", &text[text.len() - 2000..]);
                }
                Ok((o.status.code().unwrap_or(-1), text))
            }
            Ok(Err(e)) => Err((500, e.to_string())),
            Err(_) => Err((504, "timed out after 3 minutes".into())),
        }
    };
    RUNNING.lock().unwrap().as_mut().map(|g| g.remove(&d.id));
    let (code, output) = res?;
    if code == 0 && !dry() {
        remember(&d.id, on);
    }
    // doas refusing is the usual failure: say exactly what to add.
    let hint = (code != 0 && output.contains("doas:")).then(|| {
        let user = std::env::var("USER").unwrap_or_else(|_| "USER".into());
        match &d.hint_path {
            Some((p, true)) => format!("Add to /etc/doas.conf (for both the -on and -off script):\npermit nopass {user} cmd {}", p.display()),
            Some((p, false)) => format!("Add to /etc/doas.conf (for both the -on and -off script):\npermit nopass {user} cmd sh args {}", p.display()),
            None => "The command needs root: allow it in /etc/doas.conf with a `permit nopass` rule.".into(),
        }
    });
    Ok(json!({"ok": code == 0, "code": code, "output": output, "hint": hint}))
}

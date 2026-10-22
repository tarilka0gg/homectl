//! Stale-while-revalidate cache for slow, rarely changing readings (Wi-Fi, monitors, lights…): a call answers at
//! once from the last result and, when it is older than `ttl`, refreshes it in the background — so a screen that
//! polls these never waits on `nmcli`/`ddcutil`/network probes. The very first call computes inline.
use serde_json::Value;
use std::{
    collections::{HashMap, HashSet},
    future::Future,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

struct Store {
    values: Mutex<HashMap<&'static str, (Instant, Value)>>,
    busy: Mutex<HashSet<&'static str>>,
}

fn store() -> &'static Store {
    static S: OnceLock<Store> = OnceLock::new();
    S.get_or_init(|| Store { values: Default::default(), busy: Default::default() })
}

pub async fn get<F, Fut>(key: &'static str, ttl: Duration, f: F) -> Value
where
    F: FnOnce() -> Fut + Send + 'static,
    Fut: Future<Output = Value> + Send + 'static,
{
    let s = store();
    let hit = s.values.lock().unwrap().get(key).cloned();
    match hit {
        Some((at, v)) if at.elapsed() < ttl => v,
        Some((_, v)) => {
            if s.busy.lock().unwrap().insert(key) {
                tokio::spawn(async move {
                    let fresh = f().await;
                    store().values.lock().unwrap().insert(key, (Instant::now(), fresh));
                    store().busy.lock().unwrap().remove(key);
                });
            }
            v
        }
        None => {
            let v = f().await;
            s.values.lock().unwrap().insert(key, (Instant::now(), v.clone()));
            v
        }
    }
}

/// Drop a cached reading after something changed it, so the next call measures again.
pub fn forget(key: &'static str) {
    store().values.lock().unwrap().remove(key);
}

/// Store a reading that was just measured on purpose (e.g. an explicit rescan).
pub fn put(key: &'static str, v: &Value) {
    store().values.lock().unwrap().insert(key, (Instant::now(), v.clone()));
}

//! Device keys. Each phone makes an EC P-256 key pair inside its secure hardware (the private key can't be copied out) and is
//! *enrolled* here once, on the home network. After that, anything arriving through the public tunnel must carry a fresh
//! signature made by an enrolled phone — so the access token alone, if it ever leaks, no longer opens the tunnel.
//!
//! The signature covers `METHOD \n PATH?QUERY \n TIMESTAMP \n NONCE`: it is only valid for that request, for two minutes,
//! once. (Request bodies travel inside TLS and are not covered.)
use axum::http::HeaderMap;
use ring::{digest, signature};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Mutex, RwLock},
};

#[derive(Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub spki: String, // base64 of the DER SubjectPublicKeyInfo as Android exports it
    pub added: u64,
}

pub struct Devices {
    file: PathBuf,
    list: RwLock<Vec<Device>>,
    seen: Mutex<HashMap<String, u64>>,
}

pub struct Sig {
    pub dev: String,
    pub ts: u64,
    pub nonce: String,
    pub sig: String,
}

const SPKI_P256_PREFIX: [u8; 26] = [0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00];
const WINDOW_MS: u64 = 120_000;

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

/// Standard or URL-safe base64, with or without padding.
pub fn b64decode(s: &str) -> Option<Vec<u8>> {
    let (mut bits, mut n, mut out) = (0u32, 0, vec![]);
    for c in s.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' | b'\n' | b'\r' | b' ' => continue,
            _ => return None,
        } as u32;
        bits = bits << 6 | v;
        n += 6;
        if n >= 8 {
            n -= 8;
            out.push((bits >> n) as u8);
            bits &= (1 << n) - 1;
        }
    }
    Some(out)
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// The 65-byte uncompressed point inside a P-256 SubjectPublicKeyInfo, or None if it isn't one.
fn point_of(spki: &[u8]) -> Option<&[u8]> {
    (spki.len() == 91 && spki[..26] == SPKI_P256_PREFIX && spki[26] == 4).then(|| &spki[26..])
}

/// Requests that came in through the public tunnel (the edge always adds these; a client can't remove them).
pub fn via_tunnel(h: &HeaderMap) -> bool {
    h.contains_key("cf-connecting-ip") || h.contains_key("x-forwarded-for")
}

/// Forwarded by another homectl machine that already checked the phone's signature.
pub fn via_hub(h: &HeaderMap) -> bool {
    h.contains_key("x-homectl-via")
}

pub fn sig_from_headers(h: &HeaderMap) -> Option<Sig> {
    let g = |k: &str| h.get(k).and_then(|v| v.to_str().ok()).map(str::to_string);
    Some(Sig { dev: g("x-homectl-dev")?, ts: g("x-homectl-ts")?.parse().ok()?, nonce: g("x-homectl-nonce")?, sig: g("x-homectl-sig")? })
}

/// For WebSockets the signature travels in the query: returns the query without it, and the signature if all four parts are there.
pub fn split_query(query: &str) -> (String, Option<Sig>) {
    let (mut keep, mut dev, mut ts, mut n, mut sig) = (vec![], None, None, None, None);
    for kv in query.split('&').filter(|s| !s.is_empty()) {
        match kv.split_once('=') {
            Some(("dev", v)) => dev = Some(v.to_string()),
            Some(("ts", v)) => ts = v.parse().ok(),
            Some(("n", v)) => n = Some(v.to_string()),
            Some(("sig", v)) => sig = Some(v.to_string()),
            _ => keep.push(kv),
        }
    }
    let s = match (dev, ts, n, sig) {
        (Some(dev), Some(ts), Some(nonce), Some(sig)) => Some(Sig { dev, ts, nonce, sig }),
        _ => None,
    };
    (keep.join("&"), s)
}

impl Devices {
    pub fn load(dir: PathBuf) -> Self {
        let file = dir.join("devices.json");
        let list = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        Devices { file, list: RwLock::new(list), seen: Mutex::new(HashMap::new()) }
    }

    fn save(&self, l: &[Device]) {
        if let Ok(t) = serde_json::to_string_pretty(l) {
            let _ = std::fs::write(&self.file, t);
            let _ = std::fs::set_permissions(&self.file, std::os::unix::fs::PermissionsExt::from_mode(0o600));
        }
    }

    pub fn count(&self) -> usize {
        self.list.read().unwrap().len()
    }

    pub fn list_json(&self) -> Value {
        json!(self.list.read().unwrap().iter().map(|d| json!({"id": d.id, "name": d.name, "added": d.added, "fingerprint": hex(digest::digest(&digest::SHA256, &b64decode(&d.spki).unwrap_or_default()).as_ref())})).collect::<Vec<_>>())
    }

    /// Registers (or renames) a device; returns its id and SHA-256 fingerprint.
    pub fn enrol(&self, name: &str, spki_b64: &str) -> Result<(String, String), &'static str> {
        let der = b64decode(spki_b64).ok_or("bad key")?;
        point_of(&der).ok_or("not a P-256 public key")?;
        let fp = hex(digest::digest(&digest::SHA256, &der).as_ref());
        let id = fp[..16].to_string();
        let name: String = name.chars().filter(|c| !c.is_control()).take(60).collect();
        let mut l = self.list.write().unwrap();
        l.retain(|d| d.id != id);
        l.push(Device { id: id.clone(), name: if name.trim().is_empty() { "phone".into() } else { name }, spki: spki_b64.trim().to_string(), added: now_ms() / 1000 });
        self.save(&l);
        Ok((id, fp))
    }

    pub fn remove(&self, id: &str) -> bool {
        let mut l = self.list.write().unwrap();
        let before = l.len();
        l.retain(|d| d.id != id);
        self.save(&l);
        l.len() != before
    }

    /// Checks `sig` against the enrolled key for this request: right device, recent, never seen before, valid signature.
    pub fn verify(&self, method: &str, path_query: &str, s: &Sig) -> Result<(), &'static str> {
        let spki = self.list.read().unwrap().iter().find(|d| d.id == s.dev).map(|d| d.spki.clone()).ok_or("unknown device")?;
        let now = now_ms();
        if s.ts.abs_diff(now) > WINDOW_MS {
            return Err("clock too far off");
        }
        let der = b64decode(&spki).ok_or("bad stored key")?;
        let point = point_of(&der).ok_or("bad stored key")?;
        let sig = b64decode(&s.sig).ok_or("bad signature")?;
        let msg = format!("{method}\n{path_query}\n{}\n{}", s.ts, s.nonce);
        signature::UnparsedPublicKey::new(&signature::ECDSA_P256_SHA256_ASN1, point).verify(msg.as_bytes(), &sig).map_err(|_| "bad signature")?;
        let mut seen = self.seen.lock().unwrap();
        if seen.len() > 5000 {
            seen.retain(|_, exp| *exp > now);
        }
        if seen.insert(format!("{}:{}", s.dev, s.nonce), now + 2 * WINDOW_MS).is_some_and(|exp| exp > now) {
            return Err("replayed request");
        }
        Ok(())
    }
}

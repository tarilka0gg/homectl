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
    #[cfg(test)]
    scratch: bool,
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
        Devices { file, #[cfg(test)] scratch: false, list: RwLock::new(list), seen: Mutex::new(HashMap::new()) }
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

#[cfg(test)]
mod tests {
    use super::*;
    use ring::{rand::SystemRandom, signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING}};

    fn b64(b: &[u8]) -> String {
        const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut s = String::new();
        for c in b.chunks(3) {
            let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
            for i in 0..=c.len() {
                s.push(T[(n >> (18 - 6 * i) & 63) as usize] as char);
            }
        }
        s
    }

    struct Phone {
        key: EcdsaKeyPair,
        spki_b64: String,
    }

    fn phone() -> Phone {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
        let key = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng).unwrap();
        let mut spki = SPKI_P256_PREFIX.to_vec();
        spki.extend_from_slice(key.public_key().as_ref());
        Phone { key, spki_b64: b64(&spki) }
    }

    fn sign(p: &Phone, id: &str, method: &str, pq: &str, ts: u64, nonce: &str) -> Sig {
        let sig = p.key.sign(&SystemRandom::new(), format!("{method}\n{pq}\n{ts}\n{nonce}").as_bytes()).unwrap();
        Sig { dev: id.into(), ts, nonce: nonce.into(), sig: b64(sig.as_ref()) }
    }

    /// A registry in its own scratch folder. Tests run in parallel inside one process, so the folder name needs a counter —
    /// the process id and the clock alone can collide, and two tests would then share one `devices.json`.
    fn devices() -> Devices {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("homectl-test-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut d = Devices::load(dir);
        d.scratch = true;
        d
    }

    #[test]
    fn base64_accepts_both_alphabets_and_padding() {
        assert_eq!(b64decode("c2VjcmV0").unwrap(), b"secret");
        assert_eq!(b64decode("c2VjcmV0IQ==").unwrap(), b"secret!");
        assert_eq!(b64decode("c2VjcmV0IQ").unwrap(), b"secret!");
        assert_eq!(b64decode("-_-_").unwrap(), b64decode("+/+/").unwrap());
        assert!(b64decode("not base64!").is_none());
    }

    #[test]
    fn only_p256_public_keys_are_enrolled() {
        let d = devices();
        assert!(d.enrol("x", "AAAA").is_err());
        assert!(d.enrol("x", "###").is_err());
        let p = phone();
        let (id, fp) = d.enrol("my phone", &p.spki_b64).unwrap();
        assert_eq!(id, &fp[..16]);
        assert_eq!(d.count(), 1);
        d.enrol("renamed", &p.spki_b64).unwrap(); // same key: replaced, not duplicated
        assert_eq!(d.count(), 1);
    }

    #[test]
    fn a_valid_signature_is_accepted_exactly_once() {
        let (d, p) = (devices(), phone());
        let (id, _) = d.enrol("p", &p.spki_b64).unwrap();
        let s = sign(&p, &id, "GET", "/api/monitor", now_ms(), "n1");
        assert_eq!(d.verify("GET", "/api/monitor", &s), Ok(()));
        assert_eq!(d.verify("GET", "/api/monitor", &s), Err("replayed request"));
    }

    #[test]
    fn a_signature_is_bound_to_method_path_and_query() {
        let (d, p) = (devices(), phone());
        let (id, _) = d.enrol("p", &p.spki_b64).unwrap();
        let s = sign(&p, &id, "GET", "/api/fs/list?path=/home", now_ms(), "n2");
        assert_eq!(d.verify("GET", "/api/fs/list?path=/etc", &s), Err("bad signature"));
        assert_eq!(d.verify("POST", "/api/fs/list?path=/home", &s), Err("bad signature"));
        assert_eq!(d.verify("GET", "/api/fs/list?path=/home", &s), Ok(())); // the genuine request still works
    }

    #[test]
    fn stale_future_forged_and_unknown_are_refused() {
        let (d, p) = (devices(), phone());
        let (id, _) = d.enrol("p", &p.spki_b64).unwrap();
        let old = sign(&p, &id, "GET", "/a", now_ms() - 600_000, "n3");
        assert_eq!(d.verify("GET", "/a", &old), Err("clock too far off"));
        let future = sign(&p, &id, "GET", "/a", now_ms() + 600_000, "n4");
        assert_eq!(d.verify("GET", "/a", &future), Err("clock too far off"));
        let other = phone(); // a key that was never enrolled, claiming to be the enrolled phone
        let forged = sign(&other, &id, "GET", "/a", now_ms(), "n5");
        assert_eq!(d.verify("GET", "/a", &forged), Err("bad signature"));
        let stranger = sign(&p, "0000000000000000", "GET", "/a", now_ms(), "n6");
        assert_eq!(d.verify("GET", "/a", &stranger), Err("unknown device"));
        let mut tampered = sign(&p, &id, "GET", "/a", now_ms(), "n7");
        tampered.nonce = "n8".into();
        assert_eq!(d.verify("GET", "/a", &tampered), Err("bad signature"));
    }

    #[test]
    fn a_removed_phone_is_refused_at_once() {
        let (d, p) = (devices(), phone());
        let (id, _) = d.enrol("p", &p.spki_b64).unwrap();
        assert!(d.remove(&id));
        assert!(!d.remove(&id));
        assert_eq!(d.verify("GET", "/a", &sign(&p, &id, "GET", "/a", now_ms(), "n9")), Err("unknown device"));
    }

    #[test]
    fn websocket_signature_parameters_are_split_off_the_query() {
        let (q, s) = split_query("t=TOKEN&s=main&cols=80&dev=abc&ts=12&n=xyz&sig=SIG&rows=24");
        assert_eq!(q, "t=TOKEN&s=main&cols=80&rows=24");
        let s = s.unwrap();
        assert_eq!((s.dev.as_str(), s.ts, s.nonce.as_str(), s.sig.as_str()), ("abc", 12, "xyz", "SIG"));
        assert!(split_query("t=TOKEN&dev=abc&ts=12").1.is_none()); // incomplete: not a signature
    }

    #[test]
    fn tunnel_and_hub_requests_are_recognised_from_their_headers() {
        let mut h = HeaderMap::new();
        assert!(!via_tunnel(&h) && !via_hub(&h));
        h.insert("cf-connecting-ip", "1.2.3.4".parse().unwrap());
        assert!(via_tunnel(&h));
        let mut h = HeaderMap::new();
        h.insert("x-homectl-via", "hub".parse().unwrap());
        assert!(via_hub(&h) && !via_tunnel(&h));
    }

    impl Drop for Devices {
        fn drop(&mut self) {
            if self.scratch {
                if let Some(dir) = self.file.parent() {
                    let _ = std::fs::remove_dir_all(dir);
                }
            }
        }
    }
}

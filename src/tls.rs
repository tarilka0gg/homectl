//! Encrypted connections: the daemon makes its own self-signed certificate on first start and serves HTTPS on the same
//! port as plain HTTP (it looks at the first byte of each connection). Nothing trusts a certificate authority: the app
//! pins the certificate's SHA-256 fingerprint when it pairs, so a connection can neither be read nor impersonated.
use std::{path::PathBuf, sync::Arc};
use tokio_rustls::rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig,
};

pub struct Tls {
    pub acceptor: tokio_rustls::TlsAcceptor,
    /// lower-case hex SHA-256 of the DER certificate — what the app pins
    pub fingerprint: String,
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn load_or_create(dir: &PathBuf) -> Result<Tls, String> {
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");
    if !cert_path.exists() || !key_path.exists() {
        let host = std::fs::read_to_string("/proc/sys/kernel/hostname").unwrap_or_default().trim().to_string();
        let mut names = vec!["homectl".to_string(), "localhost".to_string()];
        if !host.is_empty() {
            names.push(host);
        }
        let mut params = rcgen::CertificateParams::new(names).map_err(|e| e.to_string())?;
        params.distinguished_name.push(rcgen::DnType::CommonName, "homectl");
        params.not_before = rcgen::date_time_ymd(2024, 1, 1);
        params.not_after = rcgen::date_time_ymd(2054, 1, 1);
        let key = rcgen::KeyPair::generate().map_err(|e| e.to_string())?;
        let cert = params.self_signed(&key).map_err(|e| e.to_string())?;
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        std::fs::write(&cert_path, cert.pem()).map_err(|e| e.to_string())?;
        std::fs::write(&key_path, key.serialize_pem()).map_err(|e| e.to_string())?;
        std::fs::set_permissions(&key_path, std::os::unix::fs::PermissionsExt::from_mode(0o600)).ok();
        eprintln!("created a new TLS certificate in {}", dir.display());
    }
    let cert_pem = std::fs::read_to_string(&cert_path).map_err(|e| e.to_string())?;
    let key_pem = std::fs::read_to_string(&key_path).map_err(|e| e.to_string())?;
    let cert_der = pem_block(&cert_pem, "CERTIFICATE").ok_or("bad certificate file")?;
    let key_der = pem_block(&key_pem, "PRIVATE KEY").ok_or("bad key file")?;
    let fingerprint = hex(ring::digest::digest(&ring::digest::SHA256, &cert_der).as_ref());
    let provider = Arc::new(tokio_rustls::rustls::crypto::ring::default_provider());
    let mut cfg = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(vec![CertificateDer::from(cert_der)], PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der)))
        .map_err(|e| e.to_string())?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()]; // WebSocket upgrades need HTTP/1.1
    Ok(Tls { acceptor: tokio_rustls::TlsAcceptor::from(Arc::new(cfg)), fingerprint })
}

/// The DER bytes of the first `-----BEGIN <label>-----` block.
fn pem_block(pem: &str, label: &str) -> Option<Vec<u8>> {
    let begin = format!("-----BEGIN {label}-----");
    let end = format!("-----END {label}-----");
    let body = pem.split(&begin).nth(1)?.split(&end).next()?;
    let mut out = vec![];
    let (mut bits, mut n) = (0u32, 0);
    for c in body.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => continue,
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

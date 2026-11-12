//! An in-process stand-in for the relay's device API (`docs/relay-sync.md`), over plain HTTP
//! or TLS, that checks signatures as the relay does, and helpers that build a package as the
//! phone does.

#![allow(dead_code)]

use morningissue::crypto::{self, DeviceKeys, KIND_PACKAGE, KIND_WRAP};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashSet};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::thread;

pub struct Package {
    pub id: String,
    pub envelope: Vec<u8>,
    /// Wrapped keys by device kid.
    pub keys: BTreeMap<String, String>,
}

pub struct RelayState {
    /// Listed devices: kid → auth key.
    pub devices: BTreeMap<String, [u8; 32]>,
    /// The relay's clock runs this many seconds ahead of the system clock.
    pub skew_s: i64,
    pub package: Option<Package>,
    /// (kid, batch, envelope), a batch uploaded again replacing the earlier one.
    pub changes: Vec<(String, String, Vec<u8>)>,
    pub max_pending: usize,
    /// (kid, package ID) for every `fetched`.
    pub fetched: Vec<(String, String)>,
    /// "METHOD target" of every request, signed or not.
    pub requests: Vec<String>,
    /// The `Range` header of every package download.
    pub ranges: Vec<Option<String>>,
    /// The next package download sends this many body bytes, then closes the connection.
    pub cut_download_after: Option<usize>,
    pub seen: HashSet<String>,
    /// The code of every `401` sent.
    pub refused: Vec<String>,
}

#[derive(Clone)]
pub struct FakeRelay {
    pub state: Arc<Mutex<RelayState>>,
    pub port: u16,
    pub tls: bool,
}

pub fn now_s() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64
}

fn http_date(secs: i64) -> String {
    let (y, m, d) = morningissue::time::civil_from_days(secs.div_euclid(86_400));
    let wd = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"][secs.div_euclid(86_400).rem_euclid(7) as usize];
    let r = secs.rem_euclid(86_400);
    format!("{wd}, {d:02} {} {y} {:02}:{:02}:{:02} GMT", morningissue::time::MONTHS[m as usize - 1], r / 3600, r % 3600 / 60, r % 60)
}

trait Conn: Read + Write + Send {}
impl<T: Read + Write + Send> Conn for T {}

impl FakeRelay {
    pub fn start() -> FakeRelay {
        Self::start_with(None)
    }

    /// Serves TLS with this certificate chain and key (DER).
    pub fn start_tls(chain: Vec<Vec<u8>>, key: Vec<u8>) -> FakeRelay {
        Self::start_with(Some((chain, key)))
    }

    fn start_with(tls: Option<(Vec<Vec<u8>>, Vec<u8>)>) -> FakeRelay {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = Arc::new(Mutex::new(RelayState {
            devices: BTreeMap::new(),
            skew_s: 0,
            package: None,
            changes: Vec::new(),
            max_pending: 64,
            fetched: Vec::new(),
            requests: Vec::new(),
            ranges: Vec::new(),
            cut_download_after: None,
            seen: HashSet::new(),
            refused: Vec::new(),
        }));
        let server = tls.as_ref().map(|(chain, key)| {
            use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
            let chain: Vec<CertificateDer<'static>> = chain.iter().map(|c| CertificateDer::from(c.clone())).collect();
            let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.clone()));
            Arc::new(
                rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                    .with_safe_default_protocol_versions()
                    .unwrap()
                    .with_no_client_auth()
                    .with_single_cert(chain, key)
                    .unwrap(),
            )
        });
        let s = state.clone();
        thread::spawn(move || {
            for conn in listener.incoming() {
                let Ok(conn) = conn else { continue };
                let s = s.clone();
                let server = server.clone();
                thread::spawn(move || {
                    let stream: Box<dyn Conn> = match server {
                        Some(cfg) => Box::new(rustls::StreamOwned::new(rustls::ServerConnection::new(cfg).unwrap(), conn)),
                        None => Box::new(conn),
                    };
                    handle(stream, &s);
                });
            }
        });
        FakeRelay { state, port, tls: tls.is_some() }
    }

    pub fn url(&self) -> String {
        if self.tls {
            format!("https://localhost:{}", self.port)
        } else {
            format!("http://127.0.0.1:{}", self.port)
        }
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut RelayState) -> R) -> R {
        f(&mut self.state.lock().unwrap())
    }

    /// What the phone does when it scans the code and sends the device list.
    pub fn list(&self, keys: &DeviceKeys) {
        self.with(|s| s.devices.insert(keys.kid.clone(), keys.auth));
    }

    /// What the phone does when it drops a device: its pending changes go too.
    pub fn unlist(&self, keys: &DeviceKeys) {
        self.with(|s| {
            s.devices.remove(&keys.kid);
            s.changes.retain(|c| c.0 != keys.kid);
        });
    }

    /// Encrypts `zip` as a new package for these devices and publishes it.
    pub fn publish(&self, id: &str, zip: &[u8], devices: &[&DeviceKeys]) {
        let pkg_key: [u8; 32] = crypto::random().unwrap();
        let envelope = crypto::seal(&pkg_key, KIND_PACKAGE, &format!("package|{id}"), zip).unwrap();
        let keys = devices.iter().map(|d| (d.kid.clone(), crypto::b64url(&crypto::seal(&d.enc, KIND_WRAP, &format!("wrap|{}|{id}", d.kid), &pkg_key).unwrap()))).collect();
        self.with(|s| s.package = Some(Package { id: id.into(), envelope, keys }));
    }

    /// The decrypted changes a device uploaded, by batch.
    pub fn changes_of(&self, keys: &DeviceKeys) -> Vec<(String, Value)> {
        self.with(|s| {
            s.changes
                .iter()
                .filter(|c| c.0 == keys.kid)
                .map(|(_, batch, env)| {
                    let pt = crypto::open(&keys.enc, crypto::KIND_CHANGES, &format!("changes|{}|{batch}", keys.kid), env).unwrap();
                    (batch.clone(), serde_json::from_slice(&pt).unwrap())
                })
                .collect()
        })
    }

    pub fn count(&self, prefix: &str) -> usize {
        self.with(|s| s.requests.iter().filter(|r| r.starts_with(prefix)).count())
    }
}

fn respond(conn: &mut dyn Write, status: u16, skew: i64, headers: &[(&str, String)], body: &[u8], send: Option<usize>) {
    let mut out = format!("HTTP/1.1 {status} X\r\nDate: {}\r\nContent-Length: {}\r\nConnection: close\r\n", http_date(now_s() + skew), body.len()).into_bytes();
    for (k, v) in headers {
        out.extend(format!("{k}: {v}\r\n").as_bytes());
    }
    out.extend(b"\r\n");
    out.extend(&body[..send.unwrap_or(body.len()).min(body.len())]);
    let _ = conn.write_all(&out);
    let _ = conn.flush();
}

fn handle(stream: Box<dyn Conn>, state: &Arc<Mutex<RelayState>>) {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    if reader.read_line(&mut line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("").to_string();
    let (mut auth, mut range, mut len) = (String::new(), None, 0usize);
    loop {
        let mut h = String::new();
        if reader.read_line(&mut h).unwrap_or(0) == 0 {
            return;
        }
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        let (k, v) = h.split_once(':').unwrap();
        match k.to_ascii_lowercase().as_str() {
            "authorization" => auth = v.trim().to_string(),
            "range" => range = Some(v.trim().to_string()),
            "content-length" => len = v.trim().parse().unwrap(),
            _ => {}
        }
    }
    let mut body = vec![0u8; len];
    if reader.read_exact(&mut body).is_err() {
        return;
    }
    let conn = reader.get_mut();
    let mut s = state.lock().unwrap();
    s.requests.push(format!("{method} {target}"));
    let skew = s.skew_s;
    let refuse = |s: &mut RelayState, conn: &mut dyn Write, code: &str| {
        s.refused.push(code.into());
        respond(conn, 401, skew, &[], json!({"error": code}).to_string().as_bytes(), None);
    };

    // Signature: MIR1 kid=…, ts=…, sig=…
    let fields: BTreeMap<&str, &str> = auth.strip_prefix("MIR1 ").unwrap_or("").split(", ").filter_map(|f| f.split_once('=')).collect();
    let (kid, ts, sig) = (fields.get("kid").copied().unwrap_or(""), fields.get("ts").and_then(|t| t.parse::<i64>().ok()).unwrap_or(0), fields.get("sig").copied().unwrap_or(""));
    let Some(key) = s.devices.get(kid).copied() else { return refuse(&mut s, conn, "unknown") };
    if (ts - (now_s() + skew)).abs() > 300 {
        return refuse(&mut s, conn, "skew");
    }
    if crypto::sign(&key, &method, &target, ts, &body) != sig {
        return refuse(&mut s, conn, "signature");
    }
    if !s.seen.insert(sig.to_string()) {
        return refuse(&mut s, conn, "replay");
    }

    let path = target.as_str();
    if method == "GET" && path == "/v1/package" {
        let found = s.package.as_ref().and_then(|p| p.keys.get(kid).map(|k| json!({"id": p.id, "size": p.envelope.len(), "key": k})));
        match found {
            Some(v) => respond(conn, 200, skew, &[("Content-Type", "application/json".into())], v.to_string().as_bytes(), None),
            None => respond(conn, 204, skew, &[], b"", None),
        }
    } else if let Some(id) = path.strip_prefix("/v1/packages/").filter(|_| method == "GET") {
        s.ranges.push(range.clone());
        let env = match s.package.as_ref().filter(|p| p.id == id && p.keys.contains_key(kid)) {
            Some(p) => p.envelope.clone(),
            None => return respond(conn, 404, skew, &[], br#"{"error":"not_found"}"#, None),
        };
        let cut = s.cut_download_after.take();
        let etag = ("ETag", format!("\"{id}\""));
        match range.as_deref().and_then(|r| r.strip_prefix("bytes=")).and_then(|r| r.strip_suffix('-')).and_then(|r| r.parse::<usize>().ok()) {
            Some(from) if from >= env.len() => respond(conn, 416, skew, &[], b"", None),
            Some(from) => respond(conn, 206, skew, &[etag, ("Content-Range", format!("bytes {from}-{}/{}", env.len() - 1, env.len()))], &env[from..], cut),
            None => respond(conn, 200, skew, &[etag], &env, cut),
        }
    } else if let Some(id) = path.strip_prefix("/v1/packages/").and_then(|p| p.strip_suffix("/fetched")).filter(|_| method == "POST") {
        s.fetched.push((kid.to_string(), id.to_string()));
        respond(conn, 204, skew, &[], b"", None);
    } else if let Some(batch) = path.strip_prefix("/v1/changes/").filter(|_| method == "PUT") {
        let ok = batch.split_once('-').and_then(|(a, b)| Some((a.parse::<i64>().ok()?, b.parse::<i64>().ok()?))).is_some_and(|(a, b)| a <= b && batch == format!("{a}-{b}"));
        if !ok || !body.starts_with(b"MIR1") || body.len() > 4 << 20 {
            return respond(conn, 400, skew, &[], br#"{"error":"bad_request"}"#, None);
        }
        let replacing = s.changes.iter().any(|c| c.0 == kid && c.1 == batch);
        if !replacing && s.changes.iter().filter(|c| c.0 == kid).count() >= s.max_pending {
            return respond(conn, 429, skew, &[], br#"{"error":"too_many"}"#, None);
        }
        s.changes.retain(|c| !(c.0 == kid && c.1 == batch));
        s.changes.push((kid.to_string(), batch.to_string(), body));
        respond(conn, 204, skew, &[], b"", None);
    } else {
        respond(conn, 404, skew, &[], br#"{"error":"not_found"}"#, None);
    }
}

// --------------------------------------------------------------------------------------------
// Packages, as the phone builds them

fn crc32(data: &[u8]) -> u32 {
    let mut c = !0u32;
    for &b in data {
        c ^= b as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { (c >> 1) ^ 0xedb8_8320 } else { c >> 1 };
        }
    }
    !c
}

/// A zip of these entries; the ones marked are deflated, the rest stored.
pub fn zip(entries: &[(String, Vec<u8>, bool)]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (name, data, deflate) in entries {
        let packed = if *deflate { miniz_oxide::deflate::compress_to_vec(data, 6) } else { data.clone() };
        let method: u16 = if *deflate { 8 } else { 0 };
        let offset = out.len() as u32;
        let crc = crc32(data);
        let mut common = Vec::new();
        common.extend(20u16.to_le_bytes()); // version needed
        common.extend(0u16.to_le_bytes()); // flags
        common.extend(method.to_le_bytes());
        common.extend(0u32.to_le_bytes()); // time, date
        common.extend(crc.to_le_bytes());
        common.extend((packed.len() as u32).to_le_bytes());
        common.extend((data.len() as u32).to_le_bytes());
        common.extend((name.len() as u16).to_le_bytes());
        common.extend(0u16.to_le_bytes()); // extra
        out.extend(0x0403_4b50u32.to_le_bytes());
        out.extend(&common);
        out.extend(name.as_bytes());
        out.extend(&packed);
        central.extend(0x0201_4b50u32.to_le_bytes());
        central.extend(20u16.to_le_bytes()); // version made by
        central.extend(&common);
        central.extend([0u8; 6]); // comment length, disk, internal attributes
        central.extend(0u32.to_le_bytes()); // external attributes
        central.extend(offset.to_le_bytes());
        central.extend(name.as_bytes());
    }
    let cd_offset = out.len() as u32;
    out.extend(&central);
    out.extend(0x0605_4b50u32.to_le_bytes());
    out.extend([0u8; 4]);
    out.extend((entries.len() as u16).to_le_bytes());
    out.extend((entries.len() as u16).to_le_bytes());
    out.extend((central.len() as u32).to_le_bytes());
    out.extend(cd_offset.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    out
}

/// One edition in a package: (id, version, bundle).
pub type Edition = (i64, String, Value);

/// A picture in a package: (url, w, bytes, type).
pub type Picture = (String, u32, Vec<u8>, String);

/// The package plaintext: a manifest (with `acked`), the editions, the saved shelf and the
/// pictures, the editions and manifest deflated and the rest stored, as a phone might.
pub fn package(db_id: &str, editions: &[Edition], saved: Option<(&str, Value)>, pictures: &[Picture], acked: Value) -> Vec<u8> {
    let manifest_editions: Vec<Value> =
        editions.iter().map(|(id, v, b)| json!({"id": id, "date": b["date"], "kind": b["kind"], "number": b["number"], "releaseAt": "2026-11-11T06:30:00Z", "version": v})).collect();
    let mut manifest = json!({"v": 1, "protocol": "relay/1", "builtAt": "2026-11-12T06:00:00Z", "dbId": db_id, "editions": manifest_editions, "acked": acked});
    let mut entries: Vec<(String, Vec<u8>, bool)> = Vec::new();
    if let Some((version, body)) = &saved {
        manifest["saved"] = json!({"count": body["order"].as_array().map(|a| a.len()).unwrap_or(0), "version": version});
        entries.push(("saved.json".into(), body.to_string().into_bytes(), false));
    }
    entries.insert(0, ("manifest.json".into(), manifest.to_string().into_bytes(), true));
    for (id, _, b) in editions {
        entries.push((format!("editions/{id}.json"), b.to_string().into_bytes(), true));
    }
    let mut index = Vec::new();
    for (url, w, bytes, kind) in pictures {
        let file = format!("images/{}-{w}", morningissue::store::image_key(url));
        index.push(json!({"url": url, "w": w, "file": file, "type": kind}));
        entries.push((file, bytes.clone(), false));
    }
    entries.push(("images.json".into(), Value::Array(index).to_string().into_bytes(), false));
    zip(&entries)
}

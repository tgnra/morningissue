//! An in-process stand-in for the Morning Issue server's Kobo API (`docs/server-design.md`
//! section 4, `docs/kobo-sync.md`), over plain HTTP or TLS: bearer tokens, the events gate,
//! the manifest, bundles, the saved shelf and the picture zips, with the paths, headers and
//! error bodies of the real one.

#![allow(dead_code)]

use serde_json::{json, Value};
use std::collections::{BTreeMap, VecDeque};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::thread;

/// A picture the server can prepare: (bytes, type, width, height).
pub type Picture = (Vec<u8>, String, u32, u32);

pub struct ServerState {
    /// The paired Kobo's token, as the phone registered it; None until then.
    pub token: Option<String>,
    /// Its `deviceId`, from the pairing code.
    pub device_id: String,
    pub db_id: String,
    /// Whether `GET /kobo/hello` names `db_id`; an older server left it out.
    pub hello_db_id: bool,
    /// Released editions, newest first: (id, version, bundle).
    pub editions: Vec<(i64, String, Value)>,
    /// The saved shelf: (version, body). None leaves `saved` out of the manifest.
    pub saved: Option<(String, Value)>,
    /// Pictures the server can get, by URL.
    pub pictures: BTreeMap<String, Picture>,
    /// The device's `acked_seq`.
    pub acked: i64,
    /// Every event merged, in order.
    pub events: Vec<Value>,
    /// The next uploads acknowledge at most these seqs, one per upload, as if the server had
    /// not taken them all.
    pub ack_caps: VecDeque<i64>,
    /// The server's clock runs this many seconds ahead of the system clock.
    pub skew_s: i64,
    /// "METHOD target" of every request.
    pub requests: Vec<String>,
    /// The `X-Events-Through` of every gated request, by "METHOD target".
    pub through: Vec<(String, Option<i64>)>,
    /// The code of every error answer sent ("unknown", "events_pending", …).
    pub refused: Vec<String>,
    /// (width, urls) of every picture request.
    pub image_requests: Vec<(u32, Vec<String>)>,
    /// The size of every events upload.
    pub uploads: Vec<usize>,
}

#[derive(Clone)]
pub struct FakeServer {
    pub state: Arc<Mutex<ServerState>>,
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

impl FakeServer {
    pub fn start() -> FakeServer {
        Self::start_with(None)
    }

    /// Serves TLS with this certificate chain and key (DER).
    pub fn start_tls(chain: Vec<Vec<u8>>, key: Vec<u8>) -> FakeServer {
        Self::start_with(Some((chain, key)))
    }

    fn start_with(tls: Option<(Vec<Vec<u8>>, Vec<u8>)>) -> FakeServer {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = Arc::new(Mutex::new(ServerState {
            token: None,
            device_id: String::new(),
            db_id: "db".into(),
            hello_db_id: true,
            editions: Vec::new(),
            saved: None,
            pictures: BTreeMap::new(),
            acked: 0,
            events: Vec::new(),
            ack_caps: VecDeque::new(),
            skew_s: 0,
            requests: Vec::new(),
            through: Vec::new(),
            refused: Vec::new(),
            image_requests: Vec::new(),
            uploads: Vec::new(),
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
                    handle(stream, &s, port);
                });
            }
        });
        FakeServer { state, port, tls: tls.is_some() }
    }

    pub fn url(&self) -> String {
        if self.tls {
            format!("https://localhost:{}", self.port)
        } else {
            format!("http://127.0.0.1:{}", self.port)
        }
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut ServerState) -> R) -> R {
        f(&mut self.state.lock().unwrap())
    }

    /// What `POST /api/v1/devices/kobo` does with the scanned code: the server derives the
    /// Kobo's token from `s` and accepts it from then on.
    pub fn pair(&self, pairing_uri: &str) {
        let query = pairing_uri.strip_prefix("morningissue:pair?").expect("a pairing URI");
        let fields: BTreeMap<&str, &str> = query.split('&').filter_map(|f| f.split_once('=')).collect();
        assert_eq!(fields.get("v"), Some(&"2"), "{pairing_uri}");
        let s = morningissue::crypto::b64url_decode(fields["s"]).unwrap();
        self.with(|st| {
            st.token = Some(morningissue::crypto::token(&s));
            st.device_id = fields["d"].to_string();
        });
    }

    /// Accepts the token derived from this secret, for this device.
    pub fn pair_secret(&self, secret: &[u8], device_id: &str) {
        self.with(|st| {
            st.token = Some(morningissue::crypto::token(secret));
            st.device_id = device_id.to_string();
        });
    }

    /// `DELETE /api/v1/devices/{id}`: the token is refused from now on.
    pub fn remove(&self) {
        self.with(|st| st.token = None);
    }

    pub fn count(&self, prefix: &str) -> usize {
        self.with(|s| s.requests.iter().filter(|r| r.starts_with(prefix)).count())
    }
}

fn respond(conn: &mut dyn Write, status: u16, skew: i64, content_type: &str, body: &[u8]) {
    let mut out = format!("HTTP/1.1 {status} X\r\nDate: {}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", http_date(now_s() + skew), body.len()).into_bytes();
    out.extend(body);
    let _ = conn.write_all(&out);
    let _ = conn.flush();
}

fn json(conn: &mut dyn Write, status: u16, skew: i64, v: Value) {
    respond(conn, status, skew, "application/json", v.to_string().as_bytes());
}

fn error(s: &mut ServerState, conn: &mut dyn Write, status: u16, code: &str, extra: Value) {
    s.refused.push(code.into());
    let mut v = json!({"error": code, "message": format!("fake server: {code}")});
    if let (Some(o), Some(e)) = (v.as_object_mut(), extra.as_object()) {
        o.extend(e.clone());
    }
    json(conn, status, s.skew_s, v);
}

fn handle(stream: Box<dyn Conn>, state: &Arc<Mutex<ServerState>>, port: u16) {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    if reader.read_line(&mut line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("").to_string();
    let (mut auth, mut through, mut len) = (None, None, 0usize);
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
            "authorization" => auth = Some(v.trim().to_string()),
            "x-events-through" => through = Some(v.trim().to_string()),
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
    let request = format!("{method} {target}");
    s.requests.push(request.clone());
    let skew = s.skew_s;
    let (path, query) = target.split_once('?').unwrap_or((&target, ""));

    // Auth: a Kobo token the server knows.
    let Some(token) = auth.as_deref().and_then(|a| a.strip_prefix("Bearer ")) else { return error(&mut s, conn, 401, "unauthenticated", json!({})) };
    if s.token.as_deref() != Some(token) {
        return error(&mut s, conn, 401, "unknown", json!({}));
    }

    // The gate, on the endpoints that hand out edition data.
    let gated = matches!((method.as_str(), path), ("GET", "/api/v1/sync/manifest") | ("GET", "/api/v1/saved") | ("POST", "/api/v1/kobo/images")) || (method == "GET" && path.starts_with("/api/v1/editions/") && path.ends_with("/bundle"));
    if gated {
        let seq = through.as_deref().map(|t| t.parse::<i64>().expect("X-Events-Through is a number"));
        s.through.push((request.clone(), seq));
        let Some(seq) = seq else { return error(&mut s, conn, 400, "events_through_required", json!({})) };
        if seq > s.acked {
            let acked = s.acked;
            return error(&mut s, conn, 409, "events_pending", json!({"acked": acked}));
        }
    }

    match (method.as_str(), path) {
        ("GET", "/api/v1/kobo/hello") => {
            let mut v = json!({"username": "tania", "server": format!("http://127.0.0.1:{port}")});
            if s.hello_db_id {
                v["dbId"] = json!(s.db_id);
            }
            json(conn, 200, skew, v);
        }
        ("POST", "/api/v1/sync/events") => {
            let v: Value = serde_json::from_slice(&body).unwrap();
            let events = v["events"].as_array().cloned().unwrap_or_default();
            if events.len() > 500 {
                return error(&mut s, conn, 400, "bad_request", json!({}));
            }
            s.uploads.push(events.len());
            let cap = s.ack_caps.pop_front();
            let mut sorted = events.clone();
            sorted.sort_by_key(|e| e["seq"].as_i64().unwrap());
            for e in sorted {
                let seq = e["seq"].as_i64().unwrap();
                if seq > s.acked && cap.is_none_or(|c| seq <= c) {
                    s.acked = seq;
                    s.events.push(e);
                }
            }
            let acked = s.acked;
            json(conn, 200, skew, json!({"acked": acked}));
        }
        ("GET", "/api/v1/sync/manifest") => {
            let n: usize = query.split('&').find_map(|q| q.strip_prefix("editions=")).and_then(|n| n.parse().ok()).unwrap_or(3);
            let editions: Vec<Value> = s
                .editions
                .iter()
                .take(n)
                .map(|(id, v, b)| json!({"id": id, "date": b["date"], "kind": b["kind"], "number": b["number"], "releaseAt": "2026-11-11T06:30:00Z", "version": v}))
                .collect();
            let mut m = json!({"v": 1, "protocol": "server/1", "dbId": s.db_id, "editions": editions, "acked": Value::Object([(s.device_id.clone(), json!(s.acked))].into_iter().collect()), "serverTime": "2026-11-12T06:00:00Z"});
            if let Some((version, body)) = &s.saved {
                m["saved"] = json!({"count": body["order"].as_array().map(|a| a.len()).unwrap_or(0), "version": version});
            }
            json(conn, 200, skew, m);
        }
        ("GET", "/api/v1/saved") => match s.saved.clone() {
            Some((_, body)) => json(conn, 200, skew, body),
            None => error(&mut s, conn, 404, "not_found", json!({})),
        },
        ("POST", "/api/v1/kobo/images") => {
            let v: Value = serde_json::from_slice(&body).unwrap();
            let width = v["width"].as_u64().unwrap() as u32;
            let urls: Vec<String> = v["urls"].as_array().unwrap().iter().map(|u| u.as_str().unwrap().to_string()).collect();
            if urls.len() > 100 {
                return error(&mut s, conn, 400, "bad_request", json!({}));
            }
            s.image_requests.push((width, urls.clone()));
            let mut index = Vec::new();
            let mut entries = Vec::new();
            for url in &urls {
                let Some((bytes, kind, w, h)) = s.pictures.get(url).cloned() else { continue };
                let file = format!("images/{}.{}", morningissue::store::image_key(url), if kind.contains("png") { "png" } else { "jpg" });
                index.push(json!({"url": url, "file": file, "type": kind, "width": w, "height": h}));
                entries.push((file, bytes, false));
            }
            entries.insert(0, ("images.json".to_string(), Value::Array(index).to_string().into_bytes(), true));
            respond(conn, 200, skew, "application/zip", &zip(&entries));
        }
        ("GET", p) if p.starts_with("/api/v1/editions/") && p.ends_with("/bundle") => {
            let id: i64 = p["/api/v1/editions/".len()..p.len() - "/bundle".len()].parse().unwrap();
            match s.editions.iter().find(|e| e.0 == id).map(|e| e.2.clone()) {
                Some(b) => json(conn, 200, skew, b),
                None => error(&mut s, conn, 404, "not_found", json!({})),
            }
        }
        _ => error(&mut s, conn, 404, "not_found", json!({})),
    }
}

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

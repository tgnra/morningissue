//! An in-process stand-in for the phone's side of `docs/kobo-sync.md`.

#![allow(dead_code)]

use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum PairAnswer {
    Allow,
    Deny,
    NoAnswer,
}

pub struct PhoneState {
    pub db_id: String,
    pub tokens: Vec<String>,
    pub pair_answer: PairAnswer,
    pub pair_requests: Vec<Value>,
    /// (id, version, bundle JSON), newest first.
    pub editions: Vec<(i64, String, Value)>,
    pub images: BTreeMap<String, Vec<u8>>,
    pub acked_seq: i64,
    /// Every event merged, in order, without duplicates.
    pub merged: Vec<Value>,
    /// Every POST /v1/events body received, including retries.
    pub event_posts: Vec<Value>,
    pub requests: Vec<String>,
    /// Requests whose path starts with this get the connection closed with no answer.
    pub drop_path: Option<String>,
    /// Drop the response to the next POST /v1/events after merging it.
    pub drop_events_response: bool,
    pub token_counter: u32,
}

#[derive(Clone)]
pub struct FakePhone {
    pub state: Arc<Mutex<PhoneState>>,
    pub address: String,
}

impl FakePhone {
    pub fn start(db_id: &str) -> FakePhone {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let state = Arc::new(Mutex::new(PhoneState {
            db_id: db_id.into(),
            tokens: Vec::new(),
            pair_answer: PairAnswer::Allow,
            pair_requests: Vec::new(),
            editions: Vec::new(),
            images: BTreeMap::new(),
            acked_seq: 0,
            merged: Vec::new(),
            event_posts: Vec::new(),
            requests: Vec::new(),
            drop_path: None,
            drop_events_response: false,
            token_counter: 0,
        }));
        let s = state.clone();
        thread::spawn(move || {
            for conn in listener.incoming() {
                let Ok(conn) = conn else { continue };
                let s = s.clone();
                thread::spawn(move || handle(conn, &s));
            }
        });
        FakePhone { state, address }
    }

    pub fn port(&self) -> u16 {
        self.address.rsplit(':').next().unwrap().parse().unwrap()
    }

    /// Sends beacons to `127.0.0.1:beacon_port` every 100 ms, `count` times.
    pub fn beacon(&self, beacon_port: u16, count: usize) {
        let port = self.port();
        let db = self.state.lock().unwrap().db_id.clone();
        thread::spawn(move || {
            let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
            for _ in 0..count {
                let junk = br#"{"app":"other","v":1,"port":1}"#;
                let _ = sock.send_to(junk, ("127.0.0.1", beacon_port));
                let msg = json!({"app": "morningissue", "v": 1, "port": port, "name": "Fake Pixel", "dbId": db, "extra": true});
                let _ = sock.send_to(msg.to_string().as_bytes(), ("127.0.0.1", beacon_port));
                thread::sleep(Duration::from_millis(100));
            }
        });
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut PhoneState) -> R) -> R {
        f(&mut self.state.lock().unwrap())
    }
}

fn respond(conn: &mut TcpStream, status: u16, ctype: &str, body: &[u8], chunked: bool) {
    let reason = match status {
        200 => "OK",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        408 => "Request Timeout",
        _ => "Other",
    };
    let date = "Wed, 11 Nov 2026 06:30:00 GMT";
    let mut out = if chunked {
        let mut o = format!("HTTP/1.1 {status} {reason}\r\nContent-Type: {ctype}\r\nDate: {date}\r\nTransfer-Encoding: chunked\r\n\r\n").into_bytes();
        for chunk in body.chunks(1000) {
            o.extend(format!("{:x}\r\n", chunk.len()).as_bytes());
            o.extend(chunk);
            o.extend(b"\r\n");
        }
        o.extend(b"0\r\n\r\n");
        o
    } else {
        let mut o = format!("HTTP/1.1 {status} {reason}\r\nContent-Type: {ctype}\r\nDate: {date}\r\nContent-Length: {}\r\n\r\n", body.len()).into_bytes();
        o.extend(body);
        o
    };
    let _ = conn.write_all(&out);
    out.clear();
}

fn handle(mut conn: TcpStream, state: &Arc<Mutex<PhoneState>>) {
    let mut reader = BufReader::new(conn.try_clone().unwrap());
    let mut line = String::new();
    if reader.read_line(&mut line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("").to_string();
    let mut auth = None;
    let mut len = 0usize;
    loop {
        let mut h = String::new();
        reader.read_line(&mut h).unwrap();
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        let (k, v) = h.split_once(':').unwrap();
        match k.to_ascii_lowercase().as_str() {
            "authorization" => auth = v.trim().strip_prefix("Bearer ").map(str::to_owned),
            "content-length" => len = v.trim().parse().unwrap(),
            _ => {}
        }
    }
    let mut body = vec![0u8; len];
    reader.read_exact(&mut body).unwrap();
    let body: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);

    let mut s = state.lock().unwrap();
    s.requests.push(format!("{method} {target}"));
    if s.drop_path.as_deref().is_some_and(|p| target.starts_with(p)) {
        return; // Connection closes with no response.
    }
    if method == "POST" && target == "/v1/pair" {
        s.pair_requests.push(body.clone());
        match s.pair_answer {
            PairAnswer::Allow => {
                s.token_counter += 1;
                let token = format!("tok{}", s.token_counter);
                s.tokens = vec![token.clone()];
                let db = s.db_id.clone();
                drop(s);
                respond(&mut conn, 200, "application/json", json!({"token": token, "dbId": db}).to_string().as_bytes(), false);
            }
            PairAnswer::Deny => respond(&mut conn, 403, "text/plain", b"no", false),
            PairAnswer::NoAnswer => respond(&mut conn, 408, "text/plain", b"late", false),
        }
        return;
    }
    if !auth.is_some_and(|t| s.tokens.contains(&t)) {
        drop(s);
        respond(&mut conn, 401, "text/plain", b"pair first", false);
        return;
    }
    if method == "GET" && target == "/v1/manifest" {
        let editions: Vec<Value> = s
            .editions
            .iter()
            .map(|(id, v, b)| json!({"id": id, "date": b["date"], "kind": b["kind"], "number": b["number"], "releaseAt": "2026-11-11T06:30:00Z", "version": v, "later": 1}))
            .collect();
        let m = json!({"v": 1, "dbId": s.db_id, "ackedSeq": s.acked_seq, "editions": editions, "unknownTopLevel": []});
        drop(s);
        respond(&mut conn, 200, "application/json", m.to_string().as_bytes(), true);
    } else if method == "GET" && target.starts_with("/v1/editions/") {
        let id: i64 = target["/v1/editions/".len()..].parse().unwrap_or(-1);
        let found = s.editions.iter().find(|e| e.0 == id).map(|e| e.2.to_string());
        drop(s);
        match found {
            Some(b) => respond(&mut conn, 200, "application/json", b.as_bytes(), false),
            None => respond(&mut conn, 404, "text/plain", b"", false),
        }
    } else if method == "GET" && target.starts_with("/v1/images?url=") {
        let url = decode(&target["/v1/images?url=".len()..]);
        let found = s.images.get(&url).cloned();
        drop(s);
        match found {
            Some(b) => respond(&mut conn, 200, "image/png", &b, false),
            None => respond(&mut conn, 404, "text/plain", b"", false),
        }
    } else if method == "POST" && target == "/v1/events" {
        s.event_posts.push(body.clone());
        for e in body["events"].as_array().cloned().unwrap_or_default() {
            let seq = e["seq"].as_i64().unwrap();
            if seq > s.acked_seq {
                s.acked_seq = seq;
                s.merged.push(e);
            }
        }
        let acked = s.acked_seq;
        if s.drop_events_response {
            s.drop_events_response = false;
            return;
        }
        drop(s);
        respond(&mut conn, 200, "application/json", json!({"ackedSeq": acked}).to_string().as_bytes(), false);
    } else {
        drop(s);
        respond(&mut conn, 404, "text/plain", b"", false);
    }
}

fn decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            out.push(u8::from_str_radix(&s[i + 1..i + 3], 16).unwrap());
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap()
}

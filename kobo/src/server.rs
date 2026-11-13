//! A sync with the Morning Issue server, in the order of `docs/kobo-sync.md` ("Sync", protocol
//! server/1). Every request carries `Authorization: Bearer <token>`, the token derived from
//! the secret in this Kobo's pairing code ([`crate::crypto::token`]).
//!
//! 1. No secret: [`SyncError::NotPaired`]. No server address: [`SyncError::NoServer`].
//! 2. Upload the events above the last `acked`, oldest first, 500 per
//!    `POST /api/v1/sync/events`; keep the `acked` each answer gives, and delete the events at
//!    or below it from `events.jsonl`.
//! 3. `GET /api/v1/sync/manifest?editions=3` with `X-Events-Through` set to the highest `seq`
//!    still in `events.jsonl` (0 if none). `409 events_pending` sends the events above the
//!    `acked` it names again and asks again, at most twice. Then the `protocol` and the
//!    `dbId` are checked (wiping on a change).
//! 4. Each edition whose `version` changed: `GET /api/v1/editions/{id}/bundle`, saved whole.
//! 5. The saved shelf if its `version` changed: `GET /api/v1/saved`.
//! 6. The pictures not stored yet, the newest edition's first in reading order, then the
//!    saved articles': `POST /api/v1/kobo/images`, 100 URLs at a time, at this Kobo's width.
//! 7. Editions, saved articles and pictures nothing refers to any more are deleted.
//!
//! The requests in 4 to 6 are gated as the manifest is: an event the reader records meanwhile
//! is uploaded and the request asked again. A `401 unknown` means the server does not know
//! the token: not paired yet, or removed from the account.

use crate::crypto;
use crate::http::{self, Call, Endpoint, Response, Sink};
use crate::images::{pick_image, scale_down, ImageEntry};
use crate::model::{Bundle, Manifest, SavedList};
use crate::store::{self, Event, State, Store};
use crate::sync::{Options, Progress, SyncError, SyncReport};
use crate::time;
use crate::zip::Zip;
use serde::Deserialize;
use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{self, Seek, SeekFrom};
use std::time::{Duration, Instant};

/// Every path is under this.
pub const API: &str = "/api/v1";
/// The manifest's `protocol`.
pub const PROTOCOL: &str = "server/1";
/// The gate header: the highest event `seq` this Kobo holds unacknowledged, or 0.
pub const EVENTS_THROUGH: &str = "X-Events-Through";
/// Events in one upload.
pub const BATCH: usize = 500;
/// Picture URLs in one `POST /kobo/images`.
pub const IMAGE_CHUNK: usize = 100;
/// The editions a Kobo keeps.
pub const EDITIONS: usize = 3;
/// Times a gated request uploads the events again after `409 events_pending`.
const GATE_ROUNDS: usize = 2;

/// Checks a server address: `http://` or `https://`, a host, an optional port and path
/// prefix. An address without a scheme gets `http://` when it is an IPv4 address and
/// `https://` otherwise. Returns it written out in full.
pub fn parse_address(s: &str) -> Result<String, String> {
    let s = s.trim();
    if s.is_empty() {
        return Err("The address is empty.".into());
    }
    let full = if s.contains("://") {
        s.to_string()
    } else {
        let host = s.split(['/', ':']).next().unwrap_or("");
        let ipv4 = host.split('.').count() == 4 && host.split('.').all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()));
        format!("{}://{s}", if ipv4 { "http" } else { "https" })
    };
    Endpoint::parse(&full).map(|e| e.url())
}

/// Whether an address sends the token in the clear.
pub fn is_plain_http(address: &str) -> bool {
    address.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("http://"))
}

/// The pairing code's text: `morningissue:pair?v=2&s=…&d=…&n=…&w=…`.
pub fn pair_uri(secret: &str, device_id: &str, name: &str, width: u32) -> String {
    format!("morningissue:pair?v=2&s={}&d={}&n={}&w={width}", http::url_encode(secret), http::url_encode(device_id), http::url_encode(name))
}

/// An error answer: `{"error": "<code>", "message": "…"}`, with `acked` on `409 events_pending`.
#[derive(Debug, Default, Deserialize)]
struct ErrorBody {
    #[serde(default)]
    error: String,
    #[serde(default)]
    message: String,
    #[serde(default)]
    acked: Option<i64>,
}

fn error_body(r: &Response) -> ErrorBody {
    serde_json::from_slice(&r.body).unwrap_or_default()
}

/// An answer the sync can't use, for the message: `what: HTTP 503 busy (An import is running)`.
fn unexpected(what: &str, r: &Response) -> SyncError {
    let e = error_body(r);
    let mut s = format!("{what}: HTTP {}", r.status);
    if !e.error.is_empty() {
        s.push_str(&format!(" {}", e.error));
    }
    if !e.message.is_empty() {
        s.push_str(&format!(" ({})", e.message));
    }
    SyncError::ServerProtocol(s)
}

#[derive(Deserialize)]
struct Acked {
    acked: i64,
}

/// A picture zip goes straight to its file.
impl Sink for File {
    fn start(&mut self, _: &Response) -> io::Result<()> {
        self.set_len(0)?;
        self.seek(SeekFrom::Start(0))?;
        Ok(())
    }
}

fn io_error(e: io::Error) -> SyncError {
    if crate::tls::is_clock_wrong(&e) {
        SyncError::Clock
    } else {
        SyncError::Server(e.to_string())
    }
}

struct Session<'a> {
    store: &'a Store,
    opts: &'a Options,
    ep: Endpoint,
    token: String,
    state: State,
    started: Instant,
    report: SyncReport,
}

impl Session<'_> {
    fn tell(&self, p: Progress) {
        if let Some(f) = &self.opts.progress {
            f(p);
        }
    }

    fn save(&self) -> Result<(), SyncError> {
        Ok(self.store.save_state(&self.state)?)
    }

    /// One request to `API + path`, with the token and, for a gated request, the
    /// `X-Events-Through` header. A `401` is an error; anything else is the caller's.
    fn send(&mut self, method: &str, path: &str, body: Option<&[u8]>, through: Option<i64>, timeout: Duration, sink: Option<&mut dyn Sink>) -> Result<Response, SyncError> {
        if self.started.elapsed() > self.opts.deadline {
            return Err(SyncError::TimedOut);
        }
        let target = self.ep.target(&format!("{API}{path}"));
        let mut headers = vec![("Authorization", format!("Bearer {}", self.token))];
        if let Some(t) = through {
            headers.push((EVENTS_THROUGH, t.to_string()));
        }
        let call = Call { method, target: &target, headers: &headers, body, content_type: "application/json" };
        let r = http::call(&self.ep, &call, timeout, sink).map_err(io_error)?;
        if let Some(date) = r.header("Date").and_then(time::parse_http_date) {
            self.state.clock_offset_ms = date + 500 - time::system_millis();
            crate::tls::set_clock_offset(self.state.clock_offset_ms);
        }
        if r.status == 401 {
            let e = error_body(&r);
            if e.error == "unknown" {
                return Err(SyncError::NotListed { waiting: !self.state.server_seen });
            }
            return Err(SyncError::ServerProtocol(format!("the token was refused ({})", if e.error.is_empty() { "401" } else { &e.error })));
        }
        // The token was taken: a success, or the gate asking for events first.
        if ((200..300).contains(&r.status) || r.status == 409) && (!self.state.server_seen || self.state.server_not_listed) {
            self.state.server_seen = true;
            self.state.server_not_listed = false;
            self.save()?;
        }
        Ok(r)
    }

    /// The `X-Events-Through` value: the highest `seq` still in `events.jsonl`, or 0.
    fn through(&self) -> Result<i64, SyncError> {
        Ok(self.store.read_events()?.iter().map(|e| e.seq).max().unwrap_or(0))
    }

    /// A gated request. On `409 events_pending` the events above the `acked` it names go
    /// again and the request is asked again, at most [`GATE_ROUNDS`] times.
    fn gated(&mut self, method: &str, path: &str, body: Option<&[u8]>, timeout: Duration, mut sink: Option<&mut dyn Sink>) -> Result<Response, SyncError> {
        let mut rounds = 0;
        loop {
            let through = self.through()?;
            let r = self.send(method, path, body, Some(through), timeout, sink.as_mut().map(|s| &mut **s as &mut dyn Sink))?;
            if r.status != 409 {
                return Ok(r);
            }
            let e = error_body(&r);
            if e.error != "events_pending" {
                return Err(unexpected(path, &r));
            }
            if rounds == GATE_ROUNDS {
                return Err(SyncError::EventsPending);
            }
            rounds += 1;
            let acked = e.acked.unwrap_or(0);
            eprintln!("server: {path} wants events through {through}, it has {acked}; sending them again");
            // The server's word on what it has merged; whatever is held above it goes.
            self.state.acked = acked;
            self.upload()?;
        }
    }

    fn run(&mut self) -> Result<(), SyncError> {
        // What a version that synced through the relay left.
        let _ = fs::remove_dir_all(self.store.old_relay_dir());
        if self.state.db_id.is_some() && self.state.db_protocol.as_deref() != Some(PROTOCOL) {
            // Stored before server sync: the IDs are the phone's, and its events must not
            // reach the server, where they would name other articles.
            eprintln!("server: the stored editions and events came from the phone; wiping before the first server sync");
            self.wipe()?;
            self.state.db_id = None;
            self.save()?;
        }
        self.upload()?;
        let manifest = self.manifest()?;
        self.tell(Progress::Connected { address: self.ep.url() });
        if let Some(&acked) = manifest.acked.get(&self.state.device_id) {
            if acked > self.state.acked {
                self.acknowledge(acked)?;
            }
        }
        let bundles = self.editions(&manifest)?;
        let saved = self.saved(&manifest)?;

        // Pictures, the newest edition's first in the order a reader meets them, then the
        // saved articles', at this Kobo's width.
        let mut urls: Vec<String> = Vec::new();
        for u in bundles.iter().flat_map(|b| b.image_urls_in_reading_order()).chain(saved.image_urls()) {
            if !urls.contains(&u) && !self.store.has_image(&u) {
                urls.push(u);
            }
        }
        if !urls.is_empty() {
            self.tell(Progress::ImagesQueued(urls.clone()));
            self.images(&urls)?;
        }

        // Commit: the editions that left the manifest and the pictures nothing refers to go.
        let keep: BTreeSet<i64> = manifest.editions.iter().map(|e| e.id).collect();
        self.report.editions_removed = self.state.editions.keys().filter_map(|k| k.parse().ok()).filter(|id| !keep.contains(id)).collect();
        self.state.editions = manifest.editions.iter().map(|e| (e.id.to_string(), e.version.clone())).collect();
        self.state.edition_order = manifest.editions.iter().map(|e| e.id).collect();
        self.state.last_sync_at = Some(time::system_millis());
        self.state.last_error = None;
        self.save()?;
        self.store.collect_garbage(&keep)?;
        Ok(())
    }

    /// Step 2: every event above `acked`, oldest first, [`BATCH`] at a time.
    fn upload(&mut self) -> Result<(), SyncError> {
        self.acknowledge(self.state.acked)?;
        loop {
            let acked = self.state.acked;
            let mut events: Vec<Event> = self.store.read_events()?.into_iter().filter(|e| e.seq > acked).collect();
            if events.is_empty() {
                return Ok(());
            }
            events.sort_by_key(|e| e.seq);
            events.truncate(BATCH);
            let last = events[events.len() - 1].seq;
            let body = serde_json::to_vec(&serde_json::json!({ "events": events })).map_err(|e| SyncError::Storage(e.to_string()))?;
            let r = self.send("POST", "/sync/events", Some(&body), None, self.opts.request_timeout, None)?;
            if r.status != 200 {
                return Err(unexpected("events", &r));
            }
            let a: Acked = serde_json::from_slice(&r.body).map_err(|e| SyncError::ServerProtocol(format!("events: {e}")))?;
            self.report.events_sent += events.len();
            self.acknowledge(a.acked)?;
            if a.acked < last {
                // Not all taken; the gate says whether that matters.
                eprintln!("server: sent events through {last}, it acknowledged {}", a.acked);
                return Ok(());
            }
        }
    }

    /// Records the server's `acked` and deletes the events at or below it. The seq counter is
    /// saved past them first, so a seq is never handed out twice.
    fn acknowledge(&mut self, acked: i64) -> Result<(), SyncError> {
        self.state.acked = acked;
        if acked > 0 {
            self.state.next_seq = self.store.next_seq(&self.state)?.max(acked + 1);
        }
        self.save()?;
        if acked > 0 {
            self.store.drop_events_through(acked)?;
        }
        Ok(())
    }

    /// Step 3.
    fn manifest(&mut self) -> Result<Manifest, SyncError> {
        let r = self.gated("GET", &format!("/sync/manifest?editions={EDITIONS}"), None, self.opts.request_timeout, None)?;
        if r.status != 200 {
            return Err(unexpected("manifest", &r));
        }
        let m: Manifest = serde_json::from_slice(&r.body).map_err(|e| SyncError::ServerProtocol(format!("manifest: {e}")))?;
        if m.protocol.as_deref() != Some(PROTOCOL) {
            return Err(SyncError::ServerProtocol(format!("manifest protocol {:?}", m.protocol)));
        }
        if m.db_id.is_empty() {
            return Err(SyncError::ServerProtocol("manifest: no dbId".into()));
        }
        self.check_db_id(&m.db_id)?;
        Ok(m)
    }

    fn wipe(&mut self) -> Result<(), SyncError> {
        self.store.wipe(&mut self.state)?;
        self.report.wiped = true;
        self.tell(Progress::Saved);
        Ok(())
    }

    /// Wipes everything when the server's database is not the one the stored data came from.
    fn check_db_id(&mut self, db_id: &str) -> Result<(), SyncError> {
        if self.state.db_id.as_deref().is_some_and(|saved| saved != db_id) {
            eprintln!("server: the server's database changed; wiping");
            self.wipe()?;
        }
        self.state.db_id = Some(db_id.to_string());
        self.state.db_protocol = Some(PROTOCOL.to_string());
        self.save()
    }

    /// Step 4: each edition saved as it arrives and listed in the state at once, so the reader
    /// can show it while the rest come. Returns every edition of the manifest.
    fn editions(&mut self, manifest: &Manifest) -> Result<Vec<Bundle>, SyncError> {
        let mut bundles: Vec<Bundle> = Vec::new();
        for e in &manifest.editions {
            let key = e.id.to_string();
            let path = self.store.edition_path(e.id);
            if self.state.editions.get(&key) == Some(&e.version) && path.is_file() {
                if let Some(b) = self.store.load_bundle(e.id) {
                    bundles.push(b);
                    continue;
                }
            }
            let r = self.gated("GET", &format!("/editions/{}/bundle", e.id), None, self.opts.request_timeout, None)?;
            if r.status != 200 {
                return Err(unexpected(&format!("edition {}", e.id), &r));
            }
            let bundle: Bundle = serde_json::from_slice(&r.body).map_err(|err| SyncError::ServerProtocol(format!("edition {}: {err}", e.id)))?;
            self.store.ensure_dirs()?;
            store::atomic_write(&path, &r.body)?;
            self.state.editions.insert(key, e.version.clone());
            // Manifest order first; editions about to leave stay at the end until the commit.
            let mut order: Vec<i64> = manifest.editions.iter().map(|m| m.id).filter(|id| self.state.editions.contains_key(&id.to_string())).collect();
            let leaving: Vec<i64> = self.state.edition_order.iter().copied().filter(|id| !order.contains(id)).collect();
            order.extend(leaving);
            self.state.edition_order = order;
            self.save()?;
            self.report.editions_fetched.push(e.id);
            self.tell(Progress::Edition(e.id));
            bundles.push(bundle);
        }
        Ok(bundles)
    }

    /// Step 5. Returns the saved shelf as it now stands.
    fn saved(&mut self, manifest: &Manifest) -> Result<SavedList, SyncError> {
        let mut saved = self.store.load_saved();
        match &manifest.saved {
            Some(info) if self.state.saved_version.as_deref() != Some(info.version.as_str()) || !self.store.saved_path().is_file() => {
                let r = self.gated("GET", "/saved", None, self.opts.request_timeout, None)?;
                if r.status != 200 {
                    return Err(unexpected("saved", &r));
                }
                saved = serde_json::from_slice(&r.body).map_err(|err| SyncError::ServerProtocol(format!("saved: {err}")))?;
                store::atomic_write(&self.store.saved_path(), &r.body)?;
                self.state.saved_version = Some(info.version.clone());
                self.save()?;
                self.report.saved_fetched = true;
                self.tell(Progress::Saved);
            }
            Some(_) => {}
            None => {
                // No saved shelf on offer: nothing is saved here either.
                if self.store.saved_path().exists() {
                    fs::remove_file(self.store.saved_path())?;
                    saved = SavedList::default();
                    self.tell(Progress::Saved);
                }
                self.state.saved_version = None;
            }
        }
        Ok(saved)
    }

    /// Step 6: the pictures in `urls`, [`IMAGE_CHUNK`] per zip.
    fn images(&mut self, urls: &[String]) -> Result<(), SyncError> {
        let width = self.opts.image_width;
        let dir = self.store.incoming_dir();
        fs::create_dir_all(&dir)?;
        let path = dir.join("images.zip");
        for chunk in urls.chunks(IMAGE_CHUNK) {
            let body = serde_json::to_vec(&serde_json::json!({ "width": width, "urls": chunk })).map_err(|e| SyncError::Storage(e.to_string()))?;
            let mut file = File::options().create(true).truncate(true).read(true).write(true).open(&path)?;
            let r = self.gated("POST", "/kobo/images", Some(&body), self.opts.image_timeout, Some(&mut file))?;
            drop(file);
            if r.status != 200 {
                let _ = fs::remove_file(&path);
                return Err(unexpected("images", &r));
            }
            let mut zip = Zip::open(&path).map_err(|e| SyncError::ServerProtocol(format!("images: {e}")))?;
            let index: Vec<ImageEntry> = match zip.read("images.json", 16 << 20) {
                Ok(Some(b)) => serde_json::from_slice(&b).map_err(|e| SyncError::ServerProtocol(format!("images.json: {e}")))?,
                Ok(None) => Vec::new(),
                Err(e) => return Err(SyncError::ServerProtocol(format!("images: {e}"))),
            };
            for url in chunk {
                let picked = pick_image(&index, url, width).filter(|e| zip.contains(&e.file)).cloned();
                let bytes = match &picked {
                    Some(e) => zip.read(&e.file, 32 << 20).ok().flatten(),
                    None => None,
                };
                let (Some(e), Some(bytes)) = (picked, bytes) else {
                    self.report.images_missing += 1;
                    self.tell(Progress::ImageMissing(url.clone()));
                    continue;
                };
                let bytes = if e.width > width { scale_down(bytes, &e.kind, width) } else { bytes };
                store::atomic_write(&self.store.image_path(url), &bytes)?;
                self.report.images_fetched += 1;
                self.tell(Progress::Image(url.clone()));
            }
        }
        let _ = fs::remove_file(&path);
        Ok(())
    }
}

fn session<'a>(store: &'a Store, opts: &'a Options) -> Result<Session<'a>, SyncError> {
    store.ensure_dirs()?;
    let state = store.load_state()?;
    let secret = state.secret.as_deref().and_then(crypto::b64url_decode).filter(|s| s.len() == 32).ok_or(SyncError::NotPaired)?;
    let address = opts.server.as_deref().ok_or(SyncError::NoServer)?;
    let ep = Endpoint::parse(address).map_err(SyncError::ServerAddress)?;
    crate::tls::set_clock_offset(state.clock_offset_ms);
    let report = SyncReport { address: ep.url(), ..Default::default() };
    Ok(Session { store, opts, ep, token: crypto::token(&secret), state, started: Instant::now(), report })
}

/// Runs a sync with the server. The outcome is saved in the state for the home screen and
/// Settings.
pub fn sync(store: &Store, opts: &Options) -> Result<SyncReport, SyncError> {
    let mut s = match session(store, opts) {
        Ok(s) => s,
        Err(e) => {
            let mut state = store.load_state()?;
            state.last_error = Some(e.to_string());
            store.save_state(&state)?;
            return Err(e);
        }
    };
    let result = s.run();
    let mut state = s.state;
    if let Err(e) = &result {
        if let SyncError::NotListed { .. } = e {
            state.server_not_listed = true;
        }
        state.last_error = Some(e.to_string());
    }
    store.save_state(&state)?;
    result.map(|()| s.report)
}

/// Whether the server knows this Kobo yet, for the pairing screen: one
/// `GET /api/v1/kobo/hello`, answered `401 unknown` until the phone has registered the pairing
/// code. Nothing is uploaded or installed, and the last sync's result is left alone; what the
/// server said is saved in the state as a sync saves it.
pub fn hello(store: &Store, opts: &Options) -> Result<bool, SyncError> {
    let mut s = session(store, opts)?;
    let timeout = opts.request_timeout;
    let result = match s.send("GET", "/kobo/hello", None, None, timeout, None) {
        Ok(r) if r.status == 200 => Ok(true),
        Ok(r) => Err(unexpected("hello", &r)),
        Err(SyncError::NotListed { .. }) => {
            s.state.server_not_listed = true;
            Ok(false)
        }
        Err(e) => Err(e),
    };
    store.save_state(&s.state)?;
    result
}

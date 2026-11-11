//! `morningissue sync`: the order of a sync in `docs/kobo-sync.md`.
//!
//! 1. Find the phone: its beacon, then the last address that worked, then `config.json`.
//! 2. Pair if there is no token, and again on any `401`.
//! 3. Check the phone's `dbId` with `GET /v1/manifest` (wiping on a change), then
//!    `POST /v1/events` with everything unsent.
//! 4. `GET /v1/manifest` again, now that the phone has merged the events.
//! 5. Fetch each edition whose `version` differs, then images not yet stored.
//! 6. Rename the new editions into place, save the state, then delete editions that left the
//!    manifest and images nothing refers to.
//!
//! Nothing a reader would see changes until step 6, and every file write is atomic, so an
//! interrupted sync leaves the last good state.

use crate::config::Config;
use crate::http::{self, Response};
use crate::model::{Beacon, Bundle, EventsResponse, Manifest, PairResponse};
use crate::store::{self, Event, State, Store};
use crate::time;
use std::collections::BTreeSet;
use std::fmt;
use std::io;
use std::net::UdpSocket;
use std::path::PathBuf;
use std::time::{Duration, Instant};

#[derive(Debug)]
pub enum SyncError {
    NoPhone,
    Denied,
    PairTimeout,
    Unauthorized,
    Network(String),
    Protocol(String),
    Storage(String),
    TimedOut,
}

impl fmt::Display for SyncError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SyncError::NoPhone => write!(f, "Phone not found. Open Sync with Kobo on the phone, on the same Wi-Fi."),
            SyncError::Denied => write!(f, "The phone declined to pair with this Kobo."),
            SyncError::PairTimeout => write!(f, "Pairing was not answered on the phone in time."),
            SyncError::Unauthorized => write!(f, "The phone did not accept this Kobo's pairing."),
            SyncError::Network(e) => write!(f, "The connection to the phone failed ({e})."),
            SyncError::Protocol(e) => write!(f, "The phone sent something unexpected ({e})."),
            SyncError::Storage(e) => write!(f, "Could not save to the Kobo ({e})."),
            SyncError::TimedOut => write!(f, "Sync took too long and stopped."),
        }
    }
}

impl From<io::Error> for SyncError {
    fn from(e: io::Error) -> SyncError {
        SyncError::Storage(e.to_string())
    }
}

#[derive(Debug, Default, PartialEq)]
pub struct SyncReport {
    pub address: String,
    pub paired: bool,
    pub wiped: bool,
    pub events_sent: usize,
    pub editions_fetched: Vec<i64>,
    pub editions_removed: Vec<i64>,
    pub images_fetched: usize,
    pub images_missing: usize,
}

pub struct Options {
    pub beacon_wait: Duration,
    pub beacon_port: u16,
    /// How long to wait for any one response; pairing waits for the person at the phone.
    pub request_timeout: Duration,
    pub pair_timeout: Duration,
    /// The whole sync gives up after this.
    pub deadline: Duration,
    pub configured_phone: Option<String>,
    pub device_name: String,
}

impl Options {
    pub fn from_config(c: &Config) -> Options {
        Options {
            beacon_wait: Duration::from_secs(c.beacon_seconds),
            beacon_port: c.beacon_port,
            request_timeout: Duration::from_secs(30),
            pair_timeout: Duration::from_secs(75),
            deadline: Duration::from_secs(300),
            configured_phone: c.phone.clone(),
            device_name: c.device_name(),
        }
    }
}

/// Listens for the phone's UDP beacon; returns `ip:port` of the first valid one.
pub fn listen_for_beacon(port: u16, wait: Duration) -> Option<(String, Beacon)> {
    if wait.is_zero() {
        return None;
    }
    let socket = UdpSocket::bind(("0.0.0.0", port)).ok()?;
    let until = Instant::now() + wait;
    let mut buf = [0u8; 2048];
    loop {
        let left = until.checked_duration_since(Instant::now())?;
        if left.is_zero() {
            return None;
        }
        socket.set_read_timeout(Some(left.min(Duration::from_secs(1)))).ok()?;
        match socket.recv_from(&mut buf) {
            Ok((n, from)) => {
                if let Ok(b) = serde_json::from_slice::<Beacon>(&buf[..n]) {
                    if b.app == "morningissue" && b.v == 1 {
                        let port = if b.port == 0 { 47824 } else { b.port };
                        return Some((format!("{}:{}", from.ip(), port), b));
                    }
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {}
            Err(_) => return None,
        }
    }
}

/// Whether an error means "nobody is listening at this address" rather than a failure mid-sync.
fn unreachable(e: &io::Error) -> bool {
    matches!(
        e.kind(),
        io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock | io::ErrorKind::HostUnreachable | io::ErrorKind::NetworkUnreachable | io::ErrorKind::AddrNotAvailable | io::ErrorKind::InvalidInput
    )
}

struct Session<'a> {
    store: &'a Store,
    state: State,
    address: String,
    opts: &'a Options,
    started: Instant,
    report: SyncReport,
    /// True until the first response arrives from this address.
    first_contact: bool,
}

enum CallError {
    Unreachable(io::Error),
    Failed(SyncError),
}

impl From<SyncError> for CallError {
    fn from(e: SyncError) -> CallError {
        CallError::Failed(e)
    }
}

impl<'a> Session<'a> {
    fn check_deadline(&self) -> Result<(), SyncError> {
        if self.started.elapsed() > self.opts.deadline {
            Err(SyncError::TimedOut)
        } else {
            Ok(())
        }
    }

    fn raw(&mut self, method: &str, path: &str, body: Option<&[u8]>, timeout: Duration) -> Result<Response, CallError> {
        self.check_deadline()?;
        let token = self.state.token.clone();
        let auth = if path == "/v1/pair" { None } else { token.as_deref() };
        match http::request(&self.address, method, path, auth, body, timeout) {
            Ok(r) => {
                self.first_contact = false;
                if let Some(date) = r.header("Date").and_then(time::parse_http_date) {
                    // Half-second midpoint of the header's whole seconds.
                    self.state.clock_offset_ms = date + 500 - time::system_millis();
                }
                Ok(r)
            }
            Err(e) if self.first_contact && unreachable(&e) => Err(CallError::Unreachable(e)),
            Err(e) => Err(CallError::Failed(SyncError::Network(e.to_string()))),
        }
    }

    fn pair(&mut self) -> Result<(), CallError> {
        let body = serde_json::json!({"deviceId": self.state.device_id, "name": self.opts.device_name});
        let r = self.raw("POST", "/v1/pair", Some(body.to_string().as_bytes()), self.opts.pair_timeout)?;
        match r.status {
            200 => {
                let p: PairResponse = serde_json::from_slice(&r.body).map_err(|e| SyncError::Protocol(format!("pair: {e}")))?;
                if p.token.is_empty() {
                    return Err(SyncError::Protocol("pair: no token".into()).into());
                }
                self.state.token = Some(p.token);
                self.report.paired = true;
                if !p.db_id.is_empty() {
                    self.check_db_id(&p.db_id)?;
                }
                self.store.save_state(&self.state).map_err(SyncError::from)?;
                Ok(())
            }
            403 => Err(SyncError::Denied.into()),
            408 => Err(SyncError::PairTimeout.into()),
            s => Err(SyncError::Protocol(format!("pair: HTTP {s}")).into()),
        }
    }

    /// A request that pairs again and retries once on `401`.
    fn call(&mut self, method: &str, path: &str, body: Option<&[u8]>) -> Result<Response, CallError> {
        if self.state.token.is_none() {
            self.pair()?;
        }
        let r = self.raw(method, path, body, self.opts.request_timeout)?;
        if r.status != 401 {
            return Ok(r);
        }
        self.state.token = None;
        self.pair()?;
        let r = self.raw(method, path, body, self.opts.request_timeout)?;
        if r.status == 401 {
            return Err(SyncError::Unauthorized.into());
        }
        Ok(r)
    }

    /// Wipes everything when the phone's database is not the one the stored data came from.
    fn check_db_id(&mut self, db_id: &str) -> Result<(), SyncError> {
        match &self.state.db_id {
            Some(saved) if saved != db_id => {
                self.store.wipe(&mut self.state)?;
                self.report.wiped = true;
            }
            _ => {}
        }
        self.state.db_id = Some(db_id.to_string());
        Ok(())
    }

    fn manifest(&mut self) -> Result<Manifest, CallError> {
        let r = self.call("GET", "/v1/manifest", None)?;
        if r.status != 200 {
            return Err(SyncError::Protocol(format!("manifest: HTTP {}", r.status)).into());
        }
        let m: Manifest = serde_json::from_slice(&r.body).map_err(|e| SyncError::Protocol(format!("manifest: {e}")))?;
        if m.db_id.is_empty() {
            return Err(SyncError::Protocol("manifest: no dbId".into()).into());
        }
        self.check_db_id(&m.db_id)?;
        Ok(m)
    }

    /// Drops events the phone already has, then sends the rest in batches.
    fn send_events(&mut self, acked: i64) -> Result<(), CallError> {
        self.acknowledge(acked)?;
        loop {
            let pending: Vec<Event> = self.store.read_events().map_err(SyncError::from)?;
            if pending.is_empty() {
                return Ok(());
            }
            let batch: Vec<&Event> = pending.iter().take(500).collect();
            let body = serde_json::to_vec(&serde_json::json!({ "events": batch })).map_err(|e| SyncError::Protocol(e.to_string()))?;
            let r = self.call("POST", "/v1/events", Some(&body))?;
            if r.status != 200 {
                return Err(SyncError::Protocol(format!("events: HTTP {}", r.status)).into());
            }
            let resp: EventsResponse = serde_json::from_slice(&r.body).map_err(|e| SyncError::Protocol(format!("events: {e}")))?;
            let last = batch.last().map(|e| e.seq).unwrap_or(0);
            if resp.acked_seq < last {
                return Err(SyncError::Protocol(format!("events: acked {} of {}", resp.acked_seq, last)).into());
            }
            self.report.events_sent += batch.len();
            self.acknowledge(resp.acked_seq)?;
        }
    }

    /// Saves the seq counter past `acked` before deleting the acknowledged events, so a seq
    /// is never handed out twice.
    fn acknowledge(&mut self, acked: i64) -> Result<(), SyncError> {
        if acked <= 0 {
            return Ok(());
        }
        self.state.next_seq = self.store.next_seq(&self.state)?.max(acked + 1);
        self.store.save_state(&self.state)?;
        self.store.drop_events_through(acked)?;
        Ok(())
    }

    fn run(&mut self) -> Result<(), CallError> {
        // Step 3: the dbId check comes before sending events, so events from a previous phone
        // install are dropped, not attached to whatever now has those row IDs.
        let first = self.manifest()?;
        let had_events = !self.store.read_events().map_err(SyncError::from)?.is_empty();
        self.send_events(first.acked_seq)?;
        // Step 4.
        let manifest = if had_events { self.manifest()? } else { first };

        // Step 5: editions into temporary files.
        let mut staged: Vec<(i64, PathBuf, PathBuf)> = Vec::new();
        let mut bundles: Vec<Bundle> = Vec::new();
        for e in &manifest.editions {
            let key = e.id.to_string();
            let path = self.store.edition_path(e.id);
            let current = self.state.editions.get(&key) == Some(&e.version) && path.is_file();
            if current {
                if let Some(b) = self.store.load_bundle(e.id) {
                    bundles.push(b);
                    continue;
                }
            }
            let r = self.call("GET", &format!("/v1/editions/{}", e.id), None)?;
            if r.status != 200 {
                return Err(SyncError::Protocol(format!("edition {}: HTTP {}", e.id, r.status)).into());
            }
            let bundle: Bundle = serde_json::from_slice(&r.body).map_err(|err| SyncError::Protocol(format!("edition {}: {err}", e.id)))?;
            let tmp = store::temp_path(&path);
            self.store.ensure_dirs().map_err(SyncError::from)?;
            store::write_synced(&tmp, &r.body).map_err(SyncError::from)?;
            staged.push((e.id, tmp, path));
            self.report.editions_fetched.push(e.id);
            bundles.push(bundle);
        }
        // Images, each written atomically under its final name. One that is not stored on the
        // phone (404) is skipped; the reader leaves it out.
        for url in bundles.iter().flat_map(|b| b.image_urls()).collect::<BTreeSet<_>>() {
            if self.store.has_image(&url) {
                continue;
            }
            let r = self.call("GET", &format!("/v1/images?url={}", http::url_encode(&url)), None)?;
            match r.status {
                200 if !r.body.is_empty() => {
                    store::atomic_write(&self.store.image_path(&url), &r.body).map_err(SyncError::from)?;
                    self.report.images_fetched += 1;
                }
                _ => self.report.images_missing += 1,
            }
        }

        // Step 6: commit.
        for (_, tmp, path) in &staged {
            store::commit(tmp, path).map_err(SyncError::from)?;
        }
        let keep: BTreeSet<i64> = manifest.editions.iter().map(|e| e.id).collect();
        self.report.editions_removed = self.state.editions.keys().filter_map(|k| k.parse().ok()).filter(|id| !keep.contains(id)).collect();
        self.state.editions = manifest.editions.iter().map(|e| (e.id.to_string(), e.version.clone())).collect();
        self.state.edition_order = manifest.editions.iter().map(|e| e.id).collect();
        self.state.last_address = Some(self.address.clone());
        self.state.last_sync_at = Some(time::system_millis());
        self.state.last_error = None;
        self.store.save_state(&self.state).map_err(SyncError::from)?;
        self.store.collect_garbage(&keep).map_err(SyncError::from)?;
        Ok(())
    }
}

/// Runs a whole sync. The outcome (time or error) is saved in the state for the newsstand.
pub fn sync(store: &Store, opts: &Options) -> Result<SyncReport, SyncError> {
    store.ensure_dirs()?;
    let started = Instant::now();
    let mut state = store.load_state()?;

    let mut candidates: Vec<String> = Vec::new();
    if let Some((addr, beacon)) = listen_for_beacon(opts.beacon_port, opts.beacon_wait) {
        if !beacon.name.is_empty() {
            state.phone_name = Some(beacon.name.clone());
        }
        candidates.push(addr);
    }
    for a in [state.last_address.clone(), opts.configured_phone.clone()].into_iter().flatten() {
        if !candidates.contains(&a) {
            candidates.push(a);
        }
    }

    let mut result = Err(SyncError::NoPhone);
    for address in candidates {
        let mut s = Session { store, state: state.clone(), address: address.clone(), opts, started, report: SyncReport { address, ..Default::default() }, first_contact: true };
        match s.run() {
            Ok(()) => {
                result = Ok(s.report);
                state = s.state;
                break;
            }
            Err(CallError::Unreachable(e)) => {
                eprintln!("sync: nobody at {}: {e}", s.address);
                continue;
            }
            Err(CallError::Failed(e)) => {
                // Keep what this attempt learnt that stays true: a new token, a wipe.
                state = s.state;
                result = Err(e);
                break;
            }
        }
    }
    if let Err(e) = &result {
        // Reload so a half-finished attempt never saves its in-memory edition list.
        let mut saved = store.load_state().unwrap_or(state.clone());
        saved.token = state.token.clone();
        saved.db_id = state.db_id.clone();
        saved.clock_offset_ms = state.clock_offset_ms;
        saved.last_error = Some(e.to_string());
        store.save_state(&saved)?;
    }
    result
}

//! A sync with the phone, in the order of `docs/kobo-sync.md`.
//!
//! 1. Find the phone: the address typed in Settings, then its beacon, then the last address
//!    that worked, then `config.json`.
//! 2. Pair if there is no token, and again on any `401`.
//! 3. Check the phone's `dbId` with `GET /v1/manifest` (wiping on a change), then
//!    `POST /v1/events` with everything unsent.
//! 4. `GET /v1/manifest` again, now that the phone has merged the events.
//! 5. Fetch each edition whose `version` differs and the saved list if its version differs,
//!    saving each as it arrives (the reader shows it at once), then the pictures not yet
//!    stored, three at a time.
//! 6. Save the state, then delete editions that left the manifest and pictures nothing
//!    refers to.
//!
//! Every file write is atomic, so an interrupted sync leaves whole files: the editions that
//! arrived, and the earlier ones for the rest.

use crate::config::Config;
use crate::http::{self, Response};
use crate::model::{Beacon, Bundle, EventsResponse, Manifest, PairResponse, SavedList};
use crate::store::{self, Event, State, Store};
use crate::time;
use std::collections::{BTreeSet, VecDeque};
use std::fmt;
use std::io;
use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
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
    /// The relay address in Settings or `config.json` cannot be used.
    RelayAddress(String),
    /// The relay answered `401 unknown`: the phone has not listed this Kobo yet (`waiting`,
    /// it never has) or no longer does.
    RelayUnpaired { waiting: bool },
    /// The relay could not be reached, or the connection broke.
    Relay(String),
    /// The relay answered something unexpected.
    RelayProtocol(String),
    /// The relay's certificate was not valid at the time the Kobo believes.
    Clock,
    /// The package or its key failed to decrypt or made no sense; nothing was installed.
    Integrity(String),
}

impl fmt::Display for SyncError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SyncError::NoPhone => write!(f, "Phone not found. Open Sync with Kobo on the phone, on the same Wi-Fi."),
            SyncError::Denied => write!(f, "The phone declined to pair with this Kobo."),
            SyncError::PairTimeout => write!(f, "Pairing was not answered on the phone in time."),
            SyncError::Unauthorized => write!(f, "The phone did not accept this Kobo's pairing."),
            SyncError::Network(e) if e.contains("refused") || e.contains("reset") || e.contains("closed") || e.contains("Broken pipe") => {
                write!(f, "The phone stopped answering. Keep Sync with Kobo open on the phone.")
            }
            SyncError::Network(e) if e.contains("timed out") || e.contains("temporarily unavailable") => write!(f, "The phone took too long to answer."),
            SyncError::Network(_) => write!(f, "The connection to the phone failed."),
            SyncError::Protocol(e) => write!(f, "The phone sent something unexpected ({e})."),
            SyncError::Storage(e) => write!(f, "Could not save to the Kobo ({e})."),
            SyncError::TimedOut => write!(f, "Sync took too long and stopped."),
            SyncError::RelayAddress(e) => write!(f, "The relay address can\u{2019}t be used: {e}"),
            SyncError::RelayUnpaired { waiting: true } => write!(f, "Waiting for the phone: scan the code in Settings, Pair through relay, with Morning Issue on the phone."),
            SyncError::RelayUnpaired { waiting: false } => write!(f, "Not paired: the phone no longer accepts this Kobo. Pair through relay again in Settings."),
            SyncError::Relay(e) if e.contains("timed out") || e.contains("temporarily unavailable") => write!(f, "The relay took too long to answer."),
            SyncError::Relay(_) => write!(f, "The relay could not be reached."),
            SyncError::RelayProtocol(e) => write!(f, "The relay sent something unexpected ({e})."),
            SyncError::Clock => write!(f, "The Kobo\u{2019}s clock is wrong, so the relay\u{2019}s certificate can\u{2019}t be checked. Sync once with the phone on the same Wi-Fi to set it."),
            SyncError::Integrity(e) => write!(f, "The relay\u{2019}s package failed its check and was not installed ({e})."),
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
    pub saved_fetched: bool,
    pub images_fetched: usize,
    pub images_missing: usize,
    /// Through the relay rather than with the phone on the LAN.
    pub via_relay: bool,
}

/// What a running sync has got to, so the reader can show what arrived.
#[derive(Clone, Debug, PartialEq)]
pub enum Progress {
    /// Talking to the phone at this address.
    Connected { address: String },
    /// An edition was saved; the reader can show it.
    Edition(i64),
    /// The saved list changed on the Kobo.
    Saved,
    /// These pictures are about to be fetched.
    ImagesQueued(Vec<String>),
    /// A picture was stored.
    Image(String),
    /// The phone does not have this picture (404).
    ImageMissing(String),
}

pub type ProgressFn = Arc<dyn Fn(Progress) + Send + Sync>;

pub struct Options {
    pub beacon_wait: Duration,
    pub beacon_port: u16,
    /// How long to wait for any one response; pairing waits for the person at the phone.
    pub request_timeout: Duration,
    /// How long to wait for a picture: the phone may have to download it first (up to 30 s).
    pub image_timeout: Duration,
    pub pair_timeout: Duration,
    /// The whole sync gives up after this.
    pub deadline: Duration,
    pub configured_phone: Option<String>,
    /// Typed in Settings; tried before the beacon is listened for.
    pub manual_phone: Option<String>,
    pub device_name: String,
    /// Sent as `w` with every picture request: the screen's short side in pixels.
    pub image_width: u32,
    /// Picture requests in flight at once.
    pub image_workers: usize,
    /// Told as each part arrives, on the sync's own threads.
    pub progress: Option<ProgressFn>,
    /// The relay's address (`http[s]://host[:port][/prefix]`): typed in Settings, else
    /// `relay` in `config.json`. Used once this Kobo has a relay secret.
    pub relay: Option<String>,
}

impl Options {
    pub fn from_config(c: &Config) -> Options {
        Options {
            beacon_wait: Duration::from_secs(c.beacon_seconds),
            beacon_port: c.beacon_port,
            request_timeout: Duration::from_secs(30),
            image_timeout: Duration::from_secs(60),
            pair_timeout: Duration::from_secs(75),
            deadline: Duration::from_secs(600),
            configured_phone: c.phone.clone(),
            manual_phone: None,
            device_name: c.device_name(),
            image_width: 1264,
            image_workers: 3,
            progress: None,
            relay: c.relay.clone(),
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
    fn tell(&self, p: Progress) {
        if let Some(f) = &self.opts.progress {
            f(p);
        }
    }

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
                    crate::tls::set_clock_offset(self.state.clock_offset_ms);
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
                self.tell(Progress::Saved);
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
        self.tell(Progress::Connected { address: self.address.clone() });
        let had_events = !self.store.read_events().map_err(SyncError::from)?.is_empty();
        self.send_events(first.acked_seq)?;
        // Step 4.
        let manifest = if had_events { self.manifest()? } else { first };

        // Step 5: each edition is saved as it arrives and listed in the state at once, so the
        // reader can show it while the rest come.
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
            self.store.ensure_dirs().map_err(SyncError::from)?;
            store::atomic_write(&path, &r.body).map_err(SyncError::from)?;
            self.state.editions.insert(key, e.version.clone());
            // Manifest order first; editions about to leave stay at the end until step 6.
            let mut order: Vec<i64> = manifest.editions.iter().map(|m| m.id).filter(|id| self.state.editions.contains_key(&id.to_string())).collect();
            let leaving: Vec<i64> = self.state.edition_order.iter().copied().filter(|id| !order.contains(id)).collect();
            order.extend(leaving);
            self.state.edition_order = order;
            self.store.save_state(&self.state).map_err(SyncError::from)?;
            self.report.editions_fetched.push(e.id);
            self.tell(Progress::Edition(e.id));
            bundles.push(bundle);
        }
        let mut saved = self.store.load_saved();
        match &manifest.saved {
            Some(info) if self.state.saved_version.as_deref() != Some(info.version.as_str()) || !self.store.saved_path().is_file() => {
                let r = self.call("GET", "/v1/saved", None)?;
                if r.status != 200 {
                    return Err(SyncError::Protocol(format!("saved: HTTP {}", r.status)).into());
                }
                saved = serde_json::from_slice(&r.body).map_err(|err| SyncError::Protocol(format!("saved: {err}")))?;
                store::atomic_write(&self.store.saved_path(), &r.body).map_err(SyncError::from)?;
                self.state.saved_version = Some(info.version.clone());
                self.store.save_state(&self.state).map_err(SyncError::from)?;
                self.report.saved_fetched = true;
                self.tell(Progress::Saved);
            }
            Some(_) => {}
            None => {
                // A phone with no saved shelf to offer: nothing is saved here either.
                if self.store.saved_path().exists() {
                    std::fs::remove_file(self.store.saved_path()).map_err(SyncError::from)?;
                    saved = SavedList::default();
                    self.tell(Progress::Saved);
                }
                self.state.saved_version = None;
            }
        }

        // Pictures: the newest edition first, in the order a reader meets them, then the
        // saved articles.
        let mut urls: Vec<String> = Vec::new();
        for u in bundles.iter().flat_map(|b| b.image_urls_in_reading_order()).chain(saved.image_urls()) {
            if !urls.contains(&u) && !self.store.has_image(&u) {
                urls.push(u);
            }
        }
        self.fetch_images(urls)?;

        // Step 6: commit.
        let keep: BTreeSet<i64> = manifest.editions.iter().map(|e| e.id).collect();
        self.report.editions_removed = self.state.editions.keys().filter_map(|k| k.parse().ok()).filter(|id| !keep.contains(id)).collect();
        self.state.editions = manifest.editions.iter().map(|e| (e.id.to_string(), e.version.clone())).collect();
        self.state.edition_order = manifest.editions.iter().map(|e| e.id).collect();
        self.state.last_address = Some(self.address.clone());
        self.state.last_sync_at = Some(time::system_millis());
        self.state.last_error = None;
        self.state.last_via_relay = false;
        self.store.save_state(&self.state).map_err(SyncError::from)?;
        self.store.collect_garbage(&keep).map_err(SyncError::from)?;
        Ok(())
    }

    /// Fetches pictures with up to `image_workers` requests at a time. One the phone cannot
    /// get (404) is skipped and the reader leaves it out; a failed connection stops the lot.
    fn fetch_images(&mut self, urls: Vec<String>) -> Result<(), CallError> {
        if urls.is_empty() {
            return Ok(());
        }
        self.tell(Progress::ImagesQueued(urls.clone()));
        let queue = Mutex::new(urls.into_iter().collect::<VecDeque<_>>());
        let fetched = AtomicUsize::new(0);
        let missing = AtomicUsize::new(0);
        let stop = AtomicBool::new(false);
        let failure: Mutex<Option<SyncError>> = Mutex::new(None);
        let token = self.state.token.clone();
        let (address, store, opts, started) = (self.address.clone(), self.store, self.opts, self.started);
        std::thread::scope(|scope| {
            for _ in 0..opts.image_workers.clamp(1, 8) {
                scope.spawn(|| loop {
                    if stop.load(Ordering::Relaxed) {
                        return;
                    }
                    let Some(url) = queue.lock().unwrap().pop_front() else { return };
                    let fail = |e: SyncError| {
                        stop.store(true, Ordering::Relaxed);
                        failure.lock().unwrap().get_or_insert(e);
                    };
                    if started.elapsed() > opts.deadline {
                        return fail(SyncError::TimedOut);
                    }
                    let path = format!("/v1/images?url={}&w={}", http::url_encode(&url), opts.image_width);
                    match http::request(&address, "GET", &path, token.as_deref(), None, opts.image_timeout) {
                        Ok(r) if r.status == 200 && !r.body.is_empty() => {
                            if let Err(e) = store::atomic_write(&store.image_path(&url), &r.body) {
                                return fail(e.into());
                            }
                            fetched.fetch_add(1, Ordering::Relaxed);
                            if let Some(f) = &opts.progress {
                                f(Progress::Image(url));
                            }
                        }
                        Ok(r) if r.status == 401 => return fail(SyncError::Unauthorized),
                        Ok(_) => {
                            missing.fetch_add(1, Ordering::Relaxed);
                            if let Some(f) = &opts.progress {
                                f(Progress::ImageMissing(url));
                            }
                        }
                        Err(e) => return fail(SyncError::Network(e.to_string())),
                    }
                });
            }
        });
        self.report.images_fetched += fetched.into_inner();
        self.report.images_missing += missing.into_inner();
        match failure.into_inner().unwrap() {
            // The phone forgot this Kobo mid-sync: the next sync pairs again.
            Some(SyncError::Unauthorized) => {
                self.state.token = None;
                Err(SyncError::Unauthorized.into())
            }
            Some(e) => Err(e.into()),
            None => Ok(()),
        }
    }
}

/// Runs a whole sync. The outcome (time or error) is saved in the state for the home screen.
///
/// With a relay address and a relay secret (the pairing code has been shown), the relay is
/// tried first (`docs/relay-sync.md`). If that fails for any reason, the phone is looked for
/// on this network as before; if it is not there either, the relay's error is the one kept.
/// Without both, only the LAN is used.
pub fn sync(store: &Store, opts: &Options) -> Result<SyncReport, SyncError> {
    store.ensure_dirs()?;
    let state = store.load_state()?;
    let Some(relay) = opts.relay.as_deref().filter(|_| state.relay_secret.is_some()) else {
        return sync_lan(store, opts);
    };
    let relay_error = match crate::relay::sync(store, opts, relay) {
        Ok(r) => return Ok(r),
        Err(e) => e,
    };
    eprintln!("relay sync failed: {relay_error}; looking for the phone on this network");
    match sync_lan(store, opts) {
        Ok(r) => Ok(r),
        Err(SyncError::NoPhone) => {
            let mut s = store.load_state()?;
            s.last_error = Some(relay_error.to_string());
            store.save_state(&s)?;
            Err(relay_error)
        }
        Err(e) => Err(e),
    }
}

/// A sync with the phone on the LAN (`docs/kobo-sync.md`).
fn sync_lan(store: &Store, opts: &Options) -> Result<SyncReport, SyncError> {
    store.ensure_dirs()?;
    let started = Instant::now();
    let mut state = store.load_state()?;

    // The typed address is tried before the beacon is listened for.
    let mut candidates: Vec<String> = opts.manual_phone.iter().cloned().collect();
    let mut listened = false;
    let mut result = Err(SyncError::NoPhone);
    let mut i = 0;
    loop {
        if i == candidates.len() {
            if listened {
                break;
            }
            listened = true;
            if let Some((addr, beacon)) = listen_for_beacon(opts.beacon_port, opts.beacon_wait) {
                if !beacon.name.is_empty() {
                    state.phone_name = Some(beacon.name.clone());
                }
                if !candidates.contains(&addr) {
                    candidates.push(addr);
                }
            }
            for a in [state.last_address.clone(), opts.configured_phone.clone()].into_iter().flatten() {
                if !candidates.contains(&a) {
                    candidates.push(a);
                }
            }
            continue;
        }
        let address = candidates[i].clone();
        i += 1;
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
        // Reload: the editions that arrived are already listed there, and a half-finished
        // attempt never saves its in-memory edition list.
        let mut saved = store.load_state().unwrap_or(state.clone());
        saved.token = state.token.clone();
        saved.db_id = state.db_id.clone();
        saved.clock_offset_ms = state.clock_offset_ms;
        saved.last_error = Some(e.to_string());
        store.save_state(&saved)?;
    } else {
        store.save_state(&state)?;
    }
    result
}

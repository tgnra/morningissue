//! A sync through a relay, in the device order of `docs/relay-sync.md` (protocol relay/1).
//!
//! 1. Upload the events above the highest `seq` already uploaded, oldest first, in batches of
//!    up to 500, each one changes envelope (`PUT /v1/changes/{first}-{last}`). Uploaded events
//!    stay in `events.jsonl`, and on top of what the reader shows, until a package's `acked`
//!    covers them.
//! 2. `GET /v1/package`. A package not installed yet is downloaded to `data/relay/<id>.mir`
//!    (resuming with `Range`), its key unwrapped, and decrypted as a stream to
//!    `data/relay/<id>.zip`. Then the `dbId` is checked (wiping on a change) and the editions,
//!    the saved shelf and the pictures are installed as LAN sync installs them, each file
//!    written whole and renamed into place. Then `POST …/fetched`.
//! 3. Delete the events at or below `acked[deviceId]`.
//!
//! Every request is signed with the device auth key. `ts` comes from the clock offset learnt
//! from each response's `Date`; a `401` other than `unknown` is retried once with the offset
//! that response gave. `401 unknown` means the phone does not list this Kobo.

use crate::crypto::{self, DeviceKeys, KIND_CHANGES, KIND_PACKAGE, KIND_WRAP};
use crate::http::{self, Call, Endpoint, Response, Sink};
use crate::model::{Bundle, Manifest, SavedList};
use crate::store::{self, Event, State, Store};
use crate::sync::{Options, Progress, SyncError, SyncReport};
use crate::time;
use crate::zip::Zip;
use serde::Deserialize;
use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;

/// Events in one changes batch.
pub const BATCH: usize = 500;
/// After this long without a package acknowledging them, uploaded events are uploaded again:
/// the relay deletes changes after 48 hours.
const UPLOAD_TTL_MS: i64 = 48 * 3600 * 1000;

/// The last signatures sent from this process, so none is sent twice.
static SENT: std::sync::Mutex<std::collections::VecDeque<String>> = std::sync::Mutex::new(std::collections::VecDeque::new());

#[derive(Debug, Deserialize)]
struct PackageInfo {
    id: String,
    #[serde(default)]
    size: u64,
    key: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageEntry {
    pub url: String,
    pub w: u32,
    pub file: String,
    #[serde(default, rename = "type")]
    pub kind: String,
}

/// Checks a relay address typed or configured: `http://` or `https://`, a host, an optional
/// port and path prefix. An address typed without a scheme gets `http://` when it is an IPv4
/// address and `https://` otherwise. Returns it written out in full.
pub fn parse_address(s: &str) -> Result<String, String> {
    let s = s.trim();
    if s.is_empty() {
        return Err("Type the relay address the phone\u{2019}s Kobo settings show.".into());
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

/// The pairing code's text: `morningissue:pair?v=1&s=…&d=…&n=…&w=…`.
pub fn pair_uri(secret: &str, device_id: &str, name: &str, width: u32) -> String {
    format!("morningissue:pair?v=1&s={}&d={}&n={}&w={width}", http::url_encode(secret), http::url_encode(device_id), http::url_encode(name))
}

/// A blob ID as the relay makes them: base64url, 16 to 64 characters. Checked before one is
/// used in a path or a file name.
fn valid_id(id: &str) -> bool {
    (16..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// The image to take for a device `width` wide: its own width, else the nearest larger one
/// (scaled down), else the largest there is.
pub fn pick_image<'a>(entries: &'a [ImageEntry], url: &str, width: u32) -> Option<&'a ImageEntry> {
    let of: Vec<&ImageEntry> = entries.iter().filter(|e| e.url == url).collect();
    of.iter().find(|e| e.w == width).or_else(|| of.iter().filter(|e| e.w > width).min_by_key(|e| e.w)).or_else(|| of.iter().max_by_key(|e| e.w)).copied()
}

/// A picture prepared for a wider screen, scaled down to `width` (never up) and encoded
/// again as the same type. One that does not decode is kept as it is.
pub fn scale_down(bytes: Vec<u8>, kind: &str, width: u32) -> Vec<u8> {
    let Ok(img) = image::load_from_memory(&bytes) else { return bytes };
    if img.width() <= width || width == 0 {
        return bytes;
    }
    let h = ((img.height() as u64 * width as u64) / img.width() as u64).max(1) as u32;
    let small = img.resize_exact(width, h, image::imageops::FilterType::CatmullRom).to_luma8();
    let mut out = Vec::new();
    let ok = if kind.contains("png") {
        small.write_to(&mut io::Cursor::new(&mut out), image::ImageFormat::Png).is_ok()
    } else {
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 85).encode_image(&small).is_ok()
    };
    if ok {
        out
    } else {
        bytes
    }
}

/// Writes a download into its partial file: a `206` continues it, a `200` starts it again.
struct Download {
    file: File,
    have: u64,
}

impl Write for Download {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.file.write(buf)?;
        self.have += n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }
}

impl Sink for Download {
    fn start(&mut self, resp: &Response) -> io::Result<()> {
        if resp.status == 206 {
            let from = resp.header("Content-Range").and_then(|v| v.trim().strip_prefix("bytes ")).and_then(|v| v.split('-').next()).and_then(|v| v.trim().parse::<u64>().ok());
            if from != Some(self.have) {
                return Err(io::Error::new(io::ErrorKind::InvalidData, format!("range starts at {from:?}, not {}", self.have)));
            }
        } else {
            self.file.set_len(0)?;
            self.have = 0;
        }
        self.file.seek(SeekFrom::Start(self.have))?;
        Ok(())
    }
}

enum Fetched {
    Ready(PathBuf),
    /// The package was replaced or deleted meanwhile.
    Gone,
}

struct Session<'a> {
    store: &'a Store,
    opts: &'a Options,
    ep: Endpoint,
    keys: DeviceKeys,
    state: State,
    started: Instant,
    report: SyncReport,
}

fn io_error(e: io::Error) -> SyncError {
    if crate::tls::is_clock_wrong(&e) {
        SyncError::Clock
    } else {
        SyncError::Relay(e.to_string())
    }
}

impl<'a> Session<'a> {
    fn tell(&self, p: Progress) {
        if let Some(f) = &self.opts.progress {
            f(p);
        }
    }

    fn save(&self) -> Result<(), SyncError> {
        Ok(self.store.save_state(&self.state)?)
    }

    fn now_ms(&self) -> i64 {
        time::system_millis() + self.state.clock_offset_ms
    }

    /// An `Authorization` value never sent before. The relay refuses a signature it saw in
    /// the last 600 s (`replay`), and the same request again within a second (a retry, or the
    /// next sync's `GET /v1/package`) would repeat one; then `ts` moves on a second, well
    /// inside the relay's 300 s allowance.
    fn fresh_authorization(&self, method: &str, target: &str, body: &[u8]) -> String {
        let mut sent = SENT.lock().unwrap_or_else(|e| e.into_inner());
        let mut ts = self.now_ms().div_euclid(1000);
        loop {
            let auth = crypto::authorization(&self.keys.kid, &self.keys.auth, method, target, ts, body);
            if !sent.contains(&auth) {
                if sent.len() >= 256 {
                    sent.pop_front();
                }
                sent.push_back(auth.clone());
                return auth;
            }
            ts += 1;
        }
    }

    /// A signed request; retried once after a `401` other than `unknown`, with the clock
    /// offset that answer's `Date` gave.
    fn send(&mut self, method: &str, path: &str, body: Option<&[u8]>, extra: &[(&str, String)], mut sink: Option<&mut dyn Sink>) -> Result<Response, SyncError> {
        let target = self.ep.target(path);
        for attempt in 0..2 {
            if self.started.elapsed() > self.opts.deadline {
                return Err(SyncError::TimedOut);
            }
            let auth = self.fresh_authorization(method, &target, body.unwrap_or(&[]));
            let mut headers = vec![("Authorization", auth)];
            headers.extend(extra.iter().cloned());
            let call = Call { method, target: &target, headers: &headers, body, content_type: "application/octet-stream" };
            let r = http::call(&self.ep, &call, self.opts.request_timeout, sink.as_mut().map(|s| &mut **s as &mut dyn Sink)).map_err(io_error)?;
            if let Some(date) = r.header("Date").and_then(time::parse_http_date) {
                self.state.clock_offset_ms = date + 500 - time::system_millis();
                crate::tls::set_clock_offset(self.state.clock_offset_ms);
            }
            if r.status != 401 {
                if !self.state.relay_seen || self.state.relay_unpaired {
                    self.state.relay_seen = true;
                    self.state.relay_unpaired = false;
                    self.save()?;
                }
                return Ok(r);
            }
            let code = serde_json::from_slice::<serde_json::Value>(&r.body).ok().and_then(|v| v["error"].as_str().map(str::to_owned)).unwrap_or_default();
            if code == "unknown" {
                return Err(SyncError::RelayUnpaired { waiting: !self.state.relay_seen });
            }
            eprintln!("relay: 401 {code} for {method} {path}; clock offset now {} ms", self.state.clock_offset_ms);
            if attempt == 1 {
                return Err(SyncError::RelayProtocol(format!("signature refused: {code}")));
            }
        }
        unreachable!()
    }

    fn run(&mut self) -> Result<(), SyncError> {
        self.upload()?;
        self.package()?;
        self.acknowledge()?;
        self.state.last_sync_at = Some(time::system_millis());
        self.state.last_error = None;
        self.state.last_via_relay = true;
        self.save()
    }

    /// Step 1.
    fn upload(&mut self) -> Result<(), SyncError> {
        // Changes the relay will have deleted by now are sent again; the phone skips what it
        // has merged.
        if self.state.relay_unacked_since.is_some_and(|t| self.now_ms() - t > UPLOAD_TTL_MS) {
            eprintln!("relay: uploads from more than 48 hours ago were never acknowledged; sending them again");
            self.state.relay_uploaded_seq = self.state.relay_acked.min(self.state.relay_uploaded_seq);
            self.state.relay_unacked_since = None;
        }
        loop {
            let mut events: Vec<Event> = self.store.read_events()?.into_iter().filter(|e| e.seq > self.state.relay_uploaded_seq).collect();
            events.sort_by_key(|e| e.seq);
            if events.is_empty() {
                return Ok(());
            }
            let batch = &events[..events.len().min(BATCH)];
            let name = format!("{}-{}", batch[0].seq, batch[batch.len() - 1].seq);
            let plain = serde_json::to_vec(&serde_json::json!({"deviceId": self.state.device_id, "events": batch})).map_err(|e| SyncError::Storage(e.to_string()))?;
            let envelope = crypto::seal(&self.keys.enc, KIND_CHANGES, &format!("changes|{}|{name}", self.keys.kid), &plain)?;
            let r = self.send("PUT", &format!("/v1/changes/{name}"), Some(&envelope), &[], None)?;
            match r.status {
                200 | 204 => {
                    self.state.relay_uploaded_seq = batch[batch.len() - 1].seq;
                    if self.state.relay_unacked_since.is_none() {
                        self.state.relay_unacked_since = Some(self.now_ms());
                    }
                    self.save()?;
                    self.report.events_sent += batch.len();
                }
                429 => {
                    // The phone has not collected the earlier batches yet; these go next time.
                    eprintln!("relay: 64 batches waiting already; {} events wait for the next sync", events.len());
                    return Ok(());
                }
                s => return Err(SyncError::RelayProtocol(format!("changes: HTTP {s}"))),
            }
        }
    }

    /// Step 2.
    fn package(&mut self) -> Result<(), SyncError> {
        for _ in 0..3 {
            let r = self.send("GET", "/v1/package", None, &[], None)?;
            let info: PackageInfo = match r.status {
                204 => return Ok(()),
                200 => serde_json::from_slice(&r.body).map_err(|e| SyncError::RelayProtocol(format!("package: {e}")))?,
                s => return Err(SyncError::RelayProtocol(format!("package: HTTP {s}"))),
            };
            if !valid_id(&info.id) {
                return Err(SyncError::RelayProtocol(format!("package ID {:?}", info.id)));
            }
            self.tell(Progress::Connected { address: self.ep.url() });
            if self.state.relay_package.as_deref() == Some(info.id.as_str()) {
                if !self.state.relay_fetched {
                    self.fetched(&info.id)?;
                }
                return Ok(());
            }
            match self.download(&info)? {
                Fetched::Gone => continue,
                Fetched::Ready(path) => {
                    let installed = self.install(&info, &path);
                    if let Err(SyncError::Integrity(_)) = &installed {
                        // Download it again next time rather than trust these bytes.
                        let _ = fs::remove_file(&path);
                    }
                    let _ = fs::remove_file(path.with_extension("zip"));
                    installed?;
                    let _ = fs::remove_file(&path);
                    return self.fetched(&info.id);
                }
            }
        }
        Err(SyncError::RelayProtocol("the package kept changing".into()))
    }

    fn fetched(&mut self, id: &str) -> Result<(), SyncError> {
        let r = self.send("POST", &format!("/v1/packages/{id}/fetched"), None, &[], None)?;
        match r.status {
            200 | 204 => {
                self.state.relay_fetched = true;
                self.save()
            }
            // Replaced meanwhile: nothing to record.
            404 => Ok(()),
            s => Err(SyncError::RelayProtocol(format!("fetched: HTTP {s}"))),
        }
    }

    /// Downloads the package envelope to `data/relay/<id>.mir`, continuing what an earlier
    /// sync left.
    fn download(&mut self, info: &PackageInfo) -> Result<Fetched, SyncError> {
        let dir = self.store.relay_dir();
        fs::create_dir_all(&dir)?;
        let part = dir.join(format!("{}.mir", info.id));
        // Whatever an earlier package left.
        for entry in fs::read_dir(&dir)?.flatten() {
            if entry.path() != part {
                let _ = fs::remove_file(entry.path());
            }
        }
        let file = OpenOptions::new().create(true).truncate(false).read(true).write(true).open(&part)?;
        let mut have = file.metadata()?.len();
        if info.size > 0 && have > info.size {
            file.set_len(0)?;
            have = 0;
        }
        if info.size > 0 && have == info.size {
            return Ok(Fetched::Ready(part));
        }
        let mut sink = Download { file, have };
        let range = if have > 0 { vec![("Range", format!("bytes={have}-"))] } else { Vec::new() };
        if have > 0 {
            eprintln!("relay: resuming the package download at {have} of {} bytes", info.size);
        }
        let r = self.send("GET", &format!("/v1/packages/{}", info.id), None, &range, Some(&mut sink))?;
        match r.status {
            200 | 206 => {}
            404 | 416 => {
                drop(sink);
                let _ = fs::remove_file(&part);
                return Ok(Fetched::Gone);
            }
            s => return Err(SyncError::RelayProtocol(format!("package download: HTTP {s}"))),
        }
        sink.file.sync_all()?;
        if info.size > 0 && sink.have != info.size {
            drop(sink);
            let _ = fs::remove_file(&part);
            return Err(SyncError::Integrity(format!("the package is {} bytes, not {}", sink_len(&part), info.size)));
        }
        Ok(Fetched::Ready(part))
    }

    /// Decrypts the envelope at `path` and installs what it holds.
    fn install(&mut self, info: &PackageInfo, path: &Path) -> Result<(), SyncError> {
        let wrapped = crypto::b64url_decode(&info.key).ok_or_else(|| SyncError::Integrity("the package key is not base64url".into()))?;
        let key = crypto::open(&self.keys.enc, KIND_WRAP, &format!("wrap|{}|{}", self.keys.kid, info.id), &wrapped).map_err(|e| SyncError::Integrity(format!("package key: {e}")))?;
        let key: [u8; 32] = key.try_into().map_err(|_| SyncError::Integrity("the package key is not 32 bytes".into()))?;
        let zip_path = path.with_extension("zip");
        {
            let input = BufReader::with_capacity(1 << 16, File::open(path)?);
            let mut output = BufWriter::with_capacity(1 << 16, File::create(&zip_path)?);
            crypto::open_stream(&key, KIND_PACKAGE, &format!("package|{}", info.id), input, &mut output).map_err(|e| match e {
                crypto::EnvelopeError::Io(e) => SyncError::Storage(e),
                e => SyncError::Integrity(e.to_string()),
            })?;
            output.flush()?;
        }
        let mut zip = Zip::open(&zip_path).map_err(|e| SyncError::Integrity(e.to_string()))?;
        let entry = |zip: &mut Zip, name: &str, limit: u64| -> Result<Vec<u8>, SyncError> { zip.read(name, limit).map_err(|e| SyncError::Integrity(e.to_string()))?.ok_or_else(|| SyncError::Integrity(format!("{name} is missing"))) };
        let manifest: Manifest = serde_json::from_slice(&entry(&mut zip, "manifest.json", 4 << 20)?).map_err(|e| SyncError::Integrity(format!("manifest: {e}")))?;
        if manifest.protocol.as_deref().is_some_and(|p| p != "relay/1") {
            return Err(SyncError::RelayProtocol(format!("package protocol {:?}", manifest.protocol)));
        }
        if manifest.db_id.is_empty() {
            return Err(SyncError::Integrity("manifest: no dbId".into()));
        }
        self.check_db_id(&manifest.db_id)?;

        // The editions, each saved as it is taken out, as LAN sync saves them as they arrive.
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
            let bytes = entry(&mut zip, &format!("editions/{}.json", e.id), 64 << 20)?;
            let bundle: Bundle = serde_json::from_slice(&bytes).map_err(|err| SyncError::Integrity(format!("edition {}: {err}", e.id)))?;
            self.store.ensure_dirs()?;
            store::atomic_write(&path, &bytes)?;
            self.state.editions.insert(key, e.version.clone());
            let mut order: Vec<i64> = manifest.editions.iter().map(|m| m.id).filter(|id| self.state.editions.contains_key(&id.to_string())).collect();
            let leaving: Vec<i64> = self.state.edition_order.iter().copied().filter(|id| !order.contains(id)).collect();
            order.extend(leaving);
            self.state.edition_order = order;
            self.save()?;
            self.report.editions_fetched.push(e.id);
            self.tell(Progress::Edition(e.id));
            bundles.push(bundle);
        }
        let mut saved = self.store.load_saved();
        match &manifest.saved {
            Some(info) if self.state.saved_version.as_deref() != Some(info.version.as_str()) || !self.store.saved_path().is_file() => {
                let bytes = entry(&mut zip, "saved.json", 64 << 20)?;
                saved = serde_json::from_slice(&bytes).map_err(|err| SyncError::Integrity(format!("saved: {err}")))?;
                store::atomic_write(&self.store.saved_path(), &bytes)?;
                self.state.saved_version = Some(info.version.clone());
                self.save()?;
                self.report.saved_fetched = true;
                self.tell(Progress::Saved);
            }
            Some(_) => {}
            None => {
                if self.store.saved_path().exists() {
                    fs::remove_file(self.store.saved_path())?;
                    saved = SavedList::default();
                    self.tell(Progress::Saved);
                }
                self.state.saved_version = None;
            }
        }

        // Pictures, in the order LAN sync fetches them, at this Kobo's width.
        let images: Vec<ImageEntry> = match zip.read("images.json", 16 << 20).map_err(|e| SyncError::Integrity(e.to_string()))? {
            Some(b) => serde_json::from_slice(&b).map_err(|e| SyncError::Integrity(format!("images.json: {e}")))?,
            None => Vec::new(),
        };
        let mut urls: Vec<String> = Vec::new();
        for u in bundles.iter().flat_map(|b| b.image_urls_in_reading_order()).chain(saved.image_urls()) {
            if !urls.contains(&u) && !self.store.has_image(&u) {
                urls.push(u);
            }
        }
        if !urls.is_empty() {
            self.tell(Progress::ImagesQueued(urls.clone()));
        }
        let width = self.opts.image_width;
        for url in urls {
            let picked = pick_image(&images, &url, width).filter(|e| zip.contains(&e.file));
            let Some(e) = picked else {
                self.report.images_missing += 1;
                self.tell(Progress::ImageMissing(url));
                continue;
            };
            let mut bytes = entry(&mut zip, &e.file, 32 << 20)?;
            if e.w > width {
                bytes = scale_down(bytes, &e.kind, width);
            }
            store::atomic_write(&self.store.image_path(&url), &bytes)?;
            self.report.images_fetched += 1;
            self.tell(Progress::Image(url));
        }

        // Commit, as LAN sync's step 6.
        let keep: BTreeSet<i64> = manifest.editions.iter().map(|e| e.id).collect();
        self.report.editions_removed = self.state.editions.keys().filter_map(|k| k.parse().ok()).filter(|id| !keep.contains(id)).collect();
        self.state.editions = manifest.editions.iter().map(|e| (e.id.to_string(), e.version.clone())).collect();
        self.state.edition_order = manifest.editions.iter().map(|e| e.id).collect();
        self.state.relay_package = Some(info.id.clone());
        self.state.relay_fetched = false;
        self.state.relay_acked = manifest.acked.get(&self.state.device_id).copied().unwrap_or(0);
        self.save()?;
        self.store.collect_garbage(&keep)?;
        Ok(())
    }

    fn check_db_id(&mut self, db_id: &str) -> Result<(), SyncError> {
        if self.state.db_id.as_deref().is_some_and(|saved| saved != db_id) {
            eprintln!("relay: the phone's database changed; wiping");
            self.store.wipe(&mut self.state)?;
            self.report.wiped = true;
            self.tell(Progress::Saved);
        }
        self.state.db_id = Some(db_id.to_string());
        Ok(())
    }

    /// Step 3: the events the phone has merged go. The seq counter is saved past them first,
    /// so a seq is never handed out twice.
    fn acknowledge(&mut self) -> Result<(), SyncError> {
        let acked = self.state.relay_acked;
        if acked > 0 {
            self.state.next_seq = self.store.next_seq(&self.state)?.max(acked + 1);
            self.save()?;
            self.store.drop_events_through(acked)?;
        }
        let unacked = self.store.read_events()?.iter().any(|e| e.seq <= self.state.relay_uploaded_seq);
        if !unacked && self.state.relay_unacked_since.is_some() {
            self.state.relay_unacked_since = None;
            self.save()?;
        }
        Ok(())
    }
}

fn sink_len(p: &Path) -> u64 {
    fs::metadata(p).map(|m| m.len()).unwrap_or(0)
}

fn session<'a>(store: &'a Store, opts: &'a Options, address: &str) -> Result<Session<'a>, SyncError> {
    store.ensure_dirs()?;
    let state = store.load_state()?;
    let ep = Endpoint::parse(address).map_err(SyncError::RelayAddress)?;
    let secret = state.relay_secret.as_deref().and_then(crypto::b64url_decode).filter(|s| s.len() == 32).ok_or_else(|| SyncError::RelayAddress("this Kobo has no pairing secret yet".into()))?;
    crate::tls::set_clock_offset(state.clock_offset_ms);
    let keys = DeviceKeys::from_secret(&secret);
    let report = SyncReport { address: ep.url(), via_relay: true, ..Default::default() };
    Ok(Session { store, opts, ep, keys, state, started: Instant::now(), report })
}

/// After `401 unknown`. The relay deleted this Kobo's pending changes when the phone dropped
/// it (or never had them): they are sent again once it is paired.
fn mark_unpaired(state: &mut State) {
    state.relay_unpaired = true;
    state.relay_uploaded_seq = state.relay_acked.min(state.relay_uploaded_seq);
    state.relay_unacked_since = None;
}

/// Runs a relay sync. The outcome is saved in the state for the home screen and Settings.
pub fn sync(store: &Store, opts: &Options, address: &str) -> Result<SyncReport, SyncError> {
    let mut s = session(store, opts, address)?;
    let result = s.run();
    let mut state = s.state;
    if let Err(e) = &result {
        if let SyncError::RelayUnpaired { .. } = e {
            mark_unpaired(&mut state);
        }
        state.last_error = Some(e.to_string());
    }
    store.save_state(&state)?;
    result.map(|()| s.report)
}

/// Whether the phone lists this Kobo with the relay yet, for the pairing screen: one signed
/// `GET /v1/package`, which the relay answers `401 unknown` until the phone has sent it the
/// device list after scanning the code. Nothing is uploaded, downloaded or installed, and the
/// last sync's result is left alone; what the relay said is saved in the state as a sync
/// saves it.
pub fn listed(store: &Store, opts: &Options, address: &str) -> Result<bool, SyncError> {
    let mut s = session(store, opts, address)?;
    let result = match s.send("GET", "/v1/package", None, &[], None) {
        Ok(r) if r.status == 200 || r.status == 204 => Ok(true),
        Ok(r) => Err(SyncError::RelayProtocol(format!("package: HTTP {}", r.status))),
        Err(SyncError::RelayUnpaired { .. }) => {
            mark_unpaired(&mut s.state);
            Ok(false)
        }
        Err(e) => Err(e),
    };
    store.save_state(&s.state)?;
    result
}

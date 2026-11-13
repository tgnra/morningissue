//! Everything under `data/`: the state file, stored editions, images and unsent events.
//!
//! Every whole-file write goes to a temporary file in the same directory, is fsynced, renamed
//! over the old file and the directory is fsynced, so a power cut leaves either the old or the
//! new file, never half of one. Events are appended one line at a time and fsynced each time.

use crate::model::{Bundle, SavedList};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct State {
    pub device_id: String,
    pub db_id: Option<String>,
    /// The next event `seq` to hand out. Events in `events.jsonl` may already be higher; see
    /// [`Store::next_seq`].
    pub next_seq: i64,
    /// Stored editions by ID, with the manifest `version` each was fetched at.
    pub editions: BTreeMap<String, String>,
    /// Newest first, as the manifest lists them.
    pub edition_order: Vec<i64>,
    /// `saved.version` from the manifest that `saved.json` was fetched at.
    pub saved_version: Option<String>,
    /// System-clock milliseconds of the last sync that finished.
    pub last_sync_at: Option<i64>,
    /// Why the last attempt failed; None when it succeeded.
    pub last_error: Option<String>,
    /// The protocol of the manifest `db_id` came from. A `db_id` from before server sync
    /// (`relay/1`, or none written) names the phone's database, whose IDs mean nothing to the
    /// server: the first server sync wipes before it uploads anything.
    pub db_protocol: Option<String>,
    /// True time minus Kobo clock in milliseconds, from the server's `Date` header. Added to
    /// event times so they are true UTC even when the Kobo's clock is off.
    pub clock_offset_ms: i64,
    /// The device secret `S`, base64url, from which the server token is derived. Made once,
    /// when the pairing code is first shown, and never dropped by a state write; only Reset
    /// erases it. Versions that synced through the relay wrote it as `relaySecret`.
    #[serde(alias = "relaySecret")]
    pub secret: Option<String>,
    /// The highest event `seq` the server has said it merged. Events at or below it are
    /// deleted from `events.jsonl`; every sync uploads the ones above it. Read from
    /// `relayAcked` too.
    #[serde(alias = "relayAcked")]
    pub acked: i64,
    /// The server has accepted this Kobo's token since the secret was made. (The relay
    /// versions' `relaySeen` is not carried over: the relay letting this Kobo in says nothing
    /// about the server.)
    pub server_seen: bool,
    /// The server's last answer was `401 unknown`: it does not know this Kobo's token. Before
    /// `server_seen` that means the phone has not paired it yet; after, that it was removed.
    pub server_not_listed: bool,
}

/// One line of `events.jsonl`, in the shape `POST /api/v1/sync/events` carries.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Event {
    pub seq: i64,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub article_id: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub edition_id: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub page: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ms: Option<i64>,
    pub at: String,
}

/// Likes kept in `liked.json`; older ones are dropped.
pub const MAX_LIKED: usize = 2000;

/// The reader appends events while a sync on another thread sends and drops them; both go
/// through this lock so an append is never lost to a rewrite.
static EVENTS: std::sync::Mutex<()> = std::sync::Mutex::new(());
/// Whole-state writes from the two threads, one at a time.
static STATE: std::sync::Mutex<()> = std::sync::Mutex::new(());

pub struct Store {
    pub home: PathBuf,
}

impl Store {
    pub fn new(home: impl Into<PathBuf>) -> Store {
        Store { home: home.into() }
    }

    pub fn data(&self) -> PathBuf {
        self.home.join("data")
    }
    pub fn editions_dir(&self) -> PathBuf {
        self.data().join("editions")
    }
    pub fn images_dir(&self) -> PathBuf {
        self.data().join("images")
    }
    pub fn state_path(&self) -> PathBuf {
        self.data().join("state.json")
    }
    pub fn events_path(&self) -> PathBuf {
        self.data().join("events.jsonl")
    }
    pub fn saved_path(&self) -> PathBuf {
        self.data().join("saved.json")
    }
    /// Articles Liked on this Kobo, oldest first. The phone's Likes are not shown here.
    pub fn liked_path(&self) -> PathBuf {
        self.data().join("liked.json")
    }
    pub fn settings_path(&self) -> PathBuf {
        self.data().join("settings.json")
    }
    pub fn edition_path(&self, id: i64) -> PathBuf {
        self.editions_dir().join(format!("{id}.json"))
    }
    pub fn image_path(&self, url: &str) -> PathBuf {
        self.images_dir().join(format!("{}.img", image_key(url)))
    }

    /// Written by Settings > Reset; the next `read` erases the data before anything loads it.
    pub fn reset_marker_path(&self) -> PathBuf {
        self.data().join("reset-pending")
    }

    /// Asks for a reset at the next start. The reset is not done in place because the
    /// background sync may still be writing; the reader exits straight after this.
    pub fn request_reset(&self) -> io::Result<()> {
        fs::create_dir_all(self.data())?;
        write_synced(&self.reset_marker_path(), b"reset\n")
    }

    /// If a reset was asked for, erases everything in the data directory but the logs
    /// (`launch.log*`, `panic.txt`): the pairing and device ID, editions, saved articles,
    /// pictures, unsent events and settings. Returns whether it did.
    pub fn apply_pending_reset(&self) -> io::Result<bool> {
        if !self.reset_marker_path().exists() {
            return Ok(false);
        }
        for entry in fs::read_dir(self.data())? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("launch.log") || name == "panic.txt" || name == "reset-pending" {
                continue;
            }
            let path = entry.path();
            if path.is_dir() {
                fs::remove_dir_all(&path)?;
            } else {
                fs::remove_file(&path)?;
            }
        }
        // The marker goes last, so a reset cut short is finished at the next start.
        fs::remove_file(self.reset_marker_path())?;
        Ok(true)
    }

    pub fn ensure_dirs(&self) -> io::Result<()> {
        fs::create_dir_all(self.editions_dir())?;
        fs::create_dir_all(self.images_dir())?;
        Ok(())
    }

    /// The state file, or a fresh state with a new device ID (which is saved at once, so it
    /// never changes afterwards).
    pub fn load_state(&self) -> io::Result<State> {
        let mut state = match fs::read(self.state_path()) {
            Ok(bytes) => serde_json::from_slice::<State>(&bytes).unwrap_or_default(),
            Err(e) if e.kind() == io::ErrorKind::NotFound => State::default(),
            Err(e) => return Err(e),
        };
        if state.next_seq < 1 {
            state.next_seq = 1;
        }
        if state.device_id.is_empty() {
            state.device_id = new_uuid()?;
            self.save_state(&state)?;
        }
        Ok(state)
    }

    pub fn save_state(&self, state: &State) -> io::Result<()> {
        let _guard = STATE.lock().unwrap_or_else(|e| e.into_inner());
        self.ensure_dirs()?;
        // A sync thread's copy of the state may predate the secret the reader just made for
        // the pairing code; the secret must survive that copy being saved.
        let kept;
        let state = if state.secret.is_none() {
            match self.read_state_file().and_then(|s| s.secret) {
                Some(secret) => {
                    kept = State { secret: Some(secret), ..state.clone() };
                    &kept
                }
                None => state,
            }
        } else {
            state
        };
        let bytes = serde_json::to_vec_pretty(state).map_err(io::Error::other)?;
        atomic_write(&self.state_path(), &bytes)
    }

    fn read_state_file(&self) -> Option<State> {
        fs::read(self.state_path()).ok().and_then(|b| serde_json::from_slice(&b).ok())
    }

    /// The device secret `S` (base64url), made from the kernel's random source the first time.
    pub fn ensure_secret(&self) -> io::Result<String> {
        let state = self.load_state()?;
        if let Some(s) = state.secret {
            return Ok(s);
        }
        let _guard = STATE.lock().unwrap_or_else(|e| e.into_inner());
        let mut state = self.read_state_file().unwrap_or(state);
        if let Some(s) = state.secret {
            return Ok(s);
        }
        let secret = crate::crypto::b64url(&crate::crypto::random::<32>()?);
        state.secret = Some(secret.clone());
        state.server_seen = false;
        state.server_not_listed = false;
        let bytes = serde_json::to_vec_pretty(&state).map_err(io::Error::other)?;
        atomic_write(&self.state_path(), &bytes)?;
        Ok(secret)
    }

    /// Where a picture zip is written while it downloads and is installed.
    pub fn incoming_dir(&self) -> PathBuf {
        self.data().join("incoming")
    }

    /// Where versions that synced through the relay downloaded packages; removed at the next
    /// sync.
    pub fn old_relay_dir(&self) -> PathBuf {
        self.data().join("relay")
    }

    pub fn load_bundle(&self, id: i64) -> Option<Bundle> {
        let bytes = fs::read(self.edition_path(id)).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    /// The stored editions in manifest order, newest first. An edition whose file is missing
    /// or unreadable is left out.
    pub fn load_editions(&self, state: &State) -> Vec<Bundle> {
        let mut ids: Vec<i64> = state.edition_order.clone();
        for id in state.editions.keys().filter_map(|k| k.parse::<i64>().ok()) {
            if !ids.contains(&id) {
                ids.push(id);
            }
        }
        ids.into_iter().filter(|id| state.editions.contains_key(&id.to_string())).filter_map(|id| self.load_bundle(id)).collect()
    }

    /// The saved articles from the last package's `saved.json`, or an empty list.
    pub fn load_saved(&self) -> SavedList {
        fs::read(self.saved_path()).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }

    /// The articles Liked on this Kobo, oldest first, or none.
    pub fn load_liked(&self) -> Vec<i64> {
        fs::read(self.liked_path()).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }

    /// Keeps the newest [`MAX_LIKED`] Likes.
    pub fn save_liked(&self, liked: &[i64]) -> io::Result<()> {
        fs::create_dir_all(self.data())?;
        let keep = &liked[liked.len().saturating_sub(MAX_LIKED)..];
        atomic_write(&self.liked_path(), &serde_json::to_vec(keep).map_err(io::Error::other)?)
    }

    pub fn has_image(&self, url: &str) -> bool {
        self.image_path(url).is_file()
    }

    pub fn read_image(&self, url: &str) -> Option<Vec<u8>> {
        fs::read(self.image_path(url)).ok()
    }

    // --- Events ---

    /// Every complete event line. A line cut short by a power cut is skipped.
    pub fn read_events(&self) -> io::Result<Vec<Event>> {
        let file = match File::open(self.events_path()) {
            Ok(f) => f,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(e),
        };
        let mut events = Vec::new();
        for line in BufReader::new(file).split(b'\n') {
            let line = line?;
            if let Ok(ev) = serde_json::from_slice::<Event>(&line) {
                events.push(ev);
            }
        }
        Ok(events)
    }

    /// The seq to give the next event: past both the state's counter and anything on file.
    pub fn next_seq(&self, state: &State) -> io::Result<i64> {
        let on_file = self.read_events()?.iter().map(|e| e.seq).max().unwrap_or(0);
        Ok(state.next_seq.max(on_file + 1).max(1))
    }

    /// Appends one event and fsyncs it.
    pub fn append_event(&self, event: &Event) -> io::Result<()> {
        let _guard = EVENTS.lock().unwrap_or_else(|e| e.into_inner());
        self.ensure_dirs()?;
        let path = self.events_path();
        let mut file = OpenOptions::new().create(true).append(true).read(true).open(&path)?;
        // If the last append was cut short, start on a fresh line.
        let len = file.metadata()?.len();
        let mut line = Vec::new();
        if len > 0 {
            let mut last = [0u8; 1];
            let mut f = File::open(&path)?;
            use std::io::Seek;
            f.seek(io::SeekFrom::Start(len - 1))?;
            f.read_exact(&mut last)?;
            if last[0] != b'\n' {
                line.push(b'\n');
            }
        }
        line.extend(serde_json::to_vec(event).map_err(io::Error::other)?);
        line.push(b'\n');
        file.write_all(&line)?;
        file.sync_all()?;
        sync_dir(&self.data());
        Ok(())
    }

    /// Drops events up to and including `acked`, keeping later ones.
    pub fn drop_events_through(&self, acked: i64) -> io::Result<()> {
        let _guard = EVENTS.lock().unwrap_or_else(|e| e.into_inner());
        let events = self.read_events()?;
        if !events.iter().any(|e| e.seq <= acked) {
            return Ok(());
        }
        let mut out = Vec::new();
        for e in events.iter().filter(|e| e.seq > acked) {
            out.extend(serde_json::to_vec(e).map_err(io::Error::other)?);
            out.push(b'\n');
        }
        atomic_write(&self.events_path(), &out)
    }

    /// Removes every edition, image and unsent event (a `dbId` change). Keeps the device ID,
    /// the secret and the seq counter, which only goes up.
    pub fn wipe(&self, state: &mut State) -> io::Result<()> {
        let next = self.next_seq(state)?;
        for dir in [self.editions_dir(), self.images_dir()] {
            if dir.exists() {
                fs::remove_dir_all(&dir)?;
            }
        }
        {
            let _guard = EVENTS.lock().unwrap_or_else(|e| e.into_inner());
            let _ = fs::remove_file(self.events_path());
        }
        let _ = fs::remove_file(self.saved_path());
        // The IDs belonged to the other database.
        let _ = fs::remove_file(self.liked_path());
        state.next_seq = next;
        state.editions.clear();
        state.edition_order.clear();
        state.saved_version = None;
        self.ensure_dirs()?;
        self.save_state(state)
    }

    /// Deletes edition files not in `keep` and images no kept edition or saved article refers
    /// to, and leftover temporary files.
    pub fn collect_garbage(&self, keep: &BTreeSet<i64>) -> io::Result<()> {
        let mut wanted = BTreeSet::new();
        for u in self.load_saved().image_urls() {
            wanted.insert(format!("{}.img", image_key(&u)));
        }
        for id in keep {
            if let Some(b) = self.load_bundle(*id) {
                for u in b.image_urls() {
                    wanted.insert(format!("{}.img", image_key(&u)));
                }
            }
        }
        if let Ok(rd) = fs::read_dir(self.editions_dir()) {
            for entry in rd.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                let id = name.strip_suffix(".json").and_then(|s| s.parse::<i64>().ok());
                if id.is_none_or(|id| !keep.contains(&id)) {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
        if let Ok(rd) = fs::read_dir(self.images_dir()) {
            for entry in rd.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if !wanted.contains(&name) {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
        Ok(())
    }
}

/// Lower-case hex SHA-256 of the URL's UTF-8 bytes.
pub fn image_key(url: &str) -> String {
    let digest = Sha256::digest(url.as_bytes());
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

/// Writes `bytes` to `path` so that the file is either the old one or the complete new one.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let tmp = temp_path(path);
    write_synced(&tmp, bytes)?;
    commit(&tmp, path)
}

/// A temporary sibling of `path` that [`Store::collect_garbage`] or the next write replaces.
pub fn temp_path(path: &Path) -> PathBuf {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    path.with_file_name(format!(".{name}.tmp"))
}

/// Writes and fsyncs a file (the first half of [`atomic_write`]).
pub fn write_synced(path: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let mut f = File::create(path)?;
    f.write_all(bytes)?;
    f.sync_all()
}

/// Renames a synced temporary file into place and fsyncs the directory.
pub fn commit(tmp: &Path, path: &Path) -> io::Result<()> {
    fs::rename(tmp, path)?;
    if let Some(dir) = path.parent() {
        sync_dir(dir);
    }
    Ok(())
}

fn sync_dir(dir: &Path) {
    // FAT (the Kobo's user partition) may refuse to fsync a directory; the rename still stands.
    if let Ok(d) = File::open(dir) {
        let _ = d.sync_all();
    }
}

/// A random version 4 UUID from the kernel's random source.
pub fn new_uuid() -> io::Result<String> {
    let mut b = [0u8; 16];
    File::open("/dev/urandom")?.read_exact(&mut b)?;
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    Ok(format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32]))
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn temp_home(name: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("mi-store-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn ev(seq: i64) -> Event {
        Event { seq, kind: "opened".into(), article_id: Some(1), edition_id: None, page: None, value: None, ms: None, at: "2026-11-11T06:30:00Z".into() }
    }

    #[test]
    fn a_requested_reset_erases_everything_but_the_logs() {
        let store = Store::new(temp_home("reset"));
        let device = store.load_state().unwrap().device_id;
        store.ensure_dirs().unwrap();
        store.append_event(&ev(1)).unwrap();
        fs::write(store.edition_path(7), b"{}").unwrap();
        fs::write(store.settings_path(), b"{}").unwrap();
        fs::write(store.data().join("launch.log"), b"kept").unwrap();
        // Nothing happens until a reset is asked for.
        assert!(!store.apply_pending_reset().unwrap());
        assert!(store.edition_path(7).exists());

        store.request_reset().unwrap();
        assert!(store.apply_pending_reset().unwrap());
        let left: Vec<String> = fs::read_dir(store.data()).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        assert_eq!(left, ["launch.log"]);
        // A fresh install: a new device ID, so the phone pairs it as new.
        assert_ne!(store.load_state().unwrap().device_id, device);
        assert!(!store.apply_pending_reset().unwrap());
    }

    #[test]
    fn events_append_and_ack() {
        let store = Store::new(temp_home("events"));
        let state = store.load_state().unwrap();
        assert_eq!(state.device_id.len(), 36);
        assert_eq!(store.load_state().unwrap().device_id, state.device_id);
        for s in 1..=3 {
            store.append_event(&ev(s)).unwrap();
        }
        // A torn final line from a power cut is skipped, and the next append starts afresh.
        let mut f = OpenOptions::new().append(true).open(store.events_path()).unwrap();
        f.write_all(b"{\"seq\":4,\"ty").unwrap();
        store.append_event(&ev(5)).unwrap();
        let seqs: Vec<i64> = store.read_events().unwrap().iter().map(|e| e.seq).collect();
        assert_eq!(seqs, vec![1, 2, 3, 5]);
        assert_eq!(store.next_seq(&state).unwrap(), 6);
        store.drop_events_through(2).unwrap();
        let seqs: Vec<i64> = store.read_events().unwrap().iter().map(|e| e.seq).collect();
        assert_eq!(seqs, vec![3, 5]);
    }

    #[test]
    fn image_keys_are_sha256() {
        assert_eq!(image_key("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}

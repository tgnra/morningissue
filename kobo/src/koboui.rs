//! Running as a KoboUI app (KoboUI's docs/app-api.md).
//!
//! KoboUI starts `morningissue read` from its Apps row with `KOBOUI_MODE=run`, and
//! `morningissue sync` during its own sync with `KOBOUI_MODE=sync`, Wi-Fi already up. In both
//! modes KoboUI owns Wi-Fi and sync: `read` never turns Wi-Fi on or syncs, and Settings says
//! to sync from KoboUI. `KOBOUI_DATA_DIR` is a folder on KoboUI's data image, where the data
//! that otherwise lives in `data/` next to the binary is kept. Without these variables
//! (under NickelMenu) nothing changes.

use crate::store::Store;
use crate::sync::SyncReport;
use std::path::Path;

/// How KoboUI started this process.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// Not under KoboUI.
    None,
    /// `KOBOUI_MODE=run`: the reader, on a screen KoboUI handed over.
    Run,
    /// `KOBOUI_MODE=sync`: the sync-only entry point, run by KoboUI's sync.
    Sync,
}

/// The mode, from the environment.
pub fn mode() -> Mode {
    mode_from(std::env::var("KOBOUI").ok().as_deref(), std::env::var("KOBOUI_MODE").ok().as_deref())
}

pub fn mode_from(koboui: Option<&str>, mode: Option<&str>) -> Mode {
    match (koboui, mode) {
        (Some("1"), Some("run")) => Mode::Run,
        (Some("1"), Some("sync")) => Mode::Sync,
        _ => Mode::None,
    }
}

/// The store: in `KOBOUI_DATA_DIR` when KoboUI gives one, else `home/data`.
pub fn open_store(home: &Path) -> Store {
    open_store_in(home, std::env::var_os("KOBOUI_DATA_DIR").filter(|d| !d.is_empty()).as_deref().map(Path::new))
}

/// [`open_store`] with the data folder given. The first time, the NickelMenu install's
/// `data/` is copied in ([`Store::adopt`]), so the pairing and what was read carry over.
pub fn open_store_in(home: &Path, data: Option<&Path>) -> Store {
    let Some(data) = data else { return Store::new(home) };
    let store = Store::with_data(home, data);
    match store.adopt(&home.join("data")) {
        Ok(true) => eprintln!("koboui: copied data/ into {}", data.display()),
        Ok(false) => {}
        Err(e) => eprintln!("koboui: could not copy data/ into {}: {e}", data.display()),
    }
    store
}

/// The one line KoboUI shows for a sync that worked (on the app's tile, at most 80
/// characters): what came and what went, e.g. "2 editions fetched, 45 events sent".
pub fn summary(r: &SyncReport) -> String {
    let mut parts = Vec::new();
    if r.wiped {
        parts.push("server changed, data fetched again".to_string());
    }
    match r.editions_fetched.len() {
        0 => {}
        1 => parts.push("1 edition fetched".into()),
        n => parts.push(format!("{n} editions fetched")),
    }
    if r.saved_fetched {
        parts.push("saved list updated".into());
    }
    match r.events_sent {
        0 => {}
        1 => parts.push("1 event sent".into()),
        n => parts.push(format!("{n} events sent")),
    }
    if parts.is_empty() {
        return "Up to date".into();
    }
    let mut s = parts.join(", ");
    if let Some(c) = s.get(..1) {
        s = c.to_uppercase() + &s[1..];
    }
    s
}

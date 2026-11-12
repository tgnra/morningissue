//! A sync: what it reports and how it fails. A sync goes through the relay
//! ([`crate::relay`], `docs/relay-sync.md`), the only way this Kobo syncs with the phone.
//!
//! Every file write is atomic, so an interrupted sync leaves whole files: the editions that
//! were installed, and the earlier ones for the rest.

use crate::config::Config;
use std::fmt;
use std::io;
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug)]
pub enum SyncError {
    Storage(String),
    TimedOut,
    /// This Kobo has never shown its pairing code, so it has no secret to sign with.
    NotPaired,
    /// The relay address in `config.json` cannot be used.
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
            SyncError::Storage(e) => write!(f, "Could not save to the Kobo ({e})."),
            SyncError::TimedOut => write!(f, "Sync took too long and stopped."),
            SyncError::NotPaired => write!(f, "Not paired yet: tap Pair with phone in Settings and scan the code with Morning Issue on the phone."),
            SyncError::RelayAddress(e) => write!(f, "The relay address in config.json can\u{2019}t be used: {e}"),
            SyncError::RelayUnpaired { waiting: true } => write!(f, "Waiting for the phone to publish: scan the code under Pair with phone in Settings with Morning Issue on the phone."),
            SyncError::RelayUnpaired { waiting: false } => write!(f, "Not paired: the phone no longer accepts this Kobo. Pair with phone again in Settings."),
            SyncError::Relay(e) if e.contains("timed out") || e.contains("temporarily unavailable") => write!(f, "The relay took too long to answer."),
            SyncError::Relay(_) => write!(f, "Can\u{2019}t reach the relay."),
            SyncError::RelayProtocol(e) => write!(f, "The relay sent something unexpected ({e})."),
            SyncError::Clock => write!(f, "The Kobo\u{2019}s clock is wrong, so the relay\u{2019}s certificate can\u{2019}t be checked. Connect to Wi-Fi in Nickel to set the clock, then sync again."),
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
    /// The relay's address.
    pub address: String,
    pub wiped: bool,
    pub events_sent: usize,
    pub editions_fetched: Vec<i64>,
    pub editions_removed: Vec<i64>,
    pub saved_fetched: bool,
    pub images_fetched: usize,
    pub images_missing: usize,
}

/// What a running sync has got to, so the reader can show what arrived.
#[derive(Clone, Debug, PartialEq)]
pub enum Progress {
    /// The relay has a package for this Kobo, at this address.
    Connected { address: String },
    /// An edition was saved; the reader can show it.
    Edition(i64),
    /// The saved list changed on the Kobo.
    Saved,
    /// These pictures are about to be installed.
    ImagesQueued(Vec<String>),
    /// A picture was stored.
    Image(String),
    /// The package has no copy of this picture.
    ImageMissing(String),
}

pub type ProgressFn = Arc<dyn Fn(Progress) + Send + Sync>;

pub struct Options {
    /// How long to wait for any one response.
    pub request_timeout: Duration,
    /// The whole sync gives up after this.
    pub deadline: Duration,
    /// The pictures to take from a package are this wide: the screen's short side in pixels.
    pub image_width: u32,
    /// Told as each part arrives, on the sync's own thread.
    pub progress: Option<ProgressFn>,
    /// The relay's address (`http[s]://host[:port][/prefix]`), from
    /// [`Config::relay_address`].
    pub relay: String,
}

impl Options {
    pub fn from_config(c: &Config) -> Options {
        Options { request_timeout: Duration::from_secs(30), deadline: Duration::from_secs(600), image_width: 1264, progress: None, relay: c.relay_address() }
    }
}

/// Runs a whole sync through the relay. The outcome (time or error) is saved in the state for
/// the home screen. A Kobo that has never shown its pairing code fails at once with
/// [`SyncError::NotPaired`], without a request.
pub fn sync(store: &crate::store::Store, opts: &Options) -> Result<SyncReport, SyncError> {
    crate::relay::sync(store, opts)
}

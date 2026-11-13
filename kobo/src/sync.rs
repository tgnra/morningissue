//! A sync: what it reports and how it fails. A sync talks HTTPS (or HTTP) to the Morning
//! Issue server ([`crate::server`], `docs/kobo-sync.md`, "Transport"), with a bearer token
//! derived from the secret in this Kobo's pairing code. No phone is involved once the phone
//! has registered the code with the server.
//!
//! Every file write is atomic, so an interrupted sync leaves whole files: the editions that
//! were installed, and the earlier ones for the rest.

use crate::config::Config;
use crate::settings::Settings;
use std::fmt;
use std::io;
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug)]
pub enum SyncError {
    Storage(String),
    TimedOut,
    /// This Kobo has never shown its pairing code, so it has no secret to make a token from.
    NotPaired,
    /// No server address: neither typed in Settings nor in `config.json`.
    NoServer,
    /// The server address cannot be used.
    ServerAddress(String),
    /// The server answered `401 unknown`: it doesn't know this Kobo's token. `waiting`: it
    /// never has, so the phone has not registered the pairing code yet. Otherwise this Kobo
    /// was removed from the account.
    NotListed { waiting: bool },
    /// The server could not be reached, or the connection broke.
    Server(String),
    /// The server answered something unexpected.
    ServerProtocol(String),
    /// The server's certificate was not valid at the time the Kobo believes.
    Clock,
    /// The server kept answering `409 events_pending` after the events were uploaded again.
    EventsPending,
}

impl fmt::Display for SyncError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SyncError::Storage(e) => write!(f, "Could not save to the Kobo ({e})."),
            SyncError::TimedOut => write!(f, "Sync took too long and stopped."),
            SyncError::NotPaired => write!(f, "Not paired yet: tap Pair with phone in Settings and scan the code with Morning Issue on the phone."),
            SyncError::NoServer => write!(f, "No server address yet: type it under Pairing in Settings, or put \u{201c}server\u{201d} in config.json."),
            SyncError::ServerAddress(e) => write!(f, "The server address can\u{2019}t be used: {e}"),
            SyncError::NotListed { waiting: true } => write!(f, "Waiting for the phone: scan the code under Pair with phone in Settings with Morning Issue."),
            SyncError::NotListed { waiting: false } => write!(f, "This Kobo was removed from your account; pair again."),
            SyncError::Server(e) if e.contains("timed out") || e.contains("temporarily unavailable") => write!(f, "The server took too long to answer."),
            SyncError::Server(_) => write!(f, "Can\u{2019}t reach the server."),
            SyncError::ServerProtocol(e) => write!(f, "The server sent something unexpected ({e})."),
            SyncError::Clock => write!(f, "The Kobo\u{2019}s clock is wrong, so the server\u{2019}s certificate can\u{2019}t be checked. Connect to Wi-Fi in Nickel to set the clock, then sync again."),
            SyncError::EventsPending => write!(f, "The server didn\u{2019}t take this Kobo\u{2019}s reading history. Sync again later."),
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
    /// The server's address.
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
    /// The server sent its manifest; it is at this address.
    Connected { address: String },
    /// An edition was saved; the reader can show it.
    Edition(i64),
    /// The saved list changed on the Kobo.
    Saved,
    /// These pictures are about to be fetched.
    ImagesQueued(Vec<String>),
    /// A picture was stored.
    Image(String),
    /// The server has no copy of this picture.
    ImageMissing(String),
}

pub type ProgressFn = Arc<dyn Fn(Progress) + Send + Sync>;

pub struct Options {
    /// How long to wait for any one response.
    pub request_timeout: Duration,
    /// How long to wait for a picture zip, which the server may have to prepare first.
    pub image_timeout: Duration,
    /// The whole sync gives up after this.
    pub deadline: Duration,
    /// Pictures are asked for this wide: the screen's short side in pixels.
    pub image_width: u32,
    /// Told as each part arrives, on the sync's own thread.
    pub progress: Option<ProgressFn>,
    /// The server's address (`http[s]://host[:port][/prefix]`): `server` in `config.json`,
    /// else the one typed in Settings. None when there is neither.
    pub server: Option<String>,
}

impl Options {
    pub fn new(server: Option<String>) -> Options {
        Options { request_timeout: Duration::from_secs(30), image_timeout: Duration::from_secs(120), deadline: Duration::from_secs(600), image_width: 1264, progress: None, server }
    }

    pub fn from_config(c: &Config, s: &Settings) -> Options {
        Options::new(c.server_or(s.server.as_deref()))
    }
}

/// Runs a whole sync with the server. The outcome (time or error) is saved in the state for
/// the home screen. A Kobo that has never shown its pairing code fails at once with
/// [`SyncError::NotPaired`], and one with no server address with [`SyncError::NoServer`],
/// without a request.
pub fn sync(store: &crate::store::Store, opts: &Options) -> Result<SyncReport, SyncError> {
    crate::server::sync(store, opts)
}

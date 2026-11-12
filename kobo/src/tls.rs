//! TLS for `https://` relays: rustls with the ring provider and the Mozilla roots
//! (webpki-roots). The phone on the LAN is always plain HTTP.
//!
//! Certificate validity needs roughly the right time, and a Kobo's clock can be badly wrong.
//! The time the client believes is the system clock plus the offset learnt from the last
//! `Date` header a relay or the phone sent, and never earlier than the build time.

use rustls::client::ClientConnection;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::time_provider::TimeProvider;
use rustls::{CertificateError, ClientConfig, RootCertStore, StreamOwned};
use std::fmt;
use std::io;
use std::net::TcpStream;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// When `build.rs` did not run (it always does under cargo): 2026-11-01.
const FALLBACK_BUILD_UNIX: i64 = 1_793_491_200;

/// Seconds since the epoch when this binary was built.
pub fn build_unix() -> i64 {
    option_env!("MORNINGISSUE_BUILD_UNIX").and_then(|s| s.parse().ok()).unwrap_or(FALLBACK_BUILD_UNIX)
}

static CLOCK_OFFSET_MS: AtomicI64 = AtomicI64::new(0);

/// True time minus the system clock, from the last `Date` header seen.
pub fn set_clock_offset(ms: i64) {
    CLOCK_OFFSET_MS.store(ms, Ordering::Relaxed);
}

/// The time certificates are checked against, in milliseconds since the epoch.
pub fn trusted_time_ms(system_ms: i64, offset_ms: i64) -> i64 {
    system_ms.saturating_add(offset_ms).max(build_unix() * 1000)
}

#[derive(Debug)]
struct KoboTime;

impl TimeProvider for KoboTime {
    fn current_time(&self) -> Option<UnixTime> {
        let ms = trusted_time_ms(crate::time::system_millis(), CLOCK_OFFSET_MS.load(Ordering::Relaxed));
        Some(UnixTime::since_unix_epoch(Duration::from_millis(ms.max(0) as u64)))
    }
}

/// A certificate that was not valid at the time the Kobo believes: its clock is wrong (or
/// the relay's certificate really has expired).
#[derive(Debug)]
pub struct ClockWrong(pub String);

impl fmt::Display for ClockWrong {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "certificate not valid at the Kobo's time ({})", self.0)
    }
}

impl std::error::Error for ClockWrong {}

/// Whether an error from [`connect`] (or a request over it) is [`ClockWrong`].
pub fn is_clock_wrong(e: &io::Error) -> bool {
    e.get_ref().is_some_and(|i| i.is::<ClockWrong>())
}

static EXTRA_ROOTS: Mutex<Vec<CertificateDer<'static>>> = Mutex::new(Vec::new());
static CONFIG: Mutex<Option<Arc<ClientConfig>>> = Mutex::new(None);

/// Trusts another root as well as the Mozilla ones: for tests against a local server.
pub fn add_trusted_root(der: Vec<u8>) {
    EXTRA_ROOTS.lock().unwrap().push(CertificateDer::from(der));
    *CONFIG.lock().unwrap() = None;
}

fn config() -> io::Result<Arc<ClientConfig>> {
    let mut slot = CONFIG.lock().unwrap();
    if let Some(c) = slot.as_ref() {
        return Ok(c.clone());
    }
    let mut roots = RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
    for der in EXTRA_ROOTS.lock().unwrap().iter() {
        roots.add(der.clone()).map_err(io::Error::other)?;
    }
    let config = ClientConfig::builder_with_details(Arc::new(rustls::crypto::ring::default_provider()), Arc::new(KoboTime))
        .with_safe_default_protocol_versions()
        .map_err(io::Error::other)?
        .with_root_certificates(roots)
        .with_no_client_auth();
    let config = Arc::new(config);
    *slot = Some(config.clone());
    Ok(config)
}

pub type TlsStream = StreamOwned<ClientConnection, TcpStream>;

/// Shakes hands over `tcp` (whose timeouts apply), sending `host` as SNI unless it is an IP
/// address.
pub fn connect(mut tcp: TcpStream, host: &str) -> io::Result<TlsStream> {
    let name = ServerName::try_from(host.trim_start_matches('[').trim_end_matches(']').to_string()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("bad host name {host}")))?;
    let mut conn = ClientConnection::new(config()?, name).map_err(io::Error::other)?;
    while conn.is_handshaking() {
        if let Err(e) = conn.complete_io(&mut tcp) {
            let tls = e.get_ref().and_then(|i| i.downcast_ref::<rustls::Error>());
            if let Some(rustls::Error::InvalidCertificate(c)) = tls {
                if matches!(c, CertificateError::Expired | CertificateError::NotValidYet | CertificateError::ExpiredContext { .. } | CertificateError::NotValidYetContext { .. }) {
                    return Err(io::Error::other(ClockWrong(format!("{c:?}"))));
                }
            }
            return Err(e);
        }
    }
    Ok(StreamOwned::new(conn, tcp))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_trusted_time_follows_the_offset_but_never_goes_before_the_build() {
        let build = build_unix() * 1000;
        assert!(build >= FALLBACK_BUILD_UNIX * 1000);
        // A clock years behind (a Kobo whose battery ran flat) is held at the build time.
        assert_eq!(trusted_time_ms(0, 0), build);
        // The offset from a Date header puts it right.
        assert_eq!(trusted_time_ms(0, build + 5_000), build + 5_000);
        assert_eq!(trusted_time_ms(build + 60_000, -30_000), build + 30_000);
        assert_eq!(trusted_time_ms(build + 60_000, -90_000), build);
        assert_eq!(trusted_time_ms(i64::MAX, 1), i64::MAX);
    }
}

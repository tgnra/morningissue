//! Embeds the build time (`SOURCE_DATE_EPOCH` when set) as the earliest time the TLS client
//! believes, since a Kobo's clock can be badly wrong (`src/tls.rs`).

use std::time::{SystemTime, UNIX_EPOCH};

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=SOURCE_DATE_EPOCH");
    let secs = std::env::var("SOURCE_DATE_EPOCH").ok().and_then(|s| s.trim().parse::<u64>().ok()).unwrap_or_else(|| SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0));
    println!("cargo:rustc-env=MORNINGISSUE_BUILD_UNIX={secs}");
}

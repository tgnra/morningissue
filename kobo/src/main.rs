//! `morningissue sync | read | check`
//!
//! The home directory is the binary's own directory (`/mnt/onboard/.adds/morningissue` on the
//! Kobo), or `MORNINGISSUE_HOME`.

use morningissue::app::{self, App};
use morningissue::config::Config;
use morningissue::device::SystemClock;
use morningissue::kobo::{self, EvdevInput, Framebuffer};
use morningissue::profile;
use morningissue::store::Store;
use morningissue::sync;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn home() -> PathBuf {
    if let Some(h) = std::env::var_os("MORNINGISSUE_HOME") {
        return PathBuf::from(h);
    }
    std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)).unwrap_or_else(|| PathBuf::from("."))
}

fn version_file() -> PathBuf {
    std::env::var_os("MORNINGISSUE_KOBO_VERSION").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/mnt/onboard/.kobo/version"))
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(String::as_str).unwrap_or("");
    let home = home();
    let config = Config::load(&home);
    match cmd {
        "sync" => {
            let store = Store::new(&home);
            match sync::sync(&store, &sync::Options::from_config(&config)) {
                Ok(r) => {
                    println!(
                        "synced with {}: {} events sent, editions fetched {:?}, removed {:?}, {} images fetched, {} not on the phone{}{}",
                        r.address,
                        r.events_sent,
                        r.editions_fetched,
                        r.editions_removed,
                        r.images_fetched,
                        r.images_missing,
                        if r.paired { ", paired" } else { "" },
                        if r.wiped { ", phone database changed: local data wiped" } else { "" }
                    );
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("sync failed: {e}");
                    ExitCode::from(1)
                }
            }
        }
        "check" => {
            let p = profile::detect(&version_file());
            println!("{} (product {}, {}, {:?} panel, {} dpi, {})", p.name, p.id, p.codename, p.panel, p.dpi, if p.verified { "verified" } else { "unverified" });
            match kobo::unsupported_reason(&p) {
                Some(why) => {
                    println!("{why}");
                    ExitCode::from(3)
                }
                None => ExitCode::SUCCESS,
            }
        }
        "read" => {
            let p = profile::detect(&version_file());
            eprintln!("read: {} (product {}), {}", p.name, p.id, if p.verified { "verified" } else { "unverified profile" });
            let fb = match Framebuffer::open(p.clone(), &config) {
                Ok(fb) => fb,
                Err(e) => {
                    eprintln!("read: cannot use the screen: {e}");
                    return ExitCode::from(3);
                }
            };
            let base = morningissue::device::Display::base_size(&fb);
            let rotation = fb.canonical;
            let input = match EvdevInput::open(&p, &config, base) {
                Ok(i) => i,
                Err(e) => {
                    eprintln!("read: no input: {e}");
                    return ExitCode::from(3);
                }
            };
            let store = Store::new(&home);
            let opts = app::Options { rotation, follow_sensor: p.follow_sensor, can_rotate: true };
            let mut app = match App::new(Box::new(fb), Box::new(input), Box::new(SystemClock::new()), store, config, &home.join("fonts"), opts) {
                Ok(a) => a,
                Err(e) => {
                    eprintln!("read: {e}");
                    return ExitCode::from(2);
                }
            };
            match app.run() {
                Ok(()) => ExitCode::SUCCESS,
                Err(e) => {
                    eprintln!("read: {e}");
                    let _ = app.finish();
                    ExitCode::from(2)
                }
            }
        }
        _ => {
            eprintln!("usage: morningissue sync | read | check");
            ExitCode::from(64)
        }
    }
}

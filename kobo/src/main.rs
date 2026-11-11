//! `morningissue sync | read | check`
//!
//! The home directory is the binary's own directory (`/mnt/onboard/.adds/morningissue` on the
//! Kobo), or `MORNINGISSUE_HOME`.

use morningissue::app::{self, App};
use morningissue::config::Config;
use morningissue::device::SystemClock;
use morningissue::kobo::{self, EvdevInput, Framebuffer};
use morningissue::light::{KoboLight, Light, NoLight};
use morningissue::net;
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

/// Nickel's settings, where it keeps its own light levels.
const NICKEL_CONF: &str = "/mnt/onboard/.kobo/Kobo/Kobo eReader.conf";

fn wifi_power_off() -> std::io::Result<()> {
    use std::os::fd::AsRawFd;
    const CM_WIFI_CTRL: libc::c_ulong = 208;
    let f = std::fs::OpenOptions::new().read(true).write(true).open("/dev/ntx_io")?;
    let r = unsafe { libc::ioctl(f.as_raw_fd(), CM_WIFI_CTRL as _, 0 as libc::c_ulong) };
    if r < 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
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
                        "synced with {}: {} events sent, editions fetched {:?}, removed {:?}, saved list {}, {} images fetched, {} not on the phone{}{}",
                        r.address,
                        r.events_sent,
                        r.editions_fetched,
                        r.editions_removed,
                        if r.saved_fetched { "fetched" } else { "unchanged" },
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
            let light: Box<dyn Light> = match KoboLight::open(&p.light, Path::new(NICKEL_CONF)) {
                Some(l) => Box::new(l),
                None => Box::new(NoLight),
            };
            let opts = app::Options { rotation, follow_sensor: p.follow_sensor, light, network: Box::new(net::probe), sync_on_start: true, hit_probe: None };
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
        "wifi-off" => {
            // Cuts the Wi-Fi chip's power the way Nickel does (ntx_io ioctl 208 with 0), for
            // launch.sh, which cannot call ioctl itself. Used after the modules are unloaded.
            match wifi_power_off() {
                Ok(()) => ExitCode::SUCCESS,
                Err(e) => {
                    eprintln!("wifi-off: {e}");
                    ExitCode::from(1)
                }
            }
        }
        _ => {
            eprintln!("usage: morningissue sync | read | check | wifi-off");
            ExitCode::from(64)
        }
    }
}

//! `config.json`: optional overrides. Every field may be left out, and fields this version
//! doesn't know (such as `relay` from versions that synced through a relay, and `phone`,
//! `beaconSeconds` and `beaconPort` from versions that synced over the LAN) are ignored.

use serde::Deserialize;
use std::path::Path;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    /// The Morning Issue server's address, `http[s]://host[:port][/prefix]`. It overrides the
    /// one typed in Settings. There is no built-in default.
    pub server: Option<String>,
    /// The name the phone shows for this Kobo once it has scanned the pairing code.
    pub name: Option<String>,
    /// Full flash after this many partial page turns.
    pub flash_every: u32,
    /// Put the Kobo to sleep after this many minutes without input, as the power button
    /// does. 0 never does.
    pub idle_minutes: u64,
    /// Overrides for the touch panel; see README "Input".
    pub touch: TouchOverride,
    /// Overrides for the framebuffer; see README "Display".
    pub display: DisplayOverride,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TouchOverride {
    /// Path of the touch event device, instead of probing.
    pub device: Option<String>,
    pub swap_xy: Option<bool>,
    pub mirror_x: Option<bool>,
    pub mirror_y: Option<bool>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DisplayOverride {
    /// Rotate our portrait picture by this many quarter turns clockwise before writing it.
    pub quarter_turns: Option<u32>,
    /// Waveform numbers, for panels that want others.
    pub waveform_full: Option<u32>,
    pub waveform_partial: Option<u32>,
    pub waveform_fast: Option<u32>,
}

impl Default for Config {
    fn default() -> Config {
        Config {
            server: None,
            name: None,
            flash_every: 6,
            idle_minutes: 20,
            touch: TouchOverride::default(),
            display: DisplayOverride::default(),
        }
    }
}

impl Config {
    /// Reads `config.json` in `home`; a missing or broken file gives the defaults.
    pub fn load(home: &Path) -> Config {
        match std::fs::read(home.join("config.json")) {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
                eprintln!("config.json ignored: {e}");
                Config::default()
            }),
            Err(_) => Config::default(),
        }
    }

    /// `server` written out in full (an address without a scheme gets one, see
    /// [`crate::server::parse_address`]), or None when it is not set. One that can't be used
    /// is returned as written, and the sync says why.
    pub fn server_address(&self) -> Option<String> {
        let a = self.server.as_deref().map(str::trim).filter(|a| !a.is_empty())?;
        Some(crate::server::parse_address(a).unwrap_or_else(|_| a.to_string()))
    }

    /// The server to sync with: `server` here, else the address typed in Settings.
    pub fn server_or(&self, typed: Option<&str>) -> Option<String> {
        self.server_address().or_else(|| typed.map(str::to_string))
    }

    pub fn device_name(&self) -> String {
        if let Some(n) = &self.name {
            return n.clone();
        }
        // Nickel exports MODEL_NUMBER to the programs NickelMenu starts.
        match std::env::var("MODEL_NUMBER").ok().as_deref() {
            Some("388") | None => "Kobo Libra 2".into(),
            Some(_) => "Kobo e-reader".into(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_server_by_default_and_old_fields_are_ignored() {
        let dir = std::env::temp_dir().join(format!("mi-config-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(Config::load(&dir).server_address(), None);
        // A config.json from versions that synced over the LAN, or through the relay.
        std::fs::write(dir.join("config.json"), br#"{"phone": "192.168.1.20:47824", "beaconSeconds": 10, "beaconPort": 47823, "relay": "https://mirelay.tgnra.net", "flashEvery": 4}"#).unwrap();
        let c = Config::load(&dir);
        assert_eq!((c.flash_every, c.server_address()), (4, None));
        assert_eq!(c.server_or(Some("https://typed.example.org")).as_deref(), Some("https://typed.example.org"));
        std::fs::write(dir.join("config.json"), br#"{"server": "news.example.org"}"#).unwrap();
        let c = Config::load(&dir);
        assert_eq!(c.server_address().as_deref(), Some("https://news.example.org"));
        // config.json wins over what was typed.
        assert_eq!(c.server_or(Some("https://typed.example.org")).as_deref(), Some("https://news.example.org"));
        std::fs::write(dir.join("config.json"), br#"{"server": "  "}"#).unwrap();
        assert_eq!(Config::load(&dir).server_address(), None);
        std::fs::write(dir.join("config.json"), br#"{"server": "ftp://x"}"#).unwrap();
        assert_eq!(Config::load(&dir).server_address().as_deref(), Some("ftp://x"));
    }
}

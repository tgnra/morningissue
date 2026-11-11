//! `config.json`: optional overrides. Every field may be left out.

use serde::Deserialize;
use std::path::Path;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    /// `host:port` of the phone, for networks that drop broadcasts.
    pub phone: Option<String>,
    /// The name the phone shows when asked to allow pairing.
    pub name: Option<String>,
    /// How long `sync` listens for the phone's beacon, in seconds.
    pub beacon_seconds: u64,
    pub beacon_port: u16,
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
            phone: None,
            name: None,
            beacon_seconds: 10,
            beacon_port: 47823,
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

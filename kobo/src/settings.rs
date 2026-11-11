//! What the reader's Settings screen and "Aa" popover change, kept in `data/settings.json`.
//! A missing or broken file gives the defaults; unknown fields are ignored.

use serde::{Deserialize, Serialize};
use std::io;
use std::path::Path;

/// The phone's port when none is typed (`docs/kobo-sync.md`, "Transport").
pub const DEFAULT_PORT: u16 = 47824;

/// The text sizes the reader offers, as a factor of the base size.
pub const TEXT_SIZES: [f32; 5] = [0.86, 0.93, 1.0, 1.12, 1.26];
pub const DEFAULT_TEXT_SIZE: usize = 2;

/// Portrait or landscape. On a model whose orientation sensor is mapped, the sensor turns the
/// page upside down within the chosen one to match how the Kobo is held; elsewhere it is fixed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Orientation {
    Portrait,
    Landscape,
}

impl Orientation {
    /// The rotations (quarter turns from portrait) that belong to it: even for portrait, odd
    /// for landscape.
    pub fn parity(self) -> u8 {
        match self {
            Orientation::Portrait => 0,
            Orientation::Landscape => 1,
        }
    }
}

/// Reads the orientation leniently. Up to 0.2.3 the choices were "sensor" (the default:
/// follow the sensor freely), "portrait" and "landscape"; "sensor", and anything unknown,
/// becomes None: the orientation the screen was in when the reader opened, which is what
/// following the sensor showed at that moment.
fn lenient_orientation<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<Orientation>, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(match v.as_str() {
        Some("portrait") => Some(Orientation::Portrait),
        Some("landscape") => Some(Orientation::Landscape),
        _ => None,
    })
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// `host:port` typed on the keypad; None finds the phone by its beacon.
    pub phone_address: Option<String>,
    /// Swaps the two page-turn buttons.
    pub invert_buttons: bool,
    /// None until chosen: the reader starts in the orientation it finds the screen in.
    #[serde(deserialize_with = "lenient_orientation", skip_serializing_if = "Option::is_none")]
    pub orientation: Option<Orientation>,
    /// Index into [`TEXT_SIZES`].
    pub text_size: usize,
    /// Front light, in percent; None leaves it as Nickel set it.
    pub brightness: Option<u8>,
    /// Warmth, in percent (0 coolest); None leaves it as Nickel set it.
    pub warmth: Option<u8>,
}

impl Default for Settings {
    fn default() -> Settings {
        Settings { phone_address: None, invert_buttons: false, orientation: None, text_size: DEFAULT_TEXT_SIZE, brightness: None, warmth: None }
    }
}

impl Settings {
    pub fn load(path: &Path) -> Settings {
        let mut s: Settings = std::fs::read(path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        s.text_size = s.text_size.min(TEXT_SIZES.len() - 1);
        s.brightness = s.brightness.map(|v| v.min(100));
        s.warmth = s.warmth.map(|v| v.min(100));
        if let Some(a) = &s.phone_address {
            s.phone_address = parse_address(a).ok();
        }
        s
    }

    pub fn save(&self, path: &Path) -> io::Result<()> {
        let bytes = serde_json::to_vec_pretty(self).map_err(io::Error::other)?;
        crate::store::atomic_write(path, &bytes)
    }

    pub fn text_scale(&self) -> f32 {
        TEXT_SIZES[self.text_size.min(TEXT_SIZES.len() - 1)]
    }
}

/// Checks an address typed on the keypad: an IPv4 address with an optional port. Returns it
/// as `a.b.c.d:port`, with the default port when none was typed.
pub fn parse_address(s: &str) -> Result<String, &'static str> {
    let s = s.trim();
    if s.is_empty() {
        return Err("Type the address the phone's Sync with Kobo screen shows.");
    }
    let (host, port) = match s.split_once(':') {
        Some((h, p)) => (h, Some(p)),
        None => (s, None),
    };
    let parts: Vec<&str> = host.split('.').collect();
    let ok = parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.bytes().all(|b| b.is_ascii_digit()) && p.parse::<u16>().is_ok_and(|n| n <= 255));
    if !ok {
        return Err("An address is four numbers from 0 to 255 with dots between, like 192.168.1.20.");
    }
    let port = match port {
        None => DEFAULT_PORT,
        Some(p) => match p.parse::<u16>() {
            Ok(n) if n > 0 && !p.starts_with('0') => n,
            _ => return Err("The port after ':' is a number from 1 to 65535."),
        },
    };
    let host: Vec<String> = parts.iter().map(|p| p.parse::<u16>().unwrap().to_string()).collect();
    Ok(format!("{}:{port}", host.join(".")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses() {
        assert_eq!(parse_address("192.168.1.20"), Ok("192.168.1.20:47824".into()));
        assert_eq!(parse_address("10.0.0.7:8080"), Ok("10.0.0.7:8080".into()));
        assert_eq!(parse_address("010.0.0.7"), Ok("10.0.0.7:47824".into()));
        for bad in ["", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1..2.3", "1.2.3.4:", "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:1:2", "1.2.3.4:080"] {
            assert!(parse_address(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn round_trip() {
        let dir = std::env::temp_dir().join(format!("mi-settings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("settings.json");
        assert_eq!(Settings::load(&path), Settings::default());
        let s = Settings { phone_address: Some("1.2.3.4:5".into()), invert_buttons: true, orientation: Some(Orientation::Landscape), text_size: 4, brightness: Some(40), warmth: Some(70) };
        s.save(&path).unwrap();
        assert_eq!(Settings::load(&path), s);
        std::fs::write(&path, br#"{"textSize": 99, "future": 1, "orientation": "portrait"}"#).unwrap();
        let s = Settings::load(&path);
        assert_eq!((s.text_size, s.orientation), (4, Some(Orientation::Portrait)));
    }

    #[test]
    fn the_old_sensor_choice_becomes_the_orientation_found_at_start() {
        let dir = std::env::temp_dir().join(format!("mi-settings-old-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("settings.json");
        // 0.2.3's default, with other settings that must survive it.
        std::fs::write(&path, br#"{"orientation": "sensor", "textSize": 3, "invertButtons": true, "warmth": 20}"#).unwrap();
        let s = Settings::load(&path);
        assert_eq!((s.orientation, s.text_size, s.invert_buttons, s.warmth), (None, 3, true, Some(20)));
        std::fs::write(&path, br#"{"orientation": "landscape"}"#).unwrap();
        assert_eq!(Settings::load(&path).orientation, Some(Orientation::Landscape));
        std::fs::write(&path, br#"{"orientation": 7, "textSize": 1}"#).unwrap();
        assert_eq!((Settings::load(&path).orientation, Settings::load(&path).text_size), (None, 1));
        // Not chosen yet: not written.
        Settings { text_size: 1, ..Default::default() }.save(&path).unwrap();
        assert!(!std::fs::read_to_string(&path).unwrap().contains("orientation"));
    }
}

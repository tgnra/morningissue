//! What the reader's Settings screen and "Aa" popover change, kept in `data/settings.json`.
//! A missing or broken file gives the defaults; unknown fields are ignored, among them the
//! typed phone address (`phoneAddress`) and relay address (`relayAddress`) that earlier
//! versions kept here.

use serde::{Deserialize, Serialize};
use std::io;
use std::path::Path;

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
        Settings { invert_buttons: false, orientation: None, text_size: DEFAULT_TEXT_SIZE, brightness: None, warmth: None }
    }
}

impl Settings {
    pub fn load(path: &Path) -> Settings {
        let mut s: Settings = std::fs::read(path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        s.text_size = s.text_size.min(TEXT_SIZES.len() - 1);
        s.brightness = s.brightness.map(|v| v.min(100));
        s.warmth = s.warmth.map(|v| v.min(100));
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let dir = std::env::temp_dir().join(format!("mi-settings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("settings.json");
        assert_eq!(Settings::load(&path), Settings::default());
        let s = Settings { invert_buttons: true, orientation: Some(Orientation::Landscape), text_size: 4, brightness: Some(40), warmth: Some(70) };
        s.save(&path).unwrap();
        assert_eq!(Settings::load(&path), s);
        std::fs::write(&path, br#"{"textSize": 99, "future": 1, "orientation": "portrait"}"#).unwrap();
        let s = Settings::load(&path);
        assert_eq!((s.text_size, s.orientation), (4, Some(Orientation::Portrait)));
        // The typed addresses earlier versions kept are ignored, valid or not.
        std::fs::write(&path, br#"{"phoneAddress": "192.168.1.40:47824", "relayAddress": "https://relay.example.org", "invertButtons": true}"#).unwrap();
        assert_eq!(Settings::load(&path), Settings { invert_buttons: true, ..Default::default() });
        std::fs::write(&path, br#"{"phoneAddress": 7, "relayAddress": ["x"], "textSize": 1}"#).unwrap();
        assert_eq!(Settings::load(&path).text_size, 1);
        Settings::load(&path).save(&path).unwrap();
        assert!(!std::fs::read_to_string(&path).unwrap().contains("Address"));
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

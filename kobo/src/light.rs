//! The front light: brightness on every model that has one, warmth on models with a natural
//! light. The interfaces per model are in [`crate::profile::LightSpec`], with sources.
//!
//! Nickel keeps its own levels in `Kobo eReader.conf` (`[PowerOptions] FrontLightLevel`,
//! `ColorSetting` in kelvin). The reader starts from those, applies the levels chosen in its
//! own "Aa" popover if there are any, and on exit puts the light back to Nickel's levels, so
//! the light Nickel finds matches what Nickel believes it set.

use crate::profile::LightSpec;
use std::path::{Path, PathBuf};

pub trait Light {
    /// Whether there is a light at all.
    fn present(&self) -> bool;
    fn has_warmth(&self) -> bool;
    /// Brightness 0–100.
    fn set_brightness(&mut self, pct: u8);
    /// Warmth 0–100, 0 the coolest.
    fn set_warmth(&mut self, pct: u8);
    /// The levels to show on the sliders: (brightness, warmth).
    fn levels(&self) -> (u8, u8);
    /// Puts the light back as the reader found it.
    fn restore(&mut self);
    /// Turns the light off for sleep, keeping its level for [`Light::on`].
    fn off(&mut self) {}
    /// Turns the light back on at its level after sleep.
    fn on(&mut self) {}
}

/// No front light (Kobo Touch, Mini, Aura Edition 2, Clara BW without the light, …) or one
/// this version does not drive.
pub struct NoLight;

impl Light for NoLight {
    fn present(&self) -> bool {
        false
    }
    fn has_warmth(&self) -> bool {
        false
    }
    fn set_brightness(&mut self, _: u8) {}
    fn set_warmth(&mut self, _: u8) {}
    fn levels(&self) -> (u8, u8) {
        (0, 0)
    }
    fn restore(&mut self) {}
}

enum Brightness {
    /// `/dev/ntx_io` ioctl 241 with 0–100 (Mark 4 to 6 and the Nia).
    NtxIo,
    /// A sysfs `brightness` file whose range is 0–`max`.
    Sysfs { path: PathBuf, max: u32 },
}

struct Warmth {
    path: PathBuf,
    max: u32,
    /// The file takes 0 for the warmest (KOReader's `nl_inverted`).
    inverted: bool,
}

pub struct KoboLight {
    brightness: Brightness,
    warmth: Option<Warmth>,
    level: (u8, u8),
    /// Nickel's levels, put back on exit.
    original: (u8, u8),
}

const NTX_IO_SET_LIGHT: u32 = 241;

impl KoboLight {
    /// The light described by `spec`, if its files exist on this device. `nickel_conf` is
    /// `Kobo eReader.conf`, read for Nickel's current levels.
    pub fn open(spec: &LightSpec, nickel_conf: &Path) -> Option<KoboLight> {
        let sys = |p: &str| -> Option<(PathBuf, u32)> {
            let dir = PathBuf::from(p);
            let path = dir.join("brightness");
            if !path.exists() {
                return None;
            }
            let max = read_u32(&dir.join("max_brightness")).unwrap_or(100).max(1);
            Some((path, max))
        };
        let (brightness, warmth_probe): (Brightness, &[(&str, bool)]) = match spec {
            LightSpec::None => return None,
            LightSpec::NtxIo => (Brightness::NtxIo, &[]),
            LightSpec::Sysfs { brightness, warmth } => {
                let (path, max) = sys(brightness)?;
                (Brightness::Sysfs { path, max }, warmth)
            }
        };
        let warmth = warmth_probe.iter().find_map(|(p, inverted)| {
            let path = PathBuf::from(p);
            path.exists().then(|| Warmth { max: read_u32(&path.with_file_name("max_color")).filter(|m| *m > 0).unwrap_or(10), path, inverted: *inverted })
        });
        let (b, w) = nickel_levels(&std::fs::read_to_string(nickel_conf).unwrap_or_default());
        let level = (b.unwrap_or(50), w.unwrap_or(50));
        Some(KoboLight { brightness, warmth, level, original: level })
    }

    fn write_brightness(&self, pct: u8) {
        match &self.brightness {
            Brightness::NtxIo => {
                if let Ok(f) = std::fs::OpenOptions::new().write(true).open("/dev/ntx_io") {
                    use std::os::fd::AsRawFd;
                    let r = unsafe { libc::ioctl(f.as_raw_fd(), NTX_IO_SET_LIGHT as _, pct as libc::c_ulong) };
                    if r < 0 {
                        eprintln!("light: ntx_io: {}", std::io::Error::last_os_error());
                    }
                }
            }
            Brightness::Sysfs { path, max } => {
                let v = (pct as u32 * max + 50) / 100;
                if let Err(e) = std::fs::write(path, v.to_string()) {
                    eprintln!("light: {}: {e}", path.display());
                }
            }
        }
    }

    fn write_warmth(&self, pct: u8) {
        if let Some(w) = &self.warmth {
            let mut v = (pct as u32 * w.max + 50) / 100;
            if w.inverted {
                v = w.max - v;
            }
            if let Err(e) = std::fs::write(&w.path, v.to_string()) {
                eprintln!("light: {}: {e}", w.path.display());
            }
        }
    }
}

impl Light for KoboLight {
    fn present(&self) -> bool {
        true
    }
    fn has_warmth(&self) -> bool {
        self.warmth.is_some()
    }
    fn set_brightness(&mut self, pct: u8) {
        let pct = pct.min(100);
        self.level.0 = pct;
        self.write_brightness(pct);
    }
    fn set_warmth(&mut self, pct: u8) {
        let pct = pct.min(100);
        self.level.1 = pct;
        self.write_warmth(pct);
    }
    fn levels(&self) -> (u8, u8) {
        self.level
    }
    fn off(&mut self) {
        self.write_brightness(0);
    }
    fn on(&mut self) {
        self.write_brightness(self.level.0);
    }
    fn restore(&mut self) {
        if self.level == self.original {
            return;
        }
        if self.warmth.is_some() && self.level.1 != self.original.1 {
            self.write_warmth(self.original.1);
        }
        if self.level.0 != self.original.0 {
            self.write_brightness(self.original.0);
        }
        self.level = self.original;
    }
}

fn read_u32(p: &Path) -> Option<u32> {
    std::fs::read_to_string(p).ok()?.trim().parse().ok()
}

/// Nickel's brightness (percent) and warmth (percent, 0 coolest) from `Kobo eReader.conf`:
/// `FrontLightLevel` 0–100, `FrontLightState=false` for a light turned off, and
/// `ColorSetting` in kelvin from 1500 (warmest) to 6400, as KOReader converts it.
pub fn nickel_levels(conf: &str) -> (Option<u8>, Option<u8>) {
    let mut in_power = false;
    let (mut level, mut on, mut kelvin) = (None, true, None);
    for line in conf.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_power = line == "[PowerOptions]";
            continue;
        }
        if !in_power {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            match k.trim() {
                "FrontLightLevel" => level = v.trim().parse::<f32>().ok().map(|x| x.clamp(0.0, 100.0).round() as u8),
                "FrontLightState" => on = v.trim() != "false",
                "ColorSetting" => kelvin = v.trim().parse::<f32>().ok(),
                _ => {}
            }
        }
    }
    let level = level.map(|l| if on { l } else { 0 });
    let warmth = kelvin.map(|k| (100.0 - (k.clamp(1500.0, 6400.0) - 1500.0) / 49.0).round().clamp(0.0, 100.0) as u8);
    (level, warmth)
}

/// A light that only remembers, for the desktop and tests.
#[derive(Default)]
pub struct FakeLight {
    pub warmth: bool,
    pub level: (u8, u8),
    pub original: (u8, u8),
    /// Every write: ('b' or 'w', value).
    pub writes: std::rc::Rc<std::cell::RefCell<Vec<(char, u8)>>>,
}

impl Light for FakeLight {
    fn present(&self) -> bool {
        true
    }
    fn has_warmth(&self) -> bool {
        self.warmth
    }
    fn set_brightness(&mut self, pct: u8) {
        self.level.0 = pct;
        self.writes.borrow_mut().push(('b', pct));
    }
    fn set_warmth(&mut self, pct: u8) {
        self.level.1 = pct;
        self.writes.borrow_mut().push(('w', pct));
    }
    fn levels(&self) -> (u8, u8) {
        self.level
    }
    fn off(&mut self) {
        self.writes.borrow_mut().push(('b', 0));
    }
    fn on(&mut self) {
        let b = self.level.0;
        self.writes.borrow_mut().push(('b', b));
    }
    fn restore(&mut self) {
        let o = self.original;
        self.set_brightness(o.0);
        if self.warmth {
            self.set_warmth(o.1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_nickels_levels() {
        let conf = "[Reading]\nFrontLightLevel=3\n[PowerOptions]\nAutoOffMinutes=60\nFrontLightLevel=42\nColorSetting=2725\nFrontLightState=true\n";
        assert_eq!(nickel_levels(conf), (Some(42), Some(75)));
        assert_eq!(nickel_levels("[PowerOptions]\nFrontLightLevel=42\nFrontLightState=false\nColorSetting=6400"), (Some(0), Some(0)));
        assert_eq!(nickel_levels(""), (None, None));
    }
}

//! What differs between Kobo models, by product ID. Sources for every row are in
//! `kobo/README.md` (FBInk `fbink_device_id.c`, KOReader `frontend/device/kobo/device.lua`,
//! Plato `crates/core/src/device.rs`). Only the Libra 2 has been checked on a device.

use std::path::Path;

/// How the panel is refreshed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Panel {
    /// i.MX5/6 EPDC with the NTX `mxcfb_update_data` (Mark 3 to 6): `0x4044462E`.
    MxcOld,
    /// i.MX6SLL/ULL EPDC with `dither_mode` and `quant_bit` (Mark 7 and 9): `0x4048462E`.
    Mxc7,
    /// MediaTek hwtcon (Mark 11 to 13): `0x4024462E`.
    Mtk,
    /// Allwinner B300 (Elipsa, Sage). Drawing needs ION buffers and the disp layer API; not
    /// supported in v1, so the reader refuses to start and Nickel is never stopped.
    Sunxi,
    /// Not known: probed at runtime from the framebuffer driver's name and which ioctl it accepts.
    Probe,
}

/// How the front light is driven; see [`light_for`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LightSpec {
    None,
    /// `ioctl(/dev/ntx_io, 241, percent)`.
    NtxIo,
    /// A backlight class directory with `brightness` and `max_brightness`, and the warmth
    /// files to try, each with whether 0 means warmest.
    Sysfs { brightness: &'static str, warmth: &'static [(&'static str, bool)] },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct TouchQuirk {
    /// Raw X is the screen's Y and the other way round (true on every Kobo).
    pub swap_xy: bool,
    /// After the swap, x runs right to left.
    pub mirror_x: bool,
    pub mirror_y: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Profile {
    pub id: u16,
    pub name: &'static str,
    pub codename: &'static str,
    pub panel: Panel,
    pub dpi: f32,
    /// Portrait resolution, for documentation and the desktop: the real size is always read
    /// from the framebuffer.
    pub size: (i32, i32),
    pub page_keys: bool,
    /// Has an orientation sensor.
    pub gyro: bool,
    /// How the sensor's `EV_MSC/MSC_RAW` values 0x17 (portrait, upside down), 0x18
    /// (portrait), 0x19 and 0x1a (the two landscapes) map to the reader's rotations (quarter
    /// turns clockwise: rotation 1 puts the top of the page along the panel's right edge).
    /// None where the mapping has not been checked: the sensor is then ignored.
    pub sensor: Option<SensorMap>,
    pub touch: TouchQuirk,
    /// FBInk's map from the framebuffer's `rotate` value to the canonical rotation (0 is
    /// portrait upright, 1 a quarter turn clockwise, ...).
    pub rotation_map: [u8; 4],
    /// `MXCFB_WAIT_FOR_UPDATE_COMPLETE` can be trusted; on some Mark 7 models it stalls.
    pub wait_reliable: bool,
    /// Colour (Kaleido) panel: grey output must skip the colour filter processing.
    pub kaleido: bool,
    /// How the front light is driven.
    pub light: LightSpec,
    /// Checked on a real device.
    pub verified: bool,
}

/// Rotations for the sensor values 0x17, 0x18, 0x19 and 0x1a, in that order.
pub type SensorMap = [u8; 4];

/// The Libra 2's sensor. 0x18 and 0x17 are portrait and portrait
/// upside down. 0x19 ("landscape right" in the NTX drivers, KOReader's "rotated clockwise")
/// means the Kobo was turned a quarter clockwise, so its left edge is now on top and the
/// page's top must run along the panel's left edge: rotation 3. 0x1a is the other way:
/// rotation 1. 0.2.3 had the two landscapes the other way round, and on the device the page
/// was drawn upside down in landscape; this mapping has not yet been run on the device.
pub const LIBRA2_SENSOR: SensorMap = [2, 0, 3, 1];

/// The rotation for a raw sensor value, if it is one of the four orientations (0x1b and 0x1c,
/// face up and face down, are not).
pub fn sensor_rotation(map: &SensorMap, raw: i32) -> Option<u8> {
    match raw {
        0x17..=0x1a => Some(map[(raw - 0x17) as usize]),
        _ => None,
    }
}

const SWAP_MX: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: true, mirror_y: false };
const SWAP: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: false, mirror_y: false };
const SWAP_MY: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: false, mirror_y: true };

#[allow(clippy::too_many_arguments)]
const fn p(id: u16, name: &'static str, codename: &'static str, panel: Panel, dpi: f32, size: (i32, i32), page_keys: bool, gyro: bool, touch: TouchQuirk, rotation_map: [u8; 4], wait_reliable: bool) -> Profile {
    Profile { id, name, codename, panel, dpi, size, page_keys, gyro, sensor: None, touch, rotation_map, wait_reliable, kaleido: false, light: LightSpec::None, verified: false }
}

/// Every model NickelMenu 0.6 runs on (firmware 4.6 or newer), which is every Kobo from the
/// Touch on.
pub fn table() -> Vec<Profile> {
    use Panel::*;
    let mut t = vec![
        p(310, "Kobo Touch", "trilogy", MxcOld, 167.0, (600, 800), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(320, "Kobo Touch C", "trilogy", MxcOld, 167.0, (600, 800), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(340, "Kobo Mini", "pixie", MxcOld, 200.0, (600, 800), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(330, "Kobo Glo", "kraken", MxcOld, 212.0, (758, 1024), false, false, SWAP_MX, [3, 2, 1, 0], true),
        p(350, "Kobo Aura HD", "dragon", MxcOld, 265.0, (1080, 1440), false, false, SWAP_MX, [1, 0, 3, 2], true),
        p(360, "Kobo Aura", "phoenix", MxcOld, 212.0, (758, 1024), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(370, "Kobo Aura H2O", "dahlia", MxcOld, 265.0, (1080, 1440), false, false, SWAP_MX, [1, 0, 3, 2], true),
        p(371, "Kobo Glo HD", "alyssum", MxcOld, 300.0, (1072, 1448), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(372, "Kobo Touch 2.0", "pika", MxcOld, 167.0, (600, 800), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(373, "Kobo Aura One", "daylight", MxcOld, 300.0, (1404, 1872), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(381, "Kobo Aura One Limited Edition", "daylight", MxcOld, 300.0, (1404, 1872), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(374, "Kobo Aura H2O Edition 2", "snow", MxcOld, 265.0, (1080, 1440), false, false, SWAP, [3, 0, 1, 2], true),
        p(378, "Kobo Aura H2O Edition 2", "snow", Mxc7, 265.0, (1080, 1440), false, false, SWAP_MX, [3, 0, 1, 2], true),
        p(375, "Kobo Aura Edition 2", "star", MxcOld, 212.0, (758, 1024), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(379, "Kobo Aura Edition 2", "star", Mxc7, 212.0, (758, 1024), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(376, "Kobo Clara HD", "nova", Mxc7, 300.0, (1072, 1448), false, false, SWAP_MX, [1, 2, 3, 0], true),
        p(377, "Kobo Forma", "frost", Mxc7, 300.0, (1440, 1920), true, true, SWAP_MX, [3, 2, 1, 0], true),
        p(380, "Kobo Forma 32GB", "frost", Mxc7, 300.0, (1440, 1920), true, true, SWAP_MX, [3, 2, 1, 0], true),
        p(384, "Kobo Libra H2O", "storm", Mxc7, 300.0, (1264, 1680), true, true, SWAP_MX, [0, 1, 2, 3], false),
        p(382, "Kobo Nia", "luna", Mxc7, 212.0, (758, 1024), false, false, SWAP_MX, [1, 2, 3, 0], false),
        p(386, "Kobo Clara 2E", "goldfinch", Mxc7, 300.0, (1072, 1448), false, false, SWAP_MX, [3, 2, 1, 0], false),
        p(388, "Kobo Libra 2", "io", Mxc7, 300.0, (1264, 1680), true, true, SWAP, [1, 0, 3, 2], false),
        p(387, "Kobo Elipsa", "europa", Sunxi, 227.0, (1404, 1872), false, true, SWAP_MX, [0, 1, 2, 3], true),
        p(383, "Kobo Sage", "cadmus", Sunxi, 300.0, (1440, 1920), true, true, SWAP_MX, [0, 1, 2, 3], true),
        p(389, "Kobo Elipsa 2E", "condor", Mtk, 227.0, (1404, 1872), false, true, SWAP_MY, [1, 0, 3, 2], true),
        p(391, "Kobo Clara BW", "spaBW", Mtk, 300.0, (1072, 1448), false, false, SWAP_MX, [3, 2, 1, 0], true),
        p(395, "Kobo Clara BW", "spaBWTPV", Mtk, 300.0, (1072, 1448), false, false, SWAP_MX, [3, 2, 1, 0], true),
        p(393, "Kobo Clara Colour", "spaColour", Mtk, 300.0, (1072, 1448), false, false, SWAP_MX, [3, 2, 1, 0], true),
        p(390, "Kobo Libra Colour", "monza", Mtk, 300.0, (1264, 1680), true, true, SWAP_MY, [1, 0, 3, 2], true),
    ];
    for prof in &mut t {
        match prof.id {
            388 => {
                prof.verified = true;
                // The landscape values were swapped in 0.2.3; see LIBRA2_SENSOR.
                prof.sensor = Some(LIBRA2_SENSOR);
            }
            390 | 393 => prof.kaleido = true,
            _ => {}
        }
        prof.light = light_for(prof.id);
    }
    t
}

/// The front light's interface, by product ID.
///
/// Sources: KOReader `frontend/device/kobo/device.lua` (`frontlight_settings`, the
/// `ntx_io` 241 ioctl, `nl_inverted`), `frontend/device/sysfs_light.lua`, koreader-base
/// `ffi/kobolight.lua`, and Plato `crates/core/src/frontlight/`. Both projects probe the warmth
/// file at run time because one model ships with different LED drivers (the Libra 2 has an
/// AW99703 or an LM3630A), so the warmth paths are a list tried in order.
///
/// | Models | Brightness | Warmth | Confidence |
/// |---|---|---|---|
/// | Libra 2 | `mxc_msp430.0/brightness` 0–100 | `aw99703-bl_FL1/color` or `lm3630a_led/color`, 0–10, inverted | high (both sources agree), unverified |
/// | Clara HD, Libra H2O, Clara 2E, Elipsa 2E, Clara BW/Colour, Libra Colour | the same | the same probe | medium |
/// | Forma | the same | `tlc5947_bl/color` 0–10, inverted | medium |
/// | Glo, Aura HD, Aura, H2O, Glo HD, Aura 2, Nia | `ntx_io` ioctl 241, 0–100 | none | medium |
/// | Aura One, Aura H2O 2 | the white LM3630A channel only; warmth needs a software mix of red and green channels, not done | none | low |
/// | Touch, Mini, Touch 2.0 | no light | | high |
pub fn light_for(id: u16) -> LightSpec {
    const MSP430: &str = "/sys/class/backlight/mxc_msp430.0";
    const AW: (&str, bool) = ("/sys/class/leds/aw99703-bl_FL1/color", true);
    const LM: (&str, bool) = ("/sys/class/backlight/lm3630a_led/color", true);
    const TLC: (&str, bool) = ("/sys/class/backlight/tlc5947_bl/color", true);
    match id {
        310 | 320 | 340 | 372 => LightSpec::None,
        330 | 350 | 360 | 370 | 371 | 375 | 379 | 382 => LightSpec::NtxIo,
        373 | 381 => LightSpec::Sysfs { brightness: "/sys/class/backlight/lm3630a_led1b", warmth: &[] },
        374 | 378 => LightSpec::Sysfs { brightness: "/sys/class/backlight/lm3630a_ledb", warmth: &[] },
        377 | 380 => LightSpec::Sysfs { brightness: MSP430, warmth: &[TLC, AW, LM] },
        // The Sage's AW99703 is not inverted; it cannot be drawn on in v1 anyway.
        383 => LightSpec::Sysfs { brightness: MSP430, warmth: &[("/sys/class/leds/aw99703-bl_FL1/color", false)] },
        _ => LightSpec::Sysfs { brightness: MSP430, warmth: &[AW, LM, TLC] },
    }
}

/// The safe profile for a product ID nobody has listed: the panel driver is probed, touch
/// uses the commonest transform, the density is guessed from the resolution, and the
/// sensor is ignored (manual rotation still works).
pub fn generic(id: u16) -> Profile {
    let mut g = p(id, "Kobo (unknown model)", "unknown", Panel::Probe, 0.0, (0, 0), true, false, SWAP_MX, [0, 1, 2, 3], false);
    // Every recent model: probed, and left alone when the files are not there.
    g.light = light_for(0);
    g
}

pub fn by_id(id: u16) -> Profile {
    table().into_iter().find(|p| p.id == id).unwrap_or_else(|| generic(id))
}

/// The product ID from `/mnt/onboard/.kobo/version`: its last three characters (FBInk and
/// KOReader both read it so). Falls back to Nickel's `MODEL_NUMBER`, then the codename in
/// `PRODUCT`.
pub fn detect(version_file: &Path) -> Profile {
    if let Ok(s) = std::fs::read_to_string(version_file) {
        if let Some(id) = parse_version(&s) {
            return by_id(id);
        }
    }
    if let Some(id) = std::env::var("MODEL_NUMBER").ok().and_then(|v| v.trim().parse().ok()) {
        return by_id(id);
    }
    if let Ok(code) = std::env::var("PRODUCT") {
        if let Some(p) = table().into_iter().find(|p| p.codename.eq_ignore_ascii_case(code.trim())) {
            return p;
        }
    }
    generic(0)
}

pub fn parse_version(s: &str) -> Option<u16> {
    let line = s.lines().next()?.trim();
    let last = line.rsplit(',').next()?;
    let tail = last.get(last.len().checked_sub(3)?..)?;
    tail.parse().ok()
}

/// A density for a resolution when the model is unknown.
pub fn guess_dpi(w: i32, h: i32) -> f32 {
    match w.min(h) {
        0..=600 => 167.0,
        601..=800 => 212.0,
        1080 => 265.0,
        1404 if w.max(h) == 1872 => 227.0,
        _ => 300.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_version_file() {
        assert_eq!(parse_version("N418000000000,4.1.15,4.38.23697,4.1.15,4.1.15,00000000-0000-0000-0000-000000000388"), Some(388));
        assert_eq!(by_id(388).name, "Kobo Libra 2");
        assert!(by_id(388).verified);
        assert_eq!(by_id(999).panel, Panel::Probe);
        assert_eq!(parse_version(""), None);
        let ids: Vec<u16> = table().iter().map(|p| p.id).collect();
        let mut dedup = ids.clone();
        dedup.sort();
        dedup.dedup();
        assert_eq!(ids.len(), dedup.len());
    }

    #[test]
    fn libra2_landscape_is_the_right_way_up() {
        let map = by_id(388).sensor.expect("the Libra 2 follows its sensor");
        // Portrait and portrait upside down.
        assert_eq!(sensor_rotation(&map, 0x18), Some(0));
        assert_eq!(sensor_rotation(&map, 0x17), Some(2));
        // Turned a quarter clockwise (0x19): the page's top runs along the panel's left edge,
        // which is now on top; 0.2.3 drew it along the right edge, upside down.
        assert_eq!(sensor_rotation(&map, 0x19), Some(3));
        assert_eq!(crate::device::canvas_to_base((1264, 1680), 3, 0, 0), (0, 1679), "rotation 3 starts the page's top row at the panel's bottom left");
        assert_eq!(sensor_rotation(&map, 0x1a), Some(1));
        // Face up and face down say nothing about rotation.
        assert_eq!(sensor_rotation(&map, 0x1b), None);
        assert_eq!(sensor_rotation(&map, 0x1c), None);
        // Models whose mapping has not been checked ignore their sensor.
        assert!(table().iter().filter(|p| p.id != 388).all(|p| p.sensor.is_none()));
    }
}

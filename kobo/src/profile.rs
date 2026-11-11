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
    /// The sensor's events are mapped from two agreeing sources, so the reader follows it.
    pub follow_sensor: bool,
    pub touch: TouchQuirk,
    /// FBInk's map from the framebuffer's `rotate` value to the canonical rotation (0 is
    /// portrait upright, 1 a quarter turn clockwise, ...).
    pub rotation_map: [u8; 4],
    /// `MXCFB_WAIT_FOR_UPDATE_COMPLETE` can be trusted; on some Mark 7 models it stalls.
    pub wait_reliable: bool,
    /// Colour (Kaleido) panel: grey output must skip the colour filter processing.
    pub kaleido: bool,
    /// Checked on a real device.
    pub verified: bool,
}

const SWAP_MX: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: true, mirror_y: false };
const SWAP: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: false, mirror_y: false };
const SWAP_MY: TouchQuirk = TouchQuirk { swap_xy: true, mirror_x: false, mirror_y: true };

#[allow(clippy::too_many_arguments)]
const fn p(id: u16, name: &'static str, codename: &'static str, panel: Panel, dpi: f32, size: (i32, i32), page_keys: bool, gyro: bool, touch: TouchQuirk, rotation_map: [u8; 4], wait_reliable: bool) -> Profile {
    Profile { id, name, codename, panel, dpi, size, page_keys, gyro, follow_sensor: false, touch, rotation_map, wait_reliable, kaleido: false, verified: false }
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
                // KOReader (UP→UR, RIGHT→CW, DOWN→UD, LEFT→CCW) and Plato's Libra 2 transform agree.
                prof.follow_sensor = true;
            }
            390 | 393 => prof.kaleido = true,
            _ => {}
        }
    }
    t
}

/// The safe profile for a product ID nobody has listed: the panel driver is probed, touch
/// uses the commonest transform, the density is guessed from the resolution, and the
/// sensor is ignored (manual rotation still works).
pub fn generic(id: u16) -> Profile {
    p(id, "Kobo (unknown model)", "unknown", Panel::Probe, 0.0, (0, 0), true, false, SWAP_MX, [0, 1, 2, 3], false)
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
}

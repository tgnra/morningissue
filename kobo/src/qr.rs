//! QR codes for pairing, drawn on the canvas in whole pixels per module with the
//! standard four-module quiet zone, so the phone's camera can read them off e-ink.

use crate::canvas::{Canvas, Rect, BLACK, WHITE};
use qrcode::{Color, EcLevel, QrCode};

/// The modules of `text` as rows of dark (true) and light, at error correction M.
pub fn modules(text: &str) -> Option<Vec<Vec<bool>>> {
    let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::M).ok()?;
    let w = code.width();
    let colors = code.to_colors();
    Some(colors.chunks(w).map(|row| row.iter().map(|c| *c == Color::Dark).collect()).collect())
}

/// The quiet zone on each side, in modules.
pub const QUIET: usize = 4;

/// Draws the code as large as fits in a `size`-pixel square centred at (`cx`, `top`),
/// quiet zone included; returns the square it covered.
pub fn draw(canvas: &mut Canvas, text: &str, cx: i32, top: i32, size: i32) -> Option<Rect> {
    let m = modules(text)?;
    let n = (m.len() + 2 * QUIET) as i32;
    let px = (size / n).max(1);
    let side = px * n;
    let r = Rect::new(cx - side / 2, top, side, side);
    canvas.fill(r, WHITE);
    for (y, row) in m.iter().enumerate() {
        for (x, dark) in row.iter().enumerate() {
            if *dark {
                canvas.fill(Rect::new(r.x + (x as i32 + QUIET as i32) * px, r.y + (y as i32 + QUIET as i32) * px, px, px), BLACK);
            }
        }
    }
    Some(r)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pairing_uri_fits_a_small_code() {
        let uri = crate::server::pair_uri(&"A".repeat(43), "b7e2c0de-0000-4000-8000-000000000000", "Kobo Libra 2", 1264);
        let m = modules(&uri).unwrap();
        // Version 7 or less (45 modules): big modules even on a 6-inch screen.
        assert!(m.len() <= 45, "{}", m.len());
        let mut c = Canvas::new(600, 600);
        let r = draw(&mut c, &uri, 300, 0, 560).unwrap();
        assert!(r.w <= 560 && r.w >= 500);
        // The quiet zone is white and the finder pattern's corner is black.
        let px = r.w / (m.len() as i32 + 8);
        assert_eq!(c.get(r.x + px, r.y + px), WHITE);
        assert_eq!(c.get(r.x + 4 * px, r.y + 4 * px), BLACK);
    }
}

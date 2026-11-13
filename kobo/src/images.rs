//! Pictures for e-ink, as the app's E-ink mode treats them (`ImageTone.kt`): Rec. 709 luma,
//! contrast raised by 1.3, then an ordered 4×4 Bayer dither to the panel's 16 greys, so the
//! pattern stays still when a page is drawn again.

use crate::canvas::{Canvas, Rect};
use image::imageops::FilterType;
use image::GrayImage;

pub const EINK_CONTRAST: f32 = 1.3;
const LEVELS: f32 = 16.0;
const BAYER4: [[u8; 4]; 4] = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];

/// Decodes WebP, JPEG, PNG or GIF (first frame) to grey. Very large pictures are scaled down
/// as they are decoded so memory stays bounded.
pub fn decode(bytes: &[u8]) -> Option<GrayImage> {
    let reader = image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format().ok()?;
    let mut limits = image::Limits::default();
    limits.max_alloc = Some(256 << 20);
    let mut reader = reader;
    reader.limits(limits);
    let img = reader.decode().ok()?;
    let rgba = img.to_rgba32f();
    let (w, h) = rgba.dimensions();
    let mut out = GrayImage::new(w, h);
    for (x, y, p) in rgba.enumerate_pixels() {
        let [r, g, b, a] = p.0;
        // Rec. 709 luma on linear-ish values, composited over white paper.
        let l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        let l = l * a + (1.0 - a);
        out.put_pixel(x, y, image::Luma([(l.clamp(0.0, 1.0) * 255.0).round() as u8]));
    }
    Some(out)
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Fit {
    /// Fill the box, cropping the overflow (centred).
    Cover,
    /// Fit inside the box, keeping the whole picture.
    Contain,
}

/// Scales `img` for a `w`×`h` box. With [`Fit::Contain`] the result may be smaller.
pub fn fit(img: &GrayImage, w: u32, h: u32, mode: Fit) -> GrayImage {
    let (iw, ih) = img.dimensions();
    if iw == 0 || ih == 0 || w == 0 || h == 0 {
        return GrayImage::new(w.max(1), h.max(1));
    }
    let sx = w as f32 / iw as f32;
    let sy = h as f32 / ih as f32;
    match mode {
        Fit::Contain => {
            let s = sx.min(sy);
            let (nw, nh) = (((iw as f32 * s).round() as u32).max(1), ((ih as f32 * s).round() as u32).max(1));
            image::imageops::resize(img, nw, nh, FilterType::CatmullRom)
        }
        Fit::Cover => {
            let s = sx.max(sy);
            let (nw, nh) = (((iw as f32 * s).ceil() as u32).max(w), ((ih as f32 * s).ceil() as u32).max(h));
            let scaled = image::imageops::resize(img, nw, nh, FilterType::CatmullRom);
            image::imageops::crop_imm(&scaled, (nw - w) / 2, (nh - h) / 3, w, h).to_image()
        }
    }
}

/// Raises contrast around mid-grey and dithers to 16 levels into `canvas` at (x, y). The
/// threshold depends only on the canvas position, so the same picture always dithers the same.
pub fn draw_dithered(canvas: &mut Canvas, img: &GrayImage, x: i32, y: i32, contrast: f32) {
    let step = 255.0 / (LEVELS - 1.0);
    for (ix, iy, p) in img.enumerate_pixels() {
        let (cx, cy) = (x + ix as i32, y + iy as i32);
        if !canvas.clip.contains(cx, cy) {
            continue;
        }
        let v = ((p.0[0] as f32 - 127.5) * contrast + 127.5).clamp(0.0, 255.0);
        let level = v / step;
        let base = level.floor();
        let frac = level - base;
        let threshold = (BAYER4[(cy & 3) as usize][(cx & 3) as usize] as f32 + 0.5) / 16.0;
        // A grey exactly on a level stays flat.
        let q = if frac > 1e-4 && frac >= threshold { base + 1.0 } else { base };
        canvas.set(cx, cy, (q * step).round().clamp(0.0, 255.0) as u8);
    }
}

/// Decodes, fits and draws a picture into `r`; returns the rectangle actually covered.
pub fn draw_into(canvas: &mut Canvas, img: &GrayImage, r: Rect, mode: Fit) -> Rect {
    let scaled = fit(img, r.w.max(1) as u32, r.h.max(1) as u32, mode);
    let (w, h) = (scaled.width() as i32, scaled.height() as i32);
    let x = r.x + (r.w - w) / 2;
    let y = r.y + (r.h - h) / 2;
    draw_dithered(canvas, &scaled, x, y, EINK_CONTRAST);
    Rect::new(x, y, w, h)
}

/// One line of a picture zip's `images.json` (`POST /api/v1/kobo/images`): the source URL,
/// the zip entry holding it, its type and its size in pixels.
#[derive(Clone, Debug, serde::Deserialize, PartialEq)]
pub struct ImageEntry {
    pub url: String,
    pub file: String,
    #[serde(default, rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub width: u32,
    #[serde(default)]
    pub height: u32,
}

/// The picture to take for a device `width` wide: one exactly that wide, else the nearest
/// wider one (to be scaled down), else the widest there is. The server prepares each picture
/// at the width asked for, or narrower when the source is, so there is usually one.
pub fn pick_image<'a>(entries: &'a [ImageEntry], url: &str, width: u32) -> Option<&'a ImageEntry> {
    let of: Vec<&ImageEntry> = entries.iter().filter(|e| e.url == url).collect();
    of.iter().find(|e| e.width == width).or_else(|| of.iter().filter(|e| e.width > width).min_by_key(|e| e.width)).or_else(|| of.iter().max_by_key(|e| e.width)).copied()
}

/// A picture prepared for a wider screen, scaled down to `width` (never up) and encoded
/// again as the same type. One that does not decode is kept as it is.
pub fn scale_down(bytes: Vec<u8>, kind: &str, width: u32) -> Vec<u8> {
    let Ok(img) = image::load_from_memory(&bytes) else { return bytes };
    if img.width() <= width || width == 0 {
        return bytes;
    }
    let h = ((img.height() as u64 * width as u64) / img.width() as u64).max(1) as u32;
    let small = img.resize_exact(width, h, FilterType::CatmullRom).to_luma8();
    let mut out = Vec::new();
    let ok = if kind.contains("png") {
        small.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).is_ok()
    } else {
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 85).encode_image(&small).is_ok()
    };
    if ok {
        out
    } else {
        bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_this_width_else_a_wider_one_else_the_widest() {
        let e = |url: &str, width: u32| ImageEntry { url: url.into(), file: format!("{url}-{width}"), kind: "image/png".into(), width, height: width / 2 };
        let all = vec![e("a", 600), e("a", 1264), e("a", 1600), e("b", 1072), e("b", 1404), e("c", 800)];
        assert_eq!(pick_image(&all, "a", 1264).unwrap().width, 1264);
        assert_eq!(pick_image(&all, "b", 1264).unwrap().width, 1404);
        assert_eq!(pick_image(&all, "c", 1264).unwrap().width, 800);
        assert!(pick_image(&all, "d", 1264).is_none());
        let parsed: Vec<ImageEntry> = serde_json::from_str(r#"[{"url":"u","file":"images/x.jpg","type":"image/jpeg","width":1264,"height":700,"extra":1}]"#).unwrap();
        assert_eq!(parsed[0], ImageEntry { url: "u".into(), file: "images/x.jpg".into(), kind: "image/jpeg".into(), width: 1264, height: 700 });
    }

    #[test]
    fn decodes_webp_png_and_dithers_to_16_levels() {
        let src = image::RgbImage::from_fn(64, 32, |x, y| image::Rgb([(x * 4) as u8, (y * 8) as u8, 128]));
        let mut webp = Vec::new();
        image::DynamicImage::ImageRgb8(src.clone()).write_to(&mut std::io::Cursor::new(&mut webp), image::ImageFormat::WebP).unwrap();
        let mut png = Vec::new();
        image::DynamicImage::ImageRgb8(src).write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).unwrap();
        let a = decode(&webp).unwrap();
        let b = decode(&png).unwrap();
        assert_eq!(a.dimensions(), (64, 32));
        assert_eq!(a, b, "lossless WebP decodes like the PNG");
        assert!(decode(b"not a picture").is_none());
        let mut c = Canvas::new(100, 100);
        draw_into(&mut c, &a, Rect::new(0, 0, 100, 100), Fit::Cover);
        assert!(c.px.iter().all(|v| v % 17 == 0), "only the panel's 16 greys");
        let flat = GrayImage::from_pixel(8, 8, image::Luma([17 * 5]));
        let mut c = Canvas::new(8, 8);
        draw_dithered(&mut c, &flat, 0, 0, 1.0);
        assert!(c.px.iter().all(|&v| v == 85), "a grey on a level is not dithered");
    }
}

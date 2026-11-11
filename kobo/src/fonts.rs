//! The app's three variable fonts (Fraunces, Newsreader, Inter), shaped and rasterised with
//! swash at any weight and optical size.

use crate::canvas::Canvas;
use std::cell::RefCell;
use std::collections::HashMap;
use std::io;
use std::path::Path;
use std::rc::Rc;
use swash::scale::{image::Image, Render, ScaleContext, Source};
use swash::shape::ShapeContext;
use swash::zeno::{Format, Vector};
use swash::FontRef;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Family {
    /// Fraunces: headlines and the masthead.
    Headline,
    /// Newsreader: standfirsts and article text.
    Body,
    /// Inter: bylines, labels, buttons.
    Label,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Style {
    pub family: Family,
    pub weight: f32,
    pub italic: bool,
    /// Pixels per em.
    pub size: f32,
    /// Letter-spacing in ems.
    pub tracking: f32,
    pub color: u8,
}

impl Style {
    pub fn new(family: Family, weight: f32, size: f32) -> Style {
        Style { family, weight, italic: false, size, tracking: 0.0, color: 0 }
    }
    pub fn italic(mut self) -> Style {
        self.italic = true;
        self
    }
    pub fn weight(mut self, w: f32) -> Style {
        self.weight = w;
        self
    }
    pub fn size(mut self, s: f32) -> Style {
        self.size = s;
        self
    }
    pub fn tracking(mut self, t: f32) -> Style {
        self.tracking = t;
        self
    }
    pub fn color(mut self, c: u8) -> Style {
        self.color = c;
        self
    }

    /// Optical size in points, from the pixel size and the screen's density. Fraunces and
    /// Newsreader draw finer details at large sizes and sturdier ones at small sizes.
    fn opsz(&self, dpi: f32) -> f32 {
        self.size * 72.0 / dpi
    }

    fn key(&self, dpi: f32) -> StyleKey {
        StyleKey {
            family: self.family,
            italic: self.italic,
            weight: self.weight.round() as u16,
            size: (self.size * 16.0).round() as u32,
            opsz: (self.opsz(dpi) * 4.0).round() as u16,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct StyleKey {
    family: Family,
    italic: bool,
    weight: u16,
    size: u32,
    opsz: u16,
}

#[derive(Clone, Copy, Debug)]
pub struct ShapedGlyph {
    pub id: u16,
    /// Pen position from the start of the run.
    pub x: f32,
    pub y: f32,
}

#[derive(Debug)]
pub struct Shaped {
    pub glyphs: Vec<ShapedGlyph>,
    pub width: f32,
}

#[derive(Clone, Copy, Debug)]
pub struct LineMetrics {
    pub ascent: f32,
    pub descent: f32,
    pub cap_height: f32,
    pub x_height: f32,
}

#[derive(Hash, PartialEq, Eq)]
struct GlyphKey {
    style: StyleKey,
    id: u16,
    subpx: u8,
}

/// Font files and caches. One per process; not thread-safe.
pub struct Fonts {
    faces: Vec<FontRef<'static>>,
    pub dpi: f32,
    shape_ctx: RefCell<ShapeContext>,
    scale_ctx: RefCell<ScaleContext>,
    shaped: RefCell<HashMap<(String, StyleKey), Rc<Shaped>>>,
    glyphs: RefCell<HashMap<GlyphKey, Option<Rc<Image>>>>,
}

pub const FONT_FILES: [&str; 5] = ["fraunces.ttf", "fraunces_italic.ttf", "newsreader.ttf", "newsreader_italic.ttf", "inter.ttf"];

impl Fonts {
    /// Loads the five font files from `dir`. The font data lives for the rest of the process.
    pub fn load(dir: &Path, dpi: f32) -> io::Result<Fonts> {
        let mut faces = Vec::new();
        for name in FONT_FILES {
            let data: &'static [u8] = Box::leak(std::fs::read(dir.join(name)).map_err(|e| io::Error::new(e.kind(), format!("{name}: {e}")))?.into_boxed_slice());
            faces.push(FontRef::from_index(data, 0).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("{name} is not a font")))?);
        }
        Ok(Fonts {
            faces,
            dpi,
            shape_ctx: RefCell::new(ShapeContext::new()),
            scale_ctx: RefCell::new(ScaleContext::new()),
            shaped: RefCell::new(HashMap::new()),
            glyphs: RefCell::new(HashMap::new()),
        })
    }

    /// Drops cached glyphs, e.g. after the density changes.
    pub fn set_dpi(&mut self, dpi: f32) {
        self.dpi = dpi;
        self.shaped.borrow_mut().clear();
        self.glyphs.borrow_mut().clear();
    }

    fn face(&self, s: &Style) -> FontRef<'static> {
        self.faces[match (s.family, s.italic) {
            (Family::Headline, false) => 0,
            (Family::Headline, true) => 1,
            (Family::Body, false) => 2,
            (Family::Body, true) => 3,
            (Family::Label, _) => 4,
        }]
    }

    fn variations(&self, s: &Style) -> [(&'static str, f32); 2] {
        // Each font clamps to its own axis range.
        [("wght", s.weight), ("opsz", s.opsz(self.dpi))]
    }

    pub fn metrics(&self, s: &Style) -> LineMetrics {
        let face = self.face(s);
        let mut ctx = self.shape_ctx.borrow_mut();
        let shaper = ctx.builder(face).size(s.size).variations(self.variations(s)).build();
        let m = shaper.metrics();
        LineMetrics { ascent: m.ascent, descent: m.descent, cap_height: m.cap_height, x_height: m.x_height }
    }

    /// Shapes one run of text in one style (kerning and ligatures on), cached.
    pub fn shape(&self, text: &str, s: &Style) -> Rc<Shaped> {
        let key = (text.to_string(), s.key(self.dpi));
        if let Some(hit) = self.shaped.borrow().get(&key) {
            return hit.clone();
        }
        let face = self.face(s);
        let mut glyphs = Vec::new();
        let mut pen = 0.0f32;
        let track = s.tracking * s.size;
        {
            let mut ctx = self.shape_ctx.borrow_mut();
            let liga = if s.tracking.abs() > 0.02 { 0 } else { 1 };
            let mut shaper = ctx.builder(face).size(s.size).variations(self.variations(s)).features([("kern", 1u16), ("liga", liga), ("clig", liga)]).build();
            shaper.add_str(text);
            shaper.shape_with(|cluster| {
                for g in cluster.glyphs {
                    glyphs.push(ShapedGlyph { id: g.id, x: pen + g.x, y: g.y });
                    pen += g.advance;
                }
                if !cluster.glyphs.is_empty() {
                    pen += track;
                }
            });
        }
        if track != 0.0 && !glyphs.is_empty() {
            pen -= track;
        }
        let shaped = Rc::new(Shaped { glyphs, width: pen });
        let mut cache = self.shaped.borrow_mut();
        if cache.len() > 20_000 {
            cache.clear();
        }
        cache.insert(key, shaped.clone());
        shaped
    }

    pub fn width(&self, text: &str, s: &Style) -> f32 {
        self.shape(text, s).width
    }

    /// Whether every character of `text` has a glyph in the style's font.
    pub fn covers(&self, text: &str, s: &Style) -> bool {
        let cm = self.face(s).charmap();
        text.chars().all(|c| c.is_whitespace() || cm.map(c) != 0)
    }

    fn glyph(&self, s: &Style, id: u16, subpx: u8) -> Option<Rc<Image>> {
        let key = GlyphKey { style: s.key(self.dpi), id, subpx };
        if let Some(hit) = self.glyphs.borrow().get(&key) {
            return hit.clone();
        }
        let face = self.face(s);
        let img = {
            let mut ctx = self.scale_ctx.borrow_mut();
            let mut scaler = ctx.builder(face).size(s.size).hint(false).variations(self.variations(s)).build();
            Render::new(&[Source::Outline]).format(Format::Alpha).offset(Vector::new(subpx as f32 / 4.0, 0.0)).render(&mut scaler, id).map(Rc::new)
        };
        let mut cache = self.glyphs.borrow_mut();
        if cache.len() > 8_000 {
            cache.clear();
        }
        cache.insert(key, img.clone());
        img
    }

    /// Draws a shaped run with its pen starting at (x, baseline).
    pub fn draw(&self, canvas: &mut Canvas, shaped: &Shaped, s: &Style, x: f32, baseline: f32) {
        for g in &shaped.glyphs {
            let gx = x + g.x;
            let gy = baseline - g.y;
            let ix = gx.floor();
            let subpx = ((gx - ix) * 4.0).floor().clamp(0.0, 3.0) as u8;
            let Some(img) = self.glyph(s, g.id, subpx) else { continue };
            let p = img.placement;
            let ox = ix as i32 + p.left;
            let oy = gy.round() as i32 - p.top;
            for row in 0..p.height as i32 {
                for col in 0..p.width as i32 {
                    let a = img.data[(row * p.width as i32 + col) as usize];
                    canvas.blend(ox + col, oy + row, s.color, a);
                }
            }
        }
    }

    /// Shapes and draws `text` in one go; returns its width.
    pub fn text(&self, canvas: &mut Canvas, text: &str, s: &Style, x: f32, baseline: f32) -> f32 {
        let shaped = self.shape(text, s);
        self.draw(canvas, &shaped, s, x, baseline);
        shaped.width
    }
}

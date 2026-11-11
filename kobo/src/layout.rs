//! Physical units, the type scale, and pages built from stacked pieces.
//!
//! Every size is given in points (1/72 inch) or inches and turned into pixels with the
//! screen's density, so type and touch targets are the same physical size on a 6" 212 dpi
//! Kobo and a 10.3" 227 dpi Elipsa.

use crate::canvas::{Canvas, Rect};
use crate::fonts::{Family, Fonts, Style};
use crate::images::{self, Fit};
use crate::text::Para;
use image::GrayImage;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Metrics {
    pub w: i32,
    pub h: i32,
    pub dpi: f32,
}

impl Metrics {
    pub fn pt(&self, v: f32) -> f32 {
        v * self.dpi / 72.0
    }
    pub fn inch(&self, v: f32) -> f32 {
        v * self.dpi
    }
    pub fn landscape(&self) -> bool {
        self.w > self.h
    }
    /// Side margin: a quarter inch, a little more on big screens.
    pub fn margin(&self) -> f32 {
        self.inch(0.22).max(self.w as f32 * 0.045)
    }
    /// Height of the top and bottom bars; also the minimum touch target (about 8 mm).
    pub fn bar(&self) -> f32 {
        self.inch(0.33)
    }
    pub fn content(&self) -> Rect {
        let m = self.margin().round() as i32;
        let b = self.bar().round() as i32;
        Rect::new(m, b, self.w - 2 * m, self.h - 2 * b)
    }
    /// Number of grid columns for story cells: one per 1.75" of measure, 1 to 4.
    pub fn columns(&self) -> usize {
        let w = self.content().w as f32 / self.dpi;
        ((w / 1.75).floor() as usize).clamp(1, 4)
    }
    /// The widest comfortable measure for running text: about 70 characters.
    pub fn measure(&self, body: &Style) -> f32 {
        (body.size * 34.0).min(self.content().w as f32)
    }
}

/// E-ink weights: body text a step heavier than on a phone, as the app's E-ink mode sets it.
pub struct Type {
    pub masthead: Style,
    pub lead: Style,
    pub headline: Style,
    pub headline_small: Style,
    pub standfirst: Style,
    pub excerpt: Style,
    pub reader: Style,
    pub reader_heading: Style,
    pub byline: Style,
    pub label: Style,
    pub section: Style,
    pub caption: Style,
    pub ui: Style,
}

impl Type {
    pub fn new(m: &Metrics) -> Type {
        let s = |f, w, pt| Style::new(f, w, m.pt(pt));
        Type {
            masthead: s(Family::Headline, 800.0, 24.0).tracking(-0.01),
            lead: s(Family::Headline, 700.0, 22.0).tracking(-0.012),
            headline: s(Family::Headline, 620.0, 14.5).tracking(-0.008),
            headline_small: s(Family::Headline, 620.0, 12.0),
            standfirst: s(Family::Body, 480.0, 10.5),
            excerpt: s(Family::Body, 480.0, 9.5),
            reader: s(Family::Body, 470.0, 10.5),
            reader_heading: s(Family::Headline, 640.0, 13.0),
            byline: s(Family::Label, 560.0, 7.0).tracking(0.01),
            label: s(Family::Label, 600.0, 8.5),
            section: s(Family::Label, 700.0, 7.0).tracking(0.12),
            caption: s(Family::Label, 450.0, 7.5),
            ui: s(Family::Label, 600.0, 8.5),
        }
    }
}

/// What a tap on a region does.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    OpenEdition(usize),
    OpenArticle(i64),
    Exit,
    Rotate,
    Back,
    Next,
    Prev,
    Like,
    Save,
}

#[derive(Clone, Debug)]
pub enum El {
    Text { para: Rc<Para>, x: f32, y: f32 },
    Fill { r: Rect, v: u8 },
    Frame { r: Rect, t: i32, v: u8 },
    Dotted { x: i32, y: i32, w: i32 },
    Image { url: String, r: Rect, fit: Fit },
    Tick { x: f32, y: f32, size: f32, v: u8 },
    /// Preformatted text set in fixed cells.
    Mono { lines: Vec<String>, x: f32, y: f32, cell: f32, line_height: f32, style: Style },
}

impl El {
    /// The element moved down by `dy`.
    pub fn shifted(&self, dy: f32) -> El {
        let mv = |r: &Rect| Rect::new(r.x, r.y + dy.round() as i32, r.w, r.h);
        match self {
            El::Text { para, x, y } => El::Text { para: para.clone(), x: *x, y: y + dy },
            El::Fill { r, v } => El::Fill { r: mv(r), v: *v },
            El::Frame { r, t, v } => El::Frame { r: mv(r), t: *t, v: *v },
            El::Dotted { x, y, w } => El::Dotted { x: *x, y: y + dy.round() as i32, w: *w },
            El::Image { url, r, fit } => El::Image { url: url.clone(), r: mv(r), fit: *fit },
            El::Tick { x, y, size, v } => El::Tick { x: *x, y: y + dy, size: *size, v: *v },
            El::Mono { lines, x, y, cell, line_height, style } => El::Mono { lines: lines.clone(), x: *x, y: y + dy, cell: *cell, line_height: *line_height, style: *style },
        }
    }
}

/// A slice of a page that is never split across screens: a story, a grid row, a line of text.
#[derive(Clone, Debug, Default)]
pub struct Piece {
    pub h: f32,
    /// Positioned relative to the piece's top.
    pub els: Vec<El>,
    pub hits: Vec<(Rect, Action)>,
    /// Space above, dropped at the top of a screen.
    pub gap: f32,
    /// Keep on the same screen as the next piece (a heading, a paragraph's first line).
    pub keep: bool,
}

impl Piece {
    pub fn new(h: f32) -> Piece {
        Piece { h, ..Default::default() }
    }
    pub fn gap(mut self, g: f32) -> Piece {
        self.gap = g;
        self
    }
    pub fn keep(mut self) -> Piece {
        self.keep = true;
        self
    }
}

#[derive(Clone, Debug, Default)]
pub struct Screen {
    pub els: Vec<El>,
    pub hits: Vec<(Rect, Action)>,
}

/// Stacks pieces into screens of height `area.h` starting at `area.y`. A run of kept pieces
/// moves to the next screen whole when it fits on an empty one.
pub fn paginate(pieces: &[Piece], area: Rect) -> Vec<Screen> {
    let mut screens = vec![Screen::default()];
    let mut y = 0.0f32;
    let avail = area.h as f32;
    let mut i = 0;
    while i < pieces.len() {
        // The chain of pieces that must stay together, starting here.
        let mut j = i;
        let mut chain_h = 0.0;
        loop {
            chain_h += if j == i { pieces[j].h } else { pieces[j].gap + pieces[j].h };
            if !pieces[j].keep || j + 1 >= pieces.len() || chain_h > avail {
                break;
            }
            j += 1;
        }
        let gap = if y > 0.0 { pieces[i].gap } else { 0.0 };
        let fits_here = y + gap + chain_h <= avail + 0.5;
        if !fits_here && y > 0.0 && chain_h <= avail {
            screens.push(Screen::default());
            y = 0.0;
            continue;
        }
        // Place piece i (if the chain is taller than a screen, it breaks inside).
        let p = &pieces[i];
        let gap = if y > 0.0 { p.gap } else { 0.0 };
        if y > 0.0 && y + gap + p.h > avail + 0.5 {
            screens.push(Screen::default());
            y = 0.0;
            continue;
        }
        let top = area.y as f32 + y + gap;
        let s = screens.last_mut().unwrap();
        s.els.extend(p.els.iter().map(|e| e.shifted(top)));
        s.hits.extend(p.hits.iter().map(|(r, a)| (Rect::new(r.x, r.y + top.round() as i32, r.w, r.h), a.clone())));
        y += gap + p.h;
        i += 1;
    }
    if screens.len() > 1 && screens.last().is_some_and(|s| s.els.is_empty()) {
        screens.pop();
    }
    screens
}

/// Decoded pictures, kept for a few screens.
pub struct ImageCache {
    load: Box<dyn Fn(&str) -> Option<Vec<u8>>>,
    decoded: RefCell<HashMap<String, Option<Rc<GrayImage>>>>,
    dims: RefCell<HashMap<String, Option<(u32, u32)>>>,
}

impl ImageCache {
    pub fn new(load: Box<dyn Fn(&str) -> Option<Vec<u8>>>) -> ImageCache {
        ImageCache { load, decoded: RefCell::new(HashMap::new()), dims: RefCell::new(HashMap::new()) }
    }

    /// Width and height of a stored picture, from its header only. None if it is not stored
    /// or cannot be read.
    pub fn dims(&self, url: &str) -> Option<(u32, u32)> {
        if let Some(d) = self.dims.borrow().get(url) {
            return *d;
        }
        let d = (self.load)(url).and_then(|b| {
            image::ImageReader::new(std::io::Cursor::new(b)).with_guessed_format().ok()?.into_dimensions().ok()
        });
        let d = d.filter(|&(w, h)| w >= 16 && h >= 16);
        self.dims.borrow_mut().insert(url.to_string(), d);
        d
    }

    pub fn get(&self, url: &str) -> Option<Rc<GrayImage>> {
        if let Some(i) = self.decoded.borrow().get(url) {
            return i.clone();
        }
        let img = (self.load)(url).and_then(|b| images::decode(&b)).map(|mut i| {
            // Nothing is shown larger than a screen; keep memory down.
            let (w, h) = i.dimensions();
            if w.max(h) > 2400 {
                i = images::fit(&i, 2400.min(w * 2400 / w.max(h)), 2400.min(h * 2400 / w.max(h)), Fit::Contain);
            }
            Rc::new(i)
        });
        let mut d = self.decoded.borrow_mut();
        if d.len() >= 12 {
            d.clear();
        }
        d.insert(url.to_string(), img.clone());
        img
    }
}

pub fn draw(els: &[El], fonts: &Fonts, images: &ImageCache, canvas: &mut Canvas) {
    for e in els {
        match e {
            El::Text { para, x, y } => para.draw(fonts, canvas, *x, *y),
            El::Fill { r, v } => canvas.fill(*r, *v),
            El::Frame { r, t, v } => canvas.frame(*r, *t, *v),
            El::Dotted { x, y, w } => {
                let t = (fonts.dpi / 150.0).round().max(1.0) as i32;
                canvas.dotted(*x, *y, *w, t * 4, t, 90);
            }
            El::Image { url, r, fit } => {
                if let Some(img) = images.get(url) {
                    images::draw_into(canvas, &img, *r, *fit);
                }
            }
            El::Tick { x, y, size, v } => canvas.tick(*x, *y, *size, *v),
            El::Mono { lines, x, y, cell, line_height, style } => {
                let base = crate::text::baseline_in_box(fonts, style, *line_height);
                for (i, line) in lines.iter().enumerate() {
                    let by = (y + i as f32 * line_height + base).round();
                    for (ci, ch) in line.chars().enumerate() {
                        if ch.is_whitespace() {
                            continue;
                        }
                        let s = fonts.shape(&ch.to_string(), style);
                        let cx = x + ci as f32 * cell + (cell - s.width) / 2.0;
                        fonts.draw(canvas, &s, style, cx, by);
                    }
                }
            }
        }
    }
}

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
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
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
    /// The page with its bars hidden: everything but a margin all round, a little more at
    /// the foot so the last line never sits on the bezel.
    pub fn page(&self) -> Rect {
        let m = self.margin().round() as i32;
        let top = self.inch(0.26).round() as i32;
        let bottom = self.inch(0.3).round() as i32;
        Rect::new(m, top, self.w - 2 * m, self.h - top - bottom)
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
        Type::with_text_scale(m, 1.0)
    }

    /// The scale with the article text (body, headings, captions) `scale` times the base.
    pub fn with_text_scale(m: &Metrics, scale: f32) -> Type {
        let s = |f, w, pt| Style::new(f, w, m.pt(pt));
        let r = |f, w, pt: f32| Style::new(f, w, m.pt(pt * scale));
        Type {
            masthead: s(Family::Headline, 800.0, 24.0).tracking(-0.01),
            lead: s(Family::Headline, 700.0, 22.0).tracking(-0.012),
            headline: s(Family::Headline, 620.0, 14.5).tracking(-0.008),
            headline_small: s(Family::Headline, 620.0, 12.0),
            standfirst: s(Family::Body, 480.0, 10.5),
            excerpt: s(Family::Body, 480.0, 9.5),
            reader: r(Family::Body, 470.0, 10.5),
            reader_heading: r(Family::Headline, 640.0, 13.0),
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
    OpenSaved,
    OpenSettings,
    Exit,
    Back,
    Next,
    Prev,
    Like,
    Save,
    /// The "Aa" popover.
    TextAndLight,
    /// Somewhere that does nothing but must not fall through (the popover's own panel).
    Nothing,
    SetTextSize(usize),
    /// A slider from `x0` `w` wide: the value is where along it the tap fell.
    Slider { warmth: bool, x0: i32, w: i32 },
    /// Nudges a slider by this many percent.
    Nudge { warmth: bool, by: i8 },
    SyncNow,
    /// Turns Wi-Fi on, syncs, and turns it off again.
    WifiAndSync,
    WifiOff,
    PhoneAuto,
    /// Opens the keypad for a typed phone address.
    PhoneTyped,
    InvertButtons,
    /// Settings > Reset: shows the confirmation.
    Reset,
    ResetCancel,
    /// Erases everything on the next start and exits now.
    ResetConfirm,
    SetOrientation(crate::settings::Orientation),
    /// A key of the address keypad.
    KeyChar(char),
    KeyDelete,
    KeyOk,
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
    /// Drawn only when the piece is not the first on its screen (a rule between rows).
    pub divider: Vec<El>,
    /// May move below the text that follows it, to the top of the next screen.
    pub float: bool,
    /// Where in the source this piece is (block index plus the fraction of the block), so
    /// a new layout can find the same place.
    pub anchor: f32,
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
    /// The anchor of the first piece on the screen.
    pub anchor: f32,
}

/// The screen a new layout shows for the place `anchor`: the last one starting at or before it.
pub fn screen_for_anchor(screens: &[Screen], anchor: f32) -> usize {
    screens.iter().rposition(|s| s.anchor <= anchor + 1e-4).unwrap_or(0)
}

/// Stacks pieces into screens of height `area.h` starting at `area.y`. A run of kept pieces
/// moves to the next screen whole when it fits on an empty one. A floating piece (a picture)
/// that does not fit waits for the top of the next screen while the text after it fills
/// this one.
pub fn paginate(pieces: &[Piece], area: Rect) -> Vec<Screen> {
    paginate_inner(pieces, area, None).screens
}

/// [`paginate`] for pages of stories: when the last screen would hold only a scrap (under a
/// third of the screen), pieces move over from the screen before so the two are even, rather
/// than one story sitting alone at the top of an empty screen.
pub fn paginate_balanced(pieces: &[Piece], area: Rect) -> Vec<Screen> {
    let first = paginate_inner(pieces, area, None);
    let n = first.screens.len();
    let avail = area.h as f32;
    if n < 2 || first.fills[n - 1] >= avail / 3.0 {
        return first.screens;
    }
    let last_start = first.starts[n - 1];
    let prev_start = first.starts[n - 2];
    let mut best = (first.fills[n - 1].min(first.fills[n - 2]), first);
    for j in (prev_start + 1..last_start).rev() {
        if pieces[j - 1].keep {
            continue;
        }
        let t = paginate_inner(pieces, area, Some(j));
        if t.screens.len() != n {
            continue;
        }
        let even = t.fills[n - 1].min(t.fills[n - 2]);
        if even > best.0 + 0.5 {
            best = (even, t);
        }
    }
    best.1.screens
}

struct Paged {
    screens: Vec<Screen>,
    /// How much of each screen is used.
    fills: Vec<f32>,
    /// The first piece placed on each screen.
    starts: Vec<usize>,
}

/// Stacks pieces into screens; with `break_before`, that piece starts a new screen.
fn paginate_inner(pieces: &[Piece], area: Rect, break_before: Option<usize>) -> Paged {
    fn put(s: &mut Screen, p: &Piece, top: f32, first: bool) {
        if s.els.is_empty() && s.hits.is_empty() {
            s.anchor = p.anchor;
        }
        if !first {
            s.els.extend(p.divider.iter().map(|e| e.shifted(top)));
        }
        s.els.extend(p.els.iter().map(|e| e.shifted(top)));
        s.hits.extend(p.hits.iter().map(|(r, a)| (Rect::new(r.x, r.y + top.round() as i32, r.w, r.h), a.clone())));
    }
    let mut out = Paged { screens: vec![Screen::default()], fills: Vec::new(), starts: vec![0] };
    let mut y = 0.0f32;
    let avail = area.h as f32;
    let mut waiting: Option<usize> = None;
    let mut i = 0;
    let new_screen = |out: &mut Paged, y: &mut f32, waiting: &mut Option<usize>, next: usize| {
        out.fills.push(*y);
        out.screens.push(Screen::default());
        out.starts.push(next);
        *y = 0.0;
        if let Some(f) = waiting.take() {
            put(out.screens.last_mut().unwrap(), &pieces[f], area.y as f32, true);
            *y = pieces[f].h;
            *out.starts.last_mut().unwrap() = f;
        }
    };
    while i < pieces.len() {
        if break_before == Some(i) && y > 0.0 && out.starts.last() != Some(&i) {
            new_screen(&mut out, &mut y, &mut waiting, i);
            continue;
        }
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
        if !fits_here && y > 0.0 && pieces[i].float && waiting.is_none() && chain_h <= avail {
            waiting = Some(i);
            i += 1;
            continue;
        }
        if !fits_here && y > 0.0 && chain_h <= avail {
            new_screen(&mut out, &mut y, &mut waiting, i);
            continue;
        }
        // Place piece i (if the chain is taller than a screen, it breaks inside).
        let p = &pieces[i];
        let gap = if y > 0.0 { p.gap } else { 0.0 };
        if y > 0.0 && y + gap + p.h > avail + 0.5 {
            new_screen(&mut out, &mut y, &mut waiting, i);
            continue;
        }
        let top = area.y as f32 + y + gap;
        put(out.screens.last_mut().unwrap(), p, top, y <= 0.0);
        y += gap + p.h;
        i += 1;
    }
    if waiting.is_some() {
        new_screen(&mut out, &mut y, &mut waiting, pieces.len());
    }
    out.fills.push(y);
    if out.screens.len() > 1 && out.screens.last().is_some_and(|s| s.els.is_empty()) {
        out.screens.pop();
        out.fills.pop();
        out.starts.pop();
    }
    out
}

/// Decoded pictures, kept for a few screens.
pub struct ImageCache {
    load: Box<dyn Fn(&str) -> Option<Vec<u8>>>,
    decoded: RefCell<HashMap<String, Option<Rc<GrayImage>>>>,
    dims: RefCell<HashMap<String, Option<(u32, u32)>>>,
    /// A sync is running, so a picture not stored yet may still arrive.
    pub syncing: Cell<bool>,
    /// Pictures the phone said it does not have, during this sync.
    pub missing: RefCell<HashSet<String>>,
}

/// The shape a picture is given while its size is not known: 3:2, a common photo shape.
pub const UNKNOWN_ASPECT: (u32, u32) = (1500, 1000);

impl ImageCache {
    pub fn new(load: Box<dyn Fn(&str) -> Option<Vec<u8>>>) -> ImageCache {
        ImageCache { load, decoded: RefCell::new(HashMap::new()), dims: RefCell::new(HashMap::new()), syncing: Cell::new(false), missing: RefCell::new(HashSet::new()) }
    }

    /// Forgets what was known about `url`, when it has just been stored.
    pub fn forget(&self, url: &str) {
        self.dims.borrow_mut().remove(url);
        self.decoded.borrow_mut().remove(url);
    }

    /// Whether `url` may still arrive in the running sync.
    pub fn pending(&self, url: &str) -> bool {
        self.syncing.get() && !self.missing.borrow().contains(url) && self.dims(url).is_none()
    }

    /// The size to lay a picture out at: its own when stored; while it may still arrive,
    /// `hint` (the bundle's width and height) or [`UNKNOWN_ASPECT`], so the text around it
    /// does not move when it comes; None when it will not be shown.
    pub fn slot(&self, url: &str, hint: Option<(u32, u32)>) -> Option<(u32, u32)> {
        if url.is_empty() {
            return None;
        }
        if let Some(d) = self.dims(url) {
            return Some(d);
        }
        self.pending(url).then(|| hint.filter(|&(w, h)| w >= 16 && h >= 16).unwrap_or(UNKNOWN_ASPECT))
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

/// The light grey of a picture's place before it arrives.
pub const PLACEHOLDER: u8 = 238;

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
            El::Image { url, r, fit } => match images.get(url) {
                Some(img) => {
                    images::draw_into(canvas, &img, *r, *fit);
                }
                // Its place, until it arrives.
                None => canvas.fill(*r, PLACEHOLDER),
            },
            El::Tick { x, y, size, v } => canvas.tick(*x, *y, *size, *v),
            El::Mono { lines, x, y, cell, line_height, style } => {
                let base = crate::text::baseline_in_box(fonts, style, *line_height);
                for (i, line) in lines.iter().enumerate() {
                    let by = (y + i as f32 * line_height + base).round();
                    if *cell > 0.0 {
                        // Fixed cells, each character centred in its own.
                        for (ci, ch) in line.chars().enumerate() {
                            if ch.is_whitespace() {
                                continue;
                            }
                            let s = fonts.shape(&ch.to_string(), style);
                            let cx = x + ci as f32 * cell + (cell - s.width) / 2.0;
                            fonts.draw(canvas, &s, style, cx, by);
                        }
                    } else {
                        let s = fonts.shape(line, style);
                        fonts.draw(canvas, &s, style, *x, by);
                    }
                }
            }
        }
    }
}

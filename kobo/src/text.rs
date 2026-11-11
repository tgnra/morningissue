//! Rich text to lines: inline styles from the block model's spans, line breaking, justification
//! and truncation with an ellipsis.

use crate::canvas::Canvas;
use crate::fonts::{Family, Fonts, Shaped, Style};
use crate::model::{RichText, SpanStyle};
use std::rc::Rc;

/// A stretch of text in one style.
#[derive(Clone, Debug)]
pub struct Piece {
    pub text: String,
    pub style: Style,
    /// Raised by this many pixels (negative lowers), for superscripts and subscripts.
    pub shift: f32,
    pub underline: bool,
    pub strike: bool,
}

pub type Inline = Vec<Piece>;

pub fn plain(text: &str, style: Style) -> Inline {
    vec![Piece { text: text.to_string(), style, shift: 0.0, underline: false, strike: false }]
}

/// Turns the block model's `RichText` into pieces. Span offsets are UTF-16 units.
pub fn rich(rt: &RichText, base: Style) -> Inline {
    let text = &rt.text;
    // Byte offset for every UTF-16 index.
    let mut byte_at = Vec::with_capacity(text.len() + 1);
    for (b, c) in text.char_indices() {
        for _ in 0..c.len_utf16() {
            byte_at.push(b);
        }
    }
    byte_at.push(text.len());
    let to_byte = |u: usize| byte_at[u.min(byte_at.len() - 1)];
    let mut cuts: Vec<usize> = vec![0, text.len()];
    for s in &rt.spans {
        if s.start < s.end {
            cuts.push(to_byte(s.start));
            cuts.push(to_byte(s.end));
        }
    }
    cuts.sort_unstable();
    cuts.dedup();
    let mut out = Vec::new();
    for w in cuts.windows(2) {
        let (a, b) = (w[0], w[1]);
        if a >= b {
            continue;
        }
        let mut style = base;
        let mut shift = 0.0;
        let (mut underline, mut strike) = (false, false);
        for s in &rt.spans {
            if to_byte(s.start) <= a && to_byte(s.end) >= b && s.start < s.end {
                match s.style {
                    SpanStyle::Bold => style.weight = (base.weight + 250.0).min(900.0),
                    SpanStyle::Italic => style.italic = !base.italic,
                    SpanStyle::Code => {
                        style.family = Family::Label;
                        style.italic = false;
                        style.size = base.size * 0.86;
                        style.weight = base.weight.min(500.0);
                    }
                    SpanStyle::Link => underline = true,
                    SpanStyle::Strike => strike = true,
                    SpanStyle::Sup => {
                        style.size = base.size * 0.68;
                        shift = base.size * 0.34;
                    }
                    SpanStyle::Sub => {
                        style.size = base.size * 0.68;
                        shift = -base.size * 0.14;
                    }
                }
            }
        }
        out.push(Piece { text: text[a..b].to_string(), style, shift, underline, strike });
    }
    out
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Align {
    Left,
    Center,
    Right,
    Justify,
}

#[derive(Clone, Debug)]
pub struct Placed {
    pub x: f32,
    pub shaped: Rc<Shaped>,
    pub style: Style,
    pub shift: f32,
    pub underline: bool,
    pub strike: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Line {
    pub items: Vec<Placed>,
    pub width: f32,
}

#[derive(Clone, Debug)]
struct Frag {
    piece: usize,
    text: String,
}

#[derive(Clone, Debug)]
struct Word {
    frags: Vec<Frag>,
    /// The space that follows, by the piece it belongs to; None for a break with no space
    /// (after a hyphen or slash) or at the end.
    space: Option<usize>,
    hard_break: bool,
}

fn words(inline: &Inline) -> Vec<Word> {
    let mut out: Vec<Word> = Vec::new();
    let mut cur = Word { frags: Vec::new(), space: None, hard_break: false };
    let flush = |cur: &mut Word, out: &mut Vec<Word>| {
        if !cur.frags.is_empty() || cur.hard_break {
            out.push(std::mem::replace(cur, Word { frags: Vec::new(), space: None, hard_break: false }));
        }
    };
    for (pi, p) in inline.iter().enumerate() {
        let mut buf = String::new();
        for c in p.text.chars() {
            match c {
                '\n' => {
                    if !buf.is_empty() {
                        cur.frags.push(Frag { piece: pi, text: std::mem::take(&mut buf) });
                    }
                    cur.hard_break = true;
                    flush(&mut cur, &mut out);
                }
                c if c.is_whitespace() && c != '\u{a0}' && c != '\u{202f}' => {
                    if !buf.is_empty() {
                        cur.frags.push(Frag { piece: pi, text: std::mem::take(&mut buf) });
                    }
                    if !cur.frags.is_empty() {
                        cur.space = Some(pi);
                        flush(&mut cur, &mut out);
                    }
                }
                '-' | '\u{2013}' | '\u{2014}' | '/' => {
                    buf.push(c);
                    // A break opportunity after the dash, not before; never after a leading one.
                    let has_text = !cur.frags.is_empty() || buf.chars().count() > 1;
                    if has_text {
                        cur.frags.push(Frag { piece: pi, text: std::mem::take(&mut buf) });
                        flush(&mut cur, &mut out);
                    }
                }
                _ => buf.push(c),
            }
        }
        if !buf.is_empty() {
            cur.frags.push(Frag { piece: pi, text: buf });
        }
    }
    flush(&mut cur, &mut out);
    out
}

pub struct Opts {
    pub width: f32,
    pub align: Align,
    pub max_lines: Option<usize>,
    /// Indent of the first line.
    pub indent: f32,
}

impl Opts {
    pub fn new(width: f32, align: Align) -> Opts {
        Opts { width, align, max_lines: None, indent: 0.0 }
    }
    pub fn max_lines(mut self, n: usize) -> Opts {
        self.max_lines = Some(n);
        self
    }
}

struct Measured {
    word: Word,
    width: f32,
    space: f32,
}

/// Breaks `inline` into lines no wider than `opts.width`.
pub fn break_lines(fonts: &Fonts, inline: &Inline, opts: &Opts) -> Vec<Line> {
    let measured: Vec<Measured> = words(inline)
        .into_iter()
        .map(|w| {
            let width = w.frags.iter().map(|f| fonts.width(&f.text, &inline[f.piece].style)).sum();
            let space = w.space.map(|p| fonts.width(" ", &inline[p].style)).unwrap_or(0.0);
            Measured { word: w, width, space }
        })
        .collect();

    // Greedy fill. Each line is a range of words, plus whether it ends a paragraph.
    let mut lines: Vec<(Vec<Measured>, bool)> = Vec::new();
    let mut cur: Vec<Measured> = Vec::new();
    let mut x = 0.0f32;
    let limit = |n: usize| if n == 0 { opts.width - opts.indent } else { opts.width };
    for m in measured {
        let lim = limit(lines.len());
        let before = cur.last().map(|p: &Measured| p.space).unwrap_or(0.0);
        if !cur.is_empty() && x + before + m.width > lim + 0.5 {
            lines.push((std::mem::take(&mut cur), false));
            x = 0.0;
        }
        let lim = limit(lines.len());
        if cur.is_empty() && m.width > lim {
            // A word longer than the line (a URL): cut it by characters.
            for part in split_long(fonts, inline, m, lim) {
                if !cur.is_empty() {
                    lines.push((std::mem::take(&mut cur), false));
                }
                x = part.width;
                cur.push(part);
            }
        } else {
            x += cur.last().map(|p| p.space).unwrap_or(0.0) + m.width;
            let hard = m.word.hard_break;
            cur.push(m);
            if hard {
                lines.push((std::mem::take(&mut cur), true));
                x = 0.0;
            }
        }
    }
    if !cur.is_empty() {
        lines.push((cur, true));
    }
    if let Some(last) = lines.last_mut() {
        last.1 = true;
    }

    let mut truncated = false;
    if let Some(max) = opts.max_lines {
        if lines.len() > max {
            lines.truncate(max);
            truncated = true;
        }
    }

    let n = lines.len();
    let mut out = Vec::with_capacity(n);
    for (i, (ws, ends_para)) in lines.into_iter().enumerate() {
        let lim = limit(i);
        let mut ws = ws;
        let ellipsis = truncated && i == n - 1;
        if ellipsis {
            let last_style = ws.last().and_then(|m| m.word.frags.last()).map(|f| inline[f.piece].style).unwrap_or(inline[0].style);
            let ell = fonts.width("\u{2026}", &last_style);
            while ws.len() > 1 && natural(&ws) + ell > lim {
                ws.pop();
            }
        }
        let nat = natural(&ws);
        let gaps = ws.iter().take(ws.len().saturating_sub(1)).filter(|m| m.space > 0.0).count();
        let extra = (lim - nat).max(0.0);
        let (mut x, stretch) = match opts.align {
            Align::Justify if !ends_para && !ellipsis && gaps > 0 => (0.0, extra / gaps as f32),
            Align::Center => (extra / 2.0, 0.0),
            Align::Right => (extra, 0.0),
            _ => (0.0, 0.0),
        };
        if i == 0 {
            x += opts.indent;
        }
        let mut line = Line::default();
        let count = ws.len();
        for (wi, m) in ws.iter().enumerate() {
            for f in &m.word.frags {
                let p = &inline[f.piece];
                let shaped = fonts.shape(&f.text, &p.style);
                let w = shaped.width;
                line.items.push(Placed { x, shaped, style: p.style, shift: p.shift, underline: p.underline, strike: p.strike });
                x += w;
            }
            if wi + 1 < count {
                // Underline links across the spaces inside them.
                x += m.space + if m.space > 0.0 { stretch } else { 0.0 };
            }
        }
        if ellipsis {
            let st = line.items.last().map(|p| p.style).unwrap_or(inline[0].style);
            let shaped = fonts.shape("\u{2026}", &st);
            let w = shaped.width;
            line.items.push(Placed { x, shaped, style: st, shift: 0.0, underline: false, strike: false });
            x += w;
        }
        line.width = x;
        out.push(line);
    }
    out
}

fn natural(ws: &[Measured]) -> f32 {
    let mut w = 0.0;
    for (i, m) in ws.iter().enumerate() {
        w += m.width;
        if i + 1 < ws.len() {
            w += m.space;
        }
    }
    w
}

fn split_long(fonts: &Fonts, inline: &Inline, m: Measured, lim: f32) -> Vec<Measured> {
    let mut parts = Vec::new();
    let mut cur: Vec<Frag> = Vec::new();
    let mut cur_w = 0.0;
    for f in m.word.frags {
        let style = inline[f.piece].style;
        let mut buf = String::new();
        for c in f.text.chars() {
            let cw = fonts.width(&c.to_string(), &style);
            if cur_w + cw > lim && (!buf.is_empty() || !cur.is_empty()) {
                if !buf.is_empty() {
                    cur.push(Frag { piece: f.piece, text: std::mem::take(&mut buf) });
                }
                parts.push(Measured { word: Word { frags: std::mem::take(&mut cur), space: None, hard_break: false }, width: cur_w, space: 0.0 });
                cur_w = 0.0;
            }
            buf.push(c);
            cur_w += cw;
        }
        if !buf.is_empty() {
            cur.push(Frag { piece: f.piece, text: buf });
        }
    }
    parts.push(Measured { word: Word { frags: cur, space: m.word.space, hard_break: m.word.hard_break }, width: cur_w, space: m.space });
    parts
}

/// Draws a line with its left edge at `x` and its baseline at `baseline`.
pub fn draw_line(fonts: &Fonts, canvas: &mut Canvas, line: &Line, x: f32, baseline: f32) {
    for p in &line.items {
        let by = baseline - p.shift;
        fonts.draw(canvas, &p.shaped, &p.style, x + p.x, by);
        let t = (p.style.size / 18.0).max(1.0);
        if p.underline {
            canvas.fill(crate::canvas::Rect::new((x + p.x) as i32, (by + p.style.size * 0.12) as i32, p.shaped.width.ceil() as i32, t as i32), p.style.color.max(60));
        }
        if p.strike {
            canvas.fill(crate::canvas::Rect::new((x + p.x) as i32, (by - p.style.size * 0.28) as i32, p.shaped.width.ceil() as i32, t as i32), p.style.color);
        }
    }
}

/// A laid-out paragraph with a fixed line height.
#[derive(Clone, Debug)]
pub struct Para {
    pub lines: Vec<Line>,
    pub line_height: f32,
    /// From the top of a line box to its baseline.
    pub baseline: f32,
}

impl Para {
    pub fn new(fonts: &Fonts, inline: &Inline, opts: &Opts, line_height: f32) -> Para {
        let lines = if inline.iter().all(|p| p.text.trim().is_empty()) { Vec::new() } else { break_lines(fonts, inline, opts) };
        let style = inline.first().map(|p| p.style).unwrap_or(Style::new(Family::Body, 400.0, 10.0));
        Para { lines, line_height, baseline: baseline_in_box(fonts, &style, line_height) }
    }

    pub fn height(&self) -> f32 {
        self.lines.len() as f32 * self.line_height
    }

    pub fn draw(&self, fonts: &Fonts, canvas: &mut Canvas, x: f32, top: f32) {
        for (i, l) in self.lines.iter().enumerate() {
            draw_line(fonts, canvas, l, x, (top + i as f32 * self.line_height + self.baseline).round());
        }
    }
}

/// Where the baseline sits in a line box of `line_height`, so that capitals and x-height are
/// centred the way a typesetter would centre them.
pub fn baseline_in_box(fonts: &Fonts, style: &Style, line_height: f32) -> f32 {
    let m = fonts.metrics(style);
    let asc = m.ascent.abs();
    let desc = m.descent.abs();
    (line_height - (asc + desc)) / 2.0 + asc
}

//! The screens around the paper: home (the newsstand), Saved, Settings with its address
//! keypad, the reading chrome and the "Aa" popover. Each draws itself on a canvas and returns
//! what its regions do when tapped.
//!
//! The home screen follows the phone's newsstand (`ui/newsstand/NewsstandScreen.kt`
//! `TodayDisplay`, `EditionCover.kt`, `Rack.kt`) in its E-ink theme: the latest edition faces
//! out on its own shelf with its caption, the earlier ones stand on a shelf beneath with their
//! dates, and every cover is drawn the way the phone draws it, its measures a fraction of the
//! cover's width.

use crate::canvas::{Canvas, Rect, BLACK, WHITE};
use crate::images::Fit;
use crate::layout::{self, Action, El, Piece, Screen};
use crate::model::{read_minutes, Article, Bundle, SavedList};
use crate::screens::{Ctx, MUTED};
use crate::settings::{Orientation, TEXT_SIZES};
use crate::text::{self, Align};
use crate::time::{self, Date};

/// Width over height of a cover: a tabloid page (`COVER_ASPECT`).
pub const COVER_ASPECT: f32 = 0.72;

type Hits = Vec<(Rect, Action)>;

fn dp(ctx: &Ctx, v: f32) -> f32 {
    v * ctx.m.dpi / 160.0
}

fn first_width(p: &crate::text::Para) -> f32 {
    p.lines.first().map(|l| l.width).unwrap_or(0.0)
}

/// A boxed text button, as E-ink buttons are drawn on the phone (no press feedback, so each
/// is an outlined box). Returns its rectangle.
pub fn button(ctx: &Ctx, canvas: &mut Canvas, r: Rect, label: &str, on: bool, enabled: bool) {
    let ink = if enabled { BLACK } else { 150 };
    if on {
        canvas.fill(r, BLACK);
    } else {
        canvas.fill(r, WHITE);
        canvas.frame(r, ctx.hair() * 2, ink);
    }
    let lp = ctx.line(label, ctx.ty.ui.color(if on { WHITE } else { ink }), r.w as f32, Align::Center);
    lp.draw(ctx.fonts, canvas, r.x as f32, r.y as f32 + (r.h as f32 - lp.height()) / 2.0);
}

/// The width a button needs for `label`.
fn button_w(ctx: &Ctx, label: &str) -> i32 {
    (ctx.fonts.width(label, &ctx.ty.ui) + ctx.m.pt(22.0)).round() as i32
}

/// A tap target grown to the minimum touch size around a smaller drawn control.
fn target(ctx: &Ctx, r: Rect) -> Rect {
    let min = ctx.m.bar().round() as i32;
    let dx = ((min - r.w) / 2).max(0);
    let dy = ((min - r.h) / 2).max(0);
    Rect::new(r.x - dx, r.y - dy, r.w + 2 * dx, r.h + 2 * dy)
}

// --------------------------------------------------------------------------------------------
// Home

pub struct Home<'s> {
    /// Newest first; at most three are shown.
    pub editions: &'s [Bundle],
    /// Today, for "Today's edition" or "Latest edition".
    pub today: Option<Date>,
    pub status: String,
    pub status_is_error: bool,
}

/// "Today's edition" when the newest is today's, as on the phone.
fn today_label(b: &Bundle, today: Option<Date>) -> (String, String) {
    let is_today = today.is_some() && Date::parse(&b.date) == today;
    let n = b.story_count();
    let stories = format!("{n} {}", if n == 1 { "story" } else { "stories" });
    if is_today {
        ("TODAY\u{2019}S EDITION".into(), stories)
    } else {
        let d = Date::parse(&b.date).map(|d| d.short()).unwrap_or_else(|| b.date.clone());
        ("LATEST EDITION".into(), format!("{stories}  \u{b7}  {d}"))
    }
}

pub fn draw_home(ctx: &Ctx, canvas: &mut Canvas, home: &Home) -> Hits {
    let m = &ctx.m;
    let ty = &ctx.ty;
    let mut hits = Hits::new();
    canvas.clear(WHITE);
    let margin = m.margin().round() as i32;
    let w = m.w - 2 * margin;

    // The top row: Exit on the left, Saved and Settings on the right.
    let bh = (m.bar() * 0.82).round() as i32;
    let top = m.pt(9.0).round() as i32;
    let exit = Rect::new(margin, top, button_w(ctx, "Exit"), bh);
    button(ctx, canvas, exit, "Exit", false, true);
    hits.push((target(ctx, exit), Action::Exit));
    let sw = button_w(ctx, "Settings");
    let settings = Rect::new(m.w - margin - sw, top, sw, bh);
    let vw = button_w(ctx, "Saved");
    let saved = Rect::new(settings.x - m.pt(8.0) as i32 - vw, top, vw, bh);
    button(ctx, canvas, saved, "Saved", false, true);
    button(ctx, canvas, settings, "Settings", false, true);
    hits.push((target(ctx, saved), Action::OpenSaved));
    hits.push((target(ctx, settings), Action::OpenSettings));

    // The stand's header: the masthead and the date, set left as on the phone.
    let mut y = (top + bh) as f32 + m.pt(10.0);
    let mast = ctx.line("Morning Issue", ty.masthead.size(ty.masthead.size * 0.72), w as f32, Align::Left);
    mast.draw(ctx.fonts, canvas, margin as f32, y);
    y += mast.height();
    if let Some(d) = home.today {
        let l = ctx.line(&d.long().to_uppercase(), ty.section.color(MUTED), w as f32, Align::Left);
        l.draw(ctx.fonts, canvas, margin as f32, y);
        y += l.height();
    }
    y += m.pt(10.0);

    // The status line at the foot.
    let status_style = if home.status_is_error { ty.caption.italic().color(BLACK) } else { ty.caption.color(MUTED) };
    let status = ctx.para(&text::plain(&home.status, status_style), w as f32, Align::Center, 1.35, Some(2));
    let status_top = m.h as f32 - m.pt(10.0) - status.height().max(status_style.size * 1.35);
    status.draw(ctx.fonts, canvas, margin as f32, status_top);

    let area = Rect::new(margin, y as i32, w, (status_top - m.pt(12.0) - y) as i32);
    let shown = &home.editions[..home.editions.len().min(3)];
    if shown.is_empty() {
        empty_stand(ctx, canvas, area);
        return hits;
    }
    if m.landscape() && shown.len() > 1 {
        rack_landscape(ctx, canvas, area, shown, home.today, &mut hits);
    } else {
        rack_portrait(ctx, canvas, area, shown, home.today, &mut hits);
    }
    hits
}

/// The height of the today caption under the front shelf.
fn today_caption_h(ctx: &Ctx) -> f32 {
    ctx.m.pt(8.0) + ctx.ty.section.size * 1.3 + ctx.ty.caption.size * 1.35
}

/// The front cover on its shelf with its caption, centred in `col` with its top at `y`.
fn today_display(ctx: &Ctx, canvas: &mut Canvas, b: &Bundle, col: Rect, y: i32, cover_w: i32, today: Option<Date>, hits: &mut Hits) {
    let cover_h = (cover_w as f32 / COVER_ASPECT).round() as i32;
    let r = Rect::new(col.x + (col.w - cover_w) / 2, y, cover_w, cover_h);
    draw_cover(ctx, canvas, b, r);
    hits.push((r, Action::OpenEdition(0)));
    let board = dp(ctx, 2.0).round().max(2.0) as i32;
    canvas.hline(col.x, r.bottom(), col.w, board, BLACK);
    let (label, caption) = today_label(b, today);
    let mut ty = (r.bottom() + board) as f32 + ctx.m.pt(8.0);
    let l = ctx.line(&label, ctx.ty.section, col.w as f32, Align::Center);
    l.draw(ctx.fonts, canvas, col.x as f32, ty);
    ty += l.height();
    let c = ctx.line(&caption, ctx.ty.caption.color(MUTED), col.w as f32, Align::Center);
    c.draw(ctx.fonts, canvas, col.x as f32, ty);
}

/// Earlier editions standing on a shelf in `r` (covers, the board, then their dates), left
/// to right from `r.x` as the phone's shelf grid sets them.
fn shelf(ctx: &Ctx, canvas: &mut Canvas, r: Rect, editions: &[Bundle], first_index: usize, cover_w: i32, hits: &mut Hits) {
    let cover_h = (cover_w as f32 / COVER_ASPECT).round() as i32;
    let inset = dp(ctx, 8.0).round() as i32;
    let gap = dp(ctx, 16.0).round() as i32;
    let board = dp(ctx, 2.0).round().max(2.0) as i32;
    for (i, b) in editions.iter().enumerate() {
        let cr = Rect::new(r.x + inset + i as i32 * (cover_w + gap), r.y, cover_w, cover_h);
        draw_cover(ctx, canvas, b, cr);
        hits.push((cr, Action::OpenEdition(first_index + i)));
        let date = Date::parse(&b.date).map(|d| d.short()).unwrap_or_else(|| b.date.clone());
        let c = ctx.line(&date, ctx.ty.caption.color(MUTED), cover_w as f32 + gap as f32, Align::Center);
        c.draw(ctx.fonts, canvas, cr.x as f32 - gap as f32 / 2.0, (cr.bottom() + board) as f32 + ctx.m.pt(4.0));
    }
    canvas.hline(r.x, r.y + cover_h, r.w, board, BLACK);
}

fn shelf_heading(ctx: &Ctx, canvas: &mut Canvas, x: i32, y: f32, w: i32) -> f32 {
    let l = ctx.line("RECENT EDITIONS", ctx.ty.section.color(MUTED), w as f32, Align::Left);
    l.draw(ctx.fonts, canvas, x as f32, y);
    l.height() + ctx.m.pt(8.0)
}

fn rack_portrait(ctx: &Ctx, canvas: &mut Canvas, area: Rect, shown: &[Bundle], today: Option<Date>, hits: &mut Hits) {
    let m = &ctx.m;
    let board = dp(ctx, 2.0).round().max(2.0);
    let caption_h = today_caption_h(ctx);
    let mut bottom = area.bottom() as f32;
    if shown.len() > 1 {
        // The shelf of earlier editions along the bottom.
        // Small enough that the front cover stays the thing to look at.
        let small_w = (area.w as f32 * 0.2).min(m.inch(0.8)).round() as i32;
        let small_h = (small_w as f32 / COVER_ASPECT).round();
        let dates_h = m.pt(4.0) + ctx.ty.caption.size * 1.35;
        let heading_h = ctx.ty.section.size * 1.3 + m.pt(8.0);
        let shelf_top = bottom - dates_h - board - small_h;
        let heading_top = shelf_top - heading_h;
        shelf_heading(ctx, canvas, area.x + dp(ctx, 8.0) as i32, heading_top, area.w);
        shelf(ctx, canvas, Rect::new(area.x, shelf_top as i32, area.w, small_h as i32), &shown[1..], 1, small_w, hits);
        bottom = heading_top - m.pt(12.0);
    }
    // The front display: as large as fits, centred in what is left.
    let avail_h = bottom - area.y as f32 - board - caption_h;
    let cover_w = (avail_h * COVER_ASPECT).min(area.w as f32 * 0.82).floor() as i32;
    let used = cover_w as f32 / COVER_ASPECT + board + caption_h;
    let y = area.y as f32 + ((bottom - area.y as f32 - used) / 2.0).max(0.0);
    today_display(ctx, canvas, &shown[0], area, y as i32, cover_w, today, hits);
}

/// Landscape: the front display on the left, the earlier editions' shelf beside it, as the
/// phone's wide layout sets them.
fn rack_landscape(ctx: &Ctx, canvas: &mut Canvas, area: Rect, shown: &[Bundle], today: Option<Date>, hits: &mut Hits) {
    let m = &ctx.m;
    let gutter = (m.margin() * 1.5).round() as i32;
    let left_w = ((area.w - gutter) as f32 * 0.42) as i32;
    let left = Rect::new(area.x, area.y, left_w, area.h);
    let board = dp(ctx, 2.0).round().max(2.0);
    let avail_h = area.h as f32 - board - today_caption_h(ctx);
    let cover_w = (avail_h * COVER_ASPECT).min(left_w as f32 * 0.9).floor() as i32;
    today_display(ctx, canvas, &shown[0], left, area.y, cover_w, today, hits);
    let right = Rect::new(area.x + left_w + gutter, area.y, area.w - left_w - gutter, area.h);
    let hh = shelf_heading(ctx, canvas, right.x + dp(ctx, 8.0) as i32, right.y as f32, right.w);
    let gap = dp(ctx, 16.0);
    let small_w = ((right.w as f32 - 2.0 * dp(ctx, 8.0) - gap) / 2.0).min(cover_w as f32 * 0.62).floor() as i32;
    shelf(ctx, canvas, Rect::new(right.x, (right.y as f32 + hh) as i32, right.w, (small_w as f32 / COVER_ASPECT) as i32), &shown[1..], 1, small_w, hits);
}

/// No edition yet: a blank cover with the masthead and how to get one.
fn empty_stand(ctx: &Ctx, canvas: &mut Canvas, area: Rect) {
    let m = &ctx.m;
    let text_h = m.inch(0.9);
    let cover_w = ((area.h as f32 - text_h) * COVER_ASPECT).min(area.w as f32 * 0.7).min(m.inch(3.2)) as i32;
    let r = Rect::new(area.x + (area.w - cover_w) / 2, area.y, cover_w, (cover_w as f32 / COVER_ASPECT) as i32);
    let u = r.w as f32 / 100.0;
    let inner = sheet(ctx, canvas, r);
    let y = cover_masthead(ctx, canvas, inner.x as f32, inner.y as f32, inner.w as f32, u);
    let msg = ctx.para(&text::plain("No edition yet", ctx.ty.standfirst.italic().size(u * 6.0).color(MUTED)), inner.w as f32, Align::Center, 1.3, None);
    msg.draw(ctx.fonts, canvas, inner.x as f32, y + (inner.bottom() as f32 - y - msg.height()) / 2.0);
    let board = dp(ctx, 2.0).round().max(2.0) as i32;
    canvas.hline(area.x, r.bottom(), area.w, board, BLACK);
    let how = ctx.para(
        &text::plain("On the phone, open Morning Issue\u{2019}s Sync with Kobo screen on the same Wi-Fi. Editions appear here as they arrive.", ctx.ty.standfirst.color(MUTED)),
        (area.w as f32).min(m.inch(3.6)),
        Align::Center,
        1.4,
        None,
    );
    let hw = (area.w as f32).min(m.inch(3.6));
    how.draw(ctx.fonts, canvas, area.x as f32 + (area.w as f32 - hw) / 2.0, (r.bottom() + board) as f32 + m.pt(10.0));
}

// --------------------------------------------------------------------------------------------
// Covers

/// The paper of a cover: white, an ink outline (E-ink has no shadow). Returns the area inside
/// its padding (6% of the width at the sides, 5% top and bottom).
fn sheet(ctx: &Ctx, canvas: &mut Canvas, r: Rect) -> Rect {
    canvas.fill(r, WHITE);
    canvas.frame(r, dp(ctx, 1.0).round().max(1.0) as i32, BLACK);
    let u = r.w as f32 / 100.0;
    Rect::new(r.x + (u * 6.0).round() as i32, r.y + (u * 5.0).round() as i32, r.w - (u * 12.0).round() as i32, r.h - (u * 10.0).round() as i32)
}

fn hairline(u: f32) -> i32 {
    (u * 0.3).round().max(1.0) as i32
}

/// The cover's masthead and its double rule; returns the y below them.
fn cover_masthead(ctx: &Ctx, canvas: &mut Canvas, x: f32, y: f32, w: f32, u: f32) -> f32 {
    let mast = ctx.line("Morning Issue", ctx.ty.masthead.size(u * 10.5), w, Align::Center);
    mast.draw(ctx.fonts, canvas, x, y);
    let mut y = y + mast.height() + u * 1.6;
    let thick = (u * 0.8).round().max(1.0) as i32;
    canvas.hline(x as i32, y as i32, w as i32, thick, BLACK);
    y += thick as f32 + (u * 0.5).max(1.0);
    canvas.hline(x as i32, y as i32, w as i32, hairline(u), BLACK);
    y + hairline(u) as f32
}

/// "No. 120", "No. 120 · Evening", "No. 120 · Extra" (`editionNumberLabel`).
fn number_label(b: &Bundle) -> String {
    match b.kind.as_str() {
        "EVENING" => format!("No. {} \u{b7} Evening", b.number),
        "EXTRA" => format!("No. {} \u{b7} Extra", b.number),
        _ => format!("No. {}", b.number),
    }
}

/// An edition's front as it stands on the rack (`EditionCover`): masthead, dateline, the
/// lead photo, the lead headline and its source, and greeked columns below. Every measure is
/// a fraction of the cover's width.
pub fn draw_cover(ctx: &Ctx, canvas: &mut Canvas, b: &Bundle, r: Rect) {
    let u = r.w as f32 / 100.0;
    let inner = sheet(ctx, canvas, r);
    let (x, w) = (inner.x as f32, inner.w as f32);
    let mut y = cover_masthead(ctx, canvas, x, inner.y as f32, w, u);
    // Dateline: the long date left, the number right.
    y += u * 1.4;
    let dl = ctx.ty.section.size(u * 2.7).tracking(0.08).color(MUTED);
    let date = Date::parse(&b.date).map(|d| d.long()).unwrap_or_else(|| b.date.clone()).to_uppercase();
    let num = number_label(b).to_uppercase();
    let left = ctx.line(&date, dl, w * 0.68, Align::Left);
    let right = ctx.line(&num, dl, w, Align::Right);
    left.draw(ctx.fonts, canvas, x, y);
    right.draw(ctx.fonts, canvas, x, y);
    y += left.height() + u * 1.4;
    canvas.hline(x as i32, y as i32, w as i32, hairline(u), BLACK);
    y += hairline(u) as f32 + u * 3.5;

    let pages = b.edition_pages();
    let lead = pages.first().and_then(|p| p.lead.or_else(|| p.stories.first().copied())).and_then(|id| b.article(id));
    let bottom = inner.bottom() as f32;
    if let Some(a) = lead {
        // A photo this small is noise on e-ink; the headline gets the room instead.
        let url = a.image_url.clone().filter(|u| !u.is_empty());
        let has_image = url.as_ref().is_some_and(|u| ctx.images.slot(u, None).is_some()) && u >= dp(ctx, 1.6);
        if has_image {
            let h = (w * 10.0 / 16.0).round();
            layout::draw(&[El::Image { url: url.unwrap(), r: Rect::new(x as i32, y as i32, w as i32, h as i32), fit: Fit::Cover }], ctx.fonts, ctx.images, canvas);
            y += h + u * 3.0;
        }
        let hs = ctx.ty.lead.size(u * if has_image { 7.4 } else { 9.6 });
        let head = ctx.para(&text::plain(a.title.trim(), hs), w, Align::Left, 1.08, Some(if has_image { 3 } else { 5 }));
        head.draw(ctx.fonts, canvas, x, y);
        y += head.height();
        if !a.feed_title.trim().is_empty() {
            y += u * 1.8;
            let src = ctx.line(&a.feed_title.trim().to_uppercase(), ctx.ty.section.size(u * 2.6), w, Align::Left);
            src.draw(ctx.fonts, canvas, x, y);
            y += src.height();
        }
        y += u * 3.0;
    }
    greeked_columns(canvas, b.id, u, Rect::new(x as i32, y as i32, w as i32, (bottom - y).max(0.0) as i32));
    if b.finished_at.is_some() {
        // A read edition has a tick in the bottom corner, in a ringed disc.
        let size = (r.w as f32 * 0.13).clamp(dp(ctx, 14.0), dp(ctx, 34.0));
        let pad = r.w as f32 * 0.05;
        let cx = r.right() as f32 - pad - size / 2.0;
        let cy = r.bottom() as f32 - pad - size / 2.0;
        canvas.disc(cx, cy, size / 2.0, BLACK);
        canvas.disc(cx, cy, size / 2.0 - dp(ctx, 1.5), WHITE);
        let t = size * 0.66;
        canvas.tick(cx - t / 2.0, cy - t / 2.0, t, BLACK);
    }
}

/// Two columns of stand-in text lines, the same for an edition every time (`GreekedColumns`,
/// with the same generator so a cover looks alike on both devices).
fn greeked_columns(canvas: &mut Canvas, seed: i64, u: f32, r: Rect) {
    if r.h <= 0 {
        return;
    }
    let gutter = u * 5.0;
    let col_w = (r.w as f32 - gutter) / 2.0;
    let pitch = u * 2.5;
    let thickness = (u * 0.35).max(1.0);
    let lines = (r.h as f32 / pitch) as i32;
    let step = |s: u64| s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    let mut state = step(seed as u64);
    for column in 0..2 {
        let left = r.x as f32 + column as f32 * (col_w + gutter);
        let mut until_break = 3 + ((state >> 33) % 4) as i32;
        for line in 0..lines {
            state = step(state);
            let random = ((state >> 40) % 1000) as f32 / 1000.0;
            let last = until_break == 0;
            let width = col_w * if last { 0.35 + random * 0.4 } else { 0.93 + random * 0.07 };
            canvas.fill(Rect::new(left.round() as i32, (r.y as f32 + line as f32 * pitch).round() as i32, width.round() as i32, thickness.round() as i32), BLACK);
            until_break = if last { 3 + (random * 5.0) as i32 } else { until_break - 1 };
        }
    }
    if lines > 0 {
        canvas.vline((r.x as f32 + col_w + gutter / 2.0) as i32, r.y, (lines as f32 * pitch - (pitch - thickness)) as i32, 1, 120);
    }
}

// --------------------------------------------------------------------------------------------
// Bars

/// The bar along the top of a list screen or the reader: a back chevron with its label, and
/// a title in the middle. Drawn on white over whatever is beneath.
pub fn top_bar(ctx: &Ctx, canvas: &mut Canvas, back_label: &str, title: &str, right: Option<&str>) -> Hits {
    let m = &ctx.m;
    let bar = m.bar().round() as i32;
    let margin = m.margin().round() as i32;
    let mut hits = Hits::new();
    canvas.fill(Rect::new(0, 0, m.w, bar), WHITE);
    let ui = ctx.ty.ui;
    let mid = bar as f32 / 2.0;
    let chev = ui.size * 0.62;
    canvas.chevron(margin as f32 + chev * 0.3, mid, chev, -1.0, BLACK);
    let back = ctx.line(back_label, ui, m.w as f32 / 2.0, Align::Left);
    back.draw(ctx.fonts, canvas, margin as f32 + chev * 1.25, mid - back.height() / 2.0);
    let back_w = first_width(&back) + chev * 1.25;
    hits.push((Rect::new(0, 0, margin + back_w as i32 + margin, bar), Action::Back));
    let mut right_w = 0.0;
    if let Some(r) = right {
        let p = ctx.line(r, ctx.ty.byline.color(MUTED), m.w as f32 / 2.0, Align::Right);
        right_w = first_width(&p);
        p.draw(ctx.fonts, canvas, (m.w - margin) as f32 - m.w as f32 / 2.0, mid - p.height() / 2.0);
    }
    let free = m.w as f32 - 2.0 * (margin as f32 + back_w.max(right_w)) - m.pt(12.0);
    if free > m.inch(0.6) && !title.is_empty() {
        let t = ctx.line(&title.to_uppercase(), ctx.ty.section, free, Align::Center);
        t.draw(ctx.fonts, canvas, (m.w as f32 - free) / 2.0, mid - t.height() / 2.0);
    }
    canvas.hline(0, bar - ctx.hair(), m.w, ctx.hair(), BLACK);
    hits
}

/// What the bar along the foot of a page shows.
pub struct Foot {
    pub position: String,
    pub progress: f32,
    /// Article: Like and Save, on or off, and the "Aa" button.
    pub toggles: Option<(bool, bool)>,
    /// Previous and Next, for edition pages and lists.
    pub nav: bool,
}

pub fn bottom_bar(ctx: &Ctx, canvas: &mut Canvas, f: &Foot) -> Hits {
    let m = &ctx.m;
    let bar = m.bar().round() as i32;
    let margin = m.margin().round() as i32;
    let mut hits = Hits::new();
    let top = m.h - bar;
    canvas.fill(Rect::new(0, top, m.w, bar), WHITE);
    canvas.hline(0, top, m.w, ctx.hair(), BLACK);
    let prog_h = m.pt(2.5).round().max(2.0) as i32;
    canvas.fill(Rect::new(0, top, (m.w as f32 * f.progress.clamp(0.0, 1.0)).round() as i32, prog_h), BLACK);
    let cy = top as f32 + bar as f32 / 2.0 + prog_h as f32 / 2.0;
    let ui = ctx.ty.ui;
    let chev = ui.size * 0.62;
    match f.toggles {
        Some((liked, saved)) => {
            // Position on the left, Like and Save in the middle, Aa on the right.
            let pos = ctx.line(&f.position, ctx.ty.byline.color(MUTED), m.w as f32 / 4.0, Align::Left);
            pos.draw(ctx.fonts, canvas, margin as f32, cy - pos.height() / 2.0);
            let pill_h = (bar as f32 * 0.66) as i32;
            let aa_w = (m.inch(0.55) as i32).max(pill_h);
            let aa = Rect::new(m.w - margin - aa_w, (cy - pill_h as f32 / 2.0) as i32, aa_w, pill_h);
            button(ctx, canvas, aa, "Aa", false, true);
            hits.push((target(ctx, aa).inflate(m.pt(2.0) as i32), Action::TextAndLight));
            let gap = m.pt(10.0) as i32;
            let room = m.w - 2 * margin - 2 * (aa_w + gap);
            let pill_w = (m.inch(0.9) as i32).min((room - gap) / 2);
            let x0 = m.w / 2 - pill_w - gap / 2;
            for (i, (on, on_label, off_label, act)) in [(liked, "Liked", "Like", Action::Like), (saved, "Saved", "Save", Action::Save)].into_iter().enumerate() {
                let r = Rect::new(x0 + i as i32 * (pill_w + gap), (cy - pill_h as f32 / 2.0) as i32, pill_w, pill_h);
                button(ctx, canvas, r, if on { on_label } else { off_label }, on, true);
                hits.push((r.inflate(m.pt(3.0) as i32), act));
            }
        }
        None => {
            let pos = ctx.line(&f.position, ctx.ty.byline.color(MUTED), m.w as f32 / 2.0, Align::Center);
            pos.draw(ctx.fonts, canvas, m.w as f32 / 4.0, cy - pos.height() / 2.0);
            if f.nav {
                let zone = (m.inch(0.55) as i32).max(bar);
                canvas.chevron(margin as f32 + chev * 0.3, cy, chev * 1.2, -1.0, BLACK);
                canvas.chevron((m.w - margin) as f32 - chev * 0.3, cy, chev * 1.2, 1.0, BLACK);
                let pl = ctx.line("Previous", ui, m.w as f32 / 3.0, Align::Left);
                pl.draw(ctx.fonts, canvas, margin as f32 + chev * 1.5, cy - pl.height() / 2.0);
                let nl = ctx.line("Next", ui, m.w as f32 / 3.0, Align::Right);
                nl.draw(ctx.fonts, canvas, (m.w - margin) as f32 - chev * 1.5 - m.w as f32 / 3.0, cy - nl.height() / 2.0);
                let pw = first_width(&pl) as i32;
                hits.push((Rect::new(0, top, margin + pw + zone, bar), Action::Prev));
                hits.push((Rect::new(m.w - margin - pw - zone, top, margin + pw + zone, bar), Action::Next));
            }
        }
    }
    hits
}

// --------------------------------------------------------------------------------------------
// Saved

fn saved_row(ctx: &Ctx, id: i64, a: &Article) -> Piece {
    let c = ctx.m.content();
    let ty = &ctx.ty;
    let pad = ctx.m.pt(8.0);
    let read = a.engagement.read_at.is_some();
    let ink = if read { crate::screens::READ_GREY } else { BLACK };
    let mut els = Vec::new();
    let mut y = pad;
    let mut x = c.x as f32;
    if read {
        let s = ty.headline_small.size * 0.7;
        els.push(El::Tick { x, y: y + s * 0.35, size: s, v: crate::screens::READ_GREY });
        x += s * 1.3;
    }
    let head = ctx.para(&text::plain(a.title.trim(), ty.headline_small.color(ink)), c.w as f32 - (x - c.x as f32), Align::Left, 1.15, Some(3));
    els.push(El::Text { para: head.clone(), x, y });
    y += head.height() + ctx.m.pt(3.0);
    let mut bits = Vec::new();
    if !a.feed_title.trim().is_empty() {
        bits.push(a.feed_title.trim().to_string());
    }
    if let Some(d) = a.published_at.as_deref().and_then(|s| Date::parse(s.get(0..10).unwrap_or(s))) {
        bits.push(d.short());
    }
    bits.push(format!("{}\u{a0}min", read_minutes(a.word_count)));
    let meta = ctx.line(&bits.join("  \u{b7}  "), ty.byline.color(MUTED), c.w as f32, Align::Left);
    els.push(El::Text { para: meta.clone(), x: c.x as f32, y });
    y += meta.height();
    if a.summary_only() {
        let note = ctx.line("Summary only: the phone could not get the full text.", ty.caption.italic().color(MUTED), c.w as f32, Align::Left);
        els.push(El::Text { para: note.clone(), x: c.x as f32, y });
        y += note.height();
    }
    y += pad;
    let mut p = Piece::new(y.ceil());
    p.els = els;
    p.divider.push(El::Fill { r: Rect::new(c.x, 0, c.w, ctx.hair()), v: BLACK });
    p.hits.push((Rect::new(c.x, 0, c.w, y.ceil() as i32), Action::OpenArticle(id)));
    p
}

/// The saved list as screens between the bars, newest saved first.
pub fn saved_screens(ctx: &Ctx, list: &SavedList) -> Vec<Screen> {
    let c = ctx.m.content();
    let items = list.items();
    if items.is_empty() {
        let mut p = Piece::new(0.0);
        let t = ctx.para(&text::plain("Nothing saved yet", ctx.ty.headline.italic()), c.w as f32, Align::Center, 1.2, None);
        let y0 = c.h as f32 * 0.25;
        p.els.push(El::Text { para: t.clone(), x: c.x as f32, y: y0 });
        let how = ctx.para(
            &text::plain("Articles you save on the phone appear here after the next sync, with their pictures, to read without a connection.", ctx.ty.standfirst.color(MUTED)),
            c.w as f32,
            Align::Center,
            1.4,
            None,
        );
        p.els.push(El::Text { para: how.clone(), x: c.x as f32, y: y0 + t.height() + ctx.m.pt(8.0) });
        p.h = y0 + t.height() + how.height();
        return layout::paginate(&[p], c);
    }
    let pieces: Vec<Piece> = items.iter().map(|(id, a)| saved_row(ctx, *id, a)).collect();
    layout::paginate(&pieces, c)
}

// --------------------------------------------------------------------------------------------
// Settings

pub struct SettingsView<'s> {
    pub wifi: String,
    pub online: bool,
    /// A Wi-Fi job is running: Some(true) turning on, Some(false) turning off.
    pub wifi_job: Option<bool>,
    /// The reader turned Wi-Fi on to sync and turns it off when done.
    pub wifi_auto: bool,
    /// Wi-Fi stays on until the phone is found.
    pub looking: bool,
    /// Offline because the reader turned Wi-Fi off to sleep.
    pub slept: bool,
    pub phone_address: Option<&'s str>,
    pub syncing: bool,
    pub last_result: String,
    pub last_is_error: bool,
    pub invert_buttons: bool,
    pub orientation: Orientation,
    /// The model's sensor is mapped, so it turns the page upside down within the choice.
    pub sensor: bool,
    /// Reset was tapped: show what it erases, with Erase everything and Cancel.
    pub reset_armed: bool,
    /// The relay address in use, typed or from `config.json`.
    pub relay_address: Option<&'s str>,
    /// It comes from `config.json`, not the keyboard.
    pub relay_from_config: bool,
    /// Where relay pairing stands, in a sentence ([`relay_note`]).
    pub relay_note: String,
}

/// Where relay pairing stands, for Settings and the pairing screen.
pub fn relay_note(state: &crate::store::State, address: Option<&str>) -> String {
    let s = match (address.is_some(), state.relay_secret.is_some(), state.relay_seen, state.relay_unpaired) {
        (false, _, _, _) => "Sync away from home: type the relay address the phone\u{2019}s Kobo settings show, then pair.",
        (true, false, _, _) => "Not paired with the relay yet. Tap Pair through relay and scan the code with the phone.",
        (true, true, true, true) => "Not paired: the phone no longer accepts this Kobo. Pair through relay again.",
        (true, true, false, _) => "Waiting for the phone: scan the code under Pair through relay with Morning Issue, then sync.",
        (true, true, true, false) => "Paired. Syncs through the relay, or with the phone on this Wi-Fi when the relay can\u{2019}t be reached.",
    };
    s.to_string()
}

fn section(ctx: &Ctx, canvas: &mut Canvas, x: i32, y: &mut f32, w: i32, label: &str) {
    let l = ctx.line(label, ctx.ty.section.color(MUTED), w as f32, Align::Left);
    l.draw(ctx.fonts, canvas, x as f32, *y);
    *y += l.height() + ctx.m.pt(4.0);
}

/// A round radio mark or a square check box at (x, cy), `on` or off.
fn mark(ctx: &Ctx, canvas: &mut Canvas, x: f32, cy: f32, on: bool, round: bool) -> f32 {
    let s = ctx.ty.ui.size * 1.15;
    let t = dp(ctx, 1.5).max(1.5);
    if round {
        canvas.disc(x + s / 2.0, cy, s / 2.0, BLACK);
        canvas.disc(x + s / 2.0, cy, s / 2.0 - t, WHITE);
        if on {
            canvas.disc(x + s / 2.0, cy, s / 2.0 - t * 2.4, BLACK);
        }
    } else {
        let r = Rect::new(x as i32, (cy - s / 2.0) as i32, s as i32, s as i32);
        if on {
            canvas.fill(r, BLACK);
            canvas.tick(x + s * 0.12, cy - s * 0.38, s * 0.76, WHITE);
        } else {
            canvas.frame(r, t.round() as i32, BLACK);
        }
    }
    s
}

/// One choice row: a mark and its label; returns its tap target.
fn choice(ctx: &Ctx, canvas: &mut Canvas, x: i32, y: &mut f32, w: i32, label: &str, on: bool, round: bool) -> Rect {
    let row = ctx.m.bar();
    let cy = *y + row / 2.0;
    let s = mark(ctx, canvas, x as f32, cy, on, round);
    let lx = x as f32 + s + ctx.m.pt(8.0);
    let l = ctx.para(&text::plain(label, ctx.ty.label), w as f32 - (lx - x as f32), Align::Left, 1.25, Some(2));
    l.draw(ctx.fonts, canvas, lx, cy - l.height() / 2.0);
    let r = Rect::new(x, *y as i32, w, row as i32);
    *y += row;
    r
}

fn rule(ctx: &Ctx, canvas: &mut Canvas, x: i32, y: &mut f32, w: i32) {
    *y += ctx.m.pt(6.0);
    canvas.hline(x, *y as i32, w, ctx.hair(), BLACK);
    *y += ctx.m.pt(8.0);
}

/// Settings, page `page` of however many it takes on this screen (returned with the hits).
/// The sections are laid out once, top to bottom, on a canvas as long as they need, and cut
/// into pages between sections; when they don't all fit, the foot bar turns the pages.
pub fn draw_settings(ctx: &Ctx, canvas: &mut Canvas, s: &SettingsView, page: usize) -> (Hits, usize) {
    let m = &ctx.m;
    canvas.clear(WHITE);
    let mut tall = Canvas::new(m.w, m.h * 4);
    tall.clear(WHITE);
    let mut breaks = Vec::new();
    let (body, end) = settings_body(ctx, &mut tall, s, &mut breaks);
    let c = m.content();
    let bottom_one = m.h as f32 - m.pt(10.0);
    // Each page: the part of the long canvas it shows, [from, to).
    let mut pages: Vec<(f32, f32)> = Vec::new();
    if end <= bottom_one {
        pages.push((0.0, end));
    } else {
        let avail = c.bottom() as f32 - c.y as f32 - m.pt(10.0);
        let mut from = c.y as f32;
        loop {
            let limit = from + avail;
            if end <= limit {
                pages.push((from, end));
                break;
            }
            match breaks.iter().rfind(|(e, _)| *e <= limit && *e > from + 1.0) {
                Some(&(e, next)) => {
                    pages.push((from, e));
                    from = next;
                }
                None => {
                    pages.push((from, limit));
                    from = limit;
                }
            }
        }
    }
    let page = page.min(pages.len() - 1);
    let (from, to) = pages[page];
    // Where the slice lands: in place on the first page, under the bar on the others.
    let dest = if page == 0 { from } else { c.y as f32 + m.pt(10.0) };
    let shift = (dest - from).round() as i32;
    for y in from.max(0.0) as i32..(to.ceil() as i32).min(tall.h) {
        let dy = y + shift;
        if dy < 0 || dy >= canvas.h {
            continue;
        }
        let (src, dst) = ((y * tall.w) as usize, (dy * canvas.w) as usize);
        canvas.px[dst..dst + canvas.w as usize].copy_from_slice(&tall.px[src..src + tall.w as usize]);
    }
    let mut hits: Hits = body
        .into_iter()
        .filter(|(r, _)| (r.y + r.h / 2) as f32 >= from && ((r.y + r.h / 2) as f32) < to)
        .map(|(r, a)| (Rect::new(r.x, r.y + shift, r.w, r.h), a))
        .collect();
    hits.extend(top_bar(ctx, canvas, "Home", "Settings", None));
    if pages.len() > 1 {
        let foot = Foot { position: format!("{} of {}", page + 1, pages.len()), progress: 0.0, toggles: None, nav: true };
        hits.extend(bottom_bar(ctx, canvas, &foot));
    }
    (hits, pages.len())
}

/// The end of one section (above its rule) and the start of the next, where a page may end.
fn section_break(ctx: &Ctx, canvas: &mut Canvas, x: i32, y: &mut f32, w: i32, breaks: &mut Vec<(f32, f32)>) {
    let end = *y;
    rule(ctx, canvas, x, y, w);
    breaks.push((end, *y));
}

/// Every section of Settings from the top of `canvas`; returns the hits and where it ends.
fn settings_body(ctx: &Ctx, canvas: &mut Canvas, s: &SettingsView, breaks: &mut Vec<(f32, f32)>) -> (Hits, f32) {
    let m = &ctx.m;
    let mut hits = Hits::new();
    let c = m.content();
    let (x, w) = (c.x, c.w);
    let mut y = c.y as f32 + m.pt(10.0);
    let body = ctx.ty.label;

    section(ctx, canvas, x, &mut y, w, "WI-FI");
    let wifi = ctx.para(&text::plain(&s.wifi, body), w as f32, Align::Left, 1.3, Some(2));
    wifi.draw(ctx.fonts, canvas, x as f32, y);
    y += wifi.height();
    let note = match (s.wifi_job, s.online) {
        (Some(true), _) => "Turning Wi-Fi on and joining a network saved in the Kobo\u{2019}s Wi-Fi settings\u{2026}",
        (Some(false), _) => "Turning Wi-Fi off\u{2026}",
        (None, true) if s.looking => "Wi-Fi stays on until the phone is found. Open Sync with Kobo on the phone, or type its address below.",
        (None, true) if s.wifi_auto => "Wi-Fi turns off again when the sync is done.",
        (None, true) => "Turn Wi-Fi off to save the battery; Morning Issue can turn it on again to sync.",
        (None, false) if s.slept => "Wi-Fi was turned off when the Kobo went to sleep. Turn on Wi-Fi and sync turns it on, syncs, and turns it off again.",
        (None, false) => "Turn on Wi-Fi and sync turns Wi-Fi on, syncs, and turns it off again. It joins a network saved in the Kobo\u{2019}s Wi-Fi settings.",
    };
    let n = ctx.para(&text::plain(note, ctx.ty.caption.color(MUTED)), w as f32, Align::Left, 1.35, None);
    n.draw(ctx.fonts, canvas, x as f32, y);
    y += n.height();
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "PHONE");
    let r = choice(ctx, canvas, x, &mut y, w, "Find automatically", s.phone_address.is_none(), true);
    hits.push((r, Action::PhoneAuto));
    let typed = match s.phone_address {
        Some(a) => format!("Typed address: {a}"),
        None => "Type the address\u{2026}".to_string(),
    };
    let r = choice(ctx, canvas, x, &mut y, w, &typed, s.phone_address.is_some(), true);
    hits.push((r, Action::PhoneTyped));
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "RELAY");
    let bh = (m.bar() * 0.82).round() as i32;
    {
        // Pair through relay, with the address beside it (tap it to type one).
        let pw = button_w(ctx, "Pair through relay").min(w / 2);
        let pr = Rect::new(x, y as i32, pw, bh);
        button(ctx, canvas, pr, "Pair through relay", false, true);
        hits.push((target(ctx, pr), Action::OpenPair));
        let ax = x + pw + m.pt(12.0) as i32;
        let aw = (x + w - ax).max(1);
        let addr = match (s.relay_address, s.relay_from_config) {
            (Some(a), false) => a.to_string(),
            (Some(a), true) => format!("{a} (config.json)"),
            (None, _) => "Type the relay address\u{2026}".to_string(),
        };
        let l = ctx.para(&text::plain(&addr, ctx.ty.label.color(if s.relay_address.is_some() { BLACK } else { MUTED })), aw as f32, Align::Left, 1.2, Some(2));
        l.draw(ctx.fonts, canvas, ax as f32, y + (bh as f32 - l.height()) / 2.0);
        hits.push((Rect::new(ax, y as i32, aw, bh), Action::RelayTyped));
        y += bh as f32 + m.pt(4.0);
        let n = ctx.para(&text::plain(&s.relay_note, ctx.ty.caption.color(MUTED)), w as f32, Align::Left, 1.35, Some(2));
        n.draw(ctx.fonts, canvas, x as f32, y);
        y += n.height();
    }
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "SYNC");
    // Offline, the button turns Wi-Fi on first (and off again after).
    let wifi_first = !s.online || s.wifi_job.is_some();
    let (label, action) = match (s.wifi_job, s.syncing, wifi_first) {
        (Some(true), _, _) => ("Turning Wi-Fi on\u{2026}", None),
        (Some(false), _, _) => ("Turn on Wi-Fi and sync", None),
        (None, true, _) => ("Syncing\u{2026}", None),
        (None, false, true) => ("Turn on Wi-Fi and sync", Some(Action::WifiAndSync)),
        (None, false, false) => ("Sync now", Some(Action::SyncNow)),
    };
    let widest = if wifi_first { "Turn on Wi-Fi and sync" } else { "Syncing\u{2026}" };
    let bw = button_w(ctx, widest).max(button_w(ctx, label)).max(m.inch(1.2) as i32).min(w);
    let br = Rect::new(x, y as i32, bw, bh);
    button(ctx, canvas, br, label, false, action.is_some());
    if let Some(a) = action {
        hits.push((target(ctx, br), a));
    }
    let gap = m.pt(10.0) as i32;
    // Off, whoever turned it on, whenever it is on or coming on.
    if s.online || s.wifi_job.is_some() {
        let label = if s.wifi_job == Some(false) { "Turning off\u{2026}" } else { "Turn Wi-Fi off" };
        let ow = button_w(ctx, "Turn Wi-Fi off").min(w - bw - gap);
        let or = Rect::new(x + bw + gap, y as i32, ow, bh);
        let enabled = s.wifi_job != Some(false);
        button(ctx, canvas, or, label, false, enabled);
        if enabled {
            hits.push((target(ctx, or), Action::WifiOff));
        }
    }
    let side = match (s.online || s.wifi_job.is_some(), s.slept) {
        (true, _) => "",
        (false, true) => "Offline since sleep",
        (false, false) => "Offline",
    };
    if !side.is_empty() {
        let l = ctx.line(side, ctx.ty.label.italic().color(MUTED), w as f32 - bw as f32 - m.pt(12.0), Align::Left);
        l.draw(ctx.fonts, canvas, (x + bw) as f32 + m.pt(12.0), br.y as f32 + (bh as f32 - l.height()) / 2.0);
    }
    y += bh as f32 + m.pt(6.0);
    let st = if s.last_is_error { ctx.ty.caption.italic().color(BLACK) } else { ctx.ty.caption.color(MUTED) };
    let res = ctx.para(&text::plain(&s.last_result, st), w as f32, Align::Left, 1.35, Some(3));
    res.draw(ctx.fonts, canvas, x as f32, y);
    y += res.height();
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "PAGE-TURN BUTTONS");
    let r = choice(ctx, canvas, x, &mut y, w, "Invert the page-turn buttons", s.invert_buttons, false);
    hits.push((r, Action::InvertButtons));
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "ORIENTATION");
    let options = [("Portrait", Orientation::Portrait), ("Landscape", Orientation::Landscape)];
    // A segmented row: one box per choice, the chosen one filled.
    let gap = m.pt(6.0) as i32;
    let n = options.len() as i32;
    let ow = (w - gap * (n - 1)) / n;
    for (i, (label, o)) in options.into_iter().enumerate() {
        let r = Rect::new(x + i as i32 * (ow + gap), y as i32, ow, bh);
        button(ctx, canvas, r, label, o == s.orientation, true);
        hits.push((target(ctx, r), Action::SetOrientation(o)));
    }
    y += bh as f32 + m.pt(6.0);
    if s.sensor {
        let note = "The page turns upside down to match how you hold the Kobo.";
        let n = ctx.para(&text::plain(note, ctx.ty.caption.color(MUTED)), w as f32, Align::Left, 1.35, None);
        n.draw(ctx.fonts, canvas, x as f32, y);
        y += n.height();
    }
    section_break(ctx, canvas, x, &mut y, w, breaks);

    section(ctx, canvas, x, &mut y, w, "RESET");
    if s.reset_armed {
        let warn = "Forgets the phone and deletes everything on this Kobo, then closes.";
        let n = ctx.para(&text::plain(warn, ctx.ty.caption.color(BLACK)), w as f32, Align::Left, 1.35, None);
        n.draw(ctx.fonts, canvas, x as f32, y);
        y += n.height() + m.pt(6.0);
        let ew = button_w(ctx, "Erase everything").max(m.inch(1.2) as i32);
        let er = Rect::new(x, y as i32, ew, bh);
        button(ctx, canvas, er, "Erase everything", true, true);
        hits.push((target(ctx, er), Action::ResetConfirm));
        let cw = button_w(ctx, "Cancel").max(m.inch(1.0) as i32);
        let cr = Rect::new(x + ew + gap * 2, y as i32, cw, bh);
        button(ctx, canvas, cr, "Cancel", false, true);
        hits.push((target(ctx, cr), Action::ResetCancel));
    } else {
        let rw = button_w(ctx, "Reset Morning Issue").max(m.inch(1.2) as i32);
        let rr = Rect::new(x, y as i32, rw, bh);
        button(ctx, canvas, rr, "Reset Morning Issue", false, true);
        hits.push((target(ctx, rr), Action::Reset));
    }
    (hits, y + bh as f32)
}

// --------------------------------------------------------------------------------------------
// The address keypad

pub const KEYPAD: [[&str; 3]; 5] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], [".", "0", ":"], ["\u{232b}", "OK", ""]];

pub fn draw_keypad(ctx: &Ctx, canvas: &mut Canvas, typed: &str, error: Option<&str>) -> Hits {
    let m = &ctx.m;
    canvas.clear(WHITE);
    let mut hits = top_bar(ctx, canvas, "Cancel", "Phone address", None);
    let c = m.content();
    let kw_max = m.inch(1.25);
    let field_w = ((kw_max * 3.0 + m.pt(16.0)) as i32).min(c.w);
    let x = c.x + (c.w - field_w) / 2;
    let mut y = c.y as f32 + m.pt(12.0);
    // The field: what is typed so far, with a caret.
    let fh = (m.bar() * 1.05).round() as i32;
    let field = Rect::new(x, y as i32, field_w, fh);
    canvas.frame(field, ctx.hair() * 2, BLACK);
    let st = ctx.ty.headline.weight(500.0);
    let shown = if typed.is_empty() { " " } else { typed };
    let t = ctx.line(shown, st, field_w as f32 - m.pt(20.0), Align::Left);
    let tx = field.x as f32 + m.pt(10.0);
    t.draw(ctx.fonts, canvas, tx, field.y as f32 + (fh as f32 - t.height()) / 2.0);
    let caret_x = tx + if typed.is_empty() { 0.0 } else { first_width(&t) } + m.pt(2.0);
    canvas.vline(caret_x as i32, field.y + fh / 5, fh * 3 / 5, dp(ctx, 1.5).round().max(2.0) as i32, BLACK);
    y += fh as f32 + m.pt(6.0);
    let hint = match error {
        Some(e) => ctx.para(&text::plain(e, ctx.ty.caption.italic().color(BLACK)), field_w as f32, Align::Left, 1.35, Some(3)),
        None => ctx.para(
            &text::plain("The address is on the phone\u{2019}s Sync with Kobo screen. Port 47824 is used when none is typed after \u{2018}:\u{2019}.", ctx.ty.caption.color(MUTED)),
            field_w as f32,
            Align::Left,
            1.35,
            Some(3),
        ),
    };
    hint.draw(ctx.fonts, canvas, x as f32, y);
    y += ctx.ty.caption.size * 1.35 * 3.0 + m.pt(8.0);

    // The keys.
    let gap = m.pt(8.0);
    let kw = ((field_w as f32 - 2.0 * gap) / 3.0).floor();
    let kh = ((c.bottom() as f32 - y - 4.0 * gap) / 5.0).min(m.inch(0.6)).max(m.bar()).floor();
    let key_style = ctx.ty.headline.weight(560.0);
    for (ri, row) in KEYPAD.iter().enumerate() {
        for (ci, k) in row.iter().enumerate() {
            if k.is_empty() {
                continue;
            }
            let span = if *k == "OK" { 2 } else { 1 };
            let r = Rect::new((x as f32 + ci as f32 * (kw + gap)) as i32, (y + ri as f32 * (kh + gap)) as i32, (kw * span as f32 + gap * (span - 1) as f32) as i32, kh as i32);
            let ok = *k == "OK";
            if ok {
                canvas.fill(r, BLACK);
            } else {
                canvas.frame(r, ctx.hair() * 2, BLACK);
            }
            if *k == "\u{232b}" {
                // Backspace, drawn: an arrow pointing left.
                let cy = r.y as f32 + r.h as f32 / 2.0;
                let cx = r.x as f32 + r.w as f32 / 2.0;
                let s = key_style.size * 0.5;
                canvas.chevron(cx - s * 0.6, cy, s * 1.4, -1.0, BLACK);
                canvas.stroke(cx - s * 0.9, cy, cx + s * 1.1, cy, (s / 4.0).max(1.5), BLACK);
                hits.push((r, Action::KeyDelete));
            } else {
                let style = if ok { ctx.ty.ui.size(ctx.ty.ui.size * 1.2).color(WHITE) } else { key_style };
                let l = ctx.line(k, style, r.w as f32, Align::Center);
                l.draw(ctx.fonts, canvas, r.x as f32, r.y as f32 + (r.h as f32 - l.height()) / 2.0);
                hits.push((r, if ok { Action::KeyOk } else { Action::KeyChar(k.chars().next().unwrap()) }));
            }
        }
    }
    hits
}

// --------------------------------------------------------------------------------------------
// The relay address keyboard

/// Letters, digits and what an address needs; a key's label is what it types.
pub const KEYBOARD: [&[&str]; 5] = [
    &["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
    &["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
    &["a", "s", "d", "f", "g", "h", "j", "k", "l", "-"],
    &["z", "x", "c", "v", "b", "n", "m", ".", ":", "/"],
    &["https://", "http://", "\u{232b}", "OK"],
];

pub fn draw_relay_keyboard(ctx: &Ctx, canvas: &mut Canvas, typed: &str, error: Option<&str>) -> Hits {
    let m = &ctx.m;
    canvas.clear(WHITE);
    let mut hits = top_bar(ctx, canvas, "Cancel", "Relay address", None);
    let c = m.content();
    let field_w = (m.inch(6.0) as i32).min(c.w);
    let x = c.x + (c.w - field_w) / 2;
    let mut y = c.y as f32 + m.pt(12.0);
    let fh = (m.bar() * 1.05).round() as i32;
    let field = Rect::new(x, y as i32, field_w, fh);
    canvas.frame(field, ctx.hair() * 2, BLACK);
    let st = ctx.ty.label.size(ctx.ty.label.size * 1.15);
    let room = field_w as f32 - m.pt(24.0);
    // The end of a long address, where the typing is.
    let mut shown: &str = typed;
    while !shown.is_empty() && ctx.fonts.width(shown, &st) > room {
        let next = shown.char_indices().nth(1).map(|(i, _)| i).unwrap_or(shown.len());
        shown = &shown[next..];
    }
    let t = ctx.line(if shown.is_empty() { " " } else { shown }, st, room, Align::Left);
    let tx = field.x as f32 + m.pt(10.0);
    t.draw(ctx.fonts, canvas, tx, field.y as f32 + (fh as f32 - t.height()) / 2.0);
    let caret_x = tx + if shown.is_empty() { 0.0 } else { ctx.fonts.width(shown, &st) } + m.pt(2.0);
    canvas.vline(caret_x as i32, field.y + fh / 5, fh * 3 / 5, dp(ctx, 1.5).round().max(2.0) as i32, BLACK);
    y += fh as f32 + m.pt(6.0);
    let hint = match error {
        Some(e) => ctx.para(&text::plain(e, ctx.ty.caption.italic().color(BLACK)), field_w as f32, Align::Left, 1.35, Some(3)),
        None => ctx.para(
            &text::plain("The address is in the phone\u{2019}s Kobo settings. Without http:// or https://, a name gets https://. OK with nothing typed uses config.json\u{2019}s.", ctx.ty.caption.color(MUTED)),
            field_w as f32,
            Align::Left,
            1.35,
            Some(3),
        ),
    };
    hint.draw(ctx.fonts, canvas, x as f32, y);
    y += ctx.ty.caption.size * 1.35 * 3.0 + m.pt(8.0);

    let gap = m.pt(5.0);
    let cols = 10.0;
    let kw = ((field_w as f32 - (cols - 1.0) * gap) / cols).floor();
    let kh = ((c.bottom() as f32 - y - 4.0 * gap) / 5.0).min(m.inch(0.5)).max(m.bar() * 0.8).floor();
    let key_style = ctx.ty.label.size(ctx.ty.label.size * 1.2);
    for (ri, row) in KEYBOARD.iter().enumerate() {
        let last = ri + 1 == KEYBOARD.len();
        let mut kx = x as f32;
        for k in row.iter() {
            // The last row: the two schemes three keys wide, backspace two, OK two.
            let span = if !last { 1.0 } else if k.contains("//") { 3.0 } else { 2.0 };
            let r = Rect::new(kx as i32, (y + ri as f32 * (kh + gap)) as i32, (kw * span + gap * (span - 1.0)) as i32, kh as i32);
            kx += kw * span + gap * span;
            let ok = *k == "OK";
            if ok {
                canvas.fill(r, BLACK);
            } else {
                canvas.frame(r, ctx.hair() * 2, BLACK);
            }
            if *k == "\u{232b}" {
                let cy = r.y as f32 + r.h as f32 / 2.0;
                let cx = r.x as f32 + r.w as f32 / 2.0;
                let s = key_style.size * 0.5;
                canvas.chevron(cx - s * 0.6, cy, s * 1.4, -1.0, BLACK);
                canvas.stroke(cx - s * 0.9, cy, cx + s * 1.1, cy, (s / 4.0).max(1.5), BLACK);
                hits.push((r, Action::KeyDelete));
                continue;
            }
            let style = if ok { ctx.ty.ui.color(WHITE) } else if k.len() > 1 { ctx.ty.ui } else { key_style };
            let l = ctx.line(k, style, r.w as f32, Align::Center);
            l.draw(ctx.fonts, canvas, r.x as f32, r.y as f32 + (r.h as f32 - l.height()) / 2.0);
            let action = if ok {
                Action::KeyOk
            } else if k.len() > 1 {
                Action::KeyText(k.to_string())
            } else {
                Action::KeyChar(k.chars().next().unwrap())
            };
            hits.push((r, action));
        }
    }
    hits
}

// --------------------------------------------------------------------------------------------
// The relay pairing code

/// The pairing code full-screen, as large as fits with its quiet zone, and what to do with it.
pub fn draw_pair(ctx: &Ctx, canvas: &mut Canvas, uri: &str, relay: Option<&str>, note: &str) -> Hits {
    let m = &ctx.m;
    canvas.clear(WHITE);
    let hits = top_bar(ctx, canvas, "Settings", "Pair through relay", None);
    let c = m.content();
    let w = c.w as f32;
    let how = ctx.para(
        &text::plain("On the phone, open Morning Issue\u{2019}s Kobo settings and scan this code to pair this Kobo through the relay.", ctx.ty.standfirst),
        w,
        Align::Center,
        1.35,
        None,
    );
    let addr = match relay {
        Some(a) => format!("Relay: {a}"),
        None => "Then type the relay\u{2019}s address in Settings.".to_string(),
    };
    let addr = ctx.para(&text::plain(&addr, ctx.ty.caption.color(BLACK)), w, Align::Center, 1.35, Some(2));
    let status = ctx.para(&text::plain(note, ctx.ty.caption.color(MUTED)), w, Align::Center, 1.35, Some(3));
    let gap = m.pt(10.0);
    let text_h = how.height() + addr.height() + status.height() + gap * 3.0;
    let size = (c.w as f32).min(c.h as f32 - text_h - m.pt(8.0)).max(m.inch(1.0)) as i32;
    let mut y = c.y as f32 + m.pt(8.0);
    if let Some(r) = crate::qr::draw(canvas, uri, c.x + c.w / 2, y as i32, size) {
        y = r.bottom() as f32 + gap;
    }
    how.draw(ctx.fonts, canvas, c.x as f32, y);
    y += how.height() + gap;
    addr.draw(ctx.fonts, canvas, c.x as f32, y);
    y += addr.height() + gap;
    status.draw(ctx.fonts, canvas, c.x as f32, y);
    hits
}

// --------------------------------------------------------------------------------------------
// "Aa": text size and the light

pub struct Popover {
    pub text_size: usize,
    /// Brightness, when the model has a light.
    pub brightness: Option<u8>,
    /// Warmth, when the light has it.
    pub warmth: Option<u8>,
}

/// The popover's panel, above the foot bar at the right.
pub fn popover_rect(ctx: &Ctx, p: &Popover) -> Rect {
    let m = &ctx.m;
    let margin = m.margin().round() as i32;
    let w = (m.inch(3.5) as i32).min(m.w - 2 * margin);
    let row = m.bar() * 1.25;
    let rows = 1 + p.brightness.is_some() as i32 + p.warmth.is_some() as i32;
    let h = (row * rows as f32 + m.pt(14.0) * 2.0 + m.pt(6.0) * (rows - 1) as f32) as i32;
    let bottom = m.h - m.bar().round() as i32 - m.pt(6.0) as i32;
    Rect::new(m.w - margin - w, bottom - h, w, h)
}

pub fn draw_popover(ctx: &Ctx, canvas: &mut Canvas, p: &Popover) -> Hits {
    let m = &ctx.m;
    let r = popover_rect(ctx, p);
    let mut hits: Hits = vec![(r, Action::Nothing)];
    canvas.fill(r.inflate(dp(ctx, 3.0) as i32), WHITE);
    canvas.fill(r, WHITE);
    canvas.frame(r, ctx.hair() * 2, BLACK);
    let pad = m.pt(14.0);
    let x = r.x as f32 + pad;
    let w = r.w as f32 - 2.0 * pad;
    let row = m.bar() * 1.25;
    let mut y = r.y as f32 + pad;
    let label_w = m.inch(0.95);
    let lab = ctx.ty.label;

    // Text size: a small A, five steps, a large A.
    {
        let cy = y + row / 2.0;
        let l = ctx.line("Text size", lab, label_w, Align::Left);
        l.draw(ctx.fonts, canvas, x, cy - l.height() / 2.0);
        let sx = x + label_w;
        let sw = w - label_w;
        let small = ctx.line("A", ctx.ty.reader.size(ctx.ty.ui.size * 0.9), sw, Align::Left);
        let big = ctx.line("A", ctx.ty.reader.size(ctx.ty.ui.size * 1.6), sw, Align::Right);
        small.draw(ctx.fonts, canvas, sx, cy - small.height() / 2.0);
        big.draw(ctx.fonts, canvas, sx, cy - big.height() / 2.0);
        // Widths from the font: a right-aligned line's width includes its indent.
        let small_w = ctx.fonts.width("A", &ctx.ty.reader.size(ctx.ty.ui.size * 0.9));
        let big_w = ctx.fonts.width("A", &ctx.ty.reader.size(ctx.ty.ui.size * 1.6));
        let inner_x = sx + small_w + m.pt(10.0);
        let inner_w = sw - small_w - big_w - m.pt(20.0);
        let n = TEXT_SIZES.len();
        let step = inner_w / n as f32;
        canvas.hline((inner_x + step / 2.0) as i32, cy as i32, (step * (n - 1) as f32) as i32, ctx.hair(), BLACK);
        for i in 0..n {
            let cx = inner_x + step * (i as f32 + 0.5);
            let rad = m.pt(4.0) + i as f32 * m.pt(0.8);
            canvas.disc(cx, cy, rad, BLACK);
            if i != p.text_size {
                canvas.disc(cx, cy, rad - dp(ctx, 1.5).max(1.5), WHITE);
            }
            hits.push((Rect::new((cx - step / 2.0) as i32, (cy - row / 2.0) as i32, step as i32, row as i32), Action::SetTextSize(i)));
        }
        y += row + m.pt(6.0);
    }
    for (warmth, value, name) in [(false, p.brightness, "Brightness"), (true, p.warmth, "Warmth")] {
        let Some(v) = value else { continue };
        let cy = y + row / 2.0;
        let l = ctx.line(name, lab, label_w, Align::Left);
        l.draw(ctx.fonts, canvas, x, cy - l.height() / 2.0);
        let sx = x + label_w;
        let sw = w - label_w;
        // − and + at the ends, the track between.
        let bsz = (row * 0.62).round() as i32;
        let minus = Rect::new(sx as i32, (cy - bsz as f32 / 2.0) as i32, bsz, bsz);
        let plus = Rect::new((sx + sw) as i32 - bsz, minus.y, bsz, bsz);
        for (b, sign) in [(minus, -1), (plus, 1)] {
            canvas.frame(b, ctx.hair() * 2, BLACK);
            let c = (b.x as f32 + b.w as f32 / 2.0, b.y as f32 + b.h as f32 / 2.0);
            let t = (dp(ctx, 1.6)).max(1.5);
            canvas.stroke(c.0 - bsz as f32 * 0.25, c.1, c.0 + bsz as f32 * 0.25, c.1, t, BLACK);
            if sign > 0 {
                canvas.stroke(c.0, c.1 - bsz as f32 * 0.25, c.0, c.1 + bsz as f32 * 0.25, t, BLACK);
            }
            hits.push((b.inflate(m.pt(3.0) as i32), Action::Nudge { warmth, by: if sign > 0 { 10 } else { -10 } }));
        }
        let tx0 = minus.right() as f32 + m.pt(10.0);
        let tw = plus.x as f32 - m.pt(10.0) - tx0;
        let th = m.pt(5.0).round().max(4.0) as i32;
        let track = Rect::new(tx0 as i32, (cy - th as f32 / 2.0) as i32, tw as i32, th);
        canvas.frame(track, ctx.hair(), BLACK);
        let fill = (tw * v as f32 / 100.0).round() as i32;
        // Warmth fills grey, so the two read differently at a glance.
        canvas.fill(Rect::new(track.x, track.y, fill, th), if warmth { 110 } else { BLACK });
        let kx = tx0 + fill as f32;
        canvas.disc(kx, cy, m.pt(6.5), BLACK);
        canvas.disc(kx, cy, m.pt(6.5) - dp(ctx, 1.5).max(1.5), WHITE);
        hits.push((Rect::new(track.x, (cy - row / 2.0) as i32, track.w, row as i32), Action::Slider { warmth, x0: track.x, w: track.w }));
        y += row + m.pt(6.0);
    }
    hits
}

// --------------------------------------------------------------------------------------------
// The sleep screen

/// What shows while the Kobo sleeps: a blank page with "Sleeping…" small in the lower right
/// corner.
pub fn draw_sleep(ctx: &Ctx, canvas: &mut Canvas) {
    let m = &ctx.m;
    canvas.clear(WHITE);
    let margin = m.pt(18.0);
    let w = m.w as f32 - 2.0 * margin;
    let l = ctx.line("Sleeping\u{2026}", ctx.ty.caption.italic().color(MUTED), w, Align::Right);
    l.draw(ctx.fonts, canvas, margin, m.h as f32 - margin - l.height());
}

// --------------------------------------------------------------------------------------------
// The status line

/// What the home screen and Settings say when there is no connection because the reader
/// turned Wi-Fi off to sleep.
pub const OFFLINE_SINCE_SLEEP: &str = "Offline since sleep. Turn on Wi-Fi and sync in Settings";

/// Wi-Fi the reader turned on is staying on until the phone is found.
pub const LOOKING_FOR_PHONE: &str = "Wi-Fi on. Looking for the phone: open Sync with Kobo on it, or type its address in Settings";

/// Wi-Fi was turned on but no network was joined (it is off again).
pub const WIFI_NO_NETWORK: &str = "Could not connect to Wi-Fi. Wi-Fi is off again. Check that a network in range is saved in the Kobo\u{2019}s Wi-Fi settings.";

/// The Wi-Fi script could not be run.
pub const WIFI_CANNOT_START: &str = "Could not turn Wi-Fi on.";

/// What a running sync shows.
#[derive(Clone, Debug, Default)]
pub struct SyncShow {
    /// The phone has answered.
    pub connected: bool,
    pub images_done: usize,
    pub images_total: usize,
}

/// "Syncing…", "Last synced 12 minutes ago with OnePlus 13", "Offline", or the last error
/// in plain words. The bool is true for an error.
pub fn status_line(state: &crate::store::State, now_ms: i64, syncing: Option<&SyncShow>, online: bool) -> (String, bool) {
    if let Some(s) = syncing {
        if s.images_total > 0 {
            return (format!("Syncing\u{2026} {} of {} pictures", s.images_done.min(s.images_total), s.images_total), false);
        }
        return ("Syncing\u{2026}".into(), false);
    }
    let when = state.last_sync_at.map(|t| time::relative(t, now_ms));
    let with = match (&state.phone_name, &state.last_address) {
        _ if state.last_via_relay => " through the relay".to_string(),
        (Some(p), _) => format!(" with {p}"),
        (None, Some(a)) => format!(" with the phone at {}", a.split(':').next().unwrap_or(a)),
        _ => String::new(),
    };
    if !online {
        return match when {
            Some(w) => (format!("Offline \u{b7} Last synced {w}{with}"), false),
            None => ("Offline".into(), false),
        };
    }
    match (&state.last_error, when) {
        (Some(e), Some(w)) => (format!("{e} Last synced {w}."), true),
        (Some(e), None) => (e.clone(), true),
        (None, Some(w)) => (format!("Last synced {w}{with}"), false),
        (None, None) => ("Not synced yet".into(), false),
    }
}

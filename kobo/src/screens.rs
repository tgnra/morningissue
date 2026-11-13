//! An edition's pages and an article, laid out for whatever size and density the panel has.
//! The home, saved, settings and popover screens are in [`crate::ui`].

use crate::canvas::{Rect, BLACK, WHITE};
use crate::fonts::{Fonts, Style};
use crate::images::Fit;
use crate::layout::{paginate, paginate_balanced, Action, El, ImageCache, Metrics, Piece, Screen, Type};
use crate::model::{read_minutes, Article, Block, Bundle, EditionPage, PageKind, RichText};
use crate::text::{self, Align, Inline, Opts, Para};
use crate::time::Date;
use std::rc::Rc;

pub const READ_GREY: u8 = 105;
pub const RULE_GREY: u8 = 0;
pub const MUTED: u8 = 70;

pub struct Ctx<'a> {
    pub fonts: &'a Fonts,
    pub m: Metrics,
    pub ty: Type,
    pub images: &'a ImageCache,
}

impl<'a> Ctx<'a> {
    pub fn new(fonts: &'a Fonts, m: Metrics, images: &'a ImageCache) -> Ctx<'a> {
        Ctx { fonts, ty: Type::new(&m), m, images }
    }

    /// With the article text at `scale` times its base size ("Aa").
    pub fn reading(fonts: &'a Fonts, m: Metrics, images: &'a ImageCache, scale: f32) -> Ctx<'a> {
        Ctx { fonts, ty: Type::with_text_scale(&m, scale), m, images }
    }

    pub fn hair(&self) -> i32 {
        self.m.pt(0.5).round().max(1.0) as i32
    }
    pub fn heavy(&self) -> i32 {
        self.m.pt(2.0).round().max(2.0) as i32
    }

    /// A paragraph of `inline` set `width` wide with lines `lh` × the first style's size apart.
    pub fn para(&self, inline: &Inline, width: f32, align: Align, lh: f32, max_lines: Option<usize>) -> Rc<Para> {
        let size = inline.first().map(|p| p.style.size).unwrap_or(10.0);
        let mut o = Opts::new(width, align);
        o.max_lines = max_lines;
        Rc::new(Para::new(self.fonts, inline, &o, (size * lh).round()))
    }

    pub fn line(&self, s: &str, style: Style, width: f32, align: Align) -> Rc<Para> {
        self.para(&text::plain(s, style), width, align, 1.3, Some(1))
    }

    /// The size to lay out a story's picture at: stored, or still to come in this sync.
    pub fn has_image(&self, url: Option<&str>) -> Option<(u32, u32)> {
        url.filter(|u| !u.is_empty()).and_then(|u| self.images.slot(u, None))
    }
}

pub fn byline(a: &Article) -> String {
    let mins = read_minutes(a.word_count);
    let feed = a.feed_title.trim();
    if feed.is_empty() {
        format!("{mins} min read")
    } else {
        format!("{feed}  \u{b7}  {mins}\u{a0}min")
    }
}

fn is_read(a: &Article) -> bool {
    a.engagement.read_at.is_some()
}

fn excerpt(a: &Article) -> String {
    let s = a.summary.clone().unwrap_or_default();
    let s = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if !s.is_empty() {
        return s;
    }
    for b in &a.blocks {
        if let Block::Paragraph(t) = b {
            if !t.text.trim().is_empty() {
                return t.text.split_whitespace().collect::<Vec<_>>().join(" ");
            }
        }
    }
    String::new()
}

// --------------------------------------------------------------------------------------------
// Story blocks

#[derive(Clone, Copy, PartialEq)]
enum Role {
    /// The front page's lead.
    Lead,
    /// The first story of an inside page.
    Feature,
    /// A story in the grid.
    Cell,
}

/// The text of a story (headline, standfirst, byline) `w` wide at (x, y); returns its height.
#[allow(clippy::too_many_arguments)]
fn story_text(ctx: &Ctx, a: &Article, x: f32, y0: f32, w: f32, role: Role, with_image: bool, els: &mut Vec<El>) -> f32 {
    let ty = &ctx.ty;
    let read = is_read(a);
    let ink = if read { READ_GREY } else { BLACK };
    let mut y = y0;
    let (head_style, standfirst_lines) = match role {
        Role::Lead if with_image => (ty.lead, 2),
        Role::Lead => (ty.lead.size(ty.lead.size * 1.18), 5),
        Role::Feature => (ty.headline, 4),
        Role::Cell => (ty.headline_small, 3),
    };
    let head = ctx.para(&text::plain(a.title.trim(), head_style.color(ink)), w, Align::Left, 1.12, Some(if role == Role::Cell { 5 } else { 6 }));
    els.push(El::Text { para: head.clone(), x, y });
    y += head.height() + ctx.m.pt(3.0);
    let ex = excerpt(a);
    if !ex.is_empty() {
        let st = if role == Role::Cell { ty.excerpt } else { ty.standfirst };
        let p = ctx.para(&text::plain(&ex, st.color(if read { READ_GREY } else { 30 })), w, Align::Left, 1.38, Some(standfirst_lines));
        els.push(El::Text { para: p.clone(), x, y });
        y += p.height() + ctx.m.pt(3.0);
    }
    let by_style = ty.byline.color(if read { READ_GREY } else { MUTED });
    let mut bx = x;
    if read {
        let s = by_style.size;
        els.push(El::Tick { x, y: y + s * 0.15, size: s * 1.05, v: READ_GREY });
        bx += s * 1.4;
    }
    let by = ctx.line(&byline(a), by_style, w - (bx - x), Align::Left);
    els.push(El::Text { para: by.clone(), x: bx, y });
    y += by.height();
    y - y0
}

/// A story `w` wide at (x, 0). With a `budget` (the height it may take) it shows its picture
/// if one is stored and there is room for it: above the text, or beside it on a wide page.
fn story(ctx: &Ctx, id: i64, a: &Article, x: f32, w: f32, role: Role, budget: Option<f32>) -> Piece {
    let mut els = Vec::new();
    let image = budget.and_then(|b| Some((b, ctx.has_image(a.image_url.as_deref())?, a.image_url.clone()?)));
    let mut h = 0.0;
    let mut placed = false;
    if let Some((budget, (iw, ih), url)) = image {
        let gap = ctx.m.pt(if role == Role::Lead { 10.0 } else { 8.0 });
        let aspect = ih as f32 / iw as f32;
        let min_h = ctx.m.inch(0.8).max(w * 0.22);
        if w > ctx.m.inch(4.4) {
            // Side by side: the picture takes 58% of the width.
            let pw = (w * 0.58).round();
            let tw = w - pw - gap;
            let mut text_els = Vec::new();
            let th = story_text(ctx, a, x + pw + gap, 0.0, tw, role, true, &mut text_els);
            let ph = (pw * aspect).clamp(pw * 0.5, pw * 0.8).min(budget).max(th.min(budget));
            if ph >= min_h {
                els.push(El::Image { url, r: Rect::new(x as i32, 0, pw as i32, ph as i32), fit: Fit::Cover });
                els.extend(text_els);
                h = ph.max(th);
                placed = true;
            }
        } else {
            let mut text_els = Vec::new();
            let th = story_text(ctx, a, x, 0.0, w, role, true, &mut text_els);
            let share = if role == Role::Lead { 0.42 } else { 0.32 };
            let ph = (w * aspect).clamp(w * 0.42, w * 0.7).min(ctx.m.page().h as f32 * share).min(budget - th - gap).floor();
            if ph >= min_h {
                els.push(El::Image { url, r: Rect::new(x as i32, 0, w as i32, ph as i32), fit: Fit::Cover });
                els.extend(text_els.iter().map(|e| e.shifted(ph + gap)));
                h = ph + gap + th;
                placed = true;
            }
        }
    }
    if !placed {
        h = story_text(ctx, a, x, 0.0, w, role, false, &mut els);
    }
    let mut p = Piece::new(h.ceil());
    p.els = els;
    p.hits.push((Rect::new(x as i32, 0, w as i32, h.ceil() as i32), Action::OpenArticle(id)));
    p
}

/// Stories laid out `cols` across, with rules between rows and columns.
fn grid(ctx: &Ctx, bundle: &Bundle, ids: &[i64], cols: usize) -> Vec<Piece> {
    let c = ctx.m.page();
    let gutter = ctx.m.pt(14.0).round();
    let mut out = Vec::new();
    for row in ids.chunks(cols) {
        // A short last row is spread over the full width.
        let n = row.len();
        let cw = ((c.w as f32 - gutter * (n as f32 - 1.0)) / n as f32).floor();
        let mut cells = Vec::new();
        for (i, id) in row.iter().enumerate() {
            let Some(a) = bundle.article(*id) else { continue };
            let x = c.x as f32 + i as f32 * (cw + gutter);
            let role = if n == 1 { Role::Feature } else { Role::Cell };
            cells.push(story(ctx, *id, a, x, cw, role, None));
        }
        let h = cells.iter().map(|p| p.h).fold(0.0, f32::max);
        let pad = ctx.m.pt(9.0);
        let mut piece = Piece::new(h + 2.0 * pad);
        piece.divider.push(El::Fill { r: Rect::new(c.x, 0, c.w, ctx.hair()), v: RULE_GREY });
        for (i, cell) in cells.into_iter().enumerate() {
            if i > 0 {
                let x = c.x as f32 + i as f32 * (cw + gutter) - gutter / 2.0;
                piece.els.push(El::Fill { r: Rect::new(x as i32, pad as i32, ctx.hair(), h as i32), v: RULE_GREY });
            }
            piece.els.extend(cell.els.iter().map(|e| e.shifted(pad)));
            // The whole column of the row opens the story.
            piece.hits.extend(cell.hits.into_iter().map(|(r, a)| (Rect::new(r.x, 0, r.w, (h + 2.0 * pad) as i32), a)));
        }
        out.push(piece);
    }
    out
}

// --------------------------------------------------------------------------------------------
// Edition pages

pub fn page_title(kind: &PageKind) -> String {
    match kind {
        PageKind::Front => "Front page".into(),
        PageKind::Top => "Top stories".into(),
        PageKind::Section(name) => name.clone(),
        PageKind::Brief => "In brief".into(),
        PageKind::Archive => "From the archive".into(),
        PageKind::Away => "While you were away".into(),
        PageKind::ReadingList => "From your reading list".into(),
        PageKind::Back => "Back page".into(),
        PageKind::Other(_) => "Stories".into(),
    }
}

fn masthead(ctx: &Ctx, b: &Bundle) -> Piece {
    let c = ctx.m.page();
    let ty = &ctx.ty;
    let w = c.w as f32;
    let mut els = Vec::new();
    let mut y = 0.0;
    let name = ctx.line("Morning Issue", ty.masthead, w, Align::Center);
    els.push(El::Text { para: name.clone(), x: c.x as f32, y });
    y += name.height() + ctx.m.pt(2.0);
    els.push(El::Fill { r: Rect::new(c.x, y as i32, c.w, ctx.hair()), v: BLACK });
    y += ctx.m.pt(4.0);
    let date = Date::parse(&b.date).map(|d| d.long()).unwrap_or_else(|| b.date.clone());
    let mins: i64 = b.edition_pages().iter().filter(|p| p.kind != PageKind::Back && p.kind != PageKind::Brief).flat_map(|p| p.stories.clone()).filter_map(|id| b.article(id)).map(|a| read_minutes(a.word_count)).sum();
    let info = format!("{}  \u{b7}  No.\u{a0}{}  \u{b7}  {}\u{a0}min", b.kind_label(), b.number, mins);
    let st = ty.section;
    let left = date.to_uppercase();
    let right = info.to_uppercase();
    if ctx.fonts.width(&left, &st) + ctx.fonts.width(&right, &st) + ctx.m.pt(24.0) < w {
        let l = ctx.line(&left, st, w, Align::Left);
        let r = ctx.line(&right, st, w, Align::Right);
        els.push(El::Text { para: l.clone(), x: c.x as f32, y });
        els.push(El::Text { para: r, x: c.x as f32, y });
        y += l.height();
    } else {
        for s in [left, right] {
            let l = ctx.line(&s, st, w, Align::Center);
            els.push(El::Text { para: l.clone(), x: c.x as f32, y });
            y += l.height();
        }
    }
    y += ctx.m.pt(3.0);
    els.push(El::Fill { r: Rect::new(c.x, y as i32, c.w, ctx.heavy()), v: BLACK });
    y += ctx.heavy() as f32 + ctx.m.pt(2.0);
    els.push(El::Fill { r: Rect::new(c.x, y as i32, c.w, ctx.hair()), v: BLACK });
    y += ctx.hair() as f32;
    let mut p = Piece::new(y.ceil());
    p.els = els;
    p
}

fn heading(ctx: &Ctx, page: &EditionPage, intro: Option<&str>) -> Piece {
    let c = ctx.m.page();
    let ty = &ctx.ty;
    let w = c.w as f32;
    let mut els = Vec::new();
    let mut y = 0.0;
    let title = page_title(&page.kind);
    match &page.kind {
        PageKind::Section(_) => {
            let t = ctx.para(&text::plain(&title.to_uppercase(), ty.headline.tracking(0.16)), w, Align::Center, 1.15, Some(2));
            els.push(El::Text { para: t.clone(), x: c.x as f32, y });
            y += t.height() + ctx.m.pt(1.0);
            let n = page.stories.len();
            let label = format!("SECTION  \u{b7}  {n} {}", if n == 1 { "STORY" } else { "STORIES" });
            let l = ctx.line(&label, ty.section.color(MUTED), w, Align::Center);
            els.push(El::Text { para: l.clone(), x: c.x as f32, y });
            y += l.height() + ctx.m.pt(4.0);
        }
        _ => {
            let t = ctx.para(&text::plain(&title.to_uppercase(), ty.headline_small.tracking(0.14)), w, Align::Center, 1.2, Some(2));
            els.push(El::Text { para: t.clone(), x: c.x as f32, y });
            y += t.height() + ctx.m.pt(4.0);
        }
    }
    els.push(El::Fill { r: Rect::new(c.x, y as i32, c.w, ctx.heavy()), v: BLACK });
    y += ctx.heavy() as f32;
    if let Some(intro) = intro {
        y += ctx.m.pt(6.0);
        let p = ctx.para(&text::plain(intro, ty.standfirst.italic()), w, Align::Center, 1.35, None);
        els.push(El::Text { para: p.clone(), x: c.x as f32, y });
        y += p.height();
    }
    let mut p = Piece::new(y.ceil()).keep();
    p.els = els;
    p
}

fn brief(ctx: &Ctx, id: i64, a: &Article) -> Piece {
    let c = ctx.m.page();
    let ty = &ctx.ty;
    let read = is_read(a);
    let pad = ctx.m.pt(6.0);
    let mut els = vec![El::Dotted { x: c.x, y: 0, w: c.w }];
    let mut y = pad;
    let src = ctx.line(&a.feed_title.to_uppercase(), ty.section.color(if read { READ_GREY } else { MUTED }), c.w as f32, Align::Left);
    els.push(El::Text { para: src.clone(), x: c.x as f32, y });
    y += src.height();
    let mut x = c.x as f32;
    if read {
        let s = ty.headline_small.size * 0.7;
        els.push(El::Tick { x, y: y + s * 0.35, size: s, v: READ_GREY });
        x += s * 1.3;
    }
    let h = ctx.para(&text::plain(a.title.trim(), ty.headline_small.weight(560.0).color(if read { READ_GREY } else { BLACK })), c.w as f32 - (x - c.x as f32), Align::Left, 1.15, Some(3));
    els.push(El::Text { para: h.clone(), x, y });
    y += h.height() + pad;
    let mut p = Piece::new(y.ceil());
    p.els = els;
    p.hits.push((Rect::new(c.x, 0, c.w, y.ceil() as i32), Action::OpenArticle(id)));
    p
}

fn back_page(ctx: &Ctx, b: &Bundle, page: &EditionPage, latest: bool) -> Vec<Piece> {
    let c = ctx.m.page();
    let ty = &ctx.ty;
    let w = c.w as f32;
    let mut out = Vec::new();
    let mut els = Vec::new();
    let mut y = ctx.m.pt(12.0);
    els.push(El::Fill { r: Rect::new(c.x + c.w / 3, 0, c.w / 3, ctx.heavy()), v: BLACK });
    let thats = if latest {
        "That\u{2019}s today\u{2019}s edition.".to_string()
    } else {
        format!("That\u{2019}s the edition of {}.", Date::parse(&b.date).map(|d| d.day_month()).unwrap_or_else(|| b.date.clone()))
    };
    let t = ctx.para(&text::plain(&thats, ty.lead.italic().weight(560.0)), w, Align::Center, 1.15, None);
    els.push(El::Text { para: t.clone(), x: c.x as f32, y });
    y += t.height() + ctx.m.pt(8.0);
    let sub = if latest { "The next one arrives when the phone prints it." } else { "Newer editions are on the newsstand." };
    let s = ctx.para(&text::plain(sub, ty.standfirst.color(MUTED)), w, Align::Center, 1.35, None);
    els.push(El::Text { para: s.clone(), x: c.x as f32, y });
    y += s.height() + ctx.m.pt(12.0);
    // A button back to the stand.
    let label = "Back to the newsstand";
    let bw = (ctx.fonts.width(label, &ty.ui) + ctx.m.pt(36.0)).min(w);
    let bh = ctx.m.bar().round();
    let r = Rect::new((c.x as f32 + (w - bw) / 2.0) as i32, y as i32, bw as i32, bh as i32);
    els.push(El::Frame { r, t: ctx.hair() * 2, v: BLACK });
    let lp = ctx.line(label, ty.ui, bw, Align::Center);
    els.push(El::Text { para: lp.clone(), x: r.x as f32, y: r.y as f32 + (bh - lp.height()) / 2.0 });
    y += bh + ctx.m.pt(18.0);
    let mut p = Piece::new(y.ceil());
    p.els = els;
    p.hits.push((r, Action::Back));
    out.push(p);

    if !page.stories.is_empty() {
        let n = page.stories.len();
        let intro = format!("{n} more {} from your sources, not printed today.", if n == 1 { "headline" } else { "headlines" });
        out.push(heading(ctx, &EditionPage { number: 0, kind: PageKind::Other("Also in your feeds".into()), stories: vec![], lead: None }, Some(&intro)).with_title(ctx, "Also in your feeds", Some(&intro)));
        // Grouped by source, keeping the builder's order.
        let mut groups: Vec<(String, Vec<i64>)> = Vec::new();
        for id in &page.stories {
            let Some(a) = b.article(*id) else { continue };
            match groups.iter_mut().find(|g| g.0 == a.feed_title) {
                Some(g) => g.1.push(*id),
                None => groups.push((a.feed_title.clone(), vec![*id])),
            }
        }
        for (feed, ids) in groups {
            let l = ctx.line(&feed.to_uppercase(), ty.section, w, Align::Left);
            let mut gp = Piece::new(l.height() + ctx.m.pt(2.0)).gap(ctx.m.pt(8.0)).keep();
            gp.els.push(El::Text { para: l, x: c.x as f32, y: 0.0 });
            out.push(gp);
            for id in ids {
                let Some(a) = b.article(id) else { continue };
                let ink = if is_read(a) { READ_GREY } else { BLACK };
                let indent = ctx.m.pt(10.0);
                let h = ctx.para(&text::plain(a.title.trim(), ty.excerpt.color(ink)), w - indent, Align::Left, 1.3, Some(2));
                let mut hp = Piece::new(h.height() + ctx.m.pt(3.0));
                hp.els.push(El::Fill { r: Rect::new(c.x, (h.line_height * 0.45) as i32, (indent * 0.45) as i32, ctx.hair()), v: MUTED });
                hp.els.push(El::Text { para: h.clone(), x: c.x as f32 + indent, y: 0.0 });
                hp.hits.push((Rect::new(c.x, 0, c.w, hp.h as i32), Action::OpenArticle(id)));
                out.push(hp);
            }
        }
    }
    out
}

trait WithTitle {
    fn with_title(self, ctx: &Ctx, title: &str, intro: Option<&str>) -> Piece;
}

impl WithTitle for Piece {
    /// Rebuilds a page heading with an arbitrary title (the back page's index).
    fn with_title(self, ctx: &Ctx, title: &str, intro: Option<&str>) -> Piece {
        let c = ctx.m.page();
        let ty = &ctx.ty;
        let w = c.w as f32;
        let mut els = Vec::new();
        let mut y = 0.0;
        els.push(El::Fill { r: Rect::new(c.x, 0, c.w, ctx.hair()), v: BLACK });
        y += ctx.m.pt(6.0);
        let t = ctx.para(&text::plain(&title.to_uppercase(), ty.headline_small.tracking(0.14)), w, Align::Center, 1.2, Some(2));
        els.push(El::Text { para: t.clone(), x: c.x as f32, y });
        y += t.height() + ctx.m.pt(2.0);
        if let Some(intro) = intro {
            let p = ctx.para(&text::plain(intro, ty.excerpt.italic().color(MUTED)), w, Align::Center, 1.35, None);
            els.push(El::Text { para: p.clone(), x: c.x as f32, y });
            y += p.height();
        }
        let mut p = Piece::new(y.ceil()).keep();
        p.els = els;
        p
    }
}

/// Every screen of every page of an edition: `(page index, screen)`.
pub fn edition_screens(ctx: &Ctx, b: &Bundle, pages: &[EditionPage], latest: bool) -> Vec<(usize, Screen)> {
    let area = ctx.m.page();
    let cols = ctx.m.columns();
    let gap = ctx.m.pt(10.0);
    let mut out = Vec::new();
    for (pi, page) in pages.iter().enumerate() {
        let mut pieces = Vec::new();
        let c = area;
        match &page.kind {
            PageKind::Front => {
                let mast = masthead(ctx, b);
                let left = c.h as f32 - mast.h - gap;
                pieces.push(mast);
                let mut rest = page.stories.clone();
                if let Some(lead) = page.lead {
                    rest.retain(|&x| x != lead);
                }
                let mut g = grid(ctx, b, &rest, cols);
                if let Some(a) = page.lead.and_then(|l| b.article(l)) {
                    // The lead and the first row of secondaries share the first screen when
                    // there is room for a picture of some size; otherwise the lead has it alone.
                    let row = g.first().map(|r| r.h + gap).unwrap_or(0.0);
                    let budget = if left - row > c.h as f32 * 0.42 { left - row } else { left };
                    let id = page.lead.unwrap();
                    let mut lead = story(ctx, id, a, c.x as f32, c.w as f32, Role::Lead, Some(budget));
                    let has_pic = |p: &Piece| p.els.iter().any(|e| matches!(e, El::Image { .. }));
                    if budget < left && !has_pic(&lead) && ctx.has_image(a.image_url.as_deref()).is_some() {
                        // The picture matters more than sharing the screen with the row.
                        let alone = story(ctx, id, a, c.x as f32, c.w as f32, Role::Lead, Some(left));
                        if has_pic(&alone) {
                            lead = alone;
                        }
                    }
                    pieces.push(lead.gap(gap));
                }
                if let Some(first) = g.first_mut() {
                    first.gap = gap;
                }
                pieces.extend(g);
            }
            PageKind::Brief => {
                pieces.push(heading(ctx, page, None));
                for id in &page.stories {
                    if let Some(a) = b.article(*id) {
                        pieces.push(brief(ctx, *id, a));
                    }
                }
            }
            PageKind::Back => pieces.extend(back_page(ctx, b, page, latest)),
            kind => {
                let intro = match kind {
                    PageKind::Archive => Some("An unread post from a source you love, held over for you."),
                    PageKind::Away => Some("The best of the editions you missed."),
                    PageKind::ReadingList => Some("Saved from a source you don\u{2019}t subscribe to."),
                    _ => None,
                };
                let head = heading(ctx, page, intro);
                let left = c.h as f32 - head.h - gap;
                pieces.push(head);
                let mut ids = page.stories.iter().copied().filter(|id| b.article(*id).is_some());
                if let Some(first) = ids.next() {
                    let a = b.article(first).unwrap();
                    pieces.push(story(ctx, first, a, c.x as f32, c.w as f32, Role::Feature, Some(left * 0.62)).gap(gap));
                }
                let rest: Vec<i64> = ids.collect();
                let mut g = grid(ctx, b, &rest, cols);
                if let Some(first) = g.first_mut() {
                    first.gap = gap;
                }
                pieces.extend(g);
            }
        }
        if pieces.is_empty() {
            let p = ctx.para(&text::plain("Nothing on this page.", ctx.ty.standfirst.italic()), area.w as f32, Align::Center, 1.4, None);
            let mut piece = Piece::new(p.height());
            piece.els.push(El::Text { para: p, x: area.x as f32, y: 0.0 });
            pieces.push(piece);
        }
        for s in paginate_balanced(&pieces, area) {
            out.push((pi, s));
        }
    }
    out
}

// --------------------------------------------------------------------------------------------
// Article

fn rich_or_plain(rt: &RichText, st: Style) -> Inline {
    if rt.spans.is_empty() {
        text::plain(&rt.text, st)
    } else {
        text::rich(rt, st)
    }
}

/// Lines of a paragraph as pieces, so a paragraph can break across screens without leaving
/// one line alone at the bottom (an orphan) or the top (a widow).
fn para_pieces(ctx: &Ctx, para: &Para, x: f32, gap: f32) -> Vec<Piece> {
    let n = para.lines.len();
    let mut out = Vec::with_capacity(n);
    for (i, line) in para.lines.iter().enumerate() {
        let single = Rc::new(Para { lines: vec![line.clone()], line_height: para.line_height, baseline: para.baseline });
        let mut p = Piece::new(para.line_height);
        p.els.push(El::Text { para: single, x, y: 0.0 });
        if i == 0 {
            p.gap = gap;
        }
        // Keep the first two lines together, and the last two.
        p.keep = n > 1 && (i == 0 || i + 2 == n);
        out.push(p);
    }
    let _ = ctx;
    out
}

pub struct ArticleLayout {
    pub screens: Vec<Screen>,
}

/// `toggles` is whether the article is Liked and Saved, for the buttons after its last line.
pub fn article_screens(ctx: &Ctx, a: &Article, toggles: (bool, bool)) -> ArticleLayout {
    let ty = &ctx.ty;
    let c = ctx.m.page();
    let measure = ctx.m.measure(&ty.reader);
    let x = c.x as f32 + ((c.w as f32 - measure) / 2.0).floor();
    let w = measure;
    let body_lh = 1.48;
    let line = (ty.reader.size * body_lh).round();
    let mut pieces: Vec<Piece> = Vec::new();

    // Header.
    {
        let mut els = Vec::new();
        let mut y = 0.0;
        let feed = ctx.line(&a.feed_title.to_uppercase(), ty.section, w, Align::Left);
        els.push(El::Text { para: feed.clone(), x, y });
        y += feed.height() + ctx.m.pt(4.0);
        let head = ctx.para(&text::plain(a.title.trim(), ty.headline.size(ty.headline.size * 1.25)), w, Align::Left, 1.1, None);
        els.push(El::Text { para: head.clone(), x, y });
        y += head.height() + ctx.m.pt(6.0);
        let mut bits = Vec::new();
        if let Some(au) = a.author.as_deref().filter(|s| !s.trim().is_empty()) {
            bits.push(format!("By {}", au.trim()));
        }
        if let Some(d) = a.published_at.as_deref().and_then(|s| Date::parse(s.get(0..10).unwrap_or(s))) {
            bits.push(d.short());
        }
        bits.push(format!("{}\u{a0}min read", read_minutes(a.word_count)));
        let by = ctx.para(&text::plain(&bits.join("  \u{b7}  "), ty.byline.color(MUTED)), w, Align::Left, 1.35, None);
        els.push(El::Text { para: by.clone(), x, y });
        y += by.height() + ctx.m.pt(6.0);
        els.push(El::Fill { r: Rect::new(x as i32, y as i32, w as i32, ctx.hair()), v: BLACK });
        y += ctx.hair() as f32;
        let mut p = Piece::new(y.ceil()).keep();
        p.els = els;
        pieces.push(p);
    }
    let gap = (line * 0.55).round();
    // The lead picture, unless the body starts with it anyway.
    let body_images: Vec<&str> = a.blocks.iter().filter_map(|b| if let Block::Image { url, .. } = b { Some(url.as_str()) } else { None }).collect();
    if let Some(url) = a.image_url.as_deref().filter(|u| !body_images.contains(u)) {
        if let Some(mut p) = image_piece(ctx, url, None, None, x, w, gap) {
            // The lead stays under the headline.
            p.float = false;
            pieces.push(p);
        }
    }

    let blocks: Vec<Block> = if a.blocks.is_empty() {
        a.summary.as_deref().filter(|s| !s.trim().is_empty()).map(|s| vec![Block::Paragraph(RichText::plain(s.trim()))]).unwrap_or_default()
    } else {
        a.blocks.clone()
    };
    let body = ty.reader;
    for (bi, block) in blocks.iter().enumerate() {
        let first_gap = if bi == 0 { gap * 1.4 } else { gap };
        let start = pieces.len();
        match block {
            Block::Paragraph(t) => {
                if t.text.trim().is_empty() {
                    continue;
                }
                let p = ctx.para(&rich_or_plain(t, body), w, Align::Left, body_lh, None);
                pieces.extend(para_pieces(ctx, &p, x, first_gap));
            }
            Block::Heading { level, text: t } => {
                let st = match level {
                    1 | 2 => ty.reader_heading,
                    3 => ty.reader_heading.size(ty.reader_heading.size * 0.88),
                    _ => ty.reader.weight(700.0),
                };
                let p = ctx.para(&rich_or_plain(t, st), w, Align::Left, 1.2, None);
                let mut ps = para_pieces(ctx, &p, x, (line * 1.1).round());
                // A heading stays with what follows it.
                for piece in &mut ps {
                    piece.keep = true;
                }
                pieces.extend(ps);
            }
            Block::Quote { paragraphs, citation } => {
                let indent = ctx.m.pt(14.0);
                let st = body.italic();
                let mut qp: Vec<Piece> = Vec::new();
                for (i, t) in paragraphs.iter().enumerate() {
                    let p = ctx.para(&rich_or_plain(t, st), w - indent, Align::Left, body_lh, None);
                    qp.extend(para_pieces(ctx, &p, x + indent, if i == 0 { first_gap } else { gap * 0.6 }));
                }
                if let Some(cit) = citation {
                    let p = ctx.para(&rich_or_plain(&RichText::plain(format!("\u{2014} {}", cit.text)), ty.caption.color(MUTED)), w - indent, Align::Right, 1.4, None);
                    qp.extend(para_pieces(ctx, &p, x + indent, gap * 0.4));
                }
                // The rule down the left of each line.
                let rule = ctx.m.pt(2.0).round().max(2.0) as i32;
                let rx = x as i32 + (indent * 0.25) as i32;
                for (i, p) in qp.iter_mut().enumerate() {
                    p.els.push(El::Fill { r: Rect::new(rx, 0, rule, p.h.ceil() as i32), v: MUTED });
                    if i > 0 && p.gap > 0.0 {
                        // Bridges the space above, unless the piece starts a screen.
                        p.divider.push(El::Fill { r: Rect::new(rx, -p.gap.ceil() as i32, rule, p.gap.ceil() as i32), v: MUTED });
                    }
                }
                pieces.extend(qp);
            }
            Block::List { ordered, items } => {
                let indent = ctx.m.pt(16.0);
                for (i, t) in items.iter().enumerate() {
                    let marker = if *ordered { format!("{}.", i + 1) } else { "\u{2022}".into() };
                    let p = ctx.para(&rich_or_plain(t, body), w - indent, Align::Left, body_lh, None);
                    let mut ps = para_pieces(ctx, &p, x + indent, if i == 0 { first_gap } else { gap * 0.35 });
                    if let Some(firstp) = ps.first_mut() {
                        let m = ctx.para(&text::plain(&marker, body), indent - ctx.m.pt(4.0), Align::Right, body_lh, Some(1));
                        firstp.els.push(El::Text { para: m, x, y: 0.0 });
                    }
                    pieces.extend(ps);
                }
            }
            Block::Pre { text: t } => {
                pieces.extend(pre_pieces(ctx, t, x, w, first_gap));
            }
            Block::Image { url, caption, alt, .. } => {
                let cap = caption.clone().or_else(|| alt.as_ref().filter(|s| !s.trim().is_empty()).map(|_| RichText::plain(""))).filter(|c| !c.text.trim().is_empty());
                let hint = match (block, ) {
                    (Block::Image { width: Some(iw), height: Some(ih), .. },) => Some((*iw, *ih)),
                    _ => None,
                };
                if let Some(p) = image_piece(ctx, url, cap.as_ref(), hint, x, w, first_gap) {
                    pieces.push(p);
                }
            }
        }
        // Anchors: the block's number, plus how far into it each piece starts.
        let n = (pieces.len() - start).max(1) as f32;
        for (k, p) in pieces[start..].iter_mut().enumerate() {
            p.anchor = (bi + 1) as f32 + k as f32 / n;
        }
    }
    if a.blocks.is_empty() {
        let note = ctx.para(&text::plain("The full text isn\u{2019}t stored on the phone, so this is the feed\u{2019}s summary.", ty.caption.italic().color(MUTED)), w, Align::Left, 1.4, None);
        let mut p = Piece::new(note.height()).gap(gap * 2.0);
        p.els.push(El::Text { para: note, x, y: 0.0 });
        pieces.push(p);
    }
    // An end mark, after the last word when it fits there, so it never sits alone.
    let s = ctx.m.pt(5.0).round() as i32;
    let after_last = pieces.last_mut().and_then(|p| {
        let (para, px, py) = p.els.iter().find_map(|e| match e {
            El::Text { para, x, y } if para.lines.len() == 1 => Some((para.clone(), *x, *y)),
            _ => None,
        })?;
        let lw = para.lines[0].width;
        let mx = px + lw + ctx.m.pt(6.0);
        (mx + s as f32 <= x + w).then(|| {
            let top = py + para.baseline - s as f32;
            p.els.push(El::Fill { r: Rect::new(mx.round() as i32, top.round() as i32, s, s), v: BLACK });
        })
    });
    if after_last.is_none() {
        let mut end = Piece::new(ctx.m.pt(8.0)).gap(gap);
        end.els.push(El::Fill { r: Rect::new((x + w) as i32 - s, 0, s, s), v: BLACK });
        if let Some(last) = pieces.last_mut() {
            last.keep = true;
        }
        pieces.push(end);
    }
    // Like and Save after the last line, so the reader need not call up the chrome. The last
    // line comes with them, so they never open a screen alone.
    let mut anchor = 0.0;
    if let Some(last) = pieces.last_mut() {
        last.keep = true;
        anchor = last.anchor;
    }
    let mut buttons = end_buttons(ctx, x, w, toggles, gap * 2.5);
    buttons.anchor = anchor;
    pieces.push(buttons);
    ArticleLayout { screens: paginate(&pieces, c) }
}

/// The Like and Save buttons that close an article, drawn as the chrome's are: filled when on.
fn end_buttons(ctx: &Ctx, x: f32, w: f32, (liked, saved): (bool, bool), gap: f32) -> Piece {
    let h = ctx.m.bar().round() as i32;
    let space = ctx.m.pt(12.0).round() as i32;
    let bw = (ctx.m.inch(1.1).round() as i32).min((w as i32 - space) / 2);
    let x0 = (x + w / 2.0).round() as i32 - bw - space / 2;
    let mut p = Piece::new(h as f32).gap(gap);
    for (i, (on, label, act)) in [(liked, if liked { "Liked" } else { "Like" }, Action::Like), (saved, if saved { "Saved" } else { "Save" }, Action::Save)].into_iter().enumerate() {
        let r = Rect::new(x0 + i as i32 * (bw + space), 0, bw, h);
        if on {
            p.els.push(El::Fill { r, v: BLACK });
        } else {
            p.els.push(El::Fill { r, v: WHITE });
            p.els.push(El::Frame { r, t: ctx.hair() * 2, v: BLACK });
        }
        let t = ctx.line(label, ctx.ty.ui.color(if on { WHITE } else { BLACK }), bw as f32, Align::Center);
        p.els.push(El::Text { para: t.clone(), x: r.x as f32, y: (h as f32 - t.height()) / 2.0 });
        p.hits.push((r, act));
    }
    p
}

/// A picture as wide as it reads well, at the size the bundle gives (or its own size once
/// stored), so the text does not move when it arrives.
fn image_piece(ctx: &Ctx, url: &str, caption: Option<&RichText>, hint: Option<(u32, u32)>, x: f32, w: f32, gap: f32) -> Option<Piece> {
    let (iw, ih) = ctx.images.slot(url, hint)?;
    let max_h = ctx.m.page().h as f32 * 0.42;
    // Pictures come at the screen's width; a small one is shown at least 40% wide. The
    // shape, not the pixel count, decides the space while the picture is on its way.
    let natural = if ctx.images.dims(url).is_some() { iw as f32 * ctx.m.dpi / 150.0 } else { w };
    let mut dw = w.min(natural).max(w * 0.4).min(w);
    let mut dh = dw * ih as f32 / iw as f32;
    if dh > max_h {
        dh = max_h;
        dw = dh * iw as f32 / ih as f32;
    }
    let ix = x + (w - dw) / 2.0;
    let mut p = Piece::new(dh.round()).gap(gap);
    p.float = true;
    p.els.push(El::Image { url: url.into(), r: Rect::new(ix as i32, 0, dw as i32, dh as i32), fit: Fit::Contain });
    if let Some(c) = caption {
        let cp = ctx.para(&rich_or_plain(c, ctx.ty.caption.color(MUTED)), w, Align::Left, 1.4, Some(6));
        let y = dh + ctx.m.pt(4.0);
        p.els.push(El::Text { para: cp.clone(), x, y });
        p.h = (y + cp.height()).round();
    }
    Some(p)
}

/// A line of code split where it is spaced out: (the column it starts at, the text).
fn code_segments(line: &str) -> Vec<(usize, String)> {
    let chars: Vec<char> = line.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && chars[i] == ' ' {
            i += 1;
        }
        if i >= chars.len() {
            break;
        }
        let start = i;
        // A segment runs until two spaces in a row.
        while i < chars.len() && !(chars[i] == ' ' && chars.get(i + 1).is_none_or(|c| *c == ' ')) {
            i += 1;
        }
        out.push((start, chars[start..i].iter().collect()));
    }
    out
}

fn pre_pieces(ctx: &Ctx, t: &str, x: f32, w: f32, gap: f32) -> Vec<Piece> {
    // No monospaced font is bundled, so code is set in Inter at its own spacing, which reads
    // far better than letters spread over fixed cells. Indentation and columns (a run of two
    // or more spaces) still line up: each part starts at its column on a grid of the font's
    // average letter width, or just after the part before when that is further right.
    // Long lines are set smaller, down to 80%, then wrap.
    let base = ctx.ty.caption.weight(500.0);
    let lines: Vec<String> = t.trim_end_matches('\n').replace('\t', "    ").lines().map(str::to_owned).collect();
    let pad = ctx.m.pt(6.0);
    let avail = w - 2.0 * pad;
    let place = |l: &str, st: &crate::fonts::Style| -> (Vec<(f32, String)>, f32) {
        let cell = ctx.fonts.width("abcdefghijklmnopqrstuvwxyz0123456789", st) / 36.0;
        let space = ctx.fonts.width(" ", st).max(cell * 0.5);
        let mut cursor: f32 = 0.0;
        let mut parts = Vec::new();
        for (k, (col, text)) in code_segments(l).into_iter().enumerate() {
            let sx = (col as f32 * cell).max(if k == 0 { 0.0 } else { cursor + space * 2.0 });
            cursor = sx + ctx.fonts.width(&text, st);
            parts.push((sx, text));
        }
        (parts, cursor)
    };
    let longest = lines.iter().map(|l| place(l, &base).1).fold(1.0, f32::max);
    let scale = (avail / longest).clamp(0.8, 1.0);
    let style = base.size(base.size * scale);
    let lh = (style.size * 1.45).round();
    let mut wrapped: Vec<String> = Vec::new();
    for l in lines {
        if place(&l, &style).1 <= avail {
            wrapped.push(l);
            continue;
        }
        // Break at the last character that fits, keeping the indentation on the next line.
        let lead: String = l.chars().take_while(|c| *c == ' ').collect();
        let mut cur = String::new();
        for ch in l.chars() {
            cur.push(ch);
            if place(&cur, &style).1 > avail && cur.trim().chars().count() > 1 {
                let last = cur.pop().unwrap();
                wrapped.push(cur);
                cur = format!("{lead}  {last}");
            }
        }
        wrapped.push(cur);
    }
    let n = wrapped.len();
    let mut out = Vec::new();
    for (i, l) in wrapped.into_iter().enumerate() {
        let top = if i == 0 { pad } else { 0.0 };
        let bottom = if i + 1 == n { pad } else { 0.0 };
        let mut p = Piece::new(lh + top + bottom);
        p.els.push(El::Fill { r: Rect::new(x as i32, 0, w as i32, (lh + top + bottom).ceil() as i32), v: 238 });
        for (sx, text) in place(&l, &style).0 {
            p.els.push(El::Mono { lines: vec![text], x: x + pad + sx, y: top, cell: 0.0, line_height: lh, style });
        }
        if i == 0 {
            p.gap = gap;
        }
        p.keep = n > 1 && (i == 0 || i + 2 == n);
        out.push(p);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_is_split_at_its_columns() {
        assert_eq!(code_segments("Tide   Height Wheel"), vec![(0, "Tide".into()), (7, "Height Wheel".into())]);
        assert_eq!(code_segments("    let x = 1;  // one"), vec![(4, "let x = 1;".into()), (16, "// one".into())]);
        assert!(code_segments("   ").is_empty());
    }
}

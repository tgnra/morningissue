//! `morningissue read`: the newsstand, an edition, an article, and what moves between them.
//!
//! Refresh policy: a page turn is a non-flashing partial refresh, with a full flash every
//! `flash_every` turns; changing screen (newsstand, edition, article) or rotating always
//! flashes; Like and Save redraw just their button with the fast waveform.

use crate::canvas::{Canvas, Rect};
use crate::config::Config;
use crate::device::{self, Clock, Display, Input, Key, RawEvent, Refresh};
use crate::events::{self, ArticleVisit, PageMeter, Recorder};
use crate::fonts::Fonts;
use crate::gesture::{Dir, Gesture, Recognizer};
use crate::layout::{self, Action, ImageCache, Metrics, Screen};
use crate::model::{Bundle, EditionPage, PageKind};
use crate::screens::{self, Chrome, Ctx, Stand};
use crate::store::{State, Store};
use std::collections::HashSet;
use std::io;
use std::path::Path;
use std::time::Duration;

struct Ed {
    idx: usize,
    pages: Vec<EditionPage>,
    screens: Vec<(usize, Screen)>,
    pos: usize,
}

struct Art {
    id: i64,
    screens: Vec<Screen>,
    pos: usize,
}

enum View {
    Stand,
    Edition(Ed),
    Article(Ed, Art),
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum Show {
    /// A different screen: always a full flash.
    Change,
    /// A page turn: partial, with a periodic flash.
    Turn,
    /// Only what changed, with the fast waveform.
    Touch,
}

pub struct Options {
    pub rotation: u8,
    pub follow_sensor: bool,
    pub can_rotate: bool,
}

pub struct App {
    display: Box<dyn Display>,
    input: Box<dyn Input>,
    clock: Box<dyn Clock>,
    store: Store,
    state: State,
    config: Config,
    fonts: Fonts,
    images: ImageCache,
    editions: Vec<Bundle>,
    rotation: u8,
    follow_sensor: bool,
    can_rotate: bool,
    /// Set by the Rotate button: the sensor is ignored from then on.
    locked: bool,
    view: View,
    hits: Vec<(Rect, Action)>,
    shown: Option<Canvas>,
    partials: u32,
    recorder: Recorder,
    meter: PageMeter,
    visit: Option<ArticleVisit>,
    gestures: Recognizer,
    opened_editions: HashSet<i64>,
    last_input: u64,
    /// Every refresh, for tests: (mode, region).
    pub refreshes: Vec<(Refresh, Rect)>,
}

impl App {
    pub fn new(display: Box<dyn Display>, input: Box<dyn Input>, clock: Box<dyn Clock>, store: Store, config: Config, fonts_dir: &Path, opts: Options) -> io::Result<App> {
        let state = store.load_state()?;
        let mut editions = store.load_editions(&state);
        let pending = store.read_events()?;
        events::apply(&mut editions, &pending);
        let recorder = Recorder::new(&store, &state)?;
        let dpi = display.dpi();
        let fonts = Fonts::load(fonts_dir, dpi)?;
        let images_dir = store.images_dir();
        let images = ImageCache::new(Box::new(move |url| std::fs::read(images_dir.join(format!("{}.img", crate::store::image_key(url)))).ok()));
        let now = clock.mono_ms();
        Ok(App {
            display,
            input,
            clock,
            store,
            state,
            config,
            fonts,
            images,
            editions,
            rotation: opts.rotation % 4,
            follow_sensor: opts.follow_sensor,
            can_rotate: opts.can_rotate,
            locked: false,
            view: View::Stand,
            hits: Vec::new(),
            shown: None,
            partials: 0,
            recorder,
            meter: PageMeter::default(),
            visit: None,
            gestures: Recognizer::new(dpi),
            opened_editions: HashSet::new(),
            last_input: now,
            refreshes: Vec::new(),
        })
    }

    fn metrics(&self) -> Metrics {
        let (w, h) = device::canvas_size(self.display.base_size(), self.rotation);
        Metrics { w, h, dpi: self.display.dpi() }
    }

    fn page_key(&self) -> Option<(i64, i64)> {
        match &self.view {
            View::Edition(ed) => {
                let b = &self.editions[ed.idx];
                let page = ed.screens.get(ed.pos).map(|s| s.0)?;
                Some((b.id, ed.pages[page].number))
            }
            _ => None,
        }
    }

    /// Tells the page meter what shows now, and records the time of the page that went.
    fn meter_update(&mut self) -> io::Result<()> {
        let key = self.page_key();
        let now = self.clock.mono_ms();
        if let Some(((edition, page), ms)) = self.meter.show(key, now) {
            self.record("pageView", |e| {
                e.edition_id = Some(edition);
                e.page = Some(page);
                e.ms = Some(ms as i64);
            })?;
        }
        Ok(())
    }

    fn record(&mut self, kind: &str, fill: impl FnOnce(&mut crate::store::Event)) -> io::Result<()> {
        let wall = self.clock.wall_ms();
        let e = self.recorder.record(&self.store, wall, kind, fill)?;
        events::apply_one(&mut self.editions, &e);
        Ok(())
    }

    fn layout_edition(&self, idx: usize) -> Ed {
        let m = self.metrics();
        let ctx = Ctx::new(&self.fonts, m, &self.images);
        let b = &self.editions[idx];
        let pages = b.edition_pages();
        let screens = screens::edition_screens(&ctx, b, &pages, idx == 0);
        Ed { idx, pages, screens, pos: 0 }
    }

    fn layout_article(&self, ed: &Ed, id: i64) -> Art {
        let m = self.metrics();
        let ctx = Ctx::new(&self.fonts, m, &self.images);
        let a = self.editions[ed.idx].article(id).cloned().unwrap_or_default();
        Art { id, screens: screens::article_screens(&ctx, &a).screens, pos: 0 }
    }

    fn render(&mut self) -> Canvas {
        let m = self.metrics();
        let mut canvas = Canvas::new(m.w, m.h);
        let ctx = Ctx::new(&self.fonts, m, &self.images);
        let hits = match &self.view {
            View::Stand => {
                let now = self.clock.wall_ms();
                let (status, err) = screens::status_line(&self.state, now);
                screens::draw_stand(&ctx, &mut canvas, &Stand { editions: &self.editions[..self.editions.len().min(3)], status, status_is_error: err, can_rotate: self.can_rotate })
            }
            View::Edition(ed) => {
                let (pi, screen) = &ed.screens[ed.pos];
                layout::draw(&screen.els, &self.fonts, &self.images, &mut canvas);
                let page = &ed.pages[*pi];
                let of_page: Vec<usize> = ed.screens.iter().enumerate().filter(|(_, s)| s.0 == *pi).map(|(i, _)| i).collect();
                let mut position = format!("Page {} of {}", page.number, ed.pages.len());
                if of_page.len() > 1 {
                    let k = of_page.iter().position(|&i| i == ed.pos).unwrap_or(0) + 1;
                    position.push_str(&format!("  \u{b7}  {k}/{}", of_page.len()));
                }
                let b = &self.editions[ed.idx];
                let title = if page.kind == PageKind::Front { b.kind_label() } else { screens::page_title(&page.kind) };
                let chrome = Chrome { back_label: "Newsstand", title, position, progress: (ed.pos + 1) as f32 / ed.screens.len() as f32, toggles: None };
                let mut hits = screen.hits.clone();
                hits.extend(screens::draw_chrome(&ctx, &mut canvas, &chrome));
                hits
            }
            View::Article(ed, art) => {
                let screen = &art.screens[art.pos];
                layout::draw(&screen.els, &self.fonts, &self.images, &mut canvas);
                let a = self.editions[ed.idx].article(art.id);
                let g = a.map(|a| a.engagement.clone()).unwrap_or_default();
                let chrome = Chrome {
                    back_label: "Edition",
                    title: a.map(|a| a.feed_title.clone()).unwrap_or_default(),
                    position: format!("{} of {}", art.pos + 1, art.screens.len()),
                    progress: (art.pos + 1) as f32 / art.screens.len() as f32,
                    toggles: Some((g.liked_at.is_some(), g.saved_at.is_some())),
                };
                let mut hits = screen.hits.clone();
                hits.extend(screens::draw_chrome(&ctx, &mut canvas, &chrome));
                hits
            }
        };
        self.hits = hits;
        canvas
    }

    fn show(&mut self, how: Show) -> io::Result<()> {
        let canvas = self.render();
        let full = canvas.bounds();
        let (mode, region) = match how {
            Show::Change => (Refresh::Full, full),
            Show::Turn => {
                self.partials += 1;
                if self.partials >= self.config.flash_every.max(1) {
                    (Refresh::Full, full)
                } else {
                    (Refresh::Partial, full)
                }
            }
            Show::Touch => match self.shown.as_ref().filter(|s| s.w == canvas.w && s.h == canvas.h).and_then(|s| canvas.diff(s)) {
                Some(r) => (Refresh::Fast, r),
                None if self.shown.is_some() => return Ok(()),
                None => (Refresh::Full, full),
            },
        };
        if mode == Refresh::Full {
            self.partials = 0;
        }
        self.display.present(&canvas, self.rotation, region, mode)?;
        self.refreshes.push((mode, region));
        self.shown = Some(canvas);
        Ok(())
    }

    fn open_edition(&mut self, idx: usize) -> io::Result<()> {
        if idx >= self.editions.len() {
            return Ok(());
        }
        let id = self.editions[idx].id;
        if self.opened_editions.insert(id) {
            self.record("editionOpened", |e| e.edition_id = Some(id))?;
        }
        self.view = View::Edition(self.layout_edition(idx));
        self.meter_update()?;
        self.check_finished()?;
        self.show(Show::Change)
    }

    /// Reaching the back page finishes the edition.
    fn check_finished(&mut self) -> io::Result<()> {
        if let View::Edition(ed) = &self.view {
            let b = &self.editions[ed.idx];
            let on_back = ed.screens.get(ed.pos).is_some_and(|s| s.0 + 1 == ed.pages.len());
            if on_back && b.finished_at.is_none() {
                let id = b.id;
                self.record("editionFinished", |e| e.edition_id = Some(id))?;
            }
        }
        Ok(())
    }

    fn open_article(&mut self, id: i64) -> io::Result<()> {
        let view = std::mem::replace(&mut self.view, View::Stand);
        let View::Edition(ed) = view else {
            self.view = view;
            return Ok(());
        };
        let Some(a) = self.editions[ed.idx].article(id).cloned() else {
            self.view = View::Edition(ed);
            return Ok(());
        };
        let edition_id = self.editions[ed.idx].id;
        let art = self.layout_article(&ed, id);
        self.view = View::Article(ed, art);
        // The edition page is off screen now.
        self.meter_update()?;
        if a.engagement.opened_at.is_none() {
            self.record("opened", |e| e.article_id = Some(id))?;
        }
        let now = self.clock.mono_ms();
        let mut visit = ArticleVisit {
            edition_id,
            article_id: id,
            word_count: a.word_count,
            had_fraction: a.engagement.read_fraction,
            had_read: a.engagement.read_at.is_some(),
            furthest: 0.0,
            dwell_ms: 0,
            showing_since: None,
        };
        visit.resume(now);
        if let View::Article(_, art) = &self.view {
            visit.reached(art.pos, art.screens.len());
        }
        self.visit = Some(visit);
        self.show(Show::Change)
    }

    /// Records how far the article got, and whether it now counts as read.
    fn end_visit(&mut self) -> io::Result<()> {
        let Some(mut v) = self.visit.take() else { return Ok(()) };
        v.pause(self.clock.mono_ms());
        let id = v.article_id;
        let fraction = (v.furthest * 1000.0).round() / 1000.0;
        if fraction > v.had_fraction + 1e-9 {
            self.record("readFraction", |e| {
                e.article_id = Some(id);
                e.value = Some(serde_json::json!(fraction));
            })?;
        }
        if !v.had_read && events::is_read(v.furthest, v.dwell_ms, v.word_count) {
            self.record("read", |e| e.article_id = Some(id))?;
        }
        let _ = v.edition_id;
        Ok(())
    }

    fn back(&mut self) -> io::Result<bool> {
        match std::mem::replace(&mut self.view, View::Stand) {
            View::Article(ed, _) => {
                self.end_visit()?;
                self.view = View::Edition(ed);
                self.meter_update()?;
                self.show(Show::Change)?;
            }
            View::Edition(_) => {
                self.meter_update()?;
                self.show(Show::Change)?;
            }
            View::Stand => {}
        }
        Ok(false)
    }

    fn turn(&mut self, forward: bool) -> io::Result<()> {
        let moved = match &mut self.view {
            View::Edition(ed) => step(&mut ed.pos, ed.screens.len(), forward),
            View::Article(_, art) => step(&mut art.pos, art.screens.len(), forward),
            View::Stand => false,
        };
        if !moved {
            return Ok(());
        }
        if let (View::Article(_, art), Some(v)) = (&self.view, self.visit.as_mut()) {
            v.reached(art.pos, art.screens.len());
        }
        self.meter_update()?;
        self.check_finished()?;
        self.show(Show::Turn)
    }

    fn toggle(&mut self, like: bool) -> io::Result<()> {
        let View::Article(ed, art) = &self.view else { return Ok(()) };
        let id = art.id;
        let Some(a) = self.editions[ed.idx].article(id) else { return Ok(()) };
        let on = if like { a.engagement.liked_at.is_none() } else { a.engagement.saved_at.is_none() };
        self.record(if like { "liked" } else { "saved" }, |e| {
            e.article_id = Some(id);
            e.value = Some(serde_json::json!(on));
        })?;
        self.show(Show::Touch)
    }

    fn rotate_to(&mut self, r: u8) -> io::Result<()> {
        let r = r % 4;
        if r == self.rotation {
            return Ok(());
        }
        // Keep the reader's place: the same page of the edition, the same part of the article.
        let (page, frac) = match &self.view {
            View::Edition(ed) => (ed.screens.get(ed.pos).map(|s| s.0), 0.0),
            View::Article(ed, art) => (ed.screens.get(ed.pos).map(|s| s.0), art.pos as f32 / art.screens.len().max(1) as f32),
            View::Stand => (None, 0.0),
        };
        self.rotation = r;
        let view = std::mem::replace(&mut self.view, View::Stand);
        self.view = match view {
            View::Stand => View::Stand,
            View::Edition(old) => {
                let mut ed = self.layout_edition(old.idx);
                ed.pos = page.and_then(|p| ed.screens.iter().position(|s| s.0 == p)).unwrap_or(0);
                View::Edition(ed)
            }
            View::Article(old, art) => {
                let mut ed = self.layout_edition(old.idx);
                ed.pos = page.and_then(|p| ed.screens.iter().position(|s| s.0 == p)).unwrap_or(0);
                let mut na = self.layout_article(&ed, art.id);
                na.pos = ((frac * na.screens.len() as f32) as usize).min(na.screens.len() - 1);
                View::Article(ed, na)
            }
        };
        self.gestures = Recognizer::new(self.display.dpi());
        self.show(Show::Change)
    }

    /// Returns true to exit.
    fn act(&mut self, a: Action) -> io::Result<bool> {
        match a {
            Action::OpenEdition(i) => self.open_edition(i)?,
            Action::OpenArticle(id) => self.open_article(id)?,
            Action::Back => return self.back(),
            Action::Next => self.turn(true)?,
            Action::Prev => self.turn(false)?,
            Action::Like => self.toggle(true)?,
            Action::Save => self.toggle(false)?,
            Action::Rotate => {
                self.locked = true;
                let r = (self.rotation + 1) % 4;
                self.rotate_to(r)?;
            }
            Action::Exit => return Ok(true),
        }
        Ok(false)
    }

    fn tap(&mut self, x: i32, y: i32) -> io::Result<bool> {
        if let Some((_, a)) = self.hits.iter().rev().find(|(r, _)| r.contains(x, y)).cloned() {
            return self.act(a);
        }
        // Tap zones, so a page can be turned by touch anywhere outside a story.
        let m = self.metrics();
        match self.view {
            View::Article(..) => self.act(if x < m.w / 3 { Action::Prev } else { Action::Next }),
            View::Edition(_) if x < m.w / 5 => self.act(Action::Prev),
            View::Edition(_) if x > m.w * 4 / 5 => self.act(Action::Next),
            _ => Ok(false),
        }
    }

    fn gesture(&mut self, g: Gesture) -> io::Result<bool> {
        match g {
            Gesture::Tap { x, y } => self.tap(x, y),
            Gesture::Swipe { dir: Dir::Left | Dir::Up, .. } => self.act(Action::Next),
            Gesture::Swipe { dir: Dir::Right | Dir::Down, .. } => self.act(Action::Prev),
        }
    }

    /// Runs until Exit, the power button, the sleep cover, or the idle timeout.
    pub fn run(&mut self) -> io::Result<()> {
        self.show(Show::Change)?;
        let idle = Duration::from_secs(self.config.idle_minutes * 60);
        loop {
            let ev = self.input.next(Duration::from_secs(1));
            let now = self.clock.mono_ms();
            let exit = match ev {
                None => !idle.is_zero() && now.saturating_sub(self.last_input) >= idle.as_millis() as u64,
                Some(ev) => {
                    self.last_input = now;
                    match ev {
                        RawEvent::Touch { phase, x, y } => {
                            let (cx, cy) = device::base_to_canvas(self.display.base_size(), self.rotation, x, y);
                            match self.gestures.feed(phase, cx, cy) {
                                Some(g) => self.gesture(g)?,
                                None => false,
                            }
                        }
                        RawEvent::Key(Key::Forward) => self.act(Action::Next)?,
                        RawEvent::Key(Key::Back) => self.act(Action::Prev)?,
                        RawEvent::Key(Key::Home) => self.act(Action::Back)?,
                        RawEvent::Key(Key::Power | Key::CoverClosed) => true,
                        RawEvent::Key(Key::CoverOpened) => false,
                        RawEvent::Orientation(r) => {
                            if self.follow_sensor && !self.locked {
                                self.rotate_to(r)?;
                            }
                            false
                        }
                    }
                }
            };
            if exit {
                return self.finish();
            }
        }
    }

    /// Ends the current article visit and page view, and saves the seq counter.
    pub fn finish(&mut self) -> io::Result<()> {
        self.end_visit()?;
        self.view = match std::mem::replace(&mut self.view, View::Stand) {
            View::Article(ed, _) => View::Edition(ed),
            v => v,
        };
        // Leaving ends the page's time on screen.
        let now = self.clock.mono_ms();
        if let Some(((edition, page), ms)) = self.meter.show(None, now) {
            self.record("pageView", |e| {
                e.edition_id = Some(edition);
                e.page = Some(page);
                e.ms = Some(ms as i64);
            })?;
        }
        let mut state = self.store.load_state()?;
        state.next_seq = state.next_seq.max(self.recorder.next_seq());
        self.store.save_state(&state)
    }
}

fn step(pos: &mut usize, len: usize, forward: bool) -> bool {
    if forward && *pos + 1 < len {
        *pos += 1;
        true
    } else if !forward && *pos > 0 {
        *pos -= 1;
        true
    } else {
        false
    }
}

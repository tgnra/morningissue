//! `morningissue read`: the home screen, Saved, Settings, an edition, an article, and what
//! moves between them. A sync runs on its own thread; what it brings is shown as it arrives.
//!
//! Refresh policy:
//! - changing screen or rotating flashes (a full GC16 refresh);
//! - a page turn is a non-flashing partial refresh, with a full flash every `flash_every`;
//! - showing or hiding the reading chrome or the "Aa" popover, and anything a sync brings
//!   (an edition, a picture, the status line), redraws only the pixels that changed, without
//!   a flash; pictures arriving in a burst are drawn together, at most every two seconds;
//! - Like, Save and the keypad redraw just what changed with the fast black-and-white
//!   waveform.

use crate::canvas::{Canvas, Rect};
use crate::config::Config;
use crate::device::{self, Clock, Display, Input, Key, RawEvent, Refresh};
use crate::events::{self, ArticleVisit, PageMeter, Recorder};
use crate::fonts::Fonts;
use crate::gesture::{Dir, Gesture, Recognizer};
use crate::layout::{self, Action, El, ImageCache, Metrics, Screen};
use crate::light::Light;
use crate::model::{Article, Bundle, EditionPage, PageKind, SavedList};
use crate::net::NetStatus;
use crate::screens::{self, Ctx};
use crate::settings::{self, Orientation, Settings};
use crate::store::{State, Store};
use crate::sync::{self, Progress};
use crate::time::{self, Date};
use crate::ui::{self, Foot, SyncShow};
use std::collections::HashSet;
use std::io;
use std::path::Path;
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver};
use std::sync::Arc;
use std::time::Duration;

/// The reading chrome hides itself after this long without a touch.
const CHROME_TIMEOUT_MS: u64 = 7_000;
/// Pictures that arrive in a burst are drawn together at most this often.
const BATCH_MS: u64 = 2_000;

struct Ed {
    id: i64,
    bundle: Rc<Bundle>,
    pages: Vec<EditionPage>,
    screens: Vec<(usize, Screen)>,
    pos: usize,
}

#[derive(Clone)]
enum Source {
    Edition,
    Saved,
}

struct Art {
    id: i64,
    source: Source,
    article: Article,
    screens: Vec<Screen>,
    pos: usize,
}

enum View {
    Home,
    Settings,
    Keypad { typed: String, error: Option<String> },
    Saved { screens: Vec<Screen>, pos: usize },
    Edition(Ed),
    Article(Art),
}

impl View {
    fn reading(&self) -> bool {
        matches!(self, View::Edition(_) | View::Article(_))
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum Show {
    /// A different screen: always a full flash.
    Change,
    /// A page turn: partial, with a periodic flash.
    Turn,
    /// Only what changed, with the fast black-and-white waveform.
    Touch,
    /// Only what changed, in greys, without a flash.
    Update,
}

enum SyncMsg {
    Progress(Progress),
    Done,
}

pub struct Options {
    /// The rotation Nickel left the screen in, as quarter turns from portrait.
    pub rotation: u8,
    /// The model's orientation sensor is mapped.
    pub follow_sensor: bool,
    pub light: Box<dyn Light>,
    /// Asked whether the Kobo is on a network.
    pub network: Box<dyn Fn() -> NetStatus>,
    /// Start a sync as soon as the home screen is up (when online).
    pub sync_on_start: bool,
    /// Told what can be touched after every frame, for scripted tests.
    pub hit_probe: Option<Box<dyn Fn(&[(Rect, String)])>>,
}

pub struct App {
    display: Box<dyn Display>,
    input: Box<dyn Input>,
    clock: Box<dyn Clock>,
    store: Store,
    state: State,
    config: Config,
    settings: Settings,
    fonts: Fonts,
    images: ImageCache,
    editions: Vec<Bundle>,
    saved: SavedList,
    nickel_rotation: u8,
    rotation: u8,
    follow_sensor: bool,
    light: Box<dyn Light>,
    network: Box<dyn Fn() -> NetStatus>,
    net: NetStatus,
    sync_on_start: bool,
    hit_probe: Option<Box<dyn Fn(&[(Rect, String)])>>,
    stack: Vec<View>,
    chrome: bool,
    popover: bool,
    chrome_at: u64,
    hits: Vec<(Rect, Action)>,
    chrome_hits: Vec<(Rect, Action)>,
    popover_hits: Vec<(Rect, Action)>,
    shown: Option<Canvas>,
    partials: u32,
    recorder: Recorder,
    meter: PageMeter,
    visit: Option<ArticleVisit>,
    gestures: Recognizer,
    opened_editions: HashSet<i64>,
    last_input: u64,
    sync: Option<Receiver<SyncMsg>>,
    sync_show: SyncShow,
    /// Something a sync brought is not on screen yet.
    stale: bool,
    last_update: u64,
    last_minute: u64,
    /// Every refresh, for tests: (mode, region).
    pub refreshes: Vec<(Refresh, Rect)>,
    /// How many syncs were started, for tests.
    pub syncs_started: usize,
}

impl App {
    pub fn new(display: Box<dyn Display>, input: Box<dyn Input>, clock: Box<dyn Clock>, store: Store, config: Config, fonts_dir: &Path, opts: Options) -> io::Result<App> {
        let state = store.load_state()?;
        let settings = Settings::load(&store.settings_path());
        let mut editions = store.load_editions(&state);
        let mut saved = store.load_saved();
        let pending = store.read_events()?;
        events::apply(&mut editions, &pending);
        for e in &pending {
            events::apply_saved(&mut saved, e);
        }
        let recorder = Recorder::new(&store, &state)?;
        let dpi = display.dpi();
        let fonts = Fonts::load(fonts_dir, dpi)?;
        let images_dir = store.images_dir();
        let images = ImageCache::new(Box::new(move |url| std::fs::read(images_dir.join(format!("{}.img", crate::store::image_key(url)))).ok()));
        let now = clock.mono_ms();
        let net = (opts.network)();
        let mut app = App {
            display,
            input,
            clock,
            store,
            state,
            config,
            settings,
            fonts,
            images,
            editions,
            saved,
            nickel_rotation: opts.rotation % 4,
            rotation: opts.rotation % 4,
            follow_sensor: opts.follow_sensor,
            light: opts.light,
            network: opts.network,
            net,
            sync_on_start: opts.sync_on_start,
            hit_probe: opts.hit_probe,
            stack: vec![View::Home],
            chrome: false,
            popover: false,
            chrome_at: 0,
            hits: Vec::new(),
            chrome_hits: Vec::new(),
            popover_hits: Vec::new(),
            shown: None,
            partials: 0,
            recorder,
            meter: PageMeter::default(),
            visit: None,
            gestures: Recognizer::new(dpi),
            opened_editions: HashSet::new(),
            last_input: now,
            sync: None,
            sync_show: SyncShow::default(),
            stale: false,
            last_update: now,
            last_minute: now,
            refreshes: Vec::new(),
            syncs_started: 0,
        };
        app.rotation = app.wanted_rotation(app.nickel_rotation);
        // The light as last chosen here.
        if app.light.present() {
            if let Some(b) = app.settings.brightness {
                app.light.set_brightness(b);
            }
            if let (Some(w), true) = (app.settings.warmth, app.light.has_warmth()) {
                app.light.set_warmth(w);
            }
        }
        Ok(app)
    }

    /// The rotation the Orientation setting asks for, given the current one.
    fn wanted_rotation(&self, current: u8) -> u8 {
        match self.settings.orientation {
            Orientation::Portrait => 0,
            Orientation::Landscape if current % 2 == 1 => current,
            Orientation::Landscape if self.nickel_rotation % 2 == 1 => self.nickel_rotation,
            Orientation::Landscape => 1,
            Orientation::Sensor => current,
        }
    }

    fn metrics(&self) -> Metrics {
        let (w, h) = device::canvas_size(self.display.base_size(), self.rotation);
        Metrics { w, h, dpi: self.display.dpi() }
    }

    fn top(&self) -> &View {
        self.stack.last().unwrap()
    }

    fn top_mut(&mut self) -> &mut View {
        self.stack.last_mut().unwrap()
    }

    fn page_key(&self) -> Option<(i64, i64)> {
        match self.top() {
            View::Edition(ed) => {
                let page = ed.screens.get(ed.pos).map(|s| s.0)?;
                Some((ed.id, ed.pages[page].number))
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
        events::apply_saved(&mut self.saved, &e);
        for v in self.stack.iter_mut() {
            if let View::Article(art) = v {
                if Some(art.id) == e.article_id {
                    events::apply_article(&mut art.article, &e);
                }
            }
        }
        Ok(())
    }

    // ----------------------------------------------------------------------------------------
    // Layout

    fn layout_edition(&self, bundle: Rc<Bundle>) -> Ed {
        let m = self.metrics();
        let ctx = Ctx::new(&self.fonts, m, &self.images);
        let pages = bundle.edition_pages();
        let latest = self.editions.first().is_some_and(|b| b.id == bundle.id);
        let screens = screens::edition_screens(&ctx, &bundle, &pages, latest);
        Ed { id: bundle.id, bundle, pages, screens, pos: 0 }
    }

    /// The edition laid out again (after a rotation, reading a story, or pictures arriving),
    /// on the same page.
    fn relayout_edition(&self, old: &Ed) -> Ed {
        let bundle = self.editions.iter().find(|b| b.id == old.id).map(|b| Rc::new(b.clone())).unwrap_or_else(|| old.bundle.clone());
        let page = old.screens.get(old.pos).map(|s| s.0);
        let k = old.screens[..old.pos].iter().filter(|s| Some(s.0) == page).count();
        let mut ed = self.layout_edition(bundle);
        if let Some(p) = page {
            let of_page: Vec<usize> = ed.screens.iter().enumerate().filter(|(_, s)| s.0 == p).map(|(i, _)| i).collect();
            ed.pos = of_page.get(k).or(of_page.last()).copied().unwrap_or(0);
        }
        ed
    }

    fn layout_article(&self, a: &Article) -> Vec<Screen> {
        let m = self.metrics();
        let ctx = Ctx::reading(&self.fonts, m, &self.images, self.settings.text_scale());
        screens::article_screens(&ctx, a).screens
    }

    /// The article laid out again, showing the screen with the same text at its top.
    fn relayout_article(&self, art: &mut Art) {
        let anchor = art.screens.get(art.pos).map(|s| s.anchor).unwrap_or(0.0);
        art.screens = self.layout_article(&art.article);
        art.pos = layout::screen_for_anchor(&art.screens, anchor);
    }

    fn layout_saved(&self) -> Vec<Screen> {
        let ctx = Ctx::new(&self.fonts, self.metrics(), &self.images);
        ui::saved_screens(&ctx, &self.saved)
    }

    /// Lays out every view on the stack again, keeping each one's place.
    fn relayout_all(&mut self) {
        let stack = std::mem::take(&mut self.stack);
        let mut out = Vec::with_capacity(stack.len());
        for v in stack {
            out.push(match v {
                View::Edition(old) => View::Edition(self.relayout_edition(&old)),
                View::Article(mut art) => {
                    self.relayout_article(&mut art);
                    View::Article(art)
                }
                View::Saved { pos, .. } => {
                    let screens = self.layout_saved();
                    View::Saved { pos: pos.min(screens.len() - 1), screens }
                }
                v => v,
            });
        }
        self.stack = out;
    }

    // ----------------------------------------------------------------------------------------
    // Drawing

    fn status(&self) -> (String, bool) {
        ui::status_line(&self.state, self.clock.wall_ms(), self.sync.as_ref().map(|_| &self.sync_show), self.net.online())
    }

    fn today(&self) -> Option<Date> {
        let ms = self.clock.wall_ms() + self.state.clock_offset_ms;
        let (y, m, d) = time::civil_from_days(ms.div_euclid(86_400_000));
        Some(Date { y, m, d })
    }

    fn render(&mut self) -> Canvas {
        let m = self.metrics();
        let mut canvas = Canvas::new(m.w, m.h);
        let ctx = Ctx::new(&self.fonts, m, &self.images);
        let mut chrome_hits = Vec::new();
        let mut popover_hits = Vec::new();
        let hits = match self.stack.last().unwrap() {
            View::Home => {
                let (status, err) = self.status();
                ui::draw_home(&ctx, &mut canvas, &ui::Home { editions: &self.editions, today: self.today(), status, status_is_error: err })
            }
            View::Settings => {
                let (last, err) = ui::status_line(&self.state, self.clock.wall_ms(), None, true);
                let view = ui::SettingsView {
                    wifi: self.net.describe(),
                    online: self.net.online(),
                    phone_address: self.settings.phone_address.as_deref(),
                    syncing: self.sync.is_some(),
                    last_result: if self.sync.is_some() { self.status().0 } else { last },
                    last_is_error: err && self.sync.is_none(),
                    invert_buttons: self.settings.invert_buttons,
                    orientation: self.settings.orientation,
                    sensor: self.follow_sensor,
                };
                ui::draw_settings(&ctx, &mut canvas, &view)
            }
            View::Keypad { typed, error } => ui::draw_keypad(&ctx, &mut canvas, typed, error.as_deref()),
            View::Saved { screens, pos } => {
                let s = &screens[*pos];
                layout::draw(&s.els, &self.fonts, &self.images, &mut canvas);
                let mut hits = s.hits.clone();
                let n = self.saved.items().len();
                hits.extend(ui::top_bar(&ctx, &mut canvas, "Home", "Saved", Some(&format!("{n} {}", if n == 1 { "article" } else { "articles" }))));
                let foot = Foot { position: if screens.len() > 1 { format!("{} of {}", pos + 1, screens.len()) } else { String::new() }, progress: 0.0, toggles: None, nav: screens.len() > 1 };
                hits.extend(ui::bottom_bar(&ctx, &mut canvas, &foot));
                hits
            }
            View::Edition(ed) => {
                let (pi, screen) = &ed.screens[ed.pos];
                layout::draw(&screen.els, &self.fonts, &self.images, &mut canvas);
                if self.chrome {
                    let page = &ed.pages[*pi];
                    let of_page: Vec<usize> = ed.screens.iter().enumerate().filter(|(_, s)| s.0 == *pi).map(|(i, _)| i).collect();
                    let mut position = format!("Page {} of {}", page.number, ed.pages.len());
                    if of_page.len() > 1 {
                        let k = of_page.iter().position(|&i| i == ed.pos).unwrap_or(0) + 1;
                        position.push_str(&format!("  \u{b7}  {k}/{}", of_page.len()));
                    }
                    let title = if page.kind == PageKind::Front { ed.bundle.kind_label() } else { screens::page_title(&page.kind) };
                    chrome_hits.extend(ui::top_bar(&ctx, &mut canvas, "Home", &title, None));
                    let foot = Foot { position, progress: (ed.pos + 1) as f32 / ed.screens.len() as f32, toggles: None, nav: true };
                    chrome_hits.extend(ui::bottom_bar(&ctx, &mut canvas, &foot));
                }
                screen.hits.clone()
            }
            View::Article(art) => {
                let screen = &art.screens[art.pos];
                layout::draw(&screen.els, &self.fonts, &self.images, &mut canvas);
                if self.chrome {
                    let g = &art.article.engagement;
                    let back = if matches!(art.source, Source::Saved) { "Saved" } else { "Edition" };
                    chrome_hits.extend(ui::top_bar(&ctx, &mut canvas, back, &art.article.feed_title, None));
                    let foot = Foot {
                        position: format!("{} of {}", art.pos + 1, art.screens.len()),
                        progress: (art.pos + 1) as f32 / art.screens.len() as f32,
                        toggles: Some((g.liked_at.is_some(), g.saved_at.is_some())),
                        nav: false,
                    };
                    chrome_hits.extend(ui::bottom_bar(&ctx, &mut canvas, &foot));
                    if self.popover {
                        let (b, w) = self.light.levels();
                        let p = ui::Popover {
                            text_size: self.settings.text_size,
                            brightness: self.light.present().then_some(b),
                            warmth: (self.light.present() && self.light.has_warmth()).then_some(w),
                        };
                        popover_hits = ui::draw_popover(&ctx, &mut canvas, &p);
                    }
                }
                screen.hits.clone()
            }
        };
        self.hits = hits;
        self.chrome_hits = chrome_hits;
        self.popover_hits = popover_hits;
        if let Some(probe) = &self.hit_probe {
            // In the order a tap looks: the popover, then the chrome, then the page.
            let layer = |h: &[(Rect, Action)]| h.iter().map(|(r, a)| (*r, format!("{a:?}"))).collect::<Vec<_>>();
            let mut all = layer(&self.hits);
            if self.chrome {
                all.extend(layer(&self.chrome_hits));
            }
            all.extend(layer(&self.popover_hits));
            probe(&all);
        }
        canvas
    }

    fn show(&mut self, how: Show) -> io::Result<()> {
        let canvas = self.render();
        let full = canvas.bounds();
        let diff = || self.shown.as_ref().filter(|s| s.w == canvas.w && s.h == canvas.h).map(|s| canvas.diff(s));
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
            Show::Touch | Show::Update => match diff() {
                Some(Some(r)) => (if how == Show::Touch { Refresh::Fast } else { Refresh::Partial }, r),
                Some(None) => return Ok(()),
                None => (Refresh::Full, full),
            },
        };
        if mode == Refresh::Full {
            self.partials = 0;
        }
        self.display.present(&canvas, self.rotation, region, mode)?;
        self.refreshes.push((mode, region));
        self.shown = Some(canvas);
        self.last_update = self.clock.mono_ms();
        self.stale = false;
        Ok(())
    }

    // ----------------------------------------------------------------------------------------
    // Moving between views

    fn push(&mut self, v: View) -> io::Result<()> {
        self.chrome = false;
        self.popover = false;
        self.stack.push(v);
        self.meter_update()?;
        self.show(Show::Change)
    }

    fn open_edition(&mut self, idx: usize) -> io::Result<()> {
        let Some(b) = self.editions.get(idx) else { return Ok(()) };
        let id = b.id;
        let bundle = Rc::new(b.clone());
        if self.opened_editions.insert(id) {
            self.record("editionOpened", |e| e.edition_id = Some(id))?;
        }
        let ed = self.layout_edition(bundle);
        self.push(View::Edition(ed))?;
        self.check_finished()
    }

    /// Reaching the back page finishes the edition.
    fn check_finished(&mut self) -> io::Result<()> {
        if let View::Edition(ed) = self.top() {
            let on_back = ed.screens.get(ed.pos).is_some_and(|s| s.0 + 1 == ed.pages.len());
            let id = ed.id;
            let finished = self.editions.iter().find(|b| b.id == id).map(|b| b.finished_at.is_some()).unwrap_or(ed.bundle.finished_at.is_some());
            if on_back && !finished {
                self.record("editionFinished", |e| e.edition_id = Some(id))?;
            }
        }
        Ok(())
    }

    fn open_article(&mut self, id: i64) -> io::Result<()> {
        let (source, article, edition_id) = match self.top() {
            View::Edition(ed) => match ed.bundle.article(id) {
                Some(a) => {
                    // The engagement as it stands now, not as the edition was opened.
                    let mut a = a.clone();
                    if let Some(now) = self.editions.iter().find(|b| b.id == ed.id).and_then(|b| b.article(id)) {
                        a.engagement = now.engagement.clone();
                    }
                    (Source::Edition, a, ed.id)
                }
                None => return Ok(()),
            },
            View::Saved { .. } => match self.saved.article(id) {
                Some(a) => (Source::Saved, a.clone(), 0),
                None => return Ok(()),
            },
            _ => return Ok(()),
        };
        let screens = self.layout_article(&article);
        let count = screens.len();
        let fresh = article.engagement.opened_at.is_none();
        let visit_fraction = article.engagement.read_fraction;
        let had_read = article.engagement.read_at.is_some();
        let words = article.word_count;
        self.stack.push(View::Article(Art { id, source, article, screens, pos: 0 }));
        self.chrome = false;
        self.popover = false;
        // The edition page is off screen now.
        self.meter_update()?;
        if fresh {
            self.record("opened", |e| e.article_id = Some(id))?;
        }
        let now = self.clock.mono_ms();
        let mut visit = ArticleVisit { edition_id, article_id: id, word_count: words, had_fraction: visit_fraction, had_read, furthest: 0.0, dwell_ms: 0, showing_since: None };
        visit.resume(now);
        visit.reached(0, count);
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
        Ok(())
    }

    fn back(&mut self) -> io::Result<()> {
        if self.stack.len() <= 1 {
            return Ok(());
        }
        let gone = self.stack.pop().unwrap();
        if matches!(gone, View::Article(_)) {
            self.end_visit()?;
        }
        self.chrome = false;
        self.popover = false;
        // Show the ticks and pictures that changed while away.
        let top = self.stack.pop().unwrap();
        let top = match top {
            View::Edition(old) => View::Edition(self.relayout_edition(&old)),
            View::Saved { pos, .. } => {
                let screens = self.layout_saved();
                View::Saved { pos: pos.min(screens.len() - 1), screens }
            }
            View::Settings => {
                self.net = (self.network)();
                View::Settings
            }
            v => v,
        };
        self.stack.push(top);
        self.meter_update()?;
        self.show(Show::Change)
    }

    fn turn(&mut self, forward: bool) -> io::Result<()> {
        let moved = match self.top_mut() {
            View::Edition(ed) => step(&mut ed.pos, ed.screens.len(), forward),
            View::Article(art) => step(&mut art.pos, art.screens.len(), forward),
            View::Saved { screens, pos } => step(pos, screens.len(), forward),
            _ => false,
        };
        if !moved {
            return Ok(());
        }
        self.chrome = false;
        self.popover = false;
        if let (View::Article(art), Some(v)) = (self.stack.last().unwrap(), self.visit.as_mut()) {
            v.reached(art.pos, art.screens.len());
        }
        self.meter_update()?;
        self.check_finished()?;
        self.show(Show::Turn)
    }

    fn toggle(&mut self, like: bool) -> io::Result<()> {
        let View::Article(art) = self.top() else { return Ok(()) };
        let id = art.id;
        let g = &art.article.engagement;
        let on = if like { g.liked_at.is_none() } else { g.saved_at.is_none() };
        self.record(if like { "liked" } else { "saved" }, |e| {
            e.article_id = Some(id);
            e.value = Some(serde_json::json!(on));
        })?;
        self.chrome_at = self.clock.mono_ms();
        self.show(Show::Touch)
    }

    fn rotate_to(&mut self, r: u8) -> io::Result<()> {
        let r = r % 4;
        if r == self.rotation {
            return Ok(());
        }
        self.rotation = r;
        self.relayout_all();
        self.gestures = Recognizer::new(self.display.dpi());
        self.popover = false;
        self.show(Show::Change)
    }

    fn save_settings(&mut self) {
        if let Err(e) = self.settings.save(&self.store.settings_path()) {
            eprintln!("settings: {e}");
        }
    }

    fn set_chrome(&mut self, on: bool) -> io::Result<()> {
        if self.chrome == on && !self.popover {
            return Ok(());
        }
        self.chrome = on;
        self.popover = false;
        self.chrome_at = self.clock.mono_ms();
        self.show(Show::Update)
    }

    fn set_light(&mut self, warmth: bool, v: i32) -> io::Result<()> {
        let v = v.clamp(0, 100) as u8;
        if warmth {
            self.light.set_warmth(v);
            self.settings.warmth = Some(v);
        } else {
            self.light.set_brightness(v);
            self.settings.brightness = Some(v);
        }
        self.save_settings();
        self.chrome_at = self.clock.mono_ms();
        self.show(Show::Update)
    }

    /// Returns true to exit.
    fn act(&mut self, a: Action, at: (i32, i32)) -> io::Result<bool> {
        match a {
            Action::OpenEdition(i) => self.open_edition(i)?,
            Action::OpenArticle(id) => self.open_article(id)?,
            Action::OpenSaved => {
                let screens = self.layout_saved();
                self.push(View::Saved { screens, pos: 0 })?;
            }
            Action::OpenSettings => {
                self.net = (self.network)();
                self.push(View::Settings)?;
            }
            Action::Back => self.back()?,
            Action::Next => self.turn(true)?,
            Action::Prev => self.turn(false)?,
            Action::Like => self.toggle(true)?,
            Action::Save => self.toggle(false)?,
            Action::TextAndLight => {
                self.popover = !self.popover;
                self.chrome_at = self.clock.mono_ms();
                self.show(Show::Update)?;
            }
            Action::Nothing => {}
            Action::SetTextSize(i) => {
                if i != self.settings.text_size && i < settings::TEXT_SIZES.len() {
                    self.settings.text_size = i;
                    self.save_settings();
                    let mut stack = std::mem::take(&mut self.stack);
                    for v in stack.iter_mut() {
                        if let View::Article(art) = v {
                            self.relayout_article(art);
                            if let Some(visit) = self.visit.as_mut() {
                                visit.reached(art.pos, art.screens.len());
                            }
                        }
                    }
                    self.stack = stack;
                    self.chrome_at = self.clock.mono_ms();
                    self.show(Show::Update)?;
                }
            }
            Action::Slider { warmth, x0, w } => self.set_light(warmth, ((at.0 - x0) as f32 * 100.0 / w.max(1) as f32).round() as i32)?,
            Action::Nudge { warmth, by } => {
                let (b, w) = self.light.levels();
                let cur = if warmth { w } else { b } as i32;
                // Steps of ten, landing on round numbers.
                let next = if by > 0 { (cur / 10 + 1) * 10 } else { ((cur + 9) / 10 - 1) * 10 };
                self.set_light(warmth, next)?;
            }
            Action::SyncNow => {
                self.start_sync();
                self.show(Show::Update)?;
            }
            Action::PhoneAuto => {
                self.settings.phone_address = None;
                self.save_settings();
                self.show(Show::Touch)?;
            }
            Action::PhoneTyped => {
                let typed = self.settings.phone_address.as_deref().map(|a| a.strip_suffix(&format!(":{}", settings::DEFAULT_PORT)).unwrap_or(a).to_string()).unwrap_or_default();
                self.push(View::Keypad { typed, error: None })?;
            }
            Action::InvertButtons => {
                self.settings.invert_buttons = !self.settings.invert_buttons;
                self.save_settings();
                self.show(Show::Touch)?;
            }
            Action::SetOrientation(o) => {
                if o != self.settings.orientation {
                    self.settings.orientation = o;
                    self.save_settings();
                    let r = self.wanted_rotation(self.rotation);
                    if r != self.rotation {
                        self.rotate_to(r)?;
                    } else {
                        self.show(Show::Touch)?;
                    }
                }
            }
            Action::KeyChar(c) => {
                if let View::Keypad { typed, error } = self.top_mut() {
                    if typed.len() < 21 {
                        typed.push(c);
                    }
                    *error = None;
                }
                self.show(Show::Touch)?;
            }
            Action::KeyDelete => {
                if let View::Keypad { typed, error } = self.top_mut() {
                    typed.pop();
                    *error = None;
                }
                self.show(Show::Touch)?;
            }
            Action::KeyOk => {
                let typed = match self.top() {
                    View::Keypad { typed, .. } => typed.clone(),
                    _ => return Ok(false),
                };
                match settings::parse_address(&typed) {
                    Ok(a) => {
                        self.settings.phone_address = Some(a);
                        self.save_settings();
                        self.back()?;
                    }
                    Err(e) => {
                        if let View::Keypad { error, .. } = self.top_mut() {
                            *error = Some(e.to_string());
                        }
                        self.show(Show::Update)?;
                    }
                }
            }
            Action::Exit => return Ok(true),
        }
        Ok(false)
    }

    fn tap(&mut self, x: i32, y: i32) -> io::Result<bool> {
        let find = |hits: &[(Rect, Action)]| hits.iter().rev().find(|(r, _)| r.contains(x, y)).map(|(_, a)| a.clone());
        if self.popover {
            if let Some(a) = find(&self.popover_hits) {
                return self.act(a, (x, y));
            }
            // A tap outside the popover closes it, and nothing else.
            self.popover = false;
            self.chrome_at = self.clock.mono_ms();
            self.show(Show::Update)?;
            return Ok(false);
        }
        let reading = self.top().reading();
        if reading && self.chrome {
            if let Some(a) = find(&self.chrome_hits) {
                self.chrome_at = self.clock.mono_ms();
                return self.act(a, (x, y));
            }
            // A tap on the page hides the chrome.
            self.set_chrome(false)?;
            return Ok(false);
        }
        if let Some(a) = find(&self.hits) {
            return self.act(a, (x, y));
        }
        let m = self.metrics();
        // The top edge of a page shows the chrome too, for those who tap rather than swipe.
        if reading && (y as f32) < m.inch(0.35) {
            self.set_chrome(true)?;
            return Ok(false);
        }
        // Tap zones, so a page can be turned by touch anywhere outside a story.
        match self.top() {
            View::Article(_) => self.act(if x < m.w / 3 { Action::Prev } else { Action::Next }, (x, y)),
            View::Edition(_) | View::Saved { .. } if x < m.w / 5 => self.act(Action::Prev, (x, y)),
            View::Edition(_) | View::Saved { .. } if x > m.w * 4 / 5 => self.act(Action::Next, (x, y)),
            _ => Ok(false),
        }
    }

    fn gesture(&mut self, g: Gesture) -> io::Result<bool> {
        let m = self.metrics();
        let reading = self.top().reading();
        let pages = reading || matches!(self.top(), View::Saved { .. });
        match g {
            Gesture::Tap { x, y } => self.tap(x, y),
            // A swipe down from the top edge brings the chrome.
            Gesture::Swipe { dir: Dir::Down, y, .. } if reading && (y as f32) < m.inch(0.5) => {
                self.set_chrome(true)?;
                Ok(false)
            }
            Gesture::Swipe { dir: Dir::Up, y, .. } if reading && self.chrome && (y as f32) > m.h as f32 - m.inch(0.5) => {
                self.set_chrome(false)?;
                Ok(false)
            }
            Gesture::Swipe { dir: Dir::Left | Dir::Up, .. } if pages => self.act(Action::Next, (0, 0)),
            Gesture::Swipe { dir: Dir::Right | Dir::Down, .. } if pages => self.act(Action::Prev, (0, 0)),
            Gesture::Swipe { .. } => Ok(false),
        }
    }

    fn key(&mut self, k: Key) -> io::Result<bool> {
        let k = match (k, self.settings.invert_buttons) {
            (Key::Forward, true) => Key::Back,
            (Key::Back, true) => Key::Forward,
            (k, _) => k,
        };
        match k {
            Key::Forward => self.act(Action::Next, (0, 0)),
            Key::Back => self.act(Action::Prev, (0, 0)),
            Key::Home => self.act(Action::Back, (0, 0)),
            Key::Power | Key::CoverClosed => Ok(true),
            Key::CoverOpened => Ok(false),
        }
    }

    // ----------------------------------------------------------------------------------------
    // Sync

    /// Starts a sync on its own thread, if the Kobo is online and none is running. Offline,
    /// nothing is tried.
    pub fn start_sync(&mut self) {
        if self.sync.is_some() {
            return;
        }
        self.net = (self.network)();
        if !self.net.online() {
            eprintln!("sync: offline, not trying");
            return;
        }
        let (tx, rx) = mpsc::channel();
        let mut opts = sync::Options::from_config(&self.config);
        opts.manual_phone = self.settings.phone_address.clone();
        let (bw, bh) = self.display.base_size();
        opts.image_width = bw.min(bh).max(1) as u32;
        let ptx = tx.clone();
        opts.progress = Some(Arc::new(move |p| {
            let _ = ptx.send(SyncMsg::Progress(p));
        }));
        let home = self.store.home.clone();
        let spawned = std::thread::Builder::new().name("sync".into()).spawn(move || {
            // A panic in a sync is logged by the panic hook and ends that sync only; the
            // reader goes on with what is stored.
            let store = Store::new(&home);
            let r = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sync::sync(&store, &opts))) {
                Ok(r) => r,
                Err(_) => {
                    eprintln!("sync: crashed; the reader goes on with what it has");
                    let _ = std::fs::remove_file(store.home.join("data").join("panic.txt"));
                    let _ = tx.send(SyncMsg::Done);
                    return;
                }
            };
            match &r {
                Ok(r) => eprintln!(
                    "synced with {}: {} events sent, editions fetched {:?}, removed {:?}, saved list {}, {} images fetched, {} not on the phone",
                    r.address,
                    r.events_sent,
                    r.editions_fetched,
                    r.editions_removed,
                    if r.saved_fetched { "fetched" } else { "unchanged" },
                    r.images_fetched,
                    r.images_missing
                ),
                Err(e) => eprintln!("sync failed: {e}"),
            }
            let _ = tx.send(SyncMsg::Done);
        });
        if let Err(e) = spawned {
            eprintln!("sync: cannot start: {e}");
            return;
        }
        self.sync = Some(rx);
        self.syncs_started += 1;
        self.sync_show = SyncShow::default();
        self.images.syncing.set(true);
        self.images.missing.borrow_mut().clear();
    }

    /// Reloads what is stored, with the events not yet sent applied on top.
    fn reload(&mut self, editions: bool, saved: bool) {
        let pending = self.store.read_events().unwrap_or_default();
        if editions {
            if let Ok(s) = self.store.load_state() {
                self.state = s;
            }
            self.editions = self.store.load_editions(&self.state);
            events::apply(&mut self.editions, &pending);
        }
        if saved {
            self.saved = self.store.load_saved();
            for e in &pending {
                events::apply_saved(&mut self.saved, e);
            }
        }
    }

    /// The picture URLs on the screen now.
    fn urls_on_screen(&self) -> Vec<String> {
        let els: &[El] = match self.top() {
            View::Edition(ed) => &ed.screens[ed.pos].1.els,
            View::Article(art) => &art.screens[art.pos].els,
            View::Home => {
                return self.editions.iter().take(3).filter_map(|b| b.edition_pages().first().and_then(|p| p.lead.or(p.stories.first().copied())).and_then(|id| b.article(id)?.image_url.clone())).collect();
            }
            _ => &[],
        };
        els.iter().filter_map(|e| if let El::Image { url, .. } = e { Some(url.clone()) } else { None }).collect()
    }

    /// Takes in what the sync thread has sent, and redraws what it changed.
    fn poll_sync(&mut self) -> io::Result<()> {
        let Some(rx) = &self.sync else { return Ok(()) };
        let msgs: Vec<SyncMsg> = rx.try_iter().collect();
        if msgs.is_empty() {
            return Ok(());
        }
        let mut editions = false;
        let mut saved = false;
        let mut done = false;
        let mut arrived: Vec<String> = Vec::new();
        for msg in msgs {
            match msg {
                SyncMsg::Progress(Progress::Connected { .. }) => {}
                SyncMsg::Progress(Progress::Edition(_)) => editions = true,
                SyncMsg::Progress(Progress::Saved) => saved = true,
                SyncMsg::Progress(Progress::ImagesQueued(urls)) => self.sync_show.images_total = urls.len(),
                SyncMsg::Progress(Progress::Image(url)) => {
                    self.images.forget(&url);
                    self.sync_show.images_done += 1;
                    arrived.push(url);
                }
                SyncMsg::Progress(Progress::ImageMissing(url)) => {
                    self.sync_show.images_done += 1;
                    self.images.missing.borrow_mut().insert(url);
                }
                SyncMsg::Done => done = true,
            }
        }
        if editions || saved || done {
            self.reload(editions || done, saved || done);
        }
        if done {
            self.sync = None;
            self.images.syncing.set(false);
            // Places kept for pictures that never came are given back to the text.
            let missing = self.urls_on_screen().iter().any(|u| self.images.dims(u).is_none());
            let placeholders = self.stack.iter().any(|v| matches!(v, View::Article(_) | View::Edition(_)));
            if missing && placeholders {
                self.relayout_all();
            }
        }
        // What to redraw now: the home and list screens show new data at once; a page being
        // read only gets its pictures.
        let on_screen = self.urls_on_screen();
        let pictures = arrived.iter().any(|u| on_screen.contains(u));
        match self.top() {
            View::Home | View::Settings => {
                if editions || done {
                    self.show(Show::Update)?;
                } else {
                    self.stale = true;
                }
            }
            View::Saved { .. } if saved || done => {
                let (pos, _) = match self.top() {
                    View::Saved { pos, screens } => (*pos, screens.len()),
                    _ => unreachable!(),
                };
                let screens = self.layout_saved();
                *self.top_mut() = View::Saved { pos: pos.min(screens.len() - 1), screens };
                self.show(Show::Update)?;
            }
            _ if done => self.show(Show::Update)?,
            _ if pictures => self.stale = true,
            _ => {}
        }
        Ok(())
    }

    /// Runs until Exit, the power button, the sleep cover, or the idle timeout.
    pub fn run(&mut self) -> io::Result<()> {
        self.show(Show::Change)?;
        if self.sync_on_start {
            self.start_sync();
            if self.sync.is_some() {
                self.show(Show::Update)?;
            }
        }
        let idle = Duration::from_secs(self.config.idle_minutes * 60);
        loop {
            let wait = if self.sync.is_some() { Duration::from_millis(250) } else { Duration::from_secs(1) };
            let ev = self.input.next(wait);
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
                        RawEvent::Key(k) => self.key(k)?,
                        RawEvent::Orientation(r) => {
                            if self.follow_sensor && self.settings.orientation == Orientation::Sensor {
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
            self.poll_sync()?;
            let now = self.clock.mono_ms();
            if self.stale && now.saturating_sub(self.last_update) >= BATCH_MS {
                self.show(Show::Update)?;
            }
            if self.chrome && !self.popover && now.saturating_sub(self.chrome_at) >= CHROME_TIMEOUT_MS && self.top().reading() {
                self.set_chrome(false)?;
            }
            // Once a minute the home and settings screens check the network and the clock,
            // so "Offline" and "12 minutes ago" stay true. Nothing is drawn if nothing changed.
            if now.saturating_sub(self.last_minute) >= 60_000 {
                self.last_minute = now;
                if matches!(self.top(), View::Home | View::Settings) {
                    self.net = (self.network)();
                    self.show(Show::Update)?;
                }
            }
        }
    }

    /// Ends the current article visit and page view, saves the seq counter, and puts the
    /// light back as Nickel left it.
    pub fn finish(&mut self) -> io::Result<()> {
        if matches!(self.top(), View::Article(_)) {
            self.end_visit()?;
        }
        while matches!(self.top(), View::Article(_)) {
            self.stack.pop();
        }
        // Leaving ends the page's time on screen.
        let now = self.clock.mono_ms();
        if let Some(((edition, page), ms)) = self.meter.show(None, now) {
            self.record("pageView", |e| {
                e.edition_id = Some(edition);
                e.page = Some(page);
                e.ms = Some(ms as i64);
            })?;
        }
        self.light.restore();
        let mut state = self.store.load_state()?;
        state.next_seq = state.next_seq.max(self.recorder.next_seq());
        self.store.save_state(&state)
    }

    /// Whether a sync is running, for tests.
    pub fn syncing(&self) -> bool {
        self.sync.is_some()
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

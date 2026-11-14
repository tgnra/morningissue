//! `morningissue read`: the home screen, Saved, Settings, an edition, an article, and what
//! moves between them. A sync runs on its own thread; what it brings is shown as it arrives.
//!
//! Refresh policy:
//! - changing screen or rotating flashes (a full GC16 refresh);
//! - a page turn is a non-flashing partial refresh, with a full flash every `flash_every`;
//! - showing or hiding the reading chrome or the "Aa" popover, and anything a sync brings
//!   (an edition, a picture, the status line), redraws only the pixels that changed, without
//!   a flash; pictures arriving in a burst are drawn together, at most every two seconds;
//! - Like and Save, and each key typed on the server address keyboard, redraw just what
//!   changed with the fast black-and-white waveform.

use crate::canvas::{Canvas, Rect};
use crate::config::Config;
use crate::device::{self, Clock, Display, Input, Key, Power, Radio, RawEvent, Refresh};
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
use crate::sync::{self, Progress, SyncError};
use crate::time::{self, Date};
use crate::ui::{self, Foot, Retry, SyncShow};
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
/// A sensor reading must hold this long before the page turns upside down to follow it.
const SENSOR_SETTLE_MS: u64 = 1_500;

// Sleep. The power button, closing the sleep cover, or `idleMinutes` without input put the
// Kobo to sleep; the power button or opening the cover wakes it on the page it was showing.
// The timings come from KOReader (`frontend/device/generic/device.lua` `onPowerEvent`,
// `frontend/device/kobo/device.lua` `Kobo:suspend`, `_doSuspend`, `Kobo:resume`,
// `checkUnexpectedWakeup`) and Plato (`crates/plato/src/app.rs`, `scripts/suspend.sh`):
//
// 1. the sleep screen is drawn with a full refresh;
// 2. after PREPARE_MS (Plato's PREPARE_SUSPEND_WAIT_DELAY, time for the refresh to finish),
//    Wi-Fi is turned off and the light goes off;
// 3. after SUSPEND_WAIT_MS (both projects' 15 s: suspending sooner tends to fail), the
//    subsystems are flagged (`state-extended` 1);
// 4. FLAG_MS later (both projects sleep 2 s between the two writes), the device suspends;
// 5. after it wakes, or when the kernel refuses, the flag is cleared; unless the power button
//    or the cover wakes the reader within SUSPEND_WAIT_MS, it tries again from step 3, up to
//    MAX_ATTEMPTS times (KOReader gives up after 20 unexpected wake-ups).
//
// A power press or the cover opening at any point wakes the reader at once.
const PREPARE_MS: u64 = 3_000;
const SUSPEND_WAIT_MS: u64 = 15_000;
const FLAG_MS: u64 = 2_000;
const MAX_ATTEMPTS: u32 = 20;
/// Touches this soon after waking are dropped, as are the ones queued while asleep.
const WAKE_TOUCH_GUARD_MS: u64 = 500;

// Wi-Fi turned on by the reader. "Turn on Wi-Fi and sync" in Settings runs the radio's "on"
// job, syncs once the Kobo has an address, and turns Wi-Fi off again when that sync ends. A
// sync that got through to the server is a success even when it brought nothing. If the
// server can't be reached (or broke off), or doesn't know this Kobo yet because the phone
// hasn't registered its pairing code, Wi-Fi stays on and the sync is tried again every
// RETRY_MS, until one gets through or Turn Wi-Fi off is tapped. Sleep (including the idle
// timeout) ends it as always, by turning Wi-Fi off. Any other failure turns Wi-Fi off. A Kobo
// with no server address, or that has never shown its pairing code, doesn't turn Wi-Fi on at
// all: the button opens the address keyboard, or the code, instead.
const RETRY_MS: u64 = 5_000;

// While the pairing code is shown (and the Kobo is online, has a server address, and is not
// paired already), the server is asked every PAIR_POLL_MS whether it knows this Kobo yet:
// `GET /api/v1/kobo/hello`, answered `401 unknown` until the phone has scanned the code and
// registered it. Once it is known, the reader goes back to Settings and syncs. The phone does
// not need to stay open.
const PAIR_POLL_MS: u64 = 5_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SleepStage {
    /// The sleep screen is up; Wi-Fi and the light are still on.
    Screen,
    /// Wi-Fi and the light are off; waiting to flag the subsystems.
    Waiting,
    /// `state-extended` is 1; waiting to suspend.
    Flagged,
}

struct Sleep {
    stage: SleepStage,
    /// When the next step is due (monotonic ms).
    at: u64,
    /// Attempts that ended without the power button or the cover waking the reader.
    attempts: u32,
}

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

// Views sit on a short stack, so the size of the reading ones costs nothing worth boxing for.
#[allow(clippy::large_enum_variant)]
enum View {
    Home,
    Settings,
    /// The pairing code, with the URI it shows.
    Pair { uri: String },
    /// The server address keyboard: what is typed so far, and why OK refused it.
    ServerKeys { typed: String, error: Option<String> },
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
    Done(Finish),
}

/// How a sync ended, for Wi-Fi the reader turned on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Finish {
    /// Through to the server, even with nothing new.
    Ok,
    /// Worth trying again, for this reason.
    Retry(Retry),
    /// Anything else: trying again would end the same way.
    Failed,
}

fn finish_of(r: &Result<sync::SyncReport, SyncError>) -> Finish {
    match r {
        Ok(_) => Finish::Ok,
        // A server that can't be reached (or broke off).
        Err(SyncError::Server(_)) => Finish::Retry(Retry::Unreachable),
        // One that doesn't know this Kobo yet, which it does as soon as the phone has scanned
        // the code.
        Err(SyncError::NotListed { waiting: true }) => Finish::Retry(Retry::Waiting),
        // Never paired, removed from the account (pair again), no address, a server that
        // answers something unexpected, a certificate the clock can't check: trying again
        // doesn't help.
        Err(_) => Finish::Failed,
    }
}

pub struct Options {
    /// The rotation Nickel left the screen in, as quarter turns from portrait.
    pub rotation: u8,
    /// The model's orientation sensor is mapped, so it turns the page upside down within the
    /// chosen orientation.
    pub follow_sensor: bool,
    pub light: Box<dyn Light>,
    /// Puts the device to sleep.
    pub power: Box<dyn Power>,
    /// Asked whether the Kobo is on a network.
    pub network: Box<dyn Fn() -> NetStatus>,
    /// Turns Wi-Fi on and off for "Turn on Wi-Fi and sync" and "Turn Wi-Fi off".
    pub radio: Box<dyn Radio>,
    /// Start a sync as soon as the home screen is up (when online).
    pub sync_on_start: bool,
    /// Launched by KoboUI (`KOBOUI_MODE=run`): KoboUI owns Wi-Fi and sync, so the reader
    /// never turns Wi-Fi on or syncs, and Settings says to sync from KoboUI.
    pub managed: bool,
    /// Told what can be touched after every frame, for scripted tests.
    pub hit_probe: Option<HitProbe>,
}

/// Told what can be touched after a frame.
pub type HitProbe = Box<dyn Fn(&[(Rect, String)])>;

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
    /// Articles Liked on this Kobo (`data/liked.json`), oldest first.
    liked: Vec<i64>,
    nickel_rotation: u8,
    rotation: u8,
    follow_sensor: bool,
    /// The sensor's last reading, as a rotation.
    last_sensor: Option<u8>,
    /// A sensor reading waiting to settle before the page turns to it: (rotation, since).
    pending_flip: Option<(u8, u64)>,
    light: Box<dyn Light>,
    power: Box<dyn Power>,
    /// Set while the Kobo is going to sleep or asleep.
    sleep: Option<Sleep>,
    cover_closed: bool,
    /// The light was turned off for sleep.
    light_off: bool,
    /// Wi-Fi has been turned off for sleep once (the reader never turns it back on).
    wifi_off_done: bool,
    /// The connection was lost to sleep, so "Offline" says why.
    offline_since_sleep: bool,
    radio: Box<dyn Radio>,
    /// A Wi-Fi job is running: Some(true) turning on, Some(false) turning off.
    wifi_job: Option<bool>,
    /// The reader turned Wi-Fi on to sync, and turns it off when the sync is done.
    wifi_auto: bool,
    /// The sync on that Wi-Fi is to be tried again: the next try is due then (monotonic ms).
    retry_at: Option<u64>,
    /// Why the sync on that Wi-Fi is being tried again, until one gets through.
    retry: Option<Retry>,
    /// Why turning Wi-Fi on did not lead to a connection, until the next try.
    wifi_error: Option<String>,
    /// The pairing code is shown and the server doesn't know this Kobo: the next time to ask
    /// it is due then (monotonic ms).
    pair_poll_at: Option<u64>,
    /// Asking the server whether it knows this Kobo; true once it does.
    pair_probe: Option<Receiver<bool>>,
    /// Settings > Reset was tapped and awaits Erase everything or Cancel.
    reset_armed: bool,
    /// The Settings page showing, and how many there are on this screen.
    settings_page: usize,
    settings_pages: usize,
    /// Touches before this (monotonic ms) are dropped: they were made while waking.
    ignore_touches_until: u64,
    network: Box<dyn Fn() -> NetStatus>,
    net: NetStatus,
    sync_on_start: bool,
    managed: bool,
    hit_probe: Option<HitProbe>,
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
            events::apply_saved(&mut saved, e, |id| events::find_in(&editions, id));
        }
        let liked = store.load_liked();
        let recorder = Recorder::new(&store, &state)?;
        let dpi = display.dpi();
        let fonts = Fonts::load(fonts_dir, dpi)?;
        let images_dir = store.images_dir();
        let images = ImageCache::new(Box::new(move |url| std::fs::read(images_dir.join(format!("{}.img", crate::store::image_key(url)))).ok()));
        let now = clock.mono_ms();
        let mut opts = opts;
        if opts.managed {
            // KoboUI owns Wi-Fi: the reader sees no network and can't switch the radio.
            opts.network = Box::new(|| NetStatus::Offline);
            opts.radio = Box::new(crate::device::NoRadio);
        }
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
            liked,
            nickel_rotation: opts.rotation % 4,
            rotation: opts.rotation % 4,
            follow_sensor: opts.follow_sensor,
            last_sensor: None,
            pending_flip: None,
            light: opts.light,
            power: opts.power,
            sleep: None,
            cover_closed: false,
            light_off: false,
            wifi_off_done: false,
            offline_since_sleep: false,
            radio: opts.radio,
            wifi_job: None,
            wifi_auto: false,
            retry_at: None,
            retry: None,
            wifi_error: None,
            pair_poll_at: None,
            pair_probe: None,
            reset_armed: false,
            settings_page: 0,
            settings_pages: 1,
            ignore_touches_until: 0,
            network: opts.network,
            net,
            sync_on_start: opts.sync_on_start && !opts.managed,
            managed: opts.managed,
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
        // Not chosen yet (or 0.2.3's "follow the sensor"): the orientation the screen is in.
        if app.settings.orientation.is_none() {
            app.settings.orientation = Some(if app.nickel_rotation % 2 == 1 { Orientation::Landscape } else { Orientation::Portrait });
        }
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

    fn orientation(&self) -> Orientation {
        self.settings.orientation.unwrap_or(Orientation::Portrait)
    }

    /// The rotation the Orientation setting asks for, given the current one: the current one
    /// if it already belongs to it; else the way the sensor last said the Kobo is held, if
    /// that belongs to it; else the way Nickel left the screen, if that does; else upright.
    fn wanted_rotation(&self, current: u8) -> u8 {
        let parity = self.orientation().parity();
        let held = self.last_sensor.filter(|_| self.follow_sensor);
        [Some(current), held, Some(self.nickel_rotation)].into_iter().flatten().find(|r| r % 2 == parity).unwrap_or(parity)
    }

    /// A reading from the orientation sensor. Within the chosen orientation it turns the page
    /// upside down to match how the Kobo is held, once the reading has held for
    /// SENSOR_SETTLE_MS (a brief tilt does nothing); the other orientation is ignored.
    fn sensor(&mut self, r: u8) {
        self.last_sensor = Some(r % 4);
        let r = r % 4;
        self.pending_flip = (self.follow_sensor && r % 2 == self.orientation().parity() && r != self.rotation).then(|| (r, self.clock.mono_ms()));
    }

    /// Turns the page once a sensor reading has settled; with a full refresh.
    fn settle_flip(&mut self) -> io::Result<()> {
        if let Some((r, since)) = self.pending_flip {
            if self.clock.mono_ms().saturating_sub(since) >= SENSOR_SETTLE_MS {
                self.pending_flip = None;
                self.rotate_to(r)?;
            }
        }
        Ok(())
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
        // The open article first: one opened from Saved may be in no stored edition.
        let open = self.stack.iter().find_map(|v| match v {
            View::Article(art) if Some(art.id) == e.article_id => Some(art.article.clone()),
            _ => None,
        });
        events::apply_saved(&mut self.saved, &e, |id| open.clone().or_else(|| events::find_in(&self.editions, id)));
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

    fn layout_article(&self, id: i64, a: &Article) -> Vec<Screen> {
        let m = self.metrics();
        let ctx = Ctx::reading(&self.fonts, m, &self.images, self.settings.text_scale());
        screens::article_screens(&ctx, a, (self.is_liked(id), a.engagement.saved_at.is_some())).screens
    }

    fn is_liked(&self, id: i64) -> bool {
        self.liked.contains(&id)
    }

    /// The article laid out again, showing the screen with the same text at its top.
    fn relayout_article(&self, art: &mut Art) {
        let anchor = art.screens.get(art.pos).map(|s| s.anchor).unwrap_or(0.0);
        art.screens = self.layout_article(art.id, &art.article);
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
        if self.managed {
            // Under KoboUI there's no "Offline": the last sync KoboUI ran, or its error.
            return ui::status_line(&self.state, self.clock.wall_ms(), None, true);
        }
        match self.wifi_job {
            Some(true) => return ("Turning Wi-Fi on\u{2026}".into(), false),
            Some(false) => return ("Turning Wi-Fi off\u{2026}".into(), false),
            None => {}
        }
        if let Some(why) = self.retrying() {
            return (ui::retry_status(why).into(), false);
        }
        if let Some(e) = self.wifi_error.as_ref().filter(|_| !self.net.online()) {
            return (e.clone(), true);
        }
        if self.offline_since_sleep && !self.net.online() && self.sync.is_none() {
            return (ui::OFFLINE_SINCE_SLEEP.to_string(), false);
        }
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
        let mut settings_pages = self.settings_pages;
        let hits = match self.stack.last().unwrap() {
            View::Home => {
                let (status, err) = self.status();
                ui::draw_home(&ctx, &mut canvas, &ui::Home { editions: &self.editions, today: self.today(), status, status_is_error: err })
            }
            View::Settings => {
                let server = self.server_address();
                let (last, err) = ui::status_line(&self.state, self.clock.wall_ms(), None, true);
                let view = ui::SettingsView {
                    wifi: self.net.describe(),
                    online: self.net.online(),
                    wifi_job: self.wifi_job,
                    wifi_auto: self.wifi_auto,
                    retrying: self.retrying(),
                    slept: self.offline_since_sleep,
                    syncing: self.sync.is_some(),
                    last_result: if self.sync.is_some() || self.wifi_job.is_some() || self.retrying().is_some() || self.wifi_error.is_some() { self.status().0 } else { last },
                    last_is_error: if self.wifi_error.is_some() && self.wifi_job.is_none() && !self.net.online() { true } else { err && self.sync.is_none() && self.retrying().is_none() },
                    invert_buttons: self.settings.invert_buttons,
                    orientation: self.orientation(),
                    sensor: self.follow_sensor,
                    reset_armed: self.reset_armed,
                    server_address: server.as_deref(),
                    server_from_config: self.config.server_address().is_some(),
                    pairing_note: ui::pairing_note(&self.state, server.as_deref()),
                    managed: self.managed,
                };
                let (hits, pages) = ui::draw_settings(&ctx, &mut canvas, &view, self.settings_page);
                settings_pages = pages;
                hits
            }
            View::Pair { uri } => {
                let server = self.server_address();
                let note = if self.managed && !self.pair_polling() && server.is_some() && !(self.state.server_seen && !self.state.server_not_listed) {
                    ui::PAIR_NOTE_KOBOUI.to_string()
                } else {
                    ui::pair_note(&self.state, self.pair_polling(), server.as_deref())
                };
                ui::draw_pair(&ctx, &mut canvas, uri, server.as_deref(), &note)
            }
            View::ServerKeys { typed, error } => ui::draw_server_keyboard(&ctx, &mut canvas, typed, error.as_deref()),
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
                    let saved = art.article.engagement.saved_at.is_some();
                    let back = if matches!(art.source, Source::Saved) { "Saved" } else { "Edition" };
                    chrome_hits.extend(ui::top_bar(&ctx, &mut canvas, back, &art.article.feed_title, None));
                    let foot = Foot {
                        position: format!("{} of {}", art.pos + 1, art.screens.len()),
                        progress: (art.pos + 1) as f32 / art.screens.len() as f32,
                        toggles: Some((self.is_liked(art.id), saved)),
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
        self.settings_pages = settings_pages;
        self.settings_page = self.settings_page.min(settings_pages.max(1) - 1);
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
        let screens = self.layout_article(id, &article);
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
        if matches!(self.top(), View::Settings) {
            let (mut page, pages) = (self.settings_page, self.settings_pages);
            if step(&mut page, pages, forward) {
                self.settings_page = page;
                self.reset_armed = false;
                // A whole new page of controls: flashed, as a change of screen is.
                self.show(Show::Change)?;
            }
            return Ok(());
        }
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

    /// Like or Save, from the reading chrome or the buttons at the end of the article.
    fn toggle(&mut self, like: bool) -> io::Result<()> {
        let View::Article(art) = self.top() else { return Ok(()) };
        let id = art.id;
        let on = if like { !self.is_liked(id) } else { art.article.engagement.saved_at.is_none() };
        if like {
            self.liked.retain(|&l| l != id);
            if on {
                self.liked.push(id);
            }
            if let Err(e) = self.store.save_liked(&self.liked) {
                eprintln!("liked: {e}");
            }
        }
        self.record(if like { "liked" } else { "saved" }, |e| {
            e.article_id = Some(id);
            e.value = Some(serde_json::json!(on));
        })?;
        // The buttons at the end of the article show the new state; the text does not move.
        let mut stack = std::mem::take(&mut self.stack);
        if let Some(View::Article(art)) = stack.last_mut() {
            let pos = art.pos;
            art.screens = self.layout_article(id, &art.article);
            art.pos = pos.min(art.screens.len() - 1);
        }
        self.stack = stack;
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

    /// This Kobo has never shown its pairing code, so it has no secret to sync with.
    fn never_paired(&self) -> bool {
        self.state.secret.is_none()
    }

    /// The server to sync with: `server` in `config.json`, else the one typed in Settings.
    fn server_address(&self) -> Option<String> {
        self.config.server_or(self.settings.server.as_deref())
    }

    /// Whether the server has accepted this Kobo's token and not refused it since.
    fn paired(&self) -> bool {
        self.state.server_seen && !self.state.server_not_listed
    }

    /// Shows the pairing code, making the secret the first time. While it is shown and the
    /// Kobo is online, [`App::poll_pairing`] asks the server whether the phone has registered
    /// it.
    fn open_pair(&mut self) -> io::Result<()> {
        match self.store.ensure_secret() {
            Ok(secret) => {
                if let Ok(st) = self.store.load_state() {
                    self.state = st;
                }
                let (bw, bh) = self.display.base_size();
                let uri = crate::server::pair_uri(&secret, &self.state.device_id, &self.config.device_name(), bw.min(bh).max(1) as u32);
                if !self.paired() {
                    self.pair_poll_at = Some(self.clock.mono_ms() + PAIR_POLL_MS);
                }
                self.net = (self.network)();
                self.push(View::Pair { uri })
            }
            Err(e) => {
                eprintln!("pair: cannot make the pairing secret: {e}");
                Ok(())
            }
        }
    }

    /// Opens the server address keyboard with the typed address (not one from `config.json`).
    fn open_server_keys(&mut self) -> io::Result<()> {
        let typed = self.settings.server.clone().unwrap_or_default();
        self.push(View::ServerKeys { typed, error: None })
    }

    /// OK on the keyboard: nothing typed forgets the address; anything else is checked and
    /// kept, or refused with the reason.
    fn server_typed(&mut self) -> io::Result<()> {
        let View::ServerKeys { typed, .. } = self.top() else { return Ok(()) };
        let typed = typed.trim().to_string();
        let parsed = if typed.is_empty() { Ok(None) } else { crate::server::parse_address(&typed).map(Some) };
        match parsed {
            Ok(a) => {
                eprintln!("settings: server address {}", a.as_deref().unwrap_or("forgotten"));
                self.settings.server = a;
                self.save_settings();
                self.back()
            }
            Err(e) => {
                if let View::ServerKeys { error, .. } = self.top_mut() {
                    *error = Some(e);
                }
                self.show(Show::Touch)
            }
        }
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
                self.reset_armed = false;
                self.settings_page = 0;
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
            // Without a server address, or a pairing secret, no sync can happen: both open the
            // keyboard, or the pairing code, instead.
            Action::SyncNow | Action::WifiAndSync if self.server_address().is_none() => self.open_server_keys()?,
            Action::SyncNow | Action::WifiAndSync if self.never_paired() => self.open_pair()?,
            Action::SyncNow => {
                self.start_sync();
                self.show(Show::Update)?;
            }
            Action::WifiAndSync => {
                self.wifi_on_and_sync();
                self.show(Show::Update)?;
            }
            Action::WifiOff => {
                self.wifi_off_now();
                self.show(Show::Update)?;
            }
            Action::InvertButtons => {
                self.settings.invert_buttons = !self.settings.invert_buttons;
                self.save_settings();
                self.show(Show::Touch)?;
            }
            Action::SetOrientation(o) => {
                if Some(o) != self.settings.orientation {
                    self.settings.orientation = Some(o);
                    self.save_settings();
                    self.pending_flip = None;
                    let r = self.wanted_rotation(self.rotation);
                    if r != self.rotation {
                        self.rotate_to(r)?;
                    } else {
                        self.show(Show::Touch)?;
                    }
                }
            }
            Action::OpenPair => self.open_pair()?,
            Action::ServerTyped => self.open_server_keys()?,
            Action::KeyChar(c) => {
                if let View::ServerKeys { typed, error } = self.top_mut() {
                    if typed.len() < 200 {
                        typed.push(c);
                    }
                    *error = None;
                }
                self.show(Show::Touch)?;
            }
            Action::KeyText(t) => {
                if let View::ServerKeys { typed, error } = self.top_mut() {
                    if typed.len() + t.len() <= 200 {
                        typed.push_str(&t);
                    }
                    *error = None;
                }
                self.show(Show::Touch)?;
            }
            Action::KeyDelete => {
                if let View::ServerKeys { typed, error } = self.top_mut() {
                    typed.pop();
                    *error = None;
                }
                self.show(Show::Touch)?;
            }
            Action::KeyOk => self.server_typed()?,
            Action::Reset | Action::ResetCancel => {
                self.reset_armed = a == Action::Reset;
                self.show(Show::Touch)?;
            }
            Action::ResetConfirm => {
                self.store.request_reset()?;
                eprintln!("reset: erasing everything at the next start; exiting");
                return Ok(true);
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
        let pages = reading || matches!(self.top(), View::Saved { .. } | View::Settings);
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
            // Sleep and the cover are handled in `run`; neither leaves the reader.
            Key::Power | Key::CoverClosed | Key::CoverOpened => Ok(false),
        }
    }

    // ----------------------------------------------------------------------------------------
    // Sleep (see the constants at the top for the sequence and its sources)

    /// The time the page on screen was shown ends (it is recorded as a page view).
    fn leave_page(&mut self) -> io::Result<()> {
        let now = self.clock.mono_ms();
        if let Some(((edition, page), ms)) = self.meter.show(None, now) {
            self.record("pageView", |e| {
                e.edition_id = Some(edition);
                e.page = Some(page);
                e.ms = Some(ms as i64);
            })?;
        }
        Ok(())
    }

    fn save_seq(&mut self) -> io::Result<()> {
        let mut state = self.store.load_state()?;
        state.next_seq = state.next_seq.max(self.recorder.next_seq());
        self.store.save_state(&state)
    }

    /// Shows the sleep screen and starts the way to suspend.
    fn go_to_sleep(&mut self, why: &str) -> io::Result<()> {
        eprintln!("sleep: {why}; going to sleep");
        let now = self.clock.mono_ms();
        if let Some(v) = self.visit.as_mut() {
            v.pause(now);
        }
        self.leave_page()?;
        if let Err(e) = self.save_seq() {
            eprintln!("sleep: cannot save the state: {e}");
        }
        self.chrome = false;
        self.popover = false;
        let m = self.metrics();
        let mut canvas = Canvas::new(m.w, m.h);
        ui::draw_sleep(&Ctx::new(&self.fonts, m, &self.images), &mut canvas);
        let full = canvas.bounds();
        self.display.present(&canvas, self.rotation, full, Refresh::Full)?;
        self.refreshes.push((Refresh::Full, full));
        // Whatever shows on waking is drawn whole.
        self.shown = None;
        self.partials = 0;
        self.sleep = Some(Sleep { stage: SleepStage::Screen, at: now + PREPARE_MS, attempts: 0 });
        Ok(())
    }

    /// An event while going to sleep or asleep. Only the power button (with the cover open)
    /// and opening the cover wake the reader; touches, page keys and the sensor are dropped.
    /// Returns true to leave the reader.
    fn while_asleep(&mut self, ev: Option<RawEvent>) -> io::Result<bool> {
        match ev {
            Some(RawEvent::Quit) => return Ok(true),
            Some(RawEvent::Key(Key::CoverOpened)) => {
                self.cover_closed = false;
                return self.wake("sleep cover opened");
            }
            Some(RawEvent::Key(Key::CoverClosed)) => self.cover_closed = true,
            Some(RawEvent::Key(Key::Power)) if self.cover_closed => eprintln!("sleep: power button with the cover closed; staying asleep"),
            Some(RawEvent::Key(Key::Power)) => return self.wake("power button"),
            // Remembered, so the page wakes up the way the Kobo is held.
            Some(RawEvent::Orientation(r)) => self.last_sensor = Some(r % 4),
            Some(_) | None => {}
        }
        if self.sleep.as_ref().is_some_and(|s| self.clock.mono_ms() >= s.at) {
            return self.sleep_step();
        }
        Ok(false)
    }

    /// The next step towards suspend, when it is due. Returns true to give up and leave the
    /// reader, so Nickel comes back and handles sleep itself.
    fn sleep_step(&mut self) -> io::Result<bool> {
        let Some(mut s) = self.sleep.take() else { return Ok(false) };
        let now = self.clock.mono_ms();
        match s.stage {
            SleepStage::Screen => {
                if !self.wifi_off_done {
                    // A job the reader started gives way to the full teardown.
                    let was_on = self.wifi_job.is_some() || self.wifi_auto;
                    self.radio.stop();
                    self.end_wifi_session();
                    let was_online = was_on || (self.network)().online();
                    self.power.wifi_off();
                    self.wifi_off_done = true;
                    self.offline_since_sleep = was_online;
                    self.net = NetStatus::Offline;
                }
                if self.light.present() {
                    self.light.off();
                    self.light_off = true;
                }
                s.stage = SleepStage::Waiting;
                s.at = now + SUSPEND_WAIT_MS;
            }
            SleepStage::Waiting => match self.power.prepare() {
                Ok(()) => {
                    s.stage = SleepStage::Flagged;
                    s.at = now + FLAG_MS;
                }
                Err(e) => {
                    eprintln!("sleep: the kernel refused to flag subsystems for suspend ({e}); trying again in 15 s");
                    s.attempts += 1;
                    s.at = now + SUSPEND_WAIT_MS;
                }
            },
            SleepStage::Flagged => {
                eprintln!("sleep: suspending");
                let r = self.power.suspend();
                self.power.resume();
                match r {
                    Ok(()) => eprintln!("sleep: woke up"),
                    Err(e) => eprintln!("sleep: the kernel refused to suspend ({e}); trying again in 15 s"),
                }
                // Counted until the power button or the cover says the wake-up was meant.
                s.attempts += 1;
                s.stage = SleepStage::Waiting;
                s.at = self.clock.mono_ms() + SUSPEND_WAIT_MS;
            }
        }
        let give_up = s.attempts >= MAX_ATTEMPTS;
        self.sleep = Some(s);
        if give_up {
            eprintln!("sleep: gave up after {MAX_ATTEMPTS} attempts; leaving the reader so Nickel can sleep");
        }
        Ok(give_up)
    }

    /// Wakes up on the page the reader was showing. What was queued while asleep or waking
    /// is dropped, so a touch on the dark screen never turns a page. Returns true to leave
    /// (only at the end of a desktop script).
    fn wake(&mut self, why: &str) -> io::Result<bool> {
        let Some(s) = self.sleep.take() else { return Ok(false) };
        eprintln!("sleep: {why}; waking up");
        if s.stage == SleepStage::Flagged {
            self.power.resume();
        }
        if self.light_off {
            self.light.on();
            self.light_off = false;
        }
        while let Some(ev) = self.input.next(Duration::ZERO) {
            match ev {
                RawEvent::Quit => return Ok(true),
                RawEvent::Key(Key::CoverClosed) => self.cover_closed = true,
                RawEvent::Key(Key::CoverOpened) => self.cover_closed = false,
                RawEvent::Orientation(r) => self.last_sensor = Some(r % 4),
                _ => {}
            }
        }
        // Turned over while asleep: wake up the way it is held now.
        self.pending_flip = None;
        let r = self.wanted_rotation(self.last_sensor.filter(|_| self.follow_sensor).unwrap_or(self.rotation));
        if r != self.rotation {
            self.rotation = r;
            self.relayout_all();
        }
        let now = self.clock.mono_ms();
        self.last_input = now;
        self.last_minute = now;
        self.ignore_touches_until = now + WAKE_TOUCH_GUARD_MS;
        self.gestures = Recognizer::new(self.display.dpi());
        self.net = (self.network)();
        if let Some(v) = self.visit.as_mut() {
            v.resume(now);
        }
        self.meter_update()?;
        self.show(Show::Change)?;
        Ok(false)
    }

    /// Whether the reader is going to sleep or asleep, for tests.
    pub fn asleep(&self) -> bool {
        self.sleep.is_some()
    }

    // ----------------------------------------------------------------------------------------
    // Sync

    /// Starts a sync on its own thread, if the Kobo is online and none is running. Offline,
    /// nothing is tried.
    pub fn start_sync(&mut self) {
        if self.sync.is_some() || self.managed {
            return;
        }
        self.net = (self.network)();
        if !self.net.online() {
            eprintln!("sync: offline, not trying");
            return;
        }
        let (tx, rx) = mpsc::channel();
        let mut opts = sync::Options::new(self.server_address());
        let (bw, bh) = self.display.base_size();
        opts.image_width = bw.min(bh).max(1) as u32;
        let ptx = tx.clone();
        opts.progress = Some(Arc::new(move |p| {
            let _ = ptx.send(SyncMsg::Progress(p));
        }));
        let store = self.store.clone();
        let spawned = std::thread::Builder::new().name("sync".into()).spawn(move || {
            // A panic in a sync is logged by the panic hook and ends that sync only; the
            // reader goes on with what is stored.
            let r = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sync::sync(&store, &opts))) {
                Ok(r) => r,
                Err(_) => {
                    eprintln!("sync: crashed; the reader goes on with what it has");
                    let _ = std::fs::remove_file(store.data().join("panic.txt"));
                    let _ = tx.send(SyncMsg::Done(Finish::Failed));
                    return;
                }
            };
            match &r {
                Ok(r) => eprintln!(
                    "synced with {}: {} events sent, editions fetched {:?}, removed {:?}, saved list {}, {} images installed, {} not on the server",
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
            let _ = tx.send(SyncMsg::Done(finish_of(&r)));
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

    /// "Turn on Wi-Fi and sync": starts turning Wi-Fi on; [`App::poll_wifi`] syncs once it
    /// is connected.
    fn wifi_on_and_sync(&mut self) {
        if self.wifi_job.is_some() || self.managed {
            return;
        }
        self.wifi_error = None;
        match self.radio.start(true) {
            Ok(()) => {
                eprintln!("wifi: turning on to sync");
                self.wifi_job = Some(true);
                self.wifi_auto = true;
                self.offline_since_sleep = false;
            }
            Err(e) => {
                eprintln!("wifi: cannot turn on: {e}");
                self.wifi_error = Some(ui::WIFI_CANNOT_START.into());
            }
        }
    }

    /// "Turn Wi-Fi off", whoever turned it on. A sync still running fails and is not tried
    /// again.
    fn wifi_off_now(&mut self) {
        if self.wifi_job == Some(false) {
            return;
        }
        self.radio.stop();
        self.end_wifi_session();
        self.start_wifi_off();
    }

    fn start_wifi_off(&mut self) {
        match self.radio.start(false) {
            Ok(()) => self.wifi_job = Some(false),
            Err(e) => eprintln!("wifi: cannot turn off: {e}"),
        }
    }

    /// Forgets that the reader turned Wi-Fi on to sync (the job itself is left alone).
    fn end_wifi_session(&mut self) {
        self.wifi_job = None;
        self.wifi_auto = false;
        self.retry_at = None;
        self.retry = None;
    }

    /// Why Wi-Fi stays on and the sync is tried again, while the try under way (if any) has
    /// not got through yet.
    fn retrying(&self) -> Option<Retry> {
        self.retry.filter(|_| !(self.sync.is_some() && self.sync_show.connected))
    }

    /// What a Wi-Fi job's end leads to.
    fn poll_wifi(&mut self) -> io::Result<()> {
        let Some(on) = self.wifi_job else { return Ok(()) };
        let Some(ok) = self.radio.poll() else { return Ok(()) };
        self.wifi_job = None;
        self.net = (self.network)();
        if on {
            if self.net.online() {
                eprintln!("wifi: connected ({})", self.net.describe());
                self.start_sync();
            } else {
                eprintln!("wifi: no connection after turning on (script {}); turning off again", if ok { "succeeded" } else { "failed" });
                self.wifi_error = Some(ui::WIFI_NO_NETWORK.into());
                self.end_wifi_session();
                self.start_wifi_off();
            }
        } else {
            eprintln!("wifi: off");
        }
        if matches!(self.top(), View::Home | View::Settings) {
            self.show(Show::Update)?;
        }
        Ok(())
    }

    /// After a sync on Wi-Fi the reader turned on: off again, or try again later if the
    /// server can't be reached or doesn't know this Kobo yet.
    fn after_sync(&mut self, f: Finish) {
        if !self.wifi_auto {
            return;
        }
        match f {
            Finish::Retry(why) => {
                eprintln!("wifi: {why:?}; keeping Wi-Fi on and trying again");
                self.retry_at = Some(self.clock.mono_ms() + RETRY_MS);
                self.retry = Some(why);
            }
            Finish::Ok | Finish::Failed => {
                eprintln!("wifi: sync done; turning Wi-Fi off");
                self.end_wifi_session();
                self.start_wifi_off();
            }
        }
    }

    fn retry_sync(&mut self) -> io::Result<()> {
        let Some(at) = self.retry_at else { return Ok(()) };
        if self.sync.is_some() || self.wifi_job.is_some() || self.pair_probe.is_some() || self.clock.mono_ms() < at {
            return Ok(());
        }
        self.start_sync();
        if self.sync.is_some() {
            self.retry_at = None;
        } else {
            // The connection dropped; keep waiting, Wi-Fi may come back.
            self.retry_at = Some(self.clock.mono_ms() + RETRY_MS);
        }
        Ok(())
    }

    /// While the pairing code is shown, asks the server every PAIR_POLL_MS whether it knows
    /// this Kobo yet. Once it does, or a sync meanwhile got through, the reader goes back to
    /// Settings, syncing (after a sync, it has synced already).
    fn poll_pairing(&mut self) -> io::Result<()> {
        let on_pair = matches!(self.top(), View::Pair { .. });
        if let Some(rx) = &self.pair_probe {
            let known = match rx.try_recv() {
                Ok(known) => known,
                Err(mpsc::TryRecvError::Empty) => return Ok(()),
                Err(mpsc::TryRecvError::Disconnected) => false,
            };
            self.pair_probe = None;
            if let Ok(s) = self.store.load_state() {
                self.state = s;
            }
            if known && on_pair {
                eprintln!("pair: the server knows this Kobo now; syncing");
                self.pair_poll_at = None;
                self.back()?;
                self.start_sync();
                if self.sync.is_some() {
                    self.show(Show::Update)?;
                }
                return Ok(());
            }
            if on_pair || matches!(self.top(), View::Settings) {
                self.show(Show::Update)?;
            }
        }
        if !on_pair {
            self.pair_poll_at = None;
            return Ok(());
        }
        let Some(at) = self.pair_poll_at else { return Ok(()) };
        if self.sync.is_some() || self.wifi_job.is_some() {
            return Ok(());
        }
        if self.paired() {
            eprintln!("pair: a sync got through to the server; the pairing code is done");
            self.pair_poll_at = None;
            return self.back();
        }
        let now = self.clock.mono_ms();
        if now < at {
            return Ok(());
        }
        self.pair_poll_at = Some(now + PAIR_POLL_MS);
        self.net = (self.network)();
        let Some(server) = self.server_address().filter(|_| self.net.online()) else { return Ok(()) };
        let (tx, rx) = mpsc::channel();
        let store = self.store.clone();
        let opts = sync::Options::new(Some(server));
        let spawned = std::thread::Builder::new().name("pair".into()).spawn(move || {
            let known = match crate::server::hello(&store, &opts) {
                Ok(known) => known,
                Err(e) => {
                    eprintln!("pair: cannot ask the server whether it knows this Kobo: {e}");
                    false
                }
            };
            let _ = tx.send(known);
        });
        match spawned {
            Ok(_) => self.pair_probe = Some(rx),
            Err(e) => eprintln!("pair: cannot start asking: {e}"),
        }
        Ok(())
    }

    /// Whether the pairing code is waiting for the phone and the server is being asked, for
    /// the pairing screen.
    fn pair_polling(&self) -> bool {
        self.pair_poll_at.is_some() && self.net.online() && self.server_address().is_some()
    }

    /// Whether the reader is turning Wi-Fi on or off, or trying the sync again, for tests.
    pub fn wifi_busy(&self) -> bool {
        self.wifi_job.is_some() || self.retry.is_some()
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
                events::apply_saved(&mut self.saved, e, |id| events::find_in(&self.editions, id));
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
        let mut done = None;
        let mut arrived: Vec<String> = Vec::new();
        for msg in msgs {
            match msg {
                SyncMsg::Progress(Progress::Connected { .. }) => self.sync_show.connected = true,
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
                SyncMsg::Done(f) => done = Some(f),
            }
        }
        if let Some(f) = done {
            self.after_sync(f);
        }
        let done = done.is_some();
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

    /// Runs until Exit. The power button, the sleep cover and the idle timeout put the Kobo
    /// to sleep and back; they never leave the reader.
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
            let wait = match &self.sleep {
                Some(s) => Duration::from_millis(s.at.saturating_sub(self.clock.mono_ms()).clamp(1, 1000)),
                None if self.sync.is_some() || self.pending_flip.is_some() || self.wifi_job.is_some() || self.retry_at.is_some() || self.pair_poll_at.is_some() || self.pair_probe.is_some() => Duration::from_millis(250),
                None => Duration::from_secs(1),
            };
            let ev = self.input.next(wait);
            if self.sleep.is_some() {
                if self.while_asleep(ev)? {
                    return self.finish();
                }
                continue;
            }
            let now = self.clock.mono_ms();
            let exit = match ev {
                None => {
                    if !idle.is_zero() && now.saturating_sub(self.last_input) >= idle.as_millis() as u64 {
                        self.go_to_sleep("no input for a while")?;
                        continue;
                    }
                    false
                }
                Some(RawEvent::Quit) => true,
                Some(RawEvent::Key(Key::Power)) => {
                    self.go_to_sleep("power button")?;
                    continue;
                }
                Some(RawEvent::Key(Key::CoverClosed)) => {
                    self.cover_closed = true;
                    self.go_to_sleep("sleep cover closed")?;
                    continue;
                }
                Some(RawEvent::Key(Key::CoverOpened)) => {
                    self.cover_closed = false;
                    false
                }
                // Made while waking up; the guard ends a little after the page is back.
                Some(RawEvent::Touch { .. }) if now < self.ignore_touches_until => false,
                Some(ev) => {
                    self.last_input = now;
                    match ev {
                        RawEvent::Quit => true,
                        RawEvent::Touch { phase, x, y } => {
                            let (cx, cy) = device::base_to_canvas(self.display.base_size(), self.rotation, x, y);
                            match self.gestures.feed(phase, cx, cy) {
                                Some(g) => self.gesture(g)?,
                                None => false,
                            }
                        }
                        RawEvent::Key(k) => self.key(k)?,
                        RawEvent::Orientation(r) => {
                            self.sensor(r);
                            false
                        }
                    }
                }
            };
            if exit {
                return self.finish();
            }
            self.settle_flip()?;
            self.poll_wifi()?;
            self.poll_sync()?;
            self.retry_sync()?;
            self.poll_pairing()?;
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
        self.leave_page()?;
        if let Some(s) = self.sleep.take() {
            if s.stage == SleepStage::Flagged {
                self.power.resume();
            }
        }
        // launch.sh turns Wi-Fi off after the reader; a job still running would race it.
        self.radio.stop();
        self.end_wifi_session();
        if self.light_off {
            self.light.on();
            self.light_off = false;
        }
        self.light.restore();
        self.save_seq()
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

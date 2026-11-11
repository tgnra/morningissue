//! Drives the reader on the desktop backend at several Kobo screen sizes and writes a PNG at
//! each step to `target/screens/<size>/`. Also checks the events the session recorded, what a
//! sync started from the reader did, and what the light was told.

mod fakephone;

use fakephone::FakePhone;
use morningissue::app::{self, App};
use morningissue::config::Config;
use morningissue::desktop::{self, DesktopDisplay, DesktopPower, ScriptClock, ScriptInput, Shared, Step};
use morningissue::device::{Key, Refresh};
use morningissue::light::FakeLight;
use morningissue::net::NetStatus;
use morningissue::store::{image_key, State, Store};
use morningissue::time;
use serde_json::{json, Value};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

fn manifest_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn fonts_dir() -> PathBuf {
    manifest_dir().join("../app/src/main/res/font")
}

/// A photograph-like picture: sky, sun, hills and a little texture.
fn photo(w: u32, h: u32, seed: u32) -> image::RgbImage {
    image::RgbImage::from_fn(w, h, |x, y| {
        let (fx, fy) = (x as f32 / w as f32, y as f32 / h as f32);
        let hill = 0.62 + 0.08 * ((fx * 6.0 + seed as f32).sin()) + 0.05 * ((fx * 17.0).cos());
        let far = 0.5 + 0.06 * ((fx * 3.0 + 1.3 * seed as f32).cos());
        let sun = ((fx - 0.72).powi(2) + (fy - 0.25).powi(2)).sqrt();
        let noise = (((x * 7919 + y * 104729 + seed * 31) % 97) as f32 / 97.0 - 0.5) * 18.0;
        let (r, g, b) = if fy > hill {
            (60.0 + 40.0 * fy, 90.0 + 30.0 * fx, 50.0)
        } else if fy > far {
            (110.0, 130.0, 120.0 - 30.0 * fy)
        } else if sun < 0.09 {
            (255.0, 240.0, 200.0)
        } else {
            (150.0 + 90.0 * fy, 180.0 + 60.0 * fy, 230.0)
        };
        image::Rgb([(r + noise).clamp(0.0, 255.0) as u8, (g + noise).clamp(0.0, 255.0) as u8, (b + noise).clamp(0.0, 255.0) as u8])
    })
}

/// A line drawing: an architect's elevation of the mill.
fn plan(w: u32, h: u32) -> image::RgbImage {
    let mut img = image::RgbImage::from_pixel(w, h, image::Rgb([246, 242, 232]));
    let mut rect = |x0: u32, y0: u32, x1: u32, y1: u32| {
        for x in x0..x1 {
            for t in 0..3 {
                img.put_pixel(x, y0 + t, image::Rgb([40, 40, 60]));
                img.put_pixel(x, y1 - t, image::Rgb([40, 40, 60]));
            }
        }
        for y in y0..y1 {
            for t in 0..3 {
                img.put_pixel(x0 + t, y, image::Rgb([40, 40, 60]));
                img.put_pixel(x1 - t, y, image::Rgb([40, 40, 60]));
            }
        }
    };
    rect(w / 6, h / 3, w * 4 / 6, h * 5 / 6);
    rect(w * 4 / 6, h / 2, w * 5 / 6, h * 5 / 6);
    for i in 0..5 {
        rect(w / 6 + 40 + i * (w / 10), h / 2, w / 6 + 90 + i * (w / 10), h / 2 + 90);
    }
    // The wheel.
    let (cx, cy, r) = (w as f32 * 0.86, h as f32 * 0.72, h as f32 * 0.16);
    for a in 0..2000 {
        let t = a as f32 / 2000.0 * std::f32::consts::TAU;
        for k in 0..3 {
            let rr = r - k as f32;
            img.put_pixel((cx + rr * t.cos()) as u32 % w, (cy + rr * t.sin()) as u32 % h, image::Rgb([40, 40, 60]));
        }
    }
    img
}

fn encode(img: image::RgbImage, fmt: image::ImageFormat) -> Vec<u8> {
    let mut out = Vec::new();
    image::DynamicImage::ImageRgb8(img).write_to(&mut std::io::Cursor::new(&mut out), fmt).unwrap();
    out
}

fn fixture() -> Value {
    serde_json::from_str(&std::fs::read_to_string(manifest_dir().join("tests/fixtures/edition.json")).unwrap()).unwrap()
}

/// The fixture edition as edition `id` of `date`.
fn edition(id: i64) -> Value {
    let variants = [
        (121, "2026-11-12", "MORNING", None, Some("Harbour wall repairs finish a month early, under budget"), None),
        (120, "2026-11-11", "MORNING", None, None, Some("https://example.org/img/lead.jpg")),
        (119, "2026-11-10", "MORNING", Some("2026-11-10T08:00:00Z"), Some("Night trains return to the coast line after twenty years"), Some("https://example.org/img/comet.png")),
        (118, "2026-11-09", "EVENING", None, Some("The bakery that never closes, and the town that keeps it going"), None),
    ];
    let (_, date, kind, finished, lead_title, image) = variants.iter().find(|v| v.0 == id).copied().unwrap();
    let mut b = fixture();
    b["id"] = id.into();
    b["number"] = id.into();
    b["date"] = date.into();
    b["kind"] = kind.into();
    b["finishedAt"] = finished.map(Value::from).unwrap_or(Value::Null);
    if let Some(t) = lead_title {
        b["articles"]["9001"]["title"] = t.into();
    }
    b["articles"]["9001"]["imageUrl"] = image.map(Value::from).unwrap_or(Value::Null);
    if id == 121 {
        // Pictures the Kobo has not got yet, one of them with its size given.
        b["articles"]["9001"]["imageUrl"] = "https://example.org/img/harbour.jpg".into();
        let blocks = b["articles"]["9001"]["blocks"].as_array_mut().unwrap();
        for bl in blocks.iter_mut() {
            if bl["t"] == "img" {
                bl["url"] = "https://example.org/img/harbour-plan.png".into();
            }
        }
    }
    b
}

/// Three saved articles, newest saved first: a full one with a picture, the baker with code
/// blocks, and one whose text the phone could not get.
fn saved_list() -> Value {
    let f = fixture();
    let mut articles = serde_json::Map::new();
    let mut a = f["articles"]["9038"].clone();
    a["engagement"]["savedAt"] = "2026-11-10T19:12:00Z".into();
    articles.insert("9038".into(), a);
    let mut a = f["articles"]["9001"].clone();
    a["engagement"]["savedAt"] = "2026-11-09T07:40:00Z".into();
    articles.insert("9001".into(), a);
    let mut a = f["articles"]["9006"].clone();
    a["engagement"]["savedAt"] = "2026-11-02T21:05:00Z".into();
    a["blocks"] = json!([]);
    articles.insert("9006".into(), a);
    json!({"version": "s1", "order": [9038, 9001, 9006], "articles": articles})
}

/// What a run starts from, and what it can reach.
#[derive(Clone)]
struct Setup {
    /// How many of the editions 120, 119, 118 are stored.
    editions: usize,
    saved: bool,
    online: bool,
    /// A typed phone address in the settings.
    phone: Option<String>,
    sync_on_start: bool,
    warmth: bool,
    /// A sync has never succeeded; this was the last error.
    last_error: Option<&'static str>,
    /// How many suspend and prepare attempts the simulated kernel refuses.
    refuse_suspend: u32,
    refuse_prepare: u32,
}

impl Default for Setup {
    fn default() -> Setup {
        Setup { editions: 3, saved: true, online: true, phone: None, sync_on_start: false, warmth: true, last_error: None, refuse_suspend: 0, refuse_prepare: 0 }
    }
}

/// A home directory with editions, their pictures, saved articles and a sync twelve minutes ago.
fn setup(name: &str, now_ms: i64, s: &Setup) -> PathBuf {
    let home = std::env::temp_dir().join(format!("mi-screens-{}-{}", name.replace('/', "-"), std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    let store = Store::new(&home);
    store.ensure_dirs().unwrap();
    let mut state = State { device_id: "test-device".into(), token: Some("t".into()), db_id: Some("db".into()), next_seq: 1, phone_name: Some("Pixel 9".into()), last_sync_at: Some(now_ms - 12 * 60_000), ..Default::default() };
    if let Some(e) = s.last_error {
        state.last_sync_at = None;
        state.last_error = Some(e.into());
    }
    for id in [120, 119, 118].into_iter().take(s.editions) {
        std::fs::write(store.edition_path(id), serde_json::to_vec(&edition(id)).unwrap()).unwrap();
        state.editions.insert(id.to_string(), format!("v{id}"));
        state.edition_order.push(id);
    }
    if s.saved {
        std::fs::write(store.saved_path(), serde_json::to_vec(&saved_list()).unwrap()).unwrap();
        state.saved_version = Some("s1".into());
    }
    store.save_state(&state).unwrap();
    if let Some(p) = &s.phone {
        std::fs::write(store.settings_path(), serde_json::to_vec(&json!({"phoneAddress": p})).unwrap()).unwrap();
    }
    for (url, bytes) in pictures() {
        std::fs::write(store.images_dir().join(format!("{}.img", image_key(url))), bytes).unwrap();
    }
    home
}

fn pictures() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        ("https://example.org/img/lead.jpg", encode(photo(1600, 1000, 1), image::ImageFormat::Jpeg)),
        ("https://example.org/img/satellite.webp", encode(photo(900, 600, 4), image::ImageFormat::WebP)),
        ("https://example.org/img/comet.png", encode(photo(800, 800, 7), image::ImageFormat::Png)),
        ("https://example.org/img/mill-plan.png", encode(plan(1200, 800), image::ImageFormat::Png)),
    ]
}

#[derive(Clone, Copy)]
struct Size {
    name: &'static str,
    w: i32,
    h: i32,
    dpi: f32,
    rotation: u8,
}

const SIZES: [Size; 6] = [
    Size { name: "600x800-167dpi", w: 600, h: 800, dpi: 167.0, rotation: 0 },
    Size { name: "1072x1448-300dpi", w: 1072, h: 1448, dpi: 300.0, rotation: 0 },
    Size { name: "1264x1680-300dpi", w: 1264, h: 1680, dpi: 300.0, rotation: 0 },
    Size { name: "1264x1680-300dpi-landscape", w: 1264, h: 1680, dpi: 300.0, rotation: 1 },
    Size { name: "1404x1872-227dpi", w: 1404, h: 1872, dpi: 227.0, rotation: 0 },
    Size { name: "1264x1680-300dpi-inverted", w: 1264, h: 1680, dpi: 300.0, rotation: 2 },
];

struct Outcome {
    out: PathBuf,
    home: PathBuf,
    refreshes: Vec<(Refresh, morningissue::canvas::Rect)>,
    events: Vec<Value>,
    syncs_started: usize,
    light: Vec<(char, u8)>,
    /// The sleep steps the device was asked for.
    power: Vec<String>,
}

fn run(dir: &str, size: &Size, s: &Setup, script: Vec<Step>) -> Outcome {
    let now = time::parse_iso("2026-11-11T07:30:00Z").unwrap();
    let home = setup(dir, now, s);
    let shared = Rc::new(Shared::default());
    shared.wall_ms.set(now);
    shared.rotation.set(size.rotation);
    shared.refuse_suspend.set(s.refuse_suspend);
    shared.refuse_prepare.set(s.refuse_prepare);
    let display = DesktopDisplay::new(size.w, size.h, size.dpi, shared.clone());
    let panel = display.panel.clone();
    let out = manifest_dir().join("target/screens").join(dir);
    let _ = std::fs::remove_dir_all(&out);
    std::fs::create_dir_all(&out).unwrap();
    let mut input = ScriptInput::new(script, (size.w, size.h), shared.clone());
    let (out2, shared2) = (out.clone(), shared.clone());
    input.on_mark = Some(Box::new(move |name| {
        let view = desktop::view(&panel.borrow(), shared2.rotation.get());
        desktop::save_png(&view, &out2.join(format!("{name}.png"))).unwrap();
    }));
    let writes = Rc::new(RefCell::new(Vec::new()));
    let light = FakeLight { warmth: s.warmth, level: (40, 30), original: (40, 30), writes: writes.clone() };
    let online = s.online;
    // Wi-Fi stays up until the reader turns it off to sleep.
    let shared4 = shared.clone();
    let network = move || if online && !shared4.power.borrow().iter().any(|p| p == "wifi-off") { NetStatus::Online { iface: "wlan0".into(), ip: Some("192.168.1.31".into()), ssid: Some("Harbour Lane".into()) } } else { NetStatus::Offline };
    let shared3 = shared.clone();
    let opts = app::Options {
        rotation: size.rotation,
        follow_sensor: true,
        light: Box::new(light),
        power: Box::new(DesktopPower(shared.clone())),
        network: Box::new(network),
        sync_on_start: s.sync_on_start,
        hit_probe: Some(Box::new(move |hits| *shared3.hits.borrow_mut() = hits.to_vec())),
    };
    let mut app = App::new(Box::new(display), Box::new(input), Box::new(ScriptClock(shared.clone())), Store::new(&home), Config::default(), &fonts_dir(), opts).unwrap();
    app.run().unwrap();
    contact_sheet(&out);
    let events: Vec<Value> = std::fs::read_to_string(home.join("data/events.jsonl")).unwrap_or_default().lines().map(|l| serde_json::from_str(l).unwrap()).collect();
    let light = writes.borrow().clone();
    let power = shared.power.borrow().clone();
    Outcome { out, home, refreshes: app.refreshes.clone(), events, syncs_started: app.syncs_started, light, power }
}

/// Every picture in `dir`, scaled down onto one sheet (`contact.png`), for review.
fn contact_sheet(dir: &std::path::Path) {
    let mut names: Vec<PathBuf> = std::fs::read_dir(dir).unwrap().flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "png") && !p.ends_with("contact.png")).collect();
    names.sort();
    if names.is_empty() {
        return;
    }
    let imgs: Vec<image::GrayImage> = names.iter().map(|p| image::open(p).unwrap().to_luma8()).collect();
    let (w, h) = imgs.iter().fold((0, 0), |(w, h), i| (w.max(i.width()), h.max(i.height())));
    let scale = 360.0 / w.max(h) as f32;
    let (tw, th) = ((w as f32 * scale) as u32, (h as f32 * scale) as u32);
    let cols = 6u32;
    let rows = (imgs.len() as u32).div_ceil(cols);
    let mut sheet = image::GrayImage::from_pixel(cols * (tw + 8), rows * (th + 8), image::Luma([128]));
    for (i, img) in imgs.iter().enumerate() {
        let t = image::imageops::resize(img, (img.width() as f32 * scale) as u32, (img.height() as f32 * scale) as u32, image::imageops::FilterType::Triangle);
        image::imageops::overlay(&mut sheet, &t, ((i as u32 % cols) * (tw + 8)) as i64, ((i as u32 / cols) * (th + 8)) as i64);
    }
    sheet.save(dir.join("contact.png")).unwrap();
}

fn dims(size: &Size) -> (i32, i32) {
    if size.rotation % 2 == 1 {
        (size.h, size.w)
    } else {
        (size.w, size.h)
    }
}

fn tap_on(label: &str) -> Step {
    Step::TapOn(label.into())
}

fn mark(name: &str) -> Step {
    Step::Mark(name.into())
}

/// A swipe down from the top edge, which brings the reading chrome.
fn pull_down(size: &Size) -> Step {
    let (w, h) = dims(size);
    let top = (0.15 * size.dpi) as i32;
    Step::Swipe(w / 2, top, w / 2, h / 3)
}

/// The walk every size takes: home, the edition page by page, an article with its chrome and
/// the "Aa" popover, then Settings, the keypad and Saved.
fn walk(size: &Size) -> Vec<Step> {
    let (w, h) = dims(size);
    let mut s = vec![mark("01-home"), tap_on("OpenEdition(0)"), Step::Wait(6000), mark("02-front")];
    // Through the edition with the page-turn button, a swipe and a tap in the right margin.
    s.push(Step::Key(Key::Forward));
    s.push(Step::Wait(7000));
    s.push(mark("03-next"));
    s.push(Step::Swipe(w * 3 / 4, h / 2, w / 4, h / 2 + 10));
    s.push(Step::Wait(2000));
    s.push(mark("04-next"));
    s.push(pull_down(size));
    s.push(mark("05-edition-chrome"));
    s.push(Step::Wait(8000));
    s.push(mark("06-chrome-timed-out"));
    for i in 0..10 {
        s.push(Step::Key(Key::Forward));
        s.push(Step::Wait(1500));
        s.push(mark(&format!("{:02}-page", 7 + i)));
    }
    // Back to the front page and into the lead story.
    for _ in 0..20 {
        s.push(Step::Key(Key::Back));
    }
    s.push(Step::Wait(500));
    s.push(tap_on("OpenArticle(9001)"));
    s.push(Step::Wait(30_000));
    s.push(mark("20-article"));
    for i in 0..4 {
        s.push(Step::Tap(w * 3 / 4, h / 2));
        s.push(Step::Wait(20_000));
        s.push(mark(&format!("{:02}-article", 21 + i)));
    }
    s.push(pull_down(size));
    s.push(mark("25-article-chrome"));
    s.push(tap_on("Like"));
    s.push(mark("26-liked"));
    s.push(tap_on("TextAndLight"));
    s.push(mark("27-popover"));
    s.push(tap_on("SetTextSize(4)"));
    s.push(mark("28-larger-text"));
    s.push(tap_on("Nudge { warmth: true, by: 10 }"));
    s.push(Step::Tap(w / 2, h / 2)); // outside the popover: closes it
    s.push(mark("29-popover-closed"));
    s.push(Step::Tap(w / 2, h / 2)); // on the page: hides the chrome
    s.push(mark("30-larger-text-page"));
    s.push(pull_down(size));
    s.push(tap_on("Back"));
    s.push(Step::Wait(1000));
    s.push(mark("31-back"));
    s.push(Step::Key(Key::Home));
    s.push(mark("32-home-again"));
    s.push(tap_on("OpenSettings"));
    s.push(mark("40-settings"));
    s.push(tap_on("PhoneTyped"));
    s.push(mark("41-keypad"));
    for c in "192.168.1.40".chars() {
        s.push(tap_on(&format!("KeyChar({c:?})")));
    }
    s.push(mark("42-keypad-typed"));
    s.push(tap_on("KeyOk"));
    s.push(mark("43-settings-typed"));
    s.push(tap_on("Back"));
    s.push(tap_on("OpenSaved"));
    s.push(mark("50-saved"));
    s.push(tap_on("OpenArticle(9006)"));
    s.push(mark("51-saved-summary-only"));
    s.push(pull_down(size));
    s.push(tap_on("Save"));
    s.push(mark("52-unsaved"));
    s.push(tap_on("Back"));
    s.push(tap_on("OpenArticle(9001)"));
    s.push(mark("53-saved-article"));
    s
}

#[test]
fn screens_at_every_size() {
    for size in &SIZES {
        let o = run(&format!("{}/walk", size.name), size, &Setup::default(), walk(size));
        eprintln!("{}: {} refreshes, {} events, pictures in {}", size.name, o.refreshes.len(), o.events.len(), o.out.display());
        assert!(o.out.join("53-saved-article.png").exists());
        // The first frame and every change of screen flash; page turns mostly don't.
        assert_eq!(o.refreshes[0].0, Refresh::Full);
        assert!(o.refreshes.iter().filter(|r| r.0 == Refresh::Partial).count() >= 6, "{:?}", o.refreshes);
        let kinds: Vec<&str> = o.events.iter().map(|e| e["type"].as_str().unwrap()).collect();
        assert_eq!(kinds[0], "editionOpened", "{kinds:?}");
        for k in ["pageView", "opened", "liked", "readFraction", "saved"] {
            assert!(kinds.contains(&k), "{k} in {kinds:?}");
        }
        // Unsaving a saved article sends saved=false; it stays listed until the next sync.
        let unsave = o.events.iter().find(|e| e["type"] == "saved").unwrap();
        assert_eq!((unsave["articleId"].as_i64(), unsave["value"].as_bool()), (Some(9006), Some(false)));
        // Seq only goes up, from 1.
        let seqs: Vec<i64> = o.events.iter().map(|e| e["seq"].as_i64().unwrap()).collect();
        assert_eq!(seqs, (1..=seqs.len() as i64).collect::<Vec<_>>());
        // Page views carry milliseconds on screen and no time while an article was open.
        let first = o.events.iter().find(|e| e["type"] == "pageView").unwrap();
        assert_eq!(first["editionId"], 120);
        assert_eq!(first["page"], 1);
        assert!(first["ms"].as_i64().unwrap() >= 6000);
        for e in o.events.iter().filter(|e| e["type"] == "pageView") {
            assert!(e["ms"].as_i64().unwrap() < 30_000, "article time is never page time: {e}");
        }
        // The settings were kept: the bigger text, the warmer light, the typed address.
        let settings: Value = serde_json::from_slice(&std::fs::read(Store::new(&o.home).settings_path()).unwrap()).unwrap();
        assert_eq!(settings["textSize"], 4);
        assert_eq!(settings["warmth"], 40);
        assert_eq!(settings["phoneAddress"], "192.168.1.40:47824");
        // The light went back to Nickel's levels on the way out.
        assert_eq!(o.light.last(), Some(&('w', 30)), "{:?}", o.light);
        assert!(o.light.contains(&('w', 40)));
        // No sync was asked for, so none ran.
        assert_eq!(o.syncs_started, 0);
    }
}

/// The home screen with one, two and three editions, offline, after a failed sync, and with
/// nothing yet, at every size.
#[test]
fn home_screens() {
    for size in &SIZES {
        for (name, setup) in [
            ("home-1", Setup { editions: 1, ..Default::default() }),
            ("home-2", Setup { editions: 2, ..Default::default() }),
            ("home-offline", Setup { online: false, sync_on_start: true, ..Default::default() }),
            ("home-error", Setup { editions: 0, saved: false, last_error: Some("The phone stopped answering. Keep Sync with Kobo open on the phone."), ..Default::default() }),
        ] {
            let mut script = vec![mark(name)];
            if name == "home-offline" {
                script.extend([tap_on("OpenSettings"), mark("settings-offline"), Step::Key(Key::Home)]);
            }
            let o = run(&format!("{}/{name}", size.name), size, &setup, script);
            assert!(o.out.join(format!("{name}.png")).exists());
            // Offline, nothing is even tried.
            assert_eq!(o.syncs_started, 0);
        }
    }
}

/// Starting with Wi-Fi off never reaches for the phone, even with an address typed.
#[test]
fn offline_never_tries_to_sync() {
    let phone = FakePhone::start("db");
    phone.with(|s| s.tokens = vec!["t".into()]);
    let setup = Setup { online: false, sync_on_start: true, phone: Some(phone.address.clone()), ..Default::default() };
    let o = run("offline", &SIZES[2], &setup, vec![Step::Sleep(500), tap_on("OpenSettings"), mark("settings"), Step::Sleep(300)]);
    assert_eq!(o.syncs_started, 0);
    assert!(phone.with(|s| s.requests.is_empty()));
}

/// While the phone takes its time, the home screen says it is syncing.
#[test]
fn syncing_status_on_every_size() {
    // A phone that takes the connection and never answers.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap().to_string();
    for size in &SIZES {
        let setup = Setup { sync_on_start: true, phone: Some(address.clone()), ..Default::default() };
        let o = run(&format!("{}/home-syncing", size.name), size, &setup, vec![Step::Sleep(300), mark("home-syncing"), tap_on("OpenSettings"), mark("settings-syncing")]);
        assert_eq!(o.syncs_started, 1);
    }
    drop(listener);
}

/// A sync started by the reader, against a fake phone: a new edition shows up on the home
/// screen while the reader is open, pictures are asked for at the screen's width, three at a
/// time, and the saved list follows the phone.
#[test]
fn what_a_sync_brings_shows_as_it_arrives() {
    let phone = FakePhone::start("db");
    let mut saved = saved_list();
    // The phone has dropped the summary-only article and saved nothing new.
    saved["order"] = json!([9038, 9001]);
    saved["articles"].as_object_mut().unwrap().remove("9006");
    saved["version"] = "s2".into();
    phone.with(|s| {
        s.tokens = vec!["t".into()];
        s.editions = [121, 120, 119, 118].into_iter().map(|id| (id, format!("v{id}"), edition(id))).collect();
        s.saved = Some(("s2".into(), saved));
        for (url, bytes) in pictures() {
            s.images.insert(url.into(), bytes);
        }
        s.images.insert("https://example.org/img/harbour.jpg".into(), encode(photo(1400, 900, 3), image::ImageFormat::Jpeg));
        s.images.insert("https://example.org/img/harbour-plan.png".into(), encode(plan(1200, 800), image::ImageFormat::Png));
        s.image_delay_ms = 2500;
    });
    let size = &SIZES[2];
    let setup = Setup { sync_on_start: true, phone: Some(phone.address.clone()), ..Default::default() };
    let mut script = vec![mark("01-before")];
    // Wait for the new edition, then look at it while its pictures come.
    for _ in 0..15 {
        script.push(Step::Sleep(100));
    }
    script.push(mark("02-new-edition"));
    script.push(tap_on("OpenEdition(0)"));
    script.push(tap_on("OpenArticle(9001)"));
    script.push(mark("03-article-while-pictures-come"));
    for _ in 0..100 {
        script.push(Step::Sleep(100));
    }
    script.push(Step::Key(Key::Forward));
    script.push(Step::Key(Key::Forward));
    script.push(mark("04-article-pictures-in"));
    script.push(Step::Key(Key::Home));
    script.push(Step::Key(Key::Home));
    script.push(mark("05-home-synced"));
    script.push(tap_on("OpenSaved"));
    script.push(mark("06-saved-after-sync"));
    let o = run("arrival", size, &setup, script);
    assert_eq!(o.syncs_started, 1);
    let widths = phone.with(|s| s.image_widths.clone());
    assert!(!widths.is_empty() && widths.iter().all(|w| *w == Some(1264)), "{widths:?}");
    assert!(phone.with(|s| s.max_images_in_flight) <= 3);
    let store = Store::new(&o.home);
    let state = store.load_state().unwrap();
    assert_eq!(state.edition_order, vec![121, 120, 119, 118]);
    assert_eq!(store.load_saved().order, vec![9038, 9001]);
}

#[test]
fn orientation_from_settings_and_sensor() {
    let size = &SIZES[2];
    let script = vec![
        Step::Orientation(1),
        mark("sensor-landscape"),
        Step::Orientation(0),
        mark("sensor-portrait"),
        tap_on("OpenSettings"),
        tap_on("SetOrientation(Landscape)"),
        mark("landscape"),
        Step::Orientation(0), // ignored: the setting is fixed
        mark("still-landscape"),
        tap_on("SetOrientation(Portrait)"),
        mark("portrait"),
        tap_on("SetOrientation(Sensor)"),
        Step::Orientation(1),
        mark("sensor-again"),
    ];
    let o = run("rotation", size, &Setup::default(), script);
    let dims = |n: &str| image::image_dimensions(o.out.join(format!("{n}.png"))).unwrap();
    assert_eq!(dims("sensor-landscape"), (1680, 1264));
    assert_eq!(dims("sensor-portrait"), (1264, 1680));
    assert_eq!(dims("landscape"), (1680, 1264));
    assert_eq!(dims("still-landscape"), (1680, 1264));
    assert_eq!(dims("portrait"), (1264, 1680));
    assert_eq!(dims("sensor-again"), (1680, 1264));
    let settings: Value = serde_json::from_slice(&std::fs::read(Store::new(&o.home).settings_path()).unwrap()).unwrap();
    assert_eq!(settings["orientation"], "sensor");
}

/// With the buttons inverted, Back turns forward.
#[test]
fn inverted_page_turn_buttons() {
    let size = &SIZES[2];
    let script = vec![tap_on("OpenSettings"), tap_on("InvertButtons"), mark("inverted"), Step::Key(Key::Home), tap_on("OpenEdition(0)")];
    let script = script.into_iter().chain((0..4).flat_map(|_| [Step::Key(Key::Back), Step::Wait(3000)])).collect();
    let o = run("inverted", size, &Setup::default(), script);
    let pages: Vec<i64> = o.events.iter().filter(|e| e["type"] == "pageView").map(|e| e["page"].as_i64().unwrap()).collect();
    assert!(pages.iter().any(|p| *p > 1), "{pages:?}");
}

/// Without warmth, the popover offers brightness only.
#[test]
fn popover_without_warmth() {
    for size in [&SIZES[0], &SIZES[2]] {
        let setup = Setup { warmth: false, ..Default::default() };
        let script = vec![tap_on("OpenEdition(0)"), tap_on("OpenArticle(9001)"), pull_down(size), tap_on("TextAndLight"), mark("popover-brightness-only"), tap_on("Nudge { warmth: false, by: -10 }"), mark("dimmer")];
        let o = run(&format!("{}/no-warmth", size.name), size, &setup, script);
        assert!(o.light.contains(&('b', 30)), "{:?}", o.light);
        assert!(!o.light.iter().any(|w| w.0 == 'w'));
    }
}

/// The bounding box (x0, y0, x1, y1) of the pixels that are not white, if any.
fn ink_box(path: &std::path::Path) -> Option<(u32, u32, u32, u32)> {
    let img = image::open(path).unwrap().to_luma8();
    let mut b: Option<(u32, u32, u32, u32)> = None;
    for (x, y, p) in img.enumerate_pixels() {
        if p.0[0] < 200 {
            b = Some(match b {
                None => (x, y, x, y),
                Some((x0, y0, x1, y1)) => (x0.min(x), y0.min(y), x1.max(x), y1.max(y)),
            });
        }
    }
    b
}

fn same_picture(a: &std::path::Path, b: &std::path::Path) -> bool {
    image::open(a).unwrap().to_luma8() == image::open(b).unwrap().to_luma8()
}

/// Long enough for the whole way to suspend: the sleep screen, 3 s, 15 s, 2 s.
const TO_SUSPEND_MS: u64 = 21_000;

/// The power button puts the Kobo to sleep behind a blank page that says "Sleeping…" in its
/// lower right corner, and wakes it on the same page, at every size.
#[test]
fn sleep_screen_at_every_size() {
    for size in &SIZES {
        let script = vec![tap_on("OpenEdition(0)"), mark("01-front-page"), Step::Key(Key::Power), mark("02-sleeping"), Step::Wait(TO_SUSPEND_MS), Step::Key(Key::Power), Step::Wait(1000), mark("03-awake")];
        let o = run(&format!("{}/sleep", size.name), size, &Setup::default(), script);
        let (w, h) = dims(size);
        // Blank but for the word, which sits in the lower right corner.
        let (x0, y0, x1, y1) = ink_box(&o.out.join("02-sleeping.png")).expect("the sleep screen says Sleeping");
        assert!(x0 as i32 > w * 2 / 3 && y0 as i32 > h * 9 / 10, "{}: sleeping text at {x0},{y0}", size.name);
        assert!((x1 as i32) < w && (y1 as i32) < h);
        assert!(same_picture(&o.out.join("01-front-page.png"), &o.out.join("03-awake.png")), "{}: woke on the same screen", size.name);
        assert_eq!(o.power, ["wifi-off", "prepare", "suspend", "resume"], "{}", size.name);
        // The sleep screen and the screen after waking are both full refreshes.
        assert!(o.refreshes.iter().filter(|r| r.0 == Refresh::Full).count() >= 3);
        // The light went off for sleep and came back at its level.
        let i = o.light.iter().position(|w| *w == ('b', 0)).expect("light off");
        assert_eq!(o.light[i + 1], ('b', 40), "{:?}", o.light);
    }
}

/// Power, the cover and the idle timeout sleep and wake; touches and keys made while asleep
/// or waking never turn a page; and only Exit leaves.
#[test]
fn sleep_and_wake_by_power_cover_and_idle() {
    let size = &SIZES[2];
    let (w, h) = dims(size);
    let swipe = || Step::Swipe(w * 3 / 4, h / 2, w / 4, h / 2 + 10);
    let script = vec![
        tap_on("OpenEdition(0)"),
        Step::Wait(2000),
        mark("01-page-1"),
        // Power: asleep; a swipe and a page key on the dark screen do nothing.
        Step::Key(Key::Power),
        Step::Wait(5000),
        swipe(),
        Step::Key(Key::Forward),
        Step::Wait(TO_SUSPEND_MS),
        // Woken by the power button, with a swipe queued as it wakes: dropped too.
        Step::Key(Key::Power),
        swipe(),
        Step::Wait(1000),
        mark("02-awake-still-page-1"),
        // The cover: closing it sleeps, the power button with it closed does not wake,
        // opening it does.
        Step::Key(Key::CoverClosed),
        Step::Wait(TO_SUSPEND_MS),
        Step::Key(Key::Power),
        Step::Wait(1000),
        mark("03-cover-closed-power-pressed"),
        Step::Key(Key::CoverOpened),
        Step::Wait(1000),
        mark("04-cover-opened"),
        // Twenty minutes without input: asleep, not gone.
        Step::Wait(20 * 60_000 + TO_SUSPEND_MS),
        mark("05-idle-asleep"),
        Step::Key(Key::Power),
        Step::Wait(1000),
        mark("06-awake-after-idle"),
        // Awake, a swipe turns the page again.
        swipe(),
        Step::Wait(1000),
        mark("07-page-2"),
        Step::Key(Key::Home),
        mark("08-home-offline-since-sleep"),
        tap_on("OpenSettings"),
        mark("09-settings-offline-since-sleep"),
    ];
    let o = run("sleep-wake", size, &Setup::default(), script);
    for n in ["03-cover-closed-power-pressed", "05-idle-asleep"] {
        let (x0, y0, ..) = ink_box(&o.out.join(format!("{n}.png"))).unwrap();
        assert!(x0 as i32 > w * 2 / 3 && y0 as i32 > h * 9 / 10, "{n} is the sleep screen");
    }
    for n in ["02-awake-still-page-1", "04-cover-opened", "06-awake-after-idle"] {
        assert!(same_picture(&o.out.join("01-page-1.png"), &o.out.join(format!("{n}.png"))), "{n} is page 1 again");
    }
    // Only the swipe made awake turned the page.
    assert!(!same_picture(&o.out.join("01-page-1.png"), &o.out.join("07-page-2.png")));
    // Asleep time is not reading time. (The twenty idle minutes before the idle sleep count,
    // as they did when the idle timeout left the reader.)
    let ms: Vec<i64> = o.events.iter().filter(|e| e["type"] == "pageView").map(|e| e["ms"].as_i64().unwrap()).collect();
    assert_eq!(ms.iter().filter(|m| **m >= 10_000).count(), 1, "{ms:?}");
    assert!(ms.iter().all(|m| *m < 10_000 || (20 * 60_000..20 * 60_000 + 5_000).contains(m)), "{ms:?}");
    // Wi-Fi went off once, before the first suspend; three suspends, each resumed.
    assert_eq!(o.power.iter().filter(|p| *p == "wifi-off").count(), 1, "{:?}", o.power);
    assert_eq!(o.power[0], "wifi-off");
    assert_eq!(o.power.iter().filter(|p| *p == "suspend").count(), 3, "{:?}", o.power);
    assert_eq!(o.power.iter().filter(|p| *p == "resume").count(), 3, "{:?}", o.power);
}

/// When the kernel refuses, the reader tries again 15 s later, as KOReader does.
#[test]
fn a_refused_suspend_is_tried_again() {
    let size = &SIZES[2];
    let setup = Setup { refuse_suspend: 2, refuse_prepare: 1, ..Default::default() };
    let script = vec![Step::Key(Key::Power), Step::Wait(TO_SUSPEND_MS + 3 * 17_000), Step::Key(Key::Power), mark("awake")];
    let o = run("sleep-refused", size, &setup, script);
    assert_eq!(o.power, ["wifi-off", "prepare refused", "prepare", "suspend refused", "resume", "prepare", "suspend refused", "resume", "prepare", "suspend", "resume"]);
}

/// A kernel that never lets the Kobo sleep: after twenty attempts the reader hands back to
/// Nickel, which sleeps on its own, instead of keeping the device awake behind the sleep
/// screen.
#[test]
fn a_kernel_that_never_sleeps_hands_back_to_nickel() {
    let size = &SIZES[0];
    let setup = Setup { refuse_prepare: 1000, ..Default::default() };
    let script = vec![Step::Key(Key::Power), Step::Wait(30 * 60_000), mark("never-reached")];
    let o = run("sleep-never", size, &setup, script);
    assert_eq!(o.power.iter().filter(|p| *p == "prepare refused").count(), 20, "{:?}", o.power);
    assert!(!o.out.join("never-reached.png").exists());
    // The light went back to Nickel's levels on the way out.
    assert_eq!(o.light.last(), Some(&('w', 30)), "{:?}", o.light);
}

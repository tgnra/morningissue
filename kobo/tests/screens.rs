//! Drives the reader on the desktop backend at several Kobo screen sizes and writes a PNG at
//! each step to `target/screens/<size>/`. Also checks the events the session recorded.

use morningissue::app::{self, App};
use morningissue::config::Config;
use morningissue::desktop::{self, DesktopDisplay, ScriptClock, ScriptInput, Shared, Step};
use morningissue::device::{Key, Refresh};
use morningissue::store::{image_key, State, Store};
use morningissue::time;
use serde_json::Value;
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

/// A home directory with three editions, their pictures, and a sync twelve minutes ago.
fn setup(name: &str, now_ms: i64) -> PathBuf {
    let home = std::env::temp_dir().join(format!("mi-screens-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    let store = Store::new(&home);
    store.ensure_dirs().unwrap();
    let fixture: Value = serde_json::from_str(&std::fs::read_to_string(manifest_dir().join("tests/fixtures/edition.json")).unwrap()).unwrap();
    let mut state = State { device_id: "test-device".into(), token: Some("t".into()), db_id: Some("db".into()), next_seq: 1, phone_name: Some("Pixel 9".into()), last_sync_at: Some(now_ms - 12 * 60_000), ..Default::default() };
    let variants = [(120, "2026-11-11", "MORNING", None, None), (119, "2026-11-10", "MORNING", Some("2026-11-10T08:00:00Z"), Some("Night trains return to the coast line after twenty years")), (118, "2026-11-09", "EVENING", None, Some("The bakery that never closes, and the town that keeps it going"))];
    for (id, date, kind, finished, lead_title) in variants {
        let mut b = fixture.clone();
        b["id"] = id.into();
        b["number"] = id.into();
        b["date"] = date.into();
        b["kind"] = kind.into();
        b["finishedAt"] = finished.map(Value::from).unwrap_or(Value::Null);
        if let Some(t) = lead_title {
            b["articles"]["9001"]["title"] = t.into();
            b["articles"]["9001"]["imageUrl"] = if id == 119 { "https://example.org/img/comet.png".into() } else { Value::Null };
        }
        std::fs::write(store.edition_path(id), serde_json::to_vec(&b).unwrap()).unwrap();
        state.editions.insert(id.to_string(), format!("v{id}"));
        state.edition_order.push(id);
    }
    store.save_state(&state).unwrap();
    let images = [
        ("https://example.org/img/lead.jpg", encode(photo(1600, 1000, 1), image::ImageFormat::Jpeg)),
        ("https://example.org/img/satellite.webp", encode(photo(900, 600, 4), image::ImageFormat::WebP)),
        ("https://example.org/img/comet.png", encode(photo(800, 800, 7), image::ImageFormat::Png)),
        ("https://example.org/img/mill-plan.png", encode(plan(1200, 800), image::ImageFormat::Png)),
    ];
    for (url, bytes) in images {
        std::fs::write(store.images_dir().join(format!("{}.img", image_key(url))), bytes).unwrap();
    }
    home
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

fn run(size: &Size, script: Vec<Step>) -> (PathBuf, Vec<(Refresh, morningissue::canvas::Rect)>, Vec<Value>) {
    let now = time::parse_iso("2026-11-11T07:30:00Z").unwrap();
    let home = setup(size.name, now);
    let shared = Rc::new(Shared::default());
    shared.wall_ms.set(now);
    shared.rotation.set(size.rotation);
    let display = DesktopDisplay::new(size.w, size.h, size.dpi, shared.clone());
    let panel = display.panel.clone();
    let out = manifest_dir().join("target/screens").join(size.name);
    let _ = std::fs::remove_dir_all(&out);
    std::fs::create_dir_all(&out).unwrap();
    let mut input = ScriptInput::new(script, (size.w, size.h), shared.clone());
    let (out2, shared2) = (out.clone(), shared.clone());
    input.on_mark = Some(Box::new(move |name| {
        let view = desktop::view(&panel.borrow(), shared2.rotation.get());
        desktop::save_png(&view, &out2.join(format!("{name}.png"))).unwrap();
    }));
    let opts = app::Options { rotation: size.rotation, follow_sensor: true, can_rotate: true };
    let mut app = App::new(Box::new(display), Box::new(input), Box::new(ScriptClock(shared.clone())), Store::new(&home), Config::default(), &fonts_dir(), opts).unwrap();
    app.run().unwrap();
    contact_sheet(&out);
    let events: Vec<Value> = std::fs::read_to_string(home.join("data/events.jsonl")).unwrap_or_default().lines().map(|l| serde_json::from_str(l).unwrap()).collect();
    (out, app.refreshes.clone(), events)
}

/// Every picture in `dir`, scaled down onto one sheet (`contact.png`), for review.
fn contact_sheet(dir: &std::path::Path) {
    let mut names: Vec<PathBuf> = std::fs::read_dir(dir).unwrap().flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "png") && !p.ends_with("contact.png")).collect();
    names.sort();
    if names.is_empty() {
        return;
    }
    let imgs: Vec<image::GrayImage> = names.iter().map(|p| image::open(p).unwrap().to_luma8()).collect();
    let (w, h) = imgs[0].dimensions();
    let scale = 360.0 / w.max(h) as f32;
    let (tw, th) = ((w as f32 * scale) as u32, (h as f32 * scale) as u32);
    let cols = 6u32;
    let rows = (imgs.len() as u32).div_ceil(cols);
    let mut sheet = image::GrayImage::from_pixel(cols * (tw + 8), rows * (th + 8), image::Luma([128]));
    for (i, img) in imgs.iter().enumerate() {
        let t = image::imageops::resize(img, tw, th, image::imageops::FilterType::Triangle);
        image::imageops::overlay(&mut sheet, &t, ((i as u32 % cols) * (tw + 8)) as i64, ((i as u32 / cols) * (th + 8)) as i64);
    }
    sheet.save(dir.join("contact.png")).unwrap();
}

/// The walk every size takes: the stand, the edition page by page, an article, Like.
fn walk(size: &Size) -> Vec<Step> {
    let (w, h) = if size.rotation % 2 == 1 { (size.h, size.w) } else { (size.w, size.h) };
    let inch = |v: f32| (v * size.dpi) as i32;
    let bar = inch(0.33);
    let mut s = vec![Step::Mark("01-newsstand".into())];
    // The newest cover is at the top (portrait) or on the left (landscape).
    if w > h {
        s.push(Step::Tap(w / 4, h / 2));
    } else {
        s.push(Step::Tap(w / 2, h / 3));
    }
    s.push(Step::Wait(6000));
    s.push(Step::Mark("02-front".into()));
    // Through the edition with the page-turn button, a swipe and a tap in the right margin.
    s.push(Step::Key(Key::Forward));
    s.push(Step::Wait(7000));
    s.push(Step::Mark("03-next".into()));
    s.push(Step::Swipe(w * 3 / 4, h / 2, w / 4, h / 2 + 10));
    s.push(Step::Wait(2000));
    s.push(Step::Mark("04-next".into()));
    for i in 0..12 {
        s.push(Step::Key(Key::Forward));
        s.push(Step::Wait(1500));
        s.push(Step::Mark(format!("{:02}-page", 5 + i)));
    }
    // Back to the front page and into the lead story.
    for _ in 0..20 {
        s.push(Step::Key(Key::Back));
    }
    s.push(Step::Wait(500));
    let lead_y = if w > h { h * 45 / 100 } else { h * 30 / 100 };
    s.push(Step::Tap(w / 2, lead_y));
    s.push(Step::Wait(30_000));
    s.push(Step::Mark("20-article".into()));
    for i in 0..5 {
        s.push(Step::Tap(w * 3 / 4, h / 2));
        s.push(Step::Wait(20_000));
        s.push(Step::Mark(format!("{:02}-article", 21 + i)));
    }
    // Like, from the bottom bar.
    let pill = (0.9 * size.dpi) as i32;
    let gap = (10.0 / 72.0 * size.dpi) as i32;
    s.push(Step::Tap(w / 2 - gap / 2 - pill / 2, h - bar / 2));
    s.push(Step::Mark("26-liked".into()));
    s.push(Step::Tap(5 * inch(0.1), bar / 2)); // Back to the edition
    s.push(Step::Wait(1000));
    s.push(Step::Mark("27-back".into()));
    s
}

#[test]
fn screens_at_every_size() {
    for size in &SIZES {
        let (out, refreshes, events) = run(size, walk(size));
        eprintln!("{}: {} refreshes, {} events, pictures in {}", size.name, refreshes.len(), events.len(), out.display());
        assert!(out.join("02-front.png").exists());
        // The first frame and every change of screen flash; page turns mostly don't.
        assert_eq!(refreshes[0].0, Refresh::Full);
        assert!(refreshes.iter().filter(|r| r.0 == Refresh::Partial).count() >= 6, "{:?}", refreshes);
        let kinds: Vec<&str> = events.iter().map(|e| e["type"].as_str().unwrap()).collect();
        assert_eq!(kinds[0], "editionOpened", "{kinds:?}");
        assert!(kinds.contains(&"pageView"));
        assert!(kinds.contains(&"opened"), "{kinds:?}");
        assert!(kinds.contains(&"liked"), "{kinds:?}");
        assert!(kinds.contains(&"readFraction"), "{kinds:?}");
        // Seq only goes up, from 1.
        let seqs: Vec<i64> = events.iter().map(|e| e["seq"].as_i64().unwrap()).collect();
        assert_eq!(seqs, (1..=seqs.len() as i64).collect::<Vec<_>>());
        // Page views carry milliseconds on screen and no time while an article was open.
        let first = events.iter().find(|e| e["type"] == "pageView").unwrap();
        assert_eq!(first["editionId"], 120);
        assert_eq!(first["page"], 1);
        assert!(first["ms"].as_i64().unwrap() >= 6000);
        for e in events.iter().filter(|e| e["type"] == "pageView") {
            assert!(e["ms"].as_i64().unwrap() < 30_000, "article time is never page time: {e}");
        }
    }
}

#[test]
fn rotate_button_and_sensor() {
    let size = &SIZES[2];
    let (w, h) = (size.w, size.h);
    let bar = (0.33 * size.dpi) as i32;
    let foot = h - bar - (10.0 / 72.0 * size.dpi) as i32 + bar / 2;
    // The Rotate button is left of Exit at the foot of the newsstand.
    let bw = (1.1 * size.dpi) as i32;
    let gap = (14.0 / 72.0 * size.dpi) as i32;
    let rotate_x = (w - (2 * bw + gap)) / 2 + bw / 2;
    let script = vec![
        Step::Orientation(1),
        Step::Mark("sensor-landscape".into()),
        Step::Orientation(0),
        Step::Mark("sensor-portrait".into()),
        Step::Tap(rotate_x, foot),
        Step::Mark("rotated".into()),
        Step::Orientation(0), // ignored once rotated by hand
        Step::Mark("still-rotated".into()),
    ];
    let (out, refreshes, _) = run(&Size { name: "rotation", ..*size }, script);
    let dims = |n: &str| image::image_dimensions(out.join(format!("{n}.png"))).unwrap();
    assert_eq!(dims("sensor-landscape"), (1680, 1264));
    assert_eq!(dims("sensor-portrait"), (1264, 1680));
    assert_eq!(dims("rotated"), (1680, 1264));
    assert_eq!(dims("still-rotated"), (1680, 1264));
    assert!(refreshes.iter().all(|r| r.0 == Refresh::Full));
}


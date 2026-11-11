//! The sync order of `docs/kobo-sync.md` against an in-process fake phone.

mod fakephone;

use fakephone::{FakePhone, PairAnswer};
use morningissue::store::{Event, Store};
use morningissue::sync::{sync, Options, Progress, SyncError};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::time::Duration;

fn home(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("mi-sync-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}

fn free_udp_port() -> u16 {
    std::net::UdpSocket::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

fn opts(beacon_port: u16, beacon_secs: u64, phone: Option<String>) -> Options {
    Options {
        beacon_wait: Duration::from_secs(beacon_secs),
        beacon_port,
        request_timeout: Duration::from_secs(5),
        image_timeout: Duration::from_secs(5),
        pair_timeout: Duration::from_secs(5),
        deadline: Duration::from_secs(60),
        configured_phone: phone,
        device_name: "Kobo Libra 2".into(),
        manual_phone: None,
        image_width: 1264,
        image_workers: 3,
        progress: None,
    }
}

fn bundle(id: i64, headline: &str, images: &[&str]) -> Value {
    let blocks: Vec<Value> = images.iter().map(|u| json!({"t": "img", "url": u})).chain([json!({"t": "p", "text": {"text": "Body.", "spans": []}})]).collect();
    json!({
        "id": id, "date": "2026-11-11", "kind": "MORNING", "number": id, "length": "STANDARD",
        "releaseAt": "2026-11-11T06:30:00Z", "finishedAt": null, "newField": {"a": 1},
        "pages": [{"page": 1, "items": [{"articleId": id * 100, "slot": 0, "kind": "LEAD", "section": null, "reason": "r"}]}],
        "articles": {(id * 100).to_string(): {
            "feedTitle": "Feed", "title": headline, "author": null, "url": "https://example.com/a",
            "publishedAt": "2026-11-10T20:00:00Z", "summary": "S", "imageUrl": images.first(), "wordCount": 300,
            "blocks": blocks,
            "engagement": {"openedAt": null, "readFraction": 0.0, "readAt": null, "savedAt": null, "likedAt": null}
        }}
    })
}

fn png() -> Vec<u8> {
    let mut out = Vec::new();
    let img = image::GrayImage::from_fn(8, 8, |x, y| image::Luma([((x + y) * 16) as u8]));
    img.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).unwrap();
    out
}

fn event(seq: i64) -> Event {
    Event { seq, kind: "opened".into(), article_id: Some(100), edition_id: None, page: None, value: None, ms: None, at: "2026-11-11T07:00:00Z".into() }
}

/// A phone with editions 2 (newest) and 1; edition 2 has one stored and one missing image.
fn phone() -> FakePhone {
    let p = FakePhone::start("db-A");
    p.with(|s| {
        s.editions = vec![(2, "v2".into(), bundle(2, "Second", &["http://img/a.png", "http://img/missing.webp"])), (1, "v1".into(), bundle(1, "First", &["http://img/b.png"]))];
        s.images.insert("http://img/a.png".into(), png());
        s.images.insert("http://img/b.png".into(), png());
    });
    p
}

#[test]
fn first_sync_pairs_through_the_beacon_and_skips_missing_images() {
    let phone = phone();
    let store = Store::new(home("first"));
    let port = free_udp_port();
    phone.beacon(port, 40);
    let report = sync(&store, &opts(port, 4, None)).unwrap();
    assert!(report.paired);
    assert_eq!(report.address, phone.address);
    assert_eq!(report.editions_fetched, vec![2, 1]);
    assert_eq!((report.images_fetched, report.images_missing), (2, 1));

    let state = store.load_state().unwrap();
    assert_eq!(state.token.as_deref(), Some("tok1"));
    assert_eq!(state.db_id.as_deref(), Some("db-A"));
    assert_eq!(state.phone_name.as_deref(), Some("Fake Pixel"));
    assert_eq!(state.edition_order, vec![2, 1]);
    assert_eq!(state.editions.get("2").map(String::as_str), Some("v2"));
    assert!(state.last_sync_at.is_some() && state.last_error.is_none());
    let editions = store.load_editions(&state);
    assert_eq!(editions.iter().map(|b| b.id).collect::<Vec<_>>(), vec![2, 1]);
    assert!(store.has_image("http://img/a.png") && !store.has_image("http://img/missing.webp"));
    let pair = phone.with(|s| s.pair_requests[0].clone());
    assert_eq!(pair["deviceId"], json!(state.device_id));
    assert_eq!(pair["name"], json!("Kobo Libra 2"));

    // A second sync with nothing changed fetches nothing and keeps the same token.
    let report = sync(&store, &opts(port, 0, None)).unwrap();
    assert!(!report.paired && report.editions_fetched.is_empty());
    // The missing image is asked for again, in case the phone has stored it since.
    assert_eq!(report.images_missing, 1);
}

#[test]
fn unauthorized_leads_to_pairing_again() {
    let phone = phone();
    let store = Store::new(home("401"));
    sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    phone.with(|s| s.tokens.clear()); // The phone forgot this Kobo.
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert!(report.paired);
    assert_eq!(store.load_state().unwrap().token.as_deref(), Some("tok2"));
    let requests = phone.with(|s| s.requests.clone());
    let expected = ["GET /v1/manifest", "POST /v1/pair", "GET /v1/manifest"];
    assert!(requests.windows(3).any(|w| w.iter().zip(expected).all(|(a, b)| a == b)), "{requests:?}");

    // A refusal is reported and saved for the newsstand.
    phone.with(|s| {
        s.tokens.clear();
        s.pair_answer = PairAnswer::Deny;
    });
    assert!(matches!(sync(&store, &opts(0, 0, None)), Err(SyncError::Denied)));
    let state = store.load_state().unwrap();
    assert!(state.last_error.unwrap().contains("declined"));
    // The stored editions are still there.
    assert_eq!(store.load_editions(&store.load_state().unwrap()).len(), 2);
    phone.with(|s| s.pair_answer = PairAnswer::NoAnswer);
    assert!(matches!(sync(&store, &opts(0, 0, None)), Err(SyncError::PairTimeout)));
}

#[test]
fn events_are_acknowledged_idempotently() {
    let phone = phone();
    let store = Store::new(home("events"));
    sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    for seq in 1..=3 {
        store.append_event(&event(seq)).unwrap();
    }
    // The phone merges the events but the response is lost.
    phone.with(|s| s.drop_events_response = true);
    assert!(sync(&store, &opts(0, 0, None)).is_err());
    assert_eq!(store.read_events().unwrap().len(), 3, "unacknowledged events are kept");
    store.append_event(&event(4)).unwrap();
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert_eq!(report.events_sent, 1, "events the manifest says are merged are not sent again");
    let merged: Vec<i64> = phone.with(|s| s.merged.iter().map(|e| e["seq"].as_i64().unwrap()).collect());
    assert_eq!(merged, vec![1, 2, 3, 4]);
    assert!(store.read_events().unwrap().is_empty());
    let state = store.load_state().unwrap();
    assert_eq!(store.next_seq(&state).unwrap(), 5);
    // The posted events have the protocol's shape.
    let posted = phone.with(|s| s.event_posts.last().unwrap().clone());
    assert_eq!(posted["events"][0], json!({"seq": 4, "type": "opened", "articleId": 100, "at": "2026-11-11T07:00:00Z"}));
}

#[test]
fn a_new_phone_database_wipes_everything_before_events_are_sent() {
    let phone = phone();
    let store = Store::new(home("dbid"));
    sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    store.append_event(&event(1)).unwrap();
    store.append_event(&event(2)).unwrap();
    // The phone is reinstalled: a new database with reused row IDs, and the old token kept
    // working (the worst case: nothing but the dbId tells them apart).
    phone.with(|s| {
        s.db_id = "db-B".into();
        s.editions = vec![(1, "w1".into(), bundle(1, "Reinstalled", &[]))];
    });
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert!(report.wiped);
    assert_eq!(report.events_sent, 0);
    assert!(phone.with(|s| s.event_posts.is_empty()), "stale events never reach the new database");
    let state = store.load_state().unwrap();
    assert_eq!(state.db_id.as_deref(), Some("db-B"));
    assert_eq!(state.edition_order, vec![1]);
    let editions = store.load_editions(&state);
    assert_eq!(editions[0].article(100).unwrap().title, "Reinstalled");
    assert!(!store.has_image("http://img/a.png") && !store.has_image("http://img/b.png"));
    assert!(store.next_seq(&state).unwrap() >= 3, "seq keeps going up");

    // Through pairing too: the new install does not know the token.
    store.append_event(&event(3)).unwrap();
    phone.with(|s| {
        s.db_id = "db-C".into();
        s.tokens.clear();
    });
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert!(report.paired && report.wiped);
    assert!(phone.with(|s| s.event_posts.is_empty()));
}

#[test]
fn a_changed_version_is_fetched_again_and_dropped_editions_are_removed() {
    let phone = phone();
    let store = Store::new(home("version"));
    sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    phone.with(|s| {
        s.editions = vec![(3, "v3".into(), bundle(3, "Third", &["http://img/a.png"])), (2, "v2b".into(), bundle(2, "Second, reprinted", &["http://img/a.png"]))];
    });
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert_eq!(report.editions_fetched, vec![3, 2]);
    assert_eq!(report.editions_removed, vec![1]);
    let state = store.load_state().unwrap();
    let editions = store.load_editions(&state);
    assert_eq!(editions.iter().map(|b| b.id).collect::<Vec<_>>(), vec![3, 2]);
    assert_eq!(editions[1].article(200).unwrap().title, "Second, reprinted");
    assert!(!store.edition_path(1).exists());
    assert!(store.has_image("http://img/a.png"));
    assert!(!store.has_image("http://img/b.png"), "an image nothing refers to is deleted");
}

#[test]
fn an_interrupted_sync_keeps_the_last_good_state() {
    let phone = phone();
    let store = Store::new(home("interrupt"));
    sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    let before = store.load_state().unwrap();
    phone.with(|s| {
        s.editions = vec![(3, "v3".into(), bundle(3, "Third", &[])), (2, "v2b".into(), bundle(2, "Changed", &[]))];
        s.drop_path = Some("/v1/editions/2".into());
    });
    let err = sync(&store, &opts(0, 0, None)).unwrap_err();
    assert!(matches!(err, SyncError::Network(_)), "{err:?}");
    let after = store.load_state().unwrap();
    // Edition 3 arrived before the connection dropped and is shown at once; edition 2 keeps
    // its last good copy, and edition 1 stays until a sync finishes.
    assert_eq!(after.editions.get("3").map(String::as_str), Some("v3"));
    assert_eq!(after.editions.get("2"), before.editions.get("2"));
    assert_eq!(after.edition_order, vec![3, 2, 1]);
    assert_eq!(after.last_sync_at, before.last_sync_at);
    assert!(after.last_error.as_deref().unwrap().contains("stopped answering"), "{:?}", after.last_error);
    let editions = store.load_editions(&after);
    assert_eq!(editions.iter().map(|b| b.id).collect::<Vec<_>>(), vec![3, 2, 1]);
    assert_eq!(editions[1].article(200).unwrap().title, "Second");
    assert!(store.has_image("http://img/b.png"));

    // Once the phone answers again the sync completes.
    phone.with(|s| s.drop_path = None);
    sync(&store, &opts(0, 0, None)).unwrap();
    let state = store.load_state().unwrap();
    assert_eq!(state.edition_order, vec![3, 2]);
    assert!(state.last_error.is_none());
}

#[test]
fn no_phone_anywhere_is_reported() {
    let store = Store::new(home("nophone"));
    // A port nobody listens on.
    let dead = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().to_string();
    let err = sync(&store, &opts(free_udp_port(), 1, Some(dead))).unwrap_err();
    assert!(matches!(err, SyncError::NoPhone));
    assert!(store.load_state().unwrap().last_error.unwrap().contains("Phone not found"));
}

fn saved_body(ids: &[i64], with_text: bool) -> Value {
    let mut articles = serde_json::Map::new();
    for id in ids {
        let blocks = if with_text { json!([{"t": "img", "url": format!("http://img/saved-{id}.png"), "width": 800, "height": 600}, {"t": "p", "text": {"text": "Kept."}}]) } else { json!([]) };
        articles.insert(
            id.to_string(),
            json!({"feedTitle": "Saved feed", "title": format!("Saved {id}"), "summary": "A summary.", "imageUrl": null, "wordCount": 500, "publishedAt": "2026-11-01T08:00:00Z",
                   "blocks": blocks, "engagement": {"savedAt": "2026-11-10T08:00:00Z"}}),
        );
    }
    json!({"version": "s1", "order": ids, "articles": articles})
}

#[test]
fn saved_articles_are_fetched_when_their_version_changes() {
    let phone = phone();
    phone.with(|s| {
        s.saved = Some(("s1".into(), saved_body(&[7, 5], true)));
        s.images.insert("http://img/saved-7.png".into(), png());
    });
    let store = Store::new(home("saved"));
    let report = sync(&store, &opts(0, 0, Some(phone.address.clone()))).unwrap();
    assert!(report.saved_fetched);
    let saved = store.load_saved();
    assert_eq!(saved.items().iter().map(|(id, a)| (*id, a.title.as_str())).collect::<Vec<_>>(), vec![(7, "Saved 7"), (5, "Saved 5")]);
    assert!(store.has_image("http://img/saved-7.png"), "saved articles' pictures are fetched too");
    assert_eq!(store.load_state().unwrap().saved_version.as_deref(), Some("s1"));

    // Unchanged: not fetched again.
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert!(!report.saved_fetched);
    assert_eq!(phone.with(|s| s.requests.iter().filter(|r| *r == "GET /v1/saved").count()), 1);

    // Unsaved on the Kobo: the event goes first, and the article leaves the list with the
    // phone's next version. Its picture goes with it.
    store.append_event(&Event { seq: 1, kind: "saved".into(), article_id: Some(7), edition_id: None, page: None, value: Some(json!(false)), ms: None, at: "2026-11-11T07:00:00Z".into() }).unwrap();
    phone.with(|s| s.saved = Some(("s2".into(), saved_body(&[5], false))));
    let report = sync(&store, &opts(0, 0, None)).unwrap();
    assert!(report.saved_fetched && report.events_sent == 1);
    let merged = phone.with(|s| s.merged.clone());
    assert_eq!(merged[0]["type"], "saved");
    assert_eq!(merged[0]["value"], false);
    let saved = store.load_saved();
    assert_eq!(saved.order, vec![5]);
    assert!(saved.article(5).unwrap().summary_only());
    assert!(!store.has_image("http://img/saved-7.png"));

    // A phone that offers no saved shelf leaves nothing saved.
    phone.with(|s| s.saved = None);
    sync(&store, &opts(0, 0, None)).unwrap();
    assert!(store.load_saved().order.is_empty());
}

#[test]
fn images_are_asked_for_at_the_screen_width_three_at_a_time() {
    let phone = phone();
    let urls: Vec<String> = (0..9).map(|i| format!("http://img/many-{i}.png")).collect();
    let refs: Vec<&str> = urls.iter().map(String::as_str).collect();
    phone.with(|s| {
        s.editions = vec![(4, "v4".into(), bundle(4, "Many pictures", &refs))];
        for u in &urls {
            s.images.insert(u.clone(), png());
        }
        s.image_delay_ms = 150;
    });
    let store = Store::new(home("images"));
    let arrived = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen = arrived.clone();
    let mut o = opts(0, 0, Some(phone.address.clone()));
    o.image_width = 1072;
    o.progress = Some(std::sync::Arc::new(move |p| seen.lock().unwrap().push(p)));
    let started = std::time::Instant::now();
    let report = sync(&store, &o).unwrap();
    assert_eq!(report.images_fetched, 9);
    assert!(phone.with(|s| s.image_widths.iter().all(|w| *w == Some(1072))), "w is the screen's short side");
    assert_eq!(phone.with(|s| s.max_images_in_flight), 3);
    // Nine pictures at 150 ms each, three at a time, take about 450 ms, not 1350.
    assert!(started.elapsed() < std::time::Duration::from_millis(1200), "{:?}", started.elapsed());
    let progress = arrived.lock().unwrap().clone();
    let edition_at = progress.iter().position(|p| *p == Progress::Edition(4)).unwrap();
    let first_image = progress.iter().position(|p| matches!(p, Progress::Image(_))).unwrap();
    assert!(edition_at < first_image, "the text is shown before the pictures arrive: {progress:?}");
    assert_eq!(progress.iter().filter(|p| matches!(p, Progress::Image(_))).count(), 9);
}

#[test]
fn a_typed_address_is_used_before_the_beacon() {
    let phone = phone();
    let store = Store::new(home("manual"));
    let port = free_udp_port();
    let mut o = opts(port, 5, None);
    o.manual_phone = Some(phone.address.clone());
    let started = std::time::Instant::now();
    let report = sync(&store, &o).unwrap();
    assert_eq!(report.address, phone.address);
    assert!(started.elapsed() < std::time::Duration::from_secs(3), "no time spent listening for a beacon");

    // A typed address nobody answers at falls back to the beacon.
    let dead = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().to_string();
    o.manual_phone = Some(dead);
    phone.beacon(port, 40);
    let report = sync(&store, &o).unwrap();
    assert_eq!(report.address, phone.address);
}

//! The device order of `docs/relay-sync.md` against an in-process fake relay over a real
//! socket, plain and TLS.

mod fakerelay;

use fakerelay::{package, Edition, FakeRelay, Picture};
use morningissue::crypto::{self, DeviceKeys};
use morningissue::store::{Event, Store};
use morningissue::sync::{sync, Options, SyncError};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::time::Duration;

fn home(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("mi-relay-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}

fn opts(relay: &str) -> Options {
    Options { request_timeout: Duration::from_secs(5), deadline: Duration::from_secs(60), image_width: 1264, progress: None, relay: relay.into() }
}

/// A Kobo that has shown its pairing code: its store and its keys.
fn kobo(name: &str) -> (Store, DeviceKeys) {
    let store = Store::new(home(name));
    let secret = store.ensure_relay_secret().unwrap();
    // Made once.
    assert_eq!(store.ensure_relay_secret().unwrap(), secret);
    let keys = DeviceKeys::from_secret(&crypto::b64url_decode(&secret).unwrap());
    (store, keys)
}

fn bundle(id: i64, headline: &str, images: &[&str]) -> Value {
    let blocks: Vec<Value> = images.iter().map(|u| json!({"t": "img", "url": u})).chain([json!({"t": "p", "text": {"text": "Body.", "spans": []}})]).collect();
    json!({
        "id": id, "date": "2026-11-11", "kind": "MORNING", "number": id, "length": "STANDARD",
        "releaseAt": "2026-11-11T06:30:00Z", "finishedAt": null,
        "pages": [{"page": 1, "items": [{"articleId": id * 100, "slot": 0, "kind": "LEAD", "section": null, "reason": "r"}]}],
        "articles": {(id * 100).to_string(): {
            "feedTitle": "Feed", "title": headline, "author": null, "url": "https://example.com/a",
            "publishedAt": "2026-11-10T20:00:00Z", "summary": "S", "imageUrl": images.first(), "wordCount": 300,
            "blocks": blocks,
            "engagement": {"openedAt": null, "readFraction": 0.0, "readAt": null, "savedAt": null, "likedAt": null}
        }}
    })
}

/// A picture `w` wide, with noise so it does not compress away.
fn picture(w: u32, seed: u32) -> Vec<u8> {
    let img = image::GrayImage::from_fn(w, w / 2, |x, y| image::Luma([((x * 7 + y * 13 + seed) ^ (x * y + seed)) as u8]));
    let mut out = Vec::new();
    img.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).unwrap();
    out
}

fn event(seq: i64) -> Event {
    Event { seq, kind: "opened".into(), article_id: Some(100), edition_id: None, page: None, value: None, ms: None, at: "2026-11-12T07:00:00Z".into() }
}

fn seqs(store: &Store) -> Vec<i64> {
    store.read_events().unwrap().iter().map(|e| e.seq).collect()
}

fn editions() -> Vec<Edition> {
    vec![(2, "v2".into(), bundle(2, "Second", &["http://img/a.png", "http://img/missing.webp"])), (1, "v1".into(), bundle(1, "First", &["http://img/b.png"]))]
}

fn pictures() -> Vec<Picture> {
    vec![
        // a.png at three widths; this Kobo's is there.
        ("http://img/a.png".into(), 600, picture(600, 1), "image/png".into()),
        ("http://img/a.png".into(), 1264, picture(1264, 2), "image/png".into()),
        ("http://img/a.png".into(), 1600, picture(1600, 3), "image/png".into()),
        // b.png only wider, and narrower: the wider one is scaled down.
        ("http://img/b.png".into(), 1072, picture(1072, 4), "image/png".into()),
        ("http://img/b.png".into(), 1404, picture(1404, 5), "image/png".into()),
    ]
}

fn saved() -> Value {
    json!({"version": "s1", "order": [100], "articles": {"100": bundle(1, "First", &[])["articles"]["100"]}})
}

fn id(n: u8) -> String {
    crypto::b64url(&[n; 16])
}

#[test]
fn a_package_is_installed_and_events_stay_until_a_package_acknowledges_them() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("install");
    relay.list(&keys);
    for s in 1..=3 {
        store.append_event(&event(s)).unwrap();
    }
    relay.publish(&id(1), &package("db-A", &editions(), Some(("s1", saved())), &pictures(), json!({})), &[&keys]);

    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.address, relay.url());
    assert_eq!((r.events_sent, r.editions_fetched.clone(), r.saved_fetched), (3, vec![2, 1], true));
    assert_eq!((r.images_fetched, r.images_missing), (2, 1));
    // The events went as one encrypted batch, with this Kobo's deviceId.
    let state = store.load_state().unwrap();
    let changes = relay.changes_of(&keys);
    assert_eq!(changes.len(), 1);
    assert_eq!(changes[0].0, "1-3");
    assert_eq!(changes[0].1["deviceId"], state.device_id.as_str());
    assert_eq!(changes[0].1["events"].as_array().unwrap().iter().map(|e| e["seq"].as_i64().unwrap()).collect::<Vec<_>>(), vec![1, 2, 3]);
    // Not acknowledged yet: still kept, and still applied on top.
    assert_eq!(seqs(&store), vec![1, 2, 3]);
    assert_eq!((state.relay_package.clone(), state.relay_fetched), (Some(id(1)), true));
    assert!(state.last_sync_at.is_some() && state.last_error.is_none() && state.relay_seen);
    assert_eq!(state.db_id.as_deref(), Some("db-A"));
    assert_eq!(state.edition_order, vec![2, 1]);
    assert_eq!(relay.with(|s| s.fetched.clone()), vec![(keys.kid.clone(), id(1))]);
    // This Kobo's width as it is; the wider one scaled down to it.
    assert_eq!(store.read_image("http://img/a.png").unwrap(), picture(1264, 2));
    let b = image::load_from_memory(&store.read_image("http://img/b.png").unwrap()).unwrap();
    assert_eq!((b.width(), b.height()), (1264, 632));
    assert_eq!(store.load_saved().order, vec![100]);
    assert!(store.load_bundle(2).is_some() && store.load_bundle(1).is_some());
    // Nothing left of the download.
    assert_eq!(std::fs::read_dir(store.relay_dir()).unwrap().count(), 0);

    // Nothing new: no download, and the unacknowledged events go again under the same name,
    // replacing the batch (a cancelled session may have deleted it); they stay.
    let puts = relay.count("PUT /v1/changes/");
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!((r.events_sent, r.editions_fetched.len()), (3, 0));
    assert_eq!(relay.count("PUT /v1/changes/"), puts + 1);
    assert_eq!(relay.changes_of(&keys).iter().map(|c| c.0.clone()).collect::<Vec<_>>(), vec!["1-3"]);
    assert_eq!(relay.count("GET /v1/packages/"), 1);
    assert_eq!(seqs(&store), vec![1, 2, 3]);

    // The phone merged them: the next package's acked covers 1 to 3.
    store.append_event(&event(4)).unwrap();
    let mut eds = editions();
    eds[0].1 = "v2b".into();
    relay.publish(&id(2), &package("db-A", &eds, Some(("s1", saved())), &pictures(), json!({ state.device_id.clone(): 3 })), &[&keys]);
    // Uploading comes before the package, so this sync still sends 1 to 4 (the phone skips
    // what it has merged); the next sends only 4.
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!((r.events_sent, r.editions_fetched.clone()), (4, vec![2]));
    assert_eq!(seqs(&store), vec![4]);
    assert_eq!(sync(&store, &opts(&relay.url())).unwrap().events_sent, 1);
    assert_eq!(relay.changes_of(&keys).iter().map(|c| c.0.clone()).collect::<Vec<_>>(), vec!["1-3", "1-4", "4-4"]);
    let state = store.load_state().unwrap();
    assert_eq!(state.relay_acked, 3);
    assert!(store.next_seq(&state).unwrap() >= 5);
}

#[test]
fn events_go_in_batches_of_500_and_wait_when_the_relay_is_full() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("batches");
    relay.list(&keys);
    for s in 1..=600 {
        store.append_event(&event(s)).unwrap();
    }
    relay.with(|s| s.max_pending = 1);
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 500);
    relay.with(|s| s.max_pending = 64);
    // 1-500 again (replacing it), then 501-600.
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 600);
    let batches: Vec<(String, usize)> = relay.changes_of(&keys).iter().map(|(b, v)| (b.clone(), v["events"].as_array().unwrap().len())).collect();
    assert_eq!(batches, vec![("1-500".into(), 500), ("501-600".into(), 100)]);
    assert_eq!(seqs(&store).len(), 600);
}

#[test]
fn a_dropped_download_resumes_with_range() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("resume");
    relay.list(&keys);
    relay.publish(&id(3), &package("db-A", &editions(), None, &pictures(), json!({})), &[&keys]);
    let total = relay.with(|s| s.package.as_ref().unwrap().envelope.len());
    let half = total / 2;
    relay.with(|s| s.cut_download_after = Some(half));
    match sync(&store, &opts(&relay.url())) {
        Err(SyncError::Relay(_)) => {}
        other => panic!("{other:?}"),
    }
    // Kept for next time, with nothing installed from it.
    let part = store.relay_dir().join(format!("{}.mir", id(3)));
    assert_eq!(std::fs::metadata(&part).unwrap().len() as usize, half);
    assert!(store.load_state().unwrap().editions.is_empty());
    assert!(store.load_state().unwrap().last_error.unwrap().contains("relay"));

    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.editions_fetched, vec![2, 1]);
    assert_eq!(relay.with(|s| s.ranges.clone()), vec![None, Some(format!("bytes={half}-"))]);
    assert!(!part.exists());
}

#[test]
fn a_new_phone_database_wipes_everything() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("wipe");
    relay.list(&keys);
    relay.publish(&id(4), &package("db-A", &editions(), Some(("s1", saved())), &pictures(), json!({})), &[&keys]);
    sync(&store, &opts(&relay.url())).unwrap();
    store.save_liked(&[100]).unwrap();
    assert!(store.has_image("http://img/a.png"));

    // The phone was reinstalled (and this Kobo paired with it again).
    relay.publish(&id(5), &package("db-B", &[(7, "x".into(), bundle(7, "Seventh", &[]))], None, &[], json!({})), &[&keys]);
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert!(r.wiped);
    let state = store.load_state().unwrap();
    assert_eq!((state.db_id.as_deref(), state.edition_order.clone()), (Some("db-B"), vec![7]));
    assert!(store.load_bundle(1).is_none() && store.load_bundle(2).is_none());
    assert!(!store.has_image("http://img/a.png"));
    assert!(store.load_liked().is_empty());
    assert!(store.load_saved().order.is_empty());
}

#[test]
fn an_unlisted_kobo_waits_for_the_phone_and_sends_its_events_again_once_listed() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("unpaired");
    store.append_event(&event(1)).unwrap();
    store.append_event(&event(2)).unwrap();
    // Scanned, but the phone has not sent the device list yet.
    match sync(&store, &opts(&relay.url())) {
        Err(SyncError::RelayNotListed { waiting: true }) => {}
        other => panic!("{other:?}"),
    }
    let state = store.load_state().unwrap();
    assert!(state.relay_not_listed && !state.relay_seen);
    assert!(state.last_error.unwrap().starts_with("Waiting for the phone"));

    relay.list(&keys);
    sync(&store, &opts(&relay.url())).unwrap();
    let state = store.load_state().unwrap();
    assert!(!state.relay_not_listed && state.relay_seen);

    // The phone drops this Kobo, and the relay its pending changes.
    relay.unlist(&keys);
    match sync(&store, &opts(&relay.url())) {
        Err(SyncError::RelayNotListed { waiting: false }) => {}
        other => panic!("{other:?}"),
    }
    let state = store.load_state().unwrap();
    assert!(state.relay_not_listed);
    assert!(state.last_error.unwrap().starts_with("The phone isn\u{2019}t syncing"));
    assert_eq!(seqs(&store), vec![1, 2]);

    // Paired again with the same code: the events go again.
    relay.list(&keys);
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 2);
    assert_eq!(relay.changes_of(&keys).iter().map(|c| c.0.clone()).collect::<Vec<_>>(), vec!["1-2"]);
}

/// The relay group exists only while a Sync session is open on the phone. Between sessions a
/// listed Kobo gets `401 unknown` like a removed one; it says the phone isn't syncing rather
/// than that it is unpaired, and sends again the events the deleted group took unmerged.
#[test]
fn between_sync_sessions_the_kobo_says_the_phone_isnt_syncing_and_sends_its_events_again() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("between-sessions");
    store.append_event(&event(1)).unwrap();
    store.append_event(&event(2)).unwrap();
    relay.list(&keys);
    relay.publish(&id(1), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    assert_eq!(sync(&store, &opts(&relay.url())).unwrap().events_sent, 2);

    // The session is cancelled before the phone merged the upload.
    relay.end_session();
    let e = sync(&store, &opts(&relay.url())).unwrap_err();
    assert!(matches!(e, SyncError::RelayNotListed { waiting: false }), "{e:?}");
    assert_eq!(
        e.to_string(),
        "The phone isn\u{2019}t syncing. Press Start in Sync on the phone, then sync again. If this Kobo was removed from the phone, pair again in Settings."
    );
    assert!(!e.to_string().contains("not paired") && !e.to_string().contains("Not paired"));
    let state = store.load_state().unwrap();
    assert!(state.relay_seen && state.relay_not_listed);
    assert_eq!(seqs(&store), vec![1, 2]);
    // Settings doesn't call it unpaired either.
    let note = morningissue::ui::relay_note(&state);
    assert!(note.starts_with("The phone isn\u{2019}t syncing") && !note.contains("Not paired"), "{note}");

    // The next session: listed again just before the package; the events go again.
    relay.list(&keys);
    relay.publish(&id(2), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 2);
    assert_eq!(relay.changes_of(&keys).iter().map(|c| c.0.clone()).collect::<Vec<_>>(), vec!["1-2"]);
    let state = store.load_state().unwrap();
    assert!(state.relay_seen && !state.relay_not_listed && state.last_error.is_none());
}

/// A session cancelled after this Kobo uploaded, and the next one opened before it syncs
/// again: it never gets a `401`, yet its earlier upload is gone. Every sync sends all
/// unacknowledged events, so they reach the phone anyway.
#[test]
fn events_uploaded_in_a_cancelled_session_go_again_in_the_next() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("cancelled-session");
    store.append_event(&event(1)).unwrap();
    store.append_event(&event(2)).unwrap();
    relay.list(&keys);
    relay.publish(&id(1), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    assert_eq!(sync(&store, &opts(&relay.url())).unwrap().events_sent, 2);

    relay.end_session();
    relay.list(&keys);
    relay.publish(&id(2), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    store.append_event(&event(3)).unwrap();
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 3);
    assert_eq!(relay.changes_of(&keys).iter().map(|c| c.0.clone()).collect::<Vec<_>>(), vec!["1-3"]);
    assert!(relay.with(|s| s.refused.is_empty()));

    // Once a package acknowledges them, they go no more.
    let device = store.load_state().unwrap().device_id;
    relay.end_session();
    relay.list(&keys);
    relay.publish(&id(3), &package("db-A", &editions(), None, &[], json!({ device: 3 })), &[&keys]);
    sync(&store, &opts(&relay.url())).unwrap();
    assert!(store.read_events().unwrap().is_empty());
    let puts = relay.count("PUT /v1/changes/");
    assert_eq!(sync(&store, &opts(&relay.url())).unwrap().events_sent, 0);
    assert_eq!(relay.count("PUT /v1/changes/"), puts);
}

/// More unacknowledged events than the relay's 64 pending batches hold: the oldest 64
/// batches go, the rest wait.
#[test]
fn at_most_64_batches_go_at_once() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("64-batches");
    relay.list(&keys);
    let mut lines = String::new();
    for s in 1..=(64 * 500 + 700) {
        lines.push_str(&serde_json::to_string(&event(s)).unwrap());
        lines.push('\n');
    }
    std::fs::write(store.events_path(), lines).unwrap();
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(r.events_sent, 64 * 500);
    assert_eq!(relay.count("PUT /v1/changes/"), 64);
    let names: Vec<String> = relay.with(|s| s.changes.iter().map(|c| c.1.clone()).collect());
    assert_eq!((names.first().unwrap().as_str(), names.last().unwrap().as_str()), ("1-500", "31501-32000"));
    assert!(relay.with(|s| s.refused.is_empty()));
}

/// `state.json` from before the flag was renamed still loads: `relayUnpaired` is read as
/// `relayNotListed`, which is what is written from then on, and the dropped
/// `relayUploadedSeq` and `relayUnackedSince` are ignored.
#[test]
fn the_old_relay_unpaired_field_still_loads() {
    let store = Store::new(home("old-state"));
    store.ensure_dirs().unwrap();
    std::fs::write(store.state_path(), br#"{"deviceId": "d", "nextSeq": 1, "relaySeen": true, "relayUnpaired": true, "relayUploadedSeq": 40, "relayUnackedSince": 1762900000000}"#).unwrap();
    let state = store.load_state().unwrap();
    assert!(state.relay_seen && state.relay_not_listed);
    store.save_state(&state).unwrap();
    let saved: Value = serde_json::from_slice(&std::fs::read(store.state_path()).unwrap()).unwrap();
    assert_eq!(saved["relayNotListed"], json!(true));
    assert!(saved.get("relayUnpaired").is_none() && saved.get("relayUploadedSeq").is_none() && saved.get("relayUnackedSince").is_none());
}

/// What the pairing screen asks: one signed `GET /v1/package`, false until the phone lists
/// this Kobo. It uploads, downloads and installs nothing, and leaves the last sync's result.
#[test]
fn asking_whether_the_phone_lists_this_kobo() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("listed");
    store.append_event(&event(1)).unwrap();
    relay.publish(&id(1), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    let listed = |address: &str| morningissue::relay::listed(&store, &opts(address));
    assert!(!listed(&relay.url()).unwrap());
    let state = store.load_state().unwrap();
    assert!(state.relay_not_listed && !state.relay_seen && state.last_error.is_none());

    relay.list(&keys);
    assert!(listed(&relay.url()).unwrap());
    let state = store.load_state().unwrap();
    assert!(!state.relay_not_listed && state.relay_seen && state.last_error.is_none());
    assert!(state.relay_package.is_none() && state.editions.is_empty());
    assert_eq!(relay.with(|s| s.requests.clone()), vec!["GET /v1/package", "GET /v1/package"]);
    assert_eq!(seqs(&store), vec![1]);

    // Listed, but the phone hasn't published yet (204): not yet, so the pairing screen keeps polling.
    let early = FakeRelay::start();
    early.list(&keys);
    assert!(!listed(&early.url()).unwrap());

    // A relay that can't be reached is an error, not a "no".
    match listed(&format!("http://{}", std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap())) {
        Err(SyncError::Relay(_)) => {}
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_wrong_clock_is_put_right_from_the_date_header_and_the_request_retried() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("skew");
    relay.list(&keys);
    relay.with(|s| s.skew_s = 3600);
    relay.publish(&id(6), &package("db-A", &editions(), None, &[], json!({})), &[&keys]);
    sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(relay.with(|s| s.refused.clone()), vec!["skew".to_string()]);
    let offset = store.load_state().unwrap().clock_offset_ms;
    assert!((offset - 3_600_000).abs() < 3_000, "{offset}");
    // The same requests again, in the same second, are signed afresh: never a replay.
    sync(&store, &opts(&relay.url())).unwrap();
    sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!(relay.with(|s| s.refused.clone()), vec!["skew".to_string()]);
}

#[test]
fn a_tampered_package_is_not_installed() {
    let relay = FakeRelay::start();
    let (store, keys) = kobo("tamper");
    relay.list(&keys);
    relay.publish(&id(7), &package("db-A", &editions(), None, &pictures(), json!({})), &[&keys]);
    relay.with(|s| {
        let env = &mut s.package.as_mut().unwrap().envelope;
        let n = env.len();
        env[n - 40] ^= 0x10;
    });
    match sync(&store, &opts(&relay.url())) {
        Err(SyncError::Integrity(_)) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().editions.is_empty());
    assert!(store.load_bundle(2).is_none());
    assert_eq!(std::fs::read_dir(store.relay_dir()).unwrap().count(), 0, "neither the download nor the plaintext kept");
    assert!(relay.with(|s| s.fetched.is_empty()));
    // A key wrapped for another device does not open either.
    let other = DeviceKeys::from_secret(&[9; 32]);
    relay.publish(&id(8), &package("db-A", &editions(), None, &[], json!({})), &[&other]);
    relay.with(|s| {
        let p = s.package.as_mut().unwrap();
        let k = p.keys.remove(&other.kid).unwrap();
        p.keys.insert(keys.kid.clone(), k);
    });
    assert!(matches!(sync(&store, &opts(&relay.url())), Err(SyncError::Integrity(_))));
    // A good one installs.
    relay.publish(&id(9), &package("db-A", &editions(), None, &pictures(), json!({})), &[&keys]);
    assert_eq!(sync(&store, &opts(&relay.url())).unwrap().editions_fetched, vec![2, 1]);
}

/// A Kobo that has never shown its pairing code has no secret: the sync says so and asks the
/// relay nothing. A relay address that can't be used is reported too.
#[test]
fn without_a_pairing_secret_nothing_is_asked() {
    let relay = FakeRelay::start();
    let store = Store::new(home("never-paired"));
    match sync(&store, &opts(&relay.url())) {
        Err(SyncError::NotPaired) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().starts_with("Not paired yet"));
    assert!(relay.with(|s| s.requests.is_empty()));
    let (store, _) = kobo("bad-address");
    match sync(&store, &opts("ftp://x")) {
        Err(SyncError::RelayAddress(_)) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().contains("config.json"));
}

/// A CA and a `localhost` certificate it signed, valid from `not_before` (year).
fn certificates(not_before: i32) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    use rcgen::{BasicConstraints, CertificateParams, IsCa, KeyPair};
    let ca_key = KeyPair::generate().unwrap();
    let mut ca = CertificateParams::new(Vec::<String>::new()).unwrap();
    ca.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    let ca = ca.self_signed(&ca_key).unwrap();
    let leaf_key = KeyPair::generate().unwrap();
    let mut leaf = CertificateParams::new(vec!["localhost".to_string()]).unwrap();
    leaf.not_before = rcgen::date_time_ymd(not_before, 1, 1);
    leaf.not_after = rcgen::date_time_ymd(not_before + 50, 1, 1);
    let leaf = leaf.signed_by(&leaf_key, &ca, &ca_key).unwrap();
    (ca.der().to_vec(), leaf.der().to_vec(), leaf_key.serialize_der())
}

#[test]
fn an_https_relay_is_reached_over_tls_and_a_certificate_from_the_future_blames_the_clock() {
    let (ca, leaf, key) = certificates(2020);
    morningissue::tls::add_trusted_root(ca);
    let relay = FakeRelay::start_tls(vec![leaf], key);
    assert!(relay.url().starts_with("https://localhost:"));
    let (store, keys) = kobo("tls");
    relay.list(&keys);
    store.append_event(&event(1)).unwrap();
    relay.publish(&id(10), &package("db-A", &editions(), None, &pictures(), json!({})), &[&keys]);
    let r = sync(&store, &opts(&relay.url())).unwrap();
    assert_eq!((r.events_sent, r.editions_fetched.clone()), (1, vec![2, 1]));
    // Plain HTTP to a TLS port fails; it is not silently sent in the clear.
    let mut plain = opts(&relay.url().replace("https://", "http://"));
    plain.request_timeout = Duration::from_secs(2);
    assert!(sync(&store, &plain).is_err());

    // Not valid until 2100: the Kobo's time (however far behind) is the suspect.
    let (ca, leaf, key) = certificates(2100);
    morningissue::tls::add_trusted_root(ca);
    let future = FakeRelay::start_tls(vec![leaf], key);
    let (store, keys) = kobo("tls-future");
    future.list(&keys);
    match sync(&store, &opts(&future.url())) {
        Err(SyncError::Clock) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().contains("clock is wrong"));
}

#[test]
fn relay_addresses() {
    use morningissue::relay::parse_address;
    assert_eq!(parse_address("relay.example.org").unwrap(), "https://relay.example.org");
    assert_eq!(parse_address("192.168.1.9:8080").unwrap(), "http://192.168.1.9:8080");
    assert_eq!(parse_address("http://relay.example.org:8080/").unwrap(), "http://relay.example.org:8080");
    assert_eq!(parse_address("HTTPS://Relay.Example.org:443").unwrap(), "https://relay.example.org");
    assert!(parse_address("").is_err());
    assert!(parse_address("ftp://x").is_err());
    let uri = morningissue::relay::pair_uri("AAEC-_", "b7e2", "Kobo Libra 2", 1264);
    assert_eq!(uri, "morningissue:pair?v=1&s=AAEC-_&d=b7e2&n=Kobo%20Libra%202&w=1264");
}

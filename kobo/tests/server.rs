//! The sync order of `docs/kobo-sync.md` ("Sync", protocol server/1) against an in-process
//! fake server over a real socket, plain and TLS.

mod fakeserver;

use fakeserver::{FakeServer, Picture};
use morningissue::crypto;
use morningissue::server::{hello, is_plain_http, pair_uri, parse_address};
use morningissue::store::{Event, Store};
use morningissue::sync::{sync, Options, Progress, SyncError};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

fn home(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("mi-server-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}

fn opts(server: &str) -> Options {
    let mut o = Options::new(Some(server.into()));
    o.request_timeout = Duration::from_secs(5);
    o.image_timeout = Duration::from_secs(5);
    o.deadline = Duration::from_secs(60);
    o
}

/// A Kobo that has shown its pairing code: its store and the code's text.
fn kobo(name: &str) -> (Store, String) {
    let store = Store::new(home(name));
    let secret = store.ensure_secret().unwrap();
    // Made once.
    assert_eq!(store.ensure_secret().unwrap(), secret);
    let state = store.load_state().unwrap();
    (store, pair_uri(&secret, &state.device_id, "Kobo Libra 2", 1264))
}

/// A Kobo the server knows already.
fn paired(name: &str, server: &FakeServer) -> Store {
    let (store, uri) = kobo(name);
    server.pair(&uri);
    store
}

fn bundle(id: i64, headline: &str, images: &[&str]) -> Value {
    let blocks: Vec<Value> = images.iter().map(|u| json!({"t": "img", "url": u})).chain([json!({"t": "p", "text": {"text": "Body.", "spans": []}})]).collect();
    json!({
        "id": id, "date": "2026-11-11", "kind": "MORNING", "number": id, "length": "STANDARD",
        "releaseAt": "2026-11-11T06:30:00Z", "finishedAt": null, "cutoffAt": "2026-11-11T06:00:00Z",
        "pages": [{"page": 1, "items": [{"articleId": id * 100, "slot": 0, "kind": "LEAD", "section": null, "reason": "r"}]}],
        "articles": {(id * 100).to_string(): {
            "feedTitle": "Feed", "title": headline, "author": null, "url": "https://example.com/a",
            "publishedAt": "2026-11-10T20:00:00Z", "summary": "S", "imageUrl": images.first(), "wordCount": 300,
            "feedId": 3, "guid": "g", "blocks": blocks,
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

fn png(w: u32, seed: u32) -> Picture {
    (picture(w, seed), "image/png".into(), w, w / 2)
}

fn event(seq: i64) -> Event {
    Event { seq, kind: "opened".into(), article_id: Some(100), edition_id: None, page: None, value: None, ms: None, at: "2026-11-12T07:00:00Z".into() }
}

fn seqs(store: &Store) -> Vec<i64> {
    store.read_events().unwrap().iter().map(|e| e.seq).collect()
}

/// Editions 2 and 1, and the pictures the server can get for them: a.png at this Kobo's
/// width, b.png only wider (as a server that ignored the width might send), and
/// missing.webp not at all.
fn publish(server: &FakeServer) {
    server.with(|s| {
        s.editions = vec![(2, "v2".into(), bundle(2, "Second", &["http://img/a.png", "http://img/missing.webp"])), (1, "v1".into(), bundle(1, "First", &["http://img/b.png"]))];
        s.saved = Some(("s1".into(), saved()));
        s.pictures.insert("http://img/a.png".into(), png(1264, 2));
        s.pictures.insert("http://img/b.png".into(), png(1404, 5));
    });
}

fn saved() -> Value {
    json!({"version": "s1", "order": [100], "articles": {"100": bundle(1, "First", &[])["articles"]["100"]}})
}

fn server_seqs(server: &FakeServer) -> Vec<i64> {
    server.with(|s| s.events.iter().map(|e| e["seq"].as_i64().unwrap()).collect())
}

/// The pairing code is v=2 and carries the secret; until the phone has registered it the
/// server answers `401 unknown`, and the Kobo waits for the phone. Then it is known, and
/// syncs on its own.
#[test]
fn the_kobo_waits_for_the_phone_then_syncs_on_its_own() {
    let server = FakeServer::start();
    publish(&server);
    let (store, uri) = kobo("pairing");
    assert!(uri.starts_with("morningissue:pair?v=2&s="), "{uri}");
    assert!(!hello(&store, &opts(&server.url())).unwrap());
    let state = store.load_state().unwrap();
    assert!(state.server_not_listed && !state.server_seen);
    match sync(&store, &opts(&server.url())) {
        Err(e @ SyncError::NotListed { waiting: true }) => assert!(e.to_string().starts_with("Waiting for the phone")),
        other => panic!("{other:?}"),
    }
    // Only the questions; nothing was handed out.
    assert_eq!(server.with(|s| s.refused.clone()), vec!["unknown"; 2]);

    // The phone scans the code: the server derives the same token.
    server.pair(&uri);
    assert!(hello(&store, &opts(&server.url())).unwrap());
    let state = store.load_state().unwrap();
    assert!(state.server_seen && !state.server_not_listed);
    // The last sync's result is the pairing poll's to leave alone.
    assert!(state.last_error.unwrap().starts_with("Waiting"));
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!(r.editions_fetched, vec![2, 1]);
    assert!(store.load_state().unwrap().last_error.is_none());
}

#[test]
fn a_sync_uploads_events_installs_editions_and_pictures_and_follows_versions() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("install", &server);
    for s in 1..=3 {
        store.append_event(&event(s)).unwrap();
    }
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!(r.address, server.url());
    assert_eq!((r.events_sent, r.editions_fetched.clone(), r.saved_fetched), (3, vec![2, 1], true));
    assert_eq!((r.images_fetched, r.images_missing), (2, 1));
    // Merged, acknowledged and gone from the Kobo.
    assert_eq!(server_seqs(&server), vec![1, 2, 3]);
    assert!(seqs(&store).is_empty());
    let state = store.load_state().unwrap();
    assert_eq!(state.acked, 3);
    assert!(state.next_seq >= 4);
    assert!(state.last_sync_at.is_some() && state.last_error.is_none() && state.server_seen);
    assert_eq!((state.db_id.as_deref(), state.db_protocol.as_deref()), (Some("db"), Some("server/1")));
    assert_eq!(state.edition_order, vec![2, 1]);
    // The order of `docs/kobo-sync.md`, every gated request with the header.
    let requests = server.with(|s| s.requests.clone());
    assert_eq!(
        requests,
        vec!["GET /api/v1/kobo/hello", "POST /api/v1/sync/events", "GET /api/v1/sync/manifest?editions=3", "GET /api/v1/editions/2/bundle", "GET /api/v1/editions/1/bundle", "GET /api/v1/saved", "POST /api/v1/kobo/images"]
    );
    assert!(server.with(|s| s.through.iter().all(|(_, t)| *t == Some(0))));
    // Pictures asked for at this Kobo's width, newest edition first in reading order.
    let asked = server.with(|s| s.image_requests.clone());
    assert_eq!(asked, vec![(1264, vec!["http://img/a.png".to_string(), "http://img/missing.webp".into(), "http://img/b.png".into()])]);
    assert_eq!(store.read_image("http://img/a.png").unwrap(), picture(1264, 2));
    let b = image::load_from_memory(&store.read_image("http://img/b.png").unwrap()).unwrap();
    assert_eq!((b.width(), b.height()), (1264, 632));
    assert_eq!(store.load_saved().order, vec![100]);
    assert!(store.load_bundle(2).is_some() && store.load_bundle(1).is_some());
    assert_eq!(std::fs::read_dir(store.incoming_dir()).unwrap().count(), 0, "nothing left of the zip");

    // Nothing new: hello and the manifest only (the missing picture is asked for again).
    let before = server.with(|s| s.requests.len());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!((r.events_sent, r.editions_fetched.len(), r.saved_fetched), (0, 0, false));
    let after: Vec<String> = server.with(|s| s.requests[before..].to_vec());
    assert_eq!(after, vec!["GET /api/v1/kobo/hello", "GET /api/v1/sync/manifest?editions=3", "POST /api/v1/kobo/images"]);

    // One edition changes, the saved shelf changes, and edition 1 leaves for a new one.
    server.with(|s| {
        s.editions[0].1 = "v2b".into();
        s.editions[1] = (3, "v3".into(), bundle(3, "Third", &[]));
        s.editions.swap(0, 1);
        s.saved = Some(("s2".into(), json!({"version": "s2", "order": [], "articles": {}})));
    });
    let before = server.with(|s| s.requests.len());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!((r.editions_fetched.clone(), r.editions_removed.clone(), r.saved_fetched), (vec![3, 2], vec![1], true));
    let after: Vec<String> = server.with(|s| s.requests[before..].to_vec());
    assert!(after.contains(&"GET /api/v1/editions/2/bundle".to_string()) && after.contains(&"GET /api/v1/saved".to_string()), "{after:?}");
    assert_eq!(store.load_state().unwrap().edition_order, vec![3, 2]);
    assert!(store.load_bundle(1).is_none());
    // b.png belonged to edition 1 only.
    assert!(store.read_image("http://img/b.png").is_none());
    assert!(store.read_image("http://img/a.png").is_some());
    assert!(store.load_saved().order.is_empty());
}

/// `409 events_pending`: the events above the `acked` it names go again, and the manifest is
/// asked again; at most twice, then the sync gives up.
#[test]
fn the_gate_sends_the_events_again_then_gives_up() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("gate", &server);
    for s in 1..=5 {
        store.append_event(&event(s)).unwrap();
    }
    // The first upload is acknowledged only through 2.
    server.with(|s| s.ack_caps.push_back(2));
    sync(&store, &opts(&server.url())).unwrap();
    let requests = server.with(|s| s.requests.clone());
    assert_eq!(requests[..5], ["GET /api/v1/kobo/hello", "POST /api/v1/sync/events", "GET /api/v1/sync/manifest?editions=3", "POST /api/v1/sync/events", "GET /api/v1/sync/manifest?editions=3"]);
    assert_eq!(server.with(|s| s.through[..2].iter().map(|t| t.1).collect::<Vec<_>>()), vec![Some(5), Some(0)]);
    assert_eq!(server.with(|s| s.uploads.clone()), vec![5, 3]);
    assert_eq!(server_seqs(&server), vec![1, 2, 3, 4, 5]);
    assert!(seqs(&store).is_empty());

    // A server that never takes them.
    let server = FakeServer::start();
    publish(&server);
    let store = paired("gate-stuck", &server);
    store.append_event(&event(1)).unwrap();
    server.with(|s| s.ack_caps.extend([0, 0, 0]));
    match sync(&store, &opts(&server.url())) {
        Err(SyncError::EventsPending) => {}
        other => panic!("{other:?}"),
    }
    assert_eq!(server.count("GET /api/v1/sync/manifest"), 3);
    assert_eq!(server.count("POST /api/v1/sync/events"), 3);
    assert_eq!(seqs(&store), vec![1], "kept for the next sync");
    assert!(store.load_bundle(2).is_none());
}

/// An event the reader records while the sync runs makes the next gated request `409`; it is
/// uploaded and the request asked again.
#[test]
fn an_event_recorded_during_the_sync_goes_before_the_next_bundle() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("during", &server);
    let home = store.home.clone();
    let mut o = opts(&server.url());
    o.progress = Some(Arc::new(move |p| {
        if let Progress::Connected { .. } = p {
            Store::new(&home).append_event(&event(7)).unwrap();
        }
    }));
    let r = sync(&store, &o).unwrap();
    assert_eq!(r.editions_fetched, vec![2, 1]);
    let requests = server.with(|s| s.requests.clone());
    assert_eq!(requests[..5], ["GET /api/v1/kobo/hello", "GET /api/v1/sync/manifest?editions=3", "GET /api/v1/editions/2/bundle", "POST /api/v1/sync/events", "GET /api/v1/editions/2/bundle"]);
    assert_eq!(server_seqs(&server), vec![7]);
    assert!(seqs(&store).is_empty());
}

#[test]
fn events_go_500_at_a_time() {
    let server = FakeServer::start();
    let store = paired("batches", &server);
    for s in 1..=1203 {
        store.append_event(&event(s)).unwrap();
    }
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!(r.events_sent, 1203);
    assert_eq!(server.with(|s| s.uploads.clone()), vec![500, 500, 203]);
    assert!(seqs(&store).is_empty());
}

#[test]
fn pictures_are_asked_for_100_at_a_time() {
    let server = FakeServer::start();
    let urls: Vec<String> = (0..150).map(|i| format!("http://img/{i}.png")).collect();
    let refs: Vec<&str> = urls.iter().map(String::as_str).collect();
    server.with(|s| {
        s.editions = vec![(1, "v1".into(), bundle(1, "Many", &refs))];
        for u in &urls[..140] {
            s.pictures.insert(u.clone(), png(64, 1));
        }
    });
    let store = paired("chunks", &server);
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!((r.images_fetched, r.images_missing), (140, 10));
    assert_eq!(server.with(|s| s.image_requests.iter().map(|r| r.1.len()).collect::<Vec<_>>()), vec![100, 50]);
}

#[test]
fn a_new_server_database_wipes_everything() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("wipe", &server);
    sync(&store, &opts(&server.url())).unwrap();
    std::fs::write(store.liked_path(), b"[100]").unwrap();
    server.with(|s| s.db_id = "db-B".into());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert!(r.wiped);
    assert_eq!(r.editions_fetched, vec![2, 1], "fetched again after the wipe");
    assert_eq!(store.load_state().unwrap().db_id.as_deref(), Some("db-B"));
    assert!(store.load_liked().is_empty());
}

/// Events recorded against a database the server no longer has (it was reset or restored
/// from a backup) would name other articles there: the `dbId` in hello wipes them before
/// anything is uploaded.
#[test]
fn a_new_database_in_hello_wipes_the_events_before_they_go() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("hello-wipe", &server);
    sync(&store, &opts(&server.url())).unwrap();
    for s in 1..=3 {
        store.append_event(&event(s)).unwrap();
    }
    std::fs::write(store.liked_path(), b"[100]").unwrap();
    // The server starts over with a new database.
    server.with(|s| s.db_id = "db-B".into());
    let before = server.with(|s| s.requests.len());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert!(r.wiped);
    assert_eq!(r.events_sent, 0);
    assert_eq!(server.count("POST /api/v1/sync/events"), 0);
    assert!(server_seqs(&server).is_empty(), "no event reached the new database");
    assert!(seqs(&store).is_empty());
    assert!(store.load_liked().is_empty());
    let after: Vec<String> = server.with(|s| s.requests[before..before + 2].to_vec());
    assert_eq!(after, vec!["GET /api/v1/kobo/hello", "GET /api/v1/sync/manifest?editions=3"]);
    assert_eq!(r.editions_fetched, vec![2, 1], "fetched again after the wipe");
    let state = store.load_state().unwrap();
    assert_eq!(state.db_id.as_deref(), Some("db-B"));
    assert!(state.next_seq >= 4, "seqs only go up");
}

/// The same `dbId` in hello: the events go as before, and nothing is wiped.
#[test]
fn the_same_database_in_hello_keeps_the_events() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("hello-same", &server);
    sync(&store, &opts(&server.url())).unwrap();
    for s in 1..=3 {
        store.append_event(&event(s)).unwrap();
    }
    let before = server.with(|s| s.requests.len());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert!(!r.wiped);
    assert_eq!(r.events_sent, 3);
    assert_eq!(server_seqs(&server), vec![1, 2, 3]);
    let after: Vec<String> = server.with(|s| s.requests[before..before + 3].to_vec());
    assert_eq!(after, vec!["GET /api/v1/kobo/hello", "POST /api/v1/sync/events", "GET /api/v1/sync/manifest?editions=3"]);
    assert!(store.load_bundle(2).is_some());
}

/// A server whose hello names no `dbId`: the events go, and the manifest's `dbId` decides
/// on the wipe as before.
#[test]
fn a_hello_without_a_database_leaves_the_check_to_the_manifest() {
    let server = FakeServer::start();
    publish(&server);
    server.with(|s| s.hello_db_id = false);
    let store = paired("hello-no-db", &server);
    sync(&store, &opts(&server.url())).unwrap();
    assert_eq!(store.load_state().unwrap().db_id.as_deref(), Some("db"));
    store.append_event(&event(1)).unwrap();
    server.with(|s| s.db_id = "db-B".into());
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!(r.events_sent, 1);
    assert!(r.wiped);
    assert_eq!(store.load_state().unwrap().db_id.as_deref(), Some("db-B"));
}

/// A Kobo upgraded from relay sync keeps its secret (`relaySecret`) and acked (`relayAcked`),
/// but what it stored came from the phone's database: it is wiped before anything is
/// uploaded, so no event reaches the server with the phone's IDs.
#[test]
fn what_a_relay_version_stored_is_wiped_before_the_first_upload() {
    let server = FakeServer::start();
    publish(&server);
    let store = Store::new(home("from-relay"));
    store.ensure_dirs().unwrap();
    let secret = [5u8; 32];
    let old = json!({
        "deviceId": "b7e2c0de-0000-4000-8000-000000000000", "dbId": "phone-db", "nextSeq": 6,
        "editions": {"77": "pv"}, "editionOrder": [77], "relaySecret": crypto::b64url(&secret),
        "relayPackage": "AAAAAAAAAAAAAAAAAAAAAA", "relayFetched": true, "relayAcked": 3, "relaySeen": true, "relayNotListed": false
    });
    std::fs::write(store.state_path(), serde_json::to_vec(&old).unwrap()).unwrap();
    std::fs::write(store.edition_path(77), serde_json::to_vec(&bundle(77, "Old", &[])).unwrap()).unwrap();
    for s in 4..=5 {
        store.append_event(&event(s)).unwrap();
    }
    std::fs::create_dir_all(store.old_relay_dir()).unwrap();
    std::fs::write(store.old_relay_dir().join("x.mir"), b"left over").unwrap();
    let state = store.load_state().unwrap();
    assert_eq!((state.secret.as_deref(), state.acked), (Some(crypto::b64url(&secret).as_str()), 3));
    // The relay letting it in says nothing about the server.
    assert!(!state.server_seen);

    server.pair_secret(&secret, &state.device_id);
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert!(r.wiped);
    assert!(server_seqs(&server).is_empty(), "the phone's events never reach the server");
    assert_eq!(server.count("POST /api/v1/sync/events"), 0);
    let state = store.load_state().unwrap();
    assert_eq!(state.edition_order, vec![2, 1]);
    assert!(store.load_bundle(77).is_none());
    assert!(!store.old_relay_dir().exists());
    assert!(state.next_seq >= 6, "seqs only go up");
    let written = std::fs::read_to_string(store.state_path()).unwrap();
    assert!(written.contains("\"secret\"") && !written.contains("relay"), "{written}");
}

/// After a sync has got through, `401 unknown` means this Kobo was removed from the account.
#[test]
fn a_removed_kobo_is_told_to_pair_again() {
    let server = FakeServer::start();
    publish(&server);
    let store = paired("removed", &server);
    sync(&store, &opts(&server.url())).unwrap();
    server.remove();
    store.append_event(&event(1)).unwrap();
    match sync(&store, &opts(&server.url())) {
        Err(e @ SyncError::NotListed { waiting: false }) => assert_eq!(e.to_string(), "This Kobo was removed from your account; pair again."),
        other => panic!("{other:?}"),
    }
    let state = store.load_state().unwrap();
    assert!(state.server_seen && state.server_not_listed);
    assert_eq!(state.last_error.as_deref(), Some("This Kobo was removed from your account; pair again."));
    // Hello was refused, so nothing went up; the event waits.
    assert_eq!(server.count("POST /api/v1/sync/events"), 0);
    assert_eq!(seqs(&store), vec![1]);
    // What it had stays readable.
    assert_eq!(state.edition_order, vec![2, 1]);
    assert!(!hello(&store, &opts(&server.url())).unwrap());
}

/// No secret, no address, or an address that can't be used: the sync says so and asks the
/// server nothing.
#[test]
fn without_a_secret_or_an_address_nothing_is_asked() {
    let server = FakeServer::start();
    let store = Store::new(home("never-paired"));
    match sync(&store, &opts(&server.url())) {
        Err(SyncError::NotPaired) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().starts_with("Not paired yet"));
    let (store, _) = kobo("no-server");
    match sync(&store, &Options::new(None)) {
        Err(SyncError::NoServer) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().starts_with("No server address"));
    match sync(&store, &opts("ftp://x")) {
        Err(SyncError::ServerAddress(_)) => {}
        other => panic!("{other:?}"),
    }
    assert!(server.with(|s| s.requests.is_empty()));
}

#[test]
fn a_wrong_clock_is_put_right_from_the_date_header() {
    let server = FakeServer::start();
    server.with(|s| s.skew_s = 3600);
    let store = paired("skew", &server);
    sync(&store, &opts(&server.url())).unwrap();
    let offset = store.load_state().unwrap().clock_offset_ms;
    assert!((offset - 3_600_000).abs() < 3_000, "{offset}");
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
fn an_https_server_is_reached_over_tls_and_a_certificate_from_the_future_blames_the_clock() {
    let (ca, leaf, key) = certificates(2020);
    morningissue::tls::add_trusted_root(ca);
    let server = FakeServer::start_tls(vec![leaf], key);
    publish(&server);
    assert!(server.url().starts_with("https://localhost:"));
    let store = paired("tls", &server);
    store.append_event(&event(1)).unwrap();
    let r = sync(&store, &opts(&server.url())).unwrap();
    assert_eq!((r.events_sent, r.editions_fetched.clone(), r.images_fetched), (1, vec![2, 1], 2));
    // Plain HTTP to a TLS port fails; the token is not silently sent in the clear.
    let mut plain = opts(&server.url().replace("https://", "http://"));
    plain.request_timeout = Duration::from_secs(2);
    assert!(sync(&store, &plain).is_err());

    // Not valid until 2100: the Kobo's time (however far behind) is the suspect.
    let (ca, leaf, key) = certificates(2100);
    morningissue::tls::add_trusted_root(ca);
    let future = FakeServer::start_tls(vec![leaf], key);
    let store = paired("tls-future", &future);
    match sync(&store, &opts(&future.url())) {
        Err(SyncError::Clock) => {}
        other => panic!("{other:?}"),
    }
    assert!(store.load_state().unwrap().last_error.unwrap().contains("clock is wrong"));
}

#[test]
fn addresses_and_the_pairing_code() {
    assert_eq!(parse_address("news.example.org").unwrap(), "https://news.example.org");
    assert_eq!(parse_address("192.168.1.9:8417").unwrap(), "http://192.168.1.9:8417");
    assert_eq!(parse_address("http://news.example.org:8417/").unwrap(), "http://news.example.org:8417");
    assert_eq!(parse_address("HTTPS://News.Example.org:443/mi").unwrap(), "https://news.example.org/mi");
    assert!(parse_address("").is_err());
    assert!(parse_address("ftp://x").is_err());
    assert!(is_plain_http("HTTP://192.168.1.9:8417") && !is_plain_http("https://news.example.org"));
    let uri = pair_uri("AAEC-_", "b7e2", "Kobo Libra 2", 1264);
    assert_eq!(uri, "morningissue:pair?v=2&s=AAEC-_&d=b7e2&n=Kobo%20Libra%202&w=1264");
}

/// The token this Kobo sends is the shared vector's for its secret.
#[test]
fn the_bearer_token_is_derived_from_the_secret() {
    let s: Vec<u8> = (0u8..32).collect();
    assert_eq!(crypto::token(&s), "gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc");
    let server = FakeServer::start();
    let store = Store::new(home("token"));
    store.ensure_dirs().unwrap();
    let mut state = store.load_state().unwrap();
    state.secret = Some(crypto::b64url(&s));
    store.save_state(&state).unwrap();
    server.with(|st| st.token = Some("gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc".into()));
    assert!(hello(&store, &opts(&server.url())).unwrap());
}

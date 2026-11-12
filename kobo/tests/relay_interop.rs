//! The Kobo client against the real relay (`relay/`, Go), with this test playing the phone.
//! Ignored unless `MORNINGISSUE_RELAY_BIN` names a built relay binary:
//!
//! ```sh
//! (cd relay && go build -o /tmp/relay .)
//! MORNINGISSUE_RELAY_BIN=/tmp/relay cargo test --test relay_interop -- --ignored
//! ```

mod fakerelay;

use morningissue::crypto::{self, DeviceKeys, KIND_CHANGES, KIND_PACKAGE, KIND_WRAP};
use morningissue::http::{self, Call, Endpoint, Response};
use morningissue::store::{Event, Store};
use morningissue::sync::{sync, Options, SyncError};
use serde_json::{json, Value};
use std::process::{Child, Command, Stdio};
use std::time::Duration;

struct Relay(Child);

impl Drop for Relay {
    fn drop(&mut self) {
        let _ = self.0.kill();
    }
}

/// The phone's side: requests signed with the owner key.
struct Owner {
    ep: Endpoint,
    key: [u8; 32],
    kid: String,
}

impl Owner {
    fn call(&self, method: &str, path: &str, body: &[u8], ctype: &str) -> Response {
        let ts = morningissue::time::system_millis() / 1000;
        let headers = vec![("Authorization", crypto::authorization(&self.kid, &self.key, method, path, ts, body))];
        let c = Call { method, target: path, headers: &headers, body: if body.is_empty() && method == "GET" { None } else { Some(body) }, content_type: ctype };
        let r = http::call(&self.ep, &c, Duration::from_secs(10), None).unwrap();
        // The relay refuses a repeated signature; never send two in one second.
        std::thread::sleep(Duration::from_millis(1001));
        r
    }
    fn json(&self, method: &str, path: &str, body: Value) -> Response {
        self.call(method, path, body.to_string().as_bytes(), "application/json")
    }
}

#[test]
#[ignore]
fn against_the_real_relay() {
    let Some(bin) = std::env::var_os("MORNINGISSUE_RELAY_BIN") else {
        eprintln!("MORNINGISSUE_RELAY_BIN not set; skipped");
        return;
    };
    let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let data = std::env::temp_dir().join(format!("mi-relay-interop-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data);
    let _relay = Relay(Command::new(bin).args(["-addr", &format!("127.0.0.1:{port}"), "-data"]).arg(&data).arg("-quiet").stdout(Stdio::null()).spawn().unwrap());
    let url = format!("http://127.0.0.1:{port}");
    let ep = Endpoint::parse(&url).unwrap();
    for _ in 0..100 {
        if std::net::TcpStream::connect(("127.0.0.1", port)).is_ok() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }

    let home = std::env::temp_dir().join(format!("mi-relay-interop-kobo-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    let store = Store::new(&home);
    let secret = crypto::b64url_decode(&store.ensure_relay_secret().unwrap()).unwrap();
    let device = DeviceKeys::from_secret(&secret);
    let device_id = store.load_state().unwrap().device_id;
    let opts = Options { request_timeout: Duration::from_secs(10), deadline: Duration::from_secs(120), image_width: 1264, progress: None, relay: url.clone() };

    // Before the phone lists it: waiting.
    assert!(matches!(sync(&store, &opts), Err(SyncError::RelayUnpaired { waiting: true })));

    let owner_key = crypto::hkdf32(&[0x80; 32], "morningissue relay owner v1");
    let owner = Owner { ep, key: owner_key, kid: crypto::kid(&owner_key) };
    let r = owner.json("PUT", "/v1/group", json!({"ownerKey": crypto::b64url(&owner_key)}));
    assert_eq!(r.status, 200, "{}", String::from_utf8_lossy(&r.body));
    let r = owner.json("PUT", "/v1/group/devices", json!({"devices": [crypto::b64url(&device.auth)]}));
    assert_eq!(r.status, 200, "{}", String::from_utf8_lossy(&r.body));

    // Events up, no package yet.
    for seq in 1..=3 {
        store.append_event(&Event { seq, kind: "opened".into(), article_id: Some(100), edition_id: None, page: None, value: None, ms: None, at: "2026-11-12T07:00:00Z".into() }).unwrap();
    }
    let r = sync(&store, &opts).unwrap();
    assert_eq!(r.events_sent, 3);

    // The phone collects them.
    let r = owner.call("GET", "/v1/group/changes", b"", "");
    let list: Value = serde_json::from_slice(&r.body).unwrap();
    let change = &list["changes"][0];
    assert_eq!((change["kid"].as_str(), change["batch"].as_str()), (Some(device.kid.as_str()), Some("1-3")));
    let env = owner.call("GET", &format!("/v1/group/changes/{}", change["id"].as_str().unwrap()), b"", "").body;
    let plain: Value = serde_json::from_slice(&crypto::open(&device.enc, KIND_CHANGES, &format!("changes|{}|1-3", device.kid), &env).unwrap()).unwrap();
    assert_eq!(plain["deviceId"], device_id.as_str());
    assert_eq!(plain["events"].as_array().unwrap().len(), 3);

    // And publishes a package that has merged them.
    let bundle = json!({"id": 1, "date": "2026-11-12", "kind": "MORNING", "number": 1, "pages": [], "articles": {}});
    let zip = fakerelay::package("db-real", &[(1, "v1".into(), bundle)], None, &[], json!({ device_id.clone(): 3 }));
    let pid = crypto::b64url(&[0x42; 16]);
    let pkg_key: [u8; 32] = crypto::random().unwrap();
    let envelope = crypto::seal(&pkg_key, KIND_PACKAGE, &format!("package|{pid}"), &zip).unwrap();
    let r = owner.call("PUT", &format!("/v1/group/packages/{pid}"), &envelope, "application/octet-stream");
    assert_eq!(r.status, 204, "{}", String::from_utf8_lossy(&r.body));
    let wrapped = crypto::seal(&device.enc, KIND_WRAP, &format!("wrap|{}|{pid}", device.kid), &pkg_key).unwrap();
    let r = owner.json("POST", &format!("/v1/group/packages/{pid}/publish"), json!({"keys": {device.kid.clone(): crypto::b64url(&wrapped)}}));
    assert_eq!(r.status, 204, "{}", String::from_utf8_lossy(&r.body));

    let r = sync(&store, &opts).unwrap();
    assert_eq!(r.editions_fetched, vec![1]);
    assert!(store.read_events().unwrap().is_empty(), "acked events deleted");
    // Twice more, at once: no replay refusals.
    sync(&store, &opts).unwrap();
    sync(&store, &opts).unwrap();
    let status: Value = serde_json::from_slice(&owner.call("GET", "/v1/group/status", b"", "").body).unwrap();
    assert_eq!(status["package"]["fetchedBy"], json!([device.kid]));

    // Dropped by the phone.
    owner.json("PUT", "/v1/group/devices", json!({"devices": []}));
    assert!(matches!(sync(&store, &opts), Err(SyncError::RelayUnpaired { waiting: false })));
}

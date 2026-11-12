//! Every vector in `docs/relay-sync-fixtures/vectors.json`, and envelopes that must be refused.

use morningissue::crypto::{self, DeviceKeys, EnvelopeError, KIND_CHANGES, KIND_PACKAGE, KIND_WRAP};
use serde_json::Value;

fn vectors() -> Value {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/relay-sync-fixtures/vectors.json");
    serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn key32(v: &Value, name: &str) -> [u8; 32] {
    unhex(v[name].as_str().unwrap()).try_into().unwrap()
}

fn prefix(v: &Value) -> [u8; 8] {
    unhex(v["noncePrefix"].as_str().unwrap()).try_into().unwrap()
}

#[test]
fn derivations_and_kids() {
    let v = vectors();
    let s = unhex(v["deviceSecret"].as_str().unwrap());
    let keys = DeviceKeys::from_secret(&s);
    assert_eq!(crypto::hex(&keys.auth), v["deviceAuthKey"]);
    assert_eq!(crypto::b64url(&keys.auth), v["deviceAuthKey_b64"]);
    assert_eq!(crypto::hex(&keys.enc), v["deviceEncKey"]);
    assert_eq!(keys.kid, v["deviceKid_b64"]);
    assert_eq!(keys.kid.len(), 43);
    let owner = crypto::hkdf32(&unhex(v["ownerSecret"].as_str().unwrap()), "morningissue relay owner v1");
    assert_eq!(crypto::hex(&owner), v["ownerKey"]);
    assert_eq!(crypto::b64url(&owner), v["ownerKey_b64"]);
    assert_eq!(crypto::kid(&owner), v["ownerKid_b64"]);
}

/// Seals the vector's plaintext with its key, prefix and chunk size, compares the bytes, and
/// opens it again.
fn check_package(v: &Value, name: &str, plaintext: &[u8]) {
    let case = &v[name];
    let key = key32(v, "packageKey");
    let chunk = case["chunkSize"].as_u64().unwrap() as u32;
    let context = case["context"].as_str().unwrap();
    assert_eq!(context, format!("package|{}", v["packageId"].as_str().unwrap()));
    let expected = unhex(case["envelope"].as_str().unwrap());
    let sealed = crypto::seal_with(&key, KIND_PACKAGE, &prefix(v), chunk, context, plaintext);
    assert_eq!(crypto::hex(&sealed), crypto::hex(&expected), "{name}");
    let mut out = Vec::new();
    let n = crypto::open_stream_any_chunk(&key, KIND_PACKAGE, context, &expected[..], &mut out).unwrap();
    assert_eq!((n as usize, out.as_slice()), (plaintext.len(), plaintext), "{name}");
    if chunk >= crypto::MIN_CHUNK {
        assert_eq!(crypto::open(&key, KIND_PACKAGE, context, &expected).unwrap(), plaintext);
    } else {
        // Readers refuse chunk sizes under 1 KiB; the vectors use 16 to exercise chunking.
        assert!(matches!(crypto::open(&key, KIND_PACKAGE, context, &expected), Err(EnvelopeError::Header(_))));
    }
}

#[test]
fn package_envelopes() {
    let v = vectors();
    let pt = unhex(v["plaintext"].as_str().unwrap());
    check_package(&v, "package_chunk16", &pt);
    check_package(&v, "package_chunk1MiB", &pt);
    let exact = unhex(v["package_exact_multiple"]["plaintext"].as_str().unwrap());
    assert_eq!(exact.len(), 32);
    check_package(&v, "package_exact_multiple", &exact);
    // 32 bytes in 16-byte chunks: two full chunks and an empty final one.
    assert_eq!(unhex(v["package_exact_multiple"]["envelope"].as_str().unwrap()).len(), 17 + 3 * 16 + 32);
    check_package(&v, "package_empty", b"");
    assert_eq!(unhex(v["package_empty"]["envelope"].as_str().unwrap()).len(), 17 + 16);
}

#[test]
fn wrapped_key() {
    let v = vectors();
    let w = &v["wrap"];
    let enc = key32(&v, "deviceEncKey");
    let context = format!("wrap|{}|{}", v["deviceKid_b64"].as_str().unwrap(), v["packageId"].as_str().unwrap());
    assert_eq!(w["context"], context.as_str());
    let pt = unhex(w["plaintext"].as_str().unwrap());
    assert_eq!(pt, unhex(v["packageKey"].as_str().unwrap()));
    let sealed = crypto::seal_with(&enc, KIND_WRAP, &prefix(&v), w["chunkSize"].as_u64().unwrap() as u32, &context, &pt);
    assert_eq!(crypto::b64url(&sealed), w["envelope_b64"]);
    let opened = crypto::open(&enc, KIND_WRAP, &context, &crypto::b64url_decode(w["envelope_b64"].as_str().unwrap()).unwrap()).unwrap();
    assert_eq!(opened, pt);
}

#[test]
fn changes() {
    let v = vectors();
    let c = &v["changes"];
    let enc = key32(&v, "deviceEncKey");
    let context = format!("changes|{}|{}", v["deviceKid_b64"].as_str().unwrap(), c["batch"].as_str().unwrap());
    assert_eq!(c["context"], context.as_str());
    let pt = unhex(c["plaintext"].as_str().unwrap());
    let sealed = crypto::seal_with(&enc, KIND_CHANGES, &prefix(&v), c["chunkSize"].as_u64().unwrap() as u32, &context, &pt);
    assert_eq!(crypto::hex(&sealed), c["envelope"]);
    assert_eq!(crypto::open(&enc, KIND_CHANGES, &context, &sealed).unwrap(), pt);
    let body: Value = serde_json::from_slice(&pt).unwrap();
    assert_eq!(body["events"].as_array().unwrap().len(), 2);
}

#[test]
fn signatures() {
    let v = vectors();
    let s = &v["signature"];
    let auth = key32(&v, "deviceAuthKey");
    let body = unhex(s["body"].as_str().unwrap());
    let ts = s["ts"].as_i64().unwrap();
    assert_eq!(crypto::sign(&auth, s["method"].as_str().unwrap(), s["path"].as_str().unwrap(), ts, &body), s["sig_b64"]);
    assert_eq!(crypto::authorization(v["deviceKid_b64"].as_str().unwrap(), &auth, "PUT", "/v1/changes/1-2", ts, &body), s["header"]);
    let e = &v["signature_empty_body"];
    let owner = key32(&v, "ownerKey");
    assert_eq!(crypto::sign(&owner, e["method"].as_str().unwrap(), e["path"].as_str().unwrap(), e["ts"].as_i64().unwrap(), b""), e["sig_b64"]);
}

/// A 3.5-chunk envelope at the smallest chunk size readers accept.
fn sample() -> ([u8; 32], Vec<u8>, Vec<u8>) {
    let key = [7u8; 32];
    let pt: Vec<u8> = (0..3584u32).map(|i| (i * 31 % 251) as u8).collect();
    let env = crypto::seal_with(&key, KIND_PACKAGE, &[1; 8], 1024, "package|x", &pt);
    (key, pt, env)
}

const CHUNK: usize = 1024 + 16;

#[test]
fn tampering_truncation_and_reordering_are_refused() {
    let (key, pt, env) = sample();
    assert_eq!(crypto::open(&key, KIND_PACKAGE, "package|x", &env).unwrap(), pt);
    // Every byte flipped somewhere (header, each chunk, each tag).
    for at in [0, 4, 6, 14, 17, 17 + 500, 17 + CHUNK - 1, 17 + CHUNK + 3, env.len() - 1] {
        let mut bad = env.clone();
        bad[at] ^= 1;
        assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &bad).is_err(), "flip at {at}");
    }
    // The wrong key, kind or context.
    assert!(crypto::open(&[8; 32], KIND_PACKAGE, "package|x", &env).is_err());
    assert!(matches!(crypto::open(&key, KIND_CHANGES, "package|x", &env), Err(EnvelopeError::Header(_))));
    assert!(crypto::open(&key, KIND_PACKAGE, "package|y", &env).is_err());
    // Truncated at a chunk boundary: the new last chunk was not sealed as final.
    for n in 1..=3 {
        assert_eq!(crypto::open(&key, KIND_PACKAGE, "package|x", &env[..17 + n * CHUNK]), Err(EnvelopeError::Chunk(n as u32 - 1)), "{n} chunks");
    }
    // Cut mid-chunk, or to nothing but the header.
    assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &env[..env.len() - 5]).is_err());
    assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &env[..17]).is_err());
    assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &env[..10]).is_err());
    // Two chunks swapped.
    let mut swapped = env[..17].to_vec();
    swapped.extend_from_slice(&env[17 + CHUNK..17 + 2 * CHUNK]);
    swapped.extend_from_slice(&env[17..17 + CHUNK]);
    swapped.extend_from_slice(&env[17 + 2 * CHUNK..]);
    assert_eq!(crypto::open(&key, KIND_PACKAGE, "package|x", &swapped), Err(EnvelopeError::Chunk(0)));
    // Data after the final chunk.
    let mut extra = env.clone();
    extra.extend_from_slice(&[0; 16]);
    assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &extra).is_err());
    let mut twice = env.clone();
    twice.extend_from_slice(&env[17..]);
    assert!(crypto::open(&key, KIND_PACKAGE, "package|x", &twice).is_err());
    // Chunk sizes out of range.
    for size in [16u32, 1023, (16 << 20) + 1] {
        let mut e = env.clone();
        e[13..17].copy_from_slice(&size.to_be_bytes());
        assert!(matches!(crypto::open(&key, KIND_PACKAGE, "package|x", &e), Err(EnvelopeError::Header(_))), "{size}");
    }
}

#[test]
fn a_stream_is_decrypted_in_pieces() {
    // A reader that hands out a few bytes at a time, as a socket or a file might.
    struct Trickle<'a>(&'a [u8]);
    impl std::io::Read for Trickle<'_> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            let n = buf.len().min(self.0.len()).min(37);
            buf[..n].copy_from_slice(&self.0[..n]);
            self.0 = &self.0[n..];
            Ok(n)
        }
    }
    let (key, pt, env) = sample();
    let mut out = Vec::new();
    crypto::open_stream(&key, KIND_PACKAGE, "package|x", Trickle(&env), &mut out).unwrap();
    assert_eq!(out, pt);
    // seal() picks a fresh prefix each time.
    let a = crypto::seal(&key, KIND_CHANGES, "c", b"same").unwrap();
    let b = crypto::seal(&key, KIND_CHANGES, "c", b"same").unwrap();
    assert_ne!(a, b);
    assert_eq!(crypto::open(&key, KIND_CHANGES, "c", &a).unwrap(), b"same");
}

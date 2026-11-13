//! The little cryptography server sync needs (`docs/kobo-sync.md`, "Transport"): the bearer
//! token derived from the device secret `S` in the pairing code, base64url and random bytes.

use sha2::Sha256;
use std::io::{self, Read};

/// The HKDF `info` that turns the device secret `S` into the server token.
pub const INFO_TOKEN: &str = "morningissue server token v1";

/// HKDF-SHA256 with an empty salt and 32 bytes of output.
pub fn hkdf32(secret: &[u8], info: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    hkdf::Hkdf::<Sha256>::new(None, secret).expand(info.as_bytes(), &mut out).expect("32 bytes is a valid HKDF length");
    out
}

/// The bearer token this Kobo sends: `b64url(HKDF-SHA256(S, salt empty, INFO_TOKEN, 32))`.
/// The server keeps only the hex SHA-256 of this string, never `S`.
pub fn token(secret: &[u8]) -> String {
    b64url(&hkdf32(secret, INFO_TOKEN))
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// RFC 4648 §5 without padding.
pub fn b64url(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for c in bytes.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..=c.len() {
            out.push(B64[(n >> (18 - 6 * i) & 63) as usize] as char);
        }
    }
    out
}

/// Decodes unpadded base64url (padding is tolerated); None on anything else.
pub fn b64url_decode(s: &str) -> Option<Vec<u8>> {
    let s = s.trim_end_matches('=');
    if s.len() % 4 == 1 {
        return None;
    }
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0);
    for b in s.bytes() {
        let v = B64.iter().position(|&c| c == b)? as u32;
        acc = acc << 6 | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Bytes from the kernel's random source.
pub fn random<const N: usize>() -> io::Result<[u8; N]> {
    let mut b = [0u8; N];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut b)?;
    Ok(b)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::Digest;

    #[test]
    fn base64url_round_trips() {
        for n in 0..40 {
            let b: Vec<u8> = (0..n).map(|i| (i * 37 + 11) as u8).collect();
            let e = b64url(&b);
            assert!(!e.contains('=') && !e.contains('+') && !e.contains('/'));
            assert_eq!(b64url_decode(&e).unwrap(), b);
        }
        assert_eq!(b64url(b"hello"), "aGVsbG8");
        assert!(b64url_decode("a").is_none());
        assert!(b64url_decode("ab+c").is_none());
    }

    /// The vector shared with the server (`docs/server-fixtures/kobo-token.json` once it
    /// exists): S is the bytes 0x00 to 0x1f.
    #[test]
    fn the_token_matches_the_shared_vector() {
        let s: Vec<u8> = (0u8..32).collect();
        assert_eq!(b64url(&s), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        let t = token(&s);
        assert_eq!(t, "gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc");
        // What the server stores for it.
        assert_eq!(hex(&Sha256::digest(t.as_bytes())), "fc27ecea41821dbf6c80ee0f797b450c9eb03525dcd0c4bdc67c8d9dc441a89b");
    }
}

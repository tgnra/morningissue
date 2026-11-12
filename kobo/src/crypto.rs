//! The cryptography of relay sync (`docs/relay-sync.md`, protocol relay/1): key derivation,
//! kids, the chunked MIR1 AES-256-GCM envelope and request signatures.

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::fmt;
use std::io::{self, Read, Write};

pub const KIND_PACKAGE: u8 = 1;
pub const KIND_CHANGES: u8 = 2;
pub const KIND_WRAP: u8 = 3;

/// What writers use.
pub const CHUNK_SIZE: u32 = 1 << 20;
/// What readers accept.
pub const MIN_CHUNK: u32 = 1 << 10;
pub const MAX_CHUNK: u32 = 16 << 20;

const MAGIC: &[u8; 4] = b"MIR1";
const HEADER: usize = 17;
const TAG: usize = 16;

pub const INFO_AUTH: &str = "morningissue relay auth v1";
pub const INFO_ENC: &str = "morningissue relay enc v1";

/// HKDF-SHA256 with an empty salt and 32 bytes of output.
pub fn hkdf32(secret: &[u8], info: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    hkdf::Hkdf::<Sha256>::new(None, secret).expand(info.as_bytes(), &mut out).expect("32 bytes is a valid HKDF length");
    out
}

/// `base64url(sha256(key))`, unpadded.
pub fn kid(key: &[u8]) -> String {
    b64url(&Sha256::digest(key))
}

/// The device's keys, from its secret `S`.
#[derive(Clone)]
pub struct DeviceKeys {
    pub auth: [u8; 32],
    pub enc: [u8; 32],
    pub kid: String,
}

impl DeviceKeys {
    pub fn from_secret(secret: &[u8]) -> DeviceKeys {
        let auth = hkdf32(secret, INFO_AUTH);
        DeviceKeys { auth, enc: hkdf32(secret, INFO_ENC), kid: kid(&auth) }
    }
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

#[derive(Debug, PartialEq)]
pub enum EnvelopeError {
    /// Not a MIR1 envelope, the wrong kind, or a chunk size out of range.
    Header(String),
    /// A chunk failed to authenticate: tampered, truncated, reordered or the wrong key.
    Chunk(u32),
    Io(String),
}

impl fmt::Display for EnvelopeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EnvelopeError::Header(e) => write!(f, "bad envelope header: {e}"),
            EnvelopeError::Chunk(i) => write!(f, "envelope chunk {i} failed to decrypt"),
            EnvelopeError::Io(e) => write!(f, "{e}"),
        }
    }
}

impl From<io::Error> for EnvelopeError {
    fn from(e: io::Error) -> EnvelopeError {
        EnvelopeError::Io(e.to_string())
    }
}

fn header(kind: u8, prefix: &[u8; 8], chunk: u32) -> [u8; HEADER] {
    let mut h = [0u8; HEADER];
    h[..4].copy_from_slice(MAGIC);
    h[4] = kind;
    h[5..13].copy_from_slice(prefix);
    h[13..].copy_from_slice(&chunk.to_be_bytes());
    h
}

fn nonce(prefix: &[u8], i: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[..8].copy_from_slice(prefix);
    n[8..].copy_from_slice(&i.to_be_bytes());
    n
}

fn aad(header: &[u8], i: u32, last: bool, context: &str) -> Vec<u8> {
    let mut a = Vec::with_capacity(HEADER + 5 + context.len());
    a.extend_from_slice(header);
    a.extend_from_slice(&i.to_be_bytes());
    a.push(last as u8);
    a.extend_from_slice(context.as_bytes());
    a
}

/// Encrypts `plaintext` as one envelope with this nonce prefix and chunk size.
pub fn seal_with(key: &[u8; 32], kind: u8, prefix: &[u8; 8], chunk: u32, context: &str, plaintext: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new(key.into());
    let h = header(kind, prefix, chunk);
    let size = chunk as usize;
    let count = plaintext.len() / size + 1;
    let mut out = Vec::with_capacity(HEADER + plaintext.len() + count * TAG);
    out.extend_from_slice(&h);
    for i in 0..count {
        let part = &plaintext[(i * size).min(plaintext.len())..((i + 1) * size).min(plaintext.len())];
        let mut buf = part.to_vec();
        let n = nonce(prefix, i as u32);
        cipher.encrypt_in_place(Nonce::from_slice(&n), &aad(&h, i as u32, i + 1 == count, context), &mut buf).expect("AES-GCM encryption does not fail");
        out.extend_from_slice(&buf);
    }
    out
}

/// Encrypts with a fresh random prefix and the writers' 1 MiB chunks.
pub fn seal(key: &[u8; 32], kind: u8, context: &str, plaintext: &[u8]) -> io::Result<Vec<u8>> {
    Ok(seal_with(key, kind, &random::<8>()?, CHUNK_SIZE, context, plaintext))
}

/// Reads until `buf` is full or the input ends; returns how much was read.
fn fill(r: &mut impl Read, buf: &mut [u8]) -> io::Result<usize> {
    let mut n = 0;
    while n < buf.len() {
        match r.read(&mut buf[n..]) {
            Ok(0) => break,
            Ok(k) => n += k,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(n)
}

/// Decrypts an envelope from `input` to `output` one chunk at a time, so memory stays at one
/// chunk however large the envelope. A chunk is the last one when it ends the input. Returns
/// the plaintext length. Nothing written is trustworthy unless this returns Ok: the caller
/// writes to a temporary file and keeps it only then.
pub fn open_stream(key: &[u8; 32], kind: u8, context: &str, input: impl Read, output: impl Write) -> Result<u64, EnvelopeError> {
    open_stream_within(key, kind, context, input, output, MIN_CHUNK..=MAX_CHUNK)
}

/// [`open_stream`] with any chunk size from 1 byte: only for the spec's test vectors, which
/// use 16-byte chunks that a real reader refuses.
#[doc(hidden)]
pub fn open_stream_any_chunk(key: &[u8; 32], kind: u8, context: &str, input: impl Read, output: impl Write) -> Result<u64, EnvelopeError> {
    open_stream_within(key, kind, context, input, output, 1..=MAX_CHUNK)
}

fn open_stream_within(key: &[u8; 32], kind: u8, context: &str, mut input: impl Read, mut output: impl Write, sizes: std::ops::RangeInclusive<u32>) -> Result<u64, EnvelopeError> {
    let mut h = [0u8; HEADER];
    if fill(&mut input, &mut h)? != HEADER || &h[..4] != MAGIC {
        return Err(EnvelopeError::Header("not MIR1".into()));
    }
    if h[4] != kind {
        return Err(EnvelopeError::Header(format!("kind {} where {kind} was expected", h[4])));
    }
    let chunk = u32::from_be_bytes([h[13], h[14], h[15], h[16]]);
    if !sizes.contains(&chunk) {
        return Err(EnvelopeError::Header(format!("chunk size {chunk}")));
    }
    let cipher = Aes256Gcm::new(key.into());
    let full = chunk as usize + TAG;
    // One byte more than a whole chunk, to see whether anything follows it.
    let mut buf = vec![0u8; full + 1];
    let mut have = fill(&mut input, &mut buf)?;
    let mut total = 0u64;
    let mut i: u32 = 0;
    loop {
        let last = have <= full;
        let len = have.min(full);
        if len < TAG {
            return Err(EnvelopeError::Chunk(i));
        }
        let carry = if last { None } else { Some(buf[full]) };
        buf.truncate(len);
        let n = nonce(&h[5..13], i);
        cipher.decrypt_in_place(Nonce::from_slice(&n), &aad(&h, i, last, context), &mut buf).map_err(|_| EnvelopeError::Chunk(i))?;
        output.write_all(&buf)?;
        total += buf.len() as u64;
        let Some(c) = carry else {
            output.flush()?;
            return Ok(total);
        };
        i = i.checked_add(1).ok_or(EnvelopeError::Chunk(i))?;
        buf.clear();
        buf.resize(full + 1, 0);
        buf[0] = c;
        have = 1 + fill(&mut input, &mut buf[1..])?;
    }
}

/// Decrypts a whole envelope held in memory.
pub fn open(key: &[u8; 32], kind: u8, context: &str, envelope: &[u8]) -> Result<Vec<u8>, EnvelopeError> {
    let mut out = Vec::new();
    open_stream(key, kind, context, envelope, &mut out)?;
    Ok(out)
}

/// `MIR1\nMETHOD\npath-and-query\nts\nhex(sha256(body))`, HMAC-SHA256 with `key`, base64url.
pub fn sign(key: &[u8], method: &str, path: &str, ts: i64, body: &[u8]) -> String {
    let canonical = format!("MIR1\n{method}\n{path}\n{ts}\n{}", hex(&Sha256::digest(body)));
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes any key length");
    mac.update(canonical.as_bytes());
    b64url(&mac.finalize().into_bytes())
}

/// The whole `Authorization` header value.
pub fn authorization(kid: &str, key: &[u8], method: &str, path: &str, ts: i64, body: &[u8]) -> String {
    format!("MIR1 kid={kid}, ts={ts}, sig={}", sign(key, method, path, ts, body))
}

#[cfg(test)]
mod tests {
    use super::*;

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
}

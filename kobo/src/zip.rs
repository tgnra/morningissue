//! Just enough of a zip reader for a relay package (`docs/relay-sync.md`, "Package contents"):
//! the central directory (with zip64), and entries stored or deflated. The package has already
//! been authenticated as a whole, so CRCs are not checked again.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

#[derive(Clone, Copy, Debug)]
struct Entry {
    method: u16,
    compressed: u64,
    size: u64,
    local: u64,
}

pub struct Zip {
    file: File,
    entries: HashMap<String, Entry>,
}

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("zip: {msg}"))
}

fn u16le(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}
fn u32le(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn u64le(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

impl Zip {
    pub fn open(path: &Path) -> io::Result<Zip> {
        let mut file = File::open(path)?;
        let len = file.metadata()?.len();
        // The end of central directory record: 22 bytes plus a comment of up to 64 KiB.
        let tail_len = len.min(22 + 65535 + 20);
        let mut tail = vec![0u8; tail_len as usize];
        file.seek(SeekFrom::Start(len - tail_len))?;
        file.read_exact(&mut tail)?;
        let eocd = (0..tail.len().saturating_sub(21)).rev().find(|&i| u32le(&tail, i) == 0x0605_4b50).ok_or_else(|| bad("no end of central directory"))?;
        let mut count = u16le(&tail, eocd + 10) as u64;
        let mut cd_size = u32le(&tail, eocd + 12) as u64;
        let mut cd_offset = u32le(&tail, eocd + 16) as u64;
        if (count == 0xffff || cd_size == 0xffff_ffff || cd_offset == 0xffff_ffff) && eocd >= 20 && u32le(&tail, eocd - 20) == 0x0706_4b50 {
            let at = u64le(&tail, eocd - 20 + 8);
            let mut rec = [0u8; 56];
            file.seek(SeekFrom::Start(at))?;
            file.read_exact(&mut rec)?;
            if u32le(&rec, 0) != 0x0606_4b50 {
                return Err(bad("bad zip64 record"));
            }
            count = u64le(&rec, 32);
            cd_size = u64le(&rec, 40);
            cd_offset = u64le(&rec, 48);
        }
        if cd_offset.checked_add(cd_size).is_none_or(|e| e > len) || cd_size > 64 << 20 {
            return Err(bad("central directory out of range"));
        }
        let mut cd = vec![0u8; cd_size as usize];
        file.seek(SeekFrom::Start(cd_offset))?;
        file.read_exact(&mut cd)?;
        let mut entries = HashMap::new();
        let mut p = 0usize;
        for _ in 0..count {
            if p + 46 > cd.len() || u32le(&cd, p) != 0x0201_4b50 {
                return Err(bad("bad central directory entry"));
            }
            let method = u16le(&cd, p + 10);
            let mut compressed = u32le(&cd, p + 20) as u64;
            let mut size = u32le(&cd, p + 24) as u64;
            let (name_len, extra_len, comment_len) = (u16le(&cd, p + 28) as usize, u16le(&cd, p + 30) as usize, u16le(&cd, p + 32) as usize);
            let mut local = u32le(&cd, p + 42) as u64;
            let end = p + 46 + name_len + extra_len + comment_len;
            if end > cd.len() {
                return Err(bad("central directory entry cut short"));
            }
            let name = String::from_utf8_lossy(&cd[p + 46..p + 46 + name_len]).into_owned();
            // Zip64 sizes and offset, in that order, for the fields that overflowed.
            let mut x = p + 46 + name_len;
            let extra_end = x + extra_len;
            while x + 4 <= extra_end {
                let (id, n) = (u16le(&cd, x), u16le(&cd, x + 2) as usize);
                if id == 0x0001 {
                    let mut f = x + 4;
                    for v in [&mut size, &mut compressed, &mut local] {
                        if *v == 0xffff_ffff && f + 8 <= x + 4 + n {
                            *v = u64le(&cd, f);
                            f += 8;
                        }
                    }
                }
                x += 4 + n;
            }
            entries.insert(name, Entry { method, compressed, size, local });
            p = end;
        }
        Ok(Zip { file, entries })
    }

    pub fn contains(&self, name: &str) -> bool {
        self.entries.contains_key(name)
    }

    /// An entry's contents, refused if larger than `limit`. None if there is no such entry.
    pub fn read(&mut self, name: &str, limit: u64) -> io::Result<Option<Vec<u8>>> {
        let Some(e) = self.entries.get(name).copied() else { return Ok(None) };
        if e.size > limit || e.compressed > limit.max(1 << 16) * 2 {
            return Err(bad(&format!("{name} is too large")));
        }
        let mut head = [0u8; 30];
        self.file.seek(SeekFrom::Start(e.local))?;
        self.file.read_exact(&mut head)?;
        if u32le(&head, 0) != 0x0403_4b50 {
            return Err(bad("bad local header"));
        }
        let skip = u16le(&head, 26) as i64 + u16le(&head, 28) as i64;
        self.file.seek(SeekFrom::Current(skip))?;
        let mut raw = vec![0u8; e.compressed as usize];
        self.file.read_exact(&mut raw)?;
        let data = match e.method {
            0 => raw,
            8 => miniz_oxide::inflate::decompress_to_vec_with_limit(&raw, limit as usize).map_err(|_| bad(&format!("{name} does not inflate")))?,
            m => return Err(bad(&format!("{name} uses compression method {m}"))),
        };
        if data.len() as u64 != e.size {
            return Err(bad(&format!("{name} has the wrong length")));
        }
        Ok(Some(data))
    }
}

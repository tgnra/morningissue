//! Just enough HTTP/1.1 for the phone: one request per connection, Content-Length or chunked
//! bodies, no TLS, no redirects.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Debug)]
pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Response {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

/// Bodies larger than this are refused: no bundle or stored image comes near it.
const MAX_BODY: usize = 64 << 20;

pub fn resolve(address: &str) -> io::Result<SocketAddr> {
    address.to_socket_addrs()?.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("bad address {address}")))
}

pub fn request(
    address: &str,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<&[u8]>,
    timeout: Duration,
) -> io::Result<Response> {
    let addr = resolve(address)?;
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5))?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(Duration::from_secs(30)))?;
    let _ = stream.set_nodelay(true);
    let mut head = format!("{method} {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\nUser-Agent: morningissue-kobo/{}\r\nAccept: */*\r\n", env!("CARGO_PKG_VERSION"));
    if let Some(t) = token {
        head.push_str(&format!("Authorization: Bearer {t}\r\n"));
    }
    if let Some(b) = body {
        head.push_str(&format!("Content-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\n", b.len()));
    } else if method == "POST" {
        head.push_str("Content-Length: 0\r\n");
    }
    head.push_str("\r\n");
    let mut out = head.into_bytes();
    if let Some(b) = body {
        out.extend_from_slice(b);
    }
    stream.write_all(&out)?;
    stream.flush()?;
    read_response(BufReader::new(stream), method == "HEAD")
}

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

fn read_line<R: BufRead>(r: &mut R) -> io::Result<String> {
    let mut buf = Vec::new();
    let n = r.take(16 * 1024).read_until(b'\n', &mut buf)?;
    if n == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "connection closed"));
    }
    while buf.last().is_some_and(|c| *c == b'\n' || *c == b'\r') {
        buf.pop();
    }
    String::from_utf8(buf).map_err(|_| bad("header is not UTF-8"))
}

pub fn read_response<R: BufRead>(mut r: R, head_only: bool) -> io::Result<Response> {
    let status_line = read_line(&mut r)?;
    let mut parts = status_line.splitn(3, ' ');
    let version = parts.next().unwrap_or("");
    if !version.starts_with("HTTP/1.") {
        return Err(bad("not an HTTP response"));
    }
    let status: u16 = parts.next().and_then(|s| s.parse().ok()).ok_or_else(|| bad("bad status line"))?;
    let mut headers = Vec::new();
    loop {
        let line = read_line(&mut r)?;
        if line.is_empty() {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.push((k.trim().to_string(), v.trim().to_string()));
        }
        if headers.len() > 200 {
            return Err(bad("too many headers"));
        }
    }
    let mut resp = Response { status, headers, body: Vec::new() };
    if head_only || status == 204 || status == 304 || (100..200).contains(&status) {
        return Ok(resp);
    }
    let chunked = resp.header("Transfer-Encoding").is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
    if chunked {
        loop {
            let size_line = read_line(&mut r)?;
            let size = usize::from_str_radix(size_line.split(';').next().unwrap_or("").trim(), 16).map_err(|_| bad("bad chunk size"))?;
            if size == 0 {
                // Trailers, then the blank line.
                while !read_line(&mut r).map(|l| l.is_empty()).unwrap_or(true) {}
                break;
            }
            if resp.body.len() + size > MAX_BODY {
                return Err(bad("body too large"));
            }
            let start = resp.body.len();
            resp.body.resize(start + size, 0);
            r.read_exact(&mut resp.body[start..])?;
            read_line(&mut r)?;
        }
    } else if let Some(len) = resp.header("Content-Length") {
        let len: usize = len.parse().map_err(|_| bad("bad Content-Length"))?;
        if len > MAX_BODY {
            return Err(bad("body too large"));
        }
        resp.body.resize(len, 0);
        r.read_exact(&mut resp.body)?;
    } else {
        r.take(MAX_BODY as u64).read_to_end(&mut resp.body)?;
    }
    Ok(resp)
}

/// Percent-encodes everything but unreserved characters, for a query value.
pub fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_chunked_and_length() {
        let raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nDate: x\r\n\r\n5\r\nhello\r\n6;ext\r\n world\r\n0\r\n\r\n";
        let r = read_response(&raw[..], false).unwrap();
        assert_eq!(r.body, b"hello world");
        assert_eq!(r.header("date"), Some("x"));
        let raw = b"HTTP/1.1 404 Not Found\r\nContent-Length: 3\r\n\r\nnope";
        let r = read_response(&raw[..], false).unwrap();
        assert_eq!((r.status, r.body.as_slice()), (404, &b"nop"[..]));
    }

    #[test]
    fn encodes() {
        assert_eq!(url_encode("https://a.b/c d?x=1"), "https%3A%2F%2Fa.b%2Fc%20d%3Fx%3D1");
    }
}

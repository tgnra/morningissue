//! Just enough HTTP/1.1 for the relay: one request per connection, Content-Length or chunked
//! bodies, no redirects. A relay may be `http://` or `https://` (TLS through [`crate::tls`]).

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Debug)]
pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    /// Empty when the body went to a sink.
    pub body: Vec<u8>,
}

impl Response {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

/// Bodies held in memory larger than this are refused: no bundle or stored image comes near
/// it. Streamed bodies (a relay package) are not held in memory.
const MAX_BODY: u64 = 64 << 20;
/// A streamed body larger than this is refused (the relay's own package limit is 512 MiB).
const MAX_STREAM: u64 = 2 << 30;

/// Where a relay is: `http://` or `https://`, a host, a port and an optional path prefix
/// (`https://example.org/relay` puts every path under `/relay`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Endpoint {
    pub tls: bool,
    /// A host name, an IPv4 address or a bracketed IPv6 address.
    pub host: String,
    pub port: u16,
    /// Empty, or starting with `/` and not ending with one.
    pub prefix: String,
}

impl Endpoint {
    /// Parses `http[s]://host[:port][/prefix]`.
    pub fn parse(url: &str) -> Result<Endpoint, String> {
        let url = url.trim();
        let (tls, rest) = if let Some(r) = strip_prefix_ci(url, "https://") {
            (true, r)
        } else if let Some(r) = strip_prefix_ci(url, "http://") {
            (false, r)
        } else {
            return Err("The address starts with http:// or https://.".into());
        };
        let (authority, path) = match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, ""),
        };
        if path.contains(['?', '#', ' ']) {
            return Err("The address can't have a query or spaces.".into());
        }
        let (host, port) = if let Some(end) = authority.strip_prefix('[').and_then(|a| a.find(']')) {
            let host = &authority[..end + 2];
            match &authority[end + 2..] {
                "" => (host, None),
                p => (host, Some(p.strip_prefix(':').ok_or("Bad port.")?)),
            }
        } else {
            match authority.rsplit_once(':') {
                Some((h, p)) => (h, Some(p)),
                None => (authority, None),
            }
        };
        let ok_host = !host.is_empty()
            && (host.starts_with('[') || host.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-'))
            && !host.starts_with(['.', '-'])
            && !host.ends_with('-');
        if !ok_host {
            return Err("That is not a host name or address.".into());
        }
        let port = match port {
            None => if tls { 443 } else { 80 },
            Some(p) => match p.parse::<u16>() {
                Ok(n) if n > 0 => n,
                _ => return Err("The port after ':' is a number from 1 to 65535.".into()),
            },
        };
        Ok(Endpoint { tls, host: host.to_ascii_lowercase(), port, prefix: path.trim_end_matches('/').to_string() })
    }

    /// `host` or `host:port`, as the `Host` header and the URL write it.
    pub fn authority(&self) -> String {
        if self.port == if self.tls { 443 } else { 80 } {
            self.host.clone()
        } else {
            format!("{}:{}", self.host, self.port)
        }
    }

    /// The request target for an API path: the prefix, then the path.
    pub fn target(&self, path: &str) -> String {
        format!("{}{path}", self.prefix)
    }

    pub fn url(&self) -> String {
        format!("{}://{}{}", if self.tls { "https" } else { "http" }, self.authority(), self.prefix)
    }
}

fn strip_prefix_ci<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    (s.len() >= prefix.len() && s.is_char_boundary(prefix.len()) && s[..prefix.len()].eq_ignore_ascii_case(prefix)).then(|| &s[prefix.len()..])
}

/// One request.
pub struct Call<'a> {
    pub method: &'a str,
    /// Path and query, exactly as sent.
    pub target: &'a str,
    pub headers: &'a [(&'a str, String)],
    pub body: Option<&'a [u8]>,
    pub content_type: &'a str,
}

trait Conn: Read + Write {}
impl<T: Read + Write> Conn for T {}

/// Where a streamed body goes. Told the response's status and headers before the first
/// byte, so a download can tell a `206` that continues a file from a `200` that restarts it.
pub trait Sink: Write {
    fn start(&mut self, resp: &Response) -> io::Result<()>;
}

impl Sink for Vec<u8> {
    fn start(&mut self, _: &Response) -> io::Result<()> {
        self.clear();
        Ok(())
    }
}

/// A request to a relay. With a sink, a `200` or `206` body is copied there instead of being
/// held in memory.
pub fn call(ep: &Endpoint, c: &Call, timeout: Duration, sink: Option<&mut dyn Sink>) -> io::Result<Response> {
    let host = ep.host.trim_start_matches('[').trim_end_matches(']');
    let addrs: Vec<SocketAddr> = (host, ep.port).to_socket_addrs()?.collect();
    let mut last = io::Error::new(io::ErrorKind::InvalidInput, format!("no address for {}", ep.host));
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, Duration::from_secs(10)) {
            Ok(s) => {
                let s = stream_setup(s, timeout)?;
                let conn: Box<dyn Conn> = if ep.tls { Box::new(crate::tls::connect(s, &ep.host)?) } else { Box::new(s) };
                return exchange(conn, &ep.authority(), c, sink);
            }
            Err(e) => last = e,
        }
    }
    Err(last)
}

fn stream_setup(stream: TcpStream, timeout: Duration) -> io::Result<TcpStream> {
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(Duration::from_secs(30)))?;
    let _ = stream.set_nodelay(true);
    Ok(stream)
}

fn exchange(mut stream: Box<dyn Conn>, host: &str, c: &Call, sink: Option<&mut dyn Sink>) -> io::Result<Response> {
    let mut head = format!("{} {} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\nUser-Agent: morningissue-kobo/{}\r\nAccept: */*\r\n", c.method, c.target, env!("CARGO_PKG_VERSION"));
    for (k, v) in c.headers {
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    if let Some(b) = c.body {
        head.push_str(&format!("Content-Type: {}\r\nContent-Length: {}\r\n", c.content_type, b.len()));
    } else if c.method == "POST" || c.method == "PUT" {
        head.push_str("Content-Length: 0\r\n");
    }
    head.push_str("\r\n");
    let mut out = head.into_bytes();
    if let Some(b) = c.body {
        out.extend_from_slice(b);
    }
    stream.write_all(&out)?;
    stream.flush()?;
    let mut r = BufReader::new(stream);
    let mut resp = read_head(&mut r)?;
    if c.method == "HEAD" || resp.status == 204 || resp.status == 304 || (100..200).contains(&resp.status) {
        return Ok(resp);
    }
    match sink {
        Some(w) if resp.status == 200 || resp.status == 206 => {
            w.start(&resp)?;
            read_body(&mut r, &resp, w, MAX_STREAM)?;
        }
        _ => {
            let mut body = Vec::new();
            read_body(&mut r, &resp, &mut body, MAX_BODY)?;
            resp.body = body;
        }
    }
    Ok(resp)
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

fn read_head<R: BufRead>(r: &mut R) -> io::Result<Response> {
    let status_line = read_line(r)?;
    let mut parts = status_line.splitn(3, ' ');
    let version = parts.next().unwrap_or("");
    if !version.starts_with("HTTP/1.") {
        return Err(bad("not an HTTP response"));
    }
    let status: u16 = parts.next().and_then(|s| s.parse().ok()).ok_or_else(|| bad("bad status line"))?;
    let mut headers = Vec::new();
    loop {
        let line = read_line(r)?;
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
    Ok(Response { status, headers, body: Vec::new() })
}

/// Copies exactly `n` bytes from `r` to `w`.
fn copy_exact<R: Read>(r: &mut R, w: &mut dyn Write, n: u64) -> io::Result<()> {
    let copied = io::copy(&mut r.take(n), w)?;
    if copied < n {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "connection closed mid-body"));
    }
    Ok(())
}

fn read_body<R: BufRead>(r: &mut R, resp: &Response, w: &mut dyn Write, limit: u64) -> io::Result<()> {
    let chunked = resp.header("Transfer-Encoding").is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
    if chunked {
        let mut total = 0u64;
        loop {
            let size_line = read_line(r)?;
            let size = u64::from_str_radix(size_line.split(';').next().unwrap_or("").trim(), 16).map_err(|_| bad("bad chunk size"))?;
            if size == 0 {
                // Trailers, then the blank line.
                while !read_line(r).map(|l| l.is_empty()).unwrap_or(true) {}
                return Ok(());
            }
            total += size;
            if total > limit {
                return Err(bad("body too large"));
            }
            copy_exact(r, w, size)?;
            read_line(r)?;
        }
    } else if let Some(len) = resp.header("Content-Length") {
        let len: u64 = len.parse().map_err(|_| bad("bad Content-Length"))?;
        if len > limit {
            return Err(bad("body too large"));
        }
        copy_exact(r, w, len)
    } else {
        // Until the connection closes. A TLS peer that closes without close_notify ends it too.
        match io::copy(&mut r.take(limit), w) {
            Ok(_) => Ok(()),
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => Ok(()),
            Err(e) => Err(e),
        }
    }
}

pub fn read_response<R: BufRead>(mut r: R, head_only: bool) -> io::Result<Response> {
    let mut resp = read_head(&mut r)?;
    if head_only || resp.status == 204 || resp.status == 304 || (100..200).contains(&resp.status) {
        return Ok(resp);
    }
    let mut body = Vec::new();
    read_body(&mut r, &resp, &mut body, MAX_BODY)?;
    resp.body = body;
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
        // A body cut short is an error, not a short body.
        let raw = b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort";
        assert!(read_response(&raw[..], false).is_err());
    }

    #[test]
    fn encodes() {
        assert_eq!(url_encode("https://a.b/c d?x=1"), "https%3A%2F%2Fa.b%2Fc%20d%3Fx%3D1");
    }

    #[test]
    fn endpoints() {
        let e = Endpoint::parse("https://Relay.Example.org").unwrap();
        assert_eq!(e, Endpoint { tls: true, host: "relay.example.org".into(), port: 443, prefix: String::new() });
        assert_eq!((e.authority(), e.target("/v1/package"), e.url()), ("relay.example.org".into(), "/v1/package".into(), "https://relay.example.org".into()));
        let e = Endpoint::parse("http://192.168.1.5:8080/mi/relay/").unwrap();
        assert_eq!(e, Endpoint { tls: false, host: "192.168.1.5".into(), port: 8080, prefix: "/mi/relay".into() });
        assert_eq!((e.authority(), e.target("/v1/package")), ("192.168.1.5:8080".into(), "/mi/relay/v1/package".into()));
        assert_eq!(Endpoint::parse("HTTP://h:80").unwrap().authority(), "h");
        assert_eq!(Endpoint::parse("https://[::1]:8443/x").unwrap(), Endpoint { tls: true, host: "[::1]".into(), port: 8443, prefix: "/x".into() });
        for bad in ["relay.example.org", "ftp://x", "http://", "http://a b", "http://h:0", "http://h:99999", "http://h/p?q=1", "http://-h", "http://user@h"] {
            assert!(Endpoint::parse(bad).is_err(), "{bad}");
        }
    }
}

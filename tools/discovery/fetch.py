"""A polite HTTP fetcher with an on-disk cache, for the catalogue build.

- Identifies itself (USER_AGENT), sends conditional requests (If-None-Match, If-Modified-Since)
  when it holds a copy, and does not ask again at all within `max_age_days` of the last answer,
  errors included, so a rerun the same month costs no requests.
- Reads at most `max_bytes` (after decompression, too) and gives up on a slow server after
  `deadline` seconds.
- Fetches only http and https URLs whose host resolves to public addresses, redirects included:
  a list entry cannot point the build at a machine on the local network.
- Limits concurrency per platform (the last two labels of the host), so the thousands of blogs
  on one host such as bearblog.dev are not asked for all at once.

The cache is a directory of `<sha256 of url>.json` (status, validators, final URL) and
`.body` files (gzipped; plain ones are read too). Bodies are data only; nothing here
interprets them.
"""

import concurrent.futures
import gzip
import hashlib
import ipaddress
import json
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

USER_AGENT = "MorningIssueCatalogue/1 (+https://github.com/tgnra/morningissue)"
ACCEPT_FEED = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.1"
DEFAULT_MAX_BYTES = 2 * 1024 * 1024


class FetchError(Exception):
    pass


def _check_public(url):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise FetchError("unsupported URL")
    try:
        infos = socket.getaddrinfo(parts.hostname, parts.port or (443 if parts.scheme == "https" else 80),
                                   proto=socket.IPPROTO_TCP)
    except (socket.gaierror, UnicodeError, OSError) as e:
        raise FetchError("dns: %s" % e)
    for info in infos:
        address = ipaddress.ip_address(info[4][0].split("%")[0])
        if not address.is_global:
            raise FetchError("non-public address")


class _Redirects(urllib.request.HTTPRedirectHandler):
    """Follows at most 5 redirects, each to a public address, and records their status codes."""
    max_redirections = 5

    def __init__(self):
        super().__init__()
        self.codes = []

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _check_public(newurl)
        self.codes.append(code)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def platform_of(url):
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    labels = host.split(".")
    return ".".join(labels[-2:]) if len(labels) >= 2 else host


class Fetcher:
    def __init__(self, cache_dir, max_age_days=25, timeout=15, deadline=40,
                 max_bytes=DEFAULT_MAX_BYTES, per_platform=2, offline=False):
        self.cache_dir = cache_dir
        self.max_age = max_age_days * 86400
        self.timeout = timeout
        self.deadline = deadline
        self.max_bytes = max_bytes
        self.offline = offline
        self._per_platform = per_platform
        self._platform_locks = {}
        self._lock = threading.Lock()
        os.makedirs(cache_dir, exist_ok=True)

    def _paths(self, url):
        key = hashlib.sha256(url.encode("utf-8")).hexdigest()
        sub = os.path.join(self.cache_dir, key[:2])
        return os.path.join(sub, key + ".json"), os.path.join(sub, key + ".body")

    def _semaphore(self, url):
        platform = platform_of(url)
        with self._lock:
            sem = self._platform_locks.get(platform)
            if sem is None:
                sem = self._platform_locks[platform] = threading.Semaphore(self._per_platform)
            return sem

    def cached(self, url):
        meta_path, body_path = self._paths(url)
        try:
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        except (OSError, ValueError):
            return None, None
        body = None
        if meta.get("status") == 200 and os.path.exists(body_path):
            with open(body_path, "rb") as f:
                body = f.read()
            # Stored gzipped (a fetched body never starts with the gzip magic: _decode inflates it).
            if body[:2] == b"\x1f\x8b":
                body = gzip.decompress(body)
        return meta, body

    def _store(self, url, meta, body):
        meta_path, body_path = self._paths(url)
        os.makedirs(os.path.dirname(meta_path), exist_ok=True)
        if body is not None:
            tmp = body_path + ".tmp"
            with open(tmp, "wb") as f:
                f.write(gzip.compress(body, compresslevel=6, mtime=0))
            os.replace(tmp, body_path)
        tmp = meta_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(meta, f)
        os.replace(tmp, meta_path)

    def get(self, url, accept=ACCEPT_FEED, max_age=None):
        """(meta, body): meta has status (int, 0 for a network error), error, final_url,
        permanent_url (the target when every redirect was permanent), fetched_at, from_cache."""
        max_age = self.max_age if max_age is None else max_age
        meta, body = self.cached(url)
        now = time.time()
        if meta is not None and (self.offline or now - meta.get("fetched_at", 0) < max_age):
            meta["from_cache"] = True
            return meta, body
        if self.offline:
            return {"status": 0, "error": "offline, not cached", "final_url": url, "from_cache": True}, None

        headers = {"User-Agent": USER_AGENT, "Accept": accept, "Accept-Encoding": "gzip, deflate"}
        if meta is not None and body is not None:
            if meta.get("etag"):
                headers["If-None-Match"] = meta["etag"]
            if meta.get("last_modified"):
                headers["If-Modified-Since"] = meta["last_modified"]

        with self._semaphore(url):
            result, new_body = self._fetch(url, headers)
        if result["status"] == 304 and body is not None:
            meta.update(fetched_at=result["fetched_at"], from_cache=True, revalidated=True)
            self._store(url, meta, None)
            return meta, body
        self._store(url, result, new_body)
        result["from_cache"] = False
        return result, new_body

    def _fetch(self, url, headers):
        started = time.time()
        result = {"url": url, "final_url": url, "permanent_url": None, "status": 0, "error": None,
                  "etag": None, "last_modified": None, "content_type": None, "fetched_at": started}
        try:
            _check_public(url)
            redirects = _Redirects()
            opener = urllib.request.build_opener(redirects)
            request = urllib.request.Request(url, headers=headers)
            with opener.open(request, timeout=self.timeout) as response:
                result["status"] = response.status
                result["final_url"] = response.geturl()
                result["etag"] = response.headers.get("ETag")
                result["last_modified"] = response.headers.get("Last-Modified")
                result["content_type"] = response.headers.get("Content-Type")
                encoding = (response.headers.get("Content-Encoding") or "").lower()
                raw = self._read(response, started)
            body = self._decode(raw, encoding)
            if redirects.codes and all(code in (301, 308) for code in redirects.codes):
                result["permanent_url"] = result["final_url"]
            return result, body
        except urllib.error.HTTPError as e:
            result["status"] = e.code
            result["error"] = "HTTP %d" % e.code
        except FetchError as e:
            result["error"] = str(e)
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
            result["error"] = "network: %s" % (getattr(e, "reason", None) or e)
        except Exception as e:  # malformed responses of every kind
            result["error"] = "error: %s" % type(e).__name__
        return result, None

    def _read(self, response, started):
        chunks = []
        total = 0
        while True:
            if time.time() - started > self.deadline:
                raise FetchError("too slow")
            chunk = response.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > self.max_bytes:
                raise FetchError("too large")
            chunks.append(chunk)
        return b"".join(chunks)

    def _decode(self, raw, encoding):
        if encoding in ("gzip", "x-gzip") or raw[:2] == b"\x1f\x8b":
            return self._inflate(raw, 16 + zlib.MAX_WBITS)
        if encoding == "deflate":
            try:
                return self._inflate(raw, zlib.MAX_WBITS)
            except zlib.error:
                return self._inflate(raw, -zlib.MAX_WBITS)
        return raw

    def _inflate(self, raw, wbits):
        decompressor = zlib.decompressobj(wbits)
        out = decompressor.decompress(raw, self.max_bytes + 1)
        if len(out) > self.max_bytes or decompressor.unconsumed_tail:
            raise FetchError("too large")
        return out

    def fetch_all(self, urls, workers=12, progress_every=500, log=print):
        """Fetches every URL with a thread pool; returns {url: (meta, has_body)}. Bodies stay
        in the cache (read them back with `cached`), not in memory."""
        order = _interleave(urls)
        results = {}
        done = 0
        started = time.time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(self.get, url): url for url in order}
            for future in concurrent.futures.as_completed(futures):
                url = futures.pop(future)  # drop the future, and the body it holds
                try:
                    meta, body = future.result()
                    results[url] = (meta, body is not None)
                except Exception as e:  # never lose the whole run to one URL
                    results[url] = ({"status": 0, "error": "error: %s" % type(e).__name__, "final_url": url}, False)
                done += 1
                if progress_every and done % progress_every == 0:
                    log("  fetched %d/%d (%.0f s)" % (done, len(order), time.time() - started))
        return results


def _interleave(urls):
    """Round-robin over platforms, so concurrent requests go to different servers."""
    buckets = {}
    for url in urls:
        buckets.setdefault(platform_of(url), []).append(url)
    queues = sorted(buckets.values(), key=len, reverse=True)
    out = []
    index = 0
    while queues:
        queues = [q for q in queues if index < len(q)]
        for q in queues:
            out.append(q[index])
        index += 1
    return out


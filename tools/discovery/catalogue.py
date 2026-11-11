"""Writes `catalogue.db`, the read-only SQLite file the app's discovery screen ranks from.

The schema is the contract with the app (format version 1); keep this file and
`tools/discovery/README.md` in step with the app's reader. Vectors are 256 int8 values with a
REAL scale: component i is `int8[i] * scale`.

- `meta.mean` is the mean of every feed centroid in the file, 256 little-endian float32, for
  centring: `centred(v) = normalise(v - mean)`.
- `feed.centroid` is a feed's L2-normalised centroid, NOT centred.
- `cluster.centroid` lives in the centred space: the normalised mean of its members' centred
  centroids. Compare it with centred vectors only.
"""

import os
import sqlite3

import numpy as np

FORMAT_VERSION = 1
DIMS = 256
TIERS = ("FIREHOSE", "DAILY", "WEEKLY", "RARE")  # net.tgnra.morningissue.model.CadenceTier
SOURCES = ("kagi", "plenary", "hn")

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
CREATE TABLE cluster (
  id INTEGER PRIMARY KEY,
  label TEXT NOT NULL,
  centroid BLOB NOT NULL,
  scale REAL NOT NULL
);
CREATE TABLE feed (
  id INTEGER PRIMARY KEY,
  url TEXT NOT NULL UNIQUE,
  url_key TEXT NOT NULL,
  site_url TEXT,
  host TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  icon_url TEXT,
  language TEXT,
  tier TEXT NOT NULL,
  median_interval_hours REAL,
  posts_per_week REAL NOT NULL,
  full_text_share REAL NOT NULL,
  last_post_at INTEGER NOT NULL,
  cluster_id INTEGER NOT NULL REFERENCES cluster(id),
  categories TEXT NOT NULL,
  source TEXT NOT NULL,
  quality REAL NOT NULL,
  centroid BLOB NOT NULL,
  scale REAL NOT NULL
);
CREATE INDEX feed_cluster ON feed(cluster_id);
CREATE INDEX feed_host ON feed(host);
CREATE INDEX feed_url_key ON feed(url_key);
CREATE TABLE headline (
  feed_id INTEGER NOT NULL REFERENCES feed(id),
  position INTEGER NOT NULL,
  title TEXT NOT NULL,
  url TEXT,
  published_at INTEGER,
  word_count INTEGER,
  PRIMARY KEY (feed_id, position)
);
"""


def quantise(vector):
    """(256 int8 bytes, scale) with scale = max|v| / 127 and int8 = round(v / scale)."""
    vector = np.asarray(vector, dtype=np.float32)
    if vector.shape != (DIMS,):
        raise ValueError("expected %d dimensions" % DIMS)
    peak = float(np.abs(vector).max())
    if peak == 0.0 or not np.isfinite(peak):
        raise ValueError("cannot quantise a zero or non-finite vector")
    scale = peak / 127.0
    values = np.clip(np.rint(vector / scale), -127, 127).astype(np.int8)
    return values.tobytes(), scale


def dequantise(blob, scale):
    return np.frombuffer(blob, dtype=np.int8).astype(np.float32) * np.float32(scale)


def float_blob(vector):
    return np.asarray(vector, dtype="<f4").tobytes()


def write(path, *, built_at_ms, mean, clusters, feeds, model_id=1):
    """Writes a fresh catalogue to `path`.

    clusters: [{id, label, centroid (float[256], centred space)}]
    feeds: [{url, url_key, site_url, host, title, description, icon_url, language, tier,
             median_interval_hours, posts_per_week, full_text_share, last_post_at, cluster_id,
             categories (list of str), source, quality, centroid (float[256], unit),
             headlines: [{title, url, published_at, word_count}] (newest first, at most 5)}]
    Feed ids are assigned 1.. in the order given. The output does not depend on dict ordering
    or on time, only on the arguments.
    """
    tmp = path + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    db = sqlite3.connect(tmp)
    try:
        db.execute("PRAGMA page_size = 4096")
        db.executescript(SCHEMA)
        db.execute("PRAGMA user_version = %d" % FORMAT_VERSION)
        meta = [
            ("format_version", str(FORMAT_VERSION)),
            ("built_at", str(int(built_at_ms))),
            ("model_id", str(model_id)),
            ("dims", str(DIMS)),
            ("mean", float_blob(mean)),
        ]
        db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", meta)
        for cluster in clusters:
            blob, scale = quantise(cluster["centroid"])
            db.execute("INSERT INTO cluster (id, label, centroid, scale) VALUES (?, ?, ?, ?)",
                       (int(cluster["id"]), cluster["label"], blob, scale))
        cluster_ids = {int(c["id"]) for c in clusters}
        for feed_id, feed in enumerate(feeds, start=1):
            if feed["tier"] not in TIERS:
                raise ValueError("bad tier %r" % feed["tier"])
            if feed["source"] not in SOURCES:
                raise ValueError("bad source %r" % feed["source"])
            if int(feed["cluster_id"]) not in cluster_ids:
                raise ValueError("feed in unknown cluster")
            blob, scale = quantise(feed["centroid"])
            db.execute(
                "INSERT INTO feed (id, url, url_key, site_url, host, title, description, icon_url, language, "
                "tier, median_interval_hours, posts_per_week, full_text_share, last_post_at, cluster_id, "
                "categories, source, quality, centroid, scale) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (feed_id, feed["url"], feed["url_key"], feed.get("site_url"), feed["host"], feed["title"],
                 feed.get("description"), feed.get("icon_url"), feed.get("language"), feed["tier"],
                 _round(feed.get("median_interval_hours"), 3), round(float(feed["posts_per_week"]), 3),
                 round(float(feed["full_text_share"]), 3), int(feed["last_post_at"]), int(feed["cluster_id"]),
                 "\n".join(feed.get("categories") or []), feed["source"], round(float(feed["quality"]), 4),
                 blob, scale),
            )
            for position, headline in enumerate(feed.get("headlines", [])[:5]):
                db.execute(
                    "INSERT INTO headline (feed_id, position, title, url, published_at, word_count) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (feed_id, position, headline["title"], headline.get("url"), headline.get("published_at"),
                     headline.get("word_count")),
                )
        db.commit()
        db.execute("VACUUM")
        problems = db.execute("PRAGMA integrity_check").fetchone()[0]
        if problems != "ok":
            raise RuntimeError("integrity check: " + problems)
        fk = db.execute("PRAGMA foreign_key_check").fetchall()
        if fk:
            raise RuntimeError("foreign key violations: %r" % fk[:5])
    finally:
        db.close()
    os.replace(tmp, path)


def _round(value, digits):
    return None if value is None else round(float(value), digits)


def read(path):
    """The catalogue back as Python data, for tests and reports."""
    db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    try:
        meta = dict(db.execute("SELECT key, value FROM meta"))
        clusters = [dict(id=r[0], label=r[1], centroid=dequantise(r[2], r[3]))
                    for r in db.execute("SELECT id, label, centroid, scale FROM cluster ORDER BY id")]
        columns = [d[1] for d in db.execute("PRAGMA table_info(feed)")]
        feeds = []
        for row in db.execute("SELECT * FROM feed ORDER BY id"):
            feed = dict(zip(columns, row))
            feed["vector"] = dequantise(feed["centroid"], feed["scale"])
            feeds.append(feed)
        headlines = db.execute("SELECT feed_id, position, title, url, published_at, word_count FROM headline "
                               "ORDER BY feed_id, position").fetchall()
        user_version = db.execute("PRAGMA user_version").fetchone()[0]
    finally:
        db.close()
    return {"meta": meta, "clusters": clusters, "feeds": feeds, "headlines": headlines,
            "user_version": user_version}

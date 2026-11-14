#!/usr/bin/env python3
"""Builds the feed-discovery catalogue the app ships: app/src/main/assets/discovery/catalogue.db.

Usage (NixOS; anywhere else any Python 3.11+ with numpy and defusedxml will do):

    nix-shell -p 'python3.withPackages (p: [ p.numpy p.defusedxml ])' --run \\
      'python3 -I tools/discovery/build_catalogue.py build'

Subcommands:

    build     fetch the source lists and every feed on them, filter, embed, cluster, select
              the seed and write the catalogue (the default)
    fixture   write the small deterministic catalogue the app's unit tests read
              (app/src/test/resources/discovery/catalogue-fixture.db)
    parity    write the inputs and Python vectors the app's CatalogueParityTest checks
              against the Kotlin embedder (app/src/test/resources/discovery/parity.json)

See README.md in this directory for the rules, the file format and how to rerun cheaply.
Downloaded documents are untrusted data: they are parsed with defusedxml and the standard
HTML tokenizer, never executed, and stay in the cache directory (gitignored).
"""

import argparse
import collections
import concurrent.futures
import hashlib
import json
import math
import os
import re
import statistics
import sys
import time
import tomllib
import urllib.parse

sys.dont_write_bytecode = True  # no __pycache__ in the repo
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np  # noqa: E402

import catalogue  # noqa: E402
import embedder as text_rules  # noqa: E402
import feedparse  # noqa: E402
import fetch  # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
ASSET = os.path.join(REPO, "app", "src", "main", "assets", "embedding", "potion-base-8m.bin")
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "discovery", "catalogue.db")
FIXTURE_OUT = os.path.join(REPO, "app", "src", "test", "resources", "discovery", "catalogue-fixture.db")
PARITY_OUT = os.path.join(REPO, "app", "src", "test", "resources", "discovery", "parity.json")
DEFAULT_CACHE = os.path.join(HERE, ".cache")

DAY_MS = 86_400_000
HOUR_MS = 3_600_000

# Keep rules: see README.md.
MIN_POSTS = 5
ACTIVE_DAYS = 90
STALE_DAYS = 45
CENTROID_POSTS = 20
MIN_VECTORS = 3
SPAM_POSTS_PER_DAY = 20
SPAM_MEDIAN_WORDS = 150
HEADLINES = 3
MAX_CATEGORIES = 8
DESCRIPTION_CHARS = 280
TITLE_CHARS = 160

# engine/RankingConfig.kt defaults, used by engine/Cadence.kt.
CADENCE_SAMPLE_SIZE = 30
CADENCE_WINDOW_DAYS = 90
CADENCE_MIN_SAMPLE = 5
FIREHOSE_POSTS_PER_DAY = 8.0
DAILY_POSTS_PER_DAY = 1.0
WEEKLY_POSTS_PER_WEEK = 1.0

SOURCE_PREFERENCE = ("plenary", "kagi", "hn")


# --- Small rules ports ----------------------------------------------------------------------------

_JAVA_HOST = re.compile(r"^[A-Za-z0-9.\-]+$")


def normalize_url(url):
    """`FeedUrls.normalize`."""
    trimmed = url.strip()
    try:
        parts = urllib.parse.urlsplit(trimmed if "://" in trimmed else "https://" + trimmed)
        host = parts.hostname
        port = parts.port
    except ValueError:
        host = None
        port = None
    if not host or not _JAVA_HOST.match(host) or " " in trimmed:
        return trimmed.lower().rstrip("/")
    port_text = ":%d" % port if port not in (None, -1, 80, 443) else ""
    path = parts.path.rstrip("/")
    query = "?" + parts.query if parts.query else ""
    host = host.lower()
    if host.startswith("www."):
        host = host[4:]
    return host + port_text + path + query


def percentile(sorted_values, fraction):
    rank = fraction * (len(sorted_values) - 1)
    low = int(rank)
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (rank - low)


def tier_for(median_hours):
    """`Cadence.tierFor`."""
    if median_hours <= 0.0:
        return "FIREHOSE"
    per_day = 24.0 / median_hours
    per_week = 168.0 / median_hours
    if per_day > FIREHOSE_POSTS_PER_DAY:
        return "FIREHOSE"
    if per_day >= DAILY_POSTS_PER_DAY:
        return "DAILY"
    if per_week >= WEEKLY_POSTS_PER_WEEK:
        return "WEEKLY"
    return "RARE"


def cadence(publish_ms, now_ms):
    """`Cadence.compute`: (median interval hours or None, tier, the sample, newest first)."""
    newest_first = sorted(set(publish_ms), reverse=True)
    window_start = now_ms - CADENCE_WINDOW_DAYS * DAY_MS
    in_window = sum(1 for t in newest_first if t >= window_start)
    sample = newest_first[: max(CADENCE_SAMPLE_SIZE, in_window)]
    if len(sample) < CADENCE_MIN_SAMPLE:
        return None, "WEEKLY", sample
    intervals = sorted((newer - older) / HOUR_MS for newer, older in zip(sample, sample[1:]))
    median = percentile(intervals, 0.5)
    return median, tier_for(median), sample


# --- Public suffixes ----------------------------------------------------------------------------------

class Suffixes:
    """Registrable domains from the Public Suffix List (ICANN and private sections)."""

    def __init__(self, text, extra=()):
        self.rules = set()
        self.wildcards = set()
        self.exceptions = set()
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            rule = line.split()[0].lower()
            try:
                rule = rule.encode("idna").decode("ascii") if not rule.isascii() else rule
            except UnicodeError:
                continue
            if rule.startswith("!"):
                self.exceptions.add(rule[1:])
            elif rule.startswith("*."):
                self.wildcards.add(rule[2:])
            else:
                self.rules.add(rule)
        self.rules.update(s.lower() for s in extra)

    def registrable(self, host):
        host = host.lower().rstrip(".")
        labels = host.split(".")
        if len(labels) < 2:
            return host
        # Longest matching public suffix.
        suffix_len = 1
        for i in range(len(labels)):
            candidate = ".".join(labels[i:])
            if candidate in self.exceptions:
                suffix_len = len(labels) - i - 1
                break
            if candidate in self.rules:
                suffix_len = len(labels) - i
                break
            if i + 1 < len(labels) and ".".join(labels[i + 1:]) in self.wildcards:
                suffix_len = len(labels) - i
                break
        if suffix_len >= len(labels):
            return host
        return ".".join(labels[-(suffix_len + 1):])


# --- Blocklist and language -------------------------------------------------------------------------

def load_blocklist(path):
    sections = collections.defaultdict(list)
    current = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("[") and line.endswith("]"):
                current = line[1:-1]
                continue
            if current:
                sections[current].append(line.lower())
    return Blocklist(sections)


class Blocklist:
    def __init__(self, sections):
        self.hosts = set(sections.get("hosts", []))
        self.url_contains = sections.get("url-contains", [])
        self.adult = _phrase_regex(sections.get("adult", []))
        self.comic = _phrase_regex(sections.get("comic", []))

    def host_blocked(self, host):
        host = (host or "").lower()
        if host.startswith("www."):
            host = host[4:]
        labels = host.split(".")
        return any(".".join(labels[i:]) in self.hosts for i in range(len(labels)))

    def url_blocked(self, url):
        low = url.lower()
        if any(part in low for part in self.url_contains):
            return "comments_feed"
        host = urllib.parse.urlsplit(url).hostname or ""
        if self.host_blocked(host):
            return "blocklisted"
        return None


def _phrase_regex(phrases):
    if not phrases:
        return None
    alternatives = sorted({re.escape(p) for p in phrases}, key=len, reverse=True)
    return re.compile(r"(?<!\w)(?:" + "|".join(alternatives) + r")(?!\w)", re.IGNORECASE)


STOPWORDS = {
    "en": "the and of to a in is that for it with as was on be are this by at from or have not but an they which you we his her their has were been more one all would there will can if about so what when who out up no do just my your our its also than them into some these could only other new how like".split(),
    "de": "der die und in den von zu das mit sich des auf für ist im dem nicht ein eine als auch es an werden aus er hat dass sie nach wird bei einer um am sind noch wie einem über einen so zum war haben nur oder aber vor zur bis mehr durch man".split(),
    "fr": "le la les de des et à en un une du que qui est pour dans pas au sur par plus ne se ce il elle sont avec ou mais nous vous leur été cette comme aussi bien fait tout être".split(),
    "es": "el la los las de del y en un una que es por para con no se su al lo como más pero sus le ya o fue este ha sí porque esta son entre cuando muy sin sobre también me hasta hay donde quien desde todo nos durante".split(),
    "it": "il lo la i gli le di del della e è in un una che per con non si da al alla sono come anche più ma ha nel nella questo questa ci essere".split(),
    "nl": "de het een en van in is dat op te zijn met voor niet aan er ook als maar om door bij nog wel dan naar uit worden heeft deze die was".split(),
    "pt": "o a os as de do da dos das e em um uma que é para com não se por mais como mas foi ao ele ela seu sua isso esta este são também já".split(),
    "sv": "och i att det som en på är av för med till den har de inte om ett men var jag så vi kan ska efter när".split(),
    "da": "og i at det som en på er af for med til den har de ikke om et men var jeg så vi kan skal efter når".split(),
    "pl": "i w na z do się nie to że jest o jak co po a od za ale przez tak dla czy już".split(),
    "id": "dan yang di ini itu dengan untuk dari dalam tidak ke akan pada juga ada saya kami mereka atau bisa".split(),
    "tr": "ve bir bu da de için ile çok daha ne gibi olarak ama kadar sonra var olan".split(),
    "tl": "ang ng sa na mga at ay si ni kay para hindi ito siya mo ko ako ka naman lang din rin nang kung pero".split(),
}
STOPWORDS = {lang: set(words) for lang, words in STOPWORDS.items()}
_WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)?", re.UNICODE)


def language_scores(texts):
    words = []
    for text in texts:
        words.extend(w.lower() for w in _WORD.findall(text))
    if not words:
        return 0, {}
    counts = {lang: sum(1 for w in words if w in stop) for lang, stop in STOPWORDS.items()}
    return len(words), {lang: c / len(words) for lang, c in counts.items()}


def english_verdict(declared, texts):
    """(is English, language code to store). <language> decides, unless the text plainly
    disagrees; without one, English function words must be common and the most common."""
    total, scores = language_scores(texts)
    en = scores.get("en", 0.0)
    best_other = max((s for lang, s in scores.items() if lang != "en"), default=0.0)
    lang = (declared or "").strip().lower().replace("_", "-")
    if lang in ("english", "eng"):
        lang = "en"
    if lang:
        if not (lang == "en" or lang.startswith("en-")):
            return False, lang
        # Declared English (often a CMS default): overruled only by clear evidence.
        if total >= 80 and ((best_other > 0.08 and best_other > en) or en < 0.05):
            return False, lang
        return True, lang
    if total < 30:
        return en >= 0.08 and en >= best_other, "en"
    return (en >= 0.10 and en > best_other), "en"


# --- Analysing one feed (runs in worker processes) ---------------------------------------------

_worker = {}


def _init_worker(cache_dir, blocklist_path, now_ms):
    _worker["fetcher"] = fetch.Fetcher(cache_dir, offline=True)
    _worker["blocklist"] = load_blocklist(blocklist_path)
    _worker["embedder"] = text_rules.StaticEmbedder(ASSET)
    _worker["now"] = now_ms


def _analyse_job(url):
    fetcher = _worker["fetcher"]
    meta, body = fetcher.cached(url)
    try:
        return url, analyse(url, meta, body, _worker["now"], _worker["blocklist"], _worker["embedder"])
    except Exception as e:  # one broken document must never stop the build
        return url, {"drop": "parse_error", "detail": "%s: %s" % (type(e).__name__, e)}


def fetch_reason(meta):
    if meta is None:
        return "not_fetched"
    error = meta.get("error") or ""
    status = meta.get("status") or 0
    if "too large" in error:
        return "too_large"
    if status in (404, 410):
        return "gone"
    if status >= 400:
        return "http_error"
    return "network_error"


def _clean_text(text, limit):
    text = text_rules.squash(feedparse.html_plain_text(text or "")) if text else ""
    if not text:
        return None
    return feedparse.excerpt(text, limit)


_GENERIC_CATEGORIES = {
    "uncategorized", "uncategorised", "general", "blog", "blogs", "blogging", "post", "posts", "news",
    "misc", "miscellaneous", "other", "random", "notes", "note", "updates", "update", "personal",
    "featured", "home", "articles", "article", "main", "default", "all", "stories", "top stories",
    "latest", "english", "en", "life", "thoughts", "journal", "diary", "weeknotes", "links", "link",
    "til", "untagged", "none", "podcast", "podcasts", "episode", "episodes", "video", "videos",
}


def clean_category(raw):
    text = text_rules.squash(feedparse.html_plain_text(raw or ""))
    if not text or len(text) > 40 or len(text) < 2:
        return None
    if re.fullmatch(r"[\d\W_]+", text):
        return None
    if "/" in text and " " not in text:  # paths, URLs and taxonomy ids
        text = text.rstrip("/").rsplit("/", 1)[-1]
        if not text:
            return None
    return text


def feed_categories(feed_cats, posts):
    """Up to MAX_CATEGORIES: the channel's own, then post categories used at least twice."""
    out = []
    seen = set()
    for raw in feed_cats:
        cat = clean_category(raw)
        if cat and cat.lower() not in seen and cat.lower() not in _GENERIC_CATEGORIES:
            seen.add(cat.lower())
            out.append(cat)
    counts = collections.Counter()
    spelling = {}
    for post in posts:
        distinct = set()
        for raw in post["categories"]:
            cat = clean_category(raw)
            if cat:
                key = cat.lower()
                distinct.add(key)
                spelling.setdefault(key, collections.Counter())[cat] += 1
        counts.update(distinct)
    for key, n in counts.most_common():
        if len(out) >= MAX_CATEGORIES or n < 2:
            break
        if key in seen or key in _GENERIC_CATEGORIES:
            continue
        seen.add(key)
        out.append(spelling[key].most_common(1)[0][0])
    return out[:MAX_CATEGORIES]


def analyse(url, meta, body, now_ms, blocklist, model):
    """Everything the catalogue keeps about one feed, or {"drop": reason}."""
    if body is None:
        return {"drop": fetch_reason(meta)}
    final_url = (meta or {}).get("permanent_url") or url
    blocked = blocklist.url_blocked(final_url)
    if blocked:
        return {"drop": blocked}
    try:
        feed = feedparse.parse_document(body, (meta or {}).get("final_url") or url)
    except feedparse.FeedParseError as e:
        return {"drop": "not_a_feed", "detail": str(e)[:200]}
    posts = feedparse.finish_items(feed, now_ms)

    if feed["adult"] or any(p["adult"] for p in posts):
        return {"drop": "adult"}
    if len(posts) < MIN_POSTS:
        return {"drop": "too_few_posts"}
    dated = [p for p in posts if p["published"] is not None]
    if not dated:
        return {"drop": "undated"}
    newest = max(p["published"] for p in dated)
    if now_ms - newest > ACTIVE_DAYS * DAY_MS:
        return {"drop": "inactive"}

    # Script and language, on what the app would embed.
    texts = [text_rules.embeddable_text(p["title"], p["summary"], p["body"]) for p in posts]
    non_latin = sum(1 for t in texts if text_rules.is_mostly_non_latin(t))
    if non_latin * 2 > len(texts):
        return {"drop": "non_latin"}
    sample_texts = [t for t in texts if not text_rules.is_mostly_non_latin(t)][:30]
    english, language = english_verdict(feed["language"], sample_texts)
    if not english:
        return {"drop": "not_english"}

    # Excluded kinds: YouTube, webcomics, adult.
    site_host = urllib.parse.urlsplit(feed["site_url"] or "").hostname or ""
    if blocklist.host_blocked(site_host):
        return {"drop": "blocklisted"}
    links = [urllib.parse.urlsplit(p["url"]).hostname or "" for p in posts if p["url"]]
    video = sum(1 for h in links if blocklist.host_blocked(h) and ("youtu" in h or "vimeo" in h))
    if links and video * 2 > len(links):
        return {"drop": "youtube"}
    feed_words = " \n ".join(filter(None, [feed["title"], feed["description"]] + feed["categories"]))
    post_cats = [" \n ".join(p["categories"]) for p in posts]
    post_titles = [p["title"] for p in posts]
    if blocklist.adult and (
        blocklist.adult.search(feed_words)
        or _share(blocklist.adult, post_cats) >= 0.2
        or _count(blocklist.adult, post_titles) >= max(3, len(posts) // 4)
    ):
        return {"drop": "adult"}
    if blocklist.comic and (
        blocklist.comic.search(feed_words)
        or _share(blocklist.comic, post_cats) >= 0.3
        or _share(blocklist.comic, post_titles) >= 0.3
    ):
        return {"drop": "comic"}

    # Cadence, as the app computes it after a sync (undated posts take the fetch time).
    fetched_ms = int(((meta or {}).get("fetched_at") or now_ms / 1000) * 1000)
    times = [p["published"] if p["published"] is not None else min(fetched_ms, now_ms) for p in posts]
    median_hours, tier, sample = cadence(times, now_ms)
    posts_per_week = rate_per_week(sample, now_ms)
    words = [p["words"] for p in posts]
    median_words = statistics.median(words)
    posts_per_day = 24.0 / median_hours if median_hours and median_hours > 0 else (
        float("inf") if median_hours == 0 else 0.0)
    # Spam-like: decided by the caller, which knows whether a person curated the feed.
    spam_like = posts_per_day > SPAM_POSTS_PER_DAY and median_words < SPAM_MEDIAN_WORDS

    # Centroid of the newest posts' vectors, exactly as the app would store them.
    by_date = sorted(posts, key=lambda p: (p["published"] is not None, p["published"] or 0), reverse=True)
    vectors = []
    for post in by_date[:CENTROID_POSTS]:
        vector = text_rules.stored_vector(model, post["title"], post["summary"], post["body"])
        if vector is not None:
            vectors.append(vector)
    if len(vectors) < MIN_VECTORS:
        return {"drop": "no_vectors"}
    centroid = text_rules.normalise(np.mean(np.stack(vectors), axis=0).astype(np.float32))
    if centroid is None:
        return {"drop": "no_vectors"}

    title = feed["title"] and text_rules.squash(feed["title"])
    if title and title.lower().startswith(("comments for", "comments on")):
        return {"drop": "comments_feed"}
    categories = feed_categories(feed["categories"], posts)
    # The categories the catalogue would show (post categories used twice or more) count too.
    if blocklist.adult and blocklist.adult.search(" \n ".join(categories)):
        return {"drop": "adult"}
    headlines = [{
        "title": (feedparse.excerpt(p["title"], TITLE_CHARS) or p["title"]),
        "url": p["url"],
        "published_at": p["published"],
        "word_count": p["words"],
    } for p in by_date[:HEADLINES]]

    return {
        "url": final_url,
        "fetched_url": url,
        "site_url": feed["site_url"],
        "title": feedparse.excerpt(title, TITLE_CHARS) if title else None,
        "description": clean_description(_clean_text(feed["description"], DESCRIPTION_CHARS), title),
        "icon_url": feed["icon_url"],
        "language": language or None,
        "tier": tier,
        "median_interval_hours": median_hours,
        "posts_per_week": posts_per_week,
        "full_text_share": sum(1 for p in posts if p["complete"]) / len(posts),
        "last_post_at": newest,
        "median_words": median_words,
        "posts": len(posts),
        "categories": categories,
        "headlines": headlines,
        "centroid": centroid.tolist(),
        "vectors": len(vectors),
        "spam_like": spam_like,
    }


def rate_per_week(sample, now_ms):
    """Posts a week from the cadence sample (newest first): 168 over the mean gap between posts,
    the longest tenth of gaps left out so one stray old date cannot drag it down. Below the
    cadence minimum, the posts in the 90-day window spread over the window."""
    if len(sample) < CADENCE_MIN_SAMPLE:
        recent = sum(1 for t in sample if now_ms - t <= ACTIVE_DAYS * DAY_MS)
        return recent / (ACTIVE_DAYS / 7.0)
    gaps = sorted((newer - older) / HOUR_MS for newer, older in zip(sample, sample[1:]))
    kept = gaps[: max(1, int(math.ceil(len(gaps) * 0.9)))]
    mean_gap = sum(kept) / len(kept)
    return 168.0 / mean_gap if mean_gap > 0 else float(len(sample))


_BOILERPLATE_DESCRIPTION = re.compile(
    r"^(recent content (on|in) |just another wordpress site|just another .* site$|my (wordpress )?blog$|"
    r"a blog$|blog$|rss feed|the latest (posts|articles) from |posts$|default description)", re.I)


def clean_description(description, title):
    if not description:
        return None
    if _BOILERPLATE_DESCRIPTION.search(description):
        return None
    if title and description.strip(" .").lower() == title.strip(" .").lower():
        return None
    return description


def _share(regex, texts):
    return _count(regex, texts) / len(texts) if texts else 0.0


def _count(regex, texts):
    return sum(1 for t in texts if t and regex.search(t))


# --- Source lists ---------------------------------------------------------------------------------

def read_lines_list(body):
    urls = []
    for line in body.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if re.match(r"^https?://\S+$", line):
            urls.append(line)
    return urls


def read_opml(body):
    """[(xmlUrl, title, description, category)] from an OPML document (defusedxml)."""
    root = feedparse.parse_xml(body)
    out = []

    def walk(el, category):
        for child in el:
            if child.tag.lower() != "outline":
                continue
            xml_url = child.get("xmlUrl") or child.get("xmlurl")
            if xml_url:
                out.append((xml_url.strip(), child.get("title") or child.get("text"),
                            child.get("description"), category))
            else:
                walk(child, child.get("title") or child.get("text") or category)

    for el in root:
        if el.tag.lower() == "body":
            walk(el, None)
    return out


def load_candidates(sources, fetcher, log):
    """{url_key: candidate} merged over every source list, before any feed is fetched."""
    candidates = {}

    def add(url, source, title=None, description=None, category=None):
        url = url.strip()
        if not re.match(r"^https?://", url, re.I):
            return
        key = normalize_url(url)
        cand = candidates.get(key)
        if cand is None:
            cand = candidates[key] = {"url": url, "sources": set(), "titles": [], "descriptions": [],
                                      "plenary_categories": []}
        cand["sources"].add(source)
        if title:
            cand["titles"].append(title)
        if description:
            cand["descriptions"].append(description)
        if category and category not in cand["plenary_categories"]:
            cand["plenary_categories"].append(category)

    counts = collections.Counter()
    kagi = sources["kagi"]
    for list_url in kagi["lists"]:
        meta, body = fetcher.get(list_url, accept="text/plain, */*", max_age=86400)
        if body is None:
            sys.exit("could not fetch %s: %s" % (list_url, meta.get("error")))
        for url in read_lines_list(body):
            add(url, "kagi")
            counts["kagi"] += 1
    plenary = sources["plenary"]
    for name in plenary["files"]:
        list_url = plenary["base"] + urllib.parse.quote(name)
        meta, body = fetcher.get(list_url, accept="text/xml, */*", max_age=86400)
        if body is None:
            sys.exit("could not fetch %s: %s" % (list_url, meta.get("error")))
        file_category = os.path.splitext(os.path.basename(name))[0].replace("UI - UX", "UI/UX")
        if name.startswith("countries/"):
            file_category = plenary.get("country_category", "News")
        for url, title, description, _ in read_opml(body):
            add(url, "plenary", title, description, file_category)
            counts["plenary"] += 1
    hn = sources["hn"]
    for list_url in hn["lists"]:
        meta, body = fetcher.get(list_url, accept="text/xml, */*", max_age=86400)
        if body is None:
            sys.exit("could not fetch %s: %s" % (list_url, meta.get("error")))
        for url, title, description, _ in read_opml(body):
            add(url, "hn", None, description)
            counts["hn"] += 1
    log("source lists: kagi %d, plenary %d, hn %d entries; %d distinct feed addresses" % (
        counts["kagi"], counts["plenary"], counts["hn"], len(candidates)))
    return candidates, counts


# --- Dedupe, quality, clusters, selection ---------------------------------------------------------

_SECONDARY = re.compile(
    r"(/comments?\b|/category/|/categories/|/tag/|/tags/|/topic/|/topics/|/label/|/labels/|/author/|"
    r"[?&](cat|tag|category)=|/series/|/section/)", re.I)


def host_and_key(record, suffixes, proxies, path_tenants):
    feed_host = (urllib.parse.urlsplit(record["url"]).hostname or "").lower()
    site_host = (urllib.parse.urlsplit(record.get("site_url") or "").hostname or "").lower()
    host_source = site_host if (feed_host in proxies and site_host) else feed_host
    if host_source.startswith("www."):
        host_source = host_source[4:]
    host = suffixes.registrable(host_source) if host_source else feed_host
    key = host
    if host in path_tenants:
        base = record.get("site_url") if feed_host in proxies or record.get("site_url") else record["url"]
        segment = (urllib.parse.urlsplit(base or "").path.strip("/").split("/") or [""])[0]
        if segment in ("feed", "rss", "") and record["url"]:
            parts = urllib.parse.urlsplit(record["url"]).path.strip("/").split("/")
            segment = next((p for p in parts if p not in ("feed", "rss", "atom")), "")
        key = host + "/" + segment.lower()
    return host, key


def main_feed_score(record):
    url = record["url"]
    score = 0.0
    if _SECONDARY.search(urllib.parse.urlsplit(url).path + "?" + urllib.parse.urlsplit(url).query):
        score -= 10
    score += len(record["sources"])
    score += min(record["posts"], 50) / 50.0
    score -= len(url) / 1000.0
    return score


def quality_of(record, now_ms):
    """0..1: full posts over excerpts, fresh over stale, substance, a description."""
    text = 0.8 + 0.2 * record["full_text_share"]
    fresh = 1.0 if now_ms - record["last_post_at"] <= STALE_DAYS * DAY_MS else 0.7
    substance = 0.75 + 0.25 * min(1.0, record["median_words"] / 200.0)
    described = 1.0 if record.get("description") else 0.95
    return text * fresh * substance * described


def selection_score(record):
    score = record["quality"]
    if "plenary" in record["sources"]:
        score *= 1.1
    if len(record["sources"]) > 1:
        score *= 1.05
    if record["tier"] == "FIREHOSE":
        score *= 0.85
    # A stable tie-break that does not depend on input order.
    score += int(hashlib.sha256(record["url"].encode()).hexdigest()[:6], 16) / 16 ** 6 * 1e-4
    return score


def unit_rows(matrix):
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


def spherical_kmeans(points, k, seed=7, iterations=60, restarts=3):
    """Cosine k-means with k-means++ seeding; returns (centroids, labels). Deterministic."""
    best = None
    for r in range(restarts):
        rng = np.random.default_rng(seed + r)
        n = len(points)
        centres = [points[rng.integers(n)]]
        closest = 1.0 - points @ centres[0]
        for _ in range(1, k):
            weights = np.maximum(closest, 0) ** 2
            total = weights.sum()
            index = rng.choice(n, p=weights / total) if total > 0 else rng.integers(n)
            centres.append(points[index])
            closest = np.minimum(closest, 1.0 - points @ points[index])
        centres = np.stack(centres)
        labels = None
        for _ in range(iterations):
            sims = points @ centres.T
            new_labels = sims.argmax(axis=1)
            if labels is not None and np.array_equal(new_labels, labels):
                break
            labels = new_labels
            for c in range(k):
                members = points[labels == c]
                if len(members):
                    centres[c] = members.mean(axis=0)
                else:  # re-seed an empty cluster at the worst-served point
                    centres[c] = points[sims.max(axis=1).argmin()]
            centres = unit_rows(centres)
        objective = float((points * centres[labels]).sum())
        if best is None or objective > best[0]:
            best = (objective, centres.copy(), labels.copy())
    return best[1], best[2]


def cap_clusters(sizes, share):
    """Places per cluster: every feed, except that no cluster may hold more than `share` of the
    catalogue that results. A safeguard against one dominant cluster, not a size target."""
    sizes = np.asarray(sizes, dtype=int)
    cap = int(sizes.max())
    while True:
        kept = np.minimum(sizes, cap)
        allowed = int(math.floor(share * kept.sum()))
        if cap <= allowed:
            return kept
        cap = allowed


_LABEL_STOP = _GENERIC_CATEGORIES | {
    "and", "the", "of", "in", "a", "to", "for", "on", "with", "my", "new", "how", "why", "what", "is",
    "rss", "feed", "series", "review", "reviews", "essay", "essays", "opinion", "writing", "article",
    "weekly", "monthly", "daily", "2023", "2024", "2025", "2026", "newsletter", "self", "meta",
    "projects", "project", "work", "tools", "tutorial", "tutorials", "guides", "guide", "howto",
    "tips", "resources", "events", "community", "announcements", "release", "releases",
}


def _label_key(cat):
    """A category folded for counting: lower case, hyphens as spaces, leading symbols and
    emoji gone, a plural s dropped ("Reviews" and "review" are one)."""
    low = re.sub(r"^[^\w]+|[^\w+#]+$", "", cat.lower().replace("-", " ").replace("_", " ")).strip()
    low = re.sub(r"\s+", " ", low)
    if len(low) > 4 and low.endswith("s") and not low.endswith(("ss", "ics", "ous")):
        low = low[:-1]
    return _LABEL_SYNONYMS.get(low, low)


_LABEL_SYNONYMS = {
    "artificial intelligence": "ai", "ml": "machine learning", "gamedev": "game development",
    "game dev": "game development", "cybersecurity": "security", "cyber security": "security",
    "infosec": "security", "math": "mathematics", "maths": "mathematics", "photo": "photography", "photos": "photography", "film": "movie",
    "films": "movie", "tv": "television", "dnd": "d&d", "tech": "technology", "llms": "llm",
    "selfhosting": "self hosting", "self hosted": "self hosting", "recipe": "food", "cooking": "food",
    "book review": "book", "reading": "book", "poem": "poetry", "economy": "economics",
    "christianity": "faith", "religion": "faith", "video game": "gaming", "game": "gaming",
}


def label_clusters(assignments, records, k):
    """A label per cluster from its feeds' categories: those both common in the cluster (at
    least 5 feeds and 3% of it, or 3 and 1% when no category is that common) and
    over-represented there, scored share x log(1 + lift). A plenaryapp category is preferred when the cluster's own categories carry it too. The second
    term joins the first ("Linux & Homelab") when it scores at least half as well. Unique."""
    sizes = collections.Counter(assignments)
    counts = [collections.Counter() for _ in range(k)]
    total = collections.Counter()
    spelling = collections.defaultdict(collections.Counter)
    plenary = [collections.Counter() for _ in range(k)]
    for c, record in zip(assignments, records):
        keys = set()
        for cat in record["categories"]:
            key = _label_key(cat)
            if not key or key in _LABEL_STOP or not re.search(r"[a-z]", key):
                continue
            keys.add(key)
            spelling[key][re.sub(r"^[^\w]+", "", cat).strip().lower()] += 1
        for key in keys:
            counts[c][key] += 1
            total[key] += 1
        for cat in record["plenary_categories"]:
            plenary[c][_label_key(cat)] += 1
            spelling[_label_key(cat)][cat] += 1000  # plenaryapp's spelling wins
    n_all = max(1, len(records))

    def scored(c, floor=5, share_floor=0.03):
        size = max(1, sizes[c])
        out = []
        for key, n in counts[c].items():
            if n < max(floor, share_floor * size):
                continue
            share = n / size
            lift = share / (total[key] / n_all)
            score = share * math.log(1 + lift)
            if plenary[c][key] >= 2:
                score *= 1.3
            out.append((score, key))
        out.sort(key=lambda o: (-o[0], o[1]))
        return out

    def show(key):
        return _title_case(spelling[key].most_common(1)[0][0]) if spelling[key] else _title_case(key)

    def words_of(key):
        return set(key.split())

    proposals = []
    for c in range(k):
        strict = scored(c)
        # Rarer categories (3 feeds, 1%) only after the common ones: as a second term, or as
        # alternatives once the common labels are taken by other clusters.
        terms = strict + [t for t in scored(c, 3, 0.01) if t not in strict]
        options = []
        if terms:
            first_score, first = terms[0]
            seconds = [(s, t) for s, t in terms[1:6] if not (words_of(t) & words_of(first))]
            if seconds and seconds[0][0] >= 0.5 * first_score:
                options.append((first_score, "%s & %s" % (show(first), show(seconds[0][1]))))
            options.append((first_score, show(first)))
            for i, (s1, t1) in enumerate(terms[:8]):
                for s2, t2 in terms[i + 1:10]:
                    if not (words_of(t1) & words_of(t2)):
                        options.append(((s1 + s2) / 3, "%s & %s" % (show(t1), show(t2))))
        options.append((0.0, "Miscellany"))
        proposals.append(options)

    labels = [None] * k
    used = set()
    for c in sorted(range(k), key=lambda c: -proposals[c][0][0]):
        chosen = next((label for _, label in proposals[c] if label.lower() not in used), None)
        if chosen is None:
            chosen = "%s %d" % (proposals[c][0][1], c + 1)
        used.add(chosen.lower())
        labels[c] = chosen
    return labels


def _title_case(text):
    special = {"ai": "AI", "ios": "iOS", "ui": "UI", "ux": "UX", "diy": "DIY", "llm": "LLM", "llms": "LLMs",
               "uk": "UK", "us": "US", "nyc": "NYC", "css": "CSS", "html": "HTML", "js": "JS", "api": "API",
               "macos": "macOS", "nixos": "NixOS", "f1": "F1", "nfl": "NFL", "nba": "NBA", "mlb": "MLB",
               "nhl": "NHL", "emacs": "Emacs", "vim": "Vim", "linux": "Linux", "rpg": "RPG", "ttrpg": "TTRPG",
               "d&d": "D&D", "dnd": "D&D", "sql": "SQL",
               "javascript": "JavaScript", "typescript": "TypeScript", "osr": "OSR", "css": "CSS",
               "postgresql": "PostgreSQL", "github": "GitHub", "diy": "DIY", "esp32": "ESP32", "lgbtq": "LGBTQ", "covid": "COVID", "3d": "3D"}
    if text.lower() in special:
        return special[text.lower()]
    if text != text.lower():
        return text
    return " ".join(special.get(w, w[:1].upper() + w[1:]) for w in text.split(" "))


# --- The build ------------------------------------------------------------------------------------------

def build(args):
    log = print
    started = time.time()
    with open(os.path.join(HERE, "sources.toml"), "rb") as f:
        sources = tomllib.load(f)
    blocklist_path = os.path.join(HERE, "blocklist.txt")
    blocklist = load_blocklist(blocklist_path)
    fetcher = fetch.Fetcher(args.cache_dir, max_age_days=args.max_age_days, offline=args.offline)
    now_ms = int(args.now * 1000) if args.now else int(time.time() * 1000)

    psl_meta, psl_body = fetcher.get(sources["public_suffix"]["url"], accept="text/plain", max_age=30 * 86400)
    if psl_body is None:
        sys.exit("could not fetch the public suffix list: %s" % psl_meta.get("error"))
    suffixes = Suffixes(psl_body.decode("utf-8", "replace"), sources["public_suffix"]["extra_suffixes"])
    proxies = set(sources["public_suffix"]["feed_proxies"])
    path_tenants = set(sources["public_suffix"]["path_tenants"])

    candidates, list_counts = load_candidates(sources, fetcher, log)
    drops = collections.Counter()
    to_fetch = []
    for key, cand in candidates.items():
        reason = blocklist.url_blocked(cand["url"])
        if reason:
            drops[reason] += 1
            continue
        to_fetch.append(cand["url"])
    to_fetch.sort()
    if args.limit:
        rng = np.random.default_rng(1)
        keep = set(rng.choice(len(to_fetch), size=min(args.limit, len(to_fetch)), replace=False).tolist())
        plenary_urls = {c["url"] for c in candidates.values() if "plenary" in c["sources"]}
        to_fetch = [u for i, u in enumerate(to_fetch) if i in keep or u in plenary_urls]
    log("fetching %d feeds with %d workers (cache %s)" % (len(to_fetch), args.fetch_workers, args.cache_dir))
    results = fetcher.fetch_all(to_fetch, workers=args.fetch_workers)
    from_cache = sum(1 for meta, _ in results.values() if meta.get("from_cache"))
    ok = sum(1 for _, has_body in results.values() if has_body)
    log("fetched: %d answered with a body (%d from cache) in %.0f s" % (ok, from_cache, time.time() - started))

    log("analysing with %d processes" % args.parse_workers)
    records = []
    details = collections.defaultdict(list)
    with concurrent.futures.ProcessPoolExecutor(
            max_workers=args.parse_workers, initializer=_init_worker,
            initargs=(args.cache_dir, blocklist_path, now_ms)) as pool:
        for n, (url, result) in enumerate(pool.map(_analyse_job, to_fetch, chunksize=16), start=1):
            if "drop" in result:
                drops[result["drop"]] += 1
                if len(details[result["drop"]]) < 5:
                    details[result["drop"]].append((url, result.get("detail")))
            else:
                cand = candidates[normalize_url(url)]
                # The volume-and-thin-posts rule is for open lists; plenaryapp's news outlets
                # (BBC, Guardian) post that way and were chosen by hand.
                if result["spam_like"] and "plenary" not in cand["sources"]:
                    drops["spam"] += 1
                    if len(details["spam"]) < 5:
                        details["spam"].append((url, None))
                    continue
                result["sources"] = sorted(cand["sources"])
                result["plenary_categories"] = list(cand["plenary_categories"])
                if not result["title"]:
                    result["title"] = (cand["titles"] or [None])[0] or urllib.parse.urlsplit(url).hostname
                if not result["description"] and cand["descriptions"]:
                    result["description"] = clean_description(
                        _clean_text(cand["descriptions"][0], DESCRIPTION_CHARS), result["title"])
                records.append(result)
            if n % 2000 == 0:
                log("  analysed %d/%d" % (n, len(to_fetch)))
    log("kept after filters: %d" % len(records))

    # Deduplicate: normalised URL (a moved feed can meet its new address), then site.
    by_key = {}
    for record in records:
        record["url_key"] = normalize_url(record["url"])
        prior = by_key.get(record["url_key"])
        if prior is None:
            by_key[record["url_key"]] = record
        else:
            drops["duplicate_url"] += 1
            prior["sources"] = sorted(set(prior["sources"]) | set(record["sources"]))
            for cat in record["plenary_categories"]:
                if cat not in prior["plenary_categories"]:
                    prior["plenary_categories"].append(cat)
    groups = collections.defaultdict(list)
    for record in by_key.values():
        record["host"], dedupe_key = host_and_key(record, suffixes, proxies, path_tenants)
        groups[dedupe_key].append(record)
    pool_records = []
    for key in sorted(groups):
        group = groups[key]
        group.sort(key=lambda r: (-main_feed_score(r), r["url"]))
        main = group[0]
        for other in group[1:]:
            drops["duplicate_site"] += 1
            main["sources"] = sorted(set(main["sources"]) | set(other["sources"]))
            for cat in other["plenary_categories"]:
                if cat not in main["plenary_categories"]:
                    main["plenary_categories"].append(cat)
        if _SECONDARY.search(urllib.parse.urlsplit(main["url"]).path) and "/comment" in main["url"].lower():
            drops["comments_feed"] += 1
            continue
        pool_records.append(main)
    for record in pool_records:
        record["quality"] = quality_of(record, now_ms)
        record["categories"] = _merge_categories(record["plenary_categories"], record["categories"])
    log("after deduplication: %d feeds" % len(pool_records))
    if len(pool_records) < args.clusters * 3:
        sys.exit("too few feeds to cluster")

    # Cluster the whole pool on centred centroids, select the seed across clusters, then refit
    # the clusters and the mean on the seed alone so the file is self-consistent.
    vectors = np.array([r["centroid"] for r in pool_records], dtype=np.float32)
    pool_mean = vectors.mean(axis=0)
    centres, labels = spherical_kmeans(unit_rows(vectors - pool_mean), args.clusters)
    sizes = np.bincount(labels, minlength=args.clusters)
    quota = cap_clusters(sizes, args.max_cluster_share)
    drops["cluster_cap"] = int(sizes.sum() - quota.sum())
    seed = []
    for c in range(args.clusters):
        members = [r for r, l in zip(pool_records, labels) if l == c]
        members.sort(key=lambda r: (-selection_score(r), r["url"]))
        seed.extend(members[: quota[c]])
    seed_vectors = np.array([r["centroid"] for r in seed], dtype=np.float32)
    mean = seed_vectors.mean(axis=0).astype(np.float32)
    seed_centred = unit_rows(seed_vectors - mean)
    index_of = {id(r): i for i, r in enumerate(pool_records)}
    seed_labels = np.array([labels[index_of[id(r)]] for r in seed])
    centres = unit_rows(np.stack([
        seed_centred[seed_labels == c].mean(axis=0) if (seed_labels == c).any() else centres[c]
        for c in range(args.clusters)]))
    for _ in range(10):  # a few k-means steps on the seed, from the pool's clusters
        new_labels = (seed_centred @ centres.T).argmax(axis=1)
        if np.array_equal(new_labels, seed_labels):
            break
        seed_labels = new_labels
        centres = unit_rows(np.stack([
            seed_centred[seed_labels == c].mean(axis=0) if (seed_labels == c).any() else centres[c]
            for c in range(args.clusters)]))
    used = sorted(set(seed_labels.tolist()))
    renumber = {old: new for new, old in enumerate(used, start=1)}

    # Labels from the whole pool, assigned to the final clusters.
    pool_assign = (unit_rows(vectors - mean) @ centres.T).argmax(axis=1)
    label_text = label_clusters(pool_assign.tolist(), pool_records, args.clusters)

    clusters = [{"id": renumber[c], "label": label_text[c], "centroid": centres[c]} for c in used]
    feeds = []
    for record, c in sorted(zip(seed, seed_labels), key=lambda x: (renumber[x[1]], -x[0]["quality"], x[0]["url"])):
        source = next(s for s in SOURCE_PREFERENCE if s in record["sources"])
        feeds.append({
            "url": record["url"], "url_key": record["url_key"], "site_url": record["site_url"],
            "host": record["host"], "title": record["title"], "description": record["description"],
            "icon_url": record["icon_url"], "language": record["language"], "tier": record["tier"],
            "median_interval_hours": record["median_interval_hours"],
            "posts_per_week": record["posts_per_week"], "full_text_share": record["full_text_share"],
            "last_post_at": record["last_post_at"], "cluster_id": renumber[c],
            "categories": record["categories"], "source": source, "quality": record["quality"],
            "centroid": np.asarray(record["centroid"], dtype=np.float32), "headlines": record["headlines"],
        })
    catalogue.write(args.out, built_at_ms=now_ms, mean=mean, clusters=clusters, feeds=feeds)

    size = os.path.getsize(args.out)
    cluster_counts = collections.Counter(f["cluster_id"] for f in feeds)
    report = {
        "built_at": now_ms,
        "list_entries": dict(list_counts),
        "distinct_candidates": len(candidates),
        "fetched": len(to_fetch),
        "answered_with_body": ok,
        "kept_after_filters": len(records),
        "after_dedupe": len(pool_records),
        "seed": len(feeds),
        "dropped": dict(drops.most_common()),
        "asset_bytes": size,
        "clusters": [{"id": c["id"], "label": c["label"], "seed": cluster_counts[c["id"]],
                      "pool": int((pool_assign == used[c["id"] - 1]).sum())} for c in clusters],
        "tiers": dict(collections.Counter(f["tier"] for f in feeds)),
        "sources": dict(collections.Counter(f["source"] for f in feeds)),
        "drop_examples": {k: v for k, v in details.items()},
        "seconds": round(time.time() - started),
    }
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1, ensure_ascii=False)
    log("dropped by reason: " + ", ".join("%s %d" % kv for kv in drops.most_common()))
    log("clusters:")
    for c in report["clusters"]:
        log("  %3d  %-40s seed %4d  pool %5d" % (c["id"], c["label"], c["seed"], c["pool"]))
    log("tiers %s; sources %s" % (report["tiers"], report["sources"]))
    log("wrote %s: %d feeds, %d clusters, %d bytes in %.0f s" % (
        os.path.relpath(args.out, REPO), len(feeds), len(clusters), size, time.time() - started))


def _merge_categories(plenary_categories, categories):
    out = []
    seen = set()
    for cat in list(plenary_categories) + list(categories):
        if cat.lower() not in seen:
            seen.add(cat.lower())
            out.append(cat)
    return out[:MAX_CATEGORIES]


# --- Fixture for the app's unit tests -----------------------------------------------------------------

FIXTURE_TOPICS = [
    ("Cooking", ["Food", "Recipes"], [
        "A weeknight dal with spinach and lemon", "How to keep a sourdough starter alive",
        "Braised short ribs for a cold Sunday", "The case for cast iron pans",
        "Five ways with leftover rice", "Making fresh pasta without a machine",
        "A simple tomato sauce, three ways", "Why you should salt your eggplant",
    ]),
    ("Programming", ["Programming", "Rust"], [
        "Understanding lifetimes in Rust with pictures", "Why our build got twice as fast",
        "A gentle introduction to property-based testing", "SQLite as an application file format",
        "Writing a tiny interpreter in Kotlin", "Profiling allocation-heavy code on the JVM",
        "What I learned maintaining an open source library", "Async Rust without the tears",
    ]),
    ("Astronomy", ["Space", "Science"], [
        "Jupiter's moons through a small telescope", "Tonight's meteor shower and where to look",
        "How astronomers measure the distance to galaxies", "A new exoplanet in the habitable zone",
        "Photographing the Milky Way from a dark site", "The life cycle of a massive star",
        "What the James Webb telescope saw this month", "Spotting the space station overhead",
    ]),
    ("Gardening", ["Gardening", "Nature"], [
        "Planting garlic in autumn for a summer harvest", "How to start a compost heap",
        "The best tomatoes for a small greenhouse", "Pruning roses without fear",
        "Growing herbs on a windowsill", "Attracting bees with native wildflowers",
        "Saving seeds from your own vegetables", "Mulch, and why your soil wants it",
    ]),
]


def fixture(args):
    """About 30 feeds in 4 clusters, deterministic: the same bytes on every run."""
    model = text_rules.StaticEmbedder(ASSET)
    built_at = 1_767_225_600_000  # 2026-01-01T00:00:00Z
    tiers = ["FIREHOSE", "DAILY", "WEEKLY", "RARE"]
    sources = ["kagi", "plenary", "hn"]
    records = []
    counts = [8, 8, 7, 7]
    for t, (topic, cats, headlines) in enumerate(FIXTURE_TOPICS):
        for i in range(counts[t]):
            chosen = [headlines[(i + j) % len(headlines)] for j in range(5)]
            vectors = [text_rules.stored_vector(model, h, None, None) for h in chosen]
            centroid = text_rules.normalise(np.mean(np.stack(vectors), axis=0).astype(np.float32))
            slug = "%s-%d" % (topic.lower(), i + 1)
            host = "%s.example" % slug
            tier = tiers[(t + i) % 4]
            median = {"FIREHOSE": 1.5, "DAILY": 12.0, "WEEKLY": 72.0, "RARE": 400.0}[tier]
            last = built_at - (i * 3 + t) * DAY_MS
            records.append({
                "url": "https://%s/feed.xml" % host,
                "url_key": "%s/feed.xml" % host,
                "site_url": None if i == 2 else "https://%s/" % host,
                "host": host,
                "title": ("Café %s %d" % (topic, i + 1)) if i == 1 else "%s Notes %d" % (topic, i + 1),
                "description": None if i == 3 else "A fixture feed about %s." % topic.lower(),
                "icon_url": None if i % 2 else "https://%s/icon.png" % host,
                "language": None if i == 4 else "en",
                "tier": tier,
                "median_interval_hours": None if i == 5 else median,
                "posts_per_week": round(168.0 / median, 3),
                "full_text_share": round((i % 5) / 4, 3),
                "last_post_at": last,
                "cluster_id": t + 1,
                "categories": [] if i == 6 else cats,
                "source": sources[(t + i) % 3],
                "quality": round(0.5 + 0.06 * ((i * 7 + t) % 9), 4),
                "centroid": centroid,
                "headlines": [{"title": h, "url": None if j == 4 else "https://%s/posts/%d" % (host, j + 1),
                               "published_at": None if j == 4 else last - j * int(median * HOUR_MS),
                               "word_count": None if j == 4 else 300 + 50 * j}
                              for j, h in enumerate(chosen[:HEADLINES])],
            })
    vectors = np.array([r["centroid"] for r in records], dtype=np.float32)
    mean = vectors.mean(axis=0).astype(np.float32)
    centred = unit_rows(vectors - mean)
    clusters = []
    for t, (topic, _, _) in enumerate(FIXTURE_TOPICS):
        members = centred[[r["cluster_id"] == t + 1 for r in records]]
        clusters.append({"id": t + 1, "label": topic, "centroid": unit_rows(members.mean(axis=0, keepdims=True))[0]})
    catalogue.write(args.out, built_at_ms=built_at, mean=mean, clusters=clusters, feeds=records)
    print("wrote %s: %d feeds, %d clusters, %d bytes" % (
        os.path.relpath(args.out, REPO), len(records), len(clusters), os.path.getsize(args.out)))


# --- Parity cases for the Kotlin test -----------------------------------------------------------------

def parity_cases():
    """100 (title, summary, body) inputs: plain prose, the embedding tests' sentences, the
    repo's docs, and the awkward cases (accents, CJK, other scripts, punctuation, emoji, long
    words, control characters, summaries that repeat the body, texts past the limits)."""
    with open(os.path.join(REPO, "tools", "embedding", "cases.json"), encoding="utf-8") as f:
        cases = json.load(f)
    cases_text = list(cases["reference"]) + list(cases["corpus"]) + list(cases["tokenizer"])
    paragraphs = []
    for name in ("docs/how-it-works.md", "README.md", "todo.md"):
        path = os.path.join(REPO, name)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                paragraphs += [p.strip() for p in f.read().split("\n\n") if len(p.strip()) > 80]
    out = []
    for i, text in enumerate(cases_text[:40]):
        out.append({"title": text, "summary": None, "body": None})
    for i, para in enumerate(paragraphs[:25]):
        first, _, rest = para.partition(". ")
        out.append({"title": first[:120], "summary": rest[:400] if i % 3 == 0 else None,
                    "body": para if i % 2 == 0 else rest})
    long_body = " ".join(["International negotiations continued throughout September, with delegates"
                          " from forty countries meeting in Geneva."] * 40)
    edge = [
        ("Café crème brûlée: naïve façade", "Élan vital, déjà vu, über-cool coöperation.", None),
        ("Tokyo 東京 travel notes", "東京は日本の首都です。", "We spent three days in 東京 and loved it."),
        ("Новости дня", "Сегодня в Москве прошёл дождь.", "Подробности позже."),
        ("Ελληνικά και English mixed", "Some Greek: καλημέρα κόσμε.", "Mostly English text here, with a word or two."),
        ("Emoji only 🎉🎉🎉", "🙂🙃", None),
        ("", "", ""),
        ("Supercalifragilisticexpialidocious" * 4, "A very long word.", None),
        ("Tabs\tand\nnewlines\r\nand non-breaking spaces", "Zero​width and soft­hyphen", None),
        ("Punctuation!!! (lots) of it -- yes; really: [brackets] {braces} <angles>", "“Quotes” ‘single’ «guillemets»", None),
        ("Summary repeats the body", "The opening sentence of the body is here and continues…",
         "The opening sentence of the body is here and continues with more words after the cut."),
        ("A summary that differs", "A standfirst written separately.", "The body starts elsewhere entirely."),
        ("Long body past the word limit", None, long_body),
        ("Long title " + "word " * 300, None, None),
        ("Numbers 2026, 3.14 and 1,000,000", "Version 2.3.1 released on 2026-11-11.", None),
        ("Control\x00characters\x07inside�", "Private use  here", None),
        ("Arabic العربية title", "مرحبا بالعالم", "نص عربي"),
        ("Hindi हिन्दी mixed with English", "This post is mostly English with one हिन्दी word.", None),
        ("Mixed Turkish İstanbul ılık", "Şişli'de güzel bir gün.", None),
        ("German Straße und Übermaß", "Die Größe der Städte wächst.", None),
        ("Korean 한국어 notes", "한국어 텍스트", "More text in English after the Korean."),
        ("C++ and C# and F#: a comparison", "Rust's borrow-checker vs. Go's GC.", None),
        ("URLs https://example.com/path?query=1 and emails a@b.co", None, None),
        ("ALL CAPS HEADLINE ABOUT ELECTIONS", "lower case summary about polls", None),
        ("Ellipsis at the end...", "Read more…", "Short body. Continue reading"),
        ("Fullwidth ＬＡＴＩＮ letters", "ｆｕｌｌｗｉｄｔｈ", None),
        ("Ligatures ﬁ ﬂ and ß", "Œuvre and æsthetic", None),
        ("Hyphenated well-known state-of-the-art", "e-mail co-operate re-enter", None),
        ("Math symbols ∑ ∫ √ π ≈ ≠", "x² + y² = z²", None),
        ("Japanese kana ひらがな カタカナ", "これは日本語の文章です", None),
        ("Thai ภาษาไทย", "สวัสดีครับ", None),
        ("Hebrew עברית", "שלום עולם", "English after Hebrew, which is longer than the Hebrew part by far."),
        ("Combining marks: é à ö", "ño", None),
        ("Very long summary", "word " * 500, None),
        ("Smart apostrophes don’t break it’s tokenization", "Rock ’n’ roll", None),
        ("Title only", None, None),
    ]
    for title, summary, body in edge:
        out.append({"title": title, "summary": summary, "body": body})
    return out[:100]


def parity(args):
    model = text_rules.StaticEmbedder(ASSET)
    cases = parity_cases()
    records = []
    for case in cases:
        text = text_rules.embeddable_text(case["title"], case["summary"], case["body"])
        non_latin = text_rules.is_mostly_non_latin(text)
        vector = None if non_latin else model.embed(text)
        records.append({
            "title": case["title"], "summary": case["summary"], "body": case["body"],
            "text": text, "nonLatin": non_latin,
            "vector": None if vector is None else [round(float(v), 6) for v in vector],
        })
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"modelId": text_rules.MODEL_ID, "cases": records}, f, ensure_ascii=True, separators=(",", ":"))
        f.write("\n")
    print("wrote %s: %d cases, %d with a vector" % (
        os.path.relpath(args.out, REPO), len(records), sum(r["vector"] is not None for r in records)))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command")
    b = sub.add_parser("build", help="build the seed catalogue")
    b.add_argument("--out", default=DEFAULT_OUT)
    b.add_argument("--cache-dir", default=DEFAULT_CACHE, help="raw responses; keep it gitignored")
    b.add_argument("--max-age-days", type=float, default=25, help="reuse cached answers younger than this")
    b.add_argument("--offline", action="store_true", help="use only the cache")
    b.add_argument("--fetch-workers", type=int, default=12)
    b.add_argument("--parse-workers", type=int, default=4)
    b.add_argument("--clusters", type=int, default=56)
    b.add_argument("--max-cluster-share", type=float, default=0.08,
                   help="no cluster holds more than this share of the catalogue (a safeguard)")
    b.add_argument("--limit", type=int, default=0, help="fetch a random subset of this many (plus every plenary feed)")
    b.add_argument("--now", type=float, default=0, help="build time as epoch seconds (for reproducing a build)")
    b.add_argument("--report", help="write a JSON report here")
    fx = sub.add_parser("fixture", help="write the unit-test fixture catalogue")
    fx.add_argument("--out", default=FIXTURE_OUT)
    pa = sub.add_parser("parity", help="write the Kotlin parity cases")
    pa.add_argument("--out", default=PARITY_OUT)
    args = parser.parse_args()
    if args.command in (None, "build"):
        if args.command is None:
            args = parser.parse_args(["build"] + sys.argv[1:])
        build(args)
    elif args.command == "fixture":
        fixture(args)
    elif args.command == "parity":
        parity(args)


if __name__ == "__main__":
    main()

"""Reads RSS and Atom documents into plain Python data, following the app's own reading rules
(`sync/XmlFeedParser.kt`, `sync/FeedParser.kt`, `sync/ParsedFeed.kt`) where they decide what is
embedded, counted or shown: titles, summaries, body text, word counts, dates and the excerpt rule.

Every document is untrusted. XML goes through defusedxml (no entity expansion, no external
references); HTML is read with the standard library's tokenizer and never rendered or executed.
"""

import datetime as dt
import email.utils
import hashlib
import html
import html.entities
import html.parser
import re

from defusedxml import ElementTree as SafeET
from defusedxml.common import DefusedXmlException
from xml.etree.ElementTree import ParseError

import embedder as text_rules

NS_ATOM = "http://www.w3.org/2005/Atom"
NS_ATOM03 = "http://purl.org/atom/ns#"
NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
NS_DC = "http://purl.org/dc/elements/1.1/"
NS_MEDIA = ("http://search.yahoo.com/mrss/", "http://search.yahoo.com/mrss", "http://search.yahoo.com/rss")
NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
NS_RSS1 = ("http://purl.org/rss/1.0/", "http://my.netscape.com/rdf/simple/0.9/")
NS_XML = "http://www.w3.org/XML/1998/namespace"
XML_LANG = "{%s}lang" % NS_XML

EXCERPT_CHARS = 400
TITLE_FALLBACK_CHARS = 80
COMPLETE_WORDS = 250

# DefaultFullTextFetcher.EXCERPT_ENDING
EXCERPT_ENDING = re.compile(
    r"(…|\.\.\.|\[…]|\[\.\.\.]|\[&#8230;]|read more\W*|continue reading\W*|read the rest.*|appeared first on .*)$",
    re.IGNORECASE,
)


class FeedParseError(Exception):
    pass


# --- HTML to text -------------------------------------------------------------------------------

_SKIP = {"script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "math",
         "form", "button", "select", "textarea", "head", "title", "video", "audio", "canvas"}
_BLOCK = {"p", "div", "br", "hr", "li", "ul", "ol", "dl", "dt", "dd", "h1", "h2", "h3", "h4", "h5",
          "h6", "blockquote", "pre", "table", "tr", "td", "th", "thead", "tbody", "tfoot", "section",
          "article", "header", "footer", "aside", "nav", "figure", "figcaption", "main", "address",
          "details", "summary", "caption", "center"}
_VOID = {"br", "hr", "img", "input", "meta", "link", "area", "base", "col", "source", "track", "wbr", "param"}
_JSOUP_SPACE = re.compile(r"[ \t\n\f\r ]+")


class _TextExtractor(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.blocks = []
        self.current = []
        self.skip_depth = 0

    def _break(self):
        line = _JSOUP_SPACE.sub(" ", "".join(self.current)).strip()
        if line:
            self.blocks.append(line)
        self.current = []

    def handle_starttag(self, tag, attrs):
        tag = tag.rpartition(":")[2]  # XHTML content serialised with a prefix (ns0:p)
        if tag in _SKIP and tag not in _VOID:
            self.skip_depth += 1
            return
        if tag in _BLOCK:
            self._break()

    def handle_startendtag(self, tag, attrs):
        if tag.rpartition(":")[2] in _BLOCK:
            self._break()

    def handle_endtag(self, tag):
        tag = tag.rpartition(":")[2]
        if tag in _SKIP and tag not in _VOID:
            self.skip_depth = max(0, self.skip_depth - 1)
            return
        if tag in _BLOCK:
            self._break()

    def handle_data(self, data):
        if not self.skip_depth:
            self.current.append(data)

    def close(self):
        super().close()
        self._break()


def html_blocks(fragment):
    """The text of an HTML fragment as blocks: `ContentBlocks.plainText(HtmlBlocks.convert(...))`
    approximately, one line per block."""
    if not fragment or not fragment.strip():
        return []
    parser = _TextExtractor()
    try:
        parser.feed(fragment)
        parser.close()
    except Exception:  # malformed markup: keep what was read
        parser._break()
    return parser.blocks


def html_plain_text(fragment):
    """`HtmlBlocks.plainText`: the text of a fragment with whitespace collapsed."""
    if fragment is None:
        return ""
    if "<" in fragment or "&" in fragment:
        return " ".join(html_blocks(fragment)).strip()
    return text_rules.JAVA_SPACES.sub(" ", fragment.strip())


def text_to_html(text):
    """`HtmlBlocks.textToHtml`, enough for its text to read the same."""
    paragraphs = [p.strip() for p in re.split(r"\r?\n[ \t]*\r?\n", text.strip()) if p.strip()]
    return "".join("<p>" + html.escape(p).replace("\n", "<br>").replace("\r", "") + "</p>" for p in paragraphs)


def excerpt(text, limit=EXCERPT_CHARS):
    """`Texts.excerpt`."""
    clean = text_rules.squash(text)
    if not clean:
        return None
    if len(clean) <= limit:
        return clean
    cut = clean.rfind(" ", 0, limit + 1)
    if cut <= limit // 2:
        cut = limit
    return clean[:cut].rstrip(",;: .") + "…"


def word_count(plain):
    """`ContentBlocks.wordCount`: runs between ASCII whitespace."""
    return sum(1 for w in text_rules.JAVA_SPACES.split(plain) if w)


# --- Dates --------------------------------------------------------------------------------------

_ISO = re.compile(r"^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,]\d+)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$", re.I)


def parse_date(text):
    """Epoch milliseconds for an RFC 822 or ISO 8601 date, or None."""
    if not text:
        return None
    text = text.strip()
    if not text:
        return None
    m = _ISO.match(text)
    if m:
        y, mo, d, h, mi, s, tz = m.groups()
        try:
            when = dt.datetime(int(y), int(mo), int(d), int(h or 0), int(mi or 0), int(s or 0))
        except ValueError:
            return None
        offset = 0
        if tz and tz.upper() != "Z":
            sign = -1 if tz[0] == "-" else 1
            digits = tz[1:].replace(":", "")
            offset = sign * (int(digits[:2]) * 60 + int(digits[2:4]))
        when = when.replace(tzinfo=dt.timezone(dt.timedelta(minutes=offset)))
        return int(when.timestamp() * 1000)
    try:
        when = email.utils.parsedate_to_datetime(text)
    except (TypeError, ValueError, IndexError, OverflowError):
        return None
    if when is None:
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=dt.timezone.utc)
    try:
        return int(when.timestamp() * 1000)
    except (OverflowError, OSError, ValueError):
        return None


# --- XML ------------------------------------------------------------------------------------------

_XML_PREDEFINED = {"amp", "lt", "gt", "quot", "apos"}
_ENTITY = re.compile(r"&([A-Za-z][A-Za-z0-9]{1,31});")
_BARE_AMP = re.compile(r"&(?!#\d{1,7};|#x[0-9A-Fa-f]{1,6};|[A-Za-z][A-Za-z0-9]{1,31};)")
_BAD_CHARS = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")
_DECL = re.compile(r"^\s*<\?xml[^>]*\?>", re.S)
_DECL_ENCODING = re.compile(rb"^\s*<\?xml[^>]*encoding=[\"']([A-Za-z0-9._-]+)[\"']")


def _decode(raw):
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8", "replace")
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16", "replace")
    m = _DECL_ENCODING.match(raw[:200])
    encoding = m.group(1).decode("ascii") if m else "utf-8"
    try:
        return raw.decode(encoding, "replace")
    except LookupError:
        return raw.decode("utf-8", "replace")


def _repair(raw):
    """A second chance for a document that is not well-formed: HTML entities made numeric, bare
    ampersands escaped and characters XML forbids removed. Many real feeds need this."""
    text = _decode(raw)
    text = _DECL.sub("", text, count=1)

    def entity(m):
        name = m.group(1)
        if name in _XML_PREDEFINED:
            return m.group(0)
        cp = html.entities.name2codepoint.get(name)
        return "&#%d;" % cp if cp else ""

    text = _ENTITY.sub(entity, text)
    text = _BARE_AMP.sub("&amp;", text)
    text = _BAD_CHARS.sub("", text)
    # Junk after the document element (a stray script, a second copy): cut at the root's end.
    for end in ("</rss>", "</feed>", "</rdf:RDF>"):
        at = text.rfind(end)
        if at >= 0:
            text = text[: at + len(end)]
            break
    return text.encode("utf-8")


def parse_xml(raw):
    for attempt in (raw, None):
        data = attempt if attempt is not None else _repair(raw)
        try:
            return SafeET.fromstring(data, forbid_dtd=False, forbid_entities=True, forbid_external=True)
        except DefusedXmlException as e:
            raise FeedParseError("forbidden XML construct: %s" % type(e).__name__)
        except (ParseError, ValueError, UnicodeError) as e:
            if attempt is None:
                raise FeedParseError("not well-formed: %s" % e)
    raise FeedParseError("unreachable")


def _split(tag):
    if not isinstance(tag, str):
        return "", ""
    if tag.startswith("{"):
        ns, _, local = tag[1:].partition("}")
        return ns, local.lower()
    return "", tag.lower()


def _text(el):
    if el is None:
        return ""
    return "".join(el.itertext()).strip()


def _atom_text_type(el):
    kind = (el.get("type") or "").lower()
    escaped = (el.get("mode") or "").lower() == "escaped"
    if kind in ("xhtml", "application/xhtml+xml"):
        return "html" if escaped else "xhtml"
    if kind in ("html", "text/html"):
        return "html"
    if kind in ("", "text", "text/plain"):
        return "text"
    return "other"


def _atom_plain(el):
    kind = _atom_text_type(el)
    if kind == "xhtml":
        return html_plain_text(_xhtml(el))
    if kind == "html":
        return html_plain_text(el.text or _text(el))
    return _text(el)


def _xhtml(el):
    from xml.etree.ElementTree import tostring
    parts = [html.escape(el.text or "")]
    for child in el:
        parts.append(tostring(child, encoding="unicode"))
    return "".join(parts)


def _atom_html(el):
    kind = _atom_text_type(el)
    if kind == "xhtml":
        value = _xhtml(el)
    elif kind == "html":
        value = _text(el)
    elif kind == "text":
        value = text_to_html(_text(el))
    else:
        value = ""
    return value if value.strip() else None


def _is_atom(ns):
    return ns in (NS_ATOM, NS_ATOM03)


def _new_item():
    return {"guid": None, "link": None, "title": None, "summary_html": None, "content_html": None,
            "published": None, "updated": None, "categories": [], "adult": False, "enclosures": []}


def _media(el, item):
    _, name = _split(el.tag)
    if name == "rating" and _text(el).lower() == "adult":
        item["adult"] = True
    elif name in ("content", "group"):
        for child in el:
            ns, _ = _split(child.tag)
            if ns in NS_MEDIA:
                _media(child, item)
    elif name == "description":
        text = _text(el)
        if item["summary_html"] is None and text:
            item["summary_html"] = text_to_html(text)


def _rss_item(el):
    item = _new_item()
    permalink_guid = None
    for child in el:
        ns, name = _split(child.tag)
        if ns in NS_MEDIA:
            _media(child, item)
        elif ns == NS_CONTENT and name == "encoded":
            item["content_html"] = _text(child)
        elif ns == NS_DC and name == "date":
            item["published"] = item["published"] or _text(child)
        elif ns == NS_DC and name == "subject":
            item["categories"].append(_text(child))
        elif _is_atom(ns) and name == "link":
            if (child.get("rel") or "alternate") == "alternate" and item["link"] is None:
                item["link"] = child.get("href")
        elif _is_atom(ns) and name in ("published", "updated"):
            item["updated"] = item["updated"] or _text(child)
        elif ns and ns not in NS_RSS1:
            continue
        elif name == "title":
            item["title"] = html_plain_text(_text(child))
        elif name == "link":
            item["link"] = _text(child) or child.get("href") or item["link"]
        elif name == "description":
            item["summary_html"] = _text(child)
        elif name == "guid":
            value = _text(child)
            item["guid"] = value
            if (child.get("isPermaLink") or "").lower() != "false" and re.match(r"https?://", value, re.I):
                permalink_guid = value
        elif name in ("pubdate", "date"):
            item["published"] = _text(child)
        elif name == "category":
            item["categories"].append(_text(child))
        elif name == "enclosure":
            item["enclosures"].append(((child.get("url") or ""), (child.get("type") or "")))
    if not item["link"]:
        item["link"] = permalink_guid
    return item


def _atom_entry(el):
    item = _new_item()
    link_is_html = False
    for child in el:
        ns, name = _split(child.tag)
        if ns in NS_MEDIA:
            _media(child, item)
            continue
        if name == "title":
            item["title"] = _atom_plain(child)
        elif name == "id":
            item["guid"] = _text(child)
        elif name == "link":
            rel = child.get("rel") or "alternate"
            href = child.get("href")
            if rel == "alternate" and href and not link_is_html:
                item["link"] = href
                link_is_html = child.get("type") == "text/html"
            elif rel == "enclosure" and href:
                item["enclosures"].append((href, child.get("type") or ""))
        elif name == "category":
            term = child.get("label") or child.get("term")
            if term:
                item["categories"].append(term)
        elif name in ("published", "issued", "created"):
            item["published"] = item["published"] or _text(child)
        elif name in ("updated", "modified"):
            item["updated"] = _text(child)
        elif name == "summary":
            item["summary_html"] = _atom_html(child)
        elif name == "content":
            item["content_html"] = _atom_html(child)
    return item


def _resolve(base, url):
    """`Texts.resolve`, simplified: absolute http(s) URL or None."""
    from urllib.parse import urljoin, urlsplit
    if not url:
        return None
    url = url.strip()
    try:
        joined = urljoin(base or "", url)
        parts = urlsplit(joined)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.netloc:
        return None
    return joined


def parse_document(raw, feed_url):
    """The raw feed: {format, title, link, description, language, icon, categories, adult, items}."""
    root = parse_xml(raw)
    ns, name = _split(root.tag)
    feed = {"title": None, "link": None, "description": None, "language": root.get(XML_LANG),
            "icon": None, "categories": [], "adult": False, "items": []}
    if name == "rss" or name == "rdf":
        feed["format"] = "rss"
        channel = None
        for child in root:
            if _split(child.tag)[1] == "channel":
                channel = child
                break
        if channel is None:
            raise FeedParseError("RSS without a channel")
        containers = [channel] + ([root] if name == "rdf" else [])
        for container in containers:
            for child in container:
                cns, cname = _split(child.tag)
                if cname == "item" and (not cns or cns in NS_RSS1):
                    feed["items"].append(_rss_item(child))
                    continue
                if container is root:
                    if cname == "image" and feed["icon"] is None:
                        feed["icon"] = _text(child.find("{%s}url" % cns) if cns else child.find("url")) or None
                    continue
                if cns in NS_MEDIA and cname == "rating" and _text(child).lower() == "adult":
                    feed["adult"] = True
                elif cns == NS_DC and cname == "language":
                    feed["language"] = feed["language"] or _text(child)
                elif cns == NS_ITUNES and cname == "category":
                    if child.get("text"):
                        feed["categories"].append(child.get("text"))
                elif cns == NS_ITUNES and cname == "explicit":
                    pass  # podcast language, not adult content
                elif _is_atom(cns) and cname == "link":
                    pass
                elif cns and cns not in NS_RSS1:
                    continue
                elif cname == "title":
                    feed["title"] = html_plain_text(_text(child))
                elif cname == "link":
                    feed["link"] = feed["link"] or _text(child) or None
                elif cname == "description":
                    feed["description"] = html_plain_text(_text(child)) or None
                elif cname == "language":
                    feed["language"] = _text(child) or feed["language"]
                elif cname == "category":
                    feed["categories"].append(_text(child))
                elif cname == "image":
                    url_el = None
                    for sub in child:
                        if _split(sub.tag)[1] == "url":
                            url_el = sub
                    feed["icon"] = _text(url_el) or None
                elif cname == "rating" and "adult" in _text(child).lower():
                    feed["adult"] = True
    elif name == "feed":
        feed["format"] = "atom"
        logo = None
        for child in root:
            cns, cname = _split(child.tag)
            if cns in NS_MEDIA:
                if cname == "rating" and _text(child).lower() == "adult":
                    feed["adult"] = True
                continue
            if cname == "title":
                feed["title"] = _atom_plain(child)
            elif cname in ("subtitle", "tagline"):
                feed["description"] = _atom_plain(child) or None
            elif cname == "link":
                rel = child.get("rel") or "alternate"
                if rel == "alternate" and (feed["link"] is None or child.get("type") == "text/html"):
                    feed["link"] = child.get("href")
            elif cname == "icon":
                feed["icon"] = _text(child) or None
            elif cname == "logo":
                logo = _text(child) or None
            elif cname == "category":
                term = child.get("label") or child.get("term")
                if term:
                    feed["categories"].append(term)
            elif cname == "entry":
                feed["items"].append(_atom_entry(child))
        feed["icon"] = feed["icon"] or logo
    else:
        raise FeedParseError("not RSS or Atom (root <%s>)" % name)

    site = _resolve(feed_url, feed["link"])
    feed["site_url"] = site
    link_base = site if feed["format"] == "rss" and site else feed_url
    feed["icon_url"] = _resolve(link_base, feed["icon"])
    for item in feed["items"]:
        item["url"] = _resolve(link_base, item["link"])
    return feed


# --- Posts as the app stores them ---------------------------------------------------------------------

def finish_items(feed, now_ms):
    """The posts as `FeedParser.finishItem` and `ParsedItem.content` make them: title, summary,
    the FTS body cut as `EmbeddingBackfill` reads it, word count, the excerpt verdict, date."""
    seen = set()
    posts = []
    for item in feed["items"]:
        summary_html = item["summary_html"] if item["summary_html"] and item["summary_html"].strip() else None
        body_html = item["content_html"] if item["content_html"] and item["content_html"].strip() else summary_html
        url = item["url"]
        title = text_rules.squash(item["title"] or "")
        if not title:
            fallback = html_plain_text(summary_html or body_html or "")
            title = excerpt(fallback, TITLE_FALLBACK_CHARS) or ("Untitled" if url else None)
            if title is None:
                continue
        date = parse_date(item["published"]) or parse_date(item["updated"])
        guid = (item["guid"] or "").strip() or url or ("hash:" + hashlib.sha256(
            (title + "\n" + (item["published"] or item["updated"] or "").strip()).encode()).hexdigest()[:32])
        if guid in seen:
            continue
        seen.add(guid)
        blocks = html_blocks(body_html) if body_html else []
        plain = "\n".join(blocks)
        summary = excerpt((html_plain_text(summary_html) if summary_html else "") or plain)
        words = word_count(plain or summary or "")
        complete = word_count(plain) >= COMPLETE_WORDS and not EXCERPT_ENDING.search(plain[-200:].strip())
        published = min(date, now_ms) if date is not None else None
        posts.append({
            "title": title,
            "url": url,
            "summary": summary,
            "body": plain[: text_rules.BODY_CHARS] if blocks else None,
            "words": words,
            "complete": complete,
            "published": published,
            "categories": [c.strip() for c in item["categories"] if c and c.strip()],
            "adult": item["adult"],
            "enclosures": item["enclosures"],
        })
    return posts

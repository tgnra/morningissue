"""Turns fetched feeds into small, uniform RSS 2.0 fixtures for the ranking simulation.

Usage: python3 -I normalize_feeds.py <downloads dir> server/src/test/resources/simulation/feeds

The downloads dir holds one file per fetched document, named as in FEEDS below (for example
`bbc-top.xml`, `smitten-p2.xml`); server/src/test/resources/simulation/README.md lists the address
each was fetched from. Run it with -I and keep it outside the downloads dir: the documents are
untrusted input.

Each fixture keeps the real items' titles, links, categories, enclosures and the opening of
their bodies (at most BODY_WORDS words of text, plus the first code block if the cut dropped
it). The original body length is kept in <mi:words> so the simulation can restore each post's
length bucket without the fixture carrying the whole text.
"""
import sys, re, html
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from email.utils import parsedate_to_datetime, format_datetime
from datetime import datetime, timezone

ATOM = '{http://www.w3.org/2005/Atom}'
CONTENT = '{http://purl.org/rss/1.0/modules/content/}encoded'
BODY_WORDS = 130

# name: (files, title, site, item limit)
FEEDS = {
    'bbc-news': (['bbc-top'], 'BBC News', 'https://www.bbc.co.uk/news', 33),
    'bbc-scotland': (['bbc-scotland'], 'BBC News - Scotland', 'https://www.bbc.co.uk/news/scotland', 37),
    'guardian-uk': (['guardian-uk'], 'UK news | The Guardian', 'https://www.theguardian.com/uk-news', 30),
    'guardian-business': (['guardian-business'], 'Business | The Guardian', 'https://www.theguardian.com/uk/business', 30),
    'jvns': (['jvns'], 'Julia Evans', 'https://jvns.ca/', 20),
    'smitten-kitchen': (['smitten', 'smitten-p2'], 'smitten kitchen', 'https://smittenkitchen.com/', 20),
    'irunfar': (['irunfar', 'irunfar-p2', 'irunfar-p3'], 'iRunFar', 'https://www.irunfar.com/', 15),
    'yarn-harlot': (['yarnharlot', 'yarnharlot-p2'], 'Yarn Harlot', 'https://www.yarnharlot.ca/', 20),
    'bike-snob': (['bikesnob-more'], 'BIKESNOBNYC', 'https://bikesnobnyc.blogspot.com/', 25),
    '35mmc': (['photo-lenstip'], '35mmc', 'https://www.35mmc.com/', 10),
    'indie-games-plus': (['games-indiegamesplus'], 'Indie Games Plus', 'https://indiegamesplus.com/', 10),
    'atp': (['atp'], 'Accidental Tech Podcast', 'https://atp.fm/', 25),
    'daring-fireball': (['df'], 'Daring Fireball', 'https://daringfireball.net/', 40),
    'danluu': (['danluu'], 'Dan Luu', 'https://danluu.com/', 20),
}

DROP = {'script', 'style', 'iframe', 'noscript', 'svg', 'form', 'button', 'object', 'embed', 'video', 'audio', 'source', 'figcaption'}
VOID = {'img', 'br', 'hr'}
KEEP_ATTRS = {'href', 'src', 'alt'}


class Trimmer(HTMLParser):
    def __init__(self, budget):
        super().__init__(convert_charrefs=True)
        self.budget = budget
        self.words = 0
        self.total = 0
        self.out = []
        self.stack = []
        self.dropping = 0
        self.done = False
        self.in_pre = 0
        self.pre = None  # the first <pre> block, kept aside in case the cut drops it
        self.pre_buf = None
        self.kept_pre = False

    def handle_starttag(self, tag, attrs):
        if tag in DROP:
            self.dropping += 1
            return
        if self.dropping:
            return
        if tag == 'pre':
            self.in_pre += 1
            if self.pre is None and self.pre_buf is None:
                self.pre_buf = []
        if self.done:
            return
        a = ''.join(f' {k}="{html.escape(v or "", quote=True)}"' for k, v in attrs if k in KEEP_ATTRS and v and not v.startswith('data:'))
        self.out.append(f'<{tag}{a}>' if tag not in VOID else f'<{tag}{a}/>')
        if tag not in VOID:
            self.stack.append(tag)
            if tag == 'pre':
                self.kept_pre = True

    def handle_endtag(self, tag):
        if tag in DROP:
            self.dropping = max(0, self.dropping - 1)
            return
        if self.dropping:
            return
        if tag == 'pre':
            self.in_pre = max(0, self.in_pre - 1)
            if self.pre_buf is not None and self.pre is None:
                lines = ''.join(self.pre_buf).strip('\n').split('\n')[:12]
                self.pre = '\n'.join(lines)
                self.pre_buf = None
        if self.done or tag in VOID:
            return
        if tag in self.stack:
            while self.stack:
                t = self.stack.pop()
                self.out.append(f'</{t}>')
                if t == tag:
                    break

    def handle_data(self, data):
        if self.dropping:
            return
        n = len(data.split())
        self.total += n
        if self.pre_buf is not None:
            self.pre_buf.append(data)
        if self.done:
            return
        if self.in_pre:
            self.out.append(html.escape(data, quote=False))
            return
        if self.words + n > self.budget:
            take = data.split()[: max(0, self.budget - self.words)]
            self.out.append(html.escape(' '.join(take), quote=False) + ' …')
            self.words = self.budget
            self.done = True
            return
        self.words += n
        self.out.append(html.escape(data, quote=False))

    def result(self):
        out = self.out + [f'</{t}>' for t in reversed(self.stack)]
        if self.pre and not self.kept_pre:
            out.append('<pre><code>' + html.escape(self.pre, quote=False) + '</code></pre>')
        return ''.join(out), self.total


def trim(body):
    t = Trimmer(BODY_WORDS)
    t.feed(body or '')
    t.close()
    return t.result()


def date_of(s):
    if not s:
        return None
    try:
        return parsedate_to_datetime(s.strip())
    except Exception:
        pass
    try:
        return datetime.fromisoformat(s.strip().replace('Z', '+00:00'))
    except Exception:
        return None


def items_of(path):
    root = ET.parse(path).getroot()
    out = []
    for it in root.findall('./channel/item'):
        enc = it.find('enclosure')
        out.append(dict(
            title=(it.findtext('title') or '').strip(),
            link=(it.findtext('link') or '').strip(),
            date=date_of(it.findtext('pubDate')),
            categories=[(c.text or '').strip() for c in it.findall('category') if (c.text or '').strip()],
            enclosure=None if enc is None else (enc.get('url'), enc.get('type'), enc.get('length')),
            body=it.findtext(CONTENT) or it.findtext('description') or '',
        ))
    for it in root.findall(f'{ATOM}entry'):
        links = it.findall(f'{ATOM}link')
        alt = next((l.get('href') for l in links if l.get('rel', 'alternate') == 'alternate'), None)
        enc = next(((l.get('href'), l.get('type'), l.get('length')) for l in links if l.get('rel') == 'enclosure'), None)
        out.append(dict(
            title=(it.findtext(f'{ATOM}title') or '').strip(),
            link=(alt or '').strip(),
            date=date_of(it.findtext(f'{ATOM}published') or it.findtext(f'{ATOM}updated')),
            categories=[c.get('term', '').strip() for c in it.findall(f'{ATOM}category') if c.get('term', '').strip()],
            enclosure=enc,
            body=it.findtext(f'{ATOM}content') or it.findtext(f'{ATOM}summary') or '',
        ))
    return out


def cdata(s):
    return '<![CDATA[' + s.replace(']]>', ']]]]><![CDATA[>') + ']]>'


def main(src, dst):
    for name, (files, title, site, limit) in FEEDS.items():
        items, seen = [], set()
        for f in files:
            for it in items_of(f'{src}/{f}.xml'):
                if it['link'] in seen or not it['title']:
                    continue
                seen.add(it['link'])
                items.append(it)
        items = items[:limit]
        lines = ['<?xml version="1.0" encoding="UTF-8"?>',
                 '<rss version="2.0" xmlns:mi="https://morningissue.invalid/fixture">',
                 '<channel>', f'<title>{html.escape(title)}</title>', f'<link>{html.escape(site)}</link>']
        for it in items:
            body, words = trim(it['body'])
            lines.append('<item>')
            lines.append(f'<title>{html.escape(it["title"])}</title>')
            lines.append(f'<link>{html.escape(it["link"])}</link>')
            if it['date']:
                d = it['date'] if it['date'].tzinfo else it['date'].replace(tzinfo=timezone.utc)
                lines.append(f'<pubDate>{format_datetime(d.astimezone(timezone.utc), usegmt=True)}</pubDate>')
            for c in it['categories'][:12]:
                lines.append(f'<category>{html.escape(c)}</category>')
            if it['enclosure'] and it['enclosure'][0]:
                url, typ, length = it['enclosure']
                lines.append(f'<enclosure url="{html.escape(url)}" type="{html.escape(typ or "")}" length="{html.escape(length or "0")}"/>')
            lines.append(f'<mi:words>{words}</mi:words>')
            lines.append(f'<description>{cdata(body)}</description>')
            lines.append('</item>')
        lines += ['</channel>', '</rss>', '']
        with open(f'{dst}/{name}.xml', 'w', encoding='utf-8') as out:
            out.write('\n'.join(lines))
        print(name, len(items))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])

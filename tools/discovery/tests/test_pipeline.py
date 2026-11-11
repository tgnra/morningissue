"""Tests for the catalogue pipeline. No network: every document is inline.

    nix-shell -p 'python3.withPackages (p: [ p.numpy p.defusedxml ])' --run \\
      'python3 -I tools/discovery/tests/test_pipeline.py'
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import numpy as np  # noqa: E402

import build_catalogue as B  # noqa: E402
import catalogue  # noqa: E402
import embedder as E  # noqa: E402
import feedparse  # noqa: E402

NOW = 1_794_355_200_000  # 2026-11-11T00:00:00Z
DAY = B.DAY_MS


def rss(items, language="en", extra_channel=""):
    body = "".join(items)
    return ("<?xml version='1.0'?><rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/' "
            "xmlns:media='http://search.yahoo.com/mrss/'><channel><title>Example blog</title>"
            "<link>https://example.org/</link><description>Notes &amp; essays</description>"
            "<language>%s</language>%s%s</channel></rss>" % (language, extra_channel, body)).encode("utf-8")


def item(n, days_ago, words=300, title=None, extra=""):
    text = " ".join(["The garden was quiet in the morning and the light came slowly over the hedge."] * (words // 15))
    pub = feedparse.dt.datetime.fromtimestamp((NOW - days_ago * DAY) / 1000, feedparse.dt.timezone.utc)
    return ("<item><title>%s</title><link>https://example.org/p/%d</link><guid>https://example.org/p/%d</guid>"
            "<pubDate>%s</pubDate><description>A short summary of post %d.</description>"
            "<content:encoded><![CDATA[<p>%s</p>]]></content:encoded><category>Gardening</category>%s</item>") % (
        title or "Post number %d about gardens" % n, n, n, pub.strftime("%a, %d %b %Y %H:%M:%S +0000"), n, text, extra)


class TextRulesTest(unittest.TestCase):
    def test_normalize_matches_feed_urls(self):
        for url in ("https://example.com/feed", "http://www.example.com/feed/",
                    "HTTPS://Example.COM:443/feed#top", "example.com/feed"):
            self.assertEqual("example.com/feed", B.normalize_url(url), url)
        self.assertEqual("example.com:8080/feed?format=rss", B.normalize_url("http://example.com:8080/feed?format=rss"))

    def test_tiers_follow_cadence(self):
        self.assertEqual("FIREHOSE", B.tier_for(2.0))
        self.assertEqual("DAILY", B.tier_for(12.0))
        self.assertEqual("DAILY", B.tier_for(24.0))
        self.assertEqual("WEEKLY", B.tier_for(100.0))
        self.assertEqual("RARE", B.tier_for(200.0))
        self.assertEqual("FIREHOSE", B.tier_for(0.0))

    def test_cadence_needs_five_distinct_times(self):
        median, tier, _ = B.cadence([NOW] * 10, NOW)
        self.assertIsNone(median)
        self.assertEqual("WEEKLY", tier)
        median, tier, sample = B.cadence([NOW - i * DAY for i in range(10)], NOW)
        self.assertAlmostEqual(24.0, median)
        self.assertEqual("DAILY", tier)

    def test_posts_per_week_ignores_a_stray_old_date(self):
        daily = [NOW - i * DAY for i in range(29)] + [NOW - 20000 * DAY]
        self.assertAlmostEqual(7.0, B.rate_per_week(daily, NOW), delta=0.3)
        # Too few for a cadence: the two posts inside the 90-day window, over the window.
        self.assertAlmostEqual(2 / (90 / 7), B.rate_per_week([NOW, NOW - DAY, NOW - 200 * DAY], NOW))

    def test_boilerplate_descriptions_are_dropped(self):
        self.assertIsNone(B.clean_description("Recent content on Feld Thoughts", "Feld Thoughts"))
        self.assertIsNone(B.clean_description("Just another WordPress site", "X"))
        self.assertIsNone(B.clean_description("Example Blog.", "Example blog"))
        self.assertEqual("Essays on gardens", B.clean_description("Essays on gardens", "X"))

    def test_embeddable_text_drops_a_summary_the_body_opens_with(self):
        text = E.embeddable_text("Title", "The body opens like this and…", "The body opens like this and goes on.")
        self.assertEqual("Title\nThe body opens like this and goes on.", text)
        text = E.embeddable_text("Title", "A separate standfirst.", "The body.")
        self.assertEqual("Title\nA separate standfirst.\nThe body.", text)

    def test_embeddable_text_is_cut_at_two_hundred_words(self):
        text = E.embeddable_text("t", None, " ".join("w%d" % i for i in range(500)))
        self.assertEqual(200, len(text.split()))

    def test_non_latin_share(self):
        self.assertTrue(E.is_mostly_non_latin("Сегодня в Москве прошёл дождь"))
        self.assertFalse(E.is_mostly_non_latin("An English sentence quoting καλημέρα"))
        self.assertFalse(E.is_mostly_non_latin("1234 !!! 🎉"))
        self.assertTrue(E.is_mostly_non_latin("東京は日本の首都です"))

    def test_to_bytes_rounds_half_up_like_kotlin(self):
        v = np.zeros(256, dtype=np.float32)
        v[0], v[1], v[2] = 1.0, 0.5 / 127 * 1.0, -0.5 / 127
        b = E.to_bytes(v)
        self.assertEqual(127, b[0])
        self.assertEqual(1, b[1])   # 0.5 rounds up
        self.assertEqual(0, b[2])   # -0.5 rounds up too (floor(x + 0.5))


class EmbedderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = E.StaticEmbedder(B.ASSET)

    def test_piece_ids_match_the_reference_tokenizer(self):
        with open(os.path.join(B.REPO, "app", "src", "test", "resources", "embedding", "reference.json")) as f:
            reference = json.load(f)
        for case in reference["tokenizer"] + reference["embeddings"]:
            self.assertEqual(case["ids"], self.model.piece_ids(case["text"]), case["text"][:60])

    def test_parity_file_is_current(self):
        """parity.json must be what this embedder writes, or the Kotlin test checks stale numbers."""
        with open(B.PARITY_OUT) as f:
            parity = json.load(f)
        self.assertEqual(100, len(parity["cases"]))
        for case in parity["cases"]:
            text = E.embeddable_text(case["title"], case["summary"], case["body"])
            self.assertEqual(case["text"], text)
            vector = None if E.is_mostly_non_latin(text) else self.model.embed(text)
            if case["vector"] is None:
                self.assertIsNone(vector)
            else:
                self.assertGreater(float(np.dot(vector, np.array(case["vector"], dtype=np.float32))), 0.99999)

    def test_similar_topics_are_closer(self):
        a = self.model.embed("Planting garlic in autumn")
        b = self.model.embed("Growing onions and leeks in the vegetable garden")
        c = self.model.embed("Rust borrow checker lifetimes explained")
        self.assertGreater(float(a @ b), float(a @ c))


class FeedParseTest(unittest.TestCase):
    def test_rss_posts_as_the_app_stores_them(self):
        doc = rss([item(1, 1), item(2, 3, words=40)])
        feed = feedparse.parse_document(doc, "https://example.org/feed")
        self.assertEqual("Example blog", feed["title"])
        self.assertEqual("Notes & essays", feed["description"])
        self.assertEqual("https://example.org/", feed["site_url"])
        posts = feedparse.finish_items(feed, NOW)
        self.assertEqual(2, len(posts))
        self.assertTrue(posts[0]["complete"])
        self.assertFalse(posts[1]["complete"])
        self.assertEqual("A short summary of post 1.", posts[0]["summary"])
        self.assertEqual(["Gardening"], posts[0]["categories"])
        self.assertEqual(NOW - DAY, posts[0]["published"])

    def test_teaser_ending_is_an_excerpt(self):
        long = " ".join(["word"] * 300) + " Continue reading"
        doc = rss(["<item><title>t</title><link>https://e.org/1</link><content:encoded>%s</content:encoded></item>" % long])
        posts = feedparse.finish_items(feedparse.parse_document(doc, "https://e.org/f"), NOW)
        self.assertFalse(posts[0]["complete"])

    def test_atom(self):
        doc = b"""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-GB">
          <title type="html">A &lt;b&gt;bold&lt;/b&gt; feed</title><subtitle>Sub</subtitle>
          <link rel="alternate" type="text/html" href="https://a.example/"/><icon>/i.png</icon>
          <entry><title>One</title><id>urn:1</id><link href="/posts/1"/><updated>2026-11-01T10:00:00Z</updated>
          <category term="rust" label="Rust"/><content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Hello <b>there</b></p><p>Again</p></div></content></entry>
        </feed>"""
        feed = feedparse.parse_document(doc, "https://a.example/atom.xml")
        self.assertEqual("A bold feed", feed["title"])
        self.assertEqual("en-GB", feed["language"])
        self.assertEqual("https://a.example/i.png", feed["icon_url"])
        posts = feedparse.finish_items(feed, NOW)
        self.assertEqual("https://a.example/posts/1", posts[0]["url"])
        self.assertEqual("Hello there\nAgain", posts[0]["body"])
        self.assertEqual(["Rust"], posts[0]["categories"])

    def test_html_entities_and_junk_are_repaired(self):
        doc = rss([item(1, 1, title="Caf&eacute; &nbsp;notes & more")]) + b"<script>junk</script>"
        posts = feedparse.finish_items(feedparse.parse_document(doc, "https://example.org/f"), NOW)
        self.assertEqual("Café notes & more", posts[0]["title"])

    def test_entity_expansion_is_refused(self):
        doc = b"""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;">]>
          <rss><channel><title>&b;</title></channel></rss>"""
        with self.assertRaises(feedparse.FeedParseError):
            feedparse.parse_document(doc, "https://e.org/f")

    def test_html_text_skips_scripts_and_separates_blocks(self):
        self.assertEqual(["One two", "Three"], feedparse.html_blocks("<p>One <i>two</i></p><script>x()</script><p>Three</p>"))
        self.assertEqual("a b", feedparse.html_plain_text("<div>a</div><div>b</div>"))

    def test_dates(self):
        self.assertEqual(1_794_355_200_000, feedparse.parse_date("Wed, 11 Nov 2026 00:00:00 GMT"))
        self.assertEqual(1_794_355_200_000, feedparse.parse_date("2026-11-11T01:00:00+01:00"))
        self.assertEqual(1_794_355_200_000, feedparse.parse_date("2026-11-11"))
        self.assertIsNone(feedparse.parse_date("yesterday"))

    def test_excerpt(self):
        self.assertEqual("short", feedparse.excerpt("  short "))
        cut = feedparse.excerpt("word " * 200)
        self.assertTrue(cut.endswith("…"))
        self.assertLessEqual(len(cut), 401)


class AnalyseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = E.StaticEmbedder(B.ASSET)
        cls.blocklist = B.load_blocklist(os.path.join(B.HERE, "blocklist.txt"))

    def analyse(self, doc, url="https://example.org/feed"):
        meta = {"status": 200, "final_url": url, "fetched_at": NOW / 1000}
        return B.analyse(url, meta, doc, NOW, self.blocklist, self.model)

    def test_a_good_feed_is_kept(self):
        result = self.analyse(rss([item(i, i * 3) for i in range(8)]))
        self.assertNotIn("drop", result)
        self.assertEqual("WEEKLY", result["tier"])
        self.assertAlmostEqual(72.0, result["median_interval_hours"])
        self.assertEqual(1.0, result["full_text_share"])
        self.assertEqual(3, len(result["headlines"]))
        self.assertEqual(NOW, result["last_post_at"])
        self.assertAlmostEqual(1.0, float(np.linalg.norm(result["centroid"])), places=5)
        self.assertIn("Gardening", result["categories"])

    def test_drop_reasons(self):
        self.assertEqual("too_few_posts", self.analyse(rss([item(i, i) for i in range(4)]))["drop"])
        self.assertEqual("inactive", self.analyse(rss([item(i, 100 + i) for i in range(6)]))["drop"])
        self.assertEqual("not_english", self.analyse(rss([item(i, i) for i in range(6)], language="de"))["drop"])
        rated = item(0, 0, extra="<media:rating>adult</media:rating>")
        self.assertEqual("adult", self.analyse(rss([rated] + [item(i, i) for i in range(1, 6)]))["drop"])
        nsfw = rss([item(i, i) for i in range(6)], extra_channel="<category>NSFW</category>")
        self.assertEqual("adult", self.analyse(nsfw)["drop"])
        comic = rss([item(i, i, title="New comic: episode %d" % i) for i in range(6)])
        self.assertEqual("comic", self.analyse(comic)["drop"])
        self.assertEqual("blocklisted", self.analyse(rss([item(i, i) for i in range(6)]),
                                                     url="https://www.youtube.com/feeds/videos.xml?channel_id=x")["drop"])
        self.assertEqual("not_a_feed", self.analyse(b"<html><body>hi</body></html>")["drop"])

    def test_spam_like_is_flagged(self):
        thin = [item(i, i / 48, words=30) for i in range(40)]
        result = self.analyse(rss(thin))
        self.assertTrue(result["spam_like"])

    def test_english_heuristic_without_a_language(self):
        self.assertTrue(B.english_verdict(None, ["This is a post about the garden and what we did in it"] * 5)[0])
        self.assertFalse(B.english_verdict(None, ["Der Garten ist schön und wir haben nicht viel gemacht"] * 5)[0])
        self.assertFalse(B.english_verdict("fr", ["anything"])[0])
        self.assertTrue(B.english_verdict("en-US", ["Short"])[0])


class DedupeAndClusterTest(unittest.TestCase):
    def test_registrable_hosts(self):
        psl = "// comment\ncom\nuk\nco.uk\n*.ck\n!www.ck\n// private\ngithub.io\n"
        s = B.Suffixes(psl, ["bearblog.dev"])
        self.assertEqual("example.co.uk", s.registrable("blog.example.co.uk"))
        self.assertEqual("alice.github.io", s.registrable("alice.github.io"))
        self.assertEqual("bob.bearblog.dev", s.registrable("bob.bearblog.dev"))
        self.assertEqual("example.com", s.registrable("a.b.example.com"))
        self.assertEqual("www.ck", s.registrable("www.ck"))

    def test_main_feed_beats_comment_and_category_feeds(self):
        def rec(url, sources=("kagi",)):
            return {"url": url, "sources": list(sources), "posts": 10}
        main = rec("https://example.org/feed/")
        for other in ("https://example.org/comments/feed/", "https://example.org/category/rust/feed/",
                      "https://example.org/tag/x/feed"):
            self.assertGreater(B.main_feed_score(main), B.main_feed_score(rec(other)))

    def test_cluster_cap_trims_only_a_dominant_cluster(self):
        sizes = [3000] + [500] * 30
        kept = B.cap_clusters(sizes, 0.08)
        self.assertTrue((kept[1:] == 500).all())
        self.assertLessEqual(kept[0], 0.08 * kept.sum())
        self.assertGreater(kept[0], 0.08 * kept.sum() - 2)
        self.assertEqual([100] * 20, B.cap_clusters([100] * 20, 0.08).tolist())

    def test_kmeans_is_deterministic(self):
        rng = np.random.default_rng(0)
        points = B.unit_rows(np.concatenate([rng.normal(c, 0.1, (30, 8)) for c in (-1, 0.5, 2)]))
        a = B.spherical_kmeans(points, 3)
        b = B.spherical_kmeans(points, 3)
        self.assertTrue(np.array_equal(a[1], b[1]))


class CatalogueFileTest(unittest.TestCase):
    def test_round_trip_and_schema(self):
        rng = np.random.default_rng(3)
        mean = rng.normal(size=256).astype(np.float32)
        clusters = [{"id": 1, "label": "One", "centroid": B.unit_rows(rng.normal(size=(1, 256)))[0]}]
        vector = B.unit_rows(rng.normal(size=(1, 256)))[0]
        feed = {"url": "https://e.org/feed", "url_key": "e.org/feed", "site_url": None, "host": "e.org",
                "title": "E", "description": None, "icon_url": None, "language": "en", "tier": "WEEKLY",
                "median_interval_hours": None, "posts_per_week": 1.5, "full_text_share": 0.5,
                "last_post_at": NOW, "cluster_id": 1, "categories": ["A", "B"], "source": "kagi",
                "quality": 0.9, "centroid": vector,
                "headlines": [{"title": "h", "url": None, "published_at": None, "word_count": None}]}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "c.db")
            catalogue.write(path, built_at_ms=NOW, mean=mean, clusters=clusters, feeds=[feed])
            data = catalogue.read(path)
            self.assertEqual(1, data["user_version"])
            self.assertEqual("1", data["meta"]["format_version"])
            self.assertEqual("256", data["meta"]["dims"])
            self.assertEqual("1", data["meta"]["model_id"])
            self.assertEqual(1024, len(data["meta"]["mean"]))
            self.assertTrue(np.allclose(np.frombuffer(data["meta"]["mean"], dtype="<f4"), mean))
            got = data["feeds"][0]
            self.assertEqual("A\nB", got["categories"])
            self.assertEqual(256, len(got["centroid"]))
            self.assertGreater(float(np.dot(got["vector"], vector) / np.linalg.norm(got["vector"])), 0.9999)
            db = sqlite3.connect(path)
            names = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')")}
            db.close()
            self.assertTrue({"meta", "cluster", "feed", "headline", "feed_cluster", "feed_host", "feed_url_key"} <= names)

    def test_bad_values_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                catalogue.write(os.path.join(tmp, "c.db"), built_at_ms=0, mean=np.zeros(256),
                                clusters=[{"id": 1, "label": "x", "centroid": np.ones(256)}],
                                feeds=[{"tier": "HOURLY", "source": "kagi", "cluster_id": 1}])

    def test_fixture_is_current(self):
        """The committed fixture is what the writer makes now (it is deterministic)."""
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "fixture.db")

            class Args:
                out = path
            B.fixture(Args)
            with open(path, "rb") as a, open(B.FIXTURE_OUT, "rb") as b:
                self.assertEqual(a.read(), b.read(), "run build_catalogue.py fixture and commit the result")
            data = catalogue.read(path)
            self.assertEqual(30, len(data["feeds"]))
            self.assertEqual(4, len(data["clusters"]))


if __name__ == "__main__":
    unittest.main()

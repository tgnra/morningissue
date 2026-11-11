# Discovery catalogue pipeline

Builds `app/src/main/assets/discovery/catalogue.db`, the read-only SQLite file the app's
"Find sources" screen ranks from, entirely on the phone. The file is committed like the
embedding asset; the Gradle build never touches the network. A GitHub Actions workflow
(`.github/workflows/discovery-catalogue.yml`) rebuilds it monthly and opens a pull request.

```sh
# NixOS; elsewhere any Python 3.11+ with numpy and defusedxml.
nix-shell -p 'python3.withPackages (p: [ p.numpy p.defusedxml ])' --run \
  'python3 -I tools/discovery/build_catalogue.py build --report tools/discovery/report.json'

# The unit-test fixture and the Kotlin parity cases (no network):
... 'python3 -I tools/discovery/build_catalogue.py fixture'
... 'python3 -I tools/discovery/build_catalogue.py parity'

# Tests (no network):
... 'python3 -I tools/discovery/tests/test_pipeline.py'
```

A full build fetches about 45,000 feeds with 12 threads (at most 2 at a time per hosting
platform) and takes one to two hours the first time. Every answer is cached in
`tools/discovery/.cache/` (gitignored, about 1 GB, bodies gzipped) and reused for 25 days
(`--max-age-days`); after that, feeds are asked again with `If-None-Match` and
`If-Modified-Since`. `--offline` rebuilds from the cache alone, which takes minutes: use it to
tune filters or clusters. `--limit N` fetches a random N plus every plenaryapp
feed, for a quick trial.

## Files

| File | What it is |
| --- | --- |
| `build_catalogue.py` | The pipeline: source lists, filters, deduplication, clustering, the cluster cap, writing; also `fixture` and `parity` |
| `embedder.py` | A Python port of the app's `EmbeddableText`, `WordPieceTokenizer` and `StaticEmbedder`, reading the app's own asset |
| `feedparse.py` | RSS and Atom reading (defusedxml), HTML to text, and the app's rules for titles, summaries, body text, word counts and excerpts |
| `fetch.py` | The polite fetcher and its cache |
| `catalogue.py` | The SQLite writer (shared by the catalogue and the fixture) and a reader for tests |
| `sources.toml` | The source lists, their licences, and the host rules for deduplication |
| `blocklist.txt` | Hosts, address patterns, and the adult and comic keywords |
| `SOURCES.md` | Licences and attribution |
| `tests/test_pipeline.py` | Unit tests, inline documents only |

Downloaded documents are untrusted. They stay in the cache directory, are read with defusedxml
(no entity expansion, no external references) and the standard library's HTML tokenizer, and
nothing in them is executed. Only http(s) URLs that resolve to public addresses are fetched,
redirects included. Run the scripts with `python3 -I`.

## What is kept

A feed is fetched politely (User-Agent `MorningIssueCatalogue/1 (+https://github.com/tgnra/morningissue)`,
15 s socket timeout, 40 s overall, at most 2 MB after decompression) and kept only if:

1. it parses as RSS or Atom (a second attempt repairs HTML entities, bare ampersands, bad
   characters and junk after the root element);
2. it has at least 5 posts, and a dated post within the last 90 days;
3. no channel or post carries `<media:rating>adult</media:rating>`, and no adult keyword from
   `blocklist.txt` appears in its title, description, channel categories or the categories the catalogue would
   show for it (post categories used at least twice), in 20% of its posts'
   categories, or in a quarter of its post titles (at least 3);
4. it is not a webcomic (comic keywords in the feed's words, or in 30% of post titles or
   categories) and not YouTube (host blocklist; or most post links go to a video host);
5. at most half its posts are mostly non-Latin script (`EmbeddableText.isMostlyNonLatin`, on
   the very text the app would embed);
6. it is English: `<language>` (or `dc:language`, `xml:lang`) starting with `en`; with no
   language, English function words must be at least 10% of the words and more common than
   any of 12 other languages' function words. A declared `en` (often a CMS default) is
   overruled only with at least 80 words of evidence: another language's function words over
   12% of the words and twice as common as English's, or English's under 4%;
7. it is not spam-like: more than 20 posts a day (from the median interval) with a median post
   under 150 words. plenaryapp's hand-picked news outlets (BBC, Guardian) post exactly like
   that and are exempt;
8. at least 3 of its newest 20 posts have a vector.

Then duplicates go: first by `FeedUrls.normalize` (a moved feed meets its new address), then
by site. A site is the registrable host (Public Suffix List, plus `extra_suffixes` in
`sources.toml` so every `*.bearblog.dev` blog is its own site); for path-based platforms such as
medium.com it is the host plus the first path segment; for feed proxies (FeedBurner) it is
the site address's host. Per site the main feed wins: comment, category, tag and author feeds
lose to the plain feed, then more sources, more posts and the shorter address win.

## What each feed gets

| Column | How |
| --- | --- |
| `title`, `site_url`, `description`, `icon_url` | From the feed (plenaryapp's OPML title and description when the feed has none). Description cut to 280 characters, title to 160; boilerplate descriptions ("Recent content on …", "Just another WordPress site", the title again) are dropped. `icon_url` is null when the feed names no icon or logo |
| `host` | The registrable host, as above |
| `language` | The declared code, lower-cased, or `en` when the heuristic decided |
| `tier`, `median_interval_hours` | `engine/Cadence.kt` exactly: the last 30 distinct publish times or the last 90 days, whichever is more; Firehose above 8 a day, Daily 1 to 8 a day, Weekly at least 1 a week, else Rare; Weekly and null below 5 times. Tier names are `CadenceTier`'s |
| `posts_per_week` | 168 over the mean gap between posts in that same sample, the longest tenth of gaps left out (one stray old date cannot drag it down); below 5 dated posts, the posts of the last 90 days over 90 days |
| `full_text_share` | The share of posts that look complete by `DefaultFullTextFetcher.looksComplete`: 250 words or more of body text, not ending like a teaser ("…", "Read more", "Continue reading", "appeared first on") |
| `last_post_at` | The newest post's date (future dates are clamped to the build time, as sync does) |
| `categories` | The plenaryapp category first (country lists count as "News"), then the channel's categories, then post categories used in at least two posts; generic ones ("Uncategorized", "Blog") dropped; at most 8 |
| `source` | `plenary`, else `kagi`, else `hn`, among the lists that named the feed |
| `quality` | `(0.8 + 0.2 × full_text_share) × (1.0, or 0.7 if the newest post is over 45 days old) × (0.75 + 0.25 × min(1, median words / 200)) × (1.0, or 0.95 without a description)`. About 0.4 to 1 |
| `centroid`, `scale` | See below |
| headlines | The 3 newest posts: title, link, date, word count (`ParsedItem.content.wordCount`) |

### Vectors, and why they match the phone

A feed's centroid is the normalised mean of the vectors of its newest 20 posts, each made the
way `EmbeddingBackfill.stored` makes it on the phone: `EmbeddableText.of(title, summary, body)`
with the summary `Texts.excerpt`-ed to 400 characters and the body cut to
`EmbeddableText.BODY_CHARS`; no vector for a mostly non-Latin text; `StaticEmbedder.embed`; then
the `toBytes`/`fromBytes` round trip the app stores. The result is quantised to 256 int8 with a
REAL scale (`int8 = round(v / scale)`, `scale = max|v| / 127`).

The embedder is reimplemented in Python (`embedder.py`) rather than run from Kotlin. Reasons:

- The embedder lives in the Android app module: running it from a script means a Gradle build
  with the Android SDK (several minutes and a few GB), or keeping a separate JVM tools module in
  sync with it. The Python port needs numpy and reads the app's own asset file, so the table and
  vocabulary cannot drift; only the rules could.
- The rules are small and pinned by tests on both sides. `tests/test_pipeline.py` checks the
  port's piece IDs against `reference.json` (made by Hugging Face's own tokenizer) and that
  `parity.json` is what the port writes now. The app's `CatalogueParityTest` reads
  `app/src/test/resources/discovery/parity.json` (100 posts as title, summary and body, from
  plain prose to CJK, Cyrillic, emoji, control characters and over-length text) and requires
  the same embeddable text, the same script verdict, and the same vector within cosine 0.999.
  When written, every text and verdict matched and the lowest cosine was 0.9999991.
- The workflow runs that Kotlin test after every rebuild, so a change to the app's embedder
  that is not ported fails the catalogue's pull request.

One part is approximate: HTML to text. The app converts post HTML with Jsoup into blocks; the
pipeline uses the standard library's tokenizer with the same block and skip rules. Words can
differ at the margins (an image caption, a table), which moves a 20-post centroid very little,
and the app recomputes a candidate's centroid from a live fetch anyway.

## Clusters and what ships

The catalogue ships only inside the APK, so there is no size target: every feed that passes
the filters and deduplication is included.

1. All kept feeds' centroids are centred on their mean and normalised, then clustered by
   spherical k-means (56 clusters, k-means++ seeding, 3 restarts, fixed seeds: deterministic).
2. A safeguard: no cluster may hold more than 8% of the catalogue (`--max-cluster-share`). Only
   a cluster above that loses feeds, its lowest-ranked ones; the report counts them as
   `cluster_cap`.
3. The rank within a cluster is `quality`, nudged up for plenaryapp curation (×1.1) and for
   appearing in two lists (×1.05), and down for Firehose (×0.85).
4. The mean is recomputed over the shipped feeds (it is `meta.mean`), the clusters are refitted
   on them with a few k-means steps, and empty clusters are dropped and the rest renumbered 1...
5. Labels come from the feeds' categories, folded (case, plurals, hyphens, a few synonyms such
   as "artificial intelligence" for "AI"). A category qualifies in a cluster when at least 5
   feeds and 3% of the cluster carry it (3 and 1% as a fallback), and scores
   `share × log(1 + lift)`, lift being its share here over its share in the whole catalogue;
   ×1.3 when plenaryapp feeds of that category sit in the cluster too. The best category is the
   label, joined by the second ("Linux & Docker") when that scores at least half as well.
   Labels are unique. The small web has few categories and many of them are "AI", so read the
   labels over after every rebuild.

## The file (format version 1)

The contract with the app; see `catalogue.py` for the exact DDL.

- `PRAGMA user_version = 1`; `VACUUM`ed; integrity and foreign keys checked after writing.
- `meta`: `format_version` `'1'`, `built_at` (epoch ms, text), `model_id` `'1'`
  (`StaticEmbedder.MODEL_ID`), `dims` `'256'`, `mean` (256 little-endian float32: the mean of
  every feed centroid in the file). The optional `cos_stats_sample` is not written; the app can
  sample the catalogue's own centroids.
- `cluster(id, label, centroid, scale)`: the centroid is in the **centred** space, the
  normalised mean of its members' `normalise(centroid − mean)`. Compare it with centred
  vectors only.
- `feed(...)`: `centroid` is L2-normalised and **not** centred. `categories` is
  `'\n'`-separated and may be empty. `url_key` is `FeedUrls.normalize(url)`.
- `headline(feed_id, position, ...)`: position 0 is the newest.

Changing the format means a new `format_version`/`user_version` and a matching change in the
app's reader. Changing the embedder (a new `MODEL_ID`) means rebuilding the catalogue.

## The fixture

`build_catalogue.py fixture` writes `app/src/test/resources/discovery/catalogue-fixture.db`:
30 feeds in 4 clusters (Cooking, Programming, Astronomy, Gardening) on `.example` hosts, with
real vectors of hand-written headlines, every tier and source, and some null or empty optional
fields. It is deterministic (byte-identical on every run; a test checks the committed copy).

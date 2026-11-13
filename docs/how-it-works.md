# How Morning Issue works

This describes the app as the code on this branch behaves today. Work that is planned but not built is listed in [`todo.md`](../todo.md); where this document mentions it, it says so.

## Contents

1. [The idea](#the-idea)
2. [What the reader sees](#what-the-reader-sees)
3. [Terms](#terms)
4. [Feeds, sync and cadence](#feeds-sync-and-cadence)
5. [Scoring](#scoring)
6. [Affinity learning](#affinity-learning)
7. [Ranking posts within a feed](#ranking-posts-within-a-feed)
8. [How an edition is assembled](#how-an-edition-is-assembled)
9. [Timing](#timing)
10. [Carry-over and From the archive](#carry-over-and-from-the-archive)
11. [Discovery](#discovery)
12. [What is stored](#what-is-stored)
13. [The screens](#the-screens)
14. [Themes, tablets and e-ink](#themes-tablets-and-e-ink)
15. [Architecture](#architecture)
16. [Building, testing and releasing](#building-testing-and-releasing)
17. [Not built yet](#not-built-yet)

## The idea

Morning Issue is an RSS reader that publishes one personal newspaper a day. Each morning's edition is finite: a front page, two pages of top stories, a page per tag, a column of briefs and a back page. When you reach the back page you are done for the day. There is no endless scroll and there are no unread counts.

Two rules decide what gets space:

- **Freshness is relative.** A post is old or new compared with how often its feed posts. Two days is ancient for a news site and brand new for a blog that posts monthly.
- **Scarcity earns space.** A rare post from a feed you care about gets room that a news wire's post does not.

Nothing is thrown away. A post that does not make the paper is still listed on the back page, and every edition and every fetched post stays on the phone, browsable from back issues, source pages and search.

Everything runs on the device. There is no account and no internet service; the only traffic to the internet is fetching feeds, article pages and images. Nothing listens on the phone. The one other place it sends anything is [Sync with Kobo](#sync-with-kobo-uisettings-kobo-relay): it carries the latest editions to a Kobo, wherever it is, through a relay server, which only ever holds encrypted data.

## What the reader sees

1. **First launch.** Onboarding asks for feeds, by address or by importing an OPML file. It then fetches every feed, builds the first edition and opens the newsstand. This is the only time the app waits on the network unasked; Print now, when the reader presses it, is the other.
2. **The newsstand** is the home screen: the latest edition facing out, the six before it racked behind, and four labelled buttons (Search, Saved, Sources, Settings).
3. **The paper.** Tapping a cover opens that edition. Pages turn with a horizontal swipe, from the front page to the back page, which says "That's today's edition" and when the next one is due.
4. **The reader.** Tapping a story opens it in the paper's typography, with Like and Save at the end.
5. **The next morning**, at the reset time (06:30 by default), a new edition takes over. Until then the edition never changes, whatever arrives in the feeds. If that edition could not be built overnight, an unprinted cover stands in its place with a Print now button.

## Terms

| Term | Meaning |
| --- | --- |
| Edition | One day's paper. Once built it is frozen: its stories and their order never change. The one exception is an Extra edition reprinted the same day (see [The Extra edition](#the-extra-edition)). |
| Reset time | The local time at which a new edition is released. 06:30 by default, set in Settings. |
| Release | The moment an edition becomes visible (its `releaseAt`). An edition built ahead of time is hidden until then. |
| First-seen time | When the app first fetched a post (`article.firstSeenAt`). This, not the publish date, decides which edition a post belongs to. |
| Cutoff | The instant an edition was built (`edition.cutoffAt`). An edition covers posts first seen after the previous edition's cutoff and up to its own. |
| Cadence | How often a feed posts: its median gap between posts. |
| Tier | A feed's cadence class: Firehose, Daily, Weekly or Rare. |
| Affinity | One number per feed and per tag, 0 to 1, for how much the reader cares about it. 0.5 is neutral. |
| Slider | The per-feed override of learned affinity, in five steps: Rarely, Less, Auto, More, Favourite. Auto is the default. |
| Loved | A feed whose slider is at More or Favourite, or whose affinity after the slider is at least 0.7. |
| Held-over | An untouched post of a loved Weekly or Rare feed from before the previous cutoff. It stays eligible for later editions until it is opened. |
| Printed | Placed on a page of the edition, as opposed to listed in the back-page index. |
| Index | "Also in your feeds" on the back page: every candidate that was not printed, headlines only. |
| Untouched | Never opened, read, saved or Liked. |
| Signals | What is stored about a post besides its text and is read by within-feed ranking: its categories, its traits and its embedding. |
| Category | A label the feed itself files a post under (`<category>`). Not a tag: tags are the reader's, on feeds. |

## Feeds, sync and cadence

### Adding feeds

- **By address** (`sync/DefaultFeedDiscovery.kt`). The reader types a feed URL, a site URL or a bare host. Discovery tries, in order: the address itself as a feed; the feeds the page declares with `<link rel="alternate">` (or `rel="feed"`), at most 8, with comment feeds sorted last; then the common paths `/feed`, `/rss`, `/atom.xml`, `/index.xml`, `/feed.xml` and `/rss.xml` on the site. Every feed offered has been fetched and parsed. If several are found the reader chooses.
- **By OPML import** (`sync/Opml.kt`, `sync/DefaultOpmlTransfer.kt`), from Sources or onboarding, through the system file picker. The reader accepts outlines nested to any depth. Every enclosing folder name becomes a tag, as does each entry of an OPML 2.0 `category` attribute; a feed listed in several folders is added once with all their tags. Feeds already subscribed are skipped, and the result is reported as "Added 38, skipped 4".
- **OPML export** writes OPML 2.0: untagged feeds at the top level, then one folder per tag. A feed with several tags appears in each of its folders.

Two subscriptions are the same feed when their normalised URLs match (`model/FeedUrls.kt`): scheme, a leading `www.`, default ports, fragments and a trailing slash are ignored.

### Sync

`DefaultFeedSyncer` fetches every feed, six at a time, and stores the posts it has not seen before with first-seen time set to now, each with its categories and traits (see [Post signals](#post-signals)). Every sync checks every feed; a quiet feed is checked as often as any other (see [Feed health](#feed-health)). Syncing never builds or changes an edition.

After every full sync, `MaintainedFeedSyncer` runs four pieces of housekeeping on the same background thread, in this order: the feed health check, the signals upkeep (traits and embeddings for posts that lack them, see [Post signals](#post-signals)), the sweep of the [image store](#the-image-store), then the trim to the storage budget (see [What is stored](#what-is-stored)). The upkeep comes before the trim because the trim removes the text that embeddings are computed from. A failure in one does not fail the sync or stop the others.

| When | What runs |
| --- | --- |
| Every 2 hours on any connection, unless the battery is low | Periodic `SyncWorker` (`periodic-sync`) |
| Every hour while charging on an unmetered network | A second periodic `SyncWorker` (`periodic-sync-charging`) |
| Print now on the newsstand, when an edition failed to build | The same sync, then the build and the prefetch (see [When an edition failed to build](#when-an-edition-failed-to-build)) |
| After adding a feed or importing OPML in Sources | That feed, or all feeds |
| About 90 minutes before the reset time | The early build's final sync (see [Timing](#timing)) |
| Onboarding | The first sync |

WorkManager's periodic work is inexact and can be delayed by Doze, so these are intervals, not guarantees. Runs that overlap share one sync. Opening the app never syncs, apart from first launch, and the newsstand has no refresh button or pull gesture: a sync there could only fetch posts for the next edition.

Fetching (`sync/FeedFetcher.kt`) uses the shared OkHttp client with conditional requests (`ETag` and `Last-Modified`), a 45-second timeout and an 8 MB size limit. Plain `http` feeds are allowed (`usesCleartextTraffic` in the manifest). A permanent redirect (301 or 308) updates the stored feed address.

Parsing (`sync/FeedParser.kt`, `XmlFeedParser.kt`, `JsonFeedParser.kt`) reads RSS 2.0, 0.9x and 1.0, Atom and JSON Feed. The format is decided from the document, not its content type. For each post:

- **Identity** is the feed's own ID, else the post's link, else a hash of title and date. `(feedId, guid)` is unique, so a post is stored once.
- **Date** is the feed's publish date, else its updated date, else the fetch time. Dates in the future are clamped to the fetch time.
- **Title** falls back to the opening words of the text for untitled posts.
- **Lead image** is taken from media tags, then an image enclosure, then the first image in the body that is not icon-sized.
- **Body** HTML is converted to the app's block model (paragraphs, headings, images, quotes, lists, preformatted text) and stored as JSON. A plain-text summary of up to 400 characters and a word count are stored with the article.
- **Categories and attachments** are read for [Post signals](#post-signals): RSS `<category>`, Atom `<category term>` (with its `label`), JSON Feed `tags`; RSS `<enclosure>`, Atom `link rel="enclosure"` and JSON Feed attachments.

Each fetch also updates the feed's row in `feed_health`: a success clears the error streak and records the latest post date; a failure adds to the streak. The health check reads these.

### Feed health

`engine/FeedHealthRules.kt` (pure) decides which flag, if any, a feed carries on the Subscriptions desk. `sync/FeedHealthChecker.kt` gathers its input and stores the verdict after every full sync, and again without touching the network when the desk is opened. It never unsubscribes or changes a feed's slider.

| Flag | Rule | What the desk offers |
| --- | --- | --- |
| Broken | Fetch errors for 7 days running (and at least 3 failed fetches), or the server answers 410 Gone; and the automatic repair has been tried and has not worked | Try again, Unsubscribe, Dismiss |
| Moved | A permanent redirect or a repair changed the address to a really different one (not just `http`/`https` or `www.`) | OK (clears the flag), Unsubscribe |
| Noisy | 30 or more posts a week over the last 28 days with under 2% of them opened; feed subscribed for at least 28 days; slider not already at Less or Rarely | Show less (slider to Less), Unsubscribe, Dismiss |
| Ignored | Printed on a page the reader saw in 10 or more editions over the last 60 days without one of its stories being opened, read, saved or Liked; slider not already at Less or Rarely | Unsubscribe, Show less, Dismiss |
| Loved | Slider still at Auto, learned affinity at least 0.7, at least 8 stories printed over the last 60 days on a page the reader saw (or touched wherever they ran), at least half of them read and at least a tenth Liked | Make it a favourite (slider to Favourite), Dismiss |
| Gone quiet | No posts in 90 days (counted from when it was added, for a feed that has never posted). Not a problem | Dismiss |

A feed carries one flag at a time, the first that applies in the order above. All thresholds are in `RankingConfig`. "A page the reader saw" is the rule of [Affinity learning](#signals): on screen for 5 seconds in all, or in an edition from before page views were recorded. A feed is not Ignored because the reader was away. Noisy counts posts, not printings, so it is unchanged.

- **Dismiss** hides the flag for six months (183 days, `feed_health.flagDismissedUntil`). The dismissal covers the flag that was dismissed: if a different flag arises in that time it is shown.
- **Automatic repair** (`sync/FeedRepairer.kt`). A feed that meets the Broken rule is repaired before it is flagged. In order: its own address once more; the same address over the other scheme and with or without `www.`; the feed the site itself declares, through feed discovery on the site's address; then `/feed`, `/rss`, `/atom.xml` and `/index.xml` on the feed's host. Every candidate is fetched and must parse as a feed, and an address another subscription already uses is never taken. If one works the feed's address is updated and the feed is flagged Moved so the reader is told. If none works the feed is flagged Broken. A failed repair is tried again after 7 days, and at most 3 feeds are repaired per health check. "Try again" on the desk runs the repair at once and fetches the feed if it works.
- **Quiet feeds** sit in a Quiet group at the bottom of Sources and carry the Gone quiet flag. Quiet is a label and nothing more: a quiet feed is fetched by every sync, exactly like an active one, so the post that ends its silence is found at the next sync and gets the [Welcome back](#welcome-back) boost. (Quiet feeds used to be checked less often, down to weekly; that was removed.)
- **Once a month the edition carries the desk**: a boxed notice on the back page with the number of feeds needing attention and a link to the desk. The first time the back page of the newest edition is shown in a calendar month while at least one feed has an undismissed flag other than Gone quiet, that edition takes the month's notice and keeps it; no other edition that month shows one, and it goes when nothing needs attention any more. Which edition holds the month is kept in a small preferences file (`desk_notice`).

### Post signals

Three things are stored about each post for [ranking within a feed](#ranking-posts-within-a-feed). None of them is shown to the reader.

- **Categories** (`article_category`). The feed's own labels for the post, at most 12 per post. A category's key is its name with whitespace squashed, lowercased and cut to 64 characters; the feed's spelling is kept as the label. Categories exist only in the feed document, so they are stored at sync and carried by the archive.
- **Traits** (`article_traits`, computed by `model/ArticleTraitRules`):
  - the number of links and of images in the body;
  - whether the body has code (a preformatted block or inline code);
  - the kind of attachment: audio or video by the stated media type, else by the file extension; an image attachment counts as none, because it is the lead image; anything else is "other". With several attachments the first audio or video wins;
  - whether it is a **link post**: its own link leaves the feed's site, or its body is at most 120 words and links to exactly one address off the site;
  - its **title prefix**: the text before the first `": "`, `" | "` or spaced dash in the title, lowercased, when it is at most 3 words and 40 characters ("Links: week 12" gives `links`).
  Each row records the version of the rules it was computed under (`ArticleTraitRules.VERSION`, now 1). Raising the version makes every row stale and the backfill recomputes them.
- **Embedding** (`article_embedding`). A 256-dimension vector of the post's title, summary and opening text, stored as 256 bytes. See [The embedder](#the-embedder).

Who writes them:

- **Sync** stores categories and traits with each new post, in the same transaction. For a post it already holds it fills in what is missing: categories and traits for posts stored before these tables existed, and an attachment kind the backfill could not know. Once a post has them it is passed over, at the cost of one read per fetched feed.
- **`TraitsBackfill`** (`data/signals/`) computes traits for every post that has no row under the current rules, from what is stored: the title, the link, the feed's site address and the stored body. It cannot know the attachment (a recomputed row keeps the one it had) or the categories.
- **`EmbeddingBackfill`** embeds every post that has no row under the current model, newest first, 500 posts to a transaction.
- **`SignalsUpkeep`** runs the two in that order. It runs after every full sync, and once five seconds after the app starts, on the app's background scope and never on the main thread. Only one pass runs at a time: a call that arrives during a pass does not wait and does not start another, and the running pass goes round once more when it finishes. Both passes are idempotent, stop between batches when cancelled and keep the batches they finished. A pass with nothing to do is two reads and does not load the embedder.

So a library upgraded from database version 1, or brought in by an archive import, gets its traits and embeddings without the reader doing anything, and its categories at the first sync after the upgrade (the migration clears every feed's `ETag` and `Last-Modified` so that sync fetches each feed in full).

Posts fetched by a single-feed sync (after adding a feed, or for an Extra edition) get categories and traits at once but are embedded by the next pass, not before the Extra edition is printed.

### The embedder

`embedding/StaticEmbedder.kt` turns a text into a vector without running a neural network. The model is a static embedding: a table with one vector per word piece, and a text's vector is the average of its pieces' vectors, scaled to length 1.

- **The table** is `minishlab/potion-base-8M` (MIT licence), a model2vec distillation of `BAAI/bge-base-en-v1.5`: 29,528 uncased word pieces by 256 dimensions, 8 bits a value. It ships in the APK as the asset `embedding/potion-base-8m.bin` (7.9 MB, stored uncompressed), built by `tools/embedding/build_asset.py`. No ML library is used.
- **Loading.** `EmbedderAsset` memory-maps the asset the first time an embedding is needed, off the main thread, once per process. Mapped, the vectors cost no Java heap; the vocabulary takes about 1 MB.
- **The text** (`EmbeddableText`) is the title, then the summary, then the opening of the body, cut to 200 words. A summary that is the body's own opening is not repeated.
- **Model ID.** Stored vectors carry `StaticEmbedder.MODEL_ID` (now 1). A vector written under another ID counts as missing and is replaced, so changing the table or the tokenizer means raising the ID.
- **Posts without a vector.** Every post the embedding pass reads gets a row, so it is not read again. When no vector can be stored the row holds a one-byte mark in place of the 256 bytes, and the ranking treats it as no vector:
  - mark 0: the embedder knows none of the post's words (no text, only emoji or digits);
  - mark 1: the text is mostly not in Latin script.
- **Non-Latin text.** The model is English. It knows Cyrillic, Greek, Arabic, Hebrew, kana and CJK only as single characters, so the vector of such a text encodes its script and not its subject. A text is not embedded when more than half of its letters are in a script other than Latin (`EmbeddableText.MAX_NON_LATIN_SHARE`). Digits, punctuation and emoji are not counted. Such posts are ranked on categories and traits only. Other languages in Latin script are embedded; their vectors are weak but not systematically wrong.
- A post is embedded once. It is not embedded again when its full text is fetched later.

### Full text

Many feeds carry only an excerpt. `DefaultFullTextFetcher` downloads the article's page, extracts the text with Readability4J and stores it as blocks. It is called when the reader opens an article and by the early build for every printed story. Feed text counts as complete, and the page is not fetched, when it has at least 250 words and does not end like a teaser ("…", "read more", "continue reading"). The page is fetched at most once per article.

A post whose feed body is a short teaser that sends the reader to the site (it ends with "Read in full on …", "Read more", "Continue reading", "Read the full story" or an ellipsis; `FullTextExtractor.isTeaser`) has its page fetched during the sync that first stores it, not only when it is opened, so the text is there to read anywhere, the Kobo included. At most 10 such posts per feed per sync, newest first, two at a time. A failure leaves the teaser, and the reader tries again on opening.

Once, after the upgrade that brought this (`TeaserBackfillWorker`, on the first connection after the app starts), the posts already stored get the same treatment: every subscribed feed's teaser posts from the last 30 days (`RankingConfig.maxAgeDays`), four at a time. A preference (`upgrades` / `teaserBackfillDone`) records that the pass has finished, so it never runs again.

### Cadence

After each fetch, `engine/Cadence.kt` recomputes how often the feed posts from its last 30 publish times or the last 90 days, whichever covers more posts.

- **Median interval** between posts, in hours. The median, so one long gap does not distort it.
- **Burstiness**: the 90th percentile gap over the 10th. It is stored but nothing uses it yet.
- **Tier**:

| Tier | Rule |
| --- | --- |
| Firehose | More than 8 posts a day |
| Daily | 1 to 8 posts a day |
| Weekly | At least 1 post a week |
| Rare | Fewer than 1 post a week |

Posts sharing a timestamp count once. A feed with fewer than 5 distinct timestamps is too new to judge: its tier defaults to Weekly and its interval stays unknown, in which case scoring uses the tier's typical interval (30 minutes, 24 hours, 7 days or 30 days).

## Scoring

Every candidate post gets a score from multiplied factors (`engine/Scorer.kt`). All constants live in `engine/RankingConfig.kt`.

```
score = A × F × S × P × C × W
```

Multiplying means any factor near zero sinks the post: affinity cannot rescue stale news, and a post the reader has finished never returns.

| Factor | Formula | Notes |
| --- | --- | --- |
| A, affinity weight | `0.3 + 0.7 × affinity` | `affinity` is the feed's learned affinity after the slider. The 0.3 floor keeps disliked feeds from vanishing. |
| F, freshness | `0.5 ^ (age / h)`, with `h = clamp(1.5 × medianInterval, 12 h, 240 h)` | `age` is measured at the edition's release time, not when the reader opens it. A post dated after release counts as brand new. |
| S, scarcity | `1 + (S₀ − 1) × (2 × min(1, affinity / 0.5) − 1)`, with `S₀ = min(1.8, 1 + 0.15 × log2(1 + medianInterval / 24 h))` | `S₀` is about 1.00 for a firehose, 1.15 for a daily feed, 1.45 for a weekly one, 1.74 for a monthly one. It is paid in full from neutral affinity up; below neutral it shrinks, to nothing at 0.25 and to `2 − S₀` at 0 (0.55 for a weekly feed). See below. |
| P, seen penalty | `0.5 ^ editionsRunUnopened`; 0 once the post is read | Halves for each edition the post was printed in without being opened. |
| C, comeback boost | 1.5 for a feed's first post after 90 quiet days; otherwise 1 | See [Welcome back](#welcome-back). |
| W, within-feed weight | 0.5 to 2.0 (a key alone 0.7 to 1.4); 1 when nothing is known | How this post compares with its own feed's other posts for this reader. See [Ranking posts within a feed](#ranking-posts-within-a-feed). |

The slider turns learned affinity into the value used in A:

| Slider step | Effect on affinity |
| --- | --- |
| Rarely | Capped at 0.15 |
| Less | Capped at 0.35 |
| Auto | As learned |
| More | At least 0.65 |
| Favourite | Fixed at 1.0 |

Half-life by tier:

| Tier | Typical median interval | Half-life `h` |
| --- | --- | --- |
| Firehose | 30 min | 12 h (the floor) |
| Daily | 24 h | 36 h |
| Weekly | 7 days | 10 days (the ceiling) |
| Rare | 30 days | 10 days (the ceiling) |

Worked examples, all untouched posts scored at release time, with W at 1:

| Post | Affinity | Age at release | A | F | S | Score |
| --- | --- | --- | --- | --- | --- | --- |
| Favourite weekly blog | 0.9 | 7 days | 0.93 | 0.62 | 1.45 | **0.83** |
| News site (firehose), neutral | 0.5 | 3 h | 0.65 | 0.84 | 1.00 | 0.55 |
| Daily newsletter, so-so | 0.3 | 6 h | 0.51 | 0.89 | 1.03 | 0.47 |
| News site (firehose), neutral | 0.5 | 12 h | 0.65 | 0.50 | 1.00 | 0.33 |
| Daily newsletter, neutral | 0.5 | 3 days | 0.65 | 0.25 | 1.15 | 0.19 |

The week-old post from the blog you love beats this morning's news.

**Scarcity is earned.** Scarcity is a bet that a post the reader rarely gets is worth more to them. A feed nobody knows anything about yet, and every feed the reader likes, get the whole bonus. A feed whose posts the reader keeps leaving loses it: the bonus shrinks in proportion below neutral affinity and turns into a drag below 0.25. Without that a slow feed the reader never opens kept the front page on its rarity and its long half-life (a weekly podcast nobody listened to was on the front page with every episode; see [Affinity learning](#the-calculation)). A firehose, with `S₀` = 1, is unaffected. The so-so daily newsletter above, at affinity 0.3, keeps a fifth of its bonus.

**Freshness floor.** A new post whose F has dropped below 0.05 (`RankingConfig.minFreshness`) is not printed. That is about 52 hours for a firehose and six and a half days for a daily feed; Weekly and Rare feeds reach the 30-day age limit first. The post is still listed in the back-page index. Held-over posts are exempt, as are the newest posts of each feed in an Extra edition.

Each printed story also stores a one-line reason (`Scorer.reason`), for example "You read most posts from Daring Fireball", "Rare post: this blog publishes about once a month", "You usually read Planet's posts about space" or "New from The Verge since the last edition". The line names whichever of affinity, scarcity and W pulls hardest. It is stored in `edition_item.reason` and shown by "Why is this here?" in the story menu.

### Welcome back

When a feed posts again after at least 90 days of silence (`comebackQuietDays`), that post is a comeback:

- Its score is multiplied by 1.5 (`comebackBoost`), on top of scarcity. A comeback from a neutral weekly feed scores about 0.65 × 1.45 × 1.5 = 1.41. A brand-new post from a Favourite weekly feed scores 1.45 and little else comes near, so with five front-page slots the comeback nearly always makes the front page. It is not guaranteed the lead.
- Its reason line reads "Back after 2 years: *feed* has posted again". The label ("Back after 5 months", "Back after a year", "Back after 2 years") is also on `EditionStory.comebackLabel`, read back from the reason line. No screen shows it as a badge yet.

The silence is measured from the feed's newest post that the app had already seen before this edition's window opened, to the publish time of the feed's first new post after it. So:

- only the first post back is a comeback; the feed's later posts are ordinary;
- a newly added feed is never a comeback, whatever the gaps in its history;
- a held-over reprint of the comeback post loses the boost and the label;
- a feed with the slider below Auto gets the label but not the boost.

The publisher looks the gap up from article timestamps (`ArticleDao.lastKnownPostAt`), one indexed query per feed with new posts. It does not use the `feed_health` table.

## Affinity learning

Affinity is learned from what the reader does with printed stories (`engine/Affinity.kt`, `data/repository/AffinityLearner.kt`). There is no skip or dismiss: the exposure is "the story was printed on a page the reader saw", and being seen without being opened is the only negative signal.

### Signals

| Signal | How it is recorded | Weight |
| --- | --- | --- |
| Open | The article is opened in the reader | 1 |
| Read | Scrolled through at least 70% of the body, or kept open for at least half its estimated reading time (230 words a minute, at least one minute) | 2 |
| Save | The Save button at the end of the article | 2 |
| Like | The Like button at the end of the article | 3 |
| Ran unopened | The story was printed on a page the reader saw, and never touched | Exposure with no credit |

**Seen.** The paper records how long each page of an edition is on screen (`edition_page_view`; see [Paper](#paper-uipaper)). A page counts as seen once it has been on screen for 5 seconds in all (`pageSeenMinMillis`), over one visit or several. A story on a page the reader never reached, or flicked past, has not been passed over: its run is no exposure, for affinity, for the within-feed labels, for `editionsRunUnopened` (and so P) and for the Ignored and Loved flags. A week away therefore says nothing about taste. Engagement always counts, wherever the story ran. The rule goes by pages, not stories: a page holds a handful of stories that fit on one screen or close to it, and a story-level rule would need the paper to track which cards were scrolled into view and for how long, for little gain. The rule only ever removes evidence, so where it errs, it errs towards learning nothing.

**The cut-over.** Before version 4 of the database nothing was recorded about pages. Each edition carries `pageViewsTracked`: the migration sets it to 0 for every existing edition, and every edition built afterwards gets 1. An untracked edition is judged entirely by the old rule (every printed story counts as seen), so the history learned before the upgrade stays as it was; a tracked one only counts the pages the reader saw. A flag per edition, rather than an upgrade time, cannot be confused by a clock change, and an edition built before the upgrade but read after it is not half judged by each rule. Imported editions (see [Archive export and import](#archive-export-and-import)) are untracked.

### The calculation

Every event is weighted by its age with a 30-day half-life: `w = 0.5 ^ (age in days / 30)`. Events older than 180 days are not read at all.

For one article:

```
points   = 1·w(opened) + 2·w(read) + 2·w(saved) + 3·w(liked)
positive = points / 3
exposure = max( sum of w over the editions it ran in, largest w of its engagement events )
```

`positive` is in units of "a post opened and read": an open alone is worth a third, a read post 1, a read and Liked post 2. A Like or Save is therefore extra credit that can make up for a post that was ignored. A post read outside an edition (from a source page, search or an old back issue) counts as one exposure at the time it was read.

With a prior of `n₀` pseudo-exposures:

```
tag affinity  = (10 × 0.5   + positive over its feeds) / (10 + exposure over its feeds)
feed affinity = (n₀ × prior + positive)                / (n₀ + exposure)
prior         = mean affinity of the feed's tags, or 0.5 for an untagged feed
n₀            = clamp(0.25 × reachable, 1, 10)
reachable     = (24 h / medianInterval) × 30 / ln 2
```

Both are clamped to 0..1. A feed with no evidence sits on its prior.

**The prior follows the cadence.** `reachable` is the decayed exposure a feed's record levels off at: about 43 for a feed that posts daily, 6.2 for a weekly one, 1.4 for a monthly one. With a prior of 10 for every feed, as before, a weekly or monthly feed's own record could never outweigh its prior however consistently the reader treated it: a monthly blog read nearly every time stayed near 0.3 and was never loved, and a weekly podcast never opened sank to 0.03 but kept the front page through scarcity. With `n₀` a quarter of `reachable` (`affinityPriorShare`), every feed's record outweighs its prior four to one at steady state, whatever its cadence. Daily and faster feeds keep `n₀` = 10, a weekly feed gets about 1.6, and a monthly one the floor of 1 (`affinityMinPriorStrength`), so that one post never decides on its own. A feed whose cadence is not measured yet keeps 10. For a daily feed, ten read posts in a row lift an untagged feed to 0.75; ten ignored ones drop it to 0.25.

The cost is noise: a slow feed is judged on its few posts. For a reader who opens stories at random, a weekly feed's affinity has a standard error of about 0.11 and a monthly one's about 0.18, against 0.04 for a daily feed (see the random reader of the [ranking simulation](#building-testing-and-releasing)).

**Tag borrowing** is that prior: a new or rarely seen feed starts at the average of its tags' affinity, and its own record takes over smoothly as exposures accumulate. A slow feed borrows for fewer posts, so its tags neither hold up a slow feed the reader ignores nor hold down one they read every time.

### When it runs

Nothing is kept as a running total. Each pass recomputes every feed and tag from the `edition_item` and `engagement` rows, so undoing a Like cannot leave the numbers out of step. A pass runs:

- when an edition is built, so the builder ranks with current values;
- when a story's opened, read, saved or Liked state changes;
- when tags or subscriptions change.

An unopened story only counts against its feed once its edition has been superseded by the next build, and only if its page was seen. Today's paper does not count against a feed while it can still be read.

The slider is not part of learning. It is applied on top, in `Affinity.effective`.

## Ranking posts within a feed

Feed and tag affinity say how much the reader cares about a feed. They cannot tell two posts of one feed apart. W does: it is learned from which of a feed's posts the reader opens and which they leave (`engine/WithinFeed.kt`, pure; `data/repository/WithinFeedLearner.kt` reads the stored signals for it).

**This is unproven.** It has not been tested against real reading history. Every constant below is a guess, which is why a key is held between 0.7 and 1.4 and W as a whole between 0.5 and 2.0: a wrong guess reorders a feed's posts without sinking or crowning them, and only a text taste whose two halves agree on plenty of labels can go past a key's bounds. See `todo.md`.

### The label

The evidence is the evidence affinity learning uses, gathered once by `AffinityLearner.evidence` and handed to both: the same 180-day window, the same 30-day half-life, and the same rule that an untouched story of an edition not yet superseded does not count. For each post, `positive` and `exposure` are computed as in [Affinity learning](#affinity-learning). Then, per feed:

```
feed rate r = Σ positive / Σ exposure        over the feed's posts with evidence
residual    = positive − exposure × r        per post; sums to 0 over a feed
```

A post's label is its residual: how much better or worse it did than its feed's average. A feed the reader always opens does not make all its posts look good.

### Keys

Each post carries keys built from its signals:

| Key | From |
| --- | --- |
| Category | One per category of the post |
| Length | The word count of the feed's own text: brief (up to 150 words), short (up to 500), medium (up to 1,500), long. No key when the feed gave no text |
| Attachment | Audio, video or other, when it has one |
| Link post | When it is one; "not a link post" when it is known not to be |
| Code | When the body has code; "no code" when it is known to have none |
| Title prefix | When the title has one |

A missing trait is no key, except for the two yes-or-no traits, which have a key either way once the post's traits are known (`PostTraits.traitsKnown`; not before the traits backfill has reached the post). A link blog whose own pieces the reader reads is then told apart by a key on those few pieces, not only by a small lift down on the many links: a key carried by nine posts in ten sits close to the feed's own rate and says little.  The image and link counts are stored but no key is built from them. The length is `article.feedWordCount`, the number of words in the text the feed itself gave. It is set when the post is stored and fetching the full text never changes it, so a post the reader opened and one they did not are measured alike. (`article.wordCount`, which the reading time uses, becomes the fetched page's count. Read from that column, an excerpt-only feed's opened posts looked long and its new posts brief, and the new posts were marked down together.) For each feed and key:

```
lift = Σ residual over the feed's posts carrying the key / (10 + Σ exposure of those posts)
```

This is the key's rate above or below the feed's, shrunk towards the feed's own rate by a prior of 10 pseudo-exposures (`withinFeedKeyPriorStrength`). A key carried by every labelled post of its feed has a lift of exactly 0. Unlike a feed's affinity prior this one is not scaled to how often the key turns up: it is what keeps a key seen on one or two posts of a busy feed from reaching the cap on a single read. The cost is that a key on about seven posts a month (a link blog's own articles) rests near ×1.25 rather than at ×1.4.

A feed's keys are trusted by `E / (E + 12)`, where `E` is the feed's decayed exposure (`withinFeedFeedPriorPosts`): half strength at a dozen recent labelled posts, three quarters at three dozen.

### Text taste

Keys are learned per feed. Text is learned across all feeds, so that few labels go further. Each labelled post with a vector pulls a direction towards itself by its residual:

```
direction = Σ residual × (v / |v|)
```

Because residuals sum to zero within a feed, a feed's general subject cancels out.

A direction can always be found that separates the posts it was built from, so it is never judged on them. The labelled posts are split in two halves by a hash of the article ID, a direction is built from each half, and each is tried on the other half:

```
z(post) = (cos(post, other half's direction) − mean) / deviation     over that half
slope   = max(0, Σ z × residual / Σ exposure)
```

`slope` is how many "posts read" one deviation of closeness to the taste is worth. It is about 0 for a reader who engages at random, and it is floored at 0. For a candidate:

```
text lift = slope × mean of its z under the two directions
```

The text lift is trusted by how much evidence there is and by how well the two halves agree:

```
trust = n / (n + 50) × agreement(first half) × agreement(second half)
agreement = t² / (t² + 2²), or 0 when the half predicts the opposite
t = Σ z × residual / √Σ (z × residual)²        over the half's posts, z from the other half's direction
```

`n` is the decayed exposure of all labelled posts with a vector (`withinFeedTextPriorPosts`, half strength at 50, two or three editions' worth of printed stories). `t` is the statistic of "no taste" for that half: a half whose direction predicts the other half two standard errors from nothing is believed a half, five 0.86, ten 0.96 (`withinFeedTextConfidenceT`). A handful of labels, a reader who opens at random and halves that disagree all stay near 0; hundreds of consistent labels near 1. Nothing switches at a threshold. The text lift does not wait for the candidate's own feed to have a record.

### Combining

Each signal of a candidate has an effect, with `gain` = 1.0 (`withinFeedGain`):

```
key effect  = clamp(gain × trust × lift, ln 0.7, ln 1.4)
text effect = trust × ln 2 × tanh(gain × text lift / ln 2)
ln W        = (largest positive effect, or 0) + (most negative effect, or 0)
W           = clamp(exp(ln W), 0.5, 2.0)
```

A key alone stays within 0.7..1.4 (`withinFeedKeyMinWeight`, `withinFeedKeyMaxWeight`). The text taste is close to `trust × gain × lift` for a small lift and never goes past `trust × ln 2` (`withinFeedTextMaxEffect`): with little data it is nearly neutral, and a text taste whose halves fully agree can double or halve a post's score, enough to beat a day of freshness in a daily feed (`0.5 ^ (24 / 36) ≈ 0.63`).

Only the strongest signal on each side counts. A podcast episode is often marked three ways (a category, an audio attachment, a title prefix); adding the three would count one fact three times. A candidate with no evidence either way gets exactly 1.

`Scorer` applies W whatever the slider says. Wherever posts compete on score W has its say: the ranking, which of a feed's posts takes its front-page place, Top stories, sections, In brief, From the archive and While you were away. Two rules ignore it: the freshness floor goes by F alone, and of two feeds' copies of one story the copy from the higher-affinity feed is kept.

### The reason line

"Why is this here?" names the within-feed signal when W is at least 1.15 (`withinFeedReasonMinWeight`) and pulls harder than both affinity and scarcity:

| Signal | Line |
| --- | --- |
| Category | "You usually read Planet's posts about space" |
| Length | "You usually read long posts from Planet" |
| Attachment | "You usually open audio posts from Planet" |
| Link post | "You usually read link posts from Planet"; "You usually read Planet's own pieces rather than its links" |
| Code | "You usually read posts with code from Planet"; "You usually read Planet's posts without code" |
| Title prefix | "You usually read "links" posts from Planet" |
| Text | "Similar to posts you have Liked", or "Similar to posts you have read" when no Liked post shaped the taste |

A key's line is only used when the reader engages with that feed's posts carrying the key at least half the time. A W below 1 never gives a reason. The category is named by its lowercased key, not the feed's spelling.

### When it runs

The model is learned inside every build, of every kind (scheduled, on-open, Evening, Extra), and is stored nowhere:

1. The evidence is gathered once and used for affinity.
2. The model is learned from that evidence as of the edition's release time, with the signals of every post in it read in one query.
3. The signals of every post the build scores are read: new posts, held-over posts, the already-printed posts kept for deduplication and the While you were away pool.
4. The builder scores with that model.

Nothing is read when there is no evidence (a first edition). Candidates' vectors are not read when the text taste has no slope.

**Cost.** Measured on a desktop JVM against an in-memory database with 3,000 labelled posts and 1,000 candidates, all with two categories, traits and a vector (`WithinFeedBuildCostTest`): step 2 took a median of 30 ms and step 3 a median of 7 ms, in a build that took 171 ms in all. Reading the vectors made no measurable difference to either read; the cost is in the rows. It has not been measured on a phone, and the macrobenchmark seeds no reading history, so it does not measure it either.

## How an edition is assembled

`edition/RoomEditionPublisher.kt` gathers the candidates and `engine/EditionBuilder.kt`, a pure function, lays them out. The whole build runs in one database transaction. This section describes the morning edition; the [Evening](#the-evening-edition) and [Extra](#the-extra-edition) editions differ where their sections say.

1. **Relearn affinity**, counting the editions being superseded, and learn the within-feed model from the same evidence (see [Ranking posts within a feed](#ranking-posts-within-a-feed)).
2. **Build the candidate pool.**
   - New posts: everything first seen after the previous morning edition's cutoff, up to now, except stories that an Evening or Extra edition printed in between. With no earlier edition, the last 3 days.
   - Held-over posts: for each loved Weekly or Rare feed, its 5 newest untouched posts from before the previous cutoff.
   - Left out: posts of feeds muted at release time, and new posts published more than 30 days before release (a newly added feed's back catalogue). Held-over posts are exempt from the age limit.
3. **Score** every candidate at the release time, W included, and rank them. Ties go to the newer post.
4. **Deduplicate** stories syndicated across feeds. See [Cross-feed deduplication](#cross-feed-deduplication).
5. **Apply carry-over and the freshness floor.** See [Carry-over and From the archive](#carry-over-and-from-the-archive) and [Scoring](#scoring).
6. **Front page.** The best 5 stories, at most one per feed. The highest score leads, with or without an image.
7. **Top stories.** The next best stories regardless of tag, on up to two pages. Each slot goes to the best remaining story after its score is multiplied by 0.7 for every story from the same feed already on that page (`sameFeedPagePenalty`), so one busy feed cannot fill a page while others have something to offer. With a single feed the page still fills. The penalty only orders the stories: the stored score and every threshold use the story's own score.
8. **Section pages.** One page per tag, then General. The rules:
   - A story from a feed with several tags goes to the tag with the highest tag affinity. If that section is full it spills to the feed's next tag when there is room there. If all its sections are full it is not placed in General; it falls to In brief or the index.
   - A tag with fewer than 2 stories that day gets no page. Its stories move to their other tag, or to General when they have none left.
   - Sections are ordered by tag affinity, highest first. General is always last.
   - General holds stories from untagged feeds and from tags that got no page. With fewer than 2 stories it gets no page either.
   - Stories are placed one at a time with the same-feed penalty of step 7, counted within the section the story would go to. A Favourite firehose filed under a tag shares that tag's page with the tag's other feeds instead of filling it, and with nothing else under its tag it still fills it. The penalty never sends a story to a section its feed is not in: the rules above decide where a story may go, the penalty only who gets the place.
   - A story appears at most once in an edition.
9. **In brief.** Remaining Firehose and Daily posts scoring at least 0.1, as headline-only lines, chosen with the same-feed penalty of step 7, so a busy feed shares In brief with the other fast feeds. A feed's own lines run best first. The 0.1 is held against the story's own score.
10. **From the archive.** One story; see below.
11. **While you were away.** If the reader missed editions, a pull-out page goes in as page 2. See [While you were away](#while-you-were-away).
12. **Index.** Everything else goes in "Also in your feeds" on the back page, grouped by source in alphabetical order.
13. **Freeze and store.** The edition gets the next running number. Reading it never reshuffles it; Likes, reads and skips only shape later editions.

### Cross-feed deduplication

Two candidates are the same story when either of these holds (`EditionBuilder.dedupe`, `engine/StoryKeys.kt`):

- **Same canonical URL.** The scheme, host case, a leading `www.`, a default port, the fragment, a trailing slash and tracking parameters (`utm_*`, `fbclid`, `gclid`, `mc_cid` and similar) are ignored. The path keeps its case and other query parameters count. A bare site address is not a story link. Within one feed the titles must match as well, so a status feed whose posts all link to one page is left alone.
- **Same title, from different feeds.** Titles are compared after lower-casing and removing accents, punctuation and the feed's own name at either end ("… | The Verge"). They must be identical, at least 4 words and 20 characters long, and published within 72 hours of each other.

The title rule is exact on purpose. "Security updates for Tuesday" and "Security updates for Wednesday", or two release announcements one version apart, are different stories, and short titles such as "Links" never match.

Of the copies, the one from the feed with the higher affinity (after the slider) is kept; a tie goes to the better score. A copy the reader has already read wins outright, so the story counts as finished. The other copies are placed nowhere in the edition, not even in the index. They stay on their source pages and in search.

The kept story's reason line ends "; also in 2 other feeds". `DraftItem.alsoInFeeds` carries the number in the engine; it is not stored in a column of its own.

Dropped copies do not come back as new: a later edition only takes posts first seen after the cutoff. A copy of a story that an Evening or Extra edition already printed is dropped from the next morning edition too. One case is not covered: when a dropped copy belongs to a loved Weekly or Rare feed and the reader opens the kept copy, the dropped copy can return later as a held-over post, because nothing records that it was a duplicate.

### While you were away

An edition counts as **missed** when it has been released, the reader never opened it, and it was released after their last visit (the last time they opened any edition) and within the last 30 days. Posts first seen during a gap in which no edition was built need nothing special: they are new candidates for the next build.

When the morning edition is built and there are missed editions, it carries one pull-out page (`ItemKind.AWAY`):

- **Where.** Page 2, straight after the front page. The front page keeps the masthead and today's lead, and the cover and reader code that expect the front page first stay valid.
- **From.** The untouched stories that were printed in the missed editions (not their indexes).
- **Ranked** by the same score, with freshness measured from the reader's last visit instead of the release time. Anything published since that visit counts as brand new. The unopened runs in the missed editions do not count against a story here.
- **Left out.** Firehose feeds, unless the reader Liked or saved one of their posts in the last 30 days. Feeds with the slider below Auto. Muted feeds. Stories already opened. Stories placed elsewhere in this edition (a held-over post keeps its regular place). Copies of the same story. Anything scoring under 0.1.
- **Size.** The top 30 at Standard and Long, 15 at Short. The rest are dropped, not queued.

A story appears in a pull-out that the reader could see at most once: once an edition with a pull-out has been opened, its pull-out stories are never offered again. A pull-out in an edition that was itself missed does not use its stories up. Missed editions cannot pile up, because the page is capped and every edition released before the reader's latest visit stops counting.

A reader who has never opened an edition gets no pull-out. The Evening and Extra editions never carry one.

### Edition structure

| Part | What goes in | Short | Standard | Long |
| --- | --- | --- | --- | --- |
| Front page | Best stories across all feeds, one per feed | 1 lead + 4 | 1 lead + 4 | 1 lead + 4 |
| Top stories (up to 2 pages) | Next best stories, any tag | 4 a page | 8 a page | 12 a page |
| Section pages | One per tag with at least 2 stories, then General | 3 each | 6 each | 9 each |
| In brief | Headlines from Firehose and Daily feeds | 8 lines | 15 lines | 23 lines |
| From the archive | One held-over post | 1 | 1 | 1 |
| While you were away (page 2, only after missed editions) | Best stories of the missed editions | up to 15 | up to 30 | up to 30 |
| Back page | "Also in your feeds": everything not printed | All | All | All |

The length is a setting. It applies to editions built from then on; a stored edition keeps the length it was built with. A page with nothing to put on it is left out, apart from the back page, which is always there.

## Timing

### The reset

`engine/EditionClock.kt` computes the reset instant for a date from the reset time and the device's current time zone. There is one reset per local calendar date. An edition belongs to the date of its reset.

Daylight saving: on the day the clocks go forward, a reset time inside the skipped hour moves to the first valid moment after it. On the day they go back, a reset time that happens twice uses the first.

### Changing a release time

A changed reset time, and likewise a changed Evening edition time, **applies from the next release and never to one that has already come** (`engine/ReleaseSchedule.kt`, a pure class; `edition/ReleaseTimes.kt` is what Settings calls). The latest release at the moment of the change stays the latest release, so whatever was settled for it (printed, nothing new, or failed to build) stays settled, and no edition becomes due, or due again, on the day of the change.

With the reset at 06:30:

| Changed at | New time | Today | Tomorrow |
| --- | --- | --- | --- |
| 09:00 (today's edition has come) | 08:00, earlier and already passed | Stays the 06:30 release. Nothing new is due | 08:00 |
| 09:00 | 10:00, later today | Stays the 06:30 release. No second release at 10:00 | 10:00 |
| 05:00 (today's edition still to come) | 07:00, or 05:30: still ahead today | The edition comes at the new time | The new time |
| 05:00 | 04:00, already passed today | The edition still comes at 06:30: making it due at once would be retroactive, and skipping it would lose a day | 04:00 |
| 05:30, with today's edition already built ahead of time | Any | The built edition is released at 06:30, the time it was built for. It is not re-timed, rebuilt or discarded | The new time |

So there is exactly one release per date across any change. Changing the time again before the first change has taken effect starts from the same settled release.

The mechanism is a small record stored beside each time (`resetChange`, `eveningChange` in the settings store): the release instants on either side of the change, namely the latest release when it was made, a coming release that stays at the old time if there is one, and the first release at the new time. Until that first release the schedule is read from those instants; after it, from the plain time. Keeping the instants (rather than the old time) is what makes the settled release still match its edition and its "nothing new" record.

Everything that asks for a release time goes through the schedule: the publisher (which edition to build, and its release time), the early build's scheduler (which run to queue; it is re-aimed when a time changes), the newsstand (what is due, and the "Next edition at" in its notice) and the back page's "next edition" time.

**Evening edition.** Its time follows the same rule. Switching it on counts as a change: switched on at 19:00 with the time at 18:00, tonight's edition is not due and the first one is tomorrow's. Switching it off takes effect at once.

Not covered: a time changed in the few minutes while the early build is running may or may not see that run's edition as built.

### Early build

Android will not reliably run a job at an exact time, so the edition is built ahead of time and released by a clock check.

- `WorkManagerEditionScheduler` keeps one one-off `EarlyBuildWorker` queued, aimed at 90 minutes before the coming reset, and requiring a network connection. If that moment has already passed, the job runs as soon as it can.
- With the Evening edition switched on, the job aims at whichever release comes next, the morning reset or the evening time, and builds that edition.
- The job (`edition/EarlyBuild.kt`) does a final sync, builds the next edition with `cutoffAt` = now and `releaseAt` = the coming reset, and stores it unreleased. It then prefetches full text for every printed story (briefs included) and puts those stories' pictures in the [image store](#the-image-store), so the edition reads offline. Last, it schedules the next run.
- Each stage is isolated. A failed or slow sync (four-minute limit) does not stop the build, and a failed prefetch does not undo it.
- If the job runs so late that the reset it aimed for has passed, or the next reset is more than 3 hours away, it makes sure the current edition exists instead of freezing tomorrow's paper a day early.
- When its sync reached the feeds and there was still nothing new to print, it writes that down for the release it was for. That record is what separates "nothing new" from a build that failed; see [When an edition failed to build](#when-an-edition-failed-to-build).
- The schedule is renewed on process start, when the reset time or an Evening edition setting changes (see [Changing a release time](#changing-a-release-time) for which release it then aims at), and when the device's clock or time zone changes (`TimeChangeReceiver`).

Anything fetched after the early build belongs to the following edition, because posts are assigned by first-seen time.

### Release

Release needs no job and no build. An edition built ahead of time carries its release time, and the newsstand lists the editions whose release time has passed; it looks again when it comes to the foreground and whenever a release time passes while it is on screen. Opening the app never builds an edition and never touches the network.

Opening the app before the reset time shows the current edition, whose back page gives the time of the next one.

### When an edition failed to build

The early build is best effort. When a scheduled edition is due (its release time has passed) and was not built in the background, the app does not build it silently on open, as it used to. The newsstand shows a **placeholder** in today's place on the rack: an unprinted cover (masthead, date, "Not printed", and dashed outlines where the photo and columns would be) with the line "Today's edition has not been printed." and a **Print now** button beneath it.

**What "failed to build" means** (`edition/DueEditions.kt`, a pure function over what is stored). Once a release time has passed, exactly one of these holds:

| Case | What is stored | The stand shows |
| --- | --- | --- |
| Printed | A released edition of that kind for that date (or a later one) | The cover |
| Nothing new to print: a run reached the feeds, built, and no new post was printable | No edition; the release time is in `nothingNewFor` | The previous paper and "No new edition today" (see below). Not a failure |
| Failed to build | No edition and no such record | The placeholder and Print now |

"Failed to build" therefore covers, without telling them apart: the build threw; the sync reached no feed and nothing stored was printable (the run cannot know there was nothing new); WorkManager never started the run (Doze, a force stop, the device off or without a network); the run started too late or was stopped part-way. The remedy is the same for all of them.

The one thing added to storage for this is `nothingNewFor` in the settings store: the release times (the newest four) for which a run found nothing new. An edition's existence was already stored; a failure needs no record, because it is the absence of both. A sync "reached the feeds" when it finished within its four minutes and at least one feed answered, or there were no feeds to ask.

Special cases:

- **No sources.** Never a failure: nothing can be new. A brand-new install shows the empty stand with "Add sources".
- **Sources but no edition yet** (onboarding skipped, or its first sync failed or found nothing recent). The latest reset is due like any other, so the placeholder shows. After a Print now that reaches the feeds and finds nothing, the empty stand says the sources have had nothing recent and offers "Check for new posts".
- **Onboarding** prints the first edition itself, in the foreground, straight after its first sync, as before. It does not go through Print now and writes no record.
- **Evening edition.** Due from its release time until the next morning reset, and treated the same: a placeholder ("This evening's edition has not been printed.") when it failed to build, nothing said when there was nothing new for it. The newest printed paper settles the day: with the evening edition on the stand, a morning edition missed earlier that day is not asked for (its posts wait for the next morning edition). With no evening edition because nothing was new, the question falls back to the morning one. When both failed, the placeholder asks for the morning edition, and printing it leaves nothing for the evening.
- **Extra editions** are printed on request and are never due. One on the stand does not count as today's paper. While the placeholder is up the offer of an Extra edition is withheld: Print now fetches the new sources too.
- **Time zones.** An edition dated after the latest local reset (after travelling west) counts as printed.
- **Before today's reset** the edition that is due is yesterday's.
- **A release time changed in Settings** never makes an edition due on the day it is changed, and never un-settles one: see [Changing a release time](#changing-a-release-time).

**Print now** (`edition/EditionPrinter.kt`) runs in the foreground with its progress shown where the button was. It runs the early build's own stages (`EarlyBuild.run(BuildTarget.Current)`), not a copy of them:

1. **Sync** every feed ("Fetching 42 feeds…", with the count). Four-minute limit.
2. **Build** the edition that is due from stored posts ("Setting the pages…"): `ensureCurrentEdition()`, which builds the morning edition for the latest reset and, when it is switched on and its time has passed, the evening one. Unlike the background job it never builds the coming edition, however near its release is.
3. **Fetch** the full text of every printed story ("Fetching the stories…", "12 of 40") and then the pictures the pages show. The edition is already stored at this point; the stand keeps the placeholder up until the run ends, so the cover appears with its stories fetched.
4. Schedule the next background run, as the early build does.

How a run ends:

| Result | What the reader sees |
| --- | --- |
| Printed | The cover replaces the placeholder |
| Printed, but no feed could be reached (no network) | The cover, and "Your sources could not be reached, so this edition was printed from the posts already stored." |
| The feeds were reached and nothing was new | The placeholder goes; "Your sources had nothing new to print." and, for the morning edition, the "No new edition today" notice. The release is recorded as nothing new |
| No feed could be reached and nothing stored was printable | The placeholder stays, with "Your sources could not be reached, and nothing new is stored to print. Try again when you are connected." and the button reading "Try again" |
| The build threw | The placeholder stays, with "The edition could not be printed. Try again." and "Try again" |

Each of these notices has a close button (an X: a 48 dp target with its own description, unanimated, so the same in E-ink mode). Closing one forgets the result, in the app-wide printer, so it does not come back on rotation or on returning to the stand; only a later run's result is said. Without it the notice goes at the next release time. The Extra edition's results ("Extra edition printed", "Extra edition reprinted", "Nothing recent from the new sources to print…") have the same button. Closing an error also turns "Try again" back into "Print now"; the placeholder itself stays.

Not dismissible, on purpose: the "No new edition today" notice, which is the standing reason the cover is an old one and goes with the next edition; the offer of an Extra edition, whose "Not now" is its dismissal; and the placeholder. The Subscriptions desk notice is on the paper's back page, not the newsstand, and is unchanged.

A fetch of text or pictures that fails does not undo the edition: it counts as printed, and a story without its text is fetched when it is opened.

The run belongs to the app, not the screen: `EditionPrinter` is held by the app container and works in the app's scope, so it carries on through a rotation, another screen or the app going to the background, and the stand shows where it has got to when it comes back. It cannot be started twice; a second request while one is running does nothing. If the process is killed mid-run the edition, once stored, is on the stand at the next launch; before that the placeholder is back.

The progress display is a line of words and one unanimated bar, redrawn only when a feed or a story finishes, so it is the same in E-ink mode. The placeholder takes the cover's place in all three window classes: above the shelf on a phone and a small tablet, in the left column on a tablet in landscape, with its words capped at 360 dp.

### When there are no new posts

No edition is built unless at least one post first seen since the previous cutoff is printed. If there are no new posts, or they are all muted, too old or already read, nothing is stored. Held-over posts alone do not make a paper. The cutoff stays where it was, the previous edition stays current, and the posts are offered to the next build.

The newsstand then says so: a "No new edition today" notice above the cover names the last paper, gives the time of the next edition and offers a link to check for new posts, and the cover is captioned "Latest edition" with its date instead of "Today's edition". The notice appears only when a run has recorded that there was nothing new for this reset (or there are no sources); otherwise the edition counts as failed to build and the placeholder shows instead. The link runs Print now, which prints today's edition if anything has arrived since.

### The Evening edition

Optional and off by default. Settings has the switch and, when it is on, the release time (18:00 by default).

- **What it covers.** Posts of Firehose and Daily feeds first seen since the latest Morning or Evening cutoff. Weekly and Rare posts are not placed in it at all; they wait for the morning paper. It has no held-over posts.
- **Structure.** A front page (1 lead + 4), In brief (8, 15 or 23 lines by edition length) and the back page with the index. No Top stories, sections, From the archive or While you were away.
- **When it is built.** Ahead of time by the early build, or by Print now after its release time when that did not happen (see [When an edition failed to build](#when-an-edition-failed-to-build)). It is only built while it is current: between its release time and the next morning reset. A missed evening is not built the next day. If the reader first opens the app in the evening and no morning edition exists for the day, the morning edition is built first and takes everything.
- **Nothing lost, nothing printed twice.** The next morning edition's window still starts at the previous *morning* cutoff. It takes everything first seen since then except the stories the evening edition printed. So the Weekly and Rare posts of the day are new in the morning, as are the fast posts the evening edition only listed in its index, and its printed stories do not return.
- **Counters.** Building an evening edition supersedes the morning one, and the next morning edition supersedes the evening one. Each superseded edition's untouched printed stories get one more unopened run, once. With no fast posts to print, no evening edition is stored and nothing is superseded.
- It has its own running number, the date of its release and the kind `EVENING`, and it appears on the newsstand and in back issues like any edition. The back page's "next edition" time is the next release of either kind. The newsstand's "No new edition today" notice still only knows the morning reset.

### The Extra edition

`EditionPublisher.publishExtraEdition(feedIds)` (also on `EditionRepository`) prints an edition at once from chosen feeds, meant for sources the reader has just added. The caller syncs those feeds first.

The newsstand makes the offer (`ui/newsstand/ExtraOffer.kt`). Sources added after the newest edition's cutoff bring up a notice with "Print an Extra edition" and "Not now". Printing syncs those sources and calls `publishExtraEdition`. "Not now" is remembered (`AppSettings.extraOfferDismissedAt`), so the same sources are not offered twice; they join the next scheduled edition as usual. Nothing is offered before the first edition. On a date that already has an Extra edition, sources added after it went to press are offered as a reprint of it (below). Covers and paper headers read "No. 12 · Extra".

- **What it covers.** Those feeds' posts first seen in the last 3 days that no edition has printed. Each feed's 5 newest posts are exempt from the 30-day age limit and the freshness floor, so a quiet blog still shows what it has.
- **Structure.** The same as a morning edition, without held-over posts or a pull-out. Kind `EXTRA`, the next running number, released immediately, dated today.
- **It does not disturb the schedule.** No cutoff moves, today's edition is not changed, and nothing is superseded: today's stories do not count as ignored and the next release time is the same.
- **Afterwards.** In the next morning edition, the stories the Extra printed are not new. A loved Weekly or Rare feed's untouched ones are held over under the usual rules. Posts the Extra only listed in its index are still new. The next scheduled build counts the Extra's unopened runs along with those of the scheduled edition it supersedes.
- **Limits.** It returns null and stores nothing when the feeds have nothing printable. There is at most one Extra edition per calendar date, because the database allows one edition per date and kind.
- **Reprinting.** When sources are added after the day's Extra was printed, the newsstand offers "Reprint the Extra with 3 new sources" (and "Not now"). A reprint is the one case in which a stored edition changes, and it is deliberate:
  - **It replaces the first printing in place.** The paper is set again as a whole from the feeds it had plus the new ones, and stored under the same ID and edition number, with a new cutoff and release time, so it is the newest paper on the stand. It is not the old pages with stories added: the new feeds' stories compete for the front page like any others, and a story of the first printing can lose its place to them or drop to the index.
  - **Reading state is kept.** What the reader did with a story (opened, read, saved, Liked) is stored with the story, not the edition, and is untouched. A story read to the end is not printed again. The edition keeps the time it was first opened and loses its "read" tick, since it has new stories.
  - **Dropped stories.** A story the reprint no longer prints is in no printing any more: it gets no "ran unopened" count and does not count against its feed in learning. Stories the reprint does print are counted once, when the next scheduled edition supersedes it. (If a scheduled edition had already superseded the first printing, its untouched stories were counted then, and those still in the reprint are counted again when the reprint is superseded: once per printing.) A story that led both printings has led once.
  - **The schedule is not disturbed**, exactly as for the first printing: no cutoff moves and nothing is superseded.
  - If the new sources have nothing printable, the first printing is left exactly as it was.
  - No schema change was needed: the old row and its items are deleted and the reprint inserted under the same ID in one transaction.

### Long gaps

If the phone was off or the jobs were held back for days, the next build covers everything first seen since the last cutoff in one edition. Days with no build get no edition; they are not filled in afterwards.

### Time zones

The time zone is read on every call, so a change applies at once. Travelling west can put the latest reset on a date older than the newest released edition; that edition then stays current. Travelling east can put the local reset ahead of the release time of an edition already built for that date; it stays hidden until its own release time and the previous edition remains current.

## Carry-over and From the archive

What happens to a post that was not opened depends on its feed.

| Post | Next edition |
| --- | --- |
| Printed, opened | Never printed again. It leaves the held-over pool at the first open. |
| Printed, untouched, from a feed that is not a loved Weekly or Rare feed | Not a candidate again. It stays in back issues, on its source page and in search. |
| Not printed (listed in the index) | The same. |
| Untouched, from a loved Weekly or Rare feed | Held over: a candidate for every later edition until it is opened, among that feed's 5 newest untouched posts. |

When an edition is built, each untouched printed story of the edition it supersedes gets one more "run unopened", which halves its score through P.

A held-over post competes for the regular pages on its score, with these limits:

- It may lead the front page at most twice.
- Once it has led twice, or its score has dropped below 0.2, it leaves the regular pages and can only return through From the archive.
- A muted feed's held-over posts are skipped and come back, still held over, when the mute ends.

**From the archive** is a page of its own with one story: the best-scoring held-over post that is not already placed in this edition and was not printed in any of the last 3 editions. It gives slow, loved feeds a slot even on heavy news days. If no post qualifies, the page is left out.

## Discovery

Find sources (`ui/discover/`, reached from the Sources screen and from onboarding) suggests feeds the reader does not subscribe to. Everything is decided on the device, from a catalogue that ships with the app; nothing about what the reader reads leaves the device.

### The catalogue

`assets/discovery/catalogue.db` is built offline by the pipeline in `tools/discovery/`. The app reads it read-only with the platform's SQLite API, not through Room (`data/discovery/CatalogueStore.kt`). On first use it is copied to the app's no-backup files. After an app update it is copied again only if its `built_at` or `format_version` has changed.

Its format (`PRAGMA user_version = 1`):

| Table | Holds |
| --- | --- |
| `meta` | `format_version` (`1`), `built_at` (epoch ms), `model_id`, `dims` (`256`), `mean` (256 little-endian float32: the mean of every feed centroid) |
| `cluster` | `id`, `label`, `centroid` (256 int8), `scale` |
| `feed` | `id`, `url`, `url_key`, `site_url`, `host`, `title`, `description`, `icon_url`, `language`, `tier`, `median_interval_hours`, `posts_per_week`, `full_text_share`, `last_post_at`, `cluster_id`, `categories` (one per line), `source`, `quality`, `centroid` (256 int8), `scale` |
| `headline` | `feed_id`, `position` (0 newest), `title`, `url`, `published_at`, `word_count` |

- A vector is `int8 × scale`, normalised.
- A catalogue is refused when its `model_id` is not the app's embedder (`StaticEmbedder.MODEL_ID`), because its vectors cannot be compared with the reader's. One in an unknown format is refused too.
- Without a catalogue, or with a refused one, the screen says "No suggestions available". Nothing crashes.
- The catalogue is English only, with no YouTube channels and no webcomics. The ranking filters these out again.

### The profile

`DiscoveryRepository` builds the profile each time the screen opens, from what is already stored, and keeps nothing:

- **Subscribed feeds.** Each feed's centroid is the normalised mean of its newest 50 post vectors in `article_embedding`. A feed with fewer than 3 is left out.
  - Its weight is `clamp((a − 0.35) / 0.65, 0, 1)`, where `a` is its effective affinity with the slider applied.
  - Less and Rarely weigh nothing; Favourite weighs 1.
- **Posts.** Every post Liked (weight 1) or read (weight 0.5) in the last 180 days, up to 200, whatever its feed.
- **Negatives.** Every passed feed, whose weight halves every 90 days, and every feed the reader unsubscribed from, at full weight for good.
- **Terms.** The reader's tags, and the categories of the posts they read.
- **Exclusions.** Every subscribed feed's address and host, and every feed with a verdict.
  - Some hosts give each user a site of their own: Substack, Medium, WordPress.com, Blogspot, GitHub Pages and others. On these only the exact host is excluded, so one Substack subscription does not hide every Substack.

### The score

All of it is in `engine/Discovery.kt`, which is pure and tested with simulated readers (`DiscoveryTest`).

- **Centring.** Static embeddings crowd into a narrow cone, so raw cosines all look alike. The catalogue's mean is subtracted from every vector before similarities are taken.
- **z-scores.** Each profile point's similarity to a candidate is put on a common scale: `z = (cos − m) / s`. The mean `m` and spread `s` are taken over up to 1,000 evenly spaced catalogue feeds.
- **Topic.**
  - `feedSim` is the mean of the top 3 weighted z-scores against subscribed feeds and picked topics.
  - `postSim` is the mean of the top 5 against posts.
  - `negSim` is the largest against a negative.
  - `topic = 0.6·feedSim + 0.4·postSim − 0.5·max(0, negSim)`. When only one of `feedSim` and `postSim` exists, it takes the place of both.
  - The top few are used rather than a mean, so a reader of woodworking and Rust is shown feeds about either, not feeds about neither.
- **Score.** `topic + ln(cadenceFit) + ln(quality) + 0.15·categoryOverlap`:
  - Cadence fit is 1.0 for Rare and Weekly, 0.85 for Daily and 0.5 for Firehose. It is 0.8 for Firehose when the reader loves a Firehose feed.
  - Quality is 1.0 for full posts or 0.8 for excerpts. It is multiplied by 0.7 when the newest post is more than 45 days old, and by (0.5 + 0.5 × the catalogue's own estimate).
  - Category overlap is the Jaccard overlap of the feed's categories and cluster label with the reader's terms.
- **Filters.** A feed is never shown when it:
  - has no centroid;
  - is already subscribed, or has a verdict;
  - is on an excluded host;
  - is not in English, is on YouTube, or is a comic;
  - is nearly the same as a subscription (raw cosine above 0.97).

### The deck

A week's suggestions are a finite deck of 20 cards, not an endless stack.

- The deck is built once per ISO week and catalogue, with a random generator seeded from both, and kept in `DeckStore`.
- Every opening that week shows the same cards, less those already decided. When all are decided, the screen says so.

How the cards are chosen:

- **MMR.** From the best 200, each card is the one with the best `0.7·relevance − 0.3·max similarity to the cards already picked`. No two cards come from one host.
- **Exploration.** Every fifth card comes from a cluster that none of the reader's feeds sits nearest to, and that was not picked as a topic.
  - The cluster is chosen by Thompson sampling: one draw from `Beta(1 + subscribes, 1 + passes)` per cluster, using the verdicts so far.
  - The card is that cluster's best-scoring feed. Its reason reads "Something different: Astronomy".
- **Cold start.** A reader with no weighted subscriptions and no Liked or read posts is asked to pick topics first, from the catalogue's clusters shown as chips.
  - Picked topics count as subscribed feeds of weight 1.
  - "Browse by topic" builds a deck from picked topics at any time.
- **Reasons.** A card says why it is there:
  - "Close to A and B", naming the subscribed feeds it is nearest;
  - "Because you picked Rust";
  - "Like posts you have Liked", or "Like posts you have read";
  - "Recommended in Woodworking".

### Cards and decisions

A card shows the source's name, its tier, posting rate and host, its description, the reason, and three recent headlines. Each headline opens in the reader.

The decisions are two buttons, Pass and Subscribe:

- There is no swiping or dragging.
- Screen readers get both as actions on the card.
- Nothing animates, so E-ink mode needs nothing special.
- The line under the card says what was done, with Undo.

What each part does:

- **Live headlines.** The card on screen and the next two are fetched (`sync/CandidateFetcher.kt`).
  - Each fetch is capped at 1 MB and kept in `cacheDir/discovery/` for 24 hours.
  - On a metered connection nothing is fetched until the reader taps "Load latest".
  - A fetched feed's centroid is recomputed with the app's embedder from its newest 20 posts. It replaces the catalogue's centroid in the verdict.
  - A feed that answers with an error is dropped from the screen. One that cannot be reached keeps the catalogue's headlines.
- **Pass** is permanent: the feed is never suggested again, and feeds like it are pushed down for a while. The Passed list, in the overflow menu, is the one place to take a pass back.
- **Subscribe** adds the feed as an ordinary subscription and fetches it at once.
- **Undo** restores exactly what was there before. The verdict is deleted, and a feed that the subscription created is removed with its posts and pictures.
- **Unsubscribing** in Sources writes a `DROPPED` verdict with the feed's centroid. The feed is never suggested again, and it counts against feeds like it.

Every decision is a row in `discovery_verdict` (`PASSED`, `SUBSCRIBED` or `DROPPED`), with the feed's cluster and centroid.

### Reading a suggested feed

Opening a headline stores its feed as a **discovered** feed (`feed.status = DISCOVERED`) and the post as an ordinary article. The reader, Save, search and the image store then work as they do for any post.

A "Not subscribed" bar with a Subscribe button appears:

- in the reader, with Save beside it;
- on the source page, which then has no edit or remove;
- under such a post on the Saved shelf.

A discovered feed is not a subscription anywhere. It is never:

- synced;
- in an edition, the health check or the learned affinities;
- in Sources, OPML export, the search filters or the onboarding count.

Every query over `feed` or `article` says which status it wants, and `SubscribedOnlyQueryTest` fails on any that does not. The few that must see every feed are listed in that test with their reasons: the Saved shelf, search results, storage trimming and the archive, among others.

Subscribing a discovered feed turns the same row into the subscription, so its saved posts stay. This happens however the reader subscribes: from discovery, from a Subscribe button, or by adding its address.

Unsaved posts of discovered feeds are deleted 7 days after they were opened, or first stored if never opened (`DiscoveryCleanup`, which runs after every full sync).

- Their pictures are deleted first.
- A discovered feed left with no posts is deleted too.

### From your reading list

A post saved from a discovered feed comes back in the Morning edition, at most once a week.

- The post chosen is the oldest one that is unread and has never run in this slot.
- It gets a page of its own after From the archive, with a Subscribe button under it.
- Its reason says it was saved from a source the reader does not subscribe to.

It is never a candidate for the edition:

- It is placed only after an edition has been built from subscribed feeds.
- It never counts as something new, so a saved discovered post alone never makes an edition.

### Not built

- **The crawler.** `discovery_candidate` is meant to hold feeds found on the device, through the blogrolls and outbound links of subscribed feeds. It is in the schema and has a DAO, but nothing fills it yet.
- **Webcomics.** They are left out until their place in the paper has been thought through.

Both are in `todo.md`.

## What is stored

A single Room 3 database (`data/db/`, schema version 6, exported to `app/schemas/`) on the platform's own SQLite.

| Table | Holds | Key columns |
| --- | --- | --- |
| `feed` | One subscription, or a feed looked into through discovery | `url` (unique), `siteUrl`, `title`, `iconUrl`, `medianIntervalHrs`, `burstiness`, `tier`, `affinity`, `sliderStep`, `muteUntil`, `lastSyncAt`, `etag`, `lastModified`, `addedAt`, `notify` (unused), `status` (`SUBSCRIBED` or `DISCOVERED`), `discoveredAt` |
| `tag` | One tag | `name` (unique), `affinity` |
| `feed_tag` | Which feeds carry which tags | `feedId`, `tagId` |
| `article` | One post | `feedId`, `guid` (unique together), `url`, `title`, `summary`, `imageUrl`, `author`, `publishedAt`, `firstSeenAt`, `wordCount`, `feedWordCount`, `contentHash` |
| `article_content` | A post's body as block-model JSON, uncompressed | `articleId`, `blocksJson`, `source` (`FEED` or `FETCHED`), `storedAt` |
| `engagement` | What the reader did with a post; a row exists only once something happened | `articleId`, `openedAt`, `readFraction`, `readAt`, `savedAt`, `likedAt`, `editionsRunUnopened`, `leadCount` |
| `edition` | One edition | `date` and `kind` (unique together), `cutoffAt`, `releaseAt`, `builtAt`, `length`, `number`, `openedAt`, `finishedAt`, `pageViewsTracked` |
| `edition_page_view` | How long one page of an edition has been on screen, in all | `editionId`, `page` (the two are the primary key), `msVisible`, `firstSeenAt` |
| `edition_item` | A story's place in an edition | `editionId`, `articleId`, `page`, `section`, `slot`, `kind`, `score`, `reason` |
| `feed_health` | Fetch record and health flag per feed | `feedId`, `lastPostAt`, `errorStreak`, `errorSince`, `lastHttpStatus`, `lastFlag`, `flagDismissedUntil`, `lastRepairAt` |
| `article_fts` | Full-text index (FTS4) | `title`, `summary`, `author`, `body` |
| `article_category` | A feed's category for a post | `articleId`, `key` (the two are the primary key), `label` |
| `article_traits` | A post's structural traits | `articleId`, `traitsVersion`, `linkCount`, `imageCount`, `hasCode`, `enclosure`, `isLinkPost`, `titlePrefix` |
| `article_embedding` | A post's text vector, or the mark that it has none | `articleId`, `model`, `vector` |
| `stored_image` | One image kept in the image store | `url` (the key), `articleId`, `fileName`, `byteSize`, `originalBytes`, `width`, `height`, `storedAt` |
| `discovery_verdict` | A decision on a feed: passed, subscribed from discovery, or unsubscribed | `urlKey` (the key), `url`, `verdict`, `decidedAt`, `title`, `clusterId`, `centroid` |
| `discovery_candidate` | A feed found on the device, for a crawler not built yet | `urlKey` (the key), `url`, `source`, `foundVia`, `foundViaTitle`, `title`, `siteUrl`, `host`, `description`, `mentions`, `foundAt`, `fetchedAt`, `tier`, `medianIntervalHrs`, `fullTextShare`, `lastPostAt`, `centroid` |
| `sync_peer` | A paired Kobo (see [Sync with Kobo](#sync-with-kobo-uisettings-kobo-relay)) | `deviceId` (the key), `name`, `tokenHash` (left from local-network pairing, which earlier versions had: the SHA-256 of that token, never checked now, and null for every Kobo paired since), `ackedSeq`, `pairedAt`, `lastSyncAt` (when it last finished its part of a sync session or had events merged; shown as last seen), `relayWidth`, `relayAuthKey` and `relayEncKey` (wrapped with a Keystore key), `relayPairedAt` |

`edition_item.kind` is one of `LEAD`, `FRONT`, `TOP`, `SECTION`, `BRIEF`, `ARCHIVE`, `AWAY`, `READING_LIST` or `INDEX`. All items on a page share a page type. `edition.kind` is `MORNING`, `EVENING` or `EXTRA`.

`article_traits.enclosure` is one of `NONE`, `AUDIO`, `VIDEO` or `OTHER`. The last three tables are described under [Post signals](#post-signals).

Removing a feed deletes its posts, their engagement, their signals, their placements in editions and their stored images, files included.

### Schema changes

Every change to the schema raises the database version and ships a migration (`data/db/Migrations.kt`), so a database from any released version is upgraded with its data in place. A migration is never edited once a release has shipped it. New tables are filled for older databases: by the migration itself where it can be done in SQL, otherwise by an idempotent backfill that runs in the background after the upgrade. `MigrationTest` builds each old version from its exported schema, runs the migrations and compares the result with the current schema.

| Version | Change | How older databases are filled |
| --- | --- | --- |
| 2 | Adds `article_category`, `article_traits` and `article_embedding` | Traits by `TraitsBackfill` and embeddings by `EmbeddingBackfill`, run by `SignalsUpkeep` after app start and after each sync. Categories exist only in the feeds, so the migration clears every feed's `ETag` and `Last-Modified`; the next sync fetches each feed in full and files the posts it already holds. Posts no longer in their feed stay without categories. |
| 2 | Adds `stored_image` | Nothing to fill it from: version 1 kept no images, only what Coil's cache happened to hold. It starts empty. Back issues' pictures are shown from the network as before, and an article's pictures are stored when it is next opened. |
| 2 | Adds `article.feedWordCount` | By the migration: a post whose stored body is the feed's own, or that has none, takes its `wordCount`. A post whose body was fetched from its page takes the word count of its summary, which is the feed's text cut to 400 characters; a longer feed text is undercounted. |
| 3 | Adds `feed.status` and `feed.discoveredAt`, and the tables `discovery_verdict` and `discovery_candidate` | By the migration: every existing feed becomes a subscription, since `status` defaults to `SUBSCRIBED`. The two tables start empty: no decision has been made yet, and the crawler that would fill the candidates is not built. Subscribed feeds need no verdict row, because discovery excludes them by their address. |
| 4 | Adds `edition.pageViewsTracked` and the table `edition_page_view` | Nothing to fill the table from: version 3 recorded nothing about pages, so it starts empty. Instead every existing edition gets `pageViewsTracked` = 0, the cut-over: its stories count as seen, as they always did, so nothing learned before the upgrade changes (see [Affinity learning](#signals)). Every edition built afterwards, a reprinted Extra included, gets 1. |
| 5 | Adds `sync_peer` | Nothing to fill it from: version 4 had no Kobo sync. It starts empty, and a Kobo pairs from the Sync screen. |
| 6 | Lets `sync_peer.tokenHash` be null and adds `relayWidth`, `relayAuthKey`, `relayEncKey` and `relayPairedAt` | The table is rebuilt, since SQLite cannot drop NOT NULL in place, and every paired Kobo copied with its `ackedSeq`. The relay columns start null: version 5 had no relay sync, so every Kobo stays paired over the local network only until its code is scanned. |

Settings are in a Preferences DataStore: reset time, edition length, whether the Evening edition is on and its release time, theme, display mode, whether pictures are shown in greyscale, the storage budget (default 500 MB), and whether onboarding is done. The same store holds two things that are not settings: when the offer of an Extra edition was last turned down, `nothingNewFor`, the last four release times for which a run found nothing new to print (see [When an edition failed to build](#when-an-edition-failed-to-build)), `resetChange` and `eveningChange`, how the last change of each release time takes effect (see [Changing a release time](#changing-a-release-time)), `db_id`, the install's random ID that a Kobo checks, and the Kobo sync's owner secret, the relay address the group was last registered at, and when Publish and Done last finished (see [Sync with Kobo](#sync-with-kobo-uisettings-kobo-relay)). The relay's address and registration token are not settings: they are fixed when the app is built. A migration of the store (`RetiredSettings`) removes, on first read, the keys of settings that are gone: the relay address and token an earlier version let the reader type, and `kobo_image_widths`, from local-network sync. The reader's text size is in its own preferences file.

### The image store

Pictures are kept in the app's files (`files/images/`, not the cache, which Android may clear), re-encoded smaller, with one row each in `stored_image`. The code is in `data/images/`; every number is in `ImageStoreConfig`.

**When an image is stored.**

- When an edition is prefetched by the early build: the lead image and the body images of every printed story, briefs included. Lead images of the stories the pages show come first, so a build that runs out of time has the pages' pictures.
- When an article is opened in the reader, from anywhere (an edition built on open, a back issue, a source page, search, Saved): that article's lead and body images, four seconds after its text is in hand, on the app's scope.
- Not otherwise. A picture passed in a list (a source page, search results, the Saved shelf, a cover) or in an edition that was built on open is shown from the network and held for a while in Coil's own disk cache, as before. Storing every thumbnail scrolled past would fill the budget with pictures of articles nobody opened.
- At most 24 body images per article, and none the feed says is narrower than 80 px (the reader does not show those).

**How it is stored** (`FileImageStore`, `ImageRecompressor`).

1. The original is taken from Coil's disk cache when the picture has just been shown, otherwise downloaded, streamed to a temporary file. Four downloads at a time; 20 MB at most; 30 seconds for each download. Anything slower, larger or failing is skipped, and the build goes on.
2. The file's first bytes say what it is; its address and stated type are not trusted. An error page is not stored.
3. An animated GIF, WebP or PNG and an SVG are never re-encoded. They are stored as they came if they are 2 MB or less, and not stored if they are larger.
4. Everything else is decoded, one image at a time, at the largest power-of-two reduction that keeps its longest edge at 1,280 px or more, then scaled to 1,280 px. A smaller picture keeps its size: nothing is enlarged. A 40-megapixel photograph is never held in full; it costs about 10 MB while it is handled. The EXIF orientation is applied to the pixels.
5. It is written as lossy WebP at quality 80: `WEBP_LOSSY` from Android 11, and before that the older `WEBP` format, which is also lossy below quality 100 and keeps transparency. No picture is flattened onto a background and JPEG is only a fallback should the encoder refuse.
6. If the result is not smaller than the original, the original is kept instead (2 MB or less, as above).
7. The file is named by a hash of the image's address, moved into place and its row written.

1,280 px is the reader's 640 dp column on a tablet at 2× density, the largest slot an image is shown in. On a 1,440 px phone a stored picture is scaled up by an eighth.

**Measured.** `ImageRecompressionMeasurementTest` re-encoded pictures from Wikimedia Commons at three qualities and compared each result with the pixels that were encoded (PSNR over the colour channels; mean SSIM over 8 × 8 blocks of luma). The encoder is Android's as Robolectric's native graphics builds it for a desktop; it has not been run on a device.

| Picture | Original | Stored size | q70 | q80 | q90 |
| --- | --- | --- | --- | --- | --- |
| Sunflower, 1,280 px JPEG | 225 kB | 1,280 × 892 | 89 kB, 35.5 dB, 0.961 | 116 kB, 37.0 dB, 0.967 | 194 kB, 39.4 dB, 0.981 |
| Mountain panorama, 1,600 px JPEG | 460 kB | 1,280 × 575 | 101 kB, 32.9 dB, 0.938 | 137 kB, 34.9 dB, 0.964 | 217 kB, 38.6 dB, 0.987 |
| Eiffel Tower, 1,920 px JPEG | 1,449 kB | 692 × 1,280 | 76 kB, 34.4 dB, 0.969 | 97 kB, 36.2 dB, 0.980 | 149 kB, 38.8 dB, 0.991 |
| Golden Gate Bridge, 2,048 px JPEG | 1,813 kB | 1,280 × 800 | 102 kB, 32.8 dB, 0.960 | 133 kB, 34.3 dB, 0.975 | 207 kB, 36.1 dB, 0.989 |
| Mandelbrot set, 960 px JPEG | 67 kB | 960 × 720 | 31 kB, 38.1 dB, 0.992 | 38 kB, 40.0 dB, 0.995 | 55 kB, 42.6 dB, 0.997 |
| Cat, JPEG larger than 1,280 px | 280 kB | 1,280 × 1,278 | 56 kB, 38.3 dB, 0.969 | 77 kB, 39.5 dB, 0.976 | 146 kB, 42.1 dB, 0.987 |
| Golden Gate Bridge, camera original | 13,849 kB | 1,280 × 800 | 76 kB, 34.1 dB, 0.966 | 100 kB, 35.6 dB, 0.977 | 163 kB, 37.8 dB, 0.990 |
| Photograph with transparency, PNG | 225 kB | 800 × 600 | 66 kB, 34.6 dB, 0.992 | 70 kB, 35.2 dB, 0.993 | 79 kB, 35.8 dB, 0.995 |
| Line chart, PNG | 27 kB | 600 × 436 | 27 kB, larger: original kept | 31 kB, larger: original kept | 39 kB, larger: original kept |
| Tube map, PNG | 100 kB | 1,280 × 730 | 92 kB, 33.9 dB, 0.995 | 108 kB, larger: original kept | 144 kB, larger: original kept |
| Rotating Earth, animated GIF | 1,002 kB | | kept as it came | | |
| Wikipedia logo, SVG | 165 kB | | kept as it came | | |

The five web-sized photographs, which are what feeds mostly carry, came to 10% of their original bytes at quality 70, 13% at 80 and 20% at 90. 80 was chosen. Flat drawings in PNG are often smaller than any lossy copy, which is what rule 6 is for.

**How display finds a stored image** (`StoredImageFetcher`). The app's one Coil loader has a fetcher registered ahead of the network fetcher. For every `http(s)` address it checks whether the store's file for that address exists (one file check, no database read) and hands Coil the file. When there is none it answers nothing and Coil goes on to the network fetcher and its own disk cache. So every picture in the app reads the store first, and no screen knows about it.

**Upkeep.**

- Removing a feed deletes its images' rows and files before the feed itself.
- A sweep after every full sync, and once after the app starts, removes rows whose file is gone, files that have no row, and downloads abandoned more than an hour ago.
- An image belongs to one post, the first it was stored for. A picture two posts share goes with that post and is fetched again for the other.
- The archive does not carry images. An imported library's pictures are fetched as they are shown and stored as its articles are opened.

### Storage budget

The budget (`data/storage/StorageRepository.kt`) governs stored article text (the bodies in `article_content` plus their copy in the search index, measured in characters) and the files of the image store, measured in bytes. Settings shows the two together against the budget and says how much is each. Coil's own disk cache is outside it and keeps to its fixed cap. The budget is the answer to "how long are back issues kept?": there is no fixed period.

The budget is stored in megabytes and runs from 50 MB to 16 GB (16,000 MB). It is turned into bytes as a 64-bit number in one place (`RoomStorageRepository`), because anything from 2,048 MB up does not fit a 32-bit one.

After every full sync, if the two together are over budget they are trimmed down to 90% of the budget:

- **Images go before text.** An image can be fetched again for as long as its site keeps it, and an article reads without it. A post's text often cannot be had again once the post has left its feed, and without it there is nothing to read or search. So every image that may go has gone before the first body is dropped.
- Within each, the oldest editions' posts go first (by first-seen time, which is what assigns a post to an edition).
- An image is dropped as its row and its file together. It is shown from the network the next time it is on screen, and stored again when its article is opened.
- Of text, only the body is dropped, from `article_content` and from the search index together. The post, its headline, summary and image link, its engagement and its place in its editions all stay, so back issues remain browsable and the post is still found by headline and summary.
- Never trimmed, text or images: Liked and saved posts, the stories of the two newest editions, and posts waiting for the next edition.
- A trimmed post opens in the reader with its summary, and the reader fetches the page again as it does for any post without a body.

No post, edition or engagement is ever deleted automatically.

### Archive export and import

`data/archive/` writes the whole library to one file and merges such a file back. The file is a zip of four entries (`ArchiveFormat`): `manifest.json` (format name, format version 2, export time, counts), then `feeds.jsonl`, `posts.jsonl` and `editions.jsonl` with one JSON object per line, so a library of any size is written and read a line at a time. It holds every feed with its tags and slider, every post with its stored body and engagement, and every released edition with its items. A post's line also carries the two signals only its feed could supply again: `categories` (key and label) and `enclosure` (the attachment kind). A post's line also has `feedWordCount`. All three fields were added without raising the format version; a file written before them imports as posts without categories, with `feedWordCount` estimated as the migration to version 2 does. Traits and embeddings are not in the file.

Version 2 adds each feed's `status` and `discoveredAt`, so discovered feeds and their saved posts are carried too. A version 1 file has every feed imported as a subscription. Discovery's verdicts are not in the file.

Import merges:

- A feed is matched by its normalised address.
  - One that is not held is added back with its tags, slider and status.
  - One already held keeps its own name, tags and slider.
  - One subscribed in the file but only discovered here becomes a subscription.
- A post is matched by feed and post ID. One already held is skipped, except that a body it has lost to trimming is put back (and fetched full text replaces a feed excerpt).
- Categories are added to a post that has none, and an attachment kind to a post that has none recorded. Traits and embeddings of imported posts are rebuilt by the next signals upkeep.
- Engagement never overwrites anything set locally: a local time stands, counters only go up, and the archive fills in what is missing. A Like or save removed after the export therefore comes back.
- An edition is matched by date and kind; one already held is skipped. Imported editions keep their number. Page views are not in the file, so an imported edition is untracked (`pageViewsTracked` = 0): its stories count as seen, as before page views were recorded.
- Unknown fields, unreadable lines and unknown enum names are skipped. A file whose format version is higher than this app's is refused.

The file does not carry images, stored or not: they would multiply its size and can be fetched again.

"Export and free up space" trims every image and every body that may be trimmed (the rules above, regardless of the budget) once the file has been written.

## The screens

```
Newsstand ─┬─ Paper ── Reader
           ├─ Back issues ── Paper
           ├─ Search ── Reader
           ├─ Saved / Liked ── Reader
           ├─ Sources ── Source page, Subscriptions desk, Find sources ── Reader, Passed
           └─ Settings ── Sync with Kobo
```

### Newsstand (`ui/newsstand/`)

- The latest released edition as a large cover, the six before it on shelves newest first, and an "Older issues" tile that opens the back-issues calendar.
- A cover is drawn, not a screenshot: masthead, date, lead headline, lead photo and stand-in text lines. It follows the theme and scales to any size. A read edition has a tick.
- The rack is drawn from flat theme colours with shelf edges and soft shadows. There is no texture.
- A plain labelled button bar: Search, Saved, Sources, Settings. Along the bottom on phones and small tablets, a rail down the side on tablets in landscape.
- There is no refresh button and no pull gesture. A sync from the stand could only fetch posts for the next edition, so it showed nothing but a notice; both were removed.
- When the edition that is due failed to build, an unprinted placeholder cover stands in today's place with a Print now button (see [When an edition failed to build](#when-an-edition-failed-to-build)). The covers already printed are racked behind it as usual.
- With no edition yet and no sources, the stand shows a blank cover and offers to add sources.

### Paper (`ui/paper/`)

- A horizontal pager over the edition's pages: front page, Top stories, sections, In brief, From the archive, From your reading list, back page. Each page scrolls vertically when its stories exceed the screen.
- **Pages turn** (`ui/paper/PageTurn.kt`). A page is one sheet hinged at its left edge (the right edge in right-to-left layouts). Dragging lifts the sheet towards the reader and swings it round the spine while the next page lies flat beneath it; the sheet darkens a little as it stands up, the page beneath starts in its shadow, and a thin line marks the sheet's free edge. The free edge stays under the finger, so a half-turned page can be let go and settles whichever way it was heading. Turning back is the same motion in reverse. The sheet is gone when it stands edge-on, so its back is never seen. The hinge stays at the edge on tablets too: a page is a single sheet, not a spread, and a hinge in the middle would fold through the lead story.
  - The geometry is a pure function of how far the pager has moved (`PageTurnMath`), applied in a graphics layer: a turn re-runs no composition, layout or drawing of the page. A page at rest has no transform at all.
  - When the system's animations are switched off (animator duration scale 0) pages slide plainly instead. In E-ink mode they change at once.
- While an edition is being read from the database the folio line and the masthead are already drawn, so opening a paper never shows a blank screen.
- A line across the top has the way back to the stand, the date and edition number, and "Page 3 of 7" over a thin progress bar. Tapping the page count opens a contents list: each page with its first headline and how many stories it carries (In brief counts its lines as briefs).
- Every story has a menu, opened by a long press or the "⋯" at the end of its byline: Save, Like, "Why is this here?" with the stored reason, Show less and Show more (each moves the feed's slider one step), mute the source for 1, 3, 7 or 30 days, and a link to more from the source. Changes shape the next edition; today's stays as printed.
- **Front page**: the masthead (the app's name set modestly, the date, the edition number and the estimated reading time), the lead story, then the secondaries in a grid. A lead with a photo gets the photo, the headline and a two-line standfirst. Without a photo the headline grows, the standfirst runs to four lines, and heavy rules sit above and below.
- **Inside pages**: a page heading, the page's first story with its excerpt, then the rest in the grid. Stories sit on the page divided by thin rules, with no cards or shadows.
- **In brief**: headlines with source names, divided by hairlines.
- **Back page**: "That's today's edition", the time of the next edition, buttons to back issues and search, then "Also in your feeds". Tapping a source name there opens its source page. In one edition a month it also carries the Subscriptions desk notice (see [Feed health](#feed-health)).
- Read stories are greyed and get a tick. Reaching the back page marks the edition as read.
- **Page views** (`ui/paper/PageViews.kt`). While the paper is on screen and the app is in the foreground, the time the settled page has been showing is measured on the monotonic clock and added to `edition_page_view` when the reader turns away, leaves or the app goes to the background. A page half-turned and let go is not counted until it settles. Learning only counts a story that ran unopened when its page was seen (see [Affinity learning](#signals)). Nothing else uses the times; they are not shown anywhere.

### Reader (`ui/reader/`)

- The article rendered natively from the block model, with no WebView: the source name (which opens its source page), headline, author, date, reading time, lead image, body.
- A progress bar, a five-step text size control that is remembered, and a button to open the article in the browser.
- Like and Save switches at the end of the article, then a link to more from the source.
- When no body is stored, the reader shows the feed's summary and says what is happening: the full text is being fetched, it could not be fetched, or the feed gave no text.
- Scroll depth and time spent here are the Read signal.
- **Tapping a picture** (the lead image or one in the body) opens it full screen (`ImageViewer.kt`) and loads the original from its own address, not the smaller stored copy.
  - The copy on the device is shown at once and the original is drawn over it when it arrives. If it does not (no connection, or the site has dropped it), a line says so and the copy stays.
  - Pinch enlarges it up to five times, a drag moves it, a double tap enlarges it and fits it again. It cannot be dragged off the screen. The gestures are the app's own code (`ImageZoom`); no library was added.
  - The arrow or the system's back returns to the article at the place it was left.
  - In E-ink mode nothing follows the finger: Enlarge and Reduce step through 1×, 2× and 4×, and a swipe moves an enlarged picture by 80% of the screen once the finger lifts.
  - The original is decoded no larger than it needs to be to keep its longest edge at 2,048 px or more, so a very large one is not held in full.
- Opening an article puts its pictures in the [image store](#the-image-store).

### Back issues (`ui/backissues/`)

A month calendar of every released edition, read ones ticked, with the month's editions listed by lead headline: beneath the calendar, or beside it on a tablet in landscape.

### Sources (`ui/sources/`)

- The list of feeds, each with its tier, how often it posts, its host, its tags and the 5-step slider (Rarely, Less, Auto, More, Favourite). Chips at the top filter by tag, Untagged or Muted. A muted feed shows when the mute ends, with Unmute.
- Feed detail, opened from the pencil on a row or from the source page: slider, tag editor, mute, rename, open the website, and remove with confirmation.
- Add a feed by address; import and export OPML from the overflow menu.
- A source page lists the feed's 200 most recent posts in date order, with its tags.
- Find sources (see [Discovery](#discovery)) is linked from the header, the overflow menu, the empty list, and onboarding.
- Feeds that have not posted in 90 days are listed last, under a Quiet heading. When a feed needs attention the header links to the Subscriptions desk, which is always in the overflow menu.
- **Subscriptions desk** (`SubscriptionsDeskScreen.kt`): flagged feeds grouped by flag, each group with a line of explanation and each feed with its one-tap fixes (see [Feed health](#feed-health)). Unsubscribe asks first. With nothing flagged it says "All in order".

### Saved and search

- **Saved** (`ui/saved/`): two shelves, Saved and Liked, most recent first, each with a remove button and Undo. A post saved from a feed the reader does not subscribe to has a Subscribe button under it.
- **Search** (`ui/search/`): one field, full-text search over every stored post, newest first, with a matching excerpt. Each word is a prefix and all must match.
  - **Filters** narrow the search: when (any time, past week, month or year), Saved only, Liked only, one tag, one source. A line under the field has a Filters button that says how many are set, and each active filter as a token that removes it. The button opens the controls in place of the results; on a tablet in landscape they stay open in a column beside the results. The source is chosen from a list in a dialog.
  - A filter needs a word to search for: with filters set and nothing typed the screen says so. With filters set and nothing found it offers to clear them.
  - `SearchFilters` holds the state and turns it into the repository's `SearchQuery`.

### Settings (`ui/settings/`)

Reset time, edition length (Short, Standard, Long), the Evening edition (Off or On, with its release time when on), theme (System, Light, Dark), display mode (Auto, Standard, E-ink) and pictures (Colour, Greyscale).

- **Storage**: the budget for stored text and pictures, moved one step at a time (50, 100, 250, 500 MB, 1, 2, 4, 8, 16 GB) with Less and More, a bar and "Using 132 MB of 500 MB" beneath, then "12 MB of text, 120 MB of pictures". Over budget it says so and offers "Trim now".
- **Archive**: Export, Export and free up space, and Import, through the system file picker (no storage permission). The job runs in the app's scope, so it carries on if the screen is left; progress and the result ("Imported 1,240 posts, skipped 310.") show under the buttons.

- **Kobo**: Sync with Kobo opens the screen below.

Debug builds add buttons that load or clear invented sample data.

### Sync with Kobo (`ui/settings/`, `kobo/`, `relay/`)

A Kobo e-reader can carry the latest editions and read them in its own reader. It syncs through a relay server (`relay/` at the top of the repository), so the Kobo and the phone need not be on the same Wi-Fi. They must be online at the same time: a sync is one session on the phone's Sync screen, and a Kobo syncs while it is open. The protocol is [`docs/relay-sync.md`](relay-sync.md), relay/1, and the Kobo's side lives in `kobo/`. The phone owns the database: the Kobo only downloads, then sends back what the reader did there. The relay is a mailbox: everything it holds is encrypted with keys only the phone and the Kobos have, and it holds nothing between sessions.

Earlier versions also synced over the local network, with the phone serving the Kobo while this screen was open ([`docs/kobo-sync.md`](kobo-sync.md), protocol v1). The phone no longer does; relay sync carries v1's manifest, edition bundles, saved shelf and events unchanged (`kobo/KoboProtocol.kt`, `kobo/BundleExport.kt`, `kobo/EventMerge.kt`), and only the way they travel differs.

- **The relay** is built into the app: its address is `https://mirelay.tgnra.net` unless the build is given another (the Gradle property `morningissue.relayAddress` or the variable `MORNINGISSUE_RELAY_ADDRESS`), and its registration token comes only from the build (`morningissue.relayToken` or `MORNINGISSUE_RELAY_TOKEN`, a CI secret), never from the repository. The app has no setting for either. The address must be `https://`, because the phone sends the relay keys; a bare host name gets `https://`, and debug builds also accept `http://` for `localhost` and the emulator's host.
- **The screen** is reached from Settings. It shows the relay's address, which the Kobo reader has built in as well (`"relay"` in the Kobo's `config.json` overrides it), Scan a Kobo's code, a field to paste the code instead, the paired Kobos with a Forget button each and when each was last seen, and **Start**. Last seen is recorded on the phone: the end of the Kobo's last completed exchange in a session. During a session the screen shows its progress, each Kobo as waiting, downloaded or changes collected, **Finish now** and **Cancel**, and a plain result at the end ("Brought back 12 changes from the Kobos."). It keeps the display on and tells the reader to keep the screen open.
- **A session** ([`docs/relay-sync.md`](relay-sync.md), "Phone: session"). Start builds the package, creates the group on the relay, uploads the package in parts, sends the device list and publishes. The Kobos are listed only once the package is uploaded, so none syncs too early. The phone then polls the relay every few seconds; a Kobo uploads its changes before it marks the package fetched, so as soon as it has fetched, the phone downloads and merges its changes. When every paired Kobo has fetched and been merged, the phone deletes the group. Finish now merges what has arrived and deletes the group; Kobos that didn't connect get nothing. Cancel deletes the group without merging; the Kobos keep those changes and send them again next time. Any failure deletes the group best effort. If the app goes to the background, the session is cancelled: the phone deletes the group at once, with an expedited WorkManager job as a backup in case the process dies first. Between sessions the relay holds nothing for this phone, not even its keys or the device list. The first run of the version that brought sessions deletes the group an older version left behind.
- **Pairing.** The Kobo shows a QR code holding its secret, `deviceId`, name and screen width. The phone reads it with CameraX and ZXing's core library, so no Google Play services are needed; the camera permission is asked for when Scan is tapped, and without it the code's text can be pasted. Scanning is the consent. The phone derives the Kobo's two keys, keeps them wrapped with an AES key in the Android Keystore (`sync_peer`), and starts a session straight away; the Kobo, which asks the relay while its code is shown, is listed once the package is uploaded and then syncs. Scanning a Kobo again keeps its `ackedSeq`, and so does scanning one an earlier version paired over the local network, whose row is kept for that. Forget deletes the row and nothing else: there is nothing on the relay to remove, and the next session doesn't list that Kobo. If the app's data is restored onto another phone, the Keystore key does not come with it: such a Kobo is marked, and must be scanned again.
- **Keys.** The phone makes an owner secret once and keeps it with the settings; the group on the relay is named after the key derived from it. Every request is signed with HMAC-SHA256, so no secret crosses the network. A refused clock is corrected from the relay's `Date` and the request tried once more, and a repeated signature is signed again with a fresh time. The group is registered at the start of every session from the owner secret and the Kobos' keys the phone keeps, so a relay that has been wiped needs nothing more.
- **What a Kobo gets** (built when Start is pressed): the full text of saved articles that lack it is fetched first (up to two minutes). The package then holds the manifest with `protocol`, `builtAt` and each Kobo's `acked`; the three newest editions already released (never one waiting for its release time), each as one JSON bundle with its pages, its stories' stored text as block JSON, and the phone's read, Liked and saved state; every saved article, in the same form, newest saved first; and every picture those name, prepared for e-ink once at each paired Kobo's width. The zip is encrypted as it is written, with a fresh key, into one file in the app's cache, which is streamed to the relay and deleted. The key is then wrapped for each Kobo and the package published, after the device list is sent.
- **Pictures** (`kobo/KoboImages.kt`, `kobo/GreyImage.kt`). The image store holds the pictures of the stories an edition prints, not those of the back-page index, which is most of an edition's articles, nor of saved articles from older editions. So the phone fetches from the internet what the store lacks, through Coil's disk cache first, with the store's client, 20 MB limit and 30-second timeout, 3 downloads at a time. Each picture is decoded (an animation's first frame), laid on white, turned upright by its EXIF tag, made 8-bit grey, reduced by area averaging to at most the Kobo's width (the short side of its screen, 1264 by default; never enlarged; at most 4 million pixels), and written as a one-component greyscale JPEG at quality 80, or as a greyscale PNG when its 16 commonest tones cover nine tenths of its pixels, which is line art, charts and screenshots. Both encoders are written here in Kotlin: Android's JPEG encoder always writes three components. One download serves every width. Results are cached in the app's cache directory by address and width, up to 256 MB, least recently used out first, so the next package finds most of them ready. SVG is not drawn and is left out.
- **What comes back** (merged during the session) is a list of events: opened, how far read, read, Liked or un-Liked, saved or unsaved, time a page was on screen, an edition opened or finished. When a Kobo has fetched the package, the phone lists the relay's batches of changes, downloads that Kobo's and decrypts each with its key. A batch that does not decrypt, names another Kobo, or has a `seq` outside its range is dropped. The rest are merged by `KoboEventMerger`, one Kobo's batches in `seq` order: only events above its `ackedSeq`, in one transaction with the new `ackedSeq`, so a batch sent twice changes nothing. First-open and read times keep the earlier, the read fraction keeps the larger, Likes and saves follow the Kobo's latest change, and page time adds to `edition_page_view`, so a page read on the Kobo counts as seen exactly as on the phone. When a batch changes what affinity learns from (opened, read, saved, Liked), affinity is relearned in the same transaction, as for the phone's own reader. An event about a post or edition the phone no longer has is dropped. Batches are not deleted one by one: they go with the group at the end of the session. One that could not be downloaded or merged is sent again by the Kobo in a later session, since the Kobo keeps its events until a package acknowledges them.
- **When.** Only in a session, started with Start or by scanning a Kobo's code. There is no background relay activity: neither the early build, Print now nor the periodic sync talks to the relay, and the package is built when Start is pressed.
- **`dbId`** is a random ID made once per install and kept with the settings, and sent in every package's manifest. A Kobo that sees a different one (the app was reinstalled, so its row IDs start again) wipes what it holds and starts over.
- A Kobo syncs only while a session is open on the phone. Outside one, the relay answers it as it answers an unpaired Kobo, so a Kobo that has synced before shows one message: the phone isn't syncing (open Sync on the phone and press Start, then sync again), or this Kobo was removed from the phone and must be paired again.
- A Kobo's changes reach the phone only in a session, so events that arrive after the next build have already counted their stories as unopened for that build.

## Themes, tablets and e-ink

### Themes

Every screen is built from theme tokens (`ui/theme/Color.kt`), so all three themes work everywhere.

- **Light**: warm off-white paper. The default when the system is in light mode.
- **Dark**: the "Night edition", true black.
- **E-ink**: pure black on white; overrides both.

Type is three bundled open-licence variable fonts: Fraunces for headlines, Newsreader for body text and Inter for labels.

### Greyscale pictures

Settings has Pictures: Colour or Greyscale. Greyscale shows every picture in grey, in any theme, and dithers it. E-ink mode does the same whatever the setting says, and raises the contrast by 1.3 as well (`ImageTreatment.EINK_CONTRAST`).

- **Where it happens** (`ui/components/ImageTone.kt`). `PaperImage`, which draws every picture in the app, adds a Coil transformation to its request and asks for the bitmap at exactly the size it is shown. The transformation turns each pixel to its Rec. 709 luma, applies the contrast, and dithers. It works on the pixels the screen will light, which a colour filter cannot do. Stored images stay in colour: this is display only, so switching the option off downloads nothing.
- **At once.** The transformation's key is part of Coil's memory-cache key, so a picture in another tone is another cache entry: changing the option redraws every picture on screen, and changing it back finds the colour ones still cached.
- **The dither** is ordered: a 4 × 4 Bayer matrix, to 16 grey levels (`Dither.MATRIX_SIZE`, `Dither.LEVELS`). Each pixel is compared with a fixed threshold that depends only on its position, so the pattern stays still: a page turned back to looks as it did. Error diffusion would redraw the whole texture for a one-pixel change in the crop.
  - 16 levels are the 16 greys an e-ink panel has, so the panel's own rounding never runs. In sample pictures a photograph at 16 levels reads as a photograph with a fine grain in skies and soft backgrounds; at 8 a sky is visibly dotted; at 4 the pattern covers the picture. On a phone's dense screen the grain at 16 is faint.
  - The 8 × 8 matrix gave the same pictures as 4 × 4 at 16 levels, and 2 × 2 left bands in a gradient.
  - A grey that is exactly on a level, black and white included, is left flat.
- **The full-screen original** is greyscale too (with E-ink's contrast in E-ink mode) but not dithered: it is drawn scaled, and a pattern made for one size smears at another.
- Covers' mastheads, rules and the other drawn furniture are not images and are not touched. A cover's lead photograph is.

`DitherSamplesTest` writes before-and-after pictures through this code path when given a folder of pictures.

### Tablet layouts

`ui/layout/PaperLayoutSpec.kt` makes every size-dependent decision once per window.

| Window | Paper grid | Newsstand | Reader |
| --- | --- | --- | --- |
| Compact (phone in portrait) | 2 columns | Cover above one sideways-scrolling shelf; button bar | Full width |
| Medium (small tablet, phone in landscape) | 3 columns | Cover above two shelves; button bar | Text column capped at 600 dp, centred |
| Expanded (tablet in landscape) | 4 columns; the lead spans 2 with secondaries beside it | Cover on the left, a grid of shelves on the right; button rail | Text column capped at 640 dp |

Also on Expanded: the paper's contents list opens as a panel beside the page instead of over it, search keeps its filters in a column beside the results, and back issues sets the month's editions beside the calendar.

A grid row that comes up short is spread over the page's full width. One or two stories left over are set as a headline with its excerpt and a small photo at the side; three are set as text alone, because three such cells are too narrow for the photo as well.

### E-ink mode

The display mode setting is Auto, Standard or E-ink. Auto picks E-ink when the device's manufacturer or brand matches a list of e-ink makers (`ui/theme/EinkDevices.kt`: Onyx Boox, Likebook, Meebook, Bigme, PocketBook, Kobo, Supernote and others). A manual choice always wins.

In E-ink mode:

- **Colour**: black on white, no shadows or tints. Body text is one weight heavier.
- **Motion**: none. No ripples, no transitions between screens, no animated scrolling.
- **Movement is paged.** Dragging does nothing until the finger lifts; then the content jumps one screen in the direction of the swipe, snapped to a story row or text line with a one-line overlap. In the paper a sideways swipe turns a whole page. Volume keys, page keys, arrow keys and space step forward and back. Previous and Next buttons appear under the content; in the paper they step through a long page before turning it.
- **Images** are greyscale with raised contrast, dithered to 16 greys (see [Greyscale pictures](#greyscale-pictures)). Small thumbnails are dropped in favour of text. A picture opened full screen is enlarged in steps and moved a screen at a time.
- The phone shelf wraps instead of scrolling sideways.
- Print now's progress is words and an unanimated bar, as everywhere.
- **Code blocks** cannot scroll sideways, so they are set smaller, just far enough for the longest line to fit (down to 70% of the usual size). Only lines longer than that wrap.

## Architecture

Kotlin and Jetpack Compose in a single `:app` module with a single Activity. There is no internet service, and nothing listens.

```
net.tgnra.morningissue
  model/     plain data classes and enums shared by every layer
  data/      db (Room 3 entities, DAOs, migrations), repositories, AffinityLearner,
             WithinFeedLearner, settings (DataStore), storage (budget and trimming),
             images (the image store, re-encoding, the Coil fetcher that reads it),
             archive (export and import), signals (TraitsBackfill, EmbeddingBackfill,
             SignalsUpkeep)
  embedding/ pure Kotlin apart from EmbedderAsset: StaticEmbedder, WordPieceTokenizer,
             EmbeddableText
  sync/      FeedFetcher, parsers, FeedDiscovery, FullTextFetcher, OPML, FeedSyncer, SyncWorker,
             FeedHealthChecker, FeedRepairer
  engine/    pure Kotlin, no Android imports: RankingConfig, Cadence, Scorer, Affinity,
             WithinFeed, EditionBuilder, EditionClock, ReleaseSchedule, FeedHealthRules
  edition/   RoomEditionPublisher, EarlyBuild, EarlyBuildWorker, WorkManagerEditionScheduler,
             TimeChangeReceiver, DueEditions (pure: did the due edition fail to build?),
             EditionPrinter (Print now), ReleaseTimes (changing a release time)
  kobo/      what a Kobo gets and sends back: KoboProtocol (v1's bodies), BundleExport, EventMerge,
             KoboImages and GreyImage (pictures for e-ink), InstallId
  relay/     how they travel, the Kobo sync proper: RelayCrypto (HKDF, MIR1 envelope, signing), KeyWrapper, RelayPairingUri,
             QrDecoder, RelayClient, RelayPackageWriter, RelayCleanupWorker, RelaySettings, RelaySync
  ui/        theme, layout, navigation, components, newsstand, paper, reader, backissues,
             sources, saved, search, settings, onboarding
```

- **Dependency injection** is hand-written. `AppContainer.kt` holds one instance per process of everything the UI and workers depend on, including the embedder asset and the signals upkeep. `SyncModule` and `EditionModule` wire their own layers.
- **Data flow.** Sync and the reader only write to the database. The publisher reads it, runs the pure engine and stores a frozen edition. The UI observes repositories as Flows and never touches DAOs.
- **The engine is pure.** It takes plain data and an instant, so every ranking and layout rule is unit-tested on the JVM.
- **Background work** is WorkManager: two periodic sync requests and one one-off early build. The signals upkeep is not a job of its own: it runs inside each full sync and once on the app's own scope after process start.

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Storage | Room 3 with an FTS4 table; DataStore for settings |
| Background | WorkManager |
| Network | OkHttp |
| Feed parsing | Own `XmlPullParser` parser for RSS and Atom; kotlinx.serialization for JSON Feed |
| Full text | Readability4J and Jsoup, converted to the block model |
| Images | Coil 3, reading the app's own image store first |
| QR codes | CameraX for the picture, ZXing's core library to read it; no Google Play services |

`minSdk` is 26, `targetSdk` 36 and `compileSdk` 37. The permissions are `INTERNET` and `CAMERA`. The camera is only for scanning a Kobo's pairing code, and is asked for when Scan is tapped; a phone without one can still install the app and paste the code.

## Building, testing and releasing

You need JDK 21 and the Android SDK (platform 37).

```sh
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests (JVM and Robolectric)
./gradlew connectedAndroidTest   # on-device tests; needs a device or emulator
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest   # the edition build benchmark; replaces the installed app and its data
```

**Benchmark.** The `:macrobenchmark` module measures the budget "building an edition from stored posts takes under 300 ms on a low-end phone". That build was the on-open fallback; it is now the middle stage of Print now and of a late background run, and the budget, the trace section and the class (`OnOpenEditionBuildBenchmark`) keep their names. It runs against the app's `benchmark` build type: the release build made profileable and signed with the debug key, plus a seeding receiver (`app/src/benchmark`) that only the shell can call. Each iteration stores 1,000 unread posts from 40 feeds, with their traits and embeddings, and no edition or reading history, and starts the app cold. Opening the app no longer builds anything, so the benchmark then sends the receiver a second broadcast (`--ez print true`) that calls `ensureCurrentEdition()` in the running app, and measures the trace section `MorningIssue.onOpenEditionBuild`, which wraps the build in `RoomEditionPublisher.ensureCurrentEdition`. It does not press Print now, because that would first sync the seeded feeds, which do not exist. The changed trigger compiles but has not been run. Results are written under `macrobenchmark/build/outputs/connected_android_test_additional_output/`. Numbers from an emulator are only indicative, and it has not yet been run on a phone. To run it without Gradle, install `app-benchmark.apk` and `macrobenchmark-benchmark.apk` and start `net.tgnra.morningissue.macrobenchmark/androidx.test.runner.AndroidJUnitRunner` with `am instrument -w` (add `-e androidx.benchmark.suppressErrors EMULATOR` on an emulator).

Unit tests are in `app/src/test`: the engine (scoring, cadence, affinity, within-feed ranking, edition building, sections, carry-over, the clock), the publisher and early build against an in-memory database and `work-testing`, the sync layer against saved feed fixtures and a mock web server, the embedder against the real asset and reference vectors from the original model, the migrations against the exported schemas, the image store against a local web server and Robolectric's native graphics (real decoding and WebP encoding, on the desktop), the greyscale option through a real Coil loader, the Kobo sync's bundles and merge rules against an in-memory database, the build's relay address and the removal of the old typed one, relay sync's cryptography against every vector in `docs/relay-sync-fixtures/` and against tampered, truncated and reordered envelopes, its pairing codes and QR reading, and Publish and Done end to end against a small relay of the tests' own on a mock web server, and the pure parts of the UI. `KoboFixturesTest` writes `docs/kobo-sync-fixtures/`, the sample manifest and bundle the Kobo side parses. The debug sample data has traits and embeddings (the upkeep runs after it is loaded) but no categories.

**Ranking simulation.** `RankingSimulationTest` (`app/src/test/.../simulation/`) has simulated readers use the app for 180 days: 14 real feeds saved on 2026-11-11 (`app/src/test/resources/simulation/`, with a README) replayed by a seeded generator at their own cadences, synced by the production syncer through an OkHttp interceptor in place of the network, embedded with the real potion-base-8M asset, built by the production early build at 05:00 and released at 06:30, and read through the article and edition repositories, with missed days, a week's holiday, front-page-only visits, Likes, saves and a change of taste on day 120. Five personas (a Scotland reader, a hobbyist, a tech reader, a drifter and a lapsed reader) and a reader who opens stories at random are checked with thresholds and orderings measured after a 30-day warm-up; the run is deterministic. It takes 1.5 to 2 minutes, four personas at a time. `-PsimulationReport` writes a report per persona to `app/build/reports/simulation/`, showing how affinity, W and the front page moved. Simulated readers record page views like the paper: on a full visit every page is on screen for a couple of seconds plus a few per story, with one page in ten after the front flicked past; a front-page-only visit views the front page alone; a missed day views nothing. Three checks the simulation first found failing are now kept as tests, each with the measurements before and after its fix: a week away no longer lowers a loved feed's affinity (it lowered it by about a third while every unseen page counted as ignored), a slow feed read every time becomes loved (the prior outweighed its whole record), and a link blog's own articles come first on most days that also have link posts.

**Continuous integration.** Every push and pull request runs `.github/workflows/tests.yml`: the unit tests, the ranking simulation among them, with the simulation's reports kept as a workflow artifact.

**Releases.** `master` is the default branch. Pushing a tag that starts with `v` (for example `v0.1.0`) runs `.github/workflows/release.yml`, which runs the unit tests, builds a signed release APK and attaches it to a GitHub release for that tag. The version name is the tag without its `v`. Running the workflow by hand builds the APK as a workflow artifact without publishing a release.

Signing is configured in `app/build.gradle.kts` only when `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are all set in the environment. The workflow takes them from repository secrets, with the keystore itself in `KEYSTORE_BASE64`.

## Not built yet

These are described in [`todo.md`](../todo.md) with the detail needed to build them.

- **Planned:** nothing from the three milestones is left unbuilt. The smaller gaps against the original spec are in `todo.md`.
- **Built, not proven:** ranking posts within a feed (the W factor) has not been validated on real reading history or run on a device. The open items are in `todo.md`.
- **Engine built, screen missing:** the Extra edition has no button, the comeback label is not shown as a badge, and the While you were away page has no "12 posts from 7 feeds" summary line.
- **Built, not seen:** the image store, the full-screen picture and the greyscale option have not been run on a device. Nor has the Kobo sync: the scanner, the Keystore wrapping and the screen are untested on a phone, and the phone has not yet synced through the real relay with a real Kobo. Nor has discovery. Its ranking is tested on simulated readers only, and the app has not yet run with the real catalogue.
- **Out of scope for now:** notifications, compression of stored content, and PDF export.

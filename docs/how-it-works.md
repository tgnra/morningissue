# How Morning Issue works

This describes the app as the code on this branch behaves today. Work that is planned but not built is listed in [`todo.md`](../todo.md); where this document mentions it, it says so.

## Contents

1. [The idea](#the-idea)
2. [What the reader sees](#what-the-reader-sees)
3. [Terms](#terms)
4. [Feeds, sync and cadence](#feeds-sync-and-cadence)
5. [Scoring](#scoring)
6. [Affinity learning](#affinity-learning)
7. [How an edition is assembled](#how-an-edition-is-assembled)
8. [Timing](#timing)
9. [Carry-over and From the archive](#carry-over-and-from-the-archive)
10. [What is stored](#what-is-stored)
11. [The screens](#the-screens)
12. [Themes, tablets and e-ink](#themes-tablets-and-e-ink)
13. [Architecture](#architecture)
14. [Building, testing and releasing](#building-testing-and-releasing)
15. [Not built yet](#not-built-yet)

## The idea

Morning Issue is an RSS reader that publishes one personal newspaper a day. Each morning's edition is finite: a front page, two pages of top stories, a page per tag, a column of briefs and a back page. When you reach the back page you are done for the day. There is no endless scroll and there are no unread counts.

Two rules decide what gets space:

- **Freshness is relative.** A post is old or new compared with how often its feed posts. Two days is ancient for a news site and brand new for a blog that posts monthly.
- **Scarcity earns space.** A rare post from a feed you care about gets room that a news wire's post does not.

Nothing is thrown away. A post that does not make the paper is still listed on the back page, and every edition and every fetched post stays on the phone, browsable from back issues, source pages and search.

Everything runs on the device. There is no account and no server; the only network traffic is fetching feeds, article pages and images.

## What the reader sees

1. **First launch.** Onboarding asks for feeds, by address or by importing an OPML file. It then fetches every feed, builds the first edition and opens the newsstand. This is the only time the app waits on the network.
2. **The newsstand** is the home screen: the latest edition facing out, the six before it racked behind, and four labelled buttons (Search, Saved, Sources, Settings).
3. **The paper.** Tapping a cover opens that edition. Pages turn with a horizontal swipe, from the front page to the back page, which says "That's today's edition" and when the next one is due.
4. **The reader.** Tapping a story opens it in the paper's typography, with Like and Save at the end.
5. **The next morning**, at the reset time (06:30 by default), a new edition takes over. Until then the edition never changes, whatever arrives in the feeds.

## Terms

| Term | Meaning |
| --- | --- |
| Edition | One day's paper. Once built it is frozen: its stories and their order never change. |
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

## Feeds, sync and cadence

### Adding feeds

- **By address** (`sync/DefaultFeedDiscovery.kt`). The reader types a feed URL, a site URL or a bare host. Discovery tries, in order: the address itself as a feed; the feeds the page declares with `<link rel="alternate">` (or `rel="feed"`), at most 8, with comment feeds sorted last; then the common paths `/feed`, `/rss`, `/atom.xml`, `/index.xml`, `/feed.xml` and `/rss.xml` on the site. Every feed offered has been fetched and parsed. If several are found the reader chooses.
- **By OPML import** (`sync/Opml.kt`, `sync/DefaultOpmlTransfer.kt`), from Sources or onboarding, through the system file picker. The reader accepts outlines nested to any depth. Every enclosing folder name becomes a tag, as does each entry of an OPML 2.0 `category` attribute; a feed listed in several folders is added once with all their tags. Feeds already subscribed are skipped, and the result is reported as "Added 38, skipped 4".
- **OPML export** writes OPML 2.0: untagged feeds at the top level, then one folder per tag. A feed with several tags appears in each of its folders.

Two subscriptions are the same feed when their normalised URLs match (`model/FeedUrls.kt`): scheme, a leading `www.`, default ports, fragments and a trailing slash are ignored.

### Sync

`DefaultFeedSyncer` fetches every feed, six at a time, and stores the posts it has not seen before with first-seen time set to now. Syncing never builds or changes an edition.

| When | What runs |
| --- | --- |
| Every 2 hours on any connection, unless the battery is low | Periodic `SyncWorker` (`periodic-sync`) |
| Every hour while charging on an unmetered network | A second periodic `SyncWorker` (`periodic-sync-charging`) |
| Pull to refresh, or the refresh button, on the newsstand | The same sync; reports "12 new posts saved for tomorrow" |
| After adding a feed or importing OPML in Sources | That feed, or all feeds |
| About 90 minutes before the reset time | The early build's final sync (see [Timing](#timing)) |
| Onboarding | The first sync |

WorkManager's periodic work is inexact and can be delayed by Doze, so these are intervals, not guarantees. Runs that overlap share one sync. Opening the app never syncs, apart from first launch.

Fetching (`sync/FeedFetcher.kt`) uses the shared OkHttp client with conditional requests (`ETag` and `Last-Modified`), a 45-second timeout and an 8 MB size limit. Plain `http` feeds are allowed (`usesCleartextTraffic` in the manifest). A permanent redirect (301 or 308) updates the stored feed address.

Parsing (`sync/FeedParser.kt`, `XmlFeedParser.kt`, `JsonFeedParser.kt`) reads RSS 2.0, 0.9x and 1.0, Atom and JSON Feed. The format is decided from the document, not its content type. For each post:

- **Identity** is the feed's own ID, else the post's link, else a hash of title and date. `(feedId, guid)` is unique, so a post is stored once.
- **Date** is the feed's publish date, else its updated date, else the fetch time. Dates in the future are clamped to the fetch time.
- **Title** falls back to the opening words of the text for untitled posts.
- **Lead image** is taken from media tags, then an image enclosure, then the first image in the body that is not icon-sized.
- **Body** HTML is converted to the app's block model (paragraphs, headings, images, quotes, lists, preformatted text) and stored as JSON. A plain-text summary of up to 400 characters and a word count are stored with the article.

Each fetch also updates the feed's row in `feed_health`: a success clears the error streak and records the latest post date; a failure adds to the streak. Nothing reads these yet (see [Not built yet](#not-built-yet)).

### Full text

Many feeds carry only an excerpt. `DefaultFullTextFetcher` downloads the article's page, extracts the text with Readability4J and stores it as blocks. It is called when the reader opens an article and by the early build for every printed story. Feed text counts as complete, and the page is not fetched, when it has at least 250 words and does not end like a teaser ("…", "read more", "continue reading"). The page is fetched at most once per article.

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
score = A × F × S × P × C
```

Multiplying means any factor near zero sinks the post: affinity cannot rescue stale news, and a post the reader has finished never returns.

| Factor | Formula | Notes |
| --- | --- | --- |
| A, affinity weight | `0.3 + 0.7 × affinity` | `affinity` is the feed's learned affinity after the slider. The 0.3 floor keeps disliked feeds from vanishing. |
| F, freshness | `0.5 ^ (age / h)`, with `h = clamp(1.5 × medianInterval, 12 h, 240 h)` | `age` is measured at the edition's release time, not when the reader opens it. A post dated after release counts as brand new. |
| S, scarcity | `min(1.8, 1 + 0.15 × log2(1 + medianInterval / 24 h))` | About 1.00 for a firehose, 1.15 for a daily feed, 1.45 for a weekly one, 1.74 for a monthly one. |
| P, seen penalty | `0.5 ^ editionsRunUnopened`; 0 once the post is read | Halves for each edition the post was printed in without being opened. |
| C, comeback boost | 1.5 for a feed's first post after 90 quiet days; otherwise 1 | See [Welcome back](#welcome-back). |

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

Worked examples, all untouched posts scored at release time:

| Post | Affinity | Age at release | A | F | S | Score |
| --- | --- | --- | --- | --- | --- | --- |
| Favourite weekly blog | 0.9 | 7 days | 0.93 | 0.62 | 1.45 | **0.83** |
| News site (firehose), neutral | 0.5 | 3 h | 0.65 | 0.84 | 1.00 | 0.55 |
| Daily newsletter, so-so | 0.3 | 6 h | 0.51 | 0.89 | 1.15 | 0.52 |
| News site (firehose), neutral | 0.5 | 12 h | 0.65 | 0.50 | 1.00 | 0.33 |
| Daily newsletter, neutral | 0.5 | 3 days | 0.65 | 0.25 | 1.15 | 0.19 |

The week-old post from the blog you love beats this morning's news.

**Freshness floor.** A new post whose F has dropped below 0.05 (`RankingConfig.minFreshness`) is not printed. That is about 52 hours for a firehose and six and a half days for a daily feed; Weekly and Rare feeds reach the 30-day age limit first. The post is still listed in the back-page index. Held-over posts are exempt, as are the newest posts of each feed in an Extra edition.

Each printed story also stores a one-line reason (`Scorer.reason`), for example "You read most posts from Daring Fireball", "Rare post: this blog publishes about once a month" or "New from The Verge since the last edition". The line is stored in `edition_item.reason` and shown by "Why is this here?" in the story menu.

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

Affinity is learned from what the reader does with printed stories (`engine/Affinity.kt`, `data/repository/AffinityLearner.kt`). There is no scroll tracking, skip or dismiss: the exposure is "the story was printed in an edition", and being printed without being opened is the only negative signal.

### Signals

| Signal | How it is recorded | Weight |
| --- | --- | --- |
| Open | The article is opened in the reader | 1 |
| Read | Scrolled through at least 70% of the body, or kept open for at least half its estimated reading time (230 words a minute, at least one minute) | 2 |
| Save | The Save button at the end of the article | 2 |
| Like | The Like button at the end of the article | 3 |
| Ran unopened | The story was printed and never touched | Exposure with no credit |

### The calculation

Every event is weighted by its age with a 30-day half-life: `w = 0.5 ^ (age in days / 30)`. Events older than 180 days are not read at all.

For one article:

```
points   = 1·w(opened) + 2·w(read) + 2·w(saved) + 3·w(liked)
positive = points / 3
exposure = max( sum of w over the editions it ran in, largest w of its engagement events )
```

`positive` is in units of "a post opened and read": an open alone is worth a third, a read post 1, a read and Liked post 2. A Like or Save is therefore extra credit that can make up for a post that was ignored. A post read outside an edition (from a source page, search or an old back issue) counts as one exposure at the time it was read.

With a prior of 10 pseudo-exposures:

```
tag affinity  = (10 × 0.5   + positive over its feeds) / (10 + exposure over its feeds)
feed affinity = (10 × prior + positive)                / (10 + exposure)
prior         = mean affinity of the feed's tags, or 0.5 for an untagged feed
```

Both are clamped to 0..1. A feed with no evidence sits on its prior. Ten read posts in a row lift an untagged feed to 0.75; ten ignored ones drop it to 0.25.

**Tag borrowing** is that prior: a new or rarely seen feed starts at the average of its tags' affinity, and its own record takes over smoothly as exposures accumulate.

### When it runs

Nothing is kept as a running total. Each pass recomputes every feed and tag from the `edition_item` and `engagement` rows, so undoing a Like cannot leave the numbers out of step. A pass runs:

- when an edition is built, so the builder ranks with current values;
- when a story's opened, read, saved or Liked state changes;
- when tags or subscriptions change.

An unopened story only counts against its feed once its edition has been superseded by the next build. Today's paper does not count against a feed while it can still be read.

The slider is not part of learning. It is applied on top, in `Affinity.effective`.

## How an edition is assembled

`edition/RoomEditionPublisher.kt` gathers the candidates and `engine/EditionBuilder.kt`, a pure function, lays them out. The whole build runs in one database transaction. This section describes the morning edition; the [Evening](#the-evening-edition) and [Extra](#the-extra-edition) editions differ where their sections say.

1. **Relearn affinity**, counting the editions being superseded.
2. **Build the candidate pool.**
   - New posts: everything first seen after the previous morning edition's cutoff, up to now, except stories that an Evening or Extra edition printed in between. With no earlier edition, the last 3 days.
   - Held-over posts: for each loved Weekly or Rare feed, its 5 newest untouched posts from before the previous cutoff.
   - Left out: posts of feeds muted at release time, and new posts published more than 30 days before release (a newly added feed's back catalogue). Held-over posts are exempt from the age limit.
3. **Score** every candidate at the release time and rank them. Ties go to the newer post.
4. **Deduplicate** stories syndicated across feeds. See [Cross-feed deduplication](#cross-feed-deduplication).
5. **Apply carry-over and the freshness floor.** See [Carry-over and From the archive](#carry-over-and-from-the-archive) and [Scoring](#scoring).
6. **Front page.** The best 5 stories, at most one per feed. The highest score leads, with or without an image.
7. **Top stories.** The next best stories regardless of tag, on up to two pages. Each slot goes to the best remaining story after its score is multiplied by 0.7 for every story from the same feed already on that page, so one busy feed cannot fill a page while others have something to offer.
8. **Section pages.** One page per tag, then General. The rules:
   - A story from a feed with several tags goes to the tag with the highest tag affinity. If that section is full it spills to the feed's next tag when there is room there. If all its sections are full it is not placed in General; it falls to In brief or the index.
   - A tag with fewer than 2 stories that day gets no page. Its stories move to their other tag, or to General when they have none left.
   - Sections are ordered by tag affinity, highest first. General is always last.
   - General holds stories from untagged feeds and from tags that got no page. With fewer than 2 stories it gets no page either.
   - A story appears at most once in an edition.
9. **In brief.** Remaining Firehose and Daily posts scoring at least 0.1, best first, as headline-only lines.
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

### Early build

Android will not reliably run a job at an exact time, so the edition is built ahead of time and released by a clock check.

- `WorkManagerEditionScheduler` keeps one one-off `EarlyBuildWorker` queued, aimed at 90 minutes before the coming reset, and requiring a network connection. If that moment has already passed, the job runs as soon as it can.
- With the Evening edition switched on, the job aims at whichever release comes next, the morning reset or the evening time, and builds that edition.
- The job (`edition/EarlyBuild.kt`) does a final sync, builds the next edition with `cutoffAt` = now and `releaseAt` = the coming reset, and stores it unreleased. It then prefetches full text for every printed story (briefs included) and the images its pages show, so the edition reads offline. Last, it schedules the next run.
- Each stage is isolated. A failed or slow sync (four-minute limit) does not stop the build, and a failed prefetch does not undo it.
- If the job runs so late that the reset it aimed for has passed, or the next reset is more than 3 hours away, it makes sure the current edition exists instead of freezing tomorrow's paper a day early.
- The schedule is renewed on process start, when the reset time or an Evening edition setting changes, and when the device's clock or time zone changes (`TimeChangeReceiver`).

Anything fetched after the early build belongs to the following edition, because posts are assigned by first-seen time.

### Release and the on-open fallback

Release needs no job. Whenever the newsstand comes to the foreground it calls `EditionPublisher.ensureCurrentEdition()`:

- If a released edition exists for the date of the latest reset, it is shown.
- Otherwise the edition is built on the spot from stored posts, with `cutoffAt` = now and `releaseAt` = the latest reset. This never touches the network.

With the Evening edition switched on, the same call then makes sure the evening edition exists once its release time has passed (see below). It returns the newest released edition of any kind.

The newsstand also notices when a stored edition's release time passes while it is on screen.

Opening the app before the reset time shows the current edition, whose back page gives the time of the next one.

### When there are no new posts

No edition is built unless at least one post first seen since the previous cutoff is printed. If there are no new posts, or they are all muted, too old or already read, nothing is stored. Held-over posts alone do not make a paper. The cutoff stays where it was, the previous edition stays current, and the posts are offered to the next build.

The newsstand then says so: a "No new edition today" notice above the cover names the last paper, gives the time of the next edition and offers a link to check for new posts, and the cover is captioned "Latest edition" with its date instead of "Today's edition". The notice only appears once the on-open check has finished.

### The Evening edition

Optional and off by default. Settings has the switch and, when it is on, the release time (18:00 by default).

- **What it covers.** Posts of Firehose and Daily feeds first seen since the latest Morning or Evening cutoff. Weekly and Rare posts are not placed in it at all; they wait for the morning paper. It has no held-over posts.
- **Structure.** A front page (1 lead + 4), In brief (8, 15 or 23 lines by edition length) and the back page with the index. No Top stories, sections, From the archive or While you were away.
- **When it is built.** On open after its release time, as the morning edition is, or ahead of time by the early build. It is only built while it is current: between its release time and the next morning reset. A missed evening is not built the next day. If the reader first opens the app in the evening and no morning edition exists for the day, the morning edition is built first and takes everything.
- **Nothing lost, nothing printed twice.** The next morning edition's window still starts at the previous *morning* cutoff. It takes everything first seen since then except the stories the evening edition printed. So the Weekly and Rare posts of the day are new in the morning, as are the fast posts the evening edition only listed in its index, and its printed stories do not return.
- **Counters.** Building an evening edition supersedes the morning one, and the next morning edition supersedes the evening one. Each superseded edition's untouched printed stories get one more unopened run, once. With no fast posts to print, no evening edition is stored and nothing is superseded.
- It has its own running number, the date of its release and the kind `EVENING`, and it appears on the newsstand and in back issues like any edition. The back page's "next edition" time is the next release of either kind. The newsstand's "No new edition today" notice still only knows the morning reset.

### The Extra edition

`EditionPublisher.publishExtraEdition(feedIds)` (also on `EditionRepository`) prints an edition at once from chosen feeds, meant for sources the reader has just added. The caller syncs those feeds first. No screen calls it yet.

- **What it covers.** Those feeds' posts first seen in the last 3 days that no edition has printed. Each feed's 5 newest posts are exempt from the 30-day age limit and the freshness floor, so a quiet blog still shows what it has.
- **Structure.** The same as a morning edition, without held-over posts or a pull-out. Kind `EXTRA`, the next running number, released immediately, dated today.
- **It does not disturb the schedule.** No cutoff moves, today's edition is not changed, and nothing is superseded: today's stories do not count as ignored and the next release time is the same.
- **Afterwards.** In the next morning edition, the stories the Extra printed are not new. A loved Weekly or Rare feed's untouched ones are held over under the usual rules. Posts the Extra only listed in its index are still new. The next scheduled build counts the Extra's unopened runs along with those of the scheduled edition it supersedes.
- **Limits.** It returns null and stores nothing when the feeds have nothing printable. There is at most one Extra edition per calendar date, because the database allows one edition per date and kind; a second request on the same date returns null.

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

## What is stored

A single Room 3 database (`data/db/`, schema version 1, exported to `app/schemas/`) on the platform's own SQLite.

| Table | Holds | Key columns |
| --- | --- | --- |
| `feed` | One subscription | `url` (unique), `siteUrl`, `title`, `iconUrl`, `medianIntervalHrs`, `burstiness`, `tier`, `affinity`, `sliderStep`, `muteUntil`, `lastSyncAt`, `etag`, `lastModified`, `addedAt`, `notify` (unused) |
| `tag` | One tag | `name` (unique), `affinity` |
| `feed_tag` | Which feeds carry which tags | `feedId`, `tagId` |
| `article` | One post | `feedId`, `guid` (unique together), `url`, `title`, `summary`, `imageUrl`, `author`, `publishedAt`, `firstSeenAt`, `wordCount`, `contentHash` |
| `article_content` | A post's body as block-model JSON, uncompressed | `articleId`, `blocksJson`, `source` (`FEED` or `FETCHED`), `storedAt` |
| `engagement` | What the reader did with a post; a row exists only once something happened | `articleId`, `openedAt`, `readFraction`, `readAt`, `savedAt`, `likedAt`, `editionsRunUnopened`, `leadCount` |
| `edition` | One edition | `date` and `kind` (unique together), `cutoffAt`, `releaseAt`, `builtAt`, `length`, `number`, `openedAt`, `finishedAt` |
| `edition_item` | A story's place in an edition | `editionId`, `articleId`, `page`, `section`, `slot`, `kind`, `score`, `reason` |
| `feed_health` | Fetch record per feed | `feedId`, `lastPostAt`, `errorStreak`, `errorSince`, `lastHttpStatus`, `lastFlag`, `flagDismissedUntil` |
| `article_fts` | Full-text index (FTS4) | `title`, `summary`, `author`, `body` |

`edition_item.kind` is one of `LEAD`, `FRONT`, `TOP`, `SECTION`, `BRIEF`, `ARCHIVE`, `AWAY` or `INDEX`. All items on a page share a page type. `edition.kind` is `MORNING`, `EVENING` or `EXTRA`.

Removing a feed deletes its posts, their engagement and their placements in editions.

Settings are in a Preferences DataStore: reset time, edition length, whether the Evening edition is on and its release time, theme, display mode, and whether onboarding is done. A storage budget (default 500 MB) is also stored, but nothing reads it yet. The reader's text size is in its own preferences file.

Images are not in the database; they are in Coil's disk cache.

Nothing is trimmed or deleted automatically: every post and edition is kept.

## The screens

```
Newsstand ─┬─ Paper ── Reader
           ├─ Back issues ── Paper
           ├─ Search ── Reader
           ├─ Saved / Liked ── Reader
           ├─ Sources ── Source page, Subscriptions desk
           └─ Settings
```

### Newsstand (`ui/newsstand/`)

- The latest released edition as a large cover, the six before it on shelves newest first, and an "Older issues" tile that opens the back-issues calendar.
- A cover is drawn, not a screenshot: masthead, date, lead headline, lead photo and stand-in text lines. It follows the theme and scales to any size. A read edition has a tick.
- The rack is drawn from flat theme colours with shelf edges and soft shadows. There is no texture.
- A plain labelled button bar: Search, Saved, Sources, Settings. Along the bottom on phones and small tablets, a rail down the side on tablets in landscape.
- Pull to refresh, or the refresh button in the header, fetches posts for the next edition and reports how many were saved. It never changes today's edition.
- With no edition yet, the stand shows a blank cover and offers to add sources or refresh.

### Paper (`ui/paper/`)

- A horizontal pager over the edition's pages: front page, Top stories, sections, In brief, From the archive, back page. Pages slide; each page scrolls vertically when its stories exceed the screen.
- A line across the top has the way back to the stand, the date and edition number, and "Page 3 of 7" over a thin progress bar. Tapping the page count opens a contents list.
- Every story has a menu, opened by a long press or the "⋯" at the end of its byline: Save, Like, "Why is this here?" with the stored reason, Show less and Show more (each moves the feed's slider one step), mute the source for 1, 3, 7 or 30 days, and a link to more from the source. Changes shape the next edition; today's stays as printed.
- **Front page**: the masthead (the app's name set modestly, the date, the edition number and the estimated reading time), the lead story, then the secondaries in a grid. A lead with a photo gets the photo, the headline and a two-line standfirst. Without a photo the headline grows, the standfirst runs to four lines, and heavy rules sit above and below.
- **Inside pages**: a page heading, the page's first story with its excerpt, then the rest in the grid. Stories sit on the page divided by thin rules, with no cards or shadows.
- **In brief**: headlines with source names, divided by hairlines.
- **Back page**: "That's today's edition", the time of the next edition, buttons to back issues and search, then "Also in your feeds". Tapping a source name there opens its source page.
- Read stories are greyed and get a tick. Reaching the back page marks the edition as read.

### Reader (`ui/reader/`)

- The article rendered natively from the block model, with no WebView: the source name (which opens its source page), headline, author, date, reading time, lead image, body.
- A progress bar, a five-step text size control that is remembered, and a button to open the article in the browser.
- Like and Save switches at the end of the article, then a link to more from the source.
- When no body is stored, the reader shows the feed's summary and says what is happening: the full text is being fetched, it could not be fetched, or the feed gave no text.
- Scroll depth and time spent here are the Read signal.

### Back issues (`ui/backissues/`)

A month calendar of every released edition, read ones ticked, with the month's editions listed beneath by lead headline.

### Sources (`ui/sources/`)

- The list of feeds, each with its tier, how often it posts, its host, its tags and the 5-step slider (Rarely, Less, Auto, More, Favourite). Chips at the top filter by tag, Untagged or Muted. A muted feed shows when the mute ends, with Unmute.
- Feed detail, opened from the pencil on a row or from the source page: slider, tag editor, mute, rename, open the website, and remove with confirmation.
- Add a feed by address; import and export OPML from the overflow menu.
- A source page lists the feed's 200 most recent posts in date order, with its tags.
- The Subscriptions desk screen exists as a placeholder that says the feed health check is not built.

### Saved and search

- **Saved** (`ui/saved/`): two shelves, Saved and Liked, most recent first, each with a remove button and Undo.
- **Search** (`ui/search/`): one field, full-text search over every stored post, newest first, with a matching excerpt. Each word is a prefix and all must match. Filters are not built.

### Settings (`ui/settings/`)

Reset time, edition length (Short, Standard, Long), the Evening edition (Off or On, with its release time when on), theme (System, Light, Dark) and display mode (Auto, Standard, E-ink). Debug builds add buttons that load or clear invented sample data.

## Themes, tablets and e-ink

### Themes

Every screen is built from theme tokens (`ui/theme/Color.kt`), so all three themes work everywhere.

- **Light**: warm off-white paper. The default when the system is in light mode.
- **Dark**: the "Night edition", true black.
- **E-ink**: pure black on white; overrides both.

Type is three bundled open-licence variable fonts: Fraunces for headlines, Newsreader for body text and Inter for labels.

### Tablet layouts

`ui/layout/PaperLayoutSpec.kt` makes every size-dependent decision once per window.

| Window | Paper grid | Newsstand | Reader |
| --- | --- | --- | --- |
| Compact (phone in portrait) | 2 columns | Cover above one sideways-scrolling shelf; button bar | Full width |
| Medium (small tablet, phone in landscape) | 3 columns | Cover above two shelves; button bar | Text column capped at 600 dp, centred |
| Expanded (tablet in landscape) | 4 columns; the lead spans 2 with secondaries beside it | Cover on the left, a grid of shelves on the right; button rail | Text column capped at 640 dp |

On Expanded, the paper's contents list opens as a panel beside the page instead of over it.

### E-ink mode

The display mode setting is Auto, Standard or E-ink. Auto picks E-ink when the device's manufacturer or brand matches a list of e-ink makers (`ui/theme/EinkDevices.kt`: Onyx Boox, Likebook, Meebook, Bigme, PocketBook, Kobo, Supernote and others). A manual choice always wins.

In E-ink mode:

- **Colour**: black on white, no shadows or tints. Body text is one weight heavier.
- **Motion**: none. No ripples, no transitions between screens, no animated scrolling.
- **Movement is paged.** Dragging does nothing until the finger lifts; then the content jumps one screen in the direction of the swipe, snapped to a story row or text line with a one-line overlap. In the paper a sideways swipe turns a whole page. Volume keys, page keys, arrow keys and space step forward and back. Previous and Next buttons appear under the content; in the paper they step through a long page before turning it.
- **Images** are greyscale with raised contrast. Small thumbnails are dropped in favour of text.
- Pull to refresh is replaced by the header button, the phone shelf wraps instead of scrolling sideways, and code blocks wrap instead of scrolling.

## Architecture

Kotlin and Jetpack Compose in a single `:app` module with a single Activity. There is no server.

```
net.tgnra.morningissue
  model/     plain data classes and enums shared by every layer
  data/      db (Room 3 entities, DAOs), repositories, AffinityLearner, settings (DataStore)
  sync/      FeedFetcher, parsers, FeedDiscovery, FullTextFetcher, OPML, FeedSyncer, SyncWorker
  engine/    pure Kotlin, no Android imports: RankingConfig, Cadence, Scorer, Affinity,
             EditionBuilder, EditionClock
  edition/   RoomEditionPublisher, EarlyBuild, EarlyBuildWorker, WorkManagerEditionScheduler,
             TimeChangeReceiver
  ui/        theme, layout, navigation, components, newsstand, paper, reader, backissues,
             sources, saved, search, settings, onboarding
```

- **Dependency injection** is hand-written. `AppContainer.kt` holds one instance per process of everything the UI and workers depend on. `SyncModule` and `EditionModule` wire their own layers.
- **Data flow.** Sync and the reader only write to the database. The publisher reads it, runs the pure engine and stores a frozen edition. The UI observes repositories as Flows and never touches DAOs.
- **The engine is pure.** It takes plain data and an instant, so every ranking and layout rule is unit-tested on the JVM.
- **Background work** is WorkManager: two periodic sync requests and one one-off early build.

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Storage | Room 3 with an FTS4 table; DataStore for settings |
| Background | WorkManager |
| Network | OkHttp |
| Feed parsing | Own `XmlPullParser` parser for RSS and Atom; kotlinx.serialization for JSON Feed |
| Full text | Readability4J and Jsoup, converted to the block model |
| Images | Coil 3 |

`minSdk` is 26, `targetSdk` 36 and `compileSdk` 37. The only permission is `INTERNET`.

## Building, testing and releasing

You need JDK 21 and the Android SDK (platform 37).

```sh
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests (JVM and Robolectric)
./gradlew connectedAndroidTest   # on-device tests; needs a device or emulator
```

Unit tests are in `app/src/test`: the engine (scoring, cadence, affinity, edition building, sections, carry-over, the clock), the publisher and early build against an in-memory database and `work-testing`, the sync layer against saved feed fixtures and a mock web server, and the pure parts of the UI.

**Releases.** `master` is the default branch. Pushing a tag that starts with `v` (for example `v0.1.0`) runs `.github/workflows/release.yml`, which runs the unit tests, builds a signed release APK and attaches it to a GitHub release for that tag. The version name is the tag without its `v`. Running the workflow by hand builds the APK as a workflow artifact without publishing a release.

Signing is configured in `app/build.gradle.kts` only when `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are all set in the environment. The workflow takes them from repository secrets, with the keystore itself in `KEYSTORE_BASE64`.

## Not built yet

These are described in [`todo.md`](../todo.md) with the detail needed to build them.

- **Planned:** search filters, the feed health check and Subscriptions desk, the storage budget, archive export and import, the 3D page turn, and a benchmark for the on-open build.
- **Engine built, screen missing:** the Extra edition has no button, the comeback label is not shown as a badge, and the While you were away page has no "12 posts from 7 feeds" summary line.
- **Out of scope for now:** notifications, compression of stored content, and PDF export.

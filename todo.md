# To do

What is built, what is being built and what is left. How the built parts work is in [`docs/how-it-works.md`](docs/how-it-works.md). This file carries the specification for everything not built yet, so each item has the rules and numbers needed to build it.

## Done

- [x] **Foundations.** Gradle, Kotlin and Compose setup; single Activity; light, dark and e-ink themes; bundled fonts; layout spec for phone and tablet; navigation graph; Room schema; hand-written dependency container.
- [x] **Sync.** RSS, Atom and JSON Feed parsing; conditional fetching; feed discovery from a site address; full-text extraction; periodic background sync; cadence and tiers.
- [x] **OPML import and export** in Sources and onboarding; every ancestor folder becomes a tag.
- [x] **Scoring**: A × F × S × P with all constants in `RankingConfig`.
- [x] **Daily edition**: front page, Top stories, In brief, back-page index; early build with prefetch; on-open fallback; frozen once stored.
- [x] **No empty or reprint-only editions**: with no new posts nothing is built and the previous edition stays current.
- [x] **Newsstand** with drawn covers, rack and labelled button bar; **paper** pager with contents list; **reader** with Like, Save, text size and read tracking; **back issues** calendar; **source pages**; **Saved and Liked** lists; basic **search**; **Settings**; **onboarding** with first sync.
- [x] **Tablet layouts** (Compact, Medium, Expanded) and **e-ink mode** (paged movement, no animation, greyscale images).
- [x] **Room 3** migration.
- [x] **Cleartext HTTP** allowed, for feeds that only serve `http`.
- [x] **Release signing** and a tag-triggered release workflow (see Known rough edges: it has not run yet).
- [x] **Learning, engine and data half**: affinity learning from opens, reads, Saves and Likes; tag affinity and tag borrowing; slider caps and floors applied in scoring; mutes respected by the builder; section pages; held-over posts; lead at most twice; From the archive; a stored reason line per story.

## Done: learning, user interface

Merged. Checked on a phone emulator with real feeds; see Known rough edges for what was not checked.

- [x] **Long-press story menu** on every story block: Save, Like, Show more and Show less (each moves the feed's slider one step), Mute for 1, 3, 7 or 30 days, and Why is this here?
- [x] **Why is this here?** shows the story's stored reason line (`edition_item.reason`), with Show more and Show less beside it as the correction point.
- [x] **5-step slider in Sources**: Rarely, Less, Auto, More, Favourite, with Auto in the centre.
- [x] **Tags in Sources**: add and remove tags on a feed.
- [x] **Feed detail**: slider, tags, mute, rename and remove for one feed in one place.
- [x] **Saved and Liked shelves**: finish the lists and show Liked and Saved marks on story bylines.
- [x] **Section and From the archive pages**: visual polish now that the builder produces them.
- [x] **"No new edition today" notice** on the newsstand when the latest edition is not today's because there were no new posts.

## Milestone 3: polish

In progress. The edition engine's part is built; how it works is in `docs/how-it-works.md`.

### While you were away

- [x] If the reader skips one or more days, the next morning edition carries a pull-out page (page 2) with the best stories from the missed editions: ranked from the last visit, Firehose feeds only after a recent Like or save, slider at Auto or above, top 30 (15 at Short), each story at most once.
- [ ] A summary line on the page such as "While you were away: 12 posts from 7 feeds". The page model has the stories; the line is not drawn.

### Search with filters

- [x] Filters on the search screen: one tag, one source, when (past week, month or year), Saved only and Liked only, with the active ones shown as removable tokens.
- [ ] The date filter is a choice of periods, not a from-and-to range. `SearchQuery` takes both ends; a date picker in the paper's furniture has not been drawn.
- [ ] A filter cannot be browsed without a word to search for, because `ArticleRepository.search` returns nothing for blank text. Listing "everything Saved from this source last month" needs a repository change.

### Cross-feed deduplication

- [x] Collapse posts that share a canonical URL or have an identical normalised title (the same story syndicated across feeds), keeping the copy from the higher-affinity feed. The kept story's reason line says "also in 2 other feeds".
- [ ] Show "also in 2 feeds" on the story itself. The count is only in the reason line; a field on the story would need a column in `edition_item`.
- [ ] A dropped copy from a loved Weekly or Rare feed can come back as a held-over post once the reader opens the kept copy. Fixing it needs somewhere to record that a post was dropped as a duplicate.
- `article.contentHash` (a hash of the normalised title and text) is not used by the dedupe step.

### Feed health check and the Subscriptions desk

Built; see "Feed health" in `docs/how-it-works.md` for the rules as implemented. Not yet seen on a device.

- [x] Health rules as a pure `engine` component (`FeedHealthRules`), run after sync.
- [x] **Subscriptions desk** page with one-tap fixes, always available from Sources. It never unsubscribes on its own and says "All in order" when every feed is healthy.
- [x] **Carried by the edition once a month**, as a notice on the back page of one edition per calendar month (not a page of its own), only while a feed needs attention.
- [x] Dismissing a flag hides it for 6 months (`feed_health.flagDismissedUntil`).
- [x] **Automatic repair** of a Broken feed before the reader is asked: retry, scheme and `www` variants, the site's own feed link, then `/feed`, `/rss`, `/atom.xml`, `/index.xml`. Tried again weekly.
- [x] **Quiet feeds.** A feed with no posts in 90 days moves to a Quiet group at the bottom of Sources and is checked every 6 hours, then daily, every 3 days and weekly as the quiet lengthens.
- [x] **Welcome back, engine.** When a feed posts again after 90 quiet days, the story gets the reason line "Back after 2 years: …" and a comeback boost of 1.5 on top of scarcity, so it almost always makes the front page.
- [ ] **Welcome back, label.** Show `EditionStory.comebackLabel` on the story block.
- Differences from the original table: there is no "In brief only" setting for a feed, so Noisy offers Less; Loved offers Favourite only (notifications are out of scope).

### Storage budget and trimming

Built; see "Storage budget" in `docs/how-it-works.md`. Not yet seen on a device.

- [x] A storage budget control in Settings (discrete steps, 50 MB to 4 GB) showing current usage.
- [x] When the budget fills, the stored text of the oldest editions' articles is trimmed first, after a sync. Liked and saved posts are never trimmed; headlines, summaries and edition rows stay.
- The budget counts stored text only. Cached images are in Coil's disk cache, which has its own fixed cap and is not managed by the budget.
- Content stays uncompressed (see Out of scope).

### Archive export and import

Built; see "Archive export and import" in `docs/how-it-works.md`. The file-picker path has not been run on a device.

- [x] Export the whole library (feeds, posts with text and engagement, released editions) to one versioned zip of JSON lines through the Storage Access Framework, optionally freeing space afterwards.
- [x] Import merges into the current database: posts matched by feed address and post ID are skipped (trimmed text is put back), missing feeds are re-added with their tags, editions are matched by date and kind, and local engagement is never overwritten.

### 3D page turn

- [x] Each page rotates around its spine (`graphicsLayer` with `rotationY` and a raised camera distance) and darkens slightly as it turns. It tracks the finger, so a half-turn can be let go. It falls back to the plain slide when the system's animations are switched off, and to no motion in E-ink mode.
- [ ] Smoothness has only been judged on a software-rendered emulator. Check it on a real phone and a real tablet.
- [ ] Right-to-left: the geometry is mirrored and unit-tested, but has not been looked at on a device.
- A true paper curl with an AGSL shader on Android 13 and up is a possible later step (see Later).

### Tablet refinements

- [x] Every screen reviewed on an emulator at tablet portrait (Medium) and landscape (Expanded) in Light, Dark and E-ink. Fixed: the contents panel beside the page (wider, headline under the title at full width), short grid rows of three (set as text, without the squeezed side photo), back issues in landscape (the month's editions beside the calendar), and search in landscape (filters in a column beside the results).
- [ ] The reader's lead picture fills most of a landscape tablet screen, so the text starts below the fold. It is shown whole on purpose; decide whether landscape should crop it shallower.
- [ ] Sources, Saved and Settings keep one 680 dp column on a landscape tablet, with empty margins either side.

### Evening edition

- [x] An optional Evening edition, off by default, released at a time set in Settings (18:00 by default): Firehose and Daily feeds only, on a front page and In brief; Weekly and Rare posts wait for the morning paper.
- [ ] The Settings control has not been looked at on a device or emulator.
- [ ] The newsstand's "No new edition today" notice and its "next edition" time only know the morning reset.

### Extra edition

- [x] Engine: `EditionPublisher.publishExtraEdition(feedIds)` prints an edition at once from chosen feeds without disturbing the schedule (`EditionKind.EXTRA`).
- [x] The newsstand offers one after sources are added; covers and paper headers say "Extra".
- [x] Checked on the emulator in the light phone layout: the offer after an OPML import, printing (18 s for 15 sources), and the "No. 2 · Extra" label on the cover and in the paper. Not checked in dark, e-ink or tablet layouts, nor "Not now".
- [ ] The Back issues calendar shows one edition per day, the morning one, so an Extra or Evening edition on the same date cannot be opened from it once it has left the rack.
- [ ] If sources are added after tomorrow's edition was pre-built but before it is released, the Extra and the pre-built edition can both carry the same stories. Untested.
- [ ] Only one Extra edition per calendar date can be stored, because `edition` has a unique `(date, kind)` index. Lifting this needs a schema change.

### Benchmark

- [x] A Macrobenchmark for the on-open fallback build (`:macrobenchmark`, `OnOpenEditionBuildBenchmark`): 1,000 unread posts from 40 feeds, cold start, measuring the trace section around the build. How to run it is in `docs/how-it-works.md`.
- [ ] Run it on a real low-end phone. The budget is under 300 ms there; an emulator's numbers are only indicative. On the software-rendered API 37 emulator, on a host short of memory, five cold starts gave 371 ms at best, 573 ms median and 1,061 ms at worst for the build, which is over budget there. If a real phone is over too, see "Scores kept current during sync" below.

## Smaller gaps against the original spec

Specified but not built, and not assigned to a milestone.

- [ ] **Scores kept current during sync.** The spec wanted scores updated at sync so a fallback build mostly reads a sorted list. Scores are computed at build time instead. Decide after the benchmark whether this is needed.
- [ ] **Burstiness** is computed and stored but unused. It was meant to detect feeds that post many items at once.
- [ ] **Material You accent colour**, optional.
- [ ] **Favourites during onboarding.** The spec asked new users to star 3 to 5 favourite feeds (setting their affinity to 0.8) so ranking has something to go on. Onboarding only adds feeds.
- [ ] **Cold-start diversity.** For the first two weeks, raise the same-feed penalty so every feed gets seen and its affinity can be measured.
- [ ] **"Always show everything"** per feed, for feeds read exhaustively such as a webcomic.
- [ ] **Share** as an action and a signal (the same weight as Save).
- [ ] **Podcasts and video feeds.** The spec gave them their own card with inline playback. They are currently ordinary posts.
- [ ] **Tuning metrics** on a local, opt-in debug screen, so the constants in `RankingConfig` are tuned against measurements. The spec's list was written for the live feed and needs restating for editions:
  - read-through rate: reads over impressions in the top 10 items (the main health signal for ranking);
  - rare-feed coverage: the share of Weekly and Rare posts that get seen before they expire, target above 90%;
  - unread posts expiring from favourite feeds, which should be close to zero;
  - dismiss rate by position (there is no dismiss now; "printed near the front and not opened" is the nearest equivalent);
  - session depth (now: how far through an edition the reader gets).

## Ranking posts within a feed

Built and wired into every edition build; how it works is under "Ranking posts within a feed", "Post signals" and "The embedder" in `docs/how-it-works.md`. It is **not proven**: it has been tested on simulated readers and never on real reading history, and nothing has run on a device.

- [x] **The label**: a post's engagement against its own feed's rate, from the same evidence as feed affinity (opens, reads, Saves, Likes, and runs unopened in superseded editions).
- [x] **Feed categories** parsed from RSS, Atom and JSON Feed and stored per post (`article_category`).
- [x] **Structural traits** per post (`article_traits`): link and image counts, code, attachment kind, link post, title prefix; computed at sync and by `TraitsBackfill`.
- [x] **Text embeddings** per post (`article_embedding`) from a bundled static model (`potion-base-8M`, 7.9 MB asset, no ML library), computed by `EmbeddingBackfill`. This replaced the EmbeddingGemma idea: no download, and fast enough to embed a whole library in the background.
- [x] **The W factor**: per-feed lifts for category, length, attachment, link post, code and title prefix, plus a text taste learned across feeds and checked out of sample; capped to 0.7 to 1.4 and shrunk to 1 until there is evidence; named in "Why is this here?" when it is the strongest pull.
- [x] **Database version 2** with a migration, and background upkeep that fills the new tables for an upgraded library (after each sync and once after app start).
- [x] **Posts in non-Latin scripts** get no vector, because the English model would encode their script; they are ranked on categories and traits.
- Considered and not built: a small generative model asked to rank (foreground-only or over a gigabyte, slow, hard to explain) and gradient-boosted trees (they memorise posts at this number of labels).

Open:

- [ ] **Validate offline on real labels. This was skipped.** Export labels from a few weeks of real use, train on the first weeks, predict the last, and compare against the score without W on "was the opened post ranked above the unopened ones from the same feed". Tune or remove whatever does not clearly beat the old ranking. Every constant under "W" in `RankingConfig` is a guess until this is done. There is no label export yet; the archive file holds what is needed (engagement, editions, categories) except traits and vectors, which can be rebuilt.
- [ ] **Run it on a device.** Nothing here has: the migration of a real version 1 library, the upkeep's first pass over tens of thousands of posts (embedding ran at about 20,000 posts a second on a desktop JVM; the database writes are the cost, in transactions of 500), memory-mapping the asset out of the APK, and the build cost below.
- [ ] **Build cost on a phone.** On a desktop JVM, loading signals and learning added a median of 37 ms (30 ms to learn from 3,000 labelled posts, 7 ms to read 1,000 candidates) to a 171 ms on-open build; the vectors themselves made no measurable difference. It grows with the number of stories printed in the last 180 days. The macrobenchmark seeds no reading history, so it does not measure this: give the seeder past editions with engagement and run it. If it is too slow, the options are a shorter window for W than for affinity, or learning the model at sync and storing it.
- [ ] **The length key is biased by full-text fetching.** `article.wordCount` is the feed's excerpt when a post is scored and the fetched page afterwards. In a feed that only gives excerpts, opened posts therefore end up "long" and unopened ones stay "brief", so new posts (all "brief") can be marked down as a group. The early build fetches full text for every printed story, which hides most of it; editions built on open do not. Fix: store the feed's own word count beside the traits and build the length key from that (a schema change).
- [ ] **Traits are not recomputed when full text is fetched later**, and a post is embedded once, from the text stored at the time. Code, links and images in the fetched page are missed until the trait rules' version is raised.
- [ ] **Weekly and rare feeds gain little from keys.** Their decayed exposure levels off near 7 posts, so their keys never get past about a third of full strength. Only the text taste, which is pooled across feeds, can order their posts.
- [ ] **The embedding model is English-only.** Other Latin-script languages are embedded but their vectors are mostly noise; non-Latin scripts are not embedded at all. A text that mixes scripts is judged by letter count, and one ideograph counts as one letter, so a Japanese post with many English names can pass.
- [ ] **`media:content` audio and video are not treated as attachments.** Only `<enclosure>`, Atom enclosure links and JSON Feed attachments are, so some video and podcast feeds have no attachment key.
- [ ] **Category names in reason lines are the lowercased keys** ("posts about space"), not the feed's spelling. The label is stored; the engine is only given the key.
- [ ] **Categories of old posts come back only from the feed.** After the upgrade, posts that have dropped out of their feed's document never get categories.
- [ ] **Posts from a single-feed sync are embedded late.** After adding a feed, and before an Extra edition, posts have categories and traits but no vector until the next full sync or app start.
- [ ] **A marked post is never retried.** A post marked as unembeddable (no known words, or non-Latin) stays marked until the model ID changes, even if better text is fetched for it.
- [ ] **The debug sample data has no categories**, so it shows length, code, link-post and text signals only.
- [ ] Tuning: the image and link counts are stored and unused; decide after validation whether they earn a key.

## Later

Roadmap ideas, not scheduled.

- [ ] Feed discovery: suggest feeds based on what the reader Likes.
- [ ] A home-screen widget showing today's front page.
- [ ] Likes as a public feed.
- [ ] A true paper-curl page turn (AGSL shader).

## Out of scope

Deliberately not being built. Listed so they are not mistaken for forgotten.

| Item | Why |
| --- | --- |
| Notifications | Decided in review. The spec proposed opt-in notifications for Favourite feeds, sent when a sync finds a new post, bundled, with quiet hours, opening the article without changing the edition. The edition itself was never going to notify. The `feed.notify` column exists and is unused. |
| Compression of stored content | Decided in review, for now. The spec stored editions gzip-compressed; content is stored as plain JSON. Revisit if the storage budget proves tight. |
| Wood texture on the newsstand | Decided in review. The stand uses restrained skeuomorphism drawn from flat theme colours. |
| A table of objects as the home screen | Replaced by the newsstand with a plain labelled button bar. |
| PDF or printable export of an edition | "Not initially" in the spec's decisions. |
| Publish time adapting to when the reader opens the app | Decided against: a fixed reset time in the reader's time zone. |
| Anything appearing between editions | Decided against. |
| Accounts and cross-device sync | Decided against: fully local. |
| OPML folder names as an affinity hint | Dropped in the spec's review. Folder names become tags instead. |
| Reprint-only or empty editions | Decided against: with no new posts no edition is built. |

Dropped when the design moved from a live ranked feed to a daily edition:

| Live-feed feature | What replaced it |
| --- | --- |
| Endless ranked scroll, frozen per session, with a "5 new posts" pill | One finite edition a day |
| Impressions, skips and scroll-past tracking | "Printed in an edition without being opened" is the only negative signal |
| Swipe to dismiss, swipe to save, and the dismiss penalty | Removed; horizontal swipes turn pages. Save and Like are buttons. |
| Seen penalty of 0.6 per scroll-past | 0.5 per edition run unopened |
| 4-hour half-life floor | 12 hours |
| Hero, Standard, Compact and Stack cards; burst stacks | Newspaper story blocks and the In brief column |
| Diversity pass: × 0.7 for each item from the same feed in the last 5 slots, and at most 2 of any 10 consecutive cards from one feed | One story per feed on the front page; × 0.7 for each story from the same feed already on a Top stories page |
| From the archive as one slot per session: the best unread post older than 3 days from a feed with affinity of at least 0.7 | From the archive as a page: the best held-over post not printed in the last 3 editions |
| Bottom tabs (Feed, Saved, Sources) | The newsstand and its button bar |
| Tag filter chips | Section pages |
| Catch-up card and Catch-up mode | While you were away and back issues |
| "You're all caught up" and "Show older posts" | The back page and its index |
| Relative time labels such as "new this week" | Reading time in bylines |
| `feed_score_cache` table | `edition_item.score` and `reason` |

## Known rough edges and unverified areas

- [ ] **The release workflow has never run.** GitHub has not registered `.github/workflows/release.yml` since the first push. The cause is unknown.
- [ ] **The early build has only run under tests**, never overnight on a device. Whether WorkManager runs it in time under Doze is unknown.
- [ ] **Relearning affinity on every engagement change** (each open, read, Save or Like) is unmeasured for performance.
- [ ] **OPML export on a device is untested.** The writer is unit-tested; the file-picker path is not.
- [ ] **E-ink: a long page can end on a mostly blank screen**, because steps snap to anchors.
- [ ] **E-ink: very long code lines still wrap.** Code is set smaller until its longest line fits, down to 70% of the usual size; lines longer than that wrap.
- [ ] **`targetSdk` is 36 while `compileSdk` is 37.**
- [ ] **E-ink has no tap zones.** Paging is by swipe, keys and the Previous and Next buttons.
- [ ] **Source comments still cite the deleted plan files** by name ("v1 Live feed", "v2 Daily edition") in `engine/RankingConfig.kt`, `Cadence.kt` and `Affinity.kt`. Point them at `docs/how-it-works.md` when those files are next touched.

- [ ] In a narrow story cell a long byline runs to a second line ("Source ·" over "4 min read") instead of being cut off. It leaves the dot at the end of the first line.
- [ ] Undo on the Saved and Liked shelves puts the story back at the top, because re-saving stamps a new time. Exposing `savedAt` / `likedAt` on `ArticleSummary` would fix it.
- [ ] TalkBack and right-to-left layouts have not been tested at all. (A `PaperDialog` now hides the screen beneath it from screen readers; that was checked in the semantics tree, not with TalkBack.)
- [ ] Comments in `ui/reader/ReadRule.kt`, `sync/DefaultFeedSyncer.kt` and `ScorerTest.kt` also refer to the deleted v1/v2 plan files.

- [ ] **Milestone 3 health, storage and archive screens are unchecked on a device**: the Subscriptions desk, the Quiet group in Sources, the back-page desk notice, the storage budget stepper and usage bar, and archive export and import through the file picker. Their logic is unit-tested.
- [ ] **Measuring stored text reads every body's length** (two full scans) after each sync and when Settings opens. Unmeasured on a large library.
- [ ] **Repair onto a common path can pick the wrong feed** when the broken feed was one of several on a site (for example a category feed, and `/feed` is the whole site). The reader is told through the Moved flag.
- [ ] **A Like or save removed after an export comes back on import**, because the archive cannot tell "never Liked" from "un-Liked since".

## Open questions

- [x] **While you were away:** drawn from stored editions that were released and never opened. Posts of a gap with no edition are ordinary new candidates.
- [x] **Evening edition:** released at its own setting, 18:00 by default. The morning edition's window runs from the previous morning cutoff and leaves out what the evening edition printed.
- [x] **Welcome back:** a boost of 1.5 after 90 quiet days.
- [x] **Freshness floor:** applied. A new post under the floor is listed in the index and not printed; held-over posts are exempt.
- [ ] **`targetSdk`:** raise to 37 to match `compileSdk`?

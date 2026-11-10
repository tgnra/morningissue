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

Not started.

### While you were away

- [ ] If the reader skips one or more days, the next edition opens with a pull-out section: the best stories from the missed editions.
  - Ranked by the same score, with freshness measured from the reader's last visit instead of from the release time.
  - Firehose feeds are left out unless the reader has Liked or saved one of their posts in the last 30 days.
  - Only feeds with the slider at Auto or above appear.
  - Stops after the top 30. A summary line such as "While you were away: 12 posts from 7 feeds".
  - Missed editions must never pile up as a to-do list.
- Already in place: `ItemKind.AWAY`, `EditionPage.WhileYouWereAway`, the page's rendering with dated bylines, `edition.openedAt` and `EditionDao.lastOpenedAt()`.
- To settle first: see Open questions (what counts as "missed" when no edition was built during the gap).

### Search with filters

- [ ] Add filters to the search screen: by tag, by source, by date range, and Liked or saved only.
- Already in place: `SearchQuery` and `ArticleDao.search` accept every one of these filters; only the controls are missing.

### Cross-feed deduplication

- [ ] Collapse posts that share a canonical URL or have a near-identical title (the same story syndicated across feeds).
  - Keep the copy from the higher-affinity feed.
  - Show "also in 2 feeds" on the kept story.
- Already in place: `article.contentHash` (a hash of the normalised title and text) and the empty `dedupe` step in `EditionBuilder`, between scoring and layout.

### Feed health check and the Subscriptions desk

- [ ] Health rules as a pure `engine` component, run after sync.
- [ ] **Subscriptions desk** page: flags feeds that need attention and offers one-tap fixes. Always available from Sources (the screen exists as a placeholder), and carried as a page in the edition once a month. It never unsubscribes on its own. It shows nothing when every feed is healthy.
- [ ] Dismissing a flag hides it for 6 months (`feed_health.flagDismissedUntil`).

| Flag | Rule | Suggested action |
| --- | --- | --- |
| Broken | Fetch errors 7 days running, or the feed returns 410 Gone | Retry automatically, then try common feed addresses (`/feed`, `/rss`, `/atom.xml`, `/index.xml`, http/https and `www` variants, and the site's own feed link). Only ask the reader to fix or remove the feed if none work. |
| Moved | Permanent redirect (301) | Update the address automatically, then tell the reader. |
| Gone quiet | No posts in 90 days (not a problem) | Nothing to do. The feed moves to a Quiet group and is checked less often. |
| Ignored | Ran in 10 or more editions over 60 days with no opens | Unsubscribe, or set the slider to Less. |
| Noisy | 30 or more posts a week with a read rate under 2% | Move to In brief only, or set the slider to Less. |
| Loved | High read and Like rate | Suggest Favourite. (The spec also suggested turning on notifications, which are out of scope.) |

- [ ] **Quiet feeds.** A feed with no posts in 90 days moves to a Quiet group at the bottom of Sources and is checked less often, stepping down from hourly to weekly. Quiet feeds are kept on purpose.
- [ ] **Welcome back.** When a quiet feed posts again, the story gets a label such as "Back after 2 years" and a comeback boost on top of the scarcity boost, so it almost always makes the front page.
- Already in place: the `feed_health` table and `HealthFlag` enum; every fetch records success or failure (`errorStreak`, `errorSince`, `lastHttpStatus`, `lastPostAt`); a permanent redirect already updates the address and raises `MOVED` when the address really differs; feed discovery already probes the common paths.

### Storage budget and trimming

- [ ] A storage budget slider in Settings. The setting is stored already (`storageBudgetMb`, default 500, range 50 to 4000) but has no control and nothing reads it.
- [ ] When the budget fills, trim the stored text and cached images of the oldest editions' articles first.
  - Liked and saved posts are never trimmed.
  - Headlines and edition rows stay, so back issues remain browsable.
- Already in place: `ArticleDao.contentSize`, `trimmableContentIds` and `deleteContent`.
- The budget is the answer to "how long are back issues kept?": there is no fixed period. Until this is built, nothing is ever trimmed.
- Content stays uncompressed (see Out of scope).

### Archive export and import

- [ ] Export old editions to external storage (an SD card, a cloud drive, a computer) through the Storage Access Framework.
- [ ] Import a backup later. Import merges into the current database and skips posts it already holds, matched by feed and post ID (the unique `(feedId, guid)` index), so a reader can free space and bring everything back.

### 3D page turn

- [ ] Each page rotates around its spine (`graphicsLayer` with `rotationY` and a raised camera distance) and darkens slightly as it turns.
  - It tracks the finger, so a half-turn can be let go.
  - Falls back to the plain slide when the system's animations are switched off, and in e-ink mode.
  - A true paper curl with an AGSL shader on Android 13 and up is a possible later step.
- Already in place: `Modifier.pageTurn` in `ui/paper/PageTurn.kt` is applied to every page and currently does nothing.

### Tablet refinements

- [ ] Review every screen at Medium and Expanded sizes in both orientations, in all three themes (see Known rough edges).

### Evening edition

- [ ] An optional Evening edition, off by default.
  - Covers only Firehose and Daily feeds, since the morning cutoff.
  - Weekly and Rare feeds never appear in it; their posts wait for the morning paper.
- Already in place: `EditionKind.EVENING`, the unique `(date, kind)` index and the stored `eveningEditionEnabled` setting (no control yet).
- To settle first: see Open questions (its release time, and how its cutoff interacts with the morning edition's).

### Benchmark

- [ ] A Macrobenchmark test for the on-open fallback build: under 300 ms on a low-end phone. The expectation is that scoring about 1,000 candidates takes about a millisecond and the database read tens of milliseconds.

## Smaller gaps against the original spec

Specified but not built, and not assigned to a milestone.

- [ ] **Scores kept current during sync.** The spec wanted scores updated at sync so a fallback build mostly reads a sorted list. Scores are computed at build time instead. Decide after the benchmark whether this is needed.
- [ ] **Freshness floor.** The spec's candidate pool required F ≥ 0.05. `RankingConfig.minFreshness` exists but is not applied; only the 30-day age limit is.
- [ ] **Burstiness** is computed and stored but unused. It was meant to detect feeds that post many items at once.
- [ ] **Material You accent colour**, optional.
- [ ] **Masthead drawn instantly** while stories load. Not verified; the paper shows a blank page until the edition has loaded.
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
| Catch-up card and Catch-up mode | While you were away (planned) and back issues |
| "You're all caught up" and "Show older posts" | The back page and its index |
| Relative time labels such as "new this week" | Reading time in bylines |
| `feed_score_cache` table | `edition_item.score` and `reason` |

## Known rough edges and unverified areas

- [ ] **The release workflow has never run.** GitHub has not registered `.github/workflows/release.yml` since the first push. The cause is unknown.
- [ ] **The early build has only run under tests**, never overnight on a device. Whether WorkManager runs it in time under Doze is unknown.
- [ ] **Relearning affinity on every engagement change** (each open, read, Save or Like) is unmeasured for performance.
- [ ] **E-ink and dark themes are unchecked at tablet sizes.**
- [ ] **OPML export on a device is untested.** The writer is unit-tested; the file-picker path is not.
- [ ] **E-ink: a long page can end on a mostly blank screen**, because steps snap to anchors.
- [ ] **E-ink: code blocks wrap** instead of scrolling sideways, which can make code hard to read.
- [ ] **`targetSdk` is 36 while `compileSdk` is 37.**
- [ ] **E-ink has no tap zones.** Paging is by swipe, keys and the Previous and Next buttons.
- [ ] **Source comments still cite the deleted plan files** by name ("v1 Live feed", "v2 Daily edition") in `engine/RankingConfig.kt`, `Scorer.kt`, `Cadence.kt`, `EditionBuilder.kt` and `Affinity.kt`. Point them at `docs/how-it-works.md` when those files are next touched.

- [ ] In two-column story cells the byline truncates because the "⋯" menu button takes room.
- [ ] Undo on the Saved and Liked shelves puts the story back at the top, because re-saving stamps a new time. Exposing `savedAt` / `likedAt` on `ArticleSummary` would fix it.
- [ ] An OPML import in flight is lost on rotation (add-feed discovery survives it).
- [ ] Dialogs drawn with the older `PaperDialog` leave the screen beneath visible to screen readers. TalkBack and right-to-left layouts have not been tested at all.
- [ ] The contents list counts In brief lines as "stories".
- [ ] Comments in `ui/reader/ReadRule.kt`, `sync/DefaultFeedSyncer.kt` and `ScorerTest.kt` also refer to the deleted v1/v2 plan files.

## Open questions

- [ ] **While you were away:** editions are only built by the early job or on open. After a long gap there may be no "missed editions", only one large edition. Is the pull-out drawn from stored editions that were never opened, from the posts of the gap, or both?
- [ ] **Evening edition:** when is it released, and is that a setting? The next morning edition must still pick up Weekly and Rare posts first seen before the evening cutoff, which the current "posts since the latest cutoff" rule would miss.
- [ ] **Welcome back:** how large is the comeback boost, and how long must a feed have been quiet to earn it?
- [ ] **Health flags:** what read and Like rates count as "Loved"?
- [ ] **Storage budget:** does it count Coil's image cache, which the app does not manage directly, or only stored text?
- [ ] **Freshness floor:** apply `minFreshness` to the candidate pool, or remove the constant?
- [ ] **`targetSdk`:** raise to 37 to match `compileSdk`?

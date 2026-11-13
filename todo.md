# To do

What is built, what is being built and what is left. How the built parts work is in [`docs/how-it-works.md`](docs/how-it-works.md). This file carries the specification for everything not built yet, so each item has the rules and numbers needed to build it.

## Done

- [x] **Foundations.** Gradle, Kotlin and Compose setup; single Activity; light, dark and e-ink themes; bundled fonts; layout spec for phone and tablet; navigation graph; Room schema; hand-written dependency container.
- [x] **Sync.** RSS, Atom and JSON Feed parsing; conditional fetching; feed discovery from a site address; full-text extraction; periodic background sync; cadence and tiers. (Feed fetching now runs on the server; see "Done: self-hosted server".)
- [x] **OPML import and export** in Sources and onboarding; every ancestor folder becomes a tag.
- [x] **Scoring**: A × F × S × P with all constants in `RankingConfig`.
- [x] **Daily edition**: front page, Top stories, In brief, back-page index; early build with prefetch (now the server's scheduler); on-open fallback (since replaced by the unprinted placeholder and Print now, below); frozen once stored.
- [x] **No empty or reprint-only editions**: with no new posts nothing is built and the previous edition stays current.
- [x] **Newsstand** with drawn covers, rack and labelled button bar; **paper** pager with contents list; **reader** with Like, Save, text size and read tracking; **back issues** calendar; **source pages**; **Saved and Liked** lists; basic **search**; **Settings**; **onboarding** with first sync.
- [x] **Tablet layouts** (Compact, Medium, Expanded) and **e-ink mode** (paged movement, no animation, greyscale images).
- [x] **Room 3** migration.
- [x] **Cleartext HTTP** allowed, for feeds that only serve `http`.
- [x] **Release signing** and a tag-triggered release workflow (see Known rough edges: it has not run yet).
- [x] **Learning, engine and data half**: affinity learning from opens, reads, Saves and Likes; tag affinity and tag borrowing; slider caps and floors applied in scoring; mutes respected by the builder; section pages; held-over posts; lead at most twice; From the archive; a stored reason line per story.

## Done: self-hosted server

Merged on `self-hosted`. The design and every deviation from it are in `docs/server-design.md`; how it works is in `docs/how-it-works.md`; running it is in `docs/server.md`. Tested on the JVM, in the Docker image's health check and in the NixOS VM test; not yet run for real (see Open below).

- [x] **`:core`**: the model, the engine, the embedder, feed fetching and parsing, full-text extraction, repair, discovery's ranking, the Kobo formats and grey images, the archive format, `DueEditions`, the API's wire forms and an OkHttp client of the API, shared by the server and the app. Test helpers as core test fixtures.
- [x] **The server** (`server/`): Ktor (CIO) over a multi-user Room 3 database on the JVM with the bundled SQLite; TOML config with environment overrides; accounts listed in the config, generated first passwords in the log and in `initial-passwords/`, PBKDF2 hashes, a forced password change, a login rate limit, `reset-password` and `import` on the command line; opaque bearer tokens for phones; every `/api/v1` route; `UserScopedQueryTest` guarding every query over a reader's tables.
- [x] **Feeds on the server**: one shared copy of each feed and its posts, per-reader subscriptions, tags, sliders, mutes and flags; the refresher with in-flight fetches shared and a maximum age; health checks with repair after each periodic refresh; OPML import as a job and export; signals upkeep after each refresh that stores posts; teaser full text; discovery per reader with the catalogue in the server; the discovery clean-up and a daily garbage collection.
- [x] **Editions on the server**: the publisher per reader; the scheduler building each reader's edition 30 minutes before their release in their own time zone, with one union pre-print refresh and a two-hour leeway, a periodic refresh every two hours, and a catch-up build after downtime; Print now and the Extra edition as jobs; release-time changes; the stand; affinity and within-feed learning per reader, relearned 10 seconds after a merge.
- [x] **Sync API**: events merged by the Kobo table with `acked_seq`, the stateless events gate (`X-Events-Through`, `409 events_pending`), the manifest with bundle versions, bundles, the saved shelf, the engagement delta by `change_seq`, ETags.
- [x] **Legacy import**: archive format additions (mute, page views, `settings.json`), `POST /import` with an idempotency key, the merge rules of the app's import, settings applied only to an account never changed, then signals upkeep, teaser backfill and a relearn.
- [x] **Kobo pairing and pictures on the server**: `POST /devices/kobo` with the v2 QR text, the token derived by HKDF and only its hash kept, `/kobo/hello` with the `dbId`, `/kobo/config.json`, grey pictures prepared on the JVM (ImageIO and TwelveMonkeys) in an LRU cache, warmed at every build for the reader's Kobo widths.
- [x] **Packaging**: `installDist` with `-PserverOnly` (no Android SDK), a Dockerfile and compose file, an image workflow pushing to ghcr.io on a tag, a Nix flake with a package, an overlay, a NixOS module (hardened, `DynamicUser`) and a VM test (`nix flake check`).
- [x] **The Android app as a client**: server address, login, password change and the migration screen that uploads a phone's library (merge, or use the account's library; save a copy first); the cache with server IDs (schema 7, `outbox_event`, `sync_peer` dropped); `LocalEngagement` and the outbox; `ServerSync`; `ClientSyncWorker` every two hours, after each release, on start and on returning to the foreground; server-backed repositories; Print now and Extra through jobs; the Kobo screen pairing through the server; Account in Settings with Log out. Tested against a fake server.
- [x] **The Kobo talks to the server directly** (`kobo/`): pairing code v2, the token from the same secret, the server address in `config.json` or on the restored letter keyboard, the pairing wait on `/kobo/hello`, the events gate, the picture zip, a wipe when the `dbId` changes, checked at hello before any upload. Tested against an in-process fake server.
- [x] **The relay is removed**: `relay/`, `docs/relay-sync.md` and its fixtures, the phone's relay sync and keys, the Kobo's relay client.
- [x] **End-to-end test** (`server/src/test/.../E2ETest.kt`): the real server over HTTP with core's client as the phone and the Kobo; and two readers sharing a feed fetched once by the leeway.
- [x] **CI** runs `:core:test :server:test :app:testDebugUnitTest` and the Kobo's `cargo test`; releases attach the APK and the Kobo zip; the server image is built on a tag.

Open:

- [ ] **Run the whole thing for real**: the server for weeks with real feeds and a few readers, the app against it, and a Kobo against it.
- [ ] **The Android client's screens are untested.** Its client code is tested against a fake server, but the Compose screens (account, migration, Kobo, Settings' Account), the QR camera and WorkManager have not been run, and the app has not been run against a real server.
- [ ] **Two fields were lost in the move.** `CardDto` has no exploration flag, so discovery's exploration cards no longer carry the "Something different" label (the reason line still says it); `BundleItemDto` has no score, so the app no longer has a story's score. Adding them to `:core`'s DTOs (optional fields, so older clients are unaffected) would bring both back.
- [x] **The ranking simulation runs on the server.** `RankingSimulationTest` drives the server's refresher, build runner and event merge; its thresholds passed unchanged.
- [ ] **A Kobo interop test against the real server** (`kobo/tests/server_interop.rs`, run against `:server:installDist` when an environment variable names it) is not written. The Kobo is tested only against its own fake server, the server only against core's client.
- [ ] **Server-side picture decoding misses AVIF** and other formats ImageIO and TwelveMonkeys cannot read. Such pictures are left out on the Kobo, as SVG already is.
- [ ] **A late subscriber to a shared feed** sees the posts the server stored in the last 30 days as new in their next edition (the first-seen time is the later of the post's and the subscription's). Close to the app's add-a-feed behaviour, not identical. Decide whether it is wanted.
- [ ] **The server's schema is version 1 with no migrations** until its first release. Freeze it at the release; from then on every change ships a migration with a test, as the app's do.
- [ ] **The phone's cache keeps tables and columns from when it worked alone** (`article_category`, `article_traits`, `article_embedding`, `discovery_verdict`, `discovery_candidate`, and feed columns no longer filled), because the legacy upload reads a library in that schema. Drop them once uploads from those versions no longer matter.
- [ ] **The stand before the first sync after a release.** The phone shows the placeholder for a release it has not synced since, until the sync two minutes after the release (or on opening) brings the edition. Offline, it stays.
- [ ] `ArchiveStatus.Running.importing` and `Failed.importing` are kept but always false, now that import is on the server.

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

## Done: fixes from the ranking simulation

The 180-day ranking simulation found four weaknesses; each check is now a passing test with its before and after numbers (see "Ranking simulation" in `docs/how-it-works.md`).

- [x] **An unseen page is not an ignore.** The paper records how long each page is on screen (`edition_page_view`, database version 4). A story counts as passed over only when its page was on screen for 5 seconds in all; engagement always counts. Applied to affinity, the within-feed labels, `editionsRunUnopened` and the Ignored and Loved flags. Editions from before the upgrade (`edition.pageViewsTracked` = 0) are judged by the old rule. A week away no longer lowers a loved feed's affinity by a third.
- [x] **The affinity prior follows the cadence.** A feed's n₀ is a quarter of the decayed exposure its cadence can reach, between 1 and 10, so a slow feed's own record can outweigh its tags; and scarcity is only paid in full to a feed at neutral affinity or above. A monthly blog read every time becomes loved; a weekly podcast never opened falls off the front page.
- [x] **The text taste scales to its data.** Its trust is `n / (n + 50)` times how well each half predicts the other, and its effect may reach ×2 or ×½ (keys alone stay within 0.7..1.4). Binary traits have a key either way ("not a link post", "no code"), so a link blog's own pieces are told apart.
- [x] **The same-feed penalty applies to sections and In brief**, not only to Top stories, so a busy feed shares its tag's page and In brief with the other feeds.

Left open by these:

- [ ] **Per-page, not per-story, views.** A long page counts as seen once 5 seconds have passed, whether or not the reader scrolled to its last story. Tracking which cards were scrolled into view would be more exact.
- [ ] **A rare feed's affinity is noisy.** With a prior scaled to cadence, a monthly feed is judged on its two or three posts in the window; for a reader with no taste its affinity can sit anywhere from about 0.1 to 0.9. That is the price of letting a rare feed read every time become loved.
- [ ] **Keys on rare traits stay short of the cap.** The key prior (10 pseudo-exposures) is not scaled to how often a key turns up, so a link blog's own articles (about seven a month) rest near ×1.25 rather than ×1.4, and come first on about 70% of the days that also have link posts, against about 88% at the cap.

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

- [x] Health rules as a pure `engine` component (`FeedHealthRules`), run on the server after each periodic refresh.
- [x] **Subscriptions desk** page with one-tap fixes, always available from Sources. It never unsubscribes on its own and says "All in order" when every feed is healthy.
- [x] **Carried by the edition once a month**, as a notice on the back page of one edition per calendar month (not a page of its own), only while a feed needs attention.
- [x] Dismissing a flag hides it for 6 months (`feed_health.flagDismissedUntil`).
- [x] **Automatic repair** of a Broken feed before the reader is asked: retry, scheme and `www` variants, the site's own feed link, then `/feed`, `/rss`, `/atom.xml`, `/index.xml`. Tried again weekly.
- [x] **Quiet feeds.** A feed with no posts in 90 days moves to a Quiet group at the bottom of Sources and carries the Gone quiet flag. It is checked by every sync like any other feed; the reduced cadence (6 hours down to weekly) was removed at the owner's request.
- [x] **Welcome back, engine.** When a feed posts again after 90 quiet days, the story gets the reason line "Back after 2 years: …" and a comeback boost of 1.5 on top of scarcity, so it almost always makes the front page.
- [ ] **Welcome back, label.** Show `EditionStory.comebackLabel` on the story block.
- Differences from the original table: there is no "In brief only" setting for a feed, so Noisy offers Less; Loved offers Favourite only (notifications are out of scope).

### Storage budget and trimming

Built; see "Storage budget" in `docs/how-it-works.md`. Not yet seen on a device.

- [x] A storage budget control in Settings (discrete steps, 50 MB to 16 GB) showing current usage.
- [x] When the budget fills, the stored text of the oldest editions' articles is trimmed first, after a sync. Liked and saved posts are never trimmed; headlines, summaries and edition rows stay.
- [x] The budget counts stored images as well as text, and Settings says how much is each. Images are trimmed before text, oldest editions first; Liked and saved posts keep both.
- Content stays uncompressed (see Out of scope).

### Image store, full-screen pictures and greyscale

Built; see "The image store", "Storage budget", "Reader" and "Greyscale pictures" in `docs/how-it-works.md`. **None of it has been seen on a device.**

- [x] **Image store** in the app's files (`data/images/`): the pictures of an edition's printed stories at prefetch, and of an article when it is opened; decoded with downsampling to 1,280 px, EXIF applied, re-encoded as lossy WebP at quality 80; the original kept when re-encoding is not smaller; animations and SVG kept as they came up to 2 MB.
- [x] **Display reads the store first** through a Coil fetcher ahead of the network fetcher, for every picture in the app.
- [x] **`stored_image`** in database version 2, with files and rows deleted together on feed removal, trimming and "export and free up space", and a sweep after sync and app start.
- [x] **Tap a picture in the reader** to see the original full screen, with pinch, drag and double tap; stepped and paged in E-ink mode.
- [x] **Greyscale option** in Settings for any theme, dithered (4 × 4 Bayer, 16 levels) as a Coil transformation at the size shown; E-ink mode implies it and keeps its raised contrast.

Open:

- [ ] **Run it on a device.** Unseen: real WebP sizes and encoding time on a phone (the figures in the docs are from Robolectric's desktop build of Android's encoder), the encoder on Android 8 to 10 (the older `WEBP` format, exercised only under Robolectric at API 28), memory while a very large photograph is handled, the full-screen picture's gestures, the Settings option, and how the dither looks on a phone screen and on an e-ink panel.
- [ ] **The grain at 16 levels may be too faint on a dense phone screen.** It was chosen from enlarged samples on a desktop. `Dither.LEVELS` at 8 is the stronger alternative.
- [ ] **Only the newest edition's pictures are stored after a sync.** Older cached editions' pictures are stored when their articles are opened, and a phone offline at sync time stores none until the next sync.
- [ ] **The server's prefetch time limit is shared.** After a build, full text and the Kobo pictures together have four minutes. A long edition may not finish; missing text is fetched when the article is opened, missing pictures at the Kobo's sync.
- [ ] **A stored image belongs to one post.** A picture several posts share (a podcast's cover on every episode) is trimmed with the first post that stored it, whatever the others are, and fetched again.
- [ ] **Opening an article can download a picture twice**: once by the screen and once by the store, when the store starts before Coil has finished. The store waits four seconds and takes what Coil has cached; a slow connection defeats that.
- [ ] **The full-screen original is not dithered** in greyscale and E-ink, because it is drawn scaled. Re-dithering at each zoom step would be needed for E-ink.
- [ ] **No animated or vector decoder is included**, as before: a GIF shows its first frame and an SVG shows nothing. The store keeps their bytes untouched so that adding `coil-gif` or `coil-svg` later needs no re-download.

### Archive export and import

Built; see "Archive export and import" in `docs/how-it-works.md`. The file-picker path has not been run on a device.

- [x] Export the whole library (feeds, posts with text and engagement, released editions) to one versioned zip of JSON lines through the Storage Access Framework, optionally freeing space afterwards.
- [x] Import merges into an account, now on the server (the migration screen, `POST /import`, or the command line): posts matched by feed address and post ID are skipped (trimmed text is put back), missing feeds are re-added with their tags, editions are matched by date and kind, and engagement already held is never overwritten. The phone no longer imports.

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
- [x] The day's Extra edition can be reprinted to take in sources added after it was printed: in place, under the same ID and number, set again as a whole. The newsstand offers "Reprint the Extra with 3 new sources". Engine and offer rule unit-tested; the offer has not been seen on a device.
- [ ] Still only one Extra edition per calendar date, because `edition` has a unique `(date, kind)` index. The reprint removes the practical cost (later sources no longer have to wait), but two separate Extras on one date would still need a schema change.
- [ ] A reprint rewrites the paper while it may be open in the paper screen or reader. The screens observe the edition, so they should follow; not checked on a device.

### Unprinted placeholder and Print now

Built; see "When an edition failed to build" in `docs/how-it-works.md`. Not seen on a device or emulator at all: the logic is unit-tested, the screen is not.

- [x] The newsstand's refresh button and pull-to-refresh gesture are removed.
- [x] Opening the app no longer builds an edition. When the due edition failed to build (no edition, and no record that a run found nothing new) the stand shows an unprinted placeholder cover with Print now.
- [x] Print now runs the build's stages as a job on the server (`POST /print`), followed by the phone's `PrintClient`: refresh, build, full text, Kobo pictures, with progress; it survives rotation and leaving the screen, cannot be started twice, prints from stored posts when the server cannot reach the feeds and says so, and offers Try again on failure.
- [x] `nothingNewFor` in the settings store records the releases a run found nothing new for, so "No new edition today" is not mistaken for a failure.
- [ ] Look at the placeholder, the progress display and the result notices on a phone, a small tablet and a landscape tablet, in Light, Dark and E-ink.
- [x] A changed reset time or Evening edition time applies from the next release, never retroactively (`ReleaseSchedule`, `ReleaseTimes`), so changing it no longer puts the placeholder up. An edition already built for the old time is released at the old time.
- [ ] Settings does not say when a changed time takes effect ("from tomorrow's edition"). The schedule knows; the screen does not show it.
- [ ] A release time changed while the server is building for that release is not handled specially.
- [x] Onboarding finishes with Print now on the server, so its first edition gets the full-text prefetch and records "nothing new" like any other.
- [x] Result notices on the newsstand (Print now's and the Extra edition's) have a close button; a closed notice stays closed until there is a new result.
- [ ] Print now's result says only whether the sources could be reached at all, not how many failed.

### Benchmark

- [x] A Macrobenchmark for the build from stored posts (`:macrobenchmark`, `OnOpenEditionBuildBenchmark`). The phone no longer builds editions, so it is `@Ignore`d; its seeding receiver fakes a login so the app opens onto its cache.
- [ ] Retarget it: measure the build on the server instead (`WithinFeedBuildCostTest` measures part of it on a desktop JVM), and decide whether the app needs a benchmark of its own (cold start onto the cache, a sync applying ten bundles).

### Discovery (Find sources)

Built on `discovery-app`; see "Discovery" in `docs/how-it-works.md`. Unit-tested on simulated readers, a hand-built catalogue and the real bundled `assets/discovery/catalogue.db` (18,062 feeds in 56 clusters, built by `tools/discovery/`; `BundledCatalogueTest`, now in `:server`). Discovery now runs on the server, per reader; the phone shows the cards. Not run on a device.

- [x] Schema version 3: `feed.status` (`SUBSCRIBED` or `DISCOVERED`), `feed.discoveredAt`, `discovery_verdict` and `discovery_candidate`, with `MIGRATION_2_3` and tests from versions 1 and 2. Archive format version 2 carries feed status.
- [x] Discovered feeds are left out of editions, sync, the health check, affinity learning, Sources, OPML and search filters; `SubscribedOnlyQueryTest` guards the DAO queries.
- [x] Ranking (`engine/Discovery.kt`): centred vectors, z-scored similarities, top-k profile terms, passes as negatives, MMR deck of 20, an exploration card every fifth slot by Thompson sampling, cold-start topics, reason lines.
- [x] Cards with Pass and Subscribe buttons (no gestures), Undo, TalkBack actions, the Passed list, live headlines for the card on screen and the next two (fetched by the server now, so no longer metered-aware; 1 MB cap, 24-hour cache).
- [x] Reading a suggested post stores a discovered feed; "Not subscribed" bar in the reader and on the source page, Subscribe on the Saved shelf; unsaved posts of discovered feeds deleted after 7 days.
- [x] From your reading list: a saved, unread post from a discovered feed in the Morning edition at most once a week.
- [ ] Look at every discovery screen on a phone, a tablet and in E-ink, with the real catalogue.
- [ ] **The blogroll and outbound-link crawler.** `discovery_candidate` and its DAO exist, but nothing fills them. A crawler would read the blogroll pages and outbound links of subscribed feeds' posts, count mentions per feed (`mentions`, `foundVia`), fetch each candidate's feed once to fill tier, full-text share and centroid, and offer the best as cards beside the catalogue's ("Linked from Lost Art Press"). Needs: a budget for fetches, robots.txt, and a rule for how candidates and catalogue feeds share a deck.
- [ ] **Webcomics in discovery need UX thought.** They are filtered out for now. They are image-led, post on a different cadence, and it is unclear how they print in the paper (a strip in In brief? a page of their own?). Source for a catalogue of them: Kagi's `smallcomic.txt`.
- [ ] Discovery's verdicts are not in the archive, so passes do not move to a new device.
- [ ] **Read over the catalogue's cluster labels.** They come from feed categories, and the small web leans heavily on AI ("AI & Devops", "AI & Emacs"). Labels and clusters also shift between rebuilds, which moves picked topics.
- [ ] **Near-duplicate feeds by content.** One blog can appear under two addresses (abdullah.gula.bi and gulabi.bearblog.dev); deduplication is by URL and host only.
- [ ] **A few non-English feeds declared as English** get in (about 290 feeds sit in a mixed-language cluster).
- [ ] **Catalogue rebuild workflow** (`.github/workflows/discovery-catalogue.yml`) has never run, and needs "Allow GitHub Actions to create and approve pull requests" in the repository settings.
- [ ] Feeds with no `icon_url` (12,329 of them) could fall back to the site's favicon.
- [ ] The design report's rule that a feed with a discovered row is shown as a card again only once something from it is saved was simplified: only a verdict (pass, subscribe, unsubscribe) or a subscription keeps a feed off the cards, so a feed whose post was opened but not saved can be suggested again. Decide whether that is wanted.

### Kobo sync

The Kobo syncs directly with the self-hosted server, protocol server/1 (`docs/kobo-sync.md`); the phone only registers it by scanning its pairing code. See "Sync with Kobo" in `docs/how-it-works.md`. The earlier ways (over the local network with the phone serving it, then through a relay while a session was open on the phone) are removed, with their code and docs.

- [x] Pairing code v2, scanned or pasted on the phone and sent to the server, which derives the Kobo's token (HKDF of the secret) and keeps only its hash; the Kobo derives the same token.
- [x] The Kobo's server address in `config.json` (the server serves one) or on the restored letter keyboard; the pairing wait on `/kobo/hello`; "removed from your account" once a paired Kobo's token is refused.
- [x] Events, the events gate, the manifest, bundles by version, the saved shelf, the picture zip, and a wipe when the `dbId` changes, checked at `/kobo/hello` before anything is uploaded. A Kobo that synced through the relay wipes what it holds before its first upload to the server.
- [x] Pictures prepared on the server (JVM port of the phone's code: grey JPEG or PNG, cached, warmed at each build for the reader's Kobo widths).
- [x] Fixtures in `docs/kobo-sync-fixtures/` written by the server's export path (`BundleExportTest`) and parsed by the Kobo's tests.
- [ ] **Run it against a real Kobo and a real server**, and look at the phone's Kobo screen on a phone, a tablet and in E-ink, and the QR code scanned off a real e-ink screen.
- [ ] **A Kobo interop test against `installDist`** (`kobo/tests/server_interop.rs`); see "Done: self-hosted server", Open.
- [ ] **Bundle size.** Back-page index stories carry their full stored text, like every other story, so an edition with a long index may make a bundle of several megabytes. Measure a real one; if it is too big, send index stories without `blocks` (the Kobo already falls back to the summary).
- [ ] **Events after the next build** have already counted their stories as unopened for that build (the known v1 limit), from a Kobo or from a phone that was offline. Merging could undo the run's count for a story opened elsewhere; not done.
- [ ] **The JPEG/PNG choice** is a tone count, tested on drawn examples, not on a corpus of real article pictures. Check a real edition's split and sizes, and how a photograph on a large white page fares.
- [ ] **Picture preparation is not measured on a real server**: how long 600 take, and the memory a large original costs while decoding (bounded by sampling and 4 million pixels).
- [ ] **A Kobo whose clock is far off can't check an `https://` server's certificate** before the server has ever answered it, since the clock is corrected only from the server's `Date` header. The build date is the floor. Check how far off a Libra 2's clock gets, and whether Nickel's Wi-Fi sets it.

## Smaller gaps against the original spec

Specified but not built, and not assigned to a milestone.

- [ ] **Scores kept current during refresh.** The spec wanted scores updated at sync so a fallback build mostly reads a sorted list. Scores are computed at build time instead, on the server. Decide whether this is needed once builds are measured on a real server.
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

Built and wired into every edition build; how it works is under "Ranking posts within a feed", "Post signals" and "The embedder" in `docs/how-it-works.md`. It is **not proven**: it has been tested on simulated readers and never on real reading history. It now runs on the server.

- [x] **The label**: a post's engagement against its own feed's rate, from the same evidence as feed affinity (opens, reads, Saves, Likes, and runs unopened in superseded editions).
- [x] **Feed categories** parsed from RSS, Atom and JSON Feed and stored per post (`article_category`).
- [x] **Structural traits** per post (`article_traits`): link and image counts, code, attachment kind, link post, title prefix; computed at sync and by `TraitsBackfill`.
- [x] **Text embeddings** per post (`article_embedding`) from a bundled static model (`potion-base-8M`, 7.9 MB asset, no ML library), computed by `EmbeddingBackfill`. This replaced the EmbeddingGemma idea: no download, and fast enough to embed a whole library in the background.
- [x] **The W factor**: per-feed lifts for category, length, attachment, link post, code and title prefix, plus a text taste learned across feeds and checked out of sample; keys capped to 0.7 to 1.4 and W as a whole to 0.5 to 2.0, shrunk to 1 until there is evidence; named in "Why is this here?" when it is the strongest pull.
- [x] **Database version 2** with a migration, and background upkeep that fills the new tables (on the server: after each refresh that stores posts, and after an import).
- [x] **Posts in non-Latin scripts** get no vector, because the English model would encode their script; they are ranked on categories and traits.
- [x] **The length key reads the feed's own word count** (`article.feedWordCount`), which full-text fetching never changes. Before, an excerpt-only feed's opened posts counted as long and its new posts as brief, and the new posts were marked down together. For a library upgraded from version 1 the count is estimated from the summary where a fetched page had already replaced the feed's text.
- Considered and not built: a small generative model asked to rank (foreground-only or over a gigabyte, slow, hard to explain) and gradient-boosted trees (they memorise posts at this number of labels).

Open:

- [ ] **Validate offline on real labels. This was skipped.** Export labels from a few weeks of real use, train on the first weeks, predict the last, and compare against the score without W on "was the opened post ranked above the unopened ones from the same feed". Tune or remove whatever does not clearly beat the old ranking. Every constant under "W" in `RankingConfig` is a guess until this is done. There is no label export yet; the archive file holds what is needed (engagement, editions, categories) except traits and vectors, which can be rebuilt.
- [ ] **Run it on a real server.** Nothing here has: the upkeep's first pass over an imported library of tens of thousands of posts (embedding ran at about 20,000 posts a second on a desktop JVM; the database writes are the cost, in transactions of 500), and the build cost below.
- [ ] **Build cost.** On a desktop JVM, loading signals and learning added a median of 37 ms (30 ms to learn from 3,000 labelled posts, 7 ms to read 1,000 candidates) to a 171 ms build; the vectors themselves made no measurable difference. It grows with the number of stories printed in the last 180 days, per reader. If it is too slow on a small server, the options are a shorter window for W than for affinity, or learning the model at refresh and storing it.
- [ ] **Traits are not recomputed when full text is fetched later**, and a post is embedded once, from the text stored at the time. Code, links and images in the fetched page are missed until the trait rules' version is raised.
- [ ] **Weekly and rare feeds gain little from keys.** Their decayed exposure levels off near 7 posts, so their keys never get past about a third of full strength. Only the text taste, which is pooled across feeds, can order their posts. (The affinity prior is now scaled to cadence; the keys' trust `E / (E + 12)` and key prior are not, because a key's few posts would then decide it on a single read.)
- [ ] **The embedding model is English-only.** Other Latin-script languages are embedded but their vectors are mostly noise; non-Latin scripts are not embedded at all. A text that mixes scripts is judged by letter count, and one ideograph counts as one letter, so a Japanese post with many English names can pass.
- [ ] **`media:content` audio and video are not treated as attachments.** Only `<enclosure>`, Atom enclosure links and JSON Feed attachments are, so some video and podcast feeds have no attachment key.
- [ ] **Category names in reason lines are the lowercased keys** ("posts about space"), not the feed's spelling. The label is stored; the engine is only given the key.
- [ ] **Categories of old posts come back only from the feed.** After the upgrade, posts that have dropped out of their feed's document never get categories.
- [ ] **Posts can be embedded after the build that ranks them.** The upkeep runs inside each refresh that stores posts, so a Print now or Extra edition normally ranks its new posts with vectors; but when a pass is already running, the new posts wait for it to go round again, which can be after the build.
- [ ] **A marked post is never retried.** A post marked as unembeddable (no known words, or non-Latin) stays marked until the model ID changes, even if better text is fetched for it.
- [ ] **The debug sample data has no categories**, so it shows length, code, link-post and text signals only.
- [ ] Tuning: the image and link counts are stored and unused; decide after validation whether they earn a key.

## Later

Roadmap ideas, not scheduled.

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
| A hosted service | Decided against. Accounts and cross-device sync, once decided against for a fully local app, now exist only on a server the reader runs themselves (`docs/server.md`); there is no service run for them. |
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
- [ ] **The server's scheduler has only run under tests** and in the NixOS VM test, never overnight for real readers. Whether the phone's sync after a release runs in time under Doze is unknown too; until it does, the stand shows the placeholder.
- [ ] **Relearning affinity after engagement changes** (debounced 10 seconds on the server) is unmeasured for performance on a large account.
- [ ] **OPML export on a device is untested.** The writer is unit-tested; the file-picker path is not.
- [ ] **E-ink: a long page can end on a mostly blank screen**, because steps snap to anchors.
- [ ] **E-ink: very long code lines still wrap.** Code is set smaller until its longest line fits, down to 70% of the usual size; lines longer than that wrap.
- [ ] **`targetSdk` is 36 while `compileSdk` is 37.**
- [ ] **E-ink has no tap zones.** Paging is by swipe, keys and the Previous and Next buttons.
- [ ] **Source comments still cite the deleted plan files** by name ("v1 Live feed", "v2 Daily edition") in `engine/RankingConfig.kt`, `Cadence.kt` and `Affinity.kt`. Point them at `docs/how-it-works.md` when those files are next touched.

- [ ] In a narrow story cell a long byline runs to a second line ("Source ·" over "4 min read") instead of being cut off. It leaves the dot at the end of the first line.
- [ ] Undo on the Saved and Liked shelves puts the story back at the top, because re-saving stamps a new time. Exposing `savedAt` / `likedAt` on `ArticleSummary` would fix it.
- [ ] TalkBack and right-to-left layouts have not been tested at all. (A `PaperDialog` now hides the screen beneath it from screen readers; that was checked in the semantics tree, not with TalkBack.)
- [ ] Comments in `ui/reader/ReadRule.kt` and `ScorerTest.kt` may also refer to the deleted v1/v2 plan files.

- [ ] **Milestone 3 health, storage and archive screens are unchecked on a device**: the Subscriptions desk, the Quiet group in Sources, the back-page desk notice, the storage budget stepper and usage bar, and archive export through the file picker. Their logic is unit-tested.
- [ ] **Measuring stored text reads every body's length** (two full scans) of the phone's cache after each sync and when Settings opens. Unmeasured on a large cache.
- [ ] **The image store, the full-screen picture and the greyscale option are unchecked on a device.** Their logic is unit-tested, with real decoding and encoding under Robolectric.
- [ ] **Repair onto a common path can pick the wrong feed** when the broken feed was one of several on a site (for example a category feed, and `/feed` is the whole site). The reader is told through the Moved flag.
- [ ] **A Like or save removed after an export comes back on import** into the server, because the archive cannot tell "never Liked" from "un-Liked since".

## Open questions

- [x] **While you were away:** drawn from stored editions that were released and never opened. Posts of a gap with no edition are ordinary new candidates.
- [x] **Evening edition:** released at its own setting, 18:00 by default. The morning edition's window runs from the previous morning cutoff and leaves out what the evening edition printed.
- [x] **Welcome back:** a boost of 1.5 after 90 quiet days.
- [x] **Freshness floor:** applied. A new post under the floor is listed in the index and not printed; held-over posts are exempt.
- [ ] **`targetSdk`:** raise to 37 to match `compileSdk`?

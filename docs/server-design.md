# Morning Issue self-hosted server: design

Status: design contract for the `self-hosted` branch. Every section is normative for the implementation agents unless it says "suggested". Paths are relative to the repository root `/home/tania/Code/morningissue`.

I worked this out from `docs/how-it-works.md`, `docs/kobo-sync.md`, `docs/relay-sync.md`, `kobo/README.md`, `relay/README.md`, `todo.md`, every package under `app/src/main/java/net/tgnra/morningissue/`, the build files, `kobo/src/*` and git history. Two finds from history save real work:

- **The old LAN v1 Kobo client is still in history.** It used a bearer token and per-request `GET /v1/manifest`, `POST /v1/events`, `GET /v1/editions/{id}`, `GET /v1/saved` and `GET /v1/images`. See `git show eb75c12^:kobo/src/sync.rs` and `git show eb75c12^:kobo/tests/sync.rs`.
- **The Kobo once had an on-screen letter keyboard for typing an address.** It was added in `1453bde` and removed in `bb332ac`. Recover it with `git show bb332ac^:kobo/src/<file>`.

---

## 0. Decisions at a glance

| Topic | Decision |
|---|---|
| Modules | `:core` (pure JVM, Kotlin), `:server` (Ktor + Room 3 on JVM), `:app` (Android client). `:macrobenchmark` stays, retargeted later. |
| Server persistence | **Room 3 on JVM** with `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`), a new multi-user schema `ServerDatabase`. DAOs and the publisher are ported from `app/` by threading `userId` through. Fallback if the Phase-1 spike fails: sqlite-jdbc plus hand-written DAOs. |
| HTTP | Ktor server, CIO engine, kotlinx.serialization JSON. |
| Config | TOML (`org.tomlj:tomlj`), plus environment overrides for Docker. |
| Auth | Users listed in config. Random first password goes to the log and to `<dataDir>/initial-passwords/<user>.txt`. Passwords hashed with PBKDF2-HMAC-SHA256 (JDK only, 600k iterations). Opaque bearer device tokens; only their SHA-256 is stored. |
| Events gate | Every edition-data GET carries `X-Events-Through: <highest event seq the client holds>`. The server answers `409 events_pending` if that device's `acked_seq` is lower. Stateless, and the same rule for phone and Kobo. |
| Kobo transport | Plain HTTPS to the server, same `/api/v1` API, bearer token derived from the secret already in the QR (`HKDF(S, "morningissue server token v1")`). The Go relay, MIR1 envelopes and request signing are deleted. |
| Android local storage | Keep `MorningIssueDatabase` and its entities as a **replica cache** with server IDs. Schema 7 adds one `outbox_event` table. UI read paths stay as they are; writers are replaced. |
| Legacy upload | Existing archive format v2, extended with optional fields (no version bump), streamed to `POST /api/v1/import`. Default on a non-empty account is **merge**, using the existing archive merge rules. |
| Images | The phone keeps loading images from their origin through its own image store (unchanged). The server only prepares greyscale images for Kobos, with an LRU cache. |
| Packaging | `application` plugin `installDist` → Dockerfile (Temurin 21 JRE, glibc) and a Nix flake (`gradle.fetchDeps` mitmCache) with package, overlay, NixOS module and a VM test. |

---

## 1. Gradle module split

### 1.1 Build changes (Phase 1)

- **`settings.gradle.kts`**:
  - `include(":core", ":server")`.
  - Include `:app` and `:macrobenchmark` only when the Gradle property `serverOnly` is absent: `if (!providers.gradleProperty("serverOnly").isPresent) include(":app", ":macrobenchmark")`.
  - `./gradlew -PserverOnly :server:installDist` must work with no Android SDK and `ANDROID_HOME` unset. AGP jars are still resolved through the root `apply false`; that is accepted.
- **Root `build.gradle.kts`**: add `alias(libs.plugins.kotlin.jvm) apply false`.
- **`gradle/libs.versions.toml`**, added in Phase 1 so no workstream edits it:
  - plugin `kotlin-jvm` (`org.jetbrains.kotlin.jvm`, `version.ref = "kotlin"`);
  - libraries `androidx-sqlite-bundled` (`androidx.sqlite:sqlite-bundled`, ref `sqlite`), `kotlinx-coroutines-core`;
  - `ktor-server-core`, `-cio`, `-content-negotiation`, `-call-logging`, `-status-pages`, `-auth`, `-test-host`, `ktor-serialization-kotlinx-json`;
  - `tomlj`, `logback-classic`;
  - `twelvemonkeys-imageio-jpeg`, `-webp`, `-core` (JVM image decoding for Kobo pictures).
- **`core/build.gradle.kts`**:
  - Plugins: `kotlin-jvm` and serialization. `jvmTarget = 17` because Android consumes it. No `jvmToolchain`, because the Nix sandbox cannot download toolchains.
  - `api`: kotlinx-serialization-json, kotlinx-coroutines-core, okhttp.
  - `implementation`: jsoup, readability4j.
  - **`compileOnly(libs.kxml2)`.** Android supplies `org.xmlpull` from the platform; bundling kxml2 in the APK trips lint's `DuplicatePlatformClasses`.
  - Tests: `testImplementation` junit, kxml2, mockwebserver, coroutines-test.
- **`server/build.gradle.kts`**:
  - Plugins: `kotlin-jvm`, serialization, ksp, `androidx-room3`, `application` (`mainClass = net.tgnra.morningissue.server.MainKt`, `applicationName = "morningissue-server"`).
  - Dependencies: `implementation(project(":core"))`, **`runtimeOnly(libs.kxml2)`** (kxml2 registers itself for `XmlPullParserFactory` through `META-INF/services`), room3-runtime, sqlite-bundled, ktor, tomlj, logback, twelvemonkeys. `ksp(room3-compiler)`.
  - `room3 { schemaDirectory("$projectDir/schemas") }`. `jvmTarget = 21`.
- **`app/build.gradle.kts`**: `implementation(project(":core"))`. Remove the `RELAY_*` buildConfig fields in the Android workstream (W-D), not in Phase 1.
- **Binary assets move to a top-level `assets/`.** `app/src/main/assets/embedding/potion-base-8m.bin` and `app/src/main/assets/discovery/catalogue.db` go to `assets/embedding/` and `assets/discovery/`.
  - App: `android.sourceSets.main.assets.directories.add("$rootDir/assets")`. W-D removes this line.
  - Server: `sourceSets.main.resources.srcDir("$rootDir/assets")`.
  - Update the paths in `tools/discovery/*.py`, `tools/embedding/build_asset.py`, `tools/discovery/README.md` and `SOURCES.md`, and `.github/workflows/discovery-catalogue.yml`.
  - Licences stay in `app/src/main/assets/licenses/`. Copy `potion-base-8M-MIT.txt` and `discovery-catalogue.txt` into `server/src/main/resources/licenses/`.

### 1.2 What moves to `:core`

Package names stay the same (split packages across modules are fine on JVM and Android), so imports in `:app` do not change. Use `git mv` so history follows the files.

| From `app/src/main/java/net/tgnra/morningissue/` | To `core/src/main/kotlin/net/tgnra/morningissue/` | Blocker and fix |
|---|---|---|
| `model/*` (8 files) | `model/` | None |
| `engine/*` (12 files) | `engine/` | None; already pure |
| `embedding/StaticEmbedder.kt`, `WordPieceTokenizer.kt`, `EmbeddableText.kt` | `embedding/` | `EmbedderAsset.kt` uses `Context`/`AssetManager`; it stays in `:app`. The server loads the table with `StaticEmbedder.load(ByteBuffer)` from a classpath resource read into a heap buffer (8 MB). |
| `sync/FeedDates.kt`, `FeedFetcher.kt`, `FeedParser.kt`, `XmlFeedParser.kt`, `JsonFeedParser.kt`, `XmlText.kt`, `ParsedFeed.kt`, `HtmlBlocks.kt`, `Opml.kt`, `FeedDiscovery.kt`, `DefaultFeedDiscovery.kt`, `FeedRepairer.kt`, `FeedSyncer.kt` (interface), `FullTextFetcher.kt` (interface + result), `CandidateFetcher.kt` | `sync/` | `XmlPullParser` becomes `compileOnly` kxml2 in core. `CandidateFetcher` already takes a cache `File` and an `isMetered` lambda. |
| `sync/DefaultFullTextFetcher.kt`, **split** | core `sync/FullTextExtractor.kt`: fetch page, Readability4J, blocks, `isTeaser`, `looksComplete` | The DB writes (`articleDao.storeContent`, image URL update) stay in an app class `DefaultFullTextFetcher` that delegates to `FullTextExtractor`. |
| `kobo/KoboProtocol.kt`, `kobo/GreyImage.kt` (GreyImage, GreyPlan, GreyPng, GreyJpeg) | `kobo/` | None |
| `kobo/EventMerge.kt`, **split** | core `kobo/KoboEvents.kt` (the `object KoboEvents` parser and event model) | `KoboEventMerger` (Room) stays in app. |
| `data/archive/ArchiveFormat.kt` | `data/archive/` | None. Phase 1 also adds the optional fields in section 4.13. |
| `data/repository/FtsQuery.kt` | `data/repository/` | None |
| `data/images/ImageFormats.kt`, `ImageDownload.kt` | `data/images/` | OkHttp only |
| `edition/DueEditions.kt` | `edition/` | Pure; the server's `/stand` uses it |

New in core, written in Phase 1:

- **`core/src/main/kotlin/net/tgnra/morningissue/api/`**: every `@Serializable` request and response DTO in section 4, `ApiPaths` constants, `ApiError` codes and the `EVENTS_THROUGH_HEADER = "X-Events-Through"` constant. **This is the contract between server, Android and tests.**
- **`api/client/MorningIssueApi.kt`**, OkHttp-based and pure JVM. Phase 1 writes the interface only; W-D owns the implementation. The server's end-to-end tests use the same client.

Tests that move with their code (`app/src/test/...` → `core/src/test/kotlin/...`):

- `engine/*Test` (19 files).
- `embedding/` except `EmbedderAssetTest`. `StaticEmbedderAssetTest` and `CatalogueParityTest` read from `$rootDir/assets/...`.
- `sync/`: `FeedParserTest`, `FeedDatesTest`, `HtmlBlocksTest`, `OpmlTest`, `FeedDiscoveryTest`, `FeedFetcherTest`, `FeedRepairerTest`, `FeedSignalsParserTest`, `Fixtures.kt`, and the extractor half of `FullTextFetcherTest`.
- `model/*Test`, `kobo/GreyImageTest`, `edition/DueEditionsTest`.
- Fixtures `app/src/test/resources/sync` and `embedding` → `core/src/test/resources/`. App tests that still need them get `test.resources.srcDir("$rootDir/core/src/test/resources")`.
- `ParsersOnAndroidTest` stays in app.

### 1.3 What stays in `:app` after Phase 1

Everything with Room, DataStore, WorkManager, Coil, `android.*` or CameraX: `data/db`, `data/repository/Room*`, `AffinityLearner`, `WithinFeedLearner`, `data/signals`, `data/discovery`, `data/storage`, the rest of `data/images`, `data/settings`, `data/archive/ArchiveTransfer.kt` and `ArchiveController.kt`, the rest of `sync/` (the syncers, workers, health, teaser, OPML transfer), `edition/` (except `DueEditions`), `kobo/BundleExport.kt`, `KoboImages.kt`, `InstallId.kt`, `relay/`, `ui/`, and `AppContainer`.

Phase 2 **copies** the server-relevant ones into `:server` and adapts them for multiple users. W-D then **deletes** them from `:app` (section 5). Nothing DB-bound goes into `:core`, because the server and client schemas differ.

### 1.4 `:server` package layout

All under `server/src/main/kotlin/net/tgnra/morningissue/server/`:

```
Main.kt                 CLI: serve | reset-password <user> | import <user> <file.zip>
config/                 TOML + env loading                                    (W-C)
auth/                   passwords, tokens, user bootstrap, Ktor auth plugin   (W-C)
http/                   Ktor routes, error mapping, request parsing          (W-C)
service/                interfaces only, written in Phase 1                   (contract)
db/ServerDatabase.kt    entity list + DAO accessors                           (Phase 1; shared)
db/AccountEntities.kt   users, device, user_settings, user_state, import_log (W-C)
db/FeedEntities.kt      feed, feed_status, article*, signals                  (W-A)
db/UserFeedEntities.kt  subscription, tag, subscription_tag, feed_flag, discovery_verdict (W-A)
db/EditionEntities.kt   engagement, edition, edition_item, edition_page_view  (W-B)
db/<Area>Dao.kt         one file per owner (section 8)
feeds/                  refresher, full text, signals, health, repair, discovery, OPML, search (W-A)
editions/               publisher, learners, scheduler, stand, jobs, bundles, Kobo images (W-B)
sync/                   event merge, manifest, gate                           (W-B)
importer/               legacy archive import                                 (W-B)
```

---

## 2. Server data model

### 2.1 Is reusing Room viable?

Yes. Recommended, with a Phase-1 spike that proves it.

- **Room 3 is Kotlin Multiplatform.** `androidx.room3` publishes `-jvm` artifacts. `Room.databaseBuilder<ServerDatabase>(name = path)` needs no `Context` on JVM. `BundledSQLiteDriver` ships its own SQLite, with native libraries for linux-x64 and linux-arm64 inside the jar, compiled with FTS3/4/5. KSP runs on a `kotlin("jvm")` module.
- **What is reused nearly verbatim:**
  - entity annotations, `withWriteTransaction` and `withReadTransaction`, schema export and in-memory test databases (no Robolectric needed on the server);
  - the SQL of ~1,500 lines of DAOs (edited, not rewritten);
  - `RoomEditionPublisher` (462 lines), `AffinityLearner`, `WithinFeedLearner`, `DefaultFeedSyncer`, `FeedHealthChecker`, `TraitsBackfill`, `EmbeddingBackfill`, `KoboEventMerger`, `KoboExporter`, `RoomArchiveTransfer.import`.
- **The porting work** is adding `userId` to every query over per-user tables and replacing `feed` with `subscription JOIN feed` where the app read per-user columns from `feed`. Exposed or SQLDelight would mean re-expressing every query from scratch.
- **Spike acceptance (Phase 1):**
  - an in-memory `ServerDatabase` with one ordinary entity and the FTS4 `article_fts` table runs `MATCH` and `snippet()` in `:server:test`;
  - a file database in WAL mode opens under `./gradlew -PserverOnly :server:test` on Linux x86-64;
  - the native library extracts to a configurable temp dir. Set `-Djava.io.tmpdir=<dataDir>/tmp` in the launcher, so it works under systemd `DynamicUser` and read-only roots.
- **Concurrency:**
  - Room's JVM pool gives one writer and several readers in WAL mode.
  - Builds run under a per-user `Mutex`; refreshes write one transaction per feed.
  - Use `setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(8))`.
  - Fine for the handful of users a self-hosted install has.

### 2.2 Tables

Conventions:

- Times are `Instant`, stored as epoch ms through the existing `Converters`, copied to `server/db/Converters.kt`.
- `user_id` is `INTEGER` with a foreign key to `users(id)` and `ON DELETE CASCADE`.
- Row IDs (`feed.id`, `article.id`, `edition.id`) are global and are the IDs every client sees.

**Global tables** (shared by all users)

| Table | Columns | Origin |
|---|---|---|
| `users` | `id` PK, `username` UNIQUE, `password_hash`, `must_change_password` bool, `disabled` bool, `db_id` (random UUID; the manifest's `dbId`), `created_at`, `change_seq` (per-user counter for engagement and edition deltas) | new |
| `device` | `id` TEXT PK (client UUID), `user_id`, `kind` (`PHONE`\|`KOBO`), `name`, `width` (Kobo, 160..2048), `token_hash` UNIQUE (hex SHA-256), `acked_seq` default 0, `created_at`, `last_seen_at`, `revoked_at` | replaces `sync_peer` |
| `feed` | `id`, `url` UNIQUE, `url_key` UNIQUE (`FeedUrls` normalisation), `site_url`, `title` (from the feed document), `icon_url`, `median_interval_hrs`, `burstiness`, `tier`, `etag`, `last_modified`, `added_at`, `last_attempt_at`, `last_success_at` | `feed` minus per-user columns |
| `feed_status` | `feed_id` PK, `last_post_at`, `error_streak`, `error_since`, `last_http_status`, `last_repair_at`, `moved_from_url`, `moved_at` | global half of `feed_health` |
| `article` | as today: `feedId`, `guid` (unique together), `url`, `title`, `summary`, `imageUrl`, `author`, `publishedAt`, `firstSeenAt`, `wordCount`, `feedWordCount`, `contentHash` | unchanged |
| `article_content`, `article_fts` (FTS4), `article_category`, `article_traits`, `article_embedding` | unchanged | unchanged |
| `import_log` | `user_id`, `idempotency_key` (PK together), `report_json`, `completed_at` | new |

**Per-user tables**

| Table | Columns | Origin |
|---|---|---|
| `subscription` | PK (`user_id`, `feed_id`); `title_override` (rename), `slider_step`, `mute_until`, `affinity` (learned, cached for the UI), `status` (`SUBSCRIBED`\|`DISCOVERED`), `added_at`, `discovered_at`, `notify` | per-user half of `feed` |
| `tag` | `id`, `user_id`, `name`, `affinity`; UNIQUE (`user_id`, `name`) | `tag` |
| `subscription_tag` | PK (`user_id`, `feed_id`, `tag_id`); FK to `subscription` and `tag`, both cascade | `feed_tag` |
| `feed_flag` | PK (`user_id`, `feed_id`); `last_flag`, `flag_dismissed_until`, `moved_acknowledged_at` | per-user half of `feed_health` |
| `engagement` | PK (`user_id`, `article_id`); `openedAt`, `readFraction`, `readAt`, `savedAt`, `likedAt`, `editionsRunUnopened`, `leadCount`, `change_seq` | `engagement` |
| `edition` | `id` (global), `user_id`, `date`, `kind`, UNIQUE (`user_id`, `date`, `kind`); `cutoffAt`, `releaseAt`, `builtAt`, `length`, `number` (per user), `openedAt`, `finishedAt`, `pageViewsTracked`, `change_seq` | `edition` |
| `edition_item` | unchanged (PK `editionId`, `articleId`) | unchanged |
| `edition_page_view` | unchanged | unchanged |
| `discovery_verdict` | PK (`user_id`, `url_key`); rest unchanged | `discovery_verdict` |
| `user_settings` | `user_id` PK, `json` (`ServerSettings`, section 4.4), `customized` bool (false until the first PATCH or a migration apply), `updated_at` | DataStore |
| `user_state` | PK (`user_id`, `key`), `json` | the `desk_notice` prefs, `DeckStore`, the last build outcome |

Dropped on the server: `stored_image` (the phone keeps its own), `discovery_candidate` (the crawler is not built; re-add later), `sync_peer`.

### 2.3 Rules the schema implies

- **Effective first-seen time per user** is `MAX(article.firstSeenAt, subscription.added_at)`. A user who subscribes to a feed the server already holds sees its posts as new from the moment they subscribed, still subject to the 30-day publish-age limit. This matches the app's "add a feed, everything stored now" behaviour.
  - Implement it in `EditionArticleDao.firstSeenBetween(userId, after, upTo)` as the union of `a.firstSeenAt` in the window, and `s.added_at` in the window with `a.firstSeenAt < s.added_at`. That keeps the `(firstSeenAt, feedId)` index usable.
  - Extra editions' "posts first seen in the last 3 days" uses the same expression.
- **A query over a per-user table must name `user_id`.** Port `SubscribedOnlyQueryTest` as `server/src/test/.../db/UserScopedQueryTest.kt`. It reflects over every DAO `@Query` that mentions `subscription|tag|engagement|edition|feed_flag|discovery_verdict|user_settings|user_state|device` and fails without `user_id`/`userId`, except an explicit allow-list (global GC, the scheduler's user scan).
- **Subscribed-only queries** keep the existing rule ("every query over feed/article says which status it wants") by going through `subscription.status`.
- **Unsubscribing**:
  - deletes `subscription`, `subscription_tag` and `feed_flag`;
  - deletes that user's `engagement` and `edition_item` rows for the feed's articles (parity with "removing a feed deletes … its placements");
  - writes a `DROPPED` verdict.
- **Global garbage collection** runs daily. It deletes feeds with no `subscription` rows at all, and their articles, unless an article is referenced by any `edition_item` or any `engagement` with `savedAt` or `likedAt`. A feed with zero `SUBSCRIBED` rows is never refreshed.
- **Repair** (`FeedRepairer`) changes `feed.url` globally and never takes a URL that another `feed` row has.
  - It sets `feed_status.moved_at`.
  - Each subscriber then sees `Moved` until they acknowledge it (`feed_flag.moved_acknowledged_at >= moved_at`).
- **Health flags.** `Broken`, `Moved` and `Gone quiet` come from global stats. `Noisy`, `Ignored` and `Loved` come from per-user engagement. Dismissals are per user. `FeedHealthRules` runs per (user, feed) as today.

---

## 3. Scheduling

One `Scheduler` coroutine in `server/editions/Scheduler.kt` (W-B) ticks every 30 seconds.

**Why a tick:** a tick is robust to settings changes, time-zone changes, DST and server restarts with no re-arming logic. With users in single digits, each tick costs a few microseconds of `EditionClock` maths per user.

**Config defaults** (`[editions]` and `[refresh]` in TOML):

| Setting | Default | Meaning |
|---|---|---|
| `buildLeadMinutes` | 30 | Build this long before a release |
| `prePrintMaxAgeMinutes` | 120 | The 2-hour leeway |
| `refresh.intervalMinutes` | 120 | Periodic refresh cadence (today's 2-hour periodic sync) |
| `refresh.concurrency` | 6 | Feeds fetched at once |
| `refresh.timeoutSeconds` | 240 | Pre-print refresh limit (today's 4 minutes) |

```
state (in memory, rebuilt on start):
  lastPeriodicAt: Instant? = DB max(feed.last_attempt_at) at start
  buildsDone: Map<UserId, Set<Instant>>   // release instants handled; also persisted in user_state["lastBuild"]

every 30 s:
  now = clock.instant()
  dueBuilds = []
  for user in enabledUsers():
      s = settings(user); zone = ZoneId.of(s.timeZone)
      sched = s.schedule()                                  // engine ReleaseSchedule, incl. resetChange/eveningChange
      next = sched.nextRelease(now, zone)                   // morning or evening
      buildAt = next.at - buildLead
      if now >= buildAt && next.at !in handled(user):
          dueBuilds += (user, next)
      // catch-up after downtime: the latest release passed, its edition is missing, nothing-new not recorded,
      // and it is still current (DueEditions.status(...) == NotPrinted) and has not been tried since the server
      // started: run a late build once (ensureCurrentEdition). Any later failure is left to Print now.
      latest = DueEditions.status(now, sched, zone, releasedSummaries(user))
      if latest is NotPrinted && latest.release.at !in triedLate(user): dueBuilds += (user, latest, late = true)

  if dueBuilds not empty:
      launch(buildScope) {
          // refresh shared feeds together: one union, one pass
          feeds = union(subscribedFeedIds(u) for (u, _) in dueBuilds)
          refresher.refresh(feeds, maxAge = prePrintMaxAge, deadline = now + refreshTimeout)
              // skips any feed with last_attempt_at >= now - maxAge (attempt, not success,
              // so a broken feed is not hammered); in-flight fetches are shared (see below)
          for (u, release) in dueBuilds:              // one at a time per user; users in parallel (limit 2)
              userMutex(u).withLock { runBuild(u, release) }
      }

  if lastPeriodicAt == null || now - lastPeriodicAt >= refreshInterval:
      lastPeriodicAt = now
      launch(refreshScope) {
          feeds = feedsWithAnySubscribedSubscriber()
          refresher.refresh(feeds, maxAge = refreshInterval - 5 min)
          maintenance()   // health stats + per-user flags for refreshed feeds' subscribers, signals upkeep,
                          // discovery cleanup (7-day discovered posts), Kobo image cache trim; daily: global GC
      }

runBuild(user, release):
  refreshed = refresher.lastReportCoversUserFeeds(user)  // "reached the feeds" = finished in time and >= 1 answered, or no feeds
  id = publisher.buildNextEdition(user, cutoffAt = now, releaseAt = release.at)   // late=true: ensureCurrentEdition(user)
  if id == null && refreshed: settings.recordNothingNew(user, release.at)          // DueEditions' nothingNewFor
  if id != null:
      fullText.ensureForEdition(id, concurrency = 4)          // every printed story, briefs included
      koboImages.prepare(id, widths = kobo widths of user)    // warms the grey cache so Kobo sync is fast
  markHandled(user, release.at)                               // persisted in user_state
```

**`FeedRefresher`** (interface in `service/`; W-A implements it in `feeds/FeedRefresher.kt`, ported from `DefaultFeedSyncer`):

- A global `ConcurrentHashMap<FeedId, Deferred<FeedResult>>` dedupes in-flight fetches. A second caller for the same feed awaits the same `Deferred`, so A's and B's builds starting together fetch a shared feed once.
- `maxAge` filtering reads `feed.last_attempt_at` at call time.
- Every fetch sets `last_attempt_at`, and `last_success_at` on success.
- New posts go into the global `article` table with `firstSeenAt = now`, plus categories and traits, and teaser full-text fetches (at most 10 per feed per refresh, newest first, two at a time; the existing rules).
- After the batch, signals upkeep runs (traits, then embeddings). Health runs in `maintenance()`.

Worked example: A releases at 04:00 and B at 05:00, both subscribing to feed X.

- A's build starts at 03:30 and refreshes X.
- B's build starts at 04:30. X's `last_attempt_at` is 03:30, an hour old, so B uses the stored posts.
- The periodic refresh fetches X only once it is more than 115 minutes old.

**Time zones.** `ServerSettings.timeZone` is an IANA ID, set from the phone at migration and login. The phone sends a `PATCH` when the device's zone changes and `followDeviceTimeZone` is on (the default). `EditionClock` and `ReleaseSchedule` already take a `ZoneId`, so `RoomEditionPublisher`'s `zone` parameter becomes `{ settings(user).zone }`.

**Release** needs no job. Clients list editions whose `releaseAt <= now`.

**Release-time changes** are a server operation: `PUT /api/v1/settings/release` runs `ReleaseTimes` logic server-side and stores `resetChange` and `eveningChange` in `ServerSettings`. The scheduler reads the new schedule on its next tick, which replaces `scheduleEarlyBuild()`.

---

## 4. HTTP API

Conventions:

- Base path `/api/v1`. JSON is UTF-8.
- Times are ISO-8601 UTC instants (`2026-11-11T06:30:00Z`); dates are `YYYY-MM-DD`; local times are `HH:mm`. This matches `docs/kobo-sync.md`, which supersedes the archive's epoch-ms convention on the wire.
- Unknown fields are ignored on both sides.
- Errors are `{"error": "<code>", "message": "<human text>"}`.

| Status | Codes |
|---|---|
| 400 | `bad_request`, `events_through_required`, `weak_password` |
| 401 | `unauthenticated`, `unknown` (a Kobo token not paired yet, or revoked), `invalid_credentials` |
| 403 | `password_change_required`, `forbidden_for_device` (a Kobo calling a phone-only endpoint), `account_disabled` |
| 404 | `not_found` |
| 409 | `events_pending` (body also has `"acked": n`), `conflict` |
| 413 | `too_large` |
| 429 | `too_many_attempts` |
| 503 | `busy` (an import is already running for the account) |

Auth is `Authorization: Bearer <token>` on everything except `/health`, `/auth/login` and `/kobo/config.json`.

- Phone tokens may call everything.
- Kobo tokens may call only `/sync/*`, `/editions/{id}/bundle`, `/saved`, `/kobo/*`.
- While `must_change_password` is set, every endpoint except `/auth/password`, `/auth/logout` and `/account` returns `403 password_change_required`.

Each request updates `device.last_seen_at`, throttled to once a minute.

### 4.1 Auth and account

| Method and path | Request | Response |
|---|---|---|
| `GET /health` | | `200 {"ok":true,"version":"x.y.z","api":1}` |
| `POST /auth/login` | `{"username","password","device":{"id":"<uuid>","name":"Pixel 8","kind":"PHONE"}}` | `200 {"token","deviceId","mustChangePassword":bool,"user":{"username","dbId"}}`; 401 `invalid_credentials`; 403 `account_disabled`; 429 (5 failures per username per 15 minutes) |
| `POST /auth/password` | `{"current","new"}`; at least 10 characters | `204`; 401 `invalid_credentials`; 400 `weak_password`. Clears the flag and deletes `initial-passwords/<user>.txt` |
| `POST /auth/logout` | | `204`; revokes this device's token |
| `GET /account` | | `200 {"username","dbId","mustChangePassword","hasData":bool,"settingsCustomized":bool}`. `hasData` means at least one subscription or edition |

Logging in again with the same device ID replaces that device's token and keeps its `acked_seq`.

### 4.2 The events gate and event upload

| Method and path | Request | Response |
|---|---|---|
| `POST /sync/events` | `{"events":[...]}`, at most 500, in the `docs/kobo-sync.md` "Events" format (`seq`, `type`, `articleId`/`editionId`, `value`, `page`, `ms`, `at`) | `200 {"acked": n}` |
| `GET /sync/manifest?editions=3` | header `X-Events-Through: <seq>` | `200` manifest (4.3); 400 `events_through_required`; 409 `events_pending` |

**Merge.** The phone uses exactly the Kobo merge table in `docs/kobo-sync.md` (generalised `KoboEventMerger`, scoped to the device's user):

- events with `seq > device.acked_seq` are applied in `seq` order in one transaction, with the new `acked_seq`;
- an event naming an article or edition this user does not have is skipped but counts as merged;
- if any opened, read, saved or liked state changed, the user's affinity relearn is scheduled, debounced 10 seconds;
- every changed engagement or edition row gets `change_seq = ++users.change_seq`.

**Gate (normative).** The gated endpoints are `GET /sync/manifest`, `GET /editions/{id}/bundle`, `GET /saved` and `POST /kobo/images`. Each requires the header `X-Events-Through`, carrying the highest event `seq` the client has assigned and not seen acknowledged, or 0 if it holds none.

- If `X-Events-Through > device.acked_seq`, the server answers `409 {"error":"events_pending","acked":<acked_seq>}`.
- The client then uploads events above `acked`, in batches, and retries. Clients give up after three rounds.
- The gate is stateless, needs no session, and is identical for phone and Kobo.
- A client that lost events (crash, reset) recomputes the header from what it holds, so it cannot get stuck.

### 4.3 Editions, saved shelf, stand, print

**`GET /sync/manifest`**

- The format is the `docs/kobo-sync.md` manifest with `"protocol":"server/1"`.
- `dbId` is `users.db_id`.
- `editions` lists the newest N released editions (1..30, default 3) with `version`, the SHA-256 of the bundle bytes. Cache it in memory keyed by (editionId, edition `change_seq`, max engagement `change_seq` of its articles).
- `saved` is `{"count","version"}`.
- `acked` is `{"<thisDeviceId>": n}`.
- It also gains `"serverTime"`.

**`GET /editions?before=<number>&limit=30`** returns `200 {"editions":[EditionSummaryDto]}`, released editions only, newest first. Each item has `id, date, kind, number, length, releaseAt, cutoffAt, builtAt, openedAt, finishedAt, pageViewsTracked, leadTitle, leadImageUrl, storyCount`: whatever the newsstand cover and back issues need. Copy the fields from `model.EditionSummary`.

**`GET /editions/{id}/bundle`** (gated) returns the `docs/kobo-sync.md` edition bundle.

- Optional fields added for the phone, which the Kobo ignores: edition `cutoffAt`, `builtAt`, `openedAt`, `pageViewsTracked`, `pageViews:[{page,ms,firstSeenAt}]`; article `feedId`, `guid`, `firstSeenAt`, `feedWordCount`, `contentSource`.
- Answers 404 for another user's or an unreleased edition.

**`GET /saved`** (gated) returns the `docs/kobo-sync.md` saved shelf, with the same added article fields. The server makes sure saved articles' text is fetched (`fullText.ensure`) when they are saved, not when this is called.

**`GET /engagement?since=<cursor>`** returns `200 {"cursor":n,"engagement":[{"articleId","openedAt","readFraction","readAt","savedAt","likedAt"}],"editions":[{"id","openedAt","finishedAt"}]}`. These are rows with `change_seq > since`. The phone uses it to see Kobo activity.

**`GET /stand`** returns `200 StandDto`:

```json
{"latestEditionId": 120,
 "due": {"kind":"MORNING","releaseAt":"…","state":"PRINTED|NOTHING_NEW|NOT_PRINTED"} | null,
 "nextRelease": {"kind":"EVENING","at":"…"},
 "hasSources": true,
 "extraOffer": {"feedIds":[3,9],"reprintOf":121} | null,
 "jobRunning": "<jobId>" | null}
```

It is computed with `DueEditions.status` and the existing `ExtraOffer` rule, using the user's zone.

**Print now and Extra.** `POST /print` and `POST /editions/extra {"feedIds":[...]}` return `202 {"jobId"}`, or `200 {"jobId"}` of the job already running (a print cannot be started twice per user).

**`GET /jobs/{id}`** returns `200 JobDto`:

```json
{"id","kind":"PRINT|EXTRA|IMPORT|OPML",
 "state":"RUNNING|DONE|FAILED",
 "stage":"FETCHING_FEEDS|SETTING_PAGES|FETCHING_TEXT|FETCHING_IMAGES|IMPORTING",
 "done":12,"total":40,
 "outcome":{"result":"PRINTED|PRINTED_OFFLINE|NOTHING_NEW|UNREACHABLE|FAILED|EXTRA_PRINTED|EXTRA_REPRINTED|EXTRA_NOTHING",
            "editionId":121,"report":{...}}}
```

The stages and results mirror `BuildStage` and `EarlyBuildOutcome`. Jobs live in memory for an hour.

- A print job runs: refresh with `maxAge = 0` (today's Print now syncs everything), then `ensureCurrentEdition`, then full text, then Kobo images.

**`POST /editions/{id}/desk-notice`** returns `200 {"count": 3 | null}`. It is the month rule of `DefaultFeedHealthRepository.deskNotice`, per user.

### 4.4 Settings

`GET /settings` returns `200 ServerSettings`:

```json
{"resetTime":"06:30","editionLength":"STANDARD","eveningEnabled":false,"eveningTime":"18:00",
 "timeZone":"Europe/London","followDeviceTimeZone":true,"onboardingDone":true,
 "extraOfferDismissedAt":null,"nextRelease":{"kind":"MORNING","at":"…"}}
```

`nothingNewFor`, `resetChange` and `eveningChange` are stored server-side and not exposed.

| Method and path | Request | Response |
|---|---|---|
| `PATCH /settings` | any of `editionLength`, `timeZone` (validated IANA ID), `followDeviceTimeZone`, `onboardingDone`, `extraOfferDismissedAt` | `200 ServerSettings` |
| `PUT /settings/release` | `{"resetTime"?, "eveningEnabled"?, "eveningTime"?}` | `200 ServerSettings`; applies `ReleaseTimes` semantics ("never retroactive") |

Device-local settings stay on the phone: theme, display mode, greyscale, storage budget, text size.

### 4.5 Feeds, subscriptions, OPML, health

`FeedDto`:

```json
{"id","url","siteUrl","title","iconUrl","tier","medianIntervalHrs","affinity","sliderStep",
 "muteUntil","tags":["…"],"status":"SUBSCRIBED|DISCOVERED","addedAt","discoveredAt",
 "lastSyncAt","flag":"BROKEN|MOVED|NOISY|IGNORED|LOVED|QUIET"|null}
```

`title` is `title_override ?: feed.title`.

| Method and path | Request | Response |
|---|---|---|
| `GET /feeds` | | `200 {"feeds":[FeedDto],"tags":[{"id","name","affinity"}]}`; subscribed and discovered |
| `POST /feeds/discover` | `{"address"}` | `200 {"candidates":[{"url","title","siteUrl","kind"}]}` (`DefaultFeedDiscovery`); 422 `not_a_feed` |
| `POST /feeds` | `{"url","title"?,"tags":[]}` | `201 FeedDto` (new subscription; a global feed reused by `url_key`; first fetch done before the response, 45 s limit) or `200 FeedDto` (already subscribed, or a discovered feed promoted) |
| `PATCH /feeds/{id}` | any of `title`, `sliderStep`, `tags`, `muteUntil` (null clears) | `200 FeedDto` |
| `DELETE /feeds/{id}` | | `204` (unsubscribe, section 2.3) |
| `GET /feeds/{id}/articles?before=<publishedAt>&limit=50` | | `200 {"articles":[ArticleSummaryDto]}` |
| `GET /feeds/health` | | `200 {"flagged":[FlaggedFeedDto]}`; recomputes this user's flags without network, as opening the desk does today |
| `POST /feeds/{id}/health/dismiss`, `.../acknowledge` | | `204` |
| `POST /feeds/{id}/health/retry` | | `200 {"outcome":"FIXED|STILL_BROKEN|…"}` (`RetryOutcome`) |
| `POST /opml` | body `text/x-opml` (or `application/xml`), at most 5 MB | `202 {"jobId"}`; outcome report `{"added","skipped"}` |
| `GET /opml` | | `200 text/x-opml`, OPML 2.0 as `DefaultOpmlTransfer` writes it today |

### 4.6 Articles and search

`ArticleSummaryDto` mirrors `model.ArticleSummary`.

| Method and path | Request | Response |
|---|---|---|
| `GET /articles/{id}` | | `200 {"article":<bundle Article object incl. feedId>,"feedStatus":"SUBSCRIBED|DISCOVERED|NONE"}`. Visible if the user subscribes to its feed (either status) or has engagement or an edition item for it |
| `POST /articles/{id}/fulltext` | | `200` the same shape, after `FullTextExtractor`. `"fullText":"FETCHED|FEED_COMPLETE|UNAVAILABLE"` |
| `GET /articles?shelf=saved\|liked&before=<at>&limit=100` | | `200 {"articles":[ArticleSummaryDto]}` |
| `GET /search?q=&when=ANY\|WEEK\|MONTH\|YEAR&saved=&liked=&tagId=&feedId=&limit=50&offset=0` | | `200 {"results":[{"article":ArticleSummaryDto,"excerpt"}]}`. `FtsQuery` and FTS4 `snippet`, restricted to articles visible to the user |

Engagement is never written by REST. It goes only through `/sync/events`, so there is one write path and one ordering.

### 4.7 Discovery

All of discovery runs server-side: catalogue, profile, deck and live headlines. The catalogue is extracted from resources to `<dataDir>/cache/catalogue.db` when its `built_at` or `format_version` differs, and read through `androidx.sqlite`'s `BundledSQLiteDriver` connection API (a port of `CatalogueStore`).

| Method and path | Request | Response |
|---|---|---|
| `GET /discovery/deck` | | `200 {"cards":[CardDto],"needsTopics":bool,"week":"2026-W46"}`. `CardDto` mirrors `DiscoverModel`'s card: feed fields, reason, three headlines |
| `GET /discovery/topics` | | `200 {"topics":[{"clusterId","label"}]}` |
| `POST /discovery/topics` | `{"clusterIds":[...]}` | `200` deck |
| `GET /discovery/live?url=` | | `200 {"headlines":[...],"state":"FETCHED|DEAD|UNREACHABLE"}` (`CandidateFetcher`, 24-hour server cache) |
| `POST /discovery/verdicts` | `{"url","verdict":"PASSED|SUBSCRIBED"}` | `200 {"feed":FeedDto?}` (subscribe creates the subscription) |
| `DELETE /discovery/verdicts?url=` | | `204` (Undo; removes a subscription it created) |
| `GET /discovery/passed` | | `200 {"passed":[...]}` |
| `POST /discovery/open` | `{"feedUrl","articleUrl"}` | `200 {"articleId","feedId"}`. Stores a DISCOVERED subscription and the post, for reading a headline |

### 4.8 Devices and Kobo pairing

| Method and path | Request | Response |
|---|---|---|
| `GET /devices` | | `200 {"devices":[{"id","kind","name","width","createdAt","lastSeenAt","current":bool}]}` |
| `POST /devices/kobo` | `{"pairingUri":"morningissue:pair?v=2&…"}` (the raw QR text) | `201 {"device":…}`. The server parses `v=2`, `s`, `d`, `n` and `w`, derives the token (section 6), stores its hash and drops `s`. Re-pairing the same `d` replaces the token and keeps `acked_seq`. 400 for an unknown `v` |
| `DELETE /devices/{id}` | | `204` (revokes) |
| `GET /kobo/hello` | Kobo token | `200 {"username","server":"<publicUrl>"}`; 401 `unknown` until paired |
| `POST /kobo/images` | gated; `{"width":1264,"urls":[≤100]}` | `200 application/zip`, stored entries `images/<sha256(url)>.<jpg|png>` plus `images.json`: `[{"url","file","type","width","height"}]`. A URL that cannot be got or decoded is left out |
| `GET /kobo/config.json` | unauthenticated | `{"server":"<publicUrl>"}`, for copying to the Kobo over USB |

Pictures are prepared with `KoboImages` ported to JVM (ImageIO + TwelveMonkeys for JPEG/CMYK/EXIF and WebP decoding, the first GIF frame, `GreyImage` encoders from core), cached in `<dataDir>/kobo-images/` by URL and width, LRU, `kobo.imageCacheMb = 1024`.

### 4.9 Legacy import

**`POST /import?mode=merge|replace`**

- Header `Idempotency-Key: <uuid>`. Body `application/zip`, the archive (section 4.13), streamed to `<dataDir>/imports/<key>.zip`, `import.maxMb = 4096`.
- Returns `202 {"jobId"}`. If the key is already in `import_log`, returns `200 {"report":…}` and does no work.
- `mode=replace` is refused with `409 conflict` when the account has data. It exists for the CLI `import` command on empty accounts; the phone always sends `merge`.
- Job outcome `report`: `{"feeds","posts","skippedPosts","editions","skippedEditions","settingsApplied":bool}`.
- **Merge rules** are exactly those of `RoomArchiveTransfer.import` (how-it-works "Archive export and import"), applied per user:
  - feeds by normalised URL; the shared global feed is reused and a subscription is created;
  - posts by (feed, guid), filling lost bodies;
  - engagement fills gaps and never overwrites;
  - editions by (date, kind) per user, keeping their numbers.
  - Imported editions are untracked unless the line carries `pageViews`.
  - Imported events do not count as device events.
- **Settings** from `settings.json` are applied only if `user_settings.customized == false`. An empty account gets the phone's schedule; an account already in use keeps its own.
- After import: affinity relearn for the user, signals upkeep for new articles.

### 4.10 Status codes beyond the table above

`201` on create. `202` for jobs. `204` for no body. `ETag` and `If-None-Match` are supported on bundles and saved using `version`; that is optional, and the clients' own `version` comparison is what matters.

### 4.11 Rate and size limits

- Request bodies are limited to 1 MB except `/import`, `/opml` and `/kobo/images`.
- `/sync/events` accepts at most 500 events.

### 4.12 Ktor wiring

- `http/Routes.kt` mounts route files per area: `AuthRoutes`, `SyncRoutes`, `EditionRoutes`, `FeedRoutes`, `ArticleRoutes`, `DiscoveryRoutes`, `SettingsRoutes`, `DeviceRoutes`, `ImportRoutes`.
- They depend only on `service/` interfaces, and receive a `Principal(userId, deviceId, kind)`.

### 4.13 Archive format additions (Phase 1, in core `ArchiveFormat.kt`; no version bump)

| Where | Added |
|---|---|
| `ArchiveFeed` | `muteUntil: Long?` |
| `ArchiveEdition` | `pageViewsTracked: Boolean?`, `pageViews: List<{page, ms, firstSeenAt}>?` |
| New optional zip entry `settings.json` | `{resetTime, editionLength, eveningEnabled, eveningTime, timeZone, onboardingDone, nothingNewFor:[ms], resetChange?, eveningChange?}` |

Old readers ignore all of it, which is the established precedent for additive fields.

---

## 5. Android client changes (W-D, owns `app/**`)

### 5.1 Deleted

- **`relay/`**: all of it. `QrDecoder` moves to `ui/settings/`.
- **`kobo/`**: `BundleExport.kt`, `EventMerge.kt` (merger), `KoboImages.kt`, `InstallId.kt`.
- **`sync/`**: every file left after Phase 1, including the syncers, `SyncWorker`, `SyncModule`, `TeaserBackfill`, `FeedHealthChecker`, `DefaultFeedHealthRepository` and `DefaultOpmlTransfer`. The app no longer depends on the `sync` classes in core at runtime.
- **`edition/`**: `RoomEditionPublisher`, `EarlyBuild`, `EarlyBuildWorker`, `WorkManagerEditionScheduler`, `EditionModule`, `ReleaseTimes`, `EditionPrinter`. Keep `TimeChangeReceiver`, which now triggers the time-zone `PATCH`.
- **`data/`**:
  - `data/signals/`, `data/discovery/`;
  - `data/repository/AffinityLearner.kt`, `WithinFeedLearner.kt`, `RoomFeedRepository.kt` (write paths);
  - `embedding/EmbedderAsset.kt`;
  - the import half of `data/archive/ArchiveTransfer.kt` (export is kept for migration).
- The assets reference and the `RELAY_*` buildConfig fields.
- Tests for all of the above. Keep tests for retained code.
- `readability4j` and `jsoup` dependencies, if nothing remains that uses them.

### 5.2 Added (package `net.tgnra.morningissue.client`)

- **`AuthStore`**, in DataStore `server_auth`: `serverUrl`, `token`, `deviceId` (UUID made once per install), `username`, `dbId`, `engagementCursor`, `legacyMigration` (`NONE|PENDING|DONE|SKIPPED`), `importKey`.
- **`MorningIssueApi` implementation** in `core/.../api/client/` (OkHttp, kotlinx JSON). It maps errors to typed exceptions: `EventsPending(acked)`, `Unauthenticated`, `PasswordChangeRequired`, `Offline`.
- **`Outbox`**:
  - Room schema **7**, migration 6→7: `CREATE TABLE outbox_event(seq INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL, createdAt INTEGER NOT NULL)`.
  - `OutboxUploader.flush()` posts in batches of 500 and deletes events with `seq <= acked`.
  - `through()` returns `max(seq)` of rows still present, or 0.
- **`LocalEngagement`**, replacing `RoomArticleRepository`'s write methods and `RoomEditionRepository`'s mark/record methods:
  - writes the local `engagement`, `edition` or `edition_page_view` row at once, so the UI is instant, and appends the event to the outbox in the same transaction;
  - `readFraction` events are emitted only when the fraction grows by 0.1 or more, or on leaving the reader;
  - `pageView` events carry the delta in ms.
- **`ServerSync`** runs `sync(reason)`, single-flight:
  1. `outbox.flush()`.
  2. `GET /feeds`, then upsert `feed`, `tag` and `feed_tag` with server IDs. Server-only columns are stored where the entity has them (`affinity`, `sliderStep`, `muteUntil`, `status`). Feeds absent from the response are deleted.
  3. `GET /editions?limit=30`, then upsert edition rows.
  4. `GET /sync/manifest?editions=10` with `X-Events-Through = outbox.through()`. On `409`, flush and retry, at most three rounds.
  5. For each manifest edition whose `version` differs from the stored one (kept in a small `edition_version` DataStore map), `GET /editions/{id}/bundle`. In one transaction, replace that edition's items and upsert articles, content (with FTS) and engagement.
  6. `GET /saved` the same way.
  7. `GET /engagement?since=cursor` and apply. Then re-apply un-acked outbox events on top: the server state wins, local pending changes stay visible.
  8. Prefetch images of the newest edition into the existing `ImageStore`.
  9. Run the storage trim.
  10. If `dbId` changed, wipe the cache and start again (the server database was reset).
- **`ClientSyncWorker`** (WorkManager, network required): periodic every 2 hours, plus a one-off at `stand.nextRelease.at + 2 min`, re-aimed after each sync. It also runs on app start if the last sync was more than 30 minutes ago, and on returning to the foreground. **Opening the app may now touch the network.** That is a deliberate change from the local model; update how-it-works.
- **Server-backed repositories** keep the existing interfaces:
  - `ServerFeedRepository` (reads the cache, writes through REST, then updates the cache from the response);
  - `CachedEditionRepository` (reads the cache; `getEdition` fetches the bundle when missing; `ensureCurrentEdition` becomes "sync, then latest");
  - `CachedArticleRepository` (reads the cache; `observeFeedArticles` and `search` call the server when online and fall back to the cache);
  - `ServerFeedHealthRepository`;
  - `ServerSettingsRepository` (server fields from `/settings`, cached in DataStore for offline display, plus local device fields);
  - `ServerDiscoveryRepository` (extract an interface from the current `DiscoveryRepository` and `DiscoverModel` usage);
  - `PrintClient` (replaces `EditionPrinter` with the same UI state machine, driven by `POST /print` and polling `GET /jobs/{id}` every second, then `ServerSync`).
- **`AppContainer`**: rewire, and add `resetLocalCache()`. That closes the database, deletes `morningissue.db`, `-wal`, `-shm` and `files/images/`, and recreates them lazily.

### 5.3 Login and migration flow (new `ui/account/`)

- **`ServerScreen`**: server URL. It checks `GET /health`. A bare host gets `https://`; `http://` is allowed only after an explicit "connection is not encrypted" confirmation.
- **`LoginScreen`**: username, password, then `POST /auth/login`.
- **`ChangePasswordScreen`**: shown when `mustChangePassword` is set. Current password (the generated one) and new password twice.
- **`MigrationScreen`**: shown when `legacyMigration != DONE|SKIPPED` and the legacy database holds at least one feed. Check by opening `MorningIssueDatabase` with the existing migrations, so v1–v6 installs are upgraded first.
  - `GET /account`. If `hasData` is false, start the upload automatically.
  - If `hasData` is true, offer:
    - **Merge this phone's library into the account** (default);
    - **Use the account's library**: removes this phone's local library, `SKIPPED`;
    - and in both cases a secondary **Save a copy to a file first**, using the existing export through SAF.
  - **Upload**:
    - export with the existing exporter plus the section 4.13 fields to `cacheDir/legacy-upload.zip`;
    - `POST /import?mode=merge` with a persisted `importKey` UUID, so a retry is idempotent;
    - poll the job, showing progress and the report;
    - on success set `DONE`, delete the zip, `resetLocalCache()`, run the first `ServerSync`, then go to the newsstand.
  - **On failure** the local database is untouched and the screen offers Retry and Save a copy. The app cannot be used past this screen until migration is done or skipped (server required).
  - Kobos paired with the old app are not migrated; the screen says to pair them again.
- **Onboarding** stays. It now adds feeds through the server and finishes with `POST /print` instead of a local build.
- **Settings** gains an Account section: server, username, Change password, Log out. Log out flushes the outbox first and refuses with unsent events unless forced. It clears the token and the cache.
- **Kobo screen** is simplified: Scan (CameraX/ZXing as now), Paste code, then `POST /devices/kobo`. The device list comes from `GET /devices` with Forget. No session, no Start/Finish, no keep-screen-on.

### 5.4 Storage budget for the cache

`StorageRepository` is unchanged in shape and runs after each `ServerSync`:

- the budget still covers bodies, FTS and image files;
- images go first, then the oldest editions' bodies;
- Liked, saved and the two newest editions are never trimmed;
- the "posts waiting for the next edition" rule is removed;
- a trimmed body is re-fetched with `GET /articles/{id}` on open (it was a page fetch before).

No rows are deleted automatically. Rows are small and the server is the source of truth.

---

## 6. Kobo changes (W-E, owns `kobo/**`, `docs/kobo-sync.md`, `kobo/README.md`)

**Pairing code.**

```
morningissue:pair?v=2&s=<b64url S>&d=<deviceId>&n=<name>&w=<short side px>
```

- This is the relay QR with `v=2`. `S` is the existing 32-byte device secret (`relaySecret` in `state.json`, renamed to `secret`; read both).
- The Kobo's token is `T = HKDF-SHA256(S, salt = empty, info = "morningissue server token v1")`. It sends `Authorization: Bearer <b64url(T)>`.
- The server stores `sha256(b64url(T))` as hex and never `S`.
- Scanning is the consent, as before.

**Server address.**

- `config.json` gets `"server"`, which replaces `"relay"`; `"relay"` is ignored. There is no built-in default.
- If it is unset, Settings shows **Server address** with the letter keyboard restored from `1453bde` (`git show bb332ac^:kobo/src/...`). The value is saved in `data/settings.json` as `server`; `config.json` overrides it.
- Parsing reuses `relay::parse_address` (scheme, host, port, prefix). Plain `http://` is allowed but Settings warns that the token is sent unencrypted.
- The server also serves `/kobo/config.json`.

**Pairing wait.** While the QR is shown and the Kobo is online, it polls `GET /api/v1/kobo/hello` every 5 seconds: `401 unknown` means not yet, `200` means it returns to Settings and syncs. **The phone does not need to stay open, ever.**

**Sync order**, a new `kobo/src/server.rs` modelled on `git show eb75c12^:kobo/src/sync.rs`:

1. No secret: `NotPaired`. No server address: `NoServer`.
2. Upload events: `POST /api/v1/sync/events` with events above the last acked, 500 per batch. Store the returned `acked` and delete events at or below it from `events.jsonl`.
3. `GET /api/v1/sync/manifest?editions=3` with `X-Events-Through = max seq still in events.jsonl` (0 if empty).
   - On `409 events_pending`, go back to step 2, at most twice.
   - Check `protocol == "server/1"` and `dbId`, wiping on a change as today.
4. Each edition with a changed `version`: `GET /api/v1/editions/{id}/bundle` (gated, same header). Save it atomically.
5. If `saved.version` changed, `GET /api/v1/saved`.
6. Missing pictures in the existing order (newest edition first, then saved): `POST /api/v1/kobo/images` in chunks of 100 URLs at `image_width`. Read the zip with `zip.rs`, and install with `ImageEntry`, `pick_image` and `scale_down` moved from `relay.rs` into `images.rs`.
7. Delete editions, saved articles and images that are no longer referenced, as in "Installing a package". Record `lastSync`.

The Wi-Fi behaviour of **Turn on Wi-Fi and sync** is unchanged: retry every 5 seconds while the server is unreachable, Wi-Fi off after success or any other failure. `401 unknown` after a successful past sync is now unambiguous: "This Kobo was removed from your account; pair again."

**Code changes**, all under `kobo/`:

| File | Change |
|---|---|
| `src/relay.rs` | Delete; replaced by `src/server.rs` |
| `src/crypto.rs` | Keep `hkdf32`, `b64url*`, `hex`, `random`; add `INFO_TOKEN`. Delete MIR1 seal/open, `sign`, `authorization`, `kid`, `DeviceKeys` |
| `Cargo.toml` | Drop `aes-gcm` and the explicit `hmac`. Keep `hkdf`, `sha2`, `miniz_oxide`, `rustls`, `qrcode` |
| `src/sync.rs` | Variants become `NotPaired`, `NoServer`, `ServerAddress(String)`, `NotListed{waiting}`, `Server(String)`, `ServerProtocol(String)`, `Clock`, `Storage`, `TimedOut`, `EventsPending`; messages updated. `Options.relay` becomes `server` |
| `src/config.rs` | `server` replaces `relay`; delete `DEFAULT_RELAY` |
| `src/store.rs` | `State`: rename `relay_*` to `server_seen`, `server_not_listed`, `acked`; drop `relay_package` and `relay_fetched`; keep reading old names for one version |
| `src/app.rs`, `src/screens.rs`, `src/ui.rs` | "relay" wording becomes "server"; restore the address keyboard screen; pairing status from `/kobo/hello` |
| `src/http.rs` | Keep. Add connection keep-alive for consecutive requests to the same host if cheap; otherwise leave it |
| Tests | Delete `tests/relay.rs`, `tests/relay_interop.rs`, `tests/relay_vectors.rs`. Add `tests/server.rs` with an in-process fake server (pattern from `git show eb75c12^:kobo/tests/sync.rs`) covering: pairing wait 401→200, the events→409→events→manifest loop, a `dbId` wipe, edition and saved versions, the image zip, and the removed-device message. Keep `tests/sync.rs` fixtures that parse `docs/kobo-sync-fixtures/` |
| Docs | `docs/kobo-sync.md` "Transport" section rewritten for `server/1`. `kobo/README.md` pairing and install sections |

---

## 7. Packaging

### 7.1 Config file (TOML)

The path comes from `--config`, else `$MORNINGISSUE_CONFIG`, else `/etc/morningissue/config.toml`.

```toml
[server]
listen = "0.0.0.0"
port = 8417
publicUrl = "https://news.example.org"   # shown to Kobos (/kobo/config.json) and in logs
dataDir = "/var/lib/morningissue"

[users]
names = ["tania", "sam"]                 # removing a name disables the account; data is kept

[refresh]
intervalMinutes = 120
prePrintMaxAgeMinutes = 120
concurrency = 6
timeoutSeconds = 240

[editions]
buildLeadMinutes = 30

[kobo]
imageCacheMb = 1024

[import]
maxMb = 4096
```

Environment overrides, applied after the file, so Docker can run with no file at all: `MORNINGISSUE_USERS` (comma-separated), `MORNINGISSUE_PORT`, `MORNINGISSUE_LISTEN`, `MORNINGISSUE_PUBLIC_URL`, `MORNINGISSUE_DATA_DIR`.

### 7.2 First start (W-C, `auth/UserBootstrap.kt`)

For each configured name without a row:

- create the user and a random `db_id`;
- generate a 20-character password from a 32-symbol alphabet;
- store its PBKDF2 hash with `must_change_password = true`;
- log at WARN: `Created user "tania" with password "…". Log in from the app and change it.`;
- write `<dataDir>/initial-passwords/tania.txt` with mode 0600, deleted on the first password change.

Names in the database but not in config get `disabled = true`; re-adding a name re-enables it.

CLI `morningissue-server reset-password <user>` does the same for an existing user and revokes that user's phone tokens.

### 7.3 Data dir

```
morningissue.db (+ -wal, -shm)
initial-passwords/
cache/catalogue.db
kobo-images/
imports/
tmp/            (java.io.tmpdir, the sqlite native library)
```

### 7.4 Docker (`server/Dockerfile`, W-C)

Multi-stage:

- **Build stage**: `eclipse-temurin:21-jdk`, `COPY . /src`, `./gradlew --no-daemon -PserverOnly :server:installDist`.
- **Runtime stage**: `eclipse-temurin:21-jre`. It must be glibc-based for the bundled SQLite natives; do not use Alpine.
  - `COPY` `server/build/install/morningissue-server` to `/opt/morningissue`;
  - `ENV MORNINGISSUE_DATA_DIR=/data`, `VOLUME /data`, `EXPOSE 8417`, `USER 10001`;
  - `ENTRYPOINT ["/opt/morningissue/bin/morningissue-server"]`, `CMD ["serve"]`.
- Add `.dockerignore`: `build`, `app/build`, `kobo/target`, `.gradle`.
- Ship an example `server/docker-compose.yml` with `MORNINGISSUE_USERS` and a volume.
- Workflow `.github/workflows/server-image.yml` builds on tags and pushes `ghcr.io/<owner>/morningissue-server`.
- TLS is the reverse proxy's job; document Caddy and nginx snippets in `docs/server.md`.

### 7.5 Nix flake (W-C)

`flake.nix` (nixpkgs unstable), with `nix/package.nix`, `nix/module.nix`, `nix/deps.json` and `nix/test.nix`.

**Outputs:**

- `packages.${system}.morningissue-server` (also `default`);
- `packages.${system}.docker` (`dockerTools.buildLayeredImage`, optional);
- `overlays.default`;
- `nixosModules.default`;
- `checks.${system}.vm` (the NixOS VM test);
- `devShells.default` (jdk21, gradle, cargo, go removed).

**Package** (`nix/package.nix`):

```nix
stdenv.mkDerivation (finalAttrs: {
  pname = "morningissue-server"; version = "…"; src = lib.cleanSource ../.;
  nativeBuildInputs = [ gradle_9 makeWrapper ];   # same major as the wrapper (9.4.1)
  mitmCache = gradle.fetchDeps { pkg = finalAttrs.finalPackage; data = ./deps.json; };
  __darwinAllowLocalNetworking = true;
  gradleFlags = [ "-PserverOnly" "-Dorg.gradle.java.home=${jdk21_headless}" ];
  gradleBuildTask = ":server:installDist";
  doCheck = false;   # tests run in CI and in checks.vm
  installPhase = ''
    mkdir -p $out/share/morningissue && cp -r server/build/install/morningissue-server/* $out/share/morningissue/
    makeWrapper $out/share/morningissue/bin/morningissue-server $out/bin/morningissue-server \
      --set JAVA_HOME ${jdk21_headless}
  '';
})
```

- Update dependencies with `nix build .#morningissue-server.mitmCache.updateScript && ./result`.
- Foojay toolchain auto-download must stay unused. Neither `:core` nor `:server` declares `jvmToolchain`.
- Check that the foojay settings plugin does no network access when no toolchain is requested. If it does, apply it only when `serverOnly` is absent.

**NixOS module** (`services.morningissue`):

| Option | Type | Default |
|---|---|---|
| `enable` | bool | false |
| `package` | package | `pkgs.morningissue-server` |
| `users` | list of str | `[]` |
| `listenAddress` | str | `"127.0.0.1"` |
| `port` | port | 8417 |
| `publicUrl` | nullOr str | null |
| `dataDir` | path | `"/var/lib/morningissue"` |
| `openFirewall` | bool | false |
| `settings` | `(pkgs.formats.toml {}).type` | `{}` (freeform, merged over the generated config) |
| `jvmOptions` | list of str | `["-Xmx512m"]` |

- Generates `/etc/morningissue/config.toml` with `pkgs.formats.toml`.
- systemd unit:
  - `DynamicUser=true`, `StateDirectory=morningissue`, `ExecStart=${pkg}/bin/morningissue-server serve --config …`;
  - `Environment=JAVA_OPTS="-Djava.io.tmpdir=/var/lib/morningissue/tmp …"`;
  - hardening: `ProtectSystem=strict`, `PrivateTmp`, `NoNewPrivileges`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`;
  - `Restart=on-failure`.
- The initial passwords are in `journalctl -u morningissue` and `/var/lib/morningissue/initial-passwords/`.

**VM test** (`nix/test.nix`):

- start the service with `users = ["alice"]`;
- wait for the port;
- read the generated password from the file;
- `curl` login, which must answer `mustChangePassword: true`;
- change the password;
- `GET /settings` must answer 200.

---

## 8. Phasing for parallel agents

### Phase 1: Foundation (one agent, lands on `self-hosted` before anything else)

Owns everything it touches.

**Deliverables:**

1. The Gradle changes in 1.1, including the `serverOnly` switch and all catalogue entries every later workstream needs.
2. The `:core` extraction in 1.2 with `git mv`, the file splits (`FullTextExtractor`, `KoboEvents`), tests moved, `:app` compiling against core, and the assets moved to `assets/`.
3. `core/.../api/`: every DTO and constant in section 4 with KDoc, the `MorningIssueApi` client interface (no implementation), and `ApiError` codes.
4. Archive format additions (4.13) in core, with the app exporter updated to write them.
5. `:server` skeleton:
   - `Main.kt` that starts Ktor with `/api/v1/health` only;
   - `db/ServerDatabase.kt` with **all entities in section 2.2**, split into the four entity files;
   - every DAO interface file created **empty** (section 8 ownership), with accessors on the database;
   - schema v1 exported;
   - `service/*.kt` interfaces: `AuthService`, `DeviceService`, `FeedService`, `ArticleService`, `SearchService`, `DiscoveryService`, `EditionService`, `StandService`, `JobService`, `SyncService`, `SettingsService`, `ImportService`, `FeedRefresher`, `FullTextService`, `KoboImageService`. Signatures use API DTOs plus `userId: Long` and `deviceId: String`.
6. The Room-on-JVM spike tests (2.1).

**Acceptance:**

- `./gradlew :core:test :app:testDebugUnitTest :server:test assembleDebug` passes. The app's behaviour is unchanged, and the simulation still passes in `:app`.
- `env -u ANDROID_HOME ./gradlew -PserverOnly :server:installDist` succeeds, and `server/build/install/morningissue-server/bin/morningissue-server serve` answers `/api/v1/health`.
- Update `.github/workflows/tests.yml` to run `:core:test` and `:server:test` as well.

### Phase 2: Parallel workstreams (separate git worktrees from the Phase-1 commit)

Ownership is by path. Anything outside a workstream's paths is read-only to it. `ServerDatabase.kt` (the entity list and DAO accessors) and `gradle/libs.versions.toml` may only be changed by appending lines; integration resolves those trivial conflicts. Entity changes stay inside the owner's entity file. The schema stays at version 1 until the first server release; regenerate the export at integration, with no migrations before release.

**W-A: server feeds and content**

Owns:

- `server/.../feeds/**`;
- `db/FeedEntities.kt`, `db/UserFeedEntities.kt`;
- `db/FeedDao.kt`, `ArticleDao.kt` (writes and reads for refresh, storage and search), `SignalDao.kt`, `HealthDao.kt`, `SubscriptionDao.kt`, `DiscoveryDao.kt`;
- the implementations of `FeedRefresher`, `FullTextService`, `FeedService`, `ArticleService`, `SearchService`, `DiscoveryService`;
- `server/src/test/.../feeds/**`, `server/src/test/.../db/UserScopedQueryTest.kt`.

Acceptance (`./gradlew -PserverOnly :server:test`), with these ported from `app/src/test`:

- `FeedSyncerTest` and `FeedSyncerSignalsTest` (as `FeedRefresherTest`), `FeedHealthCheckerTest` (per user), `TeaserBackfillTest`, signals backfill tests, discovery repository tests, `OpmlTransferTest`;
- new tests:
  - two users sharing a feed: one fetch, global articles, per-user engagement isolation;
  - concurrent `refresh()` on the same feed makes one HTTP request (MockWebServer request count);
  - `maxAge` skip;
  - unsubscribe cleanup and global GC;
  - repair Moved flag per subscriber;
  - search restricted to the user.

**W-B: server editions, learning, sync and import**

Owns:

- `server/.../editions/**`, `server/.../sync/**`, `server/.../importer/**`;
- `db/EditionEntities.kt`;
- `db/EditionDao.kt`, `EngagementDao.kt`, `EditionArticleDao.kt` (the per-user candidate reads), `LearningDao.kt` (signal reads), `ImportDao.kt` (cross-table inserts for import), `UserStateDao.kt`;
- the implementations of `EditionService`, `StandService`, `JobService`, `SyncService`, `SettingsService`, `ImportService`, `KoboImageService`;
- `server/src/test/.../editions|sync|importer/**`;
- `docs/kobo-sync-fixtures/` (the ported `KoboFixturesTest` regenerates them with `protocol: server/1` and the added optional fields).

It uses a fake `FeedRefresher` until integration.

Acceptance (`:server:test`), with these ported:

- `RoomEditionPublisherTest`, `EditionScenariosTest`, `LearningEndToEndTest`, `WithinFeed*EndToEnd/BuildCost/LengthBiasTest`, `ReleaseTimesTest`, `EarlyBuildTest` (as `RunBuildTest`), `BundleExportTest`, `EventMergeTest`, `KoboImagesTest` (JVM decoding), the import half of the archive tests;
- new tests:
  - scheduler: per-user zones, DST days, an evening edition, the catch-up-once rule, and the union refresh called once for two users due together;
  - the gate: 400 with no header, 409 when `through > acked`, 200 after merge;
  - merge idempotence;
  - a merge into a non-empty account keeps server settings and applies the phone's to an empty one;
  - the import idempotency key.

**W-C: server HTTP, auth, config and packaging**

Owns:

- `server/.../config/**`, `auth/**`, `http/**`, `Main.kt`;
- `db/AccountEntities.kt`, `db/AccountDao.kt`, `db/DeviceDao.kt`;
- the implementations of `AuthService` and `DeviceService`;
- `server/src/test/.../http|auth|config/**`;
- `server/Dockerfile`, `server/docker-compose.yml`, `.dockerignore`;
- `flake.nix`, `flake.lock`, `nix/**`;
- `.github/workflows/server-image.yml`;
- `docs/server.md` (administration guide).

Acceptance:

- Ktor `testApplication` tests for every route in section 4 against fake services: status codes, error bodies, auth scopes (Kobo token refused on phone endpoints), must-change-password lockout, login rate limit, user bootstrap (password file, log line, disable and re-enable), TOML plus environment config, Kobo token derivation checked against a vector shared with W-E (`docs/server-fixtures/kobo-token.json`: S and expected token, written by W-C);
- `docker build -f server/Dockerfile .` succeeds and the container answers `/api/v1/health`;
- `nix build .#morningissue-server` and `nix flake check` (VM test) pass.

**W-D: Android client**

Owns `app/**`, `core/src/main/kotlin/net/tgnra/morningissue/api/client/**` (the implementation) and `core/src/test/.../api/client/**`.

Acceptance:

- `./gradlew :app:testDebugUnitTest assembleDebug :core:test`;
- new Robolectric and MockWebServer tests:
  - login, then change password, then migration with `hasData` false and true (merge and skip), with retry idempotent on the same `Idempotency-Key`;
  - `ServerSync` with a 409 loop, version-based bundle fetch, `dbId` wipe, and the engagement delta re-applied over outbox events;
  - outbox AUTOINCREMENT across restarts;
  - offline reading from the cache;
  - `PrintClient` states;
  - Kobo QR scan posting `pairingUri`;
- `MigrationTest` 6→7 and `StorageRepository` tests updated;
- no file under `app/src/main/java/.../relay|sync|signals|discovery` remains except as listed in 5.2.

**W-E: Kobo**

Owns `kobo/**`, `docs/kobo-sync.md`, `kobo/README.md`.

Acceptance: `cd kobo && cargo test && cargo clippy -- -D warnings && ./build.sh` (the ARM release build). The token derivation test uses `docs/server-fixtures/kobo-token.json`; if W-C has not merged yet, write the vector in the test and reconcile at integration.

### Phase 3: Integration (one agent)

1. Merge in this order: W-C, W-A, W-B, W-D, W-E. Resolve `ServerDatabase.kt` and catalogue appends, and regenerate `server/schemas/` v1.
2. **Wire the real services into `Main.kt`.** Hand-written dependency injection, `server/ServerContainer.kt`, like `AppContainer`.
3. **Move `RankingSimulationTest`** and its resources from `app/src/test/.../simulation` to `server/src/test/...`, driving the production `FeedRefresher` and scheduler build. Its thresholds must pass unchanged. Then delete it from app (the `-PsimulationReport` property moves to `:server`).
4. **End-to-end test** `server/src/test/.../E2ETest.kt`:
   - start the Ktor server on a random port with a temp data dir and a MockWebServer "internet";
   - drive `MorningIssueApi` (the core client): bootstrap a user, read the password file, login, change password;
   - import a fixture archive, then add a feed and print;
   - a manifest call is refused, then events are posted, then the manifest succeeds;
   - pair a Kobo with a v2 URI;
   - Kobo-token manifest, bundle and images.
5. Optional: `kobo/tests/server_interop.rs`, run with `MORNINGISSUE_SERVER_BIN` set, against `installDist`.
6. **Delete** `relay/`, `docs/relay-sync.md`, `docs/relay-sync-fixtures/`, `relay/install-systemd.sh`, and the relay jobs in `tests.yml` and `release.yml`. Add a cargo job for the Kobo if one is missing, and the server jobs.
7. **Rewrite `docs/how-it-works.md`**: "Architecture", "What is stored", "Timing", "Storage budget", "Archive export and import", "Sync with Kobo". Update `todo.md` and `README.md`.

Final acceptance:

- `./gradlew :core:test :server:test :app:testDebugUnitTest assembleDebug`;
- `env -u ANDROID_HOME ./gradlew -PserverOnly :server:installDist`;
- `cargo test` in `kobo/`;
- `nix flake check`;
- `docker build`.

---

## 9. Risks and open questions

**Risks (with mitigations already in the plan)**

1. **Room 3 on JVM with FTS4 through `sqlite-bundled`.** If the Phase-1 spike fails, fall back to sqlite-jdbc and hand-written DAOs. Expect W-A and W-B to take roughly double the time.
2. **Bundled SQLite natives on NixOS and in Docker** extract into `java.io.tmpdir`. The plan pins it to `<dataDir>/tmp` and uses a glibc base image. The VM test is the proof.
3. **Bearer tokens over plain `http://`.** Both clients allow it only after a warning. Document a TLS reverse proxy as the supported setup.
4. **Late subscribers to a shared feed** see its stored back catalogue (within 30 days) as new in their next edition, through `MAX(firstSeenAt, addedAt)`. That is close to today's add-a-feed behaviour but not identical.
5. **Server-side image decoding** (ImageIO + TwelveMonkeys) misses AVIF and some exotic formats. Those pictures are left out on the Kobo, as SVG already is.
6. **Opening the app may now sync.** That changes the documented "opening never touches the network" rule. It is intentional and should be documented.

**Questions for the user (real blockers only)**

1. **Kobo server address.** The QR cannot carry the address to the Kobo. I chose: `"server"` in the Kobo's `config.json`, or typed on the restored on-screen keyboard, plus a server-served `/kobo/config.json`. Is that acceptable, or do you want a LAN hand-off (phone sends the address to the Kobo over the local network during pairing), which is more code?
2. **Merge versus replace.** On an account that already has data, the default is to merge the phone's library (non-destructive, existing rules), with "use the account's library" as the alternative. There is deliberately no "replace the account with this phone". Confirm.
3. **Port and public URL.** Default port 8417, with no TLS in the server (reverse proxy expected). Confirm, or name the port you want and whether the server should serve TLS itself.

### Critical Files for Implementation
- /home/tania/Code/morningissue/app/src/main/java/net/tgnra/morningissue/edition/RoomEditionPublisher.kt
- /home/tania/Code/morningissue/app/src/main/java/net/tgnra/morningissue/data/db/Entities.kt (and the `*Dao.kt` files beside it)
- /home/tania/Code/morningissue/app/src/main/java/net/tgnra/morningissue/sync/DefaultFeedSyncer.kt
- /home/tania/Code/morningissue/app/src/main/java/net/tgnra/morningissue/kobo/BundleExport.kt and EventMerge.kt
- /home/tania/Code/morningissue/app/src/main/java/net/tgnra/morningissue/data/archive/ArchiveTransfer.kt and ArchiveFormat.kt
- /home/tania/Code/morningissue/kobo/src/relay.rs (to be replaced; old client at `git show eb75c12^:kobo/src/sync.rs`)
- /home/tania/Code/morningissue/settings.gradle.kts, /home/tania/Code/morningissue/gradle/libs.versions.toml, /home/tania/Code/morningissue/app/build.gradle.kts

---

## Phase 1 deviations

Phase 1 landed on `self-hosted` in four commits (core extraction, archive additions, API, server skeleton) plus CI and docs. Where it differs from the sections above, or settles something they left open, this is what was done. Later phases treat this as part of the contract.

**Versions added to `gradle/libs.versions.toml`.** Ktor 3.6.1, tomlj 2.2.0, logback 1.6.6, TwelveMonkeys 3.15.3, `sqlite-bundled` on the existing `sqlite` ref (2.7.1), `kotlinx-coroutines-core` on the existing `coroutines` ref, plugin `kotlin-jvm` on the `kotlin` ref. Also `jspecify` 1.0.0, which `:core` needs `compileOnly`: Jsoup's nullness annotations must be visible where a Jsoup type is inferred (the app got them transitively from AndroidX).

**More moved to `:core` than section 1.2 lists.**
- `data/images/ImageStoreConfig.kt` (pure constants), because `ImageDownload` uses it.
- `kobo/KoboImagePlan.kt`: `KoboImageConfig`, `PreparedImage` and `KoboImagePlan`, split out of the app's `KoboImages.kt`. They are pure, `GreyImageTest` tests them, and W-B's JVM port of `KoboImages` needs them.
- `FullTextExtractor.isTeaser` and `looksComplete` are public (they were `internal`), with a new `feedWasComplete(feedWords, pageWords)`. The app's callers now name `FullTextExtractor.*`. `FullTextExtractor.extract(url, title)` returns `Extraction.Page(blocks, text, words, imageUrl)` or `Extraction.Unavailable(message)`, and stores nothing.

**Test helpers are core test fixtures** (`core/src/testFixtures/`, the `java-test-fixtures` plugin), not core test sources, because the app's remaining tests use them and the server's will. They are `sync/Fixtures.kt` (now public: `Fixtures`, `NOW`, `MutableClock`, `rssOf`, `response`, `Routes`), `embedding/EmbeddingFixtures.kt` (public; reads `../assets/embedding/…`, which works from any module directory), and the open parser test classes `FeedParserTest`, `FeedSignalsParserTest` and `OpmlTest`. Core runs those three through `ParsersOnJvmTest.kt` subclasses; the app still runs them under Robolectric through `ParsersOnAndroidTest`. Consumers add `testImplementation(testFixtures(project(":core")))`; `:app` and `:server` already do.

**Test moves beyond the list.**
- `parity.json` moved to `core/src/test/resources/discovery/` with `CatalogueParityTest`. `catalogue-fixture.db` stays in the app.
- `FullTextFetcherTest`'s extractor half is a new core `FullTextExtractorTest`; the database half stays in the app.
- `EmbeddableTextTest.aPostGetsAVectorOrAMarkSayingWhyNot` moved into the app's `EmbeddingBackfillTest`, because it calls the app's `EmbeddingBackfill`.

**Git history.** The 47 moved files and their tests are true renames. `FullTextExtractor.kt` and `KoboEvents.kt` show as new files, because their sources (`DefaultFullTextFetcher.kt`, `EventMerge.kt`) stay in the app holding the database halves. Use `git blame -C -C` to trace them.

**Small app edits forced by the module boundary.** Three smart casts on model properties no longer compile across modules (`DefaultFeedSyncer`, `ReaderScreen`, `SubscriptionsDeskScreen`); they now use a local value or `!!`. Nothing else in the app changed behaviour.

**Workflows.** `tests.yml` runs `:core:test :server:test testDebugUnitTest`. `release.yml` also runs `:core:test` before a release. `discovery-catalogue.yml` runs the parity check as `./gradlew -PserverOnly :core:test --tests …CatalogueParityTest` and no longer installs the Android SDK.

**Column names in the server schema.**
- Tables new on the server (`users`, `device`, `user_settings`, `user_state`, `import_log`, `feed_status`, `subscription`, `subscription_tag`, `feed_flag`) use the snake_case columns written in 2.2.
- Tables carried over from the app (`feed`, `tag`, `article*`, `engagement`, `edition`, `edition_item`, `edition_page_view`, `discovery_verdict`) keep the app's camelCase column names, so ported SQL reads the same. They add the new columns in snake_case: `user_id`, `change_seq`, `feed.url_key`, `last_attempt_at` and `last_success_at`.
- So it is `engagement(user_id, articleId, …, change_seq)`, not `article_id`, and `discovery_verdict` has the primary key (`user_id`, `urlKey`).
- `import_log.user_id` has the usual foreign key to `users` with cascade.
- `device.kind` stores `api.DeviceKind` by name.

**Server database.**
- `ServerDatabase.open(file)` uses WAL and `setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(8))`; `ServerDatabase.inMemory()` is for tests.
- Room 3 accepts DAOs with no methods, so the 14 DAO files are empty `@Dao interface`s.
- Room on the JVM needed no `@ConstructedBy`.
- The spike found nothing unworkable. FTS4 `MATCH` and `snippet()`, foreign-key cascades, WAL and native extraction under `java.io.tmpdir` all pass.

**Server skeleton.**
- The version comes from `-PserverVersion` (default `0.1.0`) through the jar manifest.
- `Main.kt` reads only `MORNINGISSUE_PORT` and `MORNINGISSUE_LISTEN`.
- When `MORNINGISSUE_DATA_DIR` is set, `useDataDirForTemp` points `java.io.tmpdir` at `<dataDir>/tmp`, unless the JVM was given `-Djava.io.tmpdir`.
- `Application.morningIssue()` is the Ktor module that tests install.
- `server/src/main/resources/logback.xml` logs INFO to stderr.
- The server's test JVM runs with `java.io.tmpdir=server/build/tmp/test-jvm`.

**API package additions and choices** (`core/.../api/`).
- **Typed fields.** Instants, dates and times are typed (`Instant`, `LocalDate`, `LocalTime`) through `IsoInstantSerializer`, `IsoDateSerializer` and `LocalTimeSerializer`. Enums reuse the model's (`EditionKind`, `EditionLength`, `ItemKind`, `SliderStep`, `CadenceTier`, `FeedStatus`, `HealthFlag`, `RetryOutcome`, `ContentSource`).
- **JSON configuration.** `ApiJson.json` ignores unknown keys, writes defaults and nulls, and coerces unknown enum values to the field's default.
- **Bundle, saved shelf and manifest.** These are separate API classes (`EditionBundleDto`, `BundleArticleDto`, `SavedBundleDto`, `ManifestDto`), field-compatible with `docs/kobo-sync.md`. The relay-era `kobo/KoboProtocol.kt` classes are unchanged. Events reuse `kobo.SyncEvent`.
- **`Patch<T>`** carries PATCH fields where null means "clear": `FeedPatch.muteUntil` and `SettingsPatch.extraOfferDismissedAt`. An absent key is `Patch.Absent`; `{"muteUntil": null}` is `Patch.Set(null)`.
- **Jobs.**
  - `JobResult` gains `DONE`, the result of IMPORT and OPML jobs.
  - `JobOutcomeDto.report` is a `JsonElement`: an `ImportReportDto` or an `OpmlReportDto`.
  - `JobOutcomeDto.message` explains a FAILED job.
- **Fields added beyond section 4:**
  - `EditionSummaryDto.leadSource`;
  - `FeedCandidateDto.iconUrl`;
  - `CardDto` carries `urlKey`, `host`, `tier`, `postsPerWeek` and `fullTextShare`;
  - `ArticleResponse.fullText` is set only by `POST /articles/{id}/fulltext`;
  - `ImportResponse(jobId?, report?)` covers both the 202 and the 200 answers;
  - the error code `not_a_feed` (422).
- **Constants and paths.** `ApiLimits` collects the numeric limits, `ApiQuery` the query parameter names, and `IDEMPOTENCY_KEY_HEADER`, `SERVER_PROTOCOL` and `API_VERSION` sit beside `EVENTS_THROUGH_HEADER`. The `ApiPaths` constants are relative to `ApiPaths.BASE` and use Ktor `{id}` templates.
- **`api/client/MorningIssueApi.kt`** holds the interface and the typed `ApiException` hierarchy (`EventsPending(acked)`, `Unauthenticated`, `PasswordChangeRequired`, `Offline`, `Http`). W-D owns both and may refine the exceptions.

**Service interfaces** (`server/.../service/`), beyond the bare list in section 8.
- `ApiFailure(code, message, acked?)` is how a service refuses a request. The HTTP layer maps it to `ApiError.statusOf(code)` and an `ApiErrorBody`.
- `Principal(userId, deviceId, kind, mustChangePassword)` comes from `AuthService.authenticate(token)`.
- `AuthService` also has `resetPassword` and `bootstrapUsers`. `DeviceService` has `koboConfig()` and `markSeen(deviceId)`.
- `FeedService.add` returns `AddFeedOutcome(feed, created)`, so the route can answer 201 or 200.
- `JobService.start(userId, kind, work)` with `JobProgress` is how W-A's OPML import and W-B's archive import run as jobs. Starting a job returns `JobStart(jobId, alreadyRunning)`.
- `SyncService.checkGate(userId, deviceId, eventsThrough: Long?)` is the gate that every gated route calls first.
- `FeedRefresher.refresh` returns a `RefreshReport` (`reachedFeeds`). `reachedFeedsOf(userId)` replaces the pseudo-code's `lastReportCoversUserFeeds`.
- `EditionService.bundle` and `saved` return DTOs. The manifest's `version` hashes their `ApiJson` encoding.
- `KoboImageService.writeZip(userId, request, output)` streams the zip.

## Phase 2 deviations (W-A)

**Files and names.**
- **The refresher's file.** It is `feeds/DefaultFeedRefresher.kt` (class `DefaultFeedRefresher`), not `feeds/FeedRefresher.kt`, so the name doesn't clash with the `service.FeedRefresher` interface.
- **Row classes.** The per-user article rows in `ArticleDao` are named `UserArticleRow` and `UserSearchRow`, so they don't clash with anything W-B adds to `db/`.
- **Fixtures.** The server tests read core's fixture files (`rss.xml`, `article.html`, the OPML files) from `../core/src/test/resources/sync/` by relative path, rather than changing the build. The test task runs in `server/`.

**Feeds and subscriptions.**
- **Feed titles.** `feed.title` always follows the feed document; every refresh rewrites it. A title given on `POST /feeds` (or by an OPML import) becomes that user's `subscription.title_override`, which the refresher never touches.
- **What `maxAge` means.**
  - `maxAge` of zero (or negative) means "fetch regardless".
  - A fetch already in flight is joined whatever `maxAge` says.
  - The 2-hour leeway itself is the scheduler's `prePrintMaxAgeMinutes`, passed in as `maxAge`.
  - A failed attempt counts as an attempt.
- **Signals upkeep after a batch.** `DefaultFeedRefresher` takes `afterBatch: suspend () -> Unit`. It runs after a refresh that stored new posts, and integration wires it to `SignalsUpkeep.run()`.
- **`reachedFeedsOf(userId)`** answers from the latest refresh whose requested feeds covered all of that user's subscribed feeds. It is false until one has run.
- **The first fetch** on `POST /feeds` happens only when the shared feed has never been tried (`last_attempt_at` is null). Adding a feed another user already has doesn't fetch it again. OPML import doesn't fetch the new feeds; the next refresh does.
- **Moved per subscriber.** A move is pending for a user when `feed_status.moved_at` is after both their `subscription.added_at` and their `moved_acknowledged_at`. Someone who subscribes after a move is never shown it.
- **Global GC.** It deletes the posts of feeds with no subscription rows, except posts some `edition_item` or a saved or Liked `engagement` refers to. Then it deletes those feeds left with no posts. A feed whose posts are still referenced stays until they are not.
- **Discovery clean-up** works per discovered subscription: one expires 7 days after `discovered_at` unless the user saved or Liked one of its posts, or opened one within those days. Its posts then go with the next GC run, unless someone else subscribes. The app worked per post instead.
- **Affinity relearn.** `Subscriptions` takes `onChanged: suspend (userId) -> Unit`. It runs after a change that shapes affinity (add, promote, remove, tags), outside the write transaction, so W-B's learner can relearn the user's affinities. The app did this inside the transaction.
- **The desk notice.** W-A provides only the count, `DefaultFeedService.needingAttention(userId)`. Which edition carries the monthly notice is W-B's.

**Discovery.**
- **Where state lives.** The week's deck and the Undo records live in `user_state`, under `discovery.deck` and `discovery.undo.<urlKey>`, read and written through `DiscoveryDao`.
- **Undo** restores what the verdict changed. It never undoes a DROPPED verdict, the one written on unsubscribe.
- **Opening a headline.** `POST /discovery/open` answers 404 when the headline is in neither the catalogue nor the feed's live posts.
- **Candidate cache.** `CandidateFetcher`'s in-memory cache never expires within a process (as in core); its file cache keeps the 24 hours.

**The query guard.**
- **Stricter than 2.3 asks.** `UserScopedQueryTest` requires `user_id = :userId`, or an insert of the user's own row, rather than any mention of `user_id`. It also checks `edition_item` and `edition_page_view`, which may instead be keyed by `:editionId`. It covers every `*Dao.kt` in `db/`, so other workstreams' DAOs are held to it.

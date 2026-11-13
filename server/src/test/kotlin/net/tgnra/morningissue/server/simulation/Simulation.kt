package net.tgnra.morningissue.server.simulation

import java.io.OutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.FullTextState
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.engine.WithinFeedSignalKind
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.SubscriptionEntity
import net.tgnra.morningissue.server.editions.AffinityLearner
import net.tgnra.morningissue.server.editions.BuildRunner
import net.tgnra.morningissue.server.editions.BuildTarget
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.editions.EditionPages
import net.tgnra.morningissue.server.editions.EditionPublisher
import net.tgnra.morningissue.server.editions.EditionTimings
import net.tgnra.morningissue.server.editions.TestWorld
import net.tgnra.morningissue.server.editions.WithinFeedLearner
import net.tgnra.morningissue.server.feeds.DefaultFeedRefresher
import net.tgnra.morningissue.server.feeds.signals.EmbeddingBackfill
import net.tgnra.morningissue.server.feeds.signals.SignalsUpkeep
import net.tgnra.morningissue.server.feeds.signals.TraitsBackfill
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.server.service.KoboImageService
import net.tgnra.morningissue.server.sync.Relearner
import net.tgnra.morningissue.server.sync.SyncServiceImpl
import net.tgnra.morningissue.sync.FeedFetcher

/** One story of one edition, printed or indexed, with what the build gave it and what the reader did. */
class ItemRecord(
    val post: SimPost,
    val articleId: Long,
    val kind: ItemKind,
    val score: Double,
    /** W as the build computed it (the model is learned again from the same evidence, see [Simulation]). */
    val w: Double,
    val signal: WithinFeedSignalKind?,
    val signalLabel: String?,
    val topic: String?,
    /** The page the story is on, and its section's name on a section page. */
    val page: Int = 0,
    val section: String? = null,
) {
    var opened = false
    var read = false
    var liked = false
    var saved = false

    val printed: Boolean get() = kind != ItemKind.INDEX
    val onFront: Boolean get() = kind == ItemKind.LEAD || kind == ItemKind.FRONT

    /** Front page, Top stories or While you were away: the pages read before the sections. */
    val prominent: Boolean get() = onFront || kind == ItemKind.TOP || kind == ItemKind.AWAY
}

/** One simulated day. */
class DayRecord(
    val day: Int,
    /** The edition built that morning; null when the build stored none. */
    val editionId: Long?,
    val visit: Visit,
    val items: List<ItemRecord>,
    /** Feed affinity by feed key as stored by that morning's build: what the edition was ranked with. */
    val affinity: Map<String, Double>,
    /** The text taste's slope and trust in that build's model; null when it had none. */
    val textSlope: Double?,
    val textTrust: Double?,
    /** Posts first seen since the previous build: the new candidates. */
    val newPosts: Int,
) {
    fun itemsOf(feedKey: String) = items.filter { it.post.feed.key == feedKey }
}

class SimulationResult(
    val persona: Persona,
    val seed: Long,
    val days: List<DayRecord>,
    val wallMillis: Long,
    /** Where the wall time went, by phase; for the report. */
    val phaseMillis: Map<String, Long> = emptyMap(),
) {
    /** A digest of everything the first [dayCount] days produced, for the determinism check. */
    fun digest(dayCount: Int = days.size): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (day in days.take(dayCount)) {
            sha.update("${day.day}|${day.editionId}|${day.visit}|${day.newPosts}\n".toByteArray())
            for (item in day.items) {
                sha.update("${item.post.guid}|${item.kind}|${"%.12f".format(item.score)}|${"%.12f".format(item.w)}|${item.opened}${item.read}${item.liked}${item.saved}\n".toByteArray())
            }
            for ((feed, value) in day.affinity.toSortedMap()) sha.update("$feed=${"%.12f".format(value)}\n".toByteArray())
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    fun affinity(day: Int, feedKey: String): Double = days[day].affinity.getValue(feedKey)
}

/**
 * Drives the real server, end to end, through [days] simulated days for one [persona], who
 * reads on one phone:
 *
 * - **Refresh.** The production `DefaultFeedRefresher` (fetcher, parser, cadence, categories
 *   and traits) with `SignalsUpkeep` as its `afterBatch`, so every post gets its traits and a
 *   real potion-base-8M embedding after each refresh that stored posts, as in production (the
 *   upkeep is a backfill, so skipping it after a refresh that stored nothing changes nothing).
 *   The network is [SimulatedInternet]. Feeds are fetched one at a time, in order, on one
 *   thread (production fetches six at once) so that article IDs, and with them everything
 *   after (the text taste splits its posts in two by ID), are the same on every run.
 * - **Release.** At 05:00 UTC the production `BuildRunner` refreshes the user's feeds (with the
 *   pre-print leeway; the last fetch was at 17:00, so every feed is fetched) and builds the
 *   edition of the user's next release, the 06:30 reset: the scheduler's build, at the time
 *   the app's early build ran (the scheduler itself builds 30 minutes ahead, at 06:00, which
 *   would only move the cutoff). Full text and Kobo pictures are not fetched.
 * - **Reading.** Some time between 07:00 and 09:00 the persona opens the latest released
 *   edition, goes through its pages in order and opens, reads, Likes and saves stories, the
 *   clock moving on by the minutes each takes. The pages are the edition's stored items in the
 *   app's page model ([EditionPages]). Everything the reader does reaches the server as the
 *   phone's events (`SyncServiceImpl.postEvents`), one request per thing done, and each
 *   request that changed opened, read, saved or Liked state relearns affinity at once
 *   (`Relearner.relearnNow`; the server debounces it by 10 s), as the app did after every
 *   change. Reading a story through sends `readFraction` 0.95 and `read` together, as the
 *   app's `setReadFraction(0.95)` set both. Each page it looks at is recorded as on screen for
 *   as long as the persona spends on it ([Persona.pageMillis]), as the paper screen records
 *   it: a front-page-only visit shows the front page alone, a missed day shows nothing, and a
 *   page turned past in a second or two is not seen, so its stories do not count as ignored.
 * - **A second refresh** at 17:00 of every feed, however recently tried, so posts are first
 *   seen through the day. (The server refreshes every two hours. How finely first-seen times
 *   are spread does not change which edition a post is in, since every post first seen
 *   between two builds is new in the second; and freshness is measured from the publish time.
 *   Refreshing is most of the simulation's cost.)
 *
 * W is not stored by the server. After each build the model is learned once more, from the
 * same evidence the build used (`AffinityLearner.evidence` with the new edition still pending,
 * as of the build's instant, `WithinFeedLearner.learn` as of the release time), and every item
 * of the edition is weighed with it. This is the build's own computation repeated, not an
 * approximation of it.
 */
class Simulation(
    private val persona: Persona,
    private val seed: Long = 1,
    private val days: Int = DAYS,
    private val feeds: List<FeedSpec> = SimulatedFeeds.all,
) {
    suspend fun run(): SimulationResult {
        val started = System.nanoTime()
        val schedule = PostSchedule(feeds, START, days, seed)
        // An in-memory server database, its clock and the user settings store, in UTC.
        val world = TestWorld(START)
        val clock = world.clock
        val db = world.db
        val settings = world.settings
        // The refresher's own scope, on one thread: with its parallelism at 1 the feeds then take
        // their turn in the order they were queued, so articles get the same IDs on every run.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        try {
            val internet = SimulatedInternet(schedule, feeds, clock)
            var upkeepNanos = 0L
            val upkeep = SignalsUpkeep(TraitsBackfill(db), EmbeddingBackfill(db, embedder = { EmbeddingFixtures.embedder }))
            val refresher = DefaultFeedRefresher(
                db,
                FeedFetcher(internet.client),
                clock,
                scope,
                parallelism = 1,
                fullText = null,
                afterBatch = {
                    val mark = System.nanoTime()
                    upkeep.run()
                    upkeepNanos += System.nanoTime() - mark
                },
            )
            val timings = EditionTimings()
            val publisher = EditionPublisher(db, settings, clock)
            val runner = BuildRunner(db, publisher, refresher, NoFullText, NoKoboImages, settings, clock, timings)
            val relearner = Relearner(db, scope, clock)
            val relearnRequests = ArrayList<Long>()
            val sync = SyncServiceImpl(db, EditionBundles(db, clock), clock, relearn = { relearnRequests += it })
            val learner = AffinityLearner(db)
            val withinFeed = WithinFeedLearner(db)

            // Onboarding: one user with one phone; the feeds are added the evening before the first edition.
            val userId = world.addUser(name = "reader")
            val deviceId = world.addDevice(userId)
            val importDao = db.importDao()
            val addedAt = START.minus(Duration.ofHours(5))
            val feedIds = HashMap<String, Long>()
            for (feed in feeds) {
                val id = importDao.insertFeed(
                    FeedEntity(url = feed.url, urlKey = FeedUrls.normalize(feed.url), siteUrl = feed.siteUrl, title = feed.title, addedAt = addedAt),
                )
                importDao.insertSubscription(SubscriptionEntity(userId = userId, feedId = id, addedAt = addedAt))
                importDao.addSubscriptionTags(userId, id, feed.tags)
                feedIds[feed.key] = id
            }
            val allFeedIds = feeds.map { feedIds.getValue(it.key) }
            val keyOf = feedIds.entries.associate { (key, id) -> id to key }
            val posts = HashMap<Long, SimPost>()
            suspend fun postOf(articleId: Long): SimPost = posts.getOrPut(articleId) {
                val guid = importDao.article(articleId)!!.guid
                checkNotNull(schedule.byGuid(guid)) { "No simulated post $guid" }
            }

            // What the phone sends: one events request per thing the reader does, then the
            // relearn the server would run 10 s later, at once.
            var seq = 0L
            suspend fun send(vararg events: (seq: Long) -> SyncEvent) {
                val response = sync.postEvents(userId, deviceId, EventsRequest(events.map { it(++seq) }))
                check(response.acked == seq) { "Events through $seq sent, ${response.acked} acknowledged" }
                if (relearnRequests.isNotEmpty()) {
                    relearnRequests.clear()
                    relearner.relearnNow(userId)
                }
            }
            fun at() = clock.now.toString()
            fun toArticle(type: String, articleId: Long, value: JsonPrimitive? = null): (Long) -> SyncEvent =
                { SyncEvent(seq = it, type = type, articleId = articleId, value = value, at = at()) }
            fun toEdition(type: String, editionId: Long): (Long) -> SyncEvent =
                { SyncEvent(seq = it, type = type, editionId = editionId, at = at()) }

            val reader = Random(seed * 7_919 + persona.name.hashCode())
            // How long pages stay up, drawn apart from [reader] so that the story decisions
            // are the same draws whatever the page times are.
            val glances = Random(seed * 104_729 + persona.name.hashCode())
            val records = ArrayList<DayRecord>(days)
            // Wall time of the early build (refresh and build), the W snapshot, the reading and the later refreshes.
            val phases = LongArray(4)
            var seenUpTo = START.minus(Duration.ofDays(3))
            for (day in 0 until days) {
                val midnight = START.plus(Duration.ofDays(day.toLong()))

                // 05:00, the early build: final refresh, then the 06:30 edition.
                clock.now = midnight.plus(Duration.ofHours(5))
                val buildAt = clock.now
                var mark = System.nanoTime()
                val stored = settings.get(userId)
                val release = stored.schedule.nextRelease(buildAt, stored.zone)
                val outcome = runner.run(userId, BuildTarget.Scheduled(release), refreshMaxAge = timings.prePrintMaxAge)
                phases[0] += System.nanoTime() - mark
                mark = System.nanoTime()
                outcome.error?.let { throw AssertionError("Day $day: the early build failed", it) }
                val newPosts = db.editionArticleDao().firstSeenBetween(userId, seenUpTo, buildAt).size
                val editionId = outcome.editionId
                val items = ArrayList<ItemRecord>()
                var slope: Double? = null
                var trust: Double? = null
                if (editionId != null) {
                    seenUpTo = buildAt
                    val edition = db.editionDao().getById(userId, editionId)!!
                    val evidence = learner.evidence(userId, buildAt, setOf(editionId))
                    val model = withinFeed.learn(userId, evidence, learner.windowStart(buildAt), at = edition.releaseAt)
                    slope = model.text?.slope
                    trust = model.text?.trust
                    val rows = db.editionDao().getItems(userId, editionId)
                    val traits = withinFeed.traitsOf(rows.map { it.item.articleId }, model)
                    for (row in rows) {
                        val weight = traits[row.item.articleId]?.let(model::weight)
                        val post = postOf(row.item.articleId)
                        items += ItemRecord(
                            post = post,
                            articleId = row.item.articleId,
                            kind = row.item.kind,
                            score = row.item.score,
                            w = weight?.multiplier ?: 1.0,
                            signal = weight?.signal?.kind,
                            signalLabel = weight?.signal?.label,
                            topic = persona.topic(post),
                            page = row.item.page,
                            section = row.item.section,
                        )
                    }
                }
                val affinity = db.editionArticleDao().subscribedFeeds(userId).associate { keyOf.getValue(it.feedId) to it.affinity }
                phases[1] += System.nanoTime() - mark
                mark = System.nanoTime()

                // The morning read.
                val visit = persona.visit(day, reader)
                val visitMinutes = reader.nextInt(120).toLong()
                if (visit != Visit.NONE) {
                    clock.now = midnight.plus(Duration.ofHours(7)).plus(Duration.ofMinutes(visitMinutes))
                    val current = db.editionDao().getLatestReleased(userId, clock.now)
                    if (current != null) {
                        send(toEdition("editionOpened", current.id))
                        val edition = EditionPages.toEdition(db.editionDao().getById(userId, current.id)!!, db.editionDao().getItems(userId, current.id))
                        val byArticle = if (current.id == editionId) items.associateBy { it.articleId } else emptyMap()
                        // A front-page-only visit looks at the front page and closes the paper.
                        for (page in if (visit == Visit.FULL) edition.pages else edition.pages.take(1)) {
                            // The page is up while its headlines are scanned; time spent in an
                            // opened story is not page time, so it is recorded first.
                            val glance = persona.pageMillis(page, glances)
                            clock.now = clock.now.plus(Duration.ofMillis(glance.millis))
                            send({ SyncEvent(seq = it, type = "pageView", editionId = current.id, page = page.number, ms = glance.millis, at = at()) })
                            if (visit != Visit.FULL) continue
                            for (story in page.stories) {
                                // The same draws for every story, so one decision does not shift the rest.
                                val open = reader.nextDouble()
                                val read = reader.nextDouble()
                                val like = reader.nextDouble()
                                val save = reader.nextDouble()
                                val minutes = 1 + reader.nextInt(3)
                                // A page turned past is not read.
                                if (story.isOpened || glance.turnedPast) continue
                                val post = postOf(story.articleId)
                                val interest = persona.interest(day, post)
                                if (open >= interest.open * persona.pageFactor(story.kind)) continue
                                val record = byArticle[story.articleId]
                                clock.now = clock.now.plus(Duration.ofMinutes(minutes.toLong()))
                                send(toArticle("opened", story.articleId))
                                record?.opened = true
                                if (read < interest.read) {
                                    clock.now = clock.now.plus(Duration.ofMinutes(story.readMinutes.coerceAtLeast(1).toLong()))
                                    // Scrolled to 95%, past `readScrollFraction`: the phone sends the
                                    // fraction and, since the server's never sets readAt, `read`.
                                    send(toArticle("readFraction", story.articleId, JsonPrimitive(0.95f)), toArticle("read", story.articleId))
                                    record?.read = true
                                    if (like < interest.like) {
                                        send(toArticle("liked", story.articleId, JsonPrimitive(true)))
                                        record?.liked = true
                                    }
                                }
                                if (save < interest.save) {
                                    send(toArticle("saved", story.articleId, JsonPrimitive(true)))
                                    record?.saved = true
                                }
                            }
                        }
                        if (visit == Visit.FULL) {
                            clock.now = clock.now.plus(Duration.ofMinutes(1))
                            send(toEdition("editionFinished", current.id))
                        }
                    }
                }

                phases[2] += System.nanoTime() - mark
                mark = System.nanoTime()
                // The day's other refreshes, of every feed however recently tried (`maxAge` zero).
                for (hour in longArrayOf(17)) {
                    clock.now = midnight.plus(Duration.ofHours(hour))
                    refresher.refresh(allFeedIds, maxAge = Duration.ZERO)
                }

                phases[3] += System.nanoTime() - mark
                records += DayRecord(day, editionId, visit, items, affinity, slope, trust, newPosts)
            }
            val phaseMillis = mapOf(
                "early build" to phases[0],
                "W snapshot" to phases[1],
                "reading" to phases[2],
                "later refreshes" to phases[3],
                "of which signals upkeep" to upkeepNanos,
            ).mapValues { it.value / 1_000_000 }
            return SimulationResult(persona, seed, records, (System.nanoTime() - started) / 1_000_000, phaseMillis)
        } finally {
            scope.cancel()
            world.close()
        }
    }

    /** Full text is not fetched, as the app's simulation used `NoOpFullTextFetcher`. */
    private object NoFullText : FullTextService {
        override suspend fun ensure(articleId: Long) = FullTextState.UNAVAILABLE
        override suspend fun ensureForEdition(editionId: Long, concurrency: Int) = 0
    }

    /** The reader has no Kobo, so a build prepares no pictures. */
    private object NoKoboImages : KoboImageService {
        override suspend fun writeZip(userId: Long, request: KoboImagesRequest, output: OutputStream) = Unit
        override suspend fun prepare(editionId: Long, widths: Set<Int>) = Unit
    }

    companion object {
        const val DAYS = 180

        /** Midnight UTC on day 0, a Monday. */
        val START: Instant = Instant.parse("2026-03-02T00:00:00Z")
    }
}
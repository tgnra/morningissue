package net.tgnra.morningissue.server.feeds

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.DefaultFeedDiscovery
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedRepairer
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import net.tgnra.morningissue.sync.rssOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The health check, repair, quiet feeds and the desk's fixes against a real database and a local
 * server; ported from the app's `FeedHealthCheckerTest`, with the flags kept per subscriber. The
 * desk notice's month claim is W-B's; here only its count ([DefaultFeedService.needingAttention]).
 */
class FeedHealthCheckerTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: ServerDatabase
    private lateinit var checker: FeedHealthChecker
    private lateinit var refresher: DefaultFeedRefresher
    private lateinit var service: DefaultFeedService
    private var user = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        user = db.newUser("tania")
        // Repair also tries https on the same port, which the plain local server never answers;
        // a short read timeout keeps that from taking ten seconds a probe.
        val fetcher = FeedFetcher(OkHttpClient.Builder().readTimeout(Duration.ofMillis(500)).build())
        val discovery = DefaultFeedDiscovery(fetcher, clock)
        checker = FeedHealthChecker(db, FeedRepairer(fetcher, discovery, clock), clock)
        refresher = DefaultFeedRefresher(db, fetcher, clock, scope)
        val subscriptions = Subscriptions(db, clock)
        service = DefaultFeedService(db, subscriptions, refresher, checker, discovery, OpmlTransfer(db, subscriptions, clock), InlineJobs(), clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun addFeed(path: String, title: String = "Feed $path", addedDaysAgo: Long = 400, vararg users: Long = longArrayOf(user)): Long =
        db.feedFor(url(path), clock.now.minus(Duration.ofDays(addedDaysAgo)), *users, title = title)

    private fun rfc822(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))

    private fun advance(duration: Duration) {
        clock.now = clock.now.plus(duration)
    }

    private suspend fun flagOf(feedId: Long, userId: Long = user) = db.healthDao().getFlag(userId, feedId)?.lastFlag

    private suspend fun visible(userId: Long = user) = service.health(userId).flagged.map { it.feed.id to it.flag }

    /** A full refresh followed by the health check, as the scheduler runs them. */
    private suspend fun syncAll() = refresher.refresh(db.feedDao().subscribedFeedIds(), Duration.ZERO).also { checker.run() }

    /** Fails the feed on every sync for [days] days, a sync a day. */
    private suspend fun failFor(days: Int) {
        repeat(days) {
            syncAll()
            advance(Duration.ofDays(1))
        }
    }

    // --- Broken and repair -------------------------------------------------------------------

    @Test
    fun aFeedFailingForAWeekIsRepairedOntoACommonAddressAndReportedAsMoved() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        failFor(7)
        assertNull(flagOf(feedId))
        assertEquals(0, routes.hits("/feed"))

        routes["/feed"] = { response(rssOf("a")) }
        syncAll()
        assertEquals(url("/feed"), db.feedDao().getById(feedId)!!.url)
        assertEquals(HealthFlag.MOVED, flagOf(feedId))
        assertEquals(0, db.healthDao().getStatus(feedId)!!.errorStreak)
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible())

        advance(Duration.ofHours(1))
        assertEquals(1, syncAll().newArticles)
        assertEquals("Moved stands until it is seen", HealthFlag.MOVED, flagOf(feedId))

        service.acknowledgeMove(user, feedId)
        assertTrue(visible().isEmpty())
        advance(Duration.ofHours(1))
        syncAll()
        assertNull(flagOf(feedId))
    }

    @Test
    fun movedIsPerSubscriberAndAcknowledgingItClearsItForThatUserAlone() = runBlocking<Unit> {
        val other = db.newUser("other")
        val feedId = addFeed("/old.xml", users = longArrayOf(user, other))
        routes["/old.xml"] = { response(code = 301, headers = arrayOf("Location" to "/new.xml")) }
        routes["/new.xml"] = { response(rssOf("a")) }
        syncAll()
        assertEquals(url("/new.xml"), db.feedDao().getById(feedId)!!.url)
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible(user))
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible(other))

        service.acknowledgeMove(user, feedId)
        advance(Duration.ofHours(1))
        syncAll()
        assertTrue(visible(user).isEmpty())
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible(other))

        // Someone subscribing after the move was never told of it, and is not now.
        val late = db.newUser("late")
        advance(Duration.ofHours(1))
        service.add(late, net.tgnra.morningissue.api.AddFeedRequest(url("/new.xml")))
        assertTrue(visible(late).isEmpty())

        // A second move is news again for the user who acknowledged the first.
        routes["/new.xml"] = { response(code = 301, headers = arrayOf("Location" to "/newer.xml")) }
        routes["/newer.xml"] = { response(rssOf("a")) }
        advance(Duration.ofHours(1))
        syncAll()
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible(user))
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible(late))
    }

    @Test
    fun aFeedThatCannotBeRepairedIsFlaggedBrokenAndRepairIsRateLimited() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        failFor(8)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        val probes = routes.hits("/feed")
        assertTrue("the alternatives were tried", probes >= 1)
        assertEquals(clock.now.minus(Duration.ofDays(1)), db.healthDao().getStatus(feedId)!!.lastRepairAt)

        failFor(6)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        assertEquals(probes, routes.hits("/feed"))

        syncAll()
        assertTrue("tried again", routes.hits("/feed") > probes)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))

        advance(Duration.ofHours(1))
        routes["/old.xml"] = { response(rssOf("a")) }
        syncAll()
        assertNull("its own address works again: no flag", flagOf(feedId))
        assertEquals(url("/old.xml"), db.feedDao().getById(feedId)!!.url)
    }

    @Test
    fun goneIsRepairedAndFlaggedAtOnce() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        routes["/old.xml"] = { response("Gone", 410) }
        syncAll()
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        assertEquals(410, db.healthDao().getStatus(feedId)!!.lastHttpStatus)
    }

    @Test
    fun atMostAFewFeedsAreRepairedInOneRun() = runBlocking<Unit> {
        val ids = (1..5).map { addFeed("/dead/$it.xml") }
        failFor(9)
        val tried = ids.count { db.healthDao().getStatus(it)!!.lastRepairAt != null }
        assertEquals(5, tried)
        val firstDay = ids.count { db.healthDao().getStatus(it)!!.lastRepairAt == clock.now.minus(Duration.ofDays(2)) }
        assertEquals(RankingConfig.Default.repairMaxPerRun, firstDay)
    }

    @Test
    fun retryOnTheDeskRepairsNowAndFetchesTheFeed() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        failFor(8)
        assertEquals(listOf(feedId to HealthFlag.BROKEN), visible())

        assertEquals(RetryOutcome.STILL_BROKEN, service.retry(user, feedId).outcome)
        assertEquals(listOf(feedId to HealthFlag.BROKEN), visible())

        routes["/"] = {
            response(
                "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/new.xml\"></head></html>",
                200,
                "Content-Type" to "text/html",
            )
        }
        routes["/new.xml"] = { response(rssOf("a", "b")) }
        assertEquals(RetryOutcome.MOVED, service.retry(user, feedId).outcome)
        assertEquals(url("/new.xml"), db.feedDao().getById(feedId)!!.url)
        assertEquals(2, db.articleDao().count())
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible())
    }

    // --- Quiet feeds -------------------------------------------------------------------------

    @Test
    fun aQuietFeedIsFetchedByEverySyncLikeAnyOther() = runBlocking<Unit> {
        val feedId = addFeed("/quiet")
        val lastPost = clock.now.minus(Duration.ofDays(100))
        var body = rssOf("old", pubDate = { rfc822(lastPost) })
        routes["/quiet"] = { response(body) }

        assertEquals(1, syncAll().attempted)
        assertEquals(HealthFlag.QUIET, flagOf(feedId))

        advance(Duration.ofMinutes(1))
        assertEquals(1, syncAll().attempted)
        advance(Duration.ofHours(2))
        assertEquals(1, syncAll().attempted)
        assertEquals(3, routes.hits("/quiet"))
        assertEquals("checking it does not end the quiet", HealthFlag.QUIET, flagOf(feedId))

        body = rssOf("new", "old", pubDate = { if (it == 0) rfc822(clock.now) else rfc822(lastPost) })
        advance(Duration.ofMinutes(30))
        assertEquals(1, syncAll().newArticles)
        assertNull(flagOf(feedId))
    }

    // --- Ignored, Noisy, Loved from stored data ------------------------------------------------

    private suspend fun post(feedId: Long, guid: String, firstSeenAt: Instant = clock.now): Long =
        db.articleDao().insertNew(
            ArticleEntity(feedId = feedId, guid = guid, title = guid, publishedAt = firstSeenAt, firstSeenAt = firstSeenAt),
        )!!

    /** One edition a day for [userId], [count] of them ending yesterday; [stories] says what each prints. */
    private suspend fun editions(count: Int, userId: Long = user, stories: suspend (day: Int) -> List<Long>) {
        for (day in 0 until count) {
            val release = clock.now.minus(Duration.ofDays((count - day).toLong()))
            db.edition(userId, release, day + 1, stories(day))
        }
    }

    private suspend fun synced(feedId: Long) = db.feedDao().recordSuccess(feedId, null, null, clock.now)

    /** As a successful fetch records it: the feed's newest post is from now. */
    private suspend fun posted(feedId: Long) = db.healthDao().recordSuccess(feedId, 200, clock.now)

    private suspend fun setAffinity(feedId: Long, affinity: Double) =
        db.exec("UPDATE subscription SET affinity = $affinity WHERE user_id = $user AND feed_id = $feedId")

    @Test
    fun ignoredNoisyAndLovedComeFromWhatTheReaderDid() = runBlocking<Unit> {
        val ignored = addFeed("/ignored", "Ignored")
        val opened = addFeed("/opened", "Opened once")
        val loved = addFeed("/loved", "Loved")
        val noisy = addFeed("/noisy", "Noisy")
        val indexOnly = addFeed("/index", "Index only")
        listOf(ignored, opened, loved, noisy, indexOnly).forEach {
            synced(it)
            posted(it)
        }

        editions(10) { day ->
            val a = post(ignored, "i$day")
            val b = post(opened, "o$day")
            val c = post(loved, "l$day")
            if (day == 3) db.engage(user, b, openedAt = clock.now)
            if (day < 8) db.engage(user, c, readAt = clock.now, likedAt = clock.now.takeIf { day < 2 })
            listOf(a, b, c)
        }
        setAffinity(loved, 0.8)
        repeat(120) { post(noisy, "n$it", clock.now.minus(Duration.ofDays(it % 27L))) }

        checker.run(repair = false)
        assertEquals(HealthFlag.IGNORED, flagOf(ignored))
        assertNull(flagOf(opened))
        assertEquals(HealthFlag.LOVED, flagOf(loved))
        assertEquals(HealthFlag.NOISY, flagOf(noisy))
        assertNull(flagOf(indexOnly))
        assertEquals(
            listOf(noisy to HealthFlag.NOISY, ignored to HealthFlag.IGNORED, loved to HealthFlag.LOVED),
            visible(),
        )
        assertEquals(3, service.needingAttention(user))

        service.patch(user, noisy, FeedPatch(sliderStep = SliderStep.LESS))
        service.patch(user, loved, FeedPatch(sliderStep = SliderStep.FAVOURITE))
        assertEquals(SliderStep.FAVOURITE, db.subscriptionDao().get(user, loved)!!.sliderStep)
        checker.run(repair = false)
        assertEquals(listOf(ignored to HealthFlag.IGNORED), visible())
    }

    @Test
    fun anotherUsersReadingDoesNotFlagMine() = runBlocking<Unit> {
        val other = db.newUser("other")
        val feedId = addFeed("/shared", users = longArrayOf(user, other))
        synced(feedId)
        posted(feedId)
        // Both get the same posts printed; only the other user reads them.
        val posts = (0 until 10).map { post(feedId, "p$it") }
        editions(10) { day -> listOf(posts[day]) }
        editions(10, userId = other) { day -> listOf(posts[day]).also { db.engage(other, posts[day], readAt = clock.now) } }

        checker.run(repair = false)
        assertEquals(HealthFlag.IGNORED, flagOf(feedId, user))
        assertNull(flagOf(feedId, other))
    }

    @Test
    fun dismissingHidesTheFlagForSixMonthsThroughLaterChecks() = runBlocking<Unit> {
        val feedId = addFeed("/ignored")
        suspend fun tenIgnoredEditions() {
            synced(feedId)
            posted(feedId)
            editions(10) { day -> listOf(post(feedId, "p$day-${clock.now.epochSecond}")) }
        }
        tenIgnoredEditions()
        checker.run(repair = false)
        assertEquals(listOf(feedId to HealthFlag.IGNORED), visible())

        service.dismissFlag(user, feedId)
        assertTrue(visible().isEmpty())
        assertEquals(0, service.needingAttention(user))
        val until = clock.now.plus(Duration.ofDays(183))
        assertEquals(until, db.healthDao().getFlag(user, feedId)!!.flagDismissedUntil)

        advance(Duration.ofDays(20))
        checker.run(repair = false)
        assertTrue(visible().isEmpty())
        assertEquals(until, db.healthDao().getFlag(user, feedId)!!.flagDismissedUntil)

        advance(Duration.ofDays(162))
        tenIgnoredEditions()
        checker.run(repair = false)
        assertTrue("day 182", visible().isEmpty())

        advance(Duration.ofDays(2))
        checker.run(repair = false)
        assertEquals(listOf(feedId to HealthFlag.IGNORED), visible())
        assertNull(db.healthDao().getFlag(user, feedId)!!.flagDismissedUntil)
    }

    @Test
    fun quietFeedsAloneDoNotNeedAttention() = runBlocking<Unit> {
        val feedId = addFeed("/quiet")
        synced(feedId)
        checker.run(repair = false)
        assertEquals(listOf(feedId to HealthFlag.QUIET), visible())
        assertEquals(0, service.needingAttention(user))
        assertFalse(NOW.isAfter(clock.now))
    }
}

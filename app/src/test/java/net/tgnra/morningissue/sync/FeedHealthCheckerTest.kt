package net.tgnra.morningissue.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.DeskNoticeRule
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The health check, repair, quiet feeds and the desk's fixes against a real database and a local server. */
@RunWith(AndroidJUnit4::class)
class FeedHealthCheckerTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val noticeStore = object : DeskNoticeStore {
        var claim: DeskNoticeRule.Claim? = null
        override fun get() = claim
        override fun set(claim: DeskNoticeRule.Claim) {
            this.claim = claim
        }
    }
    private lateinit var db: MorningIssueDatabase
    private lateinit var checker: FeedHealthChecker
    private lateinit var syncer: FeedSyncer
    private lateinit var health: DefaultFeedHealthRepository

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val fetcher = FeedFetcher(OkHttpClient())
        checker = FeedHealthChecker(db, FeedRepairer(fetcher, DefaultFeedDiscovery(fetcher, clock), clock), clock)
        syncer = MaintainedFeedSyncer(
            DefaultFeedSyncer(db, fetcher, clock, scope),
            afterSync = listOf({ checker.run() }),
        )
        health = DefaultFeedHealthRepository(db, checker, syncer, noticeStore, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun addFeed(path: String, title: String = "Feed $path", addedDaysAgo: Long = 400): Long =
        db.feedDao().insert(FeedEntity(url = url(path), title = title, addedAt = clock.now.minus(Duration.ofDays(addedDaysAgo))))

    private fun rfc822(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))

    private fun advance(duration: Duration) {
        clock.now = clock.now.plus(duration)
    }

    private suspend fun flagOf(feedId: Long) = db.feedHealthDao().get(feedId)?.lastFlag

    private suspend fun visible() = health.observeFlagged().first().map { it.feed.id to it.flag }

    /** Fails the feed on every sync for [days] days, a sync a day. */
    private suspend fun failFor(days: Int) {
        repeat(days) {
            syncer.syncAll()
            advance(Duration.ofDays(1))
        }
    }

    // --- Broken and repair -------------------------------------------------------------------

    @Test
    fun aFeedFailingForAWeekIsRepairedOntoACommonAddressAndReportedAsMoved() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        // Days 0 to 6: errors, but not yet a week of them. Nothing else is tried.
        failFor(7)
        assertNull(flagOf(feedId))
        assertEquals(0, routes.hits("/feed"))

        // Day 7: a week of errors. The feed has moved to /feed and the site says nothing.
        routes["/feed"] = { response(rssOf("a")) }
        syncer.syncAll()
        assertEquals(url("/feed"), db.feedDao().getById(feedId)!!.url)
        assertEquals(HealthFlag.MOVED, flagOf(feedId))
        assertEquals(0, db.feedHealthDao().get(feedId)!!.errorStreak)
        assertEquals(listOf(feedId to HealthFlag.MOVED), visible())

        // The next sync fetches the new address.
        advance(Duration.ofHours(1))
        assertEquals(1, syncer.syncAll().newArticles)
        assertEquals("Moved stands until it is seen", HealthFlag.MOVED, flagOf(feedId))

        // "OK" on the desk clears it for good.
        health.acknowledge(feedId)
        assertTrue(visible().isEmpty())
        advance(Duration.ofHours(1))
        syncer.syncAll()
        assertNull(flagOf(feedId))
    }

    @Test
    fun aFeedThatCannotBeRepairedIsFlaggedBrokenAndRepairIsRateLimited() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        failFor(8)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        val probes = routes.hits("/feed")
        assertTrue("the alternatives were tried", probes >= 1)
        assertEquals(clock.now.minus(Duration.ofDays(1)), db.feedHealthDao().get(feedId)!!.lastRepairAt)

        // Six more days of syncs: still broken, and not one more repair.
        failFor(6)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        assertEquals(probes, routes.hits("/feed"))

        // A week after the first repair it is due again.
        syncer.syncAll()
        assertTrue("tried again", routes.hits("/feed") > probes)
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))

        // The feed comes back by itself: the flag goes with the first fetch that works.
        advance(Duration.ofHours(1))
        routes["/old.xml"] = { response(rssOf("a")) }
        syncer.syncAll()
        assertNull("its own address works again: no flag", flagOf(feedId))
        assertEquals(url("/old.xml"), db.feedDao().getById(feedId)!!.url)
    }

    @Test
    fun goneIsRepairedAndFlaggedAtOnce() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        routes["/old.xml"] = { response("Gone", 410) }
        syncer.syncAll()
        assertEquals(HealthFlag.BROKEN, flagOf(feedId))
        assertEquals(410, db.feedHealthDao().get(feedId)!!.lastHttpStatus)
    }

    @Test
    fun atMostAFewFeedsAreRepairedInOneRun() = runBlocking<Unit> {
        val ids = (1..5).map { addFeed("/dead/$it.xml") }
        failFor(9)
        // Three were tried on the day the week was up, the other two the day after.
        val tried = ids.count { db.feedHealthDao().get(it)!!.lastRepairAt != null }
        assertEquals(5, tried)
        val firstDay = ids.count { db.feedHealthDao().get(it)!!.lastRepairAt == clock.now.minus(Duration.ofDays(2)) }
        assertEquals(RankingConfig.Default.repairMaxPerRun, firstDay)
    }

    @Test
    fun retryOnTheDeskRepairsNowAndFetchesTheFeed() = runBlocking<Unit> {
        val feedId = addFeed("/old.xml")
        failFor(8)
        assertEquals(listOf(feedId to HealthFlag.BROKEN), visible())

        assertEquals(RetryOutcome.STILL_BROKEN, health.retry(feedId))
        assertEquals(listOf(feedId to HealthFlag.BROKEN), visible())

        routes["/"] = {
            response(
                "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/new.xml\"></head></html>",
                200,
                "Content-Type" to "text/html",
            )
        }
        routes["/new.xml"] = { response(rssOf("a", "b")) }
        assertEquals(RetryOutcome.MOVED, health.retry(feedId))
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

        assertEquals(1, syncer.syncAll().feedsChecked)
        assertEquals(HealthFlag.QUIET, flagOf(feedId))
        assertEquals(1, routes.hits("/quiet"))

        // Quiet for 100 days, and still fetched by the very next sync and the one after.
        advance(Duration.ofMinutes(1))
        assertEquals(1, syncer.syncAll().feedsChecked)
        advance(Duration.ofHours(2))
        assertEquals(1, syncer.syncAll().feedsChecked)
        assertEquals(3, routes.hits("/quiet"))
        assertEquals("checking it does not end the quiet", HealthFlag.QUIET, flagOf(feedId))

        // It posts again: the next sync finds the post, and the flag goes.
        body = rssOf("new", "old", pubDate = { if (it == 0) rfc822(clock.now) else rfc822(lastPost) })
        advance(Duration.ofMinutes(30))
        assertEquals(1, syncer.syncAll().newArticles)
        assertNull(flagOf(feedId))
    }

    @Test
    fun aFeedQuietForYearsIsStillFetchedByEverySync() = runBlocking<Unit> {
        addFeed("/quiet", addedDaysAgo = 1200)
        routes["/quiet"] = { response(rssOf("old", pubDate = { rfc822(NOW.minus(Duration.ofDays(900))) })) }
        syncer.syncAll()
        advance(Duration.ofMinutes(5))
        assertEquals(1, syncer.syncAll().feedsChecked)
        advance(Duration.ofDays(1))
        assertEquals(1, syncer.syncAll().feedsChecked)
        assertEquals(3, routes.hits("/quiet"))
    }

    // --- Ignored, Noisy, Loved from stored data ------------------------------------------------

    private suspend fun post(feedId: Long, guid: String, firstSeenAt: Instant = clock.now): Long =
        db.articleDao().insertNew(
            ArticleEntity(feedId = feedId, guid = guid, title = guid, publishedAt = firstSeenAt, firstSeenAt = firstSeenAt),
        )!!

    /** One edition a day, [count] of them ending yesterday; [stories] says what each prints. */
    private suspend fun editions(count: Int, stories: suspend (day: Int) -> List<Long>) {
        for (day in 0 until count) {
            val release = clock.now.minus(Duration.ofDays((count - day).toLong()))
            db.editionDao().insertEditionWithItems(
                EditionEntity(
                    date = LocalDate.ofEpochDay(release.epochSecond / 86_400), kind = EditionKind.MORNING, cutoffAt = release,
                    releaseAt = release, builtAt = release, length = EditionLength.STANDARD, number = day + 1,
                ),
                stories(day).mapIndexed { slot, id -> EditionItemEntity(0, id, 1, null, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, 1.0) },
            )
        }
    }

    private suspend fun synced(feedId: Long) = db.feedDao().updateSyncState(feedId, null, null, clock.now)

    /** As a successful fetch records it: the feed's newest post is from now. */
    private suspend fun posted(feedId: Long) = db.feedHealthDao().recordSuccess(feedId, 200, clock.now)

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
            if (day == 3) db.engagementDao().markOpened(b, clock.now)
            // Read eight of ten, Liked two.
            if (day < 8) db.engagementDao().markRead(c, clock.now)
            if (day < 2) db.engagementDao().setLiked(c, clock.now)
            listOf(a, b, c)
        }
        db.feedDao().updateAffinity(loved, 0.8)
        // 120 posts in four weeks, none opened; never printed, so not "ignored".
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

        // The fixes: Less for the noisy feed, Favourite for the loved one. Both clear the flag,
        // and the rules do not raise it again.
        health.applySlider(noisy, SliderStep.LESS)
        health.applySlider(loved, SliderStep.FAVOURITE)
        assertEquals(SliderStep.FAVOURITE, db.feedDao().getById(loved)!!.sliderStep)
        checker.run(repair = false)
        assertEquals(listOf(ignored to HealthFlag.IGNORED), visible())
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

        health.dismiss(feedId)
        assertTrue(visible().isEmpty())
        val until = clock.now.plus(Duration.ofDays(183))
        assertEquals(until, db.feedHealthDao().get(feedId)!!.flagDismissedUntil)

        // Checks in between leave the dismissal alone.
        advance(Duration.ofDays(20))
        checker.run(repair = false)
        assertTrue(visible().isEmpty())
        assertEquals(until, db.feedHealthDao().get(feedId)!!.flagDismissedUntil)

        // Day 182: ignored all over again, and still dismissed.
        advance(Duration.ofDays(162))
        tenIgnoredEditions()
        checker.run(repair = false)
        assertTrue("day 182", visible().isEmpty())

        // Day 184: the six months are up and the flag is back.
        advance(Duration.ofDays(2))
        checker.run(repair = false)
        assertEquals(listOf(feedId to HealthFlag.IGNORED), visible())
        assertNull(db.feedHealthDao().get(feedId)!!.flagDismissedUntil)
    }

    // --- The monthly notice ------------------------------------------------------------------

    @Test
    fun theDeskNoticeIsCarriedByOneEditionAMonthWhileAFeedNeedsAttention() = runBlocking<Unit> {
        val feedId = addFeed("/ignored")
        synced(feedId)
        posted(feedId)
        editions(10) { day -> listOf(post(feedId, "p$day")) }
        val all = db.editionDao().getRecent(10)
        val latest = all.first()
        val previous = all[1]

        // No flags yet (the check has not run): no notice, and no edition takes the month.
        assertNull(health.deskNotice(latest.id, latest.date))
        assertNull(noticeStore.claim)

        checker.run(repair = false)
        assertNull("a back issue does not take it", health.deskNotice(previous.id, previous.date))
        assertEquals(1, health.deskNotice(latest.id, latest.date))
        assertEquals(DeskNoticeRule.Claim(YearMonth.of(2026, 11), latest.id), noticeStore.claim)
        assertEquals("and keeps it", 1, health.deskNotice(latest.id, latest.date))

        // Tomorrow's edition, same month: no second notice.
        advance(Duration.ofDays(1))
        val next = db.editionDao().insertEditionWithItems(
            EditionEntity(
                date = LocalDate.of(2026, 11, 11), kind = EditionKind.MORNING, cutoffAt = clock.now, releaseAt = clock.now,
                builtAt = clock.now, length = EditionLength.STANDARD, number = 11,
            ),
            emptyList(),
        )
        assertNull(health.deskNotice(next, LocalDate.of(2026, 11, 11)))

        // Dismissed: the notice goes from the edition that carried it.
        health.dismiss(feedId)
        assertNull(health.deskNotice(latest.id, latest.date))
    }

    @Test
    fun quietFeedsAloneDoNotRaiseTheNotice() = runBlocking<Unit> {
        val feedId = addFeed("/quiet")
        synced(feedId)
        val today = db.editionDao().insertEditionWithItems(
            EditionEntity(
                date = LocalDate.of(2026, 11, 10), kind = EditionKind.MORNING, cutoffAt = clock.now, releaseAt = clock.now,
                builtAt = clock.now, length = EditionLength.STANDARD, number = 1,
            ),
            emptyList(),
        )
        checker.run(repair = false)
        assertEquals(listOf(feedId to HealthFlag.QUIET), visible())
        assertNull(health.deskNotice(today, LocalDate.of(2026, 11, 10)))
    }

    // --- After-sync housekeeping -------------------------------------------------------------

    @Test
    fun housekeepingRunsAfterAFullSyncAndNeverFailsIt() = runBlocking<Unit> {
        addFeed("/feed")
        routes["/feed"] = { response(rssOf("a")) }
        val ran = mutableListOf<String>()
        val maintained = MaintainedFeedSyncer(
            syncer,
            afterSync = listOf({ ran += "health" }, { error("trim failed") }, { ran += "after" }),
        )
        assertEquals(1, maintained.syncAll().newArticles)
        assertEquals(listOf("health", "after"), ran)
    }
}

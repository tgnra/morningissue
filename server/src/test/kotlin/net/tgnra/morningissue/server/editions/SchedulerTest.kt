package net.tgnra.morningissue.server.editions

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.server.service.RefreshReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler (section 3): builds in each user's own zone and schedule, across DST, the
 * Evening edition, the catch-up-once rule after downtime, and one union refresh for builds due
 * together, with the two-hour leeway.
 */
class SchedulerTest {
    private val start: Instant = Instant.parse("2026-11-10T00:00:00Z")
    private val world = TestWorld(start)
    private val db = world.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresher = FakeRefresher(world.clock)
    private val fullText = FakeFullText()
    private val images = FakeKoboImages()
    private val publisher = EditionPublisher(db, world.settings, world.clock)
    // No periodic refresh in these tests: it would count as fetches.
    private val timings = EditionTimings(refreshInterval = Duration.ofDays(3650))
    private val runner = BuildRunner(db, publisher, refresher, fullText, images, world.settings, world.clock, timings)
    private val scheduler = Scheduler(db, world.settings, runner, refresher, world.clock, scope, timings)
    private val settingsService = SettingsServiceImpl(db, world.settings, world.clock)

    init {
        // The first tick of a fresh scheduler runs the periodic refresh; get it out of the way.
        runBlocking { scheduler.tick().joinAll() }
        refresher.calls.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        world.close()
    }

    private suspend fun at(instant: String): List<kotlinx.coroutines.Job> {
        world.clock.now = Instant.parse(instant)
        return scheduler.tick().also { it.joinAll() }
    }

    /** A user in [zone] releasing at [reset], with [feeds] own weekly feeds holding a post each, first seen a while ago. */
    private suspend fun user(zone: String, reset: String = "06:30", feeds: Int = 6, shared: List<Long> = emptyList()): Long {
        val user = world.addUser()
        settingsService.patch(user, SettingsPatch(timeZone = zone))
        settingsService.setReleaseTimes(user, ReleaseTimesRequest(resetTime = LocalTime.parse(reset)))
        repeat(feeds) { i ->
            val feed = world.addFeed(user, "Blog $user-$i", addedAt = world.clock.now.minus(Duration.ofDays(30)))
            world.addPost(feed, firstSeenAt = world.clock.now.minus(Duration.ofHours(2)), publishedAt = world.clock.now.minus(Duration.ofHours(3L + i)))
        }
        shared.forEach { world.subscribe(user, it) }
        // An established account: its previous release found nothing new, so no catch-up is due.
        val stored = world.settings.get(user)
        world.settings.recordNothingNew(user, stored.schedule.latestReset(world.clock.now, stored.zone))
        return user
    }

    private suspend fun editions(userId: Long) = db.editionDao().getRecent(userId, 10)

    @Test
    fun eachUserIsBuiltHalfAnHourBeforeTheirOwnLocalRelease() = runBlocking {
        val london = user("Europe/London")
        val newYork = user("America/New_York")
        scheduler.restore()

        at("2026-11-10T05:59:00Z")
        assertTrue(editions(london).isEmpty())

        at("2026-11-10T06:00:00Z")
        val built = editions(london).single()
        assertEquals(Instant.parse("2026-11-10T06:30:00Z"), built.releaseAt)
        assertEquals(LocalDate.of(2026, 11, 10), built.date)
        assertEquals(Instant.parse("2026-11-10T06:00:00Z"), built.cutoffAt)
        assertTrue("New York's 06:30 is 11:30 UTC", editions(newYork).isEmpty())

        at("2026-11-10T11:00:00Z")
        assertEquals(Instant.parse("2026-11-10T11:30:00Z"), editions(newYork).single().releaseAt)
        assertEquals(1, editions(london).size)
        // The full text and pictures stage ran for both.
        assertEquals(2, fullText.editions.size)
    }

    @Test
    fun aReleaseIsBuiltOnceHoweverManyTicksFallInItsWindow() = runBlocking {
        val user = user("Europe/London")
        at("2026-11-10T06:00:00Z")
        at("2026-11-10T06:10:00Z")
        at("2026-11-10T06:29:30Z")
        assertEquals(1, editions(user).size)
        assertEquals(1, fullText.editions.size)
    }

    @Test
    fun aHandledReleaseIsNotBuiltAgainAfterARestart() = runBlocking {
        val user = user("Europe/London")
        at("2026-11-10T06:00:00Z")
        assertEquals(1, fullText.editions.size)
        val restarted = Scheduler(db, world.settings, runner, refresher, world.clock, scope, timings)
        world.clock.now = Instant.parse("2026-11-10T06:15:00Z")
        restarted.tick().joinAll()
        assertEquals(1, fullText.editions.size)
        assertEquals(1, editions(user).size)
    }

    @Test
    fun theSpringForwardDayReleasesAtTheLocalTimeAnHourEarlierInUtc() = runBlocking {
        world.clock.now = Instant.parse("2026-03-27T12:00:00Z")
        val user = user("Europe/London")
        // Saturday 28 March: still GMT, so 06:30 local is 06:30 UTC.
        at("2026-03-28T06:00:00Z")
        assertEquals(Instant.parse("2026-03-28T06:30:00Z"), editions(user).single().releaseAt)
        // Sunday 29 March: BST from 01:00 UTC, so 06:30 local is 05:30 UTC, and the build is due at 05:00 UTC.
        world.addPost(world.addFeed(user, "Sunday"), firstSeenAt = Instant.parse("2026-03-29T04:00:00Z"))
        at("2026-03-29T04:59:00Z")
        assertEquals(1, editions(user).size)
        at("2026-03-29T05:00:00Z")
        val sunday = editions(user).first()
        assertEquals(LocalDate.of(2026, 3, 29), sunday.date)
        assertEquals(Instant.parse("2026-03-29T05:30:00Z"), sunday.releaseAt)
    }

    @Test
    fun theFallBackDayReleasesAtTheLocalTimeAnHourLaterInUtc() = runBlocking {
        world.clock.now = Instant.parse("2026-10-23T12:00:00Z")
        val user = user("Europe/London")
        // Saturday 24 October: BST, 06:30 local is 05:30 UTC.
        at("2026-10-24T05:00:00Z")
        assertEquals(Instant.parse("2026-10-24T05:30:00Z"), editions(user).single().releaseAt)
        // Sunday 25 October: GMT again from 01:00 UTC, so 06:30 local is 06:30 UTC.
        world.addPost(world.addFeed(user, "Sunday"), firstSeenAt = Instant.parse("2026-10-25T04:00:00Z"))
        at("2026-10-25T05:00:00Z")
        assertEquals(1, editions(user).size)
        at("2026-10-25T06:00:00Z")
        assertEquals(Instant.parse("2026-10-25T06:30:00Z"), editions(user).first().releaseAt)
    }

    @Test
    fun theEveningEditionIsBuiltBeforeItsTime() = runBlocking {
        val user = user("Europe/London")
        at("2026-11-10T06:00:00Z")
        world.clock.now = Instant.parse("2026-11-10T12:00:00Z")
        settingsService.setReleaseTimes(user, ReleaseTimesRequest(eveningEnabled = true, eveningTime = LocalTime.of(18, 0)))
        val daily = world.addFeed(user, "Daily news", tier = net.tgnra.morningissue.model.CadenceTier.DAILY, medianHours = 24.0)
        world.addPost(daily, firstSeenAt = Instant.parse("2026-11-10T15:00:00Z"))

        at("2026-11-10T17:29:00Z")
        assertEquals(1, editions(user).size)
        at("2026-11-10T17:30:00Z")
        val evening = editions(user).first()
        assertEquals(EditionKind.EVENING, evening.kind)
        assertEquals(Instant.parse("2026-11-10T18:00:00Z"), evening.releaseAt)
    }

    @Test
    fun afterDowntimeTheMissedEditionIsBuiltLateOnceOnly() = runBlocking {
        val user = user("Europe/London")
        // The server was down through the 06:00 build: at 07:10 the 06:30 edition is missing.
        refresher.reached = false
        at("2026-11-10T07:10:00Z")
        val late = editions(user).single()
        assertEquals(Instant.parse("2026-11-10T06:30:00Z"), late.releaseAt)
        assertEquals(Instant.parse("2026-11-10T07:10:00Z"), late.cutoffAt)
        assertEquals(1, fullText.editions.size)
    }

    @Test
    fun aLateBuildThatFindsNothingIsNotRetried() = runBlocking {
        val user = world.addUser()
        settingsService.patch(user, SettingsPatch(timeZone = "Europe/London"))
        // A source, but no posts at all: nothing can be built, and the refresh reached nothing.
        world.addFeed(user, "Empty")
        refresher.reached = false
        refresher.behaviour = { RefreshReport(it.size, 0, 0, finishedInTime = true) }
        at("2026-11-10T07:10:00Z")
        val triedOnce = refresher.calls.size
        assertEquals(1, triedOnce)
        at("2026-11-10T07:20:00Z")
        at("2026-11-10T07:30:00Z")
        assertEquals("left to Print now", triedOnce, refresher.calls.size)
        assertTrue(editions(user).isEmpty())
        // Nothing new was not recorded: the feeds were not reached.
        assertTrue(world.settings.get(user).nothingNewFor.isEmpty())
    }

    @Test
    fun aBuildThatReachedTheFeedsAndFoundNothingRecordsNothingNew() = runBlocking {
        val user = world.addUser()
        settingsService.patch(user, SettingsPatch(timeZone = "Europe/London"))
        world.addFeed(user, "Empty")
        at("2026-11-10T06:00:00Z")
        assertTrue(editions(user).isEmpty())
        assertEquals(listOf(Instant.parse("2026-11-10T06:30:00Z").toEpochMilli()), world.settings.get(user).nothingNewFor)
        // So after the release nothing is missing, and no late build runs.
        at("2026-11-10T06:40:00Z")
        assertEquals(1, refresher.calls.size)
    }

    @Test
    fun usersDueTogetherShareOneUnionRefresh() = runBlocking {
        val shared = world.addGlobalFeed("Shared")
        world.addPost(shared, firstSeenAt = start.minus(Duration.ofHours(1)))
        val a = user("Europe/London", shared = listOf(shared))
        val b = user("Europe/London", shared = listOf(shared))

        at("2026-11-10T06:00:00Z")

        assertEquals("one refresh for both builds", 1, refresher.calls.size)
        val union = refresher.calls.single()
        assertTrue(shared in union)
        assertEquals(db.editionArticleDao().subscribedFeedIds(a).toSet() + db.editionArticleDao().subscribedFeedIds(b), union)
        assertEquals(1, refresher.fetches[shared])
        assertNotNull(editions(a).singleOrNull())
        assertNotNull(editions(b).singleOrNull())
    }

    @Test
    fun aSharedFeedRefreshedWithinTwoHoursIsNotFetchedAgainForTheNextUser() = runBlocking {
        val shared = world.addGlobalFeed("Shared")
        world.addPost(shared, firstSeenAt = start.minus(Duration.ofHours(1)))
        val a = user("UTC", reset = "04:00", shared = listOf(shared))
        val b = user("UTC", reset = "05:00", shared = listOf(shared))

        at("2026-11-10T03:30:00Z")
        assertNotNull(editions(a).singleOrNull())
        assertTrue(editions(b).isEmpty())
        at("2026-11-10T04:30:00Z")
        assertNotNull(editions(b).singleOrNull())

        assertEquals("two pre-print refreshes", 2, refresher.calls.size)
        assertTrue(shared in refresher.calls[1])
        assertEquals("X was fetched once: an hour old at B's build", 1, refresher.fetches[shared])
    }

    @Test
    fun aDisabledUserIsNotBuilt() = runBlocking {
        val user = user("Europe/London")
        world.disableUser(user)
        at("2026-11-10T06:00:00Z")
        assertTrue(editions(user).isEmpty())
        assertNull(refresher.calls.firstOrNull())
    }

    @Test
    fun theKoboPicturesAreWarmedAtTheUsersKoboWidths() = runBlocking {
        val user = user("Europe/London")
        world.addDevice(user, width = 1264)
        world.addDevice(user, width = 1072)
        world.addDevice(user)
        at("2026-11-10T06:00:00Z")
        val (edition, widths) = images.prepared.single()
        assertEquals(editions(user).single().id, edition)
        assertEquals(setOf(1072, 1264), widths)
    }
}

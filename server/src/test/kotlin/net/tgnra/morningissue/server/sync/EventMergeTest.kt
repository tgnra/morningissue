package net.tgnra.morningissue.server.sync

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.kobo.KoboEvents
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.editions.TestWorld
import net.tgnra.morningissue.server.service.ApiFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The merge table of `docs/kobo-sync.md`, row by row, per user, as the app's `EventMergeTest`. */
class EventMergeTest {
    private val world = TestWorld(NOW)
    private val db = world.db
    private val ids: Seeded = runBlocking { SyncTestData.seed(world) }
    private val user = ids.user
    private val device: String = runBlocking { world.addDevice(user, "b7e2", width = 1264) }
    private val merger = EventMerger(db)

    @After
    fun tearDown() = world.close()

    private fun t(minutes: Long): Instant = RELEASE.plusSeconds(minutes * 60)
    private fun at(minutes: Long): String = t(minutes).toString()

    private fun merge(vararg events: SyncEvent) = runBlocking { merger.merge(user, device, events.toList())!! }

    private fun article(seq: Long, type: String, minutes: Long, value: JsonPrimitive? = null, articleId: Long = ids.lead) =
        SyncEvent(seq = seq, type = type, articleId = articleId, value = value, at = at(minutes))

    private suspend fun engagement() = db.engagementDao().get(user, ids.lead)!!

    private suspend fun acked() = db.userStateDao().ackedSeq(user, device)

    @Test
    fun openedKeepsTheEarlierTime() = runBlocking {
        world.open(user, ids.lead, t(30))
        merge(article(1, "opened", 40))
        assertEquals(t(30), engagement().openedAt)
        merge(article(2, "opened", 10))
        assertEquals(t(10), engagement().openedAt)
        // An article never touched on the phone gets the Kobo's time.
        merge(article(3, "opened", 50, articleId = ids.top))
        assertEquals(t(50), db.engagementDao().get(user, ids.top)!!.openedAt)
    }

    @Test
    fun readFractionKeepsTheLarger() = runBlocking {
        world.readFraction(user, ids.lead, 0.5f)
        merge(article(1, "readFraction", 1, JsonPrimitive(0.3)))
        assertEquals(0.5f, engagement().readFraction)
        merge(article(2, "readFraction", 2, JsonPrimitive(0.8)))
        assertEquals(0.8f, engagement().readFraction)
        // Past 1 is clamped, and a string is not a number.
        merge(article(3, "readFraction", 3, JsonPrimitive(4.0)), article(4, "readFraction", 4, JsonPrimitive("0.1")))
        assertEquals(1f, engagement().readFraction)
    }

    @Test
    fun readKeepsTheEarlierTime() = runBlocking {
        world.read(user, ids.lead, t(20))
        merge(article(1, "read", 25))
        assertEquals(t(20), engagement().readAt)
        merge(article(2, "read", 5))
        assertEquals(t(5), engagement().readAt)
    }

    @Test
    fun likedAndSavedSetAndClearInSeqOrder() = runBlocking {
        // Sent out of order: seq decides, so the Like ends cleared and the save ends set.
        merge(
            article(3, "liked", 3, JsonPrimitive(false)),
            article(1, "liked", 1, JsonPrimitive(true)),
            article(2, "saved", 2, JsonPrimitive(false)),
            article(4, "saved", 4, JsonPrimitive(true)),
        )
        assertNull(engagement().likedAt)
        assertEquals(t(4), engagement().savedAt)

        // A later Like sets the Kobo's time, replacing the phone's.
        world.like(user, ids.lead, t(-60))
        merge(article(5, "liked", 6, JsonPrimitive(true)))
        assertEquals(t(6), engagement().likedAt)
        merge(article(6, "saved", 7, JsonPrimitive(false)))
        assertNull(engagement().savedAt)
    }

    @Test
    fun pageViewsAddUpAndRecordTheFirstSighting() = runBlocking {
        merge(SyncEvent(1, "pageView", editionId = ids.edition, page = 2, ms = 8_200, at = at(3)))
        merge(SyncEvent(2, "pageView", editionId = ids.edition, page = 2, ms = 1_800, at = at(9)))
        val view = db.editionDao().getPageViews(user, ids.edition).single()
        assertEquals(2, view.page)
        assertEquals(10_000L, view.msVisible)
        assertEquals(t(3), view.firstSeenAt)
        // No time, or no page, is nothing to record.
        merge(SyncEvent(3, "pageView", editionId = ids.edition, page = 1, ms = 0, at = at(10)))
        merge(SyncEvent(4, "pageView", editionId = ids.edition, page = 0, ms = 500, at = at(10)))
        assertEquals(1, db.editionDao().getPageViews(user, ids.edition).size)
    }

    @Test
    fun editionOpenedAndFinishedKeepTheEarlierTime() = runBlocking {
        world.openEdition(user, ids.edition, t(15))
        merge(
            SyncEvent(1, "editionOpened", editionId = ids.edition, at = at(20)),
            SyncEvent(2, "editionFinished", editionId = ids.edition, at = at(40)),
        )
        assertEquals(t(15), world.edition(user, ids.edition)!!.openedAt)
        assertEquals(t(40), world.edition(user, ids.edition)!!.finishedAt)
        merge(
            SyncEvent(3, "editionOpened", editionId = ids.edition, at = at(1)),
            SyncEvent(4, "editionFinished", editionId = ids.edition, at = at(35)),
        )
        assertEquals(t(1), world.edition(user, ids.edition)!!.openedAt)
        assertEquals(t(35), world.edition(user, ids.edition)!!.finishedAt)
    }

    @Test
    fun aRetryChangesNothingAndIsAcknowledgedAgain() = runBlocking {
        val batch = arrayOf(
            SyncEvent(42, "pageView", editionId = ids.edition, page = 1, ms = 5_000, at = at(1)),
            article(43, "readFraction", 2, JsonPrimitive(0.6)),
        )
        val first = merge(*batch)
        assertEquals(43L to 2, first.acked to first.merged)
        val stateAfterFirst = db.engagementDao().get(user, ids.lead)
        val seqAfterFirst = db.userStateDao().changeSeq(user)
        // The response was lost; the Kobo sends the same batch, then the same with one more.
        val retry = merge(*batch)
        assertEquals(43L to 0, retry.acked to retry.merged)
        // Merge idempotence: the retry wrote nothing, not even a new change stamp.
        assertEquals(stateAfterFirst, db.engagementDao().get(user, ids.lead))
        assertEquals(seqAfterFirst, db.userStateDao().changeSeq(user))
        val more = merge(*batch, article(44, "opened", 3))
        assertEquals(44L to 1, more.acked to more.merged)
        assertEquals(5_000L, db.editionDao().getPageViews(user, ids.edition).single().msVisible)
        assertEquals(44L, acked())
        // A seq repeated inside one batch is merged once.
        merge(
            SyncEvent(45, "pageView", editionId = ids.edition, page = 1, ms = 1_000, at = at(4)),
            SyncEvent(45, "pageView", editionId = ids.edition, page = 1, ms = 1_000, at = at(4)),
        )
        assertEquals(6_000L, db.editionDao().getPageViews(user, ids.edition).single().msVisible)
    }

    @Test
    fun mergingTheSameStateFromTwoDevicesConverges() = runBlocking {
        val phone = world.addDevice(user, "phone")
        val events = listOf(
            article(1, "opened", 5),
            article(2, "liked", 6, JsonPrimitive(true)),
            article(3, "readFraction", 7, JsonPrimitive(0.4)),
            SyncEvent(4, "editionOpened", editionId = ids.edition, at = at(4)),
        )
        merger.merge(user, device, events)
        val once = engagement()
        val edition = world.edition(user, ids.edition)
        // The same changes reported by the phone too: the merged state is the same.
        val again = merger.merge(user, phone, events)!!
        assertEquals(4, again.merged)
        assertFalse("nothing new was learned", again.engaged)
        assertEquals(once.copy(changeSeq = 0), engagement().copy(changeSeq = 0))
        assertEquals(edition?.openedAt, world.edition(user, ids.edition)?.openedAt)
    }

    @Test
    fun unknownArticlesEditionsAndTypesAreSkippedButAcknowledged() = runBlocking {
        val result = merge(
            article(1, "opened", 1, articleId = 999_999),
            article(2, "liked", 1, JsonPrimitive(true), articleId = 999_999),
            SyncEvent(3, "pageView", editionId = 999_999, page = 1, ms = 1_000, at = at(1)),
            SyncEvent(4, "editionOpened", editionId = 999_999, at = at(1)),
            SyncEvent(5, "highlighted", articleId = ids.lead, at = at(1)),
            // A known type missing what it needs.
            SyncEvent(6, "opened", articleId = ids.lead),
            SyncEvent(7, "read", articleId = ids.lead, at = "yesterday"),
            article(8, "liked", 1, value = null),
        )
        assertEquals(8L to 8, result.acked to result.merged)
        assertNull(db.engagementDao().get(user, 999_999))
        assertNull(db.engagementDao().get(user, ids.lead))
        assertEquals(8L, acked())
    }

    @Test
    fun anotherUsersArticlesAndEditionsAreSkipped() = runBlocking {
        val other = world.addUser()
        val stranger = world.addFeed(other, "Not yours")
        val theirs = world.addPost(stranger, firstSeenAt = RELEASE)
        val theirEdition = SyncTestData.seed(world, userId = other).edition
        val result = merge(
            article(1, "opened", 1, articleId = theirs),
            SyncEvent(2, "editionFinished", editionId = theirEdition, at = at(2)),
            SyncEvent(3, "pageView", editionId = theirEdition, page = 1, ms = 9_000, at = at(2)),
        )
        assertEquals(3L to 3, result.acked to result.merged)
        assertNull(db.engagementDao().get(user, theirs))
        assertNull(db.engagementDao().get(other, theirs))
        assertNull(world.edition(other, theirEdition)!!.finishedAt)
        assertTrue(db.editionDao().getPageViews(other, theirEdition).isEmpty())
        // The shared feed's post, seen by both: only this user's engagement moves.
        merge(article(4, "liked", 3, JsonPrimitive(true)))
        assertNull(db.engagementDao().get(other, ids.lead))
    }

    @Test
    fun aDeviceOfAnotherUserMergesNothing() = runBlocking {
        val other = world.addUser()
        val theirs = world.addDevice(other, "theirs")
        assertNull(merger.merge(user, theirs, listOf(article(1, "opened", 1))))
        assertNull(merger.merge(user, "stranger", listOf(article(1, "opened", 1))))
        assertNull(db.engagementDao().get(user, ids.lead))
        assertEquals(0L, db.userStateDao().ackedSeq(other, theirs))
    }

    @Test
    fun anEmptyBatchReturnsTheCurrentSeq() = runBlocking {
        merge(article(7, "opened", 1))
        val empty = merge()
        assertEquals(7L to 0, empty.acked to empty.merged)
    }

    @Test
    fun changesAreStampedWithOneNewChangeSeqPerRequest() = runBlocking {
        val before = db.userStateDao().changeSeq(user)
        merge(
            article(1, "opened", 1),
            article(2, "liked", 2, JsonPrimitive(true), articleId = ids.top),
            SyncEvent(3, "editionOpened", editionId = ids.edition, at = at(1)),
        )
        val seq = db.userStateDao().changeSeq(user)
        assertEquals(before + 1, seq)
        assertEquals(seq, engagement().changeSeq)
        assertEquals(seq, db.engagementDao().get(user, ids.top)!!.changeSeq)
        assertEquals(setOf(ids.lead, ids.top), db.engagementDao().changedSince(user, before).map { it.articleId }.toSet())
        assertEquals(listOf(ids.edition), db.editionDao().changedSince(user, before).map { it.id })
        // A page view moves the edition's stamp, so its bundle version is looked at again.
        merge(SyncEvent(4, "pageView", editionId = ids.older, page = 1, ms = 3_000, at = at(2)))
        assertEquals(listOf(ids.older), db.editionDao().changedSince(user, seq).map { it.id })
    }

    @Test
    fun resultsSayWhatToRelearnAndWhatToFetch() = runBlocking {
        val opened = merge(article(1, "opened", 1))
        assertTrue(opened.engaged)
        assertTrue(opened.saved.isEmpty())
        val fraction = merge(article(2, "readFraction", 2, JsonPrimitive(0.4)))
        assertFalse("the read fraction alone is not something affinity learns from", fraction.engaged)
        val saved = merge(article(3, "saved", 3, JsonPrimitive(true), articleId = ids.top))
        assertEquals(listOf(ids.top), saved.saved)
        // Saved again: not newly saved.
        assertTrue(merge(article(4, "saved", 4, JsonPrimitive(true), articleId = ids.top)).saved.isEmpty())
    }

    @Test
    fun aLikeOnTheKoboRelearnsAffinityAsOneOnThePhoneWould() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val relearner = Relearner(db, scope, world.clock, delay = Duration.ofMillis(1))
            val relearned = mutableListOf<Long>()
            val sync = SyncServiceImpl(db, EditionBundles(db, world.clock), world.clock, relearn = { relearned += it; relearner.schedule(it) })
            val affinity = suspend { db.importDao().subscription(user, ids.feed)!!.affinity }
            assertEquals(0.5, affinity(), 1e-9)

            sync.postEvents(user, device, EventsRequest(listOf(article(1, "liked", 1, JsonPrimitive(true)), article(2, "opened", 1))))
            assertEquals(listOf(user), relearned)
            relearner.relearnNow(user)
            val learned = affinity()
            assertTrue("affinity $learned", learned > 0.5)

            // The same state reached on the phone learns the same number, for another user.
            val other = world.addUser()
            world.subscribe(other, ids.feed, addedAt = RELEASE.minusSeconds(86_400 * 30L))
            SyncTestData.seed(world, userId = other)
            world.like(other, ids.lead, t(1))
            world.open(other, ids.lead, t(1))
            relearner.relearnNow(other)
            assertEquals(learned, db.importDao().subscription(other, ids.feed)!!.affinity, 1e-9)

            // The read fraction alone does not relearn.
            sync.postEvents(user, device, EventsRequest(listOf(article(3, "readFraction", 2, JsonPrimitive(0.4)))))
            assertEquals(listOf(user), relearned)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theUserMayNotSeeOtherUsersEngagementChange() = runBlocking {
        val other = world.addUser()
        SyncTestData.seed(world, userId = other)
        val otherDevice = world.addDevice(other, "k2", width = 1072)
        merger.merge(other, otherDevice, listOf(article(1, "liked", 1, JsonPrimitive(true))))
        assertNotEquals(null, db.engagementDao().get(other, ids.lead)?.likedAt)
        assertNull(db.engagementDao().get(user, ids.lead))
        assertEquals(0L, db.userStateDao().changeSeq(user))
    }

    @Test
    fun timesReadWithAnOffsetOrFractions() {
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), KoboEvents.parseTime("2026-11-11T07:30:00+01:00"))
        assertEquals(Instant.parse("2026-11-11T06:30:00.250Z"), KoboEvents.parseTime("2026-11-11T06:30:00.250Z"))
        assertNull(KoboEvents.parseTime("2026-11-11"))
    }

    @Test
    fun tooManyEventsInOneRequestAreRefused() = runBlocking {
        val sync = SyncServiceImpl(db, EditionBundles(db, world.clock), world.clock, relearn = {})
        val events = (1L..(net.tgnra.morningissue.api.ApiLimits.MAX_EVENTS_PER_REQUEST + 1)).map { article(it, "opened", 1) }
        try {
            sync.postEvents(user, device, EventsRequest(events))
            fail()
        } catch (e: ApiFailure) {
            assertEquals(400, e.status)
        }
        assertEquals(0L, acked())
    }
}

package net.tgnra.morningissue.kobo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The merge table of `docs/kobo-sync.md`, row by row, on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class EventMergeTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var ids: Seeded
    private lateinit var merger: KoboEventMerger

    private val device = "b7e2"
    private fun t(minutes: Long): Instant = RELEASE.plusSeconds(minutes * 60)
    private fun at(minutes: Long): String = BundleExport.time(t(minutes))

    @Before
    fun setUp() = runTest {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        ids = KoboTestData.seed(db)
        db.syncPeerDao().pairRelay(device, "Kobo Libra 2", 1264, "auth", "enc", RELEASE)
        merger = KoboEventMerger(db, NOW_CLOCK)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun merge(vararg events: SyncEvent) = merger.merge(device, events.toList())!!

    private fun article(seq: Long, type: String, minutes: Long, value: JsonPrimitive? = null, articleId: Long = ids.lead) =
        SyncEvent(seq = seq, type = type, articleId = articleId, value = value, at = at(minutes))

    private suspend fun engagement() = db.engagementDao().get(ids.lead)!!

    @Test
    fun openedKeepsTheEarlierTime() = runTest {
        db.engagementDao().markOpened(ids.lead, t(30))
        merge(article(1, "opened", 40))
        assertEquals(t(30), engagement().openedAt)
        merge(article(2, "opened", 10))
        assertEquals(t(10), engagement().openedAt)
        // An article never touched on the phone gets the Kobo's time.
        merge(article(3, "opened", 50, articleId = ids.top))
        assertEquals(t(50), db.engagementDao().get(ids.top)!!.openedAt)
    }

    @Test
    fun readFractionKeepsTheLarger() = runTest {
        db.engagementDao().recordReadFraction(ids.lead, 0.5f)
        merge(article(1, "readFraction", 1, JsonPrimitive(0.3)))
        assertEquals(0.5f, engagement().readFraction)
        merge(article(2, "readFraction", 2, JsonPrimitive(0.8)))
        assertEquals(0.8f, engagement().readFraction)
        // Past 1 is clamped, and a string is not a number.
        merge(article(3, "readFraction", 3, JsonPrimitive(4.0)), article(4, "readFraction", 4, JsonPrimitive("0.1")))
        assertEquals(1f, engagement().readFraction)
    }

    @Test
    fun readKeepsTheEarlierTime() = runTest {
        db.engagementDao().markRead(ids.lead, t(20))
        merge(article(1, "read", 25))
        assertEquals(t(20), engagement().readAt)
        merge(article(2, "read", 5))
        assertEquals(t(5), engagement().readAt)
    }

    @Test
    fun likedAndSavedSetAndClearInSeqOrder() = runTest {
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
        db.engagementDao().setLiked(ids.lead, t(-60))
        merge(article(5, "liked", 6, JsonPrimitive(true)))
        assertEquals(t(6), engagement().likedAt)
        merge(article(6, "saved", 7, JsonPrimitive(false)))
        assertNull(engagement().savedAt)
    }

    @Test
    fun pageViewsAddUpAndRecordTheFirstSighting() = runTest {
        merge(SyncEvent(1, "pageView", editionId = ids.edition, page = 2, ms = 8_200, at = at(3)))
        merge(SyncEvent(2, "pageView", editionId = ids.edition, page = 2, ms = 1_800, at = at(9)))
        val view = db.editionDao().getPageViews(ids.edition).single()
        assertEquals(2, view.page)
        assertEquals(10_000L, view.msVisible)
        assertEquals(t(3), view.firstSeenAt)
        // No time, or no page, is nothing to record.
        merge(SyncEvent(3, "pageView", editionId = ids.edition, page = 1, ms = 0, at = at(10)))
        merge(SyncEvent(4, "pageView", editionId = ids.edition, page = 0, ms = 500, at = at(10)))
        assertEquals(1, db.editionDao().getPageViews(ids.edition).size)
    }

    @Test
    fun editionOpenedAndFinishedKeepTheEarlierTime() = runTest {
        db.editionDao().markOpened(ids.edition, t(15))
        merge(
            SyncEvent(1, "editionOpened", editionId = ids.edition, at = at(20)),
            SyncEvent(2, "editionFinished", editionId = ids.edition, at = at(40)),
        )
        assertEquals(t(15), db.editionDao().getById(ids.edition)!!.openedAt)
        assertEquals(t(40), db.editionDao().getById(ids.edition)!!.finishedAt)
        merge(
            SyncEvent(3, "editionOpened", editionId = ids.edition, at = at(1)),
            SyncEvent(4, "editionFinished", editionId = ids.edition, at = at(35)),
        )
        assertEquals(t(1), db.editionDao().getById(ids.edition)!!.openedAt)
        assertEquals(t(35), db.editionDao().getById(ids.edition)!!.finishedAt)
    }

    @Test
    fun aRetryChangesNothingAndIsAcknowledgedAgain() = runTest {
        val batch = arrayOf(
            SyncEvent(42, "pageView", editionId = ids.edition, page = 1, ms = 5_000, at = at(1)),
            article(43, "readFraction", 2, JsonPrimitive(0.6)),
        )
        assertEquals(KoboEventMerger.Result(ackedSeq = 43, merged = 2), merge(*batch))
        // The response was lost; the Kobo sends the same batch, then the same with one more.
        assertEquals(KoboEventMerger.Result(ackedSeq = 43, merged = 0), merge(*batch))
        assertEquals(KoboEventMerger.Result(ackedSeq = 44, merged = 1), merge(*batch, article(44, "opened", 3)))
        assertEquals(5_000L, db.editionDao().getPageViews(ids.edition).single().msVisible)
        assertEquals(44L, db.syncPeerDao().get(device)!!.ackedSeq)
        assertEquals(NOW, db.syncPeerDao().get(device)!!.lastSyncAt)
        // A seq repeated inside one batch is merged once.
        merge(SyncEvent(45, "pageView", editionId = ids.edition, page = 1, ms = 1_000, at = at(4)), SyncEvent(45, "pageView", editionId = ids.edition, page = 1, ms = 1_000, at = at(4)))
        assertEquals(6_000L, db.editionDao().getPageViews(ids.edition).single().msVisible)
    }

    @Test
    fun unknownArticlesEditionsAndTypesAreSkippedButAcknowledged() = runTest {
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
        assertEquals(KoboEventMerger.Result(ackedSeq = 8, merged = 8), result)
        assertNull(db.engagementDao().get(999_999))
        assertNull(db.engagementDao().get(ids.lead))
        assertEquals(8L, db.syncPeerDao().get(device)!!.ackedSeq)
    }

    @Test
    fun aLikeOnTheKoboRelearnsAffinityAsOneOnThePhoneWould() = runTest {
        val feedId = db.articleDao().getById(ids.lead)!!.feedId
        assertEquals(0.5, db.feedDao().getById(feedId)!!.affinity, 1e-9)
        merge(article(1, "liked", 1, JsonPrimitive(true)), article(2, "opened", 1))
        val learned = db.feedDao().getById(feedId)!!.affinity
        assertTrue("affinity $learned", learned > 0.5)
        // The same state reached on the phone learns the same number.
        net.tgnra.morningissue.data.repository.AffinityLearner(db).refresh(NOW)
        assertEquals(learned, db.feedDao().getById(feedId)!!.affinity, 1e-9)
        // The read fraction alone is not something affinity learns from.
        merge(article(3, "readFraction", 2, JsonPrimitive(0.4)))
        assertEquals(learned, db.feedDao().getById(feedId)!!.affinity, 1e-9)
    }

    @Test
    fun anUnpairedDeviceMergesNothing() = runTest {
        assertNull(merger.merge("stranger", listOf(article(1, "opened", 1))))
        assertNull(db.engagementDao().get(ids.lead))
    }

    @Test
    fun anEmptyBatchReturnsTheCurrentSeq() = runTest {
        merge(article(7, "opened", 1))
        assertEquals(KoboEventMerger.Result(7, 0), merge())
    }

    @Test
    fun timesReadWithAnOffsetOrFractions() {
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), KoboEvents.parseTime("2026-11-11T07:30:00+01:00"))
        assertEquals(Instant.parse("2026-11-11T06:30:00.250Z"), KoboEvents.parseTime("2026-11-11T06:30:00.250Z"))
        assertNull(KoboEvents.parseTime("2026-11-11"))
    }
}

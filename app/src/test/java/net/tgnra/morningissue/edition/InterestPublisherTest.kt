package net.tgnra.morningissue.edition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.ArticleInterestEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.Scorer
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model-interest factor through the publisher: the stored ratings reach the edition, and
 * without them (no model, the setting off, nothing rated) the edition is exactly today's.
 */
@RunWith(AndroidJUnit4::class)
class InterestPublisherTest {
    /** Tuesday 10 November 2026, half an hour after the 06:30 reset (UTC). */
    private val morning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val databases = mutableListOf<MorningIssueDatabase>()

    @After
    fun tearDown() = databases.forEach { it.close() }

    private fun database() = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext()).also { databases += it }

    /**
     * The same library in every database: twelve daily feeds of three posts each over the last
     * day, a couple of them tagged, and one post already read. Returns the article IDs in order.
     */
    private suspend fun seed(db: MorningIssueDatabase): List<Long> {
        val ids = mutableListOf<Long>()
        for (f in 0 until 12) {
            val feedId = db.feedDao().insert(
                FeedEntity(
                    url = "https://example.com/$f.xml",
                    title = "Feed $f",
                    tier = CadenceTier.DAILY,
                    medianIntervalHrs = 24.0,
                    affinity = 0.3 + f * 0.04,
                    addedAt = morning.minus(Duration.ofDays(30)),
                ),
            )
            if (f % 4 == 0) db.tagDao().setFeedTags(feedId, listOf("Tag ${f % 8}"))
            for (p in 0 until 3) {
                val seen = morning.minus(Duration.ofMinutes(30L + f * 7 + p * 180))
                ids += db.articleDao().insertNew(
                    ArticleEntity(
                        feedId = feedId,
                        guid = "$f-$p",
                        url = "https://example.com/$f/$p",
                        title = "Story $f-$p",
                        summary = "About story $f-$p.",
                        publishedAt = seen.minus(Duration.ofMinutes(10)),
                        firstSeenAt = seen,
                    ),
                )!!
            }
        }
        db.engagementDao().markRead(ids[5], morning.minus(Duration.ofMinutes(5)))
        return ids
    }

    private fun publisher(db: MorningIssueDatabase, settings: FakeSettings = FakeSettings(), ratings: RoomEditionPublisher.InterestRatings = RoomEditionPublisher.InterestRatings.None) =
        RoomEditionPublisher(db, settings, MutableClock(morning), zone = { ZoneOffset.UTC }, interestRatings = ratings)

    /** The stored items, without the edition's own ID. */
    private suspend fun build(db: MorningIssueDatabase, publisher: RoomEditionPublisher): List<EditionItemEntity> {
        val id = publisher.ensureCurrentEdition()!!
        return db.editionDao().getItems(id).map { it.item.copy(editionId = 0) }.sortedWith(compareBy({ it.page }, { it.slot }, { it.articleId }))
    }

    private suspend fun rate(db: MorningIssueDatabase, ratings: Map<Long, Int>) =
        db.interestDao().upsert(ratings.map { (id, rating) -> ArticleInterestEntity(id, rating, morning, "hash") })

    @Test
    fun withoutTheModelTheEditionIsIdenticalToTodays() = runTest {
        val plain = database()
        seed(plain)
        val today = build(plain, publisher(plain))

        // The stored ratings are read, but there are none.
        val unrated = database()
        seed(unrated)
        assertEquals(today, build(unrated, publisher(unrated, ratings = RoomEditionPublisher.InterestRatings.stored(unrated) { true })))

        // Every post rated, but the reader switched the model off.
        val off = database()
        val offIds = seed(off)
        rate(off, offIds.associateWith { 1 + (it % 5).toInt() })
        val offSettings = FakeSettings(AppSettings(onDeviceAiRanking = false))
        val offPublisher = publisher(off, offSettings, RoomEditionPublisher.InterestRatings.stored(off) { offSettings.current.onDeviceAiRanking })
        assertEquals(today, build(off, offPublisher))

        // Every post rated a plain 3, or with ratings that cannot be read: as if unrated.
        val threes = database()
        val threeIds = seed(threes)
        rate(threes, threeIds.associateWith { 3 })
        assertEquals(today, build(threes, publisher(threes, ratings = RoomEditionPublisher.InterestRatings.stored(threes) { true })))

        val nonsense = database()
        val nonsenseIds = seed(nonsense)
        assertEquals(today, build(nonsense, publisher(nonsense, ratings = { nonsenseIds.associateWith { 0 } })))
    }

    @Test
    fun storedRatingsReorderTheEditionAndSaySo() = runTest {
        val plain = database()
        seed(plain)
        val today = build(plain, publisher(plain))
        val lead = today.single { it.kind == ItemKind.LEAD }

        val rated = database()
        val ids = seed(rated)
        // The lead without the model is rated 1; the best story of another feed 5.
        val challenger = today.filter { it.kind == ItemKind.FRONT }.first()
        rate(rated, mapOf(lead.articleId to 1, challenger.articleId to 5))
        val items = build(rated, publisher(rated, ratings = RoomEditionPublisher.InterestRatings.stored(rated) { true }))

        assertNotEquals(today, items)
        assertEquals(challenger.articleId, items.single { it.kind == ItemKind.LEAD }.articleId)
        val up = items.single { it.articleId == challenger.articleId }
        val down = items.single { it.articleId == lead.articleId }
        assertTrue(up.reason!!.endsWith("; ${Scorer.INTEREST_UP}"))
        assertTrue(down.reason!!.endsWith("; ${Scorer.INTEREST_DOWN}"))
        assertEquals(challenger.score * 1.25, up.score, 1e-9)
        assertEquals(lead.score * 0.8, down.score, 1e-9)
        // The same stories are printed, only in another order.
        assertEquals(today.map { it.articleId }.toSet(), items.map { it.articleId }.toSet())
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun theShortlistIsTheNextEditionsBestCandidatesBestFirst() = runTest {
        val db = database()
        val ids = seed(db)
        val publisher = publisher(db)

        val shortlist = publisher.shortlist(10)

        assertEquals(10, shortlist.size)
        assertEquals(shortlist.distinct(), shortlist)
        // A read post is finished: it is never rated.
        assertTrue(ids[5] !in shortlist)
        // The whole pool when asked for more than there is.
        assertEquals(35, publisher.shortlist(100).size)
        // Best first: the next edition's lead, built from the same posts, heads it.
        val next = publisher.buildNextEdition()!!
        val nextLead = db.editionDao().getItems(next).single { it.item.kind == ItemKind.LEAD }.item.articleId
        assertEquals(nextLead, shortlist.first())
        // Once the next edition is built there is nothing left to rate for it.
        assertEquals(emptyList<Long>(), publisher.shortlist(10))
    }
}

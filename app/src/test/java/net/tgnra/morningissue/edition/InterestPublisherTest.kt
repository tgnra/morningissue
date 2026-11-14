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
import net.tgnra.morningissue.engine.Interest
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

    /**
     * [count] posts from long ago (not candidates: first seen 40 days back), rated [rating] at
     * [ratedAt]: the reader's rating history, from which an unrated post's N is taken.
     */
    private suspend fun history(db: MorningIssueDatabase, count: Int, rating: Int, ratedAt: Instant) {
        val feedId = db.feedDao().insert(FeedEntity(url = "https://example.com/old.xml", title = "Old", addedAt = morning.minus(Duration.ofDays(90))))
        val seen = morning.minus(Duration.ofDays(40))
        val ids = (0 until count).map { p ->
            db.articleDao().insertNew(ArticleEntity(feedId = feedId, guid = "old-$p", title = "Old $p", publishedAt = seen, firstSeenAt = seen))!!
        }
        db.interestDao().upsert(ids.map { ArticleInterestEntity(it, rating, ratedAt, "hash") })
    }

    private fun stored(db: MorningIssueDatabase) = RoomEditionPublisher.InterestRatings.stored(db) { true }

    @Test
    fun unratedPostsTakeTheReadersMeanRating() = runTest {
        val plain = database()
        seed(plain)
        val today = build(plain, publisher(plain))

        // Twenty ratings in the last 30 days, all 1: every unrated post scores at N = 0.8. With
        // nothing of this edition rated the order is untouched and only the scores scale.
        val low = database()
        seed(low)
        history(low, 20, rating = 1, ratedAt = morning.minus(Duration.ofDays(29)))
        val items = build(low, publisher(low, ratings = stored(low)))
        assertEquals(today.map { it.articleId to it.kind }, items.map { it.articleId to it.kind })
        today.zip(items).forEach { (before, after) -> assertEquals(before.score * 0.8, after.score, 1e-9) }
        assertEquals("the mean is not the model's word on a post", today.map { it.reason }, items.map { it.reason })

        // So a post the model rated a plain 3 now beats an unrated one it used to trail.
        val rated = database()
        seed(rated)
        history(rated, 20, rating = 1, ratedAt = morning.minus(Duration.ofDays(1)))
        val lead = today.single { it.kind == ItemKind.LEAD }
        val challenger = today.filter { it.kind == ItemKind.FRONT }.first()
        rate(rated, mapOf(challenger.articleId to 3))
        val reordered = build(rated, publisher(rated, ratings = stored(rated)))
        assertEquals(challenger.articleId, reordered.single { it.kind == ItemKind.LEAD }.articleId)
        assertEquals(challenger.score, reordered.single { it.articleId == challenger.articleId }.score, 1e-9)
        // The 3 counts in the mean too: (20 × 1 + 3) / 21.
        val mean = Interest().weightOf(23.0 / 21.0)
        assertEquals(lead.score * mean, reordered.single { it.articleId == lead.articleId }.score, 1e-9)
    }

    @Test
    fun withTooFewRecentRatingsUnratedPostsStayAtOne() = runTest {
        val plain = database()
        seed(plain)
        val today = build(plain, publisher(plain))

        // Nineteen is too few.
        val few = database()
        seed(few)
        history(few, 19, rating = 1, ratedAt = morning.minus(Duration.ofDays(1)))
        assertEquals(today, build(few, publisher(few, ratings = stored(few))))

        // Older than 30 days, they do not count.
        val old = database()
        seed(old)
        history(old, 40, rating = 1, ratedAt = morning.minus(Duration.ofDays(31)))
        assertEquals(today, build(old, publisher(old, ratings = stored(old))))

        // Switched off, the history is ignored too.
        val off = database()
        seed(off)
        history(off, 40, rating = 1, ratedAt = morning.minus(Duration.ofDays(1)))
        assertEquals(today, build(off, publisher(off, ratings = RoomEditionPublisher.InterestRatings.stored(off) { false })))
    }

    @Test
    fun theShortlistIsTheDueEditionsBestCandidatesBestFirst() = runTest {
        val db = database()
        val ids = seed(db)
        val clock = MutableClock(morning)
        val publisher = RoomEditionPublisher(db, FakeSettings(), clock, zone = { ZoneOffset.UTC })

        // Today's edition is due and not printed yet: the print rates its shortlist first.
        val shortlist = publisher.shortlist(10)

        assertEquals(10, shortlist.size)
        assertEquals(shortlist.distinct(), shortlist)
        // A read post is finished: it is never rated.
        assertTrue(ids[5] !in shortlist)
        // The whole pool when asked for more than there is.
        assertEquals(35, publisher.shortlist(100).size)
        // Best first: the edition's lead, built from the same posts, heads it.
        val printed = publisher.ensureCurrentEdition()!!
        val lead = db.editionDao().getItems(printed).single { it.item.kind == ItemKind.LEAD }.item.articleId
        assertEquals(lead, shortlist.first())
        // Once it is printed the shortlist is the next edition's, which has nothing yet.
        assertEquals(emptyList<Long>(), publisher.shortlist(10))
        // A post that arrives later is rated ahead of the next print.
        val later = db.articleDao().insertNew(
            ArticleEntity(
                feedId = db.articleDao().getById(ids[0])!!.feedId,
                guid = "later",
                url = "https://example.com/later",
                title = "Later",
                publishedAt = morning.plus(Duration.ofHours(2)),
                firstSeenAt = morning.plus(Duration.ofHours(2)),
            ),
        )!!
        clock.now = morning.plus(Duration.ofHours(3))
        assertEquals(listOf(later), publisher.shortlist(10))
    }
}

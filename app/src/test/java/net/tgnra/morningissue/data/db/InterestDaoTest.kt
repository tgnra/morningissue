package net.tgnra.morningissue.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The on-device model's stored ratings, on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class InterestDaoTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var dao: InterestDao

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.interestDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun post(feedId: Long, guid: String, title: String = "Title $guid"): Long =
        db.articleDao().insertNew(article(feedId, guid, title = title), null, null)!!

    @Test
    fun aRatingIsStoredAndReplacedWhenThePostIsRatedAgain() = runTest {
        val feedId = db.feedDao().insert(feed())
        val a = post(feedId, "a")
        val b = post(feedId, "b")

        dao.upsert(listOf(ArticleInterestEntity(a, 4, T0, "p1"), ArticleInterestEntity(b, 2, T0, "p1")))
        dao.upsert(listOf(ArticleInterestEntity(a, 5, T0.plusSeconds(60), "p2")))

        val stored = dao.ratingsOf(listOf(a, b, 999))
        assertEquals(setOf(a, b), stored.keys)
        assertEquals(ArticleInterestEntity(a, 5, T0.plusSeconds(60), "p2"), stored[a])
        assertEquals(2, stored[b]!!.rating)
        assertEquals(2, dao.count())
    }

    @Test
    fun manyPostsAreReadInChunks() = runTest {
        val feedId = db.feedDao().insert(feed())
        val ids = (0 until 1_200).map { post(feedId, "p$it") }
        dao.upsert(ids.mapIndexed { i, id -> ArticleInterestEntity(id, 1 + i % 5, T0, "p") })

        val stored = dao.ratingsOf(ids + ids.take(10))
        assertEquals(1_200, stored.size)
        assertEquals(1 + 777 % 5, stored[ids[777]]!!.rating)
    }

    @Test
    fun aRatingGoesWhenItsFeedIsRemoved() = runTest {
        val feedId = db.feedDao().insert(feed())
        val a = post(feedId, "a")
        dao.upsert(listOf(ArticleInterestEntity(a, 4, T0, "p")))

        db.feedDao().delete(feedId)

        assertEquals(0, dao.count())
    }

    @Test
    fun theTasteProfileGetsTheMostRecentLikesAndSavesFirst() = runTest {
        val feedId = db.feedDao().insert(feed())
        val liked = post(feedId, "a", title = "Liked long ago")
        val saved = post(feedId, "b", title = "Saved yesterday")
        val both = post(feedId, "c", title = "Liked today, saved last week")
        val opened = post(feedId, "d", title = "Only opened")
        val engagement = db.engagementDao()
        engagement.setLiked(liked, T0.minus(Duration.ofDays(30)))
        engagement.setSaved(saved, T0.minus(Duration.ofDays(1)))
        engagement.setSaved(both, T0.minus(Duration.ofDays(7)))
        engagement.setLiked(both, T0)
        engagement.markOpened(opened, T0)

        assertEquals(listOf("Liked today, saved last week", "Saved yesterday", "Liked long ago"), dao.recentLikedOrSavedTitles(10))
        assertEquals(listOf("Liked today, saved last week"), dao.recentLikedOrSavedTitles(1))
        // Un-Liking takes a post out.
        engagement.setLiked(liked, null)
        assertTrue("Liked long ago" !in dao.recentLikedOrSavedTitles(10))
    }
}

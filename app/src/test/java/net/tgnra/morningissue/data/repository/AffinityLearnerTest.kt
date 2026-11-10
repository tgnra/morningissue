package net.tgnra.morningissue.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.NewFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Engagement recording, and how it reaches `feed.affinity` and `tag.affinity`. */
@RunWith(AndroidJUnit4::class)
class AffinityLearnerTest {
    private val clock = MutableClock(T0.plus(Duration.ofHours(1)))
    private lateinit var db: MorningIssueDatabase
    private lateinit var feeds: RoomFeedRepository
    private lateinit var articles: RoomArticleRepository
    private lateinit var learner: AffinityLearner

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = RoomFeedRepository(db, clock)
        articles = RoomArticleRepository(db, clock)
        learner = AffinityLearner(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addFeed(name: String, vararg tags: String): Long =
        feeds.addFeed(NewFeed("https://example.com/$name.xml", title = name, tags = tags.toList())).feedId

    private suspend fun addPost(feedId: Long, guid: String, seenAt: Instant = T0.minus(Duration.ofHours(1))): Long =
        db.articleDao().insertNew(article(feedId, guid, firstSeenAt = seenAt))!!

    /** Stores an edition released at [releaseAt] with [printed] on its front page and [indexed] on the back. */
    private suspend fun addEdition(number: Int, releaseAt: Instant, printed: List<Long>, indexed: List<Long> = emptyList()): Long {
        val edition = EditionEntity(
            date = LocalDate.of(2026, 11, 9).plusDays(number.toLong()),
            kind = EditionKind.MORNING,
            cutoffAt = releaseAt,
            releaseAt = releaseAt,
            builtAt = releaseAt,
            length = EditionLength.STANDARD,
            number = number,
        )
        val items = printed.mapIndexed { slot, id ->
            EditionItemEntity(0, id, page = 1, slot = slot, kind = if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, score = 1.0)
        } + indexed.mapIndexed { slot, id -> EditionItemEntity(0, id, page = 2, slot = slot, kind = ItemKind.INDEX, score = 0.1) }
        return db.editionDao().insertEditionWithItems(edition, items)
    }

    private suspend fun affinityOf(feedId: Long): Double = feeds.getFeed(feedId)!!.affinity

    private suspend fun tagAffinity(name: String): Double = feeds.observeTags().first().single { it.name == name }.affinity

    @Test
    fun everySignalIsStoredAndMovesAffinityAtOnce() = runTest {
        val feed = addFeed("Blog")
        val post = addPost(feed, "a")
        addEdition(1, T0, printed = listOf(post))
        assertEquals(0.5, affinityOf(feed), 0.0)

        articles.markOpened(post)
        val opened = db.engagementDao().get(post)!!
        assertEquals(clock.now, opened.openedAt)
        assertNull(opened.readAt)
        assertEquals((5 + 1 / 3.0) / 11, affinityOf(feed), 1e-3)

        // Scrolling part of the way is remembered but is not a read.
        articles.setReadFraction(post, 0.4f)
        assertEquals(0.4f, db.engagementDao().get(post)!!.readFraction, 0f)
        assertNull(db.engagementDao().get(post)!!.readAt)
        assertEquals((5 + 1 / 3.0) / 11, affinityOf(feed), 1e-3)

        clock.now = clock.now.plus(Duration.ofMinutes(5))
        articles.setReadFraction(post, 0.75f)
        val read = db.engagementDao().get(post)!!
        assertEquals(clock.now, read.readAt)
        assertEquals(0.75f, read.readFraction, 0f)
        // The first open keeps its time; scrolling back up loses nothing.
        assertEquals(opened.openedAt, read.openedAt)
        articles.markOpened(post)
        articles.setReadFraction(post, 0.1f)
        assertEquals(read, db.engagementDao().get(post))
        assertEquals(6.0 / 11, affinityOf(feed), 1e-3)

        articles.setLiked(post, true)
        assertEquals(clock.now, db.engagementDao().get(post)!!.likedAt)
        assertEquals(7.0 / 11, affinityOf(feed), 1e-3)
        articles.setSaved(post, true)
        assertEquals(clock.now, db.engagementDao().get(post)!!.savedAt)
        assertEquals((7 + 2 / 3.0) / 11, affinityOf(feed), 1e-3)

        // Taking a Like or a Save back takes its credit back too.
        articles.setLiked(post, false)
        articles.setSaved(post, false)
        assertNull(db.engagementDao().get(post)!!.likedAt)
        assertNull(db.engagementDao().get(post)!!.savedAt)
        assertEquals(6.0 / 11, affinityOf(feed), 1e-3)
    }

    @Test
    fun markReadWithoutScrollingCountsAsARead() = runTest {
        val feed = addFeed("Blog")
        val post = addPost(feed, "a")
        addEdition(1, T0, printed = listOf(post))

        articles.markOpened(post)
        articles.markRead(post)

        assertNotNull(db.engagementDao().get(post)!!.readAt)
        assertEquals(6.0 / 11, affinityOf(feed), 1e-3)
    }

    @Test
    fun todaysUnopenedStoriesOnlyCountOnceTheEditionIsSuperseded() = runTest {
        val read = addFeed("Read")
        val ignored = addFeed("Ignored")
        val listed = addFeed("Listed")
        val readPost = addPost(read, "r")
        val ignoredPost = addPost(ignored, "i")
        val listedPost = addPost(listed, "l")
        addEdition(1, T0, printed = listOf(readPost, ignoredPost), indexed = listOf(listedPost))

        articles.markOpened(readPost)
        articles.markRead(readPost)

        // The paper is still current: not having got to a story yet is not held against it.
        assertEquals(6.0 / 11, affinityOf(read), 1e-3)
        assertEquals(0.5, affinityOf(ignored), 0.0)

        // The next edition supersedes it.
        clock.now = T0.plus(Duration.ofDays(1))
        val next = addPost(read, "r2", seenAt = clock.now)
        addEdition(2, clock.now, printed = listOf(next))
        learner.refresh(clock.now)

        val day = Math.pow(0.5, 1.0 / 30)
        assertEquals(5.0 / (10 + day), affinityOf(ignored), 1e-9)
        // A post that was only listed on the back page was never an exposure.
        assertEquals(0.5, affinityOf(listed), 0.0)
        // While building, every stored edition counts, the newest included.
        val building = learner.learn(clock.now, pendingEditionId = null)
        assertEquals((5 + day) / (10 + day + 1), building.feeds.getValue(read), 1e-3)
    }

    @Test
    fun readingFromASourcePageCountsWithoutAnEdition() = runTest {
        val feed = addFeed("Blog")
        val post = addPost(feed, "a")

        articles.markOpened(post)
        articles.setReadFraction(post, 1f)
        articles.setLiked(post, true)

        assertEquals(7.0 / 11, affinityOf(feed), 1e-3)
    }

    @Test
    fun tagsLearnAndNewFeedsBorrowFromThem() = runTest {
        val loved = addFeed("Loved", "Tech")
        val posts = (0 until 10).map { addPost(loved, "p$it") }
        addEdition(1, T0, printed = posts)
        posts.forEach {
            articles.markOpened(it)
            articles.markRead(it)
            articles.setLiked(it, true)
        }

        // Pooled over the tag's feeds: (5 + 20) / (10 + 10), capped at 1.
        assertEquals(1.0, tagAffinity("Tech"), 0.0)
        assertEquals(1.0, affinityOf(loved), 0.0)

        // A feed added under the tag starts where the tag is; an untagged one starts neutral.
        val newcomer = addFeed("Newcomer", "Tech")
        val plain = addFeed("Plain")
        assertEquals(1.0, affinityOf(newcomer), 0.0)
        assertEquals(0.5, affinityOf(plain), 0.0)

        // Tagging and untagging relearn straight away.
        feeds.setTags(plain, listOf("tech"))
        assertEquals(1.0, affinityOf(plain), 0.0)
        feeds.setTags(newcomer, listOf("Other"))
        assertEquals(0.5, affinityOf(newcomer), 0.0)
        assertEquals(0.5, tagAffinity("Other"), 0.0)

        // Removing the feed that taught the tag leaves the tag neutral again.
        feeds.removeFeed(loved)
        assertEquals(0.5, tagAffinity("Tech"), 0.0)
        assertEquals(0.5, affinityOf(plain), 0.0)
    }

    @Test
    fun unopenedRunsAreOnlyCountedForUntouchedStories() = runTest {
        val feed = addFeed("Blog")
        val untouched = addPost(feed, "untouched")
        val opened = addPost(feed, "opened")
        val readOnly = addPost(feed, "read")
        val liked = addPost(feed, "liked")
        val saved = addPost(feed, "saved")
        val listed = addPost(feed, "listed")
        val edition = addEdition(1, T0, printed = listOf(untouched, opened, readOnly, liked, saved), indexed = listOf(listed))
        articles.markOpened(opened)
        // From the long-press menu, without opening the story.
        articles.markRead(readOnly)
        articles.setLiked(liked, true)
        articles.setSaved(saved, true)

        db.engagementDao().countUnopenedRun(edition)

        assertEquals(1, db.engagementDao().get(untouched)?.editionsRunUnopened)
        listOf(opened, readOnly, liked, saved).forEach { assertEquals(0, db.engagementDao().get(it)?.editionsRunUnopened) }
        assertNull(db.engagementDao().get(listed))
    }

    @Test
    fun heldOverPoolIsTheNewestUntouchedPostsUpToTheCutoff() = runTest {
        val feed = addFeed("Blog")
        val other = addFeed("Other")
        fun ago(days: Long) = T0.minus(Duration.ofDays(days))
        val posts = (1..6L).map { db.articleDao().insertNew(article(feed, "p$it", firstSeenAt = ago(it), publishedAt = ago(it)))!! }
        val afterCutoff = db.articleDao().insertNew(article(feed, "new", firstSeenAt = T0.plusSeconds(1)))!!
        db.articleDao().insertNew(article(other, "other", firstSeenAt = ago(2)))
        articles.markOpened(posts[0])
        articles.setSaved(posts[1], true)
        // An empty engagement row (the post ran unopened) does not count as touched.
        db.engagementDao().ensure(posts[2])

        val held = db.articleDao().heldOverForFeed(feed, upTo = T0, limit = 3).map { it.article.id }

        assertEquals(listOf(posts[2], posts[3], posts[4]), held)
        assertEquals(false, afterCutoff in db.articleDao().heldOverForFeed(feed, upTo = T0, limit = 50).map { it.article.id })
    }
}

package net.tgnra.morningissue.server.editions

import androidx.room3.withWriteTransaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import net.tgnra.morningissue.server.sync.Relearner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engagement as merged events store it, and how it reaches `subscription.affinity` and
 * `tag.affinity`: the app's `AffinityLearnerTest`, per user.
 *
 * On the phone every action relearned at once inside `RoomArticleRepository`; on the server
 * the engagement comes in as events and `Relearner` relearns after a debounce. [Reader] writes
 * an action and relearns at once, so the numbers are the app's.
 */
class AffinityLearnerTest {
    private val t0: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val world = TestWorld(t0.plus(Duration.ofHours(1)))
    private val db = world.db
    private val learner = AffinityLearner(db)

    @After
    fun tearDown() = world.close()

    /** As the app's feeds here: no cadence measured yet, so the prior has its default strength. */
    private suspend fun addFeed(userId: Long, title: String, tags: List<String> = emptyList(), addedAt: Instant = t0.minus(Duration.ofDays(30))): Long =
        world.addFeed(userId, title, medianHours = null, addedAt = addedAt, tags = tags)

    private suspend fun addPost(feedId: Long, seenAt: Instant = t0.minus(Duration.ofHours(1)), publishedAt: Instant = seenAt): Long =
        world.addPost(feedId, firstSeenAt = seenAt, publishedAt = publishedAt)

    /** Stores [userId]'s edition released at [releaseAt] with [printed] on its front page and [indexed] on the back. */
    private suspend fun addEdition(userId: Long, number: Int, releaseAt: Instant, printed: List<Long>, indexed: List<Long> = emptyList()): Long {
        val edition = EditionEntity(
            userId = userId,
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

    @Test
    fun everySignalIsStoredAndMovesAffinityAtOnce() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val feed = addFeed(user, "Blog")
        val post = addPost(feed)
        addEdition(user, 1, t0, printed = listOf(post))
        assertEquals(0.5, world.affinityOf(user, feed), 0.0)

        reader.markOpened(post)
        val opened = db.engagementDao().get(user, post)!!
        assertEquals(world.clock.now, opened.openedAt)
        assertNull(opened.readAt)
        assertEquals((5 + 1 / 3.0) / 11, world.affinityOf(user, feed), 1e-3)

        // Scrolling part of the way is remembered but is not a read.
        reader.setReadFraction(post, 0.4f)
        assertEquals(0.4f, db.engagementDao().get(user, post)!!.readFraction, 0f)
        assertNull(db.engagementDao().get(user, post)!!.readAt)
        assertEquals((5 + 1 / 3.0) / 11, world.affinityOf(user, feed), 1e-3)

        world.clock.now = world.clock.now.plus(Duration.ofMinutes(5))
        reader.setReadFraction(post, 0.75f)
        val read = db.engagementDao().get(user, post)!!
        assertEquals(world.clock.now, read.readAt)
        assertEquals(0.75f, read.readFraction, 0f)
        // The first open keeps its time; scrolling back up loses nothing.
        assertEquals(opened.openedAt, read.openedAt)
        reader.markOpened(post)
        reader.setReadFraction(post, 0.1f)
        assertEquals(read, db.engagementDao().get(user, post))
        assertEquals(6.0 / 11, world.affinityOf(user, feed), 1e-3)

        reader.setLiked(post, true)
        assertEquals(world.clock.now, db.engagementDao().get(user, post)!!.likedAt)
        assertEquals(7.0 / 11, world.affinityOf(user, feed), 1e-3)
        reader.setSaved(post, true)
        assertEquals(world.clock.now, db.engagementDao().get(user, post)!!.savedAt)
        assertEquals((7 + 2 / 3.0) / 11, world.affinityOf(user, feed), 1e-3)

        // Taking a Like or a Save back takes its credit back too.
        reader.setLiked(post, false)
        reader.setSaved(post, false)
        assertNull(db.engagementDao().get(user, post)!!.likedAt)
        assertNull(db.engagementDao().get(user, post)!!.savedAt)
        assertEquals(6.0 / 11, world.affinityOf(user, feed), 1e-3)
    }

    @Test
    fun markReadWithoutScrollingCountsAsARead() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val feed = addFeed(user, "Blog")
        val post = addPost(feed)
        addEdition(user, 1, t0, printed = listOf(post))

        reader.markOpened(post)
        reader.markRead(post)

        assertNotNull(db.engagementDao().get(user, post)!!.readAt)
        assertEquals(6.0 / 11, world.affinityOf(user, feed), 1e-3)
    }

    @Test
    fun todaysUnopenedStoriesOnlyCountOnceTheEditionIsSuperseded() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val read = addFeed(user, "Read")
        val ignored = addFeed(user, "Ignored")
        val listed = addFeed(user, "Listed")
        val readPost = addPost(read)
        val ignoredPost = addPost(ignored)
        val listedPost = addPost(listed)
        addEdition(user, 1, t0, printed = listOf(readPost, ignoredPost), indexed = listOf(listedPost))

        reader.markOpened(readPost)
        reader.markRead(readPost)

        // The paper is still current: not having got to a story yet is not held against it.
        assertEquals(6.0 / 11, world.affinityOf(user, read), 1e-3)
        assertEquals(0.5, world.affinityOf(user, ignored), 0.0)

        // The next edition supersedes it.
        world.clock.now = t0.plus(Duration.ofDays(1))
        val next = addPost(read, seenAt = world.clock.now)
        addEdition(user, 2, world.clock.now, printed = listOf(next))
        db.withWriteTransaction { learner.refresh(user, world.clock.now) }

        val day = Math.pow(0.5, 1.0 / 30)
        assertEquals(5.0 / (10 + day), world.affinityOf(user, ignored), 1e-9)
        // A post that was only listed on the back page was never an exposure.
        assertEquals(0.5, world.affinityOf(user, listed), 0.0)
        // While building, every stored edition counts, the newest included.
        val building = learner.learn(user, world.clock.now, pendingEditionIds = emptySet())
        assertEquals((5 + day) / (10 + day + 1), building.feeds.getValue(read), 1e-3)
    }

    @Test
    fun readingFromASourcePageCountsWithoutAnEdition() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val feed = addFeed(user, "Blog")
        val post = addPost(feed)

        reader.markOpened(post)
        reader.setReadFraction(post, 1f)
        reader.setLiked(post, true)

        assertEquals(7.0 / 11, world.affinityOf(user, feed), 1e-3)
    }

    @Test
    fun tagsLearnAndNewFeedsBorrowFromThem() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val loved = addFeed(user, "Loved", tags = listOf("Tech"))
        val posts = (0 until 10).map { addPost(loved) }
        addEdition(user, 1, t0, printed = posts)
        posts.forEach {
            reader.markOpened(it)
            reader.markRead(it)
            reader.setLiked(it, true)
        }

        // Pooled over the tag's feeds: (5 + 20) / (10 + 10), capped at 1.
        assertEquals(1.0, world.tagAffinities(user).getValue("Tech"), 0.0)
        assertEquals(1.0, world.affinityOf(user, loved), 0.0)

        // A feed added under the tag starts where the tag is; an untagged one starts neutral.
        // (Subscribing is W-A's; here the next relearn gives the new subscription its value.)
        val newcomer = addFeed(user, "Newcomer", tags = listOf("Tech"))
        val plain = addFeed(user, "Plain")
        reader.relearn()
        assertEquals(1.0, world.affinityOf(user, newcomer), 0.0)
        assertEquals(0.5, world.affinityOf(user, plain), 0.0)

        // Tagging and untagging relearn: the tag is matched without regard to case.
        db.importDao().addSubscriptionTags(user, plain, listOf("tech"))
        reader.relearn()
        assertEquals(1.0, world.affinityOf(user, plain), 0.0)
        world.exec("DELETE FROM subscription_tag WHERE user_id = $user AND feed_id = $newcomer")
        db.importDao().addSubscriptionTags(user, newcomer, listOf("Other"))
        reader.relearn()
        assertEquals(0.5, world.affinityOf(user, newcomer), 0.0)
        assertEquals(0.5, world.tagAffinities(user).getValue("Other"), 0.0)

        // Removing the feed that taught the tag leaves the tag neutral again.
        world.exec(
            "DELETE FROM subscription_tag WHERE user_id = $user AND feed_id = $loved",
            "DELETE FROM subscription WHERE user_id = $user AND feed_id = $loved",
        )
        reader.relearn()
        assertEquals(0.5, world.tagAffinities(user).getValue("Tech"), 0.0)
        assertEquals(0.5, world.affinityOf(user, plain), 0.0)
    }

    @Test
    fun unopenedRunsAreOnlyCountedForUntouchedStories() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val feed = addFeed(user, "Blog")
        val untouched = addPost(feed)
        val opened = addPost(feed)
        val readOnly = addPost(feed)
        val liked = addPost(feed)
        val saved = addPost(feed)
        val listed = addPost(feed)
        val edition = addEdition(user, 1, t0, printed = listOf(untouched, opened, readOnly, liked, saved), indexed = listOf(listed))
        reader.markOpened(opened)
        // From the long-press menu, without opening the story.
        reader.markRead(readOnly)
        reader.setLiked(liked, true)
        reader.setSaved(saved, true)

        db.engagementDao().countUnopenedRun(user, edition)

        assertEquals(1, db.engagementDao().get(user, untouched)?.editionsRunUnopened)
        listOf(opened, readOnly, liked, saved).forEach { assertEquals(0, db.engagementDao().get(user, it)?.editionsRunUnopened) }
        assertNull(db.engagementDao().get(user, listed))
    }

    @Test
    fun heldOverPoolIsTheNewestUntouchedPostsUpToTheCutoff() = runBlocking {
        val user = world.addUser()
        val reader = Reader(world, user)
        val feed = addFeed(user, "Blog", addedAt = t0.minus(Duration.ofDays(60)))
        val other = addFeed(user, "Other", addedAt = t0.minus(Duration.ofDays(60)))
        fun ago(days: Long) = t0.minus(Duration.ofDays(days))
        val posts = (1..6L).map { addPost(feed, seenAt = ago(it)) }
        val afterCutoff = addPost(feed, seenAt = t0.plusSeconds(1))
        addPost(other, seenAt = ago(2))
        reader.markOpened(posts[0])
        reader.setSaved(posts[1], true)
        // An empty engagement row (the post ran unopened) does not count as touched.
        db.engagementDao().ensure(user, posts[2])

        val held = db.editionArticleDao().heldOverForFeed(user, feed, upTo = t0, limit = 3).map { it.article.id }

        assertEquals(listOf(posts[2], posts[3], posts[4]), held)
        assertFalse(afterCutoff in db.editionArticleDao().heldOverForFeed(user, feed, upTo = t0, limit = 50).map { it.article.id })
    }

    // --- Per user (server only) ------------------------------------------------------------------

    @Test
    fun oneUsersEngagementDoesNotMoveAnotherUsersAffinities() = runBlocking {
        val alice = world.addUser()
        val bob = world.addUser()
        // One global feed, under the same tag name for both.
        val shared = world.addGlobalFeed("Shared", medianHours = null)
        world.subscribe(alice, shared, tags = listOf("Tech"))
        world.subscribe(bob, shared, tags = listOf("Tech"))
        val posts = (0 until 5).map { addPost(shared) }
        addEdition(alice, 1, t0, printed = posts)
        val bobsEdition = addEdition(bob, 1, t0, printed = posts)

        val aliceReads = Reader(world, alice)
        posts.forEach {
            aliceReads.markOpened(it)
            aliceReads.markRead(it)
            aliceReads.setLiked(it, true)
        }
        Reader(world, bob).relearn()

        assertTrue(world.affinityOf(alice, shared) > 0.9)
        assertTrue(world.tagAffinities(alice).getValue("Tech") > 0.9)
        assertEquals(0.5, world.affinityOf(bob, shared), 0.0)
        assertEquals(0.5, world.tagAffinities(bob).getValue("Tech"), 0.0)
        posts.forEach { assertNull(db.engagementDao().get(bob, it)) }

        // Bob's paper is superseded unopened: the runs are held against his affinity alone.
        world.clock.now = t0.plus(Duration.ofDays(1))
        addEdition(bob, 2, world.clock.now, printed = listOf(addPost(shared, seenAt = world.clock.now)))
        db.engagementDao().countUnopenedRun(bob, bobsEdition)
        val before = world.affinityOf(alice, shared)
        Reader(world, bob).relearn()
        aliceReads.relearn()
        assertTrue(world.affinityOf(bob, shared) < 0.5)
        assertEquals(before, world.affinityOf(alice, shared), 1e-3)
        posts.forEach { assertEquals(0, db.engagementDao().get(alice, it)!!.editionsRunUnopened) }
    }

    @Test
    fun theRelearnerRelearnsOnceAfterItsDebounce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val user = world.addUser()
            val feed = addFeed(user, "Blog")
            val post = addPost(feed)
            addEdition(user, 1, t0, printed = listOf(post))
            val relearner = Relearner(db, scope, world.clock, delay = Duration.ofMillis(200))

            world.open(user, post)
            world.read(user, post)
            val first = relearner.schedule(user)
            // Before the debounce is up nothing has moved.
            assertEquals(0.5, world.affinityOf(user, feed), 0.0)
            // A second batch replaces the waiting pass.
            val second = relearner.schedule(user)
            second.join()
            assertTrue(first.isCancelled)
            assertEquals(6.0 / 11, world.affinityOf(user, feed), 1e-3)
        } finally {
            scope.cancel()
        }
    }
}

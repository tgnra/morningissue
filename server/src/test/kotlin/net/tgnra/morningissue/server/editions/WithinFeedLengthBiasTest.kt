package net.tgnra.morningissue.server.editions

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.engine.LabelledPost
import net.tgnra.morningissue.engine.PostTraits
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.WithinFeed
import net.tgnra.morningissue.engine.WithinFeedSignalKind
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.server.db.ArticleEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The length key in a feed that only gives excerpts (the app's `WithinFeedLengthBiasTest`).
 * Opening a post fetches its page, which overwrites `article.wordCount` with the page's length;
 * unopened posts keep the excerpt's. Measured by that column, "the reader opened it" reads as
 * "it is long", every unopened post is "brief", and each morning's new posts, all excerpts, are
 * marked down together. Measured by `article.feedWordCount`, which fetching never changes,
 * every post of the feed is the same length and the key says nothing.
 *
 * On the server the fetched page is a global row shared by every reader of the feed, which
 * makes the bias worse, not better: the learner must read `feedWordCount` here too.
 */
class WithinFeedLengthBiasTest {
    private val firstMorning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val world = TestWorld(firstMorning)
    private val clock = world.clock
    private val db = world.db

    @After
    fun tearDown() = world.close()

    private fun words(count: Int) = (1..count).joinToString(" ") { "word$it" }

    private fun body(count: Int) = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(words(count)))))

    /** A post as sync stores it from an excerpt-only feed: 60 words of feed text. */
    private suspend fun addExcerpt(feedId: Long, guid: String, seenAt: Instant): Long = db.importDao().insertNew(
        ArticleEntity(
            feedId = feedId, guid = guid, url = "https://example.com/$guid", title = "Post $guid", summary = words(EXCERPT_WORDS),
            publishedAt = seenAt, firstSeenAt = seenAt, wordCount = EXCERPT_WORDS, feedWordCount = EXCERPT_WORDS,
        ),
        body(EXCERPT_WORDS),
        words(EXCERPT_WORDS),
    )!!

    @Test
    fun openedPostsOfAnExcerptFeedDoNotMakeItsNewPostsLookBrief() = runBlocking {
        val user = world.addUser()
        val publisher = EditionPublisher(db, world.settings, clock)
        val reader = Reader(world, user)
        val feed = world.addFeed(user, "Excerpts", tier = CadenceTier.DAILY, medianHours = 24.0, addedAt = firstMorning.minus(Duration.ofDays(60)))
        var guid = 0
        for (day in 0..20) {
            clock.now = firstMorning.plus(Duration.ofDays(day.toLong()))
            val seenAt = clock.now.minus(Duration.ofMinutes(30))
            val opened = addExcerpt(feed, "post-${guid++}", seenAt)
            addExcerpt(feed, "post-${guid++}", seenAt)
            // The reader turns through the whole paper ...
            world.viewPages(user, publisher.ensureCurrentEdition(user)!!, clock.now)

            // ... opens one of the two and finishes it; the full text of its page is fetched.
            clock.now = clock.now.plus(Duration.ofHours(1))
            reader.markOpened(opened)
            db.importDao().storeContent(opened, body(PAGE_WORDS), words(PAGE_WORDS), PAGE_WORDS, ContentSource.FETCHED, clock.now)
            reader.setReadFraction(opened, 0.9f)
            assertEquals(PAGE_WORDS, db.importDao().article(opened)!!.wordCount)
            assertEquals(EXCERPT_WORDS, db.importDao().article(opened)!!.feedWordCount)
        }

        // The next morning: two new posts, excerpts like every post this feed has ever given.
        clock.now = firstMorning.plus(Duration.ofDays(21))
        val candidates = listOf(addExcerpt(feed, "new-a", clock.now), addExcerpt(feed, "new-b", clock.now))

        val affinity = AffinityLearner(db)
        // As the build of the next edition sees it: the edition it supersedes counts.
        val evidence = affinity.evidence(user, clock.now, emptySet())
        assertTrue(evidence.size >= 40)

        // The fault, reproduced: the same evidence with length measured by `article.wordCount`.
        val engine = WithinFeed(RankingConfig.Default)
        val biased = engine.learn(
            evidence.map { (id, record) -> LabelledPost(id, record, PostTraits(feed, wordCount = db.importDao().article(id)!!.wordCount)) },
            clock.now,
        )
        val marked = biased.weight(PostTraits(feed, wordCount = db.importDao().article(candidates[0])!!.wordCount))
        assertTrue("W by the stored body's length is ${marked.multiplier}", marked.multiplier < 0.9)
        assertEquals(WithinFeedSignalKind.LENGTH, marked.signal!!.kind)
        assertEquals("brief", marked.signal!!.label)

        // As built: the learner reads the feed's own word count for labelled posts and candidates alike.
        val within = WithinFeedLearner(db)
        val model = within.learn(user, evidence, affinity.windowStart(clock.now), at = clock.now)
        val traits = within.traitsOf(candidates, model)
        for (id in candidates) {
            assertEquals(EXCERPT_WORDS, traits.getValue(id).wordCount)
            val weight = model.weight(traits.getValue(id))
            assertEquals(1.0, weight.multiplier, 1e-9)
            assertNull(weight.signal)
        }
    }

    private companion object {
        const val EXCERPT_WORDS = 60
        const val PAGE_WORDS = 1200
    }
}

package net.tgnra.morningissue.edition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.data.repository.RoomArticleRepository
import net.tgnra.morningissue.data.repository.WithinFeedLearner
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The length key in a feed that only gives excerpts. Opening a post fetches its page, which
 * overwrites `article.wordCount` with the page's length; unopened posts keep the excerpt's.
 * Measured by that column, "the reader opened it" reads as "it is long", every unopened post
 * is "brief", and each morning's new posts, all excerpts, are marked down together. Measured
 * by `article.feedWordCount`, which fetching never changes, every post of the feed is the same
 * length and the key says nothing.
 */
@RunWith(AndroidJUnit4::class)
class WithinFeedLengthBiasTest {
    private val firstMorning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val clock = MutableClock(firstMorning)
    private lateinit var db: MorningIssueDatabase

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun words(count: Int) = (1..count).joinToString(" ") { "word$it" }

    private fun body(count: Int) = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(words(count)))))

    /** A post as sync stores it from an excerpt-only feed: 60 words of feed text. */
    private suspend fun addExcerpt(feedId: Long, guid: String, seenAt: Instant): Long = db.articleDao().insertNew(
        ArticleEntity(
            feedId = feedId, guid = guid, url = "https://example.com/$guid", title = "Post $guid", summary = words(EXCERPT_WORDS),
            publishedAt = seenAt, firstSeenAt = seenAt, wordCount = EXCERPT_WORDS, feedWordCount = EXCERPT_WORDS,
        ),
        body(EXCERPT_WORDS),
        words(EXCERPT_WORDS),
    )!!

    @Test
    fun openedPostsOfAnExcerptFeedDoNotMakeItsNewPostsLookBrief() = runTest {
        val publisher = RoomEditionPublisher(db, FakeSettings(), clock, zone = { ZoneOffset.UTC })
        val articles = RoomArticleRepository(db, clock)
        val feed = db.feedDao().insert(
            FeedEntity(
                url = "https://example.com/feed.xml", siteUrl = "https://example.com/", title = "Excerpts",
                tier = CadenceTier.DAILY, medianIntervalHrs = 24.0, addedAt = firstMorning.minus(Duration.ofDays(60)),
            ),
        )
        var guid = 0
        for (day in 0..20) {
            clock.now = firstMorning.plus(Duration.ofDays(day.toLong()))
            val seenAt = clock.now.minus(Duration.ofMinutes(30))
            val opened = addExcerpt(feed, "post-${guid++}", seenAt)
            addExcerpt(feed, "post-${guid++}", seenAt)
            // Built on open, so nothing is fetched for the stories the reader leaves.
            publisher.ensureCurrentEdition()!!

            // The reader opens one of the two and finishes it; the reader screen fetches its page.
            clock.now = clock.now.plus(Duration.ofHours(1))
            articles.markOpened(opened)
            db.articleDao().storeContent(opened, body(PAGE_WORDS), words(PAGE_WORDS), PAGE_WORDS, ContentSource.FETCHED, clock.now)
            articles.setReadFraction(opened, 0.9f)
            assertEquals(PAGE_WORDS, db.articleDao().getById(opened)!!.wordCount)
            assertEquals(EXCERPT_WORDS, db.articleDao().getById(opened)!!.feedWordCount)
        }

        // The next morning: two new posts, excerpts like every post this feed has ever given.
        clock.now = firstMorning.plus(Duration.ofDays(21))
        val candidates = listOf(addExcerpt(feed, "new-a", clock.now), addExcerpt(feed, "new-b", clock.now))

        val affinity = AffinityLearner(db)
        // As the build of the next edition sees it: the edition it supersedes counts.
        val evidence = affinity.evidence(clock.now, emptySet())
        assertTrue(evidence.size >= 40)

        // The fault, reproduced: the same evidence with length measured by `article.wordCount`.
        val engine = WithinFeed(RankingConfig.Default)
        val biased = engine.learn(
            evidence.map { (id, record) -> LabelledPost(id, record, PostTraits(feed, wordCount = db.articleDao().getById(id)!!.wordCount)) },
            clock.now,
        )
        val marked = biased.weight(PostTraits(feed, wordCount = db.articleDao().getById(candidates[0])!!.wordCount))
        assertTrue("W by the stored body's length is ${marked.multiplier}", marked.multiplier < 0.9)
        assertEquals(WithinFeedSignalKind.LENGTH, marked.signal!!.kind)
        assertEquals("brief", marked.signal!!.label)

        // As built: the learner reads the feed's own word count for labelled posts and candidates alike.
        val within = WithinFeedLearner(db)
        val model = within.learn(evidence, affinity.windowStart(clock.now), at = clock.now)
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

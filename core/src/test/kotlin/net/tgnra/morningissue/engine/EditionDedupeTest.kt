package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-feed deduplication (assembly step 3). */
class EditionDedupeTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val builder = EditionBuilder()
    private var nextArticleId = 1L

    private val blog = FeedProfile(1, "The Blog", CadenceTier.WEEKLY, medianIntervalHours = 168.0, affinity = 0.8)
    private val planet = FeedProfile(2, "Planet", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.4)
    private val wire = FeedProfile(3, "Wire", CadenceTier.FIREHOSE, medianIntervalHours = 0.5, affinity = 0.5)
    private val feeds = listOf(blog, planet, wire)

    private fun post(feed: FeedProfile, title: String, url: String?, ageHours: Double = 2.0) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
        title = title,
        url = url,
    )

    private fun build(vararg posts: Candidate, feeds: List<FeedProfile> = this.feeds, alreadyRun: List<Candidate> = emptyList()) =
        builder.build(posts.toList(), feeds, EditionLength.STANDARD, release, alreadyRun = alreadyRun)

    private fun EditionDraft.ids() = items.map { it.articleId }.toSet()

    @Test
    fun aSharedCanonicalUrlKeepsTheCopyFromTheHigherAffinityFeed() {
        val original = post(blog, "On writing small programs", "https://blog.example/small-programs/")
        val syndicated = post(planet, "The Blog: On writing small programs", "http://www.blog.example/small-programs?utm_source=planet", ageHours = 0.5)
        val other = post(wire, "Something else entirely happened today", "https://wire.example/1")

        val draft = build(syndicated, original, other)

        assertEquals(setOf(original.articleId, other.articleId), draft.ids())
        assertEquals(1, draft.duplicatesDropped)
        val kept = draft.items.single { it.articleId == original.articleId }
        assertEquals(1, kept.alsoInFeeds)
        assertTrue(kept.reason, kept.reason.endsWith("; also in 1 other feed"))
        assertEquals(0, draft.items.single { it.articleId == other.articleId }.alsoInFeeds)
    }

    @Test
    fun theSliderDecidesWhichCopyIsKept() {
        // Planet's learned affinity is lower, but the reader has made it a favourite.
        val favourite = planet.copy(sliderStep = SliderStep.FAVOURITE)
        val original = post(blog, "On writing small programs", "https://blog.example/small-programs")
        val syndicated = post(favourite, "On writing small programs", "https://blog.example/small-programs")

        val draft = build(original, syndicated, feeds = listOf(blog, favourite, wire))

        assertEquals(setOf(syndicated.articleId), draft.ids())
    }

    @Test
    fun anIdenticalTitleFromAnotherFeedIsTheSameStoryWhateverTheLink() {
        val original = post(blog, "Why the Rhine Ran Dry in 2026", "https://blog.example/rhine")
        val wireCopy = post(wire, "Why the Rhine ran dry in 2026 | Wire", "https://wire.example/world/88213", ageHours = 30.0)
        val planetCopy = post(planet, "why the rhine ran dry, in 2026", null, ageHours = 5.0)

        val draft = build(wireCopy, planetCopy, original)

        assertEquals(setOf(original.articleId), draft.ids())
        assertEquals(2, draft.duplicatesDropped)
        assertEquals(2, draft.lead!!.alsoInFeeds)
        assertTrue(draft.lead!!.reason.endsWith("; also in 2 other feeds"))
    }

    @Test
    fun droppedCopiesAreNotListedInTheIndexEither() {
        val original = post(blog, "On writing small programs", "https://blog.example/small-programs")
        val copies = (0 until 3).map { post(wire, "On writing small programs", "https://wire.example/$it", ageHours = 40.0 + it) }

        val draft = build(original, *copies.toTypedArray())

        // The wire's three copies are also copies of each other; one story remains.
        assertEquals(setOf(original.articleId), draft.ids())
        assertTrue(draft.index.isEmpty())
        assertEquals(1, draft.lead!!.alsoInFeeds)
    }

    @Test
    fun titlesOneWordApartAreDifferentStories() {
        val tuesday = post(blog, "Security updates for Tuesday", "https://blog.example/tue")
        val wednesday = post(planet, "Security updates for Wednesday", "https://planet.example/wed")
        val v190 = post(blog, "Rust 1.90 has been released", "https://blog.example/190")
        val v191 = post(wire, "Rust 1.91 has been released", "https://wire.example/191")

        val draft = build(tuesday, wednesday, v190, v191)

        assertEquals(4, draft.items.size)
        assertEquals(0, draft.duplicatesDropped)
    }

    @Test
    fun shortOrGenericTitlesNeverMatchAcrossFeeds() {
        val posts = arrayOf(
            post(blog, "Links", "https://blog.example/links-41"),
            post(planet, "Links", "https://planet.example/links"),
            post(blog, "Weekly update", "https://blog.example/weekly"),
            post(wire, "Weekly update", "https://wire.example/weekly"),
            // Four words but under twenty characters.
            post(blog, "A day at sea", "https://blog.example/sea"),
            post(planet, "A day at sea", "https://planet.example/sea"),
        )

        assertEquals(6, build(*posts).items.size)
    }

    @Test
    fun theSameTitleWithinOneFeedIsNotADuplicate() {
        val monday = post(blog, "Security updates for this week", "https://blog.example/sec-1", ageHours = 50.0)
        val tuesday = post(blog, "Security updates for this week", "https://blog.example/sec-2", ageHours = 26.0)

        assertEquals(2, build(monday, tuesday).items.size)
    }

    @Test
    fun theSameTitleDaysApartIsARecurringColumnNotACopy() {
        val lastWeek = post(blog, "What we are reading this month", "https://blog.example/reading", ageHours = 24.0 * 5)
        val today = post(planet, "What we are reading this month", "https://planet.example/reading", ageHours = 1.0)
        assertEquals(2, build(lastWeek, today).items.size)

        // Within three days it is the same story.
        val recent = post(blog, "What we are reading this month", "https://blog.example/reading-2", ageHours = 60.0)
        assertEquals(1, build(recent, today).items.size)
    }

    @Test
    fun feedsWhosePostsAllLinkToOnePageAreLeftAlone() {
        val status = arrayOf(
            post(wire, "Database maintenance on Thursday", "https://status.example/"),
            post(wire, "Login outage resolved this morning", "https://status.example/"),
            post(wire, "API latency in the EU region", "https://status.example/incidents"),
            post(wire, "Search results delayed for some", "https://status.example/incidents"),
        )

        assertEquals(4, build(*status).items.size)
        // The same post issued twice by its feed (same page, same title) is one story.
        val twice = post(wire, "Search results delayed for some", "https://status.example/incidents#update")
        assertEquals(4, build(*status, twice).items.size)
    }

    @Test
    fun aCopyTheReaderAlreadyReadCountsForTheWholeStory() {
        val original = post(blog, "On writing small programs", "https://blog.example/small-programs")
        val read = post(planet, "On writing small programs", "https://blog.example/small-programs").copy(isOpened = true, isRead = true)
        val other = post(wire, "Something else entirely happened today", "https://wire.example/1")

        val draft = build(original, read, other)

        // The story is finished: listed once, as the copy that was read, and not printed again.
        assertEquals(setOf(read.articleId, other.articleId), draft.ids())
        assertEquals(ItemKind.INDEX, draft.items.single { it.articleId == read.articleId }.kind)
    }

    @Test
    fun aCopyOfAStoryAnEarlierEditionPrintedIsDropped() {
        val printedThisEvening = post(planet, "On writing small programs", "https://blog.example/small-programs")
        val lateCopy = post(blog, "On writing small programs", "https://blog.example/small-programs?utm_campaign=feed")
        val other = post(wire, "Something else entirely happened today", "https://wire.example/1")

        val draft = build(lateCopy, other, alreadyRun = listOf(printedThisEvening))

        // Even from the higher-affinity feed: the story has run. The shadow itself is not placed.
        assertEquals(setOf(other.articleId), draft.ids())
        assertEquals(1, draft.duplicatesDropped)
        assertFalse(draft.items.any { it.alsoInFeeds > 0 })
    }

    @Test
    fun postsWithoutTitleOrLinkAreNeverDuplicates() {
        val bare = (0 until 3).map { Candidate(nextArticleId++, wire.id, release.minus(Duration.ofHours(1L + it)), release) }
        assertEquals(3, builder.build(bare, feeds, EditionLength.STANDARD, release).items.size)
    }
}

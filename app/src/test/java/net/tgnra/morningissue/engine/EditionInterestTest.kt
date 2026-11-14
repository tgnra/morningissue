package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model-interest factor N in a whole edition: what it may reorder, and the rules it never overrides. */
class EditionInterestTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private var nextArticleId = 1L

    private val ars = FeedProfile(1, "Ars Technica", CadenceTier.DAILY, medianIntervalHours = 24.0)
    private val verge = FeedProfile(2, "The Verge", CadenceTier.DAILY, medianIntervalHours = 24.0)

    private fun post(feed: FeedProfile, ageHours: Double) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
    )

    private fun rated(ratings: Map<Long, Int>) = EditionBuilder(scorer = Scorer(interestSource = Interest().source(ratings)))

    private fun EditionDraft.item(candidate: Candidate) = items.single { it.articleId == candidate.articleId }

    /** A realistic morning: a dozen daily feeds of mixed affinity, four posts each. */
    private fun morning(): Pair<List<FeedProfile>, List<Candidate>> {
        val feeds = (1..12L).map { FeedProfile(it, "Feed $it", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.3 + it * 0.05, tags = listOf("t${it % 3}")) }
        val candidates = feeds.flatMap { feed -> (0 until 4).map { post(feed, ageHours = 1.0 + feed.id + it * 7.0) } }
        return feeds to candidates
    }

    @Test
    fun withNoRatingsTheEditionIsIdenticalToOneBuiltWithoutTheModel() {
        val (feeds, candidates) = morning()

        val before = EditionBuilder().build(candidates, feeds, EditionLength.STANDARD, release)
        val neutral = EditionBuilder(scorer = Scorer(interestSource = InterestSource.Neutral)).build(candidates, feeds, EditionLength.STANDARD, release)
        val empty = rated(emptyMap()).build(candidates, feeds, EditionLength.STANDARD, release)
        // Every post rated a plain 3 is the same as none rated.
        val allThrees = rated(candidates.associate { it.articleId to 3 }).build(candidates, feeds, EditionLength.STANDARD, release)
        // Ratings that cannot be read count as unrated.
        val nonsense = rated(candidates.associate { it.articleId to 9 }).build(candidates, feeds, EditionLength.STANDARD, release)

        assertEquals(before, neutral)
        assertEquals(before, empty)
        assertEquals(before, allThrees)
        assertEquals(before, nonsense)
    }

    @Test
    fun aRatingReordersTwoPostsThatWereCloseAndSaysSo() {
        // Two feeds alike, posts an hour apart: the newer leads without the model.
        val newer = post(ars, ageHours = 2.0)
        val older = post(verge, ageHours = 3.0)
        val candidates = listOf(newer, older)

        val before = EditionBuilder().build(candidates, listOf(ars, verge), EditionLength.STANDARD, release)
        assertEquals(newer.articleId, before.lead!!.articleId)

        val after = rated(mapOf(older.articleId to 5, newer.articleId to 2)).build(candidates, listOf(ars, verge), EditionLength.STANDARD, release)
        assertEquals(older.articleId, after.lead!!.articleId)
        assertEquals("New from The Verge since the last edition; ${Scorer.INTEREST_UP}", after.lead!!.reason)
        assertEquals("New from Ars Technica since the last edition; ${Scorer.INTEREST_DOWN}", after.item(newer).reason)
        assertEquals(Scorer().score(verge, older, release).value * 1.25, after.lead!!.score, 1e-12)
    }

    @Test
    fun aRatingCannotOvercomeADaysFreshness() {
        // Within its bounds N is worth at most 1.25 / 0.8 ≈ 1.56: less than a day of a daily feed's freshness (2^(24/36) ≈ 1.59).
        val today = post(ars, ageHours = 2.0)
        val yesterday = post(verge, ageHours = 26.0)

        val draft = rated(mapOf(yesterday.articleId to 5, today.articleId to 1)).build(listOf(today, yesterday), listOf(ars, verge), EditionLength.STANDARD, release)

        assertEquals(today.articleId, draft.lead!!.articleId)
    }

    @Test
    fun aRatingCannotBringBackAPostPastTheFreshnessFloor() {
        val stale = post(ars, ageHours = 7 * 24.0)
        val fresh = post(ars, ageHours = 3.0)

        val draft = rated(mapOf(stale.articleId to 5)).build(listOf(stale, fresh), listOf(ars), EditionLength.STANDARD, release)

        assertEquals(ItemKind.INDEX, draft.item(stale).kind)
    }

    @Test
    fun theFrontPageStillHasOneStoryPerFeed() {
        val posts = (0 until 6).map { post(ars, ageHours = 2.0 + it) }
        val other = post(verge, ageHours = 20.0)

        val draft = rated(posts.associate { it.articleId to 5 } + (other.articleId to 1))
            .build(posts + other, listOf(ars, verge), EditionLength.STANDARD, release)

        val front = draft.items.filter { it.kind == ItemKind.LEAD || it.kind == ItemKind.FRONT }
        assertEquals(front.size, front.map { it.feedId }.toSet().size)
    }

    @Test
    fun aHeldOverPostIsJudgedOnItsScoreWithoutTheModel() {
        val loved = FeedProfile(3, "Slow Blog", CadenceTier.WEEKLY, medianIntervalHours = 168.0, affinity = 0.9)
        // About 0.26 without N, above the 0.2 a held-over post needs.
        val above = post(loved, ageHours = 14 * 24.0).copy(isHeldOver = true, editionsRunUnopened = 1)
        // About 0.13 without N, below it; rated 5 it would be 0.16, still below.
        val below = post(loved, ageHours = 14 * 24.0).copy(isHeldOver = true, editionsRunUnopened = 2)
        val other = post(ars, ageHours = 3.0)
        val feeds = listOf(ars, loved)

        // Rated 1 (N = 0.8) the first would score about 0.21 with N; it stays on the regular pages either way.
        val down = rated(mapOf(above.articleId to 1)).build(listOf(above, other), feeds, EditionLength.STANDARD, release)
        assertNotEquals(ItemKind.ARCHIVE, down.item(above).kind)
        val up = rated(mapOf(below.articleId to 5)).build(listOf(below, other), feeds, EditionLength.STANDARD, release)
        assertEquals(ItemKind.ARCHIVE, up.item(below).kind)
    }

    @Test
    fun aHeldOverPostThatLedTwiceDoesNotLeadAgainHoweverItIsRated() {
        val loved = FeedProfile(3, "Slow Blog", CadenceTier.WEEKLY, medianIntervalHours = 168.0, affinity = 1.0)
        val ledTwice = post(loved, ageHours = 24.0).copy(isHeldOver = true, leadCount = 2)
        val other = post(ars, ageHours = 10.0)

        val draft = rated(mapOf(ledTwice.articleId to 5, other.articleId to 1)).build(listOf(ledTwice, other), listOf(ars, loved), EditionLength.STANDARD, release)

        assertEquals(other.articleId, draft.lead!!.articleId)
    }

    @Test
    fun inBriefTakesOnlyPostsWhoseScoreWithoutTheModelReachesItsThreshold() {
        // Fill the front page and Top stories with fresh posts from many feeds, then two weak firehose posts.
        val feeds = (1..30L).map { FeedProfile(it, "Feed $it", CadenceTier.DAILY, medianIntervalHours = 24.0) }
        val strong = feeds.map { post(it, ageHours = 1.0 + it.id * 0.1) }
        val hose = FeedProfile(99, "Firehose", CadenceTier.FIREHOSE, medianIntervalHours = 0.5, affinity = 0.5)
        // A firehose post scores about 0.65 × 1.005 × 0.5^(age / 12 h): about 0.094 at 33.5 h and 0.103 at 32 h.
        val justUnder = post(hose, ageHours = 33.5)
        val justOver = post(hose, ageHours = 32.0)
        val all = strong + justUnder + justOver
        val allFeeds = feeds + hose

        val scorer = Scorer()
        assertTrue(scorer.score(hose, justUnder, release).base < RankingConfig.Default.briefMinScore)
        assertTrue(scorer.score(hose, justOver, release).base >= RankingConfig.Default.briefMinScore)

        val draft = rated(mapOf(justUnder.articleId to 5, justOver.articleId to 1)).build(all, allFeeds, EditionLength.STANDARD, release)

        assertNotEquals(ItemKind.BRIEF, draft.item(justUnder).kind)
        assertEquals(ItemKind.BRIEF, draft.item(justOver).kind)
    }
}

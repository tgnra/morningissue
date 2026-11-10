package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Held-over posts, the lead-twice limit and From the archive (steps 1 and 7, "Carry-over"). */
class EditionCarryOverTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val builder = EditionBuilder()
    private var nextArticleId = 1L

    /**
     * A favourite monthly blog: a fresh post scores about 1.74 (A = 1, S = 1.74), against at
     * most 0.75 for a post of the neutral daily feeds around it.
     */
    private val loved = FeedProfile(1, "Essays", CadenceTier.RARE, medianIntervalHours = 720.0, sliderStep = SliderStep.FAVOURITE)
    private val others = (2..9L).map { FeedProfile(it, "Daily $it", CadenceTier.DAILY, medianIntervalHours = 24.0) }
    private val wire = FeedProfile(20, "Wire", CadenceTier.FIREHOSE, medianIntervalHours = 0.5)
    private val feeds = others + loved + wire

    private fun post(feed: FeedProfile, ageHours: Double) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
    )

    private fun heldOver(ageHours: Double, runUnopened: Int = 0, leadCount: Int = 0, ranRecently: Boolean = false, feed: FeedProfile = loved) =
        post(feed, ageHours).copy(isHeldOver = true, editionsRunUnopened = runUnopened, leadCount = leadCount, ranRecently = ranRecently)

    /** Today's news: one new post from each ordinary feed, and a few from the wire. */
    private fun news(): List<Candidate> = others.map { post(it, 2.0 + it.id) } + (0 until 4).map { post(wire, 1.0 + it) }

    private fun build(vararg extra: Candidate, feeds: List<FeedProfile> = this.feeds) =
        builder.build(news() + extra, feeds, EditionLength.STANDARD, release)

    private val scorer = Scorer()
    private val freshScore = scorer.scarcity(720.0)

    private fun EditionDraft.item(post: Candidate) = items.single { it.articleId == post.articleId }

    @Test
    fun aHeldOverPostCompetesWithItsSeenPenalty() {
        val held = heldOver(ageHours = 26.0, runUnopened = 1)

        val draft = build(held)
        val item = draft.item(held)

        // Halved once it is still the best story of the day.
        assertEquals(ItemKind.LEAD, item.kind)
        assertEquals(freshScore * scorer.freshness(26.0, 720.0) * 0.5, item.score, 1e-12)
        assertEquals("Held over: still unread from Essays, one of your favourites", item.reason)

        // Halved twice it gives way to the fresh posts but is still printed.
        val twice = heldOver(ageHours = 50.0, runUnopened = 2)
        val later = build(twice)
        assertTrue(later.item(twice).kind in setOf(ItemKind.FRONT, ItemKind.TOP))
        assertTrue(later.lead!!.score > later.item(twice).score)
    }

    @Test
    fun aHeldOverPostIsExemptFromTheAgeLimit() {
        val heldOld = heldOver(ageHours = 60 * 24.0)
        val newButOld = post(loved, ageHours = 60 * 24.0)

        val draft = build(heldOld, newButOld)

        // The feed's back catalogue is dropped as before; the held-over post is kept.
        assertEquals(1, draft.staleSkipped)
        assertTrue(draft.items.none { it.articleId == newButOld.articleId })
        assertEquals(ItemKind.ARCHIVE, draft.item(heldOld).kind)
    }

    @Test
    fun aHeldOverPostLeadsTheFrontPageAtMostTwice() {
        // It has led once and is still the best story: it may lead a second time.
        val once = heldOver(ageHours = 26.0, runUnopened = 1, leadCount = 1, ranRecently = true)
        assertEquals(ItemKind.LEAD, build(once).item(once).kind)

        // After two leads it is off the regular pages, however well it scores.
        val twice = heldOver(ageHours = 1.0, leadCount = 2, ranRecently = true)
        val draft = build(twice)
        assertTrue(draft.item(twice).score > draft.lead!!.score)
        assertEquals(ItemKind.INDEX, draft.item(twice).kind)
        assertTrue(draft.printed.none { it.articleId == twice.articleId })

        // ... and comes back only through From the archive, once it has rested.
        val rested = heldOver(ageHours = 1.0, leadCount = 2, ranRecently = false)
        assertEquals(ItemKind.ARCHIVE, build(rested).item(rested).kind)
    }

    @Test
    fun aHeldOverPostThatHasSunkLeavesTheRegularPages() {
        // 1.74 halved four times is under the 0.2 threshold, although the pages have room.
        val sunk = heldOver(ageHours = 2.0, runUnopened = 4, ranRecently = true)
        val draft = build(sunk)

        assertTrue(draft.item(sunk).score < RankingConfig.Default.heldOverMinScore)
        assertEquals(ItemKind.INDEX, draft.item(sunk).kind)
        assertTrue(draft.printed.size < EditionLimits.of(RankingConfig.Default, EditionLength.STANDARD).maxMixedStories)

        // Three halvings leave it just above, so it is still printed.
        val afloat = heldOver(ageHours = 2.0, runUnopened = 3, ranRecently = true)
        assertTrue(build(afloat).item(afloat).kind in setOf(ItemKind.FRONT, ItemKind.TOP))
    }

    @Test
    fun postsThatRanUnopenedAndAreNotHeldOverAreNotPrintedAgain() {
        val ran = post(others.first(), ageHours = 0.1).copy(editionsRunUnopened = 1)

        val draft = build(ran)

        assertEquals(ItemKind.INDEX, draft.item(ran).kind)
        assertTrue(draft.printed.none { it.articleId == ran.articleId })
    }

    @Test
    fun fromTheArchiveTakesTheBestHeldOverPostThatHasNotRunInTheLastEditions() {
        // All three have led twice, so none is on the regular pages.
        val bestButRecent = heldOver(ageHours = 24.0, leadCount = 2, ranRecently = true)
        val best = heldOver(ageHours = 48.0, leadCount = 2)
        val worse = heldOver(ageHours = 96.0, leadCount = 2)

        // Enough wire posts to fill the mixed pages and General and leave some for In brief.
        val moreWire = (0 until 30).map { post(wire, 5.0 + it * 0.2) }.toTypedArray()

        val draft = build(worse, best, bestButRecent, *moreWire)
        val archive = draft.items.filter { it.kind == ItemKind.ARCHIVE }

        assertEquals(listOf(best.articleId), archive.map { it.articleId })
        assertEquals(0, archive.single().slot)
        assertEquals("From the archive: you have not read this one from Essays yet", archive.single().reason)
        assertEquals(ItemKind.INDEX, draft.item(bestButRecent).kind)
        assertEquals(ItemKind.INDEX, draft.item(worse).kind)

        // Its own page, after In brief and before the back-page index.
        val pages = draft.items.groupBy { it.page }.toSortedMap().values.map { page -> page.map { it.kind }.distinct() }
        assertEquals(listOf(ItemKind.ARCHIVE), pages[pages.size - 2])
        assertEquals(listOf(ItemKind.BRIEF), pages[pages.size - 3])
        assertEquals(listOf(ItemKind.INDEX), pages.last())
        assertEquals(1, draft.items.count { it.page == archive.single().page })
    }

    @Test
    fun aPostAlreadyOnAPageIsNotAlsoTheArchivePick() {
        val printed = heldOver(ageHours = 3.0)
        val alone = build(printed)
        assertEquals(ItemKind.LEAD, alone.item(printed).kind)
        assertTrue(alone.items.none { it.kind == ItemKind.ARCHIVE })
        assertEquals(1, alone.items.count { it.articleId == printed.articleId })

        // A second, older held-over post is the archive story; the first keeps its place.
        val older = heldOver(ageHours = 90 * 24.0)
        val both = build(printed, older)
        assertEquals(ItemKind.LEAD, both.item(printed).kind)
        assertEquals(ItemKind.ARCHIVE, both.item(older).kind)
    }

    @Test
    fun onlyHeldOverPostsCanComeFromTheArchive() {
        // A post first seen for this edition is never an archive story, however old it is.
        val draft = build(post(loved, ageHours = 25 * 24.0))
        assertTrue(draft.items.none { it.kind == ItemKind.ARCHIVE })
        assertNull(build().items.firstOrNull { it.kind == ItemKind.ARCHIVE })
    }

    @Test
    fun aMutedFeedsHeldOverPostsWaitForTheMuteToEnd() {
        val held = heldOver(ageHours = 26.0)
        val muted = loved.copy(muteUntil = release.plusSeconds(1))
        val expired = loved.copy(muteUntil = release)

        val whileMuted = build(held, feeds = others + muted + wire)
        assertTrue(whileMuted.items.none { it.articleId == held.articleId })
        assertEquals(1, whileMuted.mutedSkipped)

        // The mute has run out by the time the edition is released: the post is back.
        val after = build(held, feeds = others + expired + wire)
        assertEquals(ItemKind.LEAD, after.item(held).kind)
        assertFalse(after.mutedSkipped > 0)
    }
}

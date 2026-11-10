package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffinityTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val affinity = Affinity()

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    /** A post of feed 1 that ran today, with the given reactions (all today). */
    private fun ran(
        opened: Boolean = false,
        read: Boolean = false,
        saved: Boolean = false,
        liked: Boolean = false,
        feedId: Long = 1,
        at: Instant = now,
    ) = ArticleEvidence(
        feedId = feedId,
        ranAt = listOf(at),
        openedAt = at.takeIf { opened },
        readAt = at.takeIf { read },
        savedAt = at.takeIf { saved },
        likedAt = at.takeIf { liked },
    )

    private fun learnOne(evidence: List<ArticleEvidence>): Double =
        affinity.learn(evidence, mapOf(1L to emptyList()), now).feeds.getValue(1L)

    @Test
    fun aFeedWithoutEvidenceIsNeutral() {
        val learned = affinity.learn(emptyList(), mapOf(1L to emptyList(), 2L to listOf(7L)), now, tagIds = listOf(7L, 8L))

        assertEquals(0.5, learned.feeds.getValue(1L), 0.0)
        assertEquals(0.5, learned.feeds.getValue(2L), 0.0)
        assertEquals(0.5, learned.own.getValue(2L), 0.0)
        // Every tag gets a value, including one no feed carries.
        assertEquals(mapOf(7L to 0.5, 8L to 0.5), learned.tags)
    }

    @Test
    fun eachSignalCarriesItsV1Weight() {
        fun positive(evidence: ArticleEvidence) = affinity.tally(evidence, now).positive

        // In units of "a post opened and read" (Open 1 + Read 2).
        assertEquals(0.0, positive(ran()), 0.0)
        assertEquals(1.0 / 3.0, positive(ran(opened = true)), 1e-12)
        assertEquals(2.0 / 3.0, positive(ran(read = true)), 1e-12)
        assertEquals(1.0, positive(ran(opened = true, read = true)), 1e-12)
        assertEquals(2.0 / 3.0, positive(ran(saved = true)), 1e-12)
        assertEquals(1.0, positive(ran(liked = true)), 1e-12)
        assertEquals(2.0, positive(ran(opened = true, read = true, liked = true)), 1e-12)
        assertEquals(8.0 / 3.0, positive(ran(opened = true, read = true, saved = true, liked = true)), 1e-12)
        // Whatever happened, one run is one exposure.
        assertEquals(1.0, affinity.tally(ran(opened = true, read = true, liked = true), now).exposure, 0.0)
        assertEquals(1.0, affinity.tally(ran(), now).exposure, 0.0)
    }

    @Test
    fun weightsComeFromTheConfig() {
        val custom = Affinity(RankingConfig(openWeight = 1.0, readWeight = 1.0, likeWeight = 4.0))
        assertEquals(0.5, custom.tally(ran(opened = true), now).positive, 1e-12)
        assertEquals(2.0, custom.tally(ran(liked = true), now).positive, 1e-12)
    }

    @Test
    fun thePriorTakesAboutTenExposuresToOvercome() {
        val readPost = ran(opened = true, read = true)

        // (10 × 0.5 + n) / (10 + n)
        assertEquals(6.0 / 11.0, learnOne(List(1) { readPost }), 1e-12)
        assertEquals(8.0 / 13.0, learnOne(List(3) { readPost }), 1e-12)
        assertEquals(0.75, learnOne(List(10) { readPost }), 1e-12)
        assertEquals(105.0 / 110.0, learnOne(List(100) { readPost }), 1e-12)
        // One read post is not yet a loved feed; seven in a row are.
        assertTrue(learnOne(List(6) { readPost }) < 0.7)
        assertTrue(learnOne(List(7) { readPost }) >= 0.7)
    }

    @Test
    fun runningUnopenedPullsAffinityDown() {
        // (10 × 0.5) / (10 + n)
        assertEquals(5.0 / 11.0, learnOne(List(1) { ran() }), 1e-12)
        assertEquals(0.25, learnOne(List(10) { ran() }), 1e-12)
        assertEquals(5.0 / 40.0, learnOne(List(30) { ran() }), 1e-12)

        // A post that ran three times before it was read counts three exposures, one read.
        val lateRead = ArticleEvidence(1, ranAt = List(3) { now }, openedAt = now, readAt = now)
        assertEquals(6.0 / 13.0, learnOne(listOf(lateRead)), 1e-12)

        // Half the posts read: the feed stays neutral.
        assertEquals(0.5, learnOne(List(10) { ran(opened = true, read = true) } + List(10) { ran() }), 1e-12)
    }

    @Test
    fun likesAreExtraCreditAndAffinityStopsAtOne() {
        val liked = ran(opened = true, read = true, liked = true)

        // A Liked post makes up for one that was ignored.
        assertEquals(learnOne(listOf(ran(opened = true, read = true), ran(opened = true, read = true))), learnOne(listOf(liked, ran())), 1e-12)
        assertEquals(1.0, learnOne(List(200) { liked }), 0.0)
        // Opens without reads (clickbait) do not make a loved feed.
        assertTrue(learnOne(List(50) { ran(opened = true) }) < 0.5)
    }

    @Test
    fun evidenceDecaysWithAThirtyDayHalfLife() {
        assertEquals(1.0, affinity.decay(now, now), 0.0)
        assertEquals(0.5, affinity.decay(daysAgo(30), now), 1e-12)
        assertEquals(0.25, affinity.decay(daysAgo(60), now), 1e-12)
        // An edition built early is released in the future: brand new, not more than new.
        assertEquals(1.0, affinity.decay(now.plus(Duration.ofHours(2)), now), 0.0)

        // Ten posts read a month ago weigh as five: (5 + 5) / (10 + 5).
        val old = List(10) { ran(opened = true, read = true, at = daysAgo(30)) }
        assertEquals(10.0 / 15.0, learnOne(old), 1e-12)
        // Old love fades when the recent posts are ignored.
        val drifted = old + List(10) { ran() }
        assertEquals(10.0 / 25.0, learnOne(drifted), 1e-12)
        // And long ago is as good as never.
        assertEquals(0.5, learnOne(List(10) { ran(opened = true, read = true, at = daysAgo(600)) }), 1e-4)
    }

    @Test
    fun readingOutsideAnEditionCountsAsOneExposure() {
        // Opened and read from the source page; never printed.
        val fromSourcePage = ArticleEvidence(1, openedAt = now, readAt = now)
        assertEquals(Tally(positive = 1.0, exposure = 1.0), affinity.tally(fromSourcePage, now))

        // Ran two months ago (weight 0.25), read today from a back issue: the fresh read
        // brings a full exposure with it rather than counting four-fold.
        val fromBackIssue = ArticleEvidence(1, ranAt = listOf(daysAgo(60)), openedAt = now, readAt = now)
        assertEquals(Tally(positive = 1.0, exposure = 1.0), affinity.tally(fromBackIssue, now))

        // An article with no runs and no engagement says nothing.
        assertEquals(Tally(), affinity.tally(ArticleEvidence(1), now))
    }

    @Test
    fun tagsLearnFromTheFeedsCarryingThem() {
        val tech = 10L
        val personal = 11L
        val feedTags = mapOf(1L to listOf(tech), 2L to listOf(tech, personal), 3L to emptyList())
        val evidence = List(5) { ran(opened = true, read = true, feedId = 1) } +
            List(5) { ran(feedId = 2) } +
            List(20) { ran(opened = true, read = true, liked = true, feedId = 3) }

        val learned = affinity.learn(evidence, feedTags, now, tagIds = listOf(tech, personal))

        // Tech pools feeds 1 and 2: (5 + 5) / (10 + 10). Personal has only feed 2: 5 / 15.
        assertEquals(0.5, learned.tags.getValue(tech), 1e-12)
        assertEquals(1.0 / 3.0, learned.tags.getValue(personal), 1e-12)
        // The untagged feed's Likes teach no tag.
        assertEquals(setOf(tech, personal), learned.tags.keys)
    }

    @Test
    fun aNewFeedBorrowsTheAverageOfItsTags() {
        val loved = 10L
        val ignored = 11L
        val feedTags = mapOf(
            1L to listOf(loved),
            2L to listOf(ignored),
            3L to listOf(loved),
            4L to listOf(loved, ignored),
            5L to emptyList(),
        )
        val evidence = List(30) { ran(opened = true, read = true, feedId = 1) } + List(30) { ran(feedId = 2) }

        val learned = affinity.learn(evidence, feedTags, now, tagIds = listOf(loved, ignored))
        val lovedTag = 35.0 / 40.0
        val ignoredTag = 5.0 / 40.0

        assertEquals(lovedTag, learned.tags.getValue(loved), 1e-12)
        assertEquals(ignoredTag, learned.tags.getValue(ignored), 1e-12)
        // No exposures of their own: the tag's affinity, or the average of both tags.
        assertEquals(lovedTag, learned.feeds.getValue(3L), 1e-12)
        assertEquals((lovedTag + ignoredTag) / 2, learned.feeds.getValue(4L), 1e-12)
        // Untagged feeds have nothing to borrow.
        assertEquals(0.5, learned.feeds.getValue(5L), 0.0)
        // Borrowing is kept apart from what the feed earned itself.
        assertEquals(0.5, learned.own.getValue(3L), 0.0)
        assertEquals(35.0 / 40.0, learned.own.getValue(1L), 1e-12)
    }

    @Test
    fun aFeedsOwnRecordTakesOverFromItsTags() {
        val tag = 10L
        // Feed 1 makes the tag loved; feed 2 carries the tag but is ignored n times.
        fun feedTwoAfter(ignoredRuns: Int): Double {
            val evidence = List(90) { ran(opened = true, read = true, feedId = 1) } + List(ignoredRuns) { ran(feedId = 2) }
            val learned = affinity.learn(evidence, mapOf(1L to listOf(tag), 2L to listOf(tag)), now, listOf(tag))
            val tagAffinity = learned.tags.getValue(tag)
            // (10 × tag + 0) / (10 + n)
            assertEquals(10 * tagAffinity / (10 + ignoredRuns), learned.feeds.getValue(2L), 1e-12)
            return learned.feeds.getValue(2L)
        }

        assertEquals(0.95, feedTwoAfter(0), 1e-12)
        assertTrue(feedTwoAfter(2) > 0.7)
        // At ten exposures the feed's own record weighs as much as what it borrowed.
        assertTrue(feedTwoAfter(10) < 0.5)
        assertTrue(feedTwoAfter(40) < 0.2)
    }

    @Test
    fun evidenceOfUnknownFeedsIsIgnored() {
        val learned = affinity.learn(listOf(ran(opened = true, read = true, feedId = 99)), mapOf(1L to emptyList()), now)
        assertEquals(mapOf(1L to 0.5), learned.feeds)
    }

    @Test
    fun theSliderCapsFloorsAndPinsWhatWasLearned() {
        assertEquals(0.15, affinity.effective(0.9, SliderStep.RARELY), 0.0)
        assertEquals(0.05, affinity.effective(0.05, SliderStep.RARELY), 0.0)
        assertEquals(0.35, affinity.effective(0.9, SliderStep.LESS), 0.0)
        assertEquals(0.2, affinity.effective(0.2, SliderStep.LESS), 0.0)
        assertEquals(0.9, affinity.effective(0.9, SliderStep.AUTO), 0.0)
        assertEquals(0.65, affinity.effective(0.2, SliderStep.MORE), 0.0)
        assertEquals(0.9, affinity.effective(0.9, SliderStep.MORE), 0.0)
        assertEquals(1.0, affinity.effective(0.0, SliderStep.FAVOURITE), 0.0)
        assertEquals(1.0, affinity.effective(7.0, SliderStep.AUTO), 0.0)

        // The scorer uses the same rules.
        val scorer = Scorer()
        for (step in SliderStep.entries) {
            for (learned in listOf(0.0, 0.1, 0.5, 0.68, 1.0)) {
                assertEquals(affinity.effective(learned, step), scorer.effectiveAffinity(learned, step), 0.0)
            }
        }
    }

    @Test
    fun lovedMeansHighAffinityOrTheSliderTurnedUp() {
        assertTrue(affinity.isLoved(0.7, SliderStep.AUTO))
        assertFalse(affinity.isLoved(0.69, SliderStep.AUTO))
        assertTrue(affinity.isLoved(0.1, SliderStep.MORE))
        assertTrue(affinity.isLoved(0.1, SliderStep.FAVOURITE))
        // Turned down, a feed is not loved however much was read.
        assertFalse(affinity.isLoved(0.95, SliderStep.LESS))
        assertFalse(affinity.isLoved(0.95, SliderStep.RARELY))
    }

    @Test
    fun onlyLovedSlowFeedsHoldPostsOver() {
        fun feed(tier: CadenceTier, value: Double = 0.9, slider: SliderStep = SliderStep.AUTO) =
            FeedProfile(1, "Feed", tier, affinity = value, sliderStep = slider)

        assertTrue(affinity.holdsPostsOver(feed(CadenceTier.WEEKLY)))
        assertTrue(affinity.holdsPostsOver(feed(CadenceTier.RARE)))
        assertTrue(affinity.holdsPostsOver(feed(CadenceTier.RARE, value = 0.5, slider = SliderStep.MORE)))
        assertFalse(affinity.holdsPostsOver(feed(CadenceTier.DAILY)))
        assertFalse(affinity.holdsPostsOver(feed(CadenceTier.FIREHOSE, slider = SliderStep.FAVOURITE)))
        assertFalse(affinity.holdsPostsOver(feed(CadenceTier.WEEKLY, value = 0.5)))
        assertFalse(affinity.holdsPostsOver(feed(CadenceTier.WEEKLY, slider = SliderStep.LESS)))
    }
}

package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N, the model-interest weight, in the score and in "Why is this here?". */
class ScorerInterestTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val plain = Scorer()
    private val feed = FeedProfile(id = 1, title = "Ars Technica", tier = CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.6)

    private fun post(id: Long = 1, ageHours: Double = 5.0) = Candidate(
        articleId = id,
        feedId = 1,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release,
    )

    private fun scorerWith(n: Double) = Scorer(interestSource = { n })

    @Test
    fun withoutASourceNIsOneAndTheScoreIsWhatItWas() {
        val score = plain.score(feed, post(), release)

        assertEquals(1.0, score.n, 0.0)
        assertEquals(score.base, score.value, 0.0)
        assertEquals(score.a * score.f * score.s * score.p * score.c * score.w, score.value, 0.0)
    }

    @Test
    fun aSourceThatSaysOneChangesNothingAtAll() {
        val before = plain.score(feed, post(), release)
        val neutral = Scorer(interestSource = InterestSource.Neutral).score(feed, post(), release)
        val ones = scorerWith(1.0).score(feed, post(), release)

        assertEquals(before, neutral)
        assertEquals(before, ones)
        assertEquals(plain.reason(feed, post(), before), scorerWith(1.0).reason(feed, post(), ones))
    }

    @Test
    fun nMultipliesTheScoreAndLeavesEveryOtherFactor() {
        val before = plain.score(feed, post(), release)
        val up = scorerWith(1.2).score(feed, post(), release)

        assertEquals(1.2, up.n, 0.0)
        assertEquals(before.value * 1.2, up.value, 1e-12)
        assertEquals(before.value, up.base, 0.0)
        assertEquals(before, up.copy(n = 1.0))
        // A read post stays at zero whatever the model thinks of it.
        assertEquals(0.0, scorerWith(1.25).score(feed, post().copy(isRead = true), release).value, 0.0)
    }

    @Test
    fun theScorerHoldsAnySourceToItsBounds() {
        assertEquals(1.25, scorerWith(4.0).score(feed, post(), release).n, 0.0)
        assertEquals(0.8, scorerWith(0.0).score(feed, post(), release).n, 0.0)
        assertEquals(0.8, scorerWith(-3.0).score(feed, post(), release).n, 0.0)
        assertEquals(1.0, scorerWith(Double.NaN).score(feed, post(), release).n, 0.0)
        assertEquals(1.25, scorerWith(Double.POSITIVE_INFINITY).score(feed, post(), release).n, 0.0)
    }

    @Test
    fun theSourceIsAskedByArticleId() {
        val scorer = Scorer(interestSource = Interest().source(mapOf(7L to 5, 8L to 1)))

        assertEquals(1.25, scorer.score(feed, post(7), release).n, 0.0)
        assertEquals(0.8, scorer.score(feed, post(8), release).n, 0.0)
        assertEquals(1.0, scorer.score(feed, post(9), release).n, 0.0)
    }

    @Test
    fun ratingsMapLinearlyOnEachSideOfThree() {
        val interest = Interest()

        assertEquals(0.8, interest.weightOf(1), 1e-12)
        assertEquals(0.9, interest.weightOf(2), 1e-12)
        assertEquals(1.0, interest.weightOf(3), 0.0)
        assertEquals(1.125, interest.weightOf(4), 1e-12)
        assertEquals(1.25, interest.weightOf(5), 1e-12)
        // Unrated, or nonsense: as if there were no model.
        assertEquals(1.0, interest.weightOf(null), 0.0)
        assertEquals(1.0, interest.weightOf(0), 0.0)
        assertEquals(1.0, interest.weightOf(6), 0.0)
        assertEquals(1.0, interest.weightOf(-1), 0.0)
    }

    @Test
    fun theBoundsComeFromTheConfig() {
        val wide = Interest(RankingConfig(interestMinWeight = 0.5, interestMaxWeight = 2.0))

        assertEquals(0.5, wide.weightOf(1), 1e-12)
        assertEquals(0.75, wide.weightOf(2), 1e-12)
        assertEquals(1.5, wide.weightOf(4), 1e-12)
        assertEquals(2.0, wide.weightOf(5), 1e-12)
    }

    @Test
    fun theReasonLineGetsANoteOnlyWhenNMovedThePostNoticeably() {
        val base = plain.reason(feed, post(), plain.score(feed, post(), release))
        fun reasonAt(rating: Int): String {
            val scorer = Scorer(interestSource = Interest().source(mapOf(1L to rating)))
            return scorer.reason(feed, post(), scorer.score(feed, post(), release))
        }

        assertEquals("$base; ${Scorer.INTEREST_UP}", reasonAt(5))
        assertEquals("$base; ${Scorer.INTEREST_UP}", reasonAt(4))
        assertEquals(base, reasonAt(3))
        assertEquals("$base; ${Scorer.INTEREST_DOWN}", reasonAt(2))
        assertEquals("$base; ${Scorer.INTEREST_DOWN}", reasonAt(1))
        // Under the threshold: no note.
        val nudged = scorerWith(1.05)
        assertEquals(base, nudged.reason(feed, post(), nudged.score(feed, post(), release)))
    }

    @Test
    fun theNoteFollowsEveryKindOfReason() {
        val scorer = scorerWith(1.25)
        val score = scorer.score(feed, post(), release)

        val archive = scorer.reason(feed, post(), score, ItemKind.ARCHIVE)
        assertTrue(archive.startsWith("From the archive: "))
        assertTrue(archive.endsWith("; ${Scorer.INTEREST_UP}"))
    }

    @Test
    fun aComebackKeepsItsLabelWithTheNote() {
        val scorer = scorerWith(1.25)
        val comeback = post().copy(previousPostAt = release.minus(Duration.ofDays(400)))
        val reason = scorer.reason(feed, comeback, scorer.score(feed, comeback, release))

        assertEquals("Back after a year", Scorer.comebackLabelOf(reason))
    }
}

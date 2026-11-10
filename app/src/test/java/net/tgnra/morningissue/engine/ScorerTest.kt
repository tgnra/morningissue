package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorerTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val v1 = Scorer(RankingConfig(halfLifeFloorHours = 4.0))
    private val v2 = Scorer()

    private fun feed(
        affinity: Double = 0.5,
        medianHours: Double? = 24.0,
        tier: CadenceTier = CadenceTier.DAILY,
        slider: SliderStep = SliderStep.AUTO,
        title: String = "Example",
    ) = FeedProfile(id = 1, title = title, tier = tier, medianIntervalHours = medianHours, affinity = affinity, sliderStep = slider)

    private fun post(ageHours: Double, runUnopened: Int = 0, read: Boolean = false) = Candidate(
        articleId = 1,
        feedId = 1,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release,
        editionsRunUnopened = runUnopened,
        isRead = read,
    )

    /** Matches a value printed to two decimals in the v1 document. */
    private fun assertPrinted(expected: Double, actual: Double) = assertEquals(expected, actual, 0.005 + 1e-9)

    @Test
    fun reproducesV1WorkedExamplesWithFourHourFloor() {
        data class Row(
            val name: String,
            val affinity: Double,
            val ageHours: Double,
            val medianHours: Double,
            val a: Double,
            val f: Double,
            val s: Double,
            val score: Double,
        )
        val table = listOf(
            Row("Favourite weekly blog", 0.9, 7 * 24.0, 7 * 24.0, 0.93, 0.62, 1.45, 0.83),
            Row("Daily newsletter, so-so", 0.3, 6.0, 24.0, 0.51, 0.89, 1.15, 0.52),
            Row("News site (firehose), neutral, 3 h", 0.5, 3.0, 0.5, 0.65, 0.59, 1.00, 0.39),
            Row("Daily newsletter, neutral", 0.5, 3 * 24.0, 24.0, 0.65, 0.25, 1.15, 0.19),
            Row("News site (firehose), neutral, 12 h", 0.5, 12.0, 0.5, 0.65, 0.13, 1.00, 0.08),
        )
        for (row in table) {
            val score = v1.score(feed(row.affinity, row.medianHours), post(row.ageHours), release)
            assertEquals("${row.name}: A", row.a, score.a, 0.005 + 1e-9)
            assertEquals("${row.name}: F", row.f, score.f, 0.005 + 1e-9)
            assertEquals("${row.name}: S", row.s, score.s, 0.005 + 1e-9)
            assertEquals("${row.name}: P", 1.0, score.p, 0.0)
            assertEquals("${row.name}: score", row.score, score.value, 0.005 + 1e-9)
        }
    }

    @Test
    fun v1HalfLivesMatchTheFreshnessTable() {
        assertEquals(4.0, v1.halfLifeHours(0.5), 0.0)
        assertEquals(36.0, v1.halfLifeHours(24.0), 0.0)
        assertEquals(240.0, v1.halfLifeHours(7 * 24.0), 0.0)
        assertEquals(240.0, v1.halfLifeHours(30 * 24.0), 0.0)
    }

    @Test
    fun scarcityMatchesTheQuotedValues() {
        assertPrinted(1.0, v2.scarcity(0.5))
        assertPrinted(1.15, v2.scarcity(24.0))
        assertPrinted(1.45, v2.scarcity(7 * 24.0))
        assertPrinted(1.74, v2.scarcity(30 * 24.0))
        assertEquals(1.8, v2.scarcity(10_000 * 24.0), 0.0)
    }

    @Test
    fun v2RaisesTheHalfLifeFloorToTwelveHours() {
        val firehose = feed(medianHours = 0.5, tier = CadenceTier.FIREHOSE)
        val lastNight = post(ageHours = 12.0)

        assertEquals(12.0, v2.halfLifeHours(0.5), 0.0)
        // Last night's news keeps half its freshness by morning instead of an eighth.
        assertEquals(0.5, v2.score(firehose, lastNight, release).f, 1e-9)
        assertEquals(0.125, v1.score(firehose, lastNight, release).f, 1e-9)
        // Feeds whose half-life is above the floor are unchanged.
        assertEquals(v1.halfLifeHours(24.0), v2.halfLifeHours(24.0), 0.0)
    }

    @Test
    fun ageIsMeasuredAtTheReleaseTimeGiven() {
        val daily = feed(medianHours = 24.0)
        val post = post(ageHours = 0.0)

        assertEquals(1.0, v2.score(daily, post, release).f, 1e-9)
        assertEquals(0.5, v2.score(daily, post, release.plus(Duration.ofHours(36))).f, 1e-9)
        assertEquals(0.25, v2.score(daily, post, release.plus(Duration.ofHours(72))).f, 1e-9)
    }

    @Test
    fun postPublishedAfterReleaseCountsAsBrandNew() {
        val post = post(ageHours = 0.0).copy(publishedAt = release.plus(Duration.ofHours(3)))
        assertEquals(1.0, v2.score(feed(), post, release).f, 0.0)
    }

    @Test
    fun seenPenaltyHalvesPerEditionRunUnopened() {
        val feed = feed()
        val fresh = v2.score(feed, post(1.0), release)

        assertEquals(1.0, fresh.p, 0.0)
        assertEquals(0.5, v2.score(feed, post(1.0, runUnopened = 1), release).p, 0.0)
        assertEquals(0.25, v2.score(feed, post(1.0, runUnopened = 2), release).p, 0.0)
        assertEquals(fresh.value * 0.125, v2.score(feed, post(1.0, runUnopened = 3), release).value, 1e-12)
    }

    @Test
    fun readPostScoresZero() {
        val score = v2.score(feed(affinity = 1.0), post(0.0, read = true), release)
        assertEquals(0.0, score.p, 0.0)
        assertEquals(0.0, score.value, 0.0)
    }

    @Test
    fun scoreIsTheProductOfItsFactors() {
        val score = v2.score(feed(affinity = 0.8, medianHours = 48.0), post(20.0, runUnopened = 1), release)
        assertEquals(score.a * score.f * score.s * score.p, score.value, 0.0)
        assertEquals(0.3 + 0.7 * 0.8, score.a, 1e-12)
    }

    @Test
    fun sliderOverridesLearnedAffinity() {
        assertEquals(0.15, v2.effectiveAffinity(0.9, SliderStep.RARELY), 0.0)
        assertEquals(0.05, v2.effectiveAffinity(0.05, SliderStep.RARELY), 0.0)
        assertEquals(0.35, v2.effectiveAffinity(0.9, SliderStep.LESS), 0.0)
        assertEquals(0.9, v2.effectiveAffinity(0.9, SliderStep.AUTO), 0.0)
        assertEquals(0.65, v2.effectiveAffinity(0.2, SliderStep.MORE), 0.0)
        assertEquals(0.9, v2.effectiveAffinity(0.9, SliderStep.MORE), 0.0)
        assertEquals(1.0, v2.effectiveAffinity(0.2, SliderStep.FAVOURITE), 0.0)

        val favourite = v2.score(feed(affinity = 0.2, slider = SliderStep.FAVOURITE), post(0.0), release)
        assertEquals(1.0, favourite.a, 1e-12)
    }

    @Test
    fun affinitySourceReplacesTheStoredValue() {
        val learned = Scorer(affinitySource = { 0.9 })
        assertEquals(0.3 + 0.7 * 0.9, learned.score(feed(affinity = 0.5), post(0.0), release).a, 1e-12)
        // The slider still has the last word.
        assertEquals(0.3 + 0.7 * 0.15, learned.score(feed(slider = SliderStep.RARELY), post(0.0), release).a, 1e-12)
    }

    @Test
    fun unknownCadenceFallsBackToTheTiersTypicalInterval() {
        assertEquals(0.5, v2.intervalHours(feed(medianHours = null, tier = CadenceTier.FIREHOSE)), 0.0)
        assertEquals(24.0, v2.intervalHours(feed(medianHours = null, tier = CadenceTier.DAILY)), 0.0)
        assertEquals(168.0, v2.intervalHours(feed(medianHours = null, tier = CadenceTier.WEEKLY)), 0.0)
        assertEquals(720.0, v2.intervalHours(feed(medianHours = null, tier = CadenceTier.RARE)), 0.0)
        assertEquals(5.0, v2.intervalHours(feed(medianHours = 5.0, tier = CadenceTier.RARE)), 0.0)
    }

    @Test
    fun reasonNamesTheDominantFactor() {
        fun reason(feed: FeedProfile, post: Candidate = post(2.0)) = v2.reason(feed, post, v2.score(feed, post, release))

        assertEquals(
            "You read most posts from Daring Fireball",
            reason(feed(affinity = 0.9, title = "Daring Fireball")),
        )
        assertEquals(
            "Rare post: Slow Blog publishes about once a month",
            reason(feed(medianHours = 30 * 24.0, tier = CadenceTier.RARE, title = "Slow Blog")),
        )
        assertEquals(
            "Rare post: Weekly publishes about once a week",
            reason(feed(medianHours = 7 * 24.0, tier = CadenceTier.WEEKLY, title = "Weekly")),
        )
        assertEquals(
            "New from The Wire since the last edition",
            reason(feed(medianHours = 0.5, tier = CadenceTier.FIREHOSE, title = "The Wire")),
        )
        assertEquals(
            "Pinned is one of your favourites",
            reason(feed(slider = SliderStep.FAVOURITE, title = "Pinned")),
        )
        assertEquals(
            "You asked to see more from More",
            reason(feed(affinity = 0.7, slider = SliderStep.MORE, title = "More")),
        )
        // A mildly liked monthly blog is there for its rarity, not its affinity.
        assertTrue(reason(feed(affinity = 0.6, medianHours = 30 * 24.0)).startsWith("Rare post"))
        assertTrue(reason(feed(affinity = 0.9), post(2.0, runUnopened = 1)).startsWith("Held over"))
    }
}

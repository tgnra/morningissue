package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep

/**
 * Where a feed's learned affinity (0..1) comes from. Milestone 1 reads the feed row, which
 * holds the neutral 0.5 until affinity learning writes to it; a later milestone can swap in a
 * source that falls back to tag affinity without touching [Scorer].
 */
fun interface AffinitySource {
    fun learnedAffinity(feed: FeedProfile): Double

    companion object {
        /** The affinity stored on the feed row. */
        val Stored = AffinitySource { it.affinity }
    }
}

/** One post's score with its factors, kept so the reason line can name the dominant one. */
data class Score(
    /** Affinity after the slider override, 0..1. */
    val affinity: Double,
    val a: Double,
    val f: Double,
    val s: Double,
    val p: Double,
    val ageHours: Double,
    val halfLifeHours: Double,
    /** The median interval used, in hours (the tier's typical one when cadence is unknown). */
    val intervalHours: Double,
) {
    val value: Double = a * f * s * p
}

/**
 * `score = A × F × S × P` from "v1 Live feed" with v2's changes: age is measured at the
 * edition's release time, and P counts editions a post ran in unopened. Every constant comes
 * from [config]; with `halfLifeFloorHours = 4` this reproduces v1's worked examples.
 */
class Scorer(
    private val config: RankingConfig = RankingConfig.Default,
    private val affinitySource: AffinitySource = AffinitySource.Stored,
) {
    /** Scores [candidate] of [feed] as of [at], the edition's release time. */
    fun score(feed: FeedProfile, candidate: Candidate, at: Instant): Score {
        val affinity = effectiveAffinity(affinitySource.learnedAffinity(feed), feed.sliderStep)
        val interval = intervalHours(feed)
        // A post dated after the release moment (an edition built on open) is simply brand new.
        val ageHours = (Duration.between(candidate.publishedAt, at).toMillis() / MILLIS_PER_HOUR).coerceAtLeast(0.0)
        return Score(
            affinity = affinity,
            a = affinityWeight(affinity),
            f = freshness(ageHours, interval),
            s = scarcity(interval),
            p = seenPenalty(candidate.editionsRunUnopened, candidate.isRead),
            ageHours = ageHours,
            halfLifeHours = halfLifeHours(interval),
            intervalHours = interval,
        )
    }

    /** The slider overrides learning: Rarely and Less cap it, More floors it, Favourite pins it. */
    fun effectiveAffinity(learned: Double, slider: SliderStep): Double {
        val affinity = learned.coerceIn(0.0, 1.0)
        return when (slider) {
            SliderStep.RARELY -> min(affinity, config.sliderRarelyCap)
            SliderStep.LESS -> min(affinity, config.sliderLessCap)
            SliderStep.AUTO -> affinity
            SliderStep.MORE -> maxOf(affinity, config.sliderMoreFloor)
            SliderStep.FAVOURITE -> config.sliderFavouriteValue
        }
    }

    /** The feed's median interval, or its tier's typical one while cadence is unknown. */
    fun intervalHours(feed: FeedProfile): Double =
        feed.medianIntervalHours?.takeIf { it >= 0.0 && it.isFinite() } ?: when (feed.tier) {
            CadenceTier.FIREHOSE -> config.firehoseTypicalIntervalHours
            CadenceTier.DAILY -> config.dailyTypicalIntervalHours
            CadenceTier.WEEKLY -> config.weeklyTypicalIntervalHours
            CadenceTier.RARE -> config.rareTypicalIntervalHours
        }

    /** A = floor + range × affinity. */
    fun affinityWeight(affinity: Double): Double = config.affinityFloor + config.affinityRange * affinity

    /** h = clamp(multiplier × medianInterval, floor, ceiling). */
    fun halfLifeHours(medianIntervalHours: Double): Double =
        (config.halfLifeMultiplier * medianIntervalHours).coerceIn(config.halfLifeFloorHours, config.halfLifeCeilingHours)

    /** F = 0.5 ^ (age / h). */
    fun freshness(ageHours: Double, medianIntervalHours: Double): Double =
        0.5.pow(ageHours.coerceAtLeast(0.0) / halfLifeHours(medianIntervalHours))

    /** S = min(cap, 1 + coefficient × log2(1 + medianInterval / 24 h)). */
    fun scarcity(medianIntervalHours: Double): Double =
        min(config.scarcityCap, 1.0 + config.scarcityCoefficient * log2(1.0 + medianIntervalHours / 24.0))

    /** P = base ^ editions run unopened; 0 once the post has been read. */
    fun seenPenalty(editionsRunUnopened: Int, isRead: Boolean): Double =
        if (isRead) 0.0 else config.seenPenaltyBase.pow(editionsRunUnopened.coerceAtLeast(0))

    /**
     * The one-line "Why is this here?" for a scored post, from its dominant factor. Each
     * factor's pull is put on a 0..1 scale: affinity above neutral, scarcity above 1 towards
     * its cap. A held-over post says so first; then a loved feed (or the user's own slider
     * choice) or a rare feed, whichever pulls harder; a post neither explains is there because
     * it is new.
     */
    fun reason(feed: FeedProfile, candidate: Candidate, score: Score): String {
        if (candidate.editionsRunUnopened > 0) {
            return "Held over: still unread from ${feed.title}"
        }
        val affinityPull = ((score.affinity - config.neutralAffinity) / (1.0 - config.neutralAffinity)).coerceIn(0.0, 1.0)
        val scarcityPull = ((score.s - 1.0) / (config.scarcityCap - 1.0)).coerceIn(0.0, 1.0)
        val lovedPull = (config.lovedAffinity - config.neutralAffinity) / (1.0 - config.neutralAffinity)
        return when {
            affinityPull >= lovedPull && affinityPull >= scarcityPull -> when (feed.sliderStep) {
                SliderStep.FAVOURITE -> "${feed.title} is one of your favourites"
                SliderStep.MORE -> "You asked to see more from ${feed.title}"
                else -> "You read most posts from ${feed.title}"
            }
            scarcityPull >= RARE_PULL -> "Rare post: ${feed.title} publishes ${describeInterval(score.intervalHours)}"
            else -> "New from ${feed.title} since the last edition"
        }
    }

    private fun describeInterval(hours: Double): String {
        val days = hours / 24.0
        return when {
            days < 5.0 -> "every few days"
            days < 10.0 -> "about once a week"
            days < 21.0 -> "every couple of weeks"
            days < 45.0 -> "about once a month"
            else -> "less than once a month"
        }
    }

    private fun log2(x: Double): Double = ln(x) / LN_2

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000.0
        val LN_2 = ln(2.0)

        /** Scarcity counts as the reason from roughly a post every three days or slower. */
        const val RARE_PULL = 0.35
    }
}

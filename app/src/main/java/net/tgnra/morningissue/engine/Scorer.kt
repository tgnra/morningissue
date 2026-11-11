package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

/**
 * Where a feed's learned affinity (0..1) comes from. The default reads the value on the feed
 * profile, which affinity learning keeps current with tag borrowing already applied (see
 * [Affinity]); a test or an experiment can swap in another source without touching [Scorer].
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
    /** C, the comeback boost: 1 unless this post ends a long silence of its feed. */
    val c: Double = 1.0,
    /** How long the feed had been quiet, in days, when this post is a comeback; null otherwise. */
    val quietDays: Long? = null,
    /** The within-feed weight with the signal behind it; neutral unless a [WithinFeedSource] is set. */
    val within: WithinFeedWeight = WithinFeedWeight.Neutral,
) {
    /** W, the within-feed multiplier: 1 unless the reader's record tells this feed's posts apart. */
    val w: Double get() = within.multiplier

    val value: Double = a * f * s * p * c * within.multiplier

    val isComeback: Boolean get() = quietDays != null
}

/**
 * `score = A × F × S × P × C × W`, as described under "Scoring" in `docs/how-it-works.md`: age
 * is measured at the edition's release time, P counts editions a post ran in unopened, C is
 * the comeback boost and W the within-feed weight ([WithinFeed]). Every constant comes from
 * [config]; with `halfLifeFloorHours = 4` this reproduces the original live-feed design's
 * worked examples.
 *
 * W is 1 unless a [withinFeedSource] is given, so a scorer built without one scores exactly
 * as before within-feed ranking. It applies whatever the slider says: it orders a feed's own
 * posts, and at most ×1.4 on a turned-down feed's small A does not bring that feed back.
 */
class Scorer(
    private val config: RankingConfig = RankingConfig.Default,
    private val affinitySource: AffinitySource = AffinitySource.Stored,
    private val withinFeedSource: WithinFeedSource = WithinFeedSource.Neutral,
) {
    private val affinityRules = Affinity(config)

    /** Scores [candidate] of [feed] as of [at], the edition's release time. */
    fun score(feed: FeedProfile, candidate: Candidate, at: Instant): Score {
        val affinity = effectiveAffinity(affinitySource.learnedAffinity(feed), feed.sliderStep)
        val interval = intervalHours(feed)
        // A post dated after the release moment (an edition built on open) is simply brand new.
        val ageHours = (Duration.between(candidate.publishedAt, at).toMillis() / MILLIS_PER_HOUR).coerceAtLeast(0.0)
        val quietDays = quietDays(candidate)
        return Score(
            affinity = affinity,
            a = affinityWeight(affinity),
            f = freshness(ageHours, interval),
            s = scarcity(interval),
            p = seenPenalty(candidate.editionsRunUnopened, candidate.isRead),
            ageHours = ageHours,
            halfLifeHours = halfLifeHours(interval),
            intervalHours = interval,
            // The reader turned the feed down: its return is labelled but not pushed forward.
            c = if (quietDays != null && feed.sliderStep >= SliderStep.AUTO) config.comebackBoost else 1.0,
            quietDays = quietDays,
            within = withinFeed(feed, candidate),
        )
    }

    /** W from the source, held to the caps whatever the source returns. */
    private fun withinFeed(feed: FeedProfile, candidate: Candidate): WithinFeedWeight {
        val weight = withinFeedSource.weight(feed, candidate)
        val capped = weight.multiplier.coerceIn(config.withinFeedMinWeight, config.withinFeedMaxWeight)
        return if (capped == weight.multiplier) weight else weight.copy(multiplier = capped)
    }

    /**
     * Welcome back: how many days the feed had been silent before [candidate], when that is
     * at least `comebackQuietDays`; null otherwise. Only a new post can be a comeback, so a
     * held-over reprint does not keep the boost.
     */
    fun quietDays(candidate: Candidate): Long? {
        val previous = candidate.previousPostAt ?: return null
        if (candidate.isHeldOver) return null
        return Duration.between(previous, candidate.publishedAt).toDays().takeIf { it >= config.comebackQuietDays }
    }

    /** The slider applied to learned affinity; `Affinity.effective` is the one implementation. */
    fun effectiveAffinity(learned: Double, slider: SliderStep): Double = affinityRules.effective(learned, slider)

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
     * The one-line "Why is this here?" for a scored post. A From the archive pick and a
     * held-over post say so first. Otherwise the dominant factor speaks, each factor's pull put
     * on a 0..1 scale (affinity above neutral, scarcity above 1 towards its cap, W above 1
     * towards its cap): a loved feed, worded after the user's own slider choice, what they read
     * from the feed, or what they read from its tag when the affinity is borrowed; or a rare
     * feed, whichever pulls harder. A post neither explains is there because it is new.
     *
     * The within-feed signal speaks when W is at least `withinFeedReasonMinWeight` and pulls
     * strictly harder than both affinity and scarcity ([withinFeedReason]). A W below 1 never
     * gives a reason: it is why a post sits lower, not why it is here.
     *
     * @param kind where the post was placed, when known
     */
    fun reason(feed: FeedProfile, candidate: Candidate, score: Score, kind: ItemKind? = null): String {
        if (kind == ItemKind.ARCHIVE) {
            return "From the archive: you have not read this one from ${feed.title} yet"
        }
        if (kind == ItemKind.READING_LIST) {
            return "From your reading list: you saved this from ${feed.title}, which you don't subscribe to"
        }
        if (kind == ItemKind.AWAY) {
            return "While you were away: ${feed.title} ran this in an edition you missed"
        }
        score.quietDays?.let { return "${comebackLabel(it)}: ${feed.title} has posted again" }
        if (candidate.isHeldOver) {
            return "Held over: still unread from ${feed.title}, ${lovedBecause(feed)}"
        }
        val affinityPull = ((score.affinity - config.neutralAffinity) / (1.0 - config.neutralAffinity)).coerceIn(0.0, 1.0)
        val scarcityPull = ((score.s - 1.0) / (config.scarcityCap - 1.0)).coerceIn(0.0, 1.0)
        val lovedPull = (config.lovedAffinity - config.neutralAffinity) / (1.0 - config.neutralAffinity)
        val withinPull = ((score.w - 1.0) / (config.withinFeedMaxWeight - 1.0)).coerceIn(0.0, 1.0)
        if (score.w >= config.withinFeedReasonMinWeight && withinPull > affinityPull && withinPull > scarcityPull) {
            withinFeedReason(feed, score.within.signal)?.let { return it }
        }
        return when {
            affinityPull >= lovedPull && affinityPull >= scarcityPull -> when {
                feed.sliderStep == SliderStep.FAVOURITE -> "${feed.title} is one of your favourites"
                feed.sliderStep == SliderStep.MORE -> "You asked to see more from ${feed.title}"
                feed.borrowedFromTag != null -> "You read most posts tagged ${feed.borrowedFromTag}"
                else -> "You read most posts from ${feed.title}"
            }
            scarcityPull >= RARE_PULL -> "Rare post: ${feed.title} publishes ${describeInterval(score.intervalHours)}"
            else -> "New from ${feed.title} since the last edition"
        }
    }

    /**
     * The line for a within-feed signal that lifted a post, or null when it cannot be said
     * honestly. A key's line says "usually", so it needs the reader to engage with this
     * feed's posts carrying the key at least as often as not (`neutralAffinity`, in posts
     * read): in a feed the reader mostly skips, a category read a little more than the rest
     * still lifts the post but is not called a habit. The text line names Likes only when a
     * Liked post is among those the taste was learned from.
     */
    private fun withinFeedReason(feed: FeedProfile, signal: WithinFeedSignal?): String? {
        if (signal == null || signal.effect <= 0.0) return null
        if (signal.kind == WithinFeedSignalKind.TEXT) {
            return if (signal.fromLikes) "Similar to posts you have Liked" else "Similar to posts you have read"
        }
        if ((signal.rate ?: 0.0) < config.neutralAffinity) return null
        return when (signal.kind) {
            WithinFeedSignalKind.CATEGORY -> "You usually read ${feed.title}'s posts about ${signal.label}"
            WithinFeedSignalKind.LENGTH -> "You usually read ${signal.label} posts from ${feed.title}"
            WithinFeedSignalKind.ENCLOSURE -> when (signal.label) {
                "audio" -> "You usually open audio posts from ${feed.title}"
                "video" -> "You usually open video posts from ${feed.title}"
                else -> "You usually open posts with attachments from ${feed.title}"
            }
            WithinFeedSignalKind.LINK_POST -> "You usually read link posts from ${feed.title}"
            WithinFeedSignalKind.CODE -> "You usually read posts with code from ${feed.title}"
            WithinFeedSignalKind.TITLE_PREFIX -> "You usually read \"${signal.label}\" posts from ${feed.title}"
            WithinFeedSignalKind.TEXT -> null
        }
    }

    /** Why a feed's posts are held over, as the tail of a sentence. */
    private fun lovedBecause(feed: FeedProfile): String = when {
        feed.sliderStep == SliderStep.FAVOURITE -> "one of your favourites"
        feed.sliderStep == SliderStep.MORE -> "a source you asked to see more of"
        feed.borrowedFromTag != null -> "in ${feed.borrowedFromTag}, which you read a lot"
        else -> "a source you read a lot"
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

    /** "Back after 2 years", "Back after 5 months": the label of a comeback post. */
    fun comebackLabel(quietDays: Long): String {
        val years = quietDays / 365
        return when {
            years >= 2 -> "$COMEBACK_PREFIX $years years"
            years == 1L -> "$COMEBACK_PREFIX a year"
            else -> "$COMEBACK_PREFIX ${(quietDays / 30).coerceIn(2, 11)} months"
        }
    }

    private fun log2(x: Double): Double = ln(x) / LN_2

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private val LN_2 = ln(2.0)

        /** Scarcity counts as the reason from roughly a post every three days or slower. */
        private const val RARE_PULL = 0.35

        private const val COMEBACK_PREFIX = "Back after"

        /**
         * The comeback label carried by a stored reason line ("Back after 2 years"), or null
         * when the story is not a comeback. The label is stored nowhere else.
         */
        fun comebackLabelOf(reason: String?): String? =
            reason?.takeIf { it.startsWith("$COMEBACK_PREFIX ") && ':' in it }?.substringBefore(':')
    }
}

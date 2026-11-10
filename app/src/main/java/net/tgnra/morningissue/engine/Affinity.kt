package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep

/**
 * Everything affinity learning knows about one article: when it was printed and what the reader
 * did with it. Articles that were neither printed nor touched carry no evidence and are left out.
 */
data class ArticleEvidence(
    val feedId: Long,
    /**
     * Release times of the editions the article was printed in (any kind but the back-page
     * index), one entry per edition. The caller leaves out runs that cannot count against the
     * feed yet; see [Affinity].
     */
    val ranAt: List<Instant> = emptyList(),
    val openedAt: Instant? = null,
    val readAt: Instant? = null,
    val savedAt: Instant? = null,
    val likedAt: Instant? = null,
) {
    val isEngaged: Boolean get() = openedAt != null || readAt != null || savedAt != null || likedAt != null
}

/** Decayed sums for one feed or tag: [positive] in "posts read", [exposure] in "posts run". */
data class Tally(val positive: Double = 0.0, val exposure: Double = 0.0) {
    operator fun plus(other: Tally) = Tally(positive + other.positive, exposure + other.exposure)
}

/** The result of one learning pass. Every feed and tag passed in has an entry in each map. */
data class LearnedAffinities(
    /** Feed ID to learned affinity, with tag borrowing applied; what `feed.affinity` stores. */
    val feeds: Map<Long, Double>,
    /** Tag ID to learned affinity; what `tag.affinity` stores. */
    val tags: Map<Long, Double>,
    /**
     * Feed ID to the affinity its own evidence gives against a neutral prior, without
     * borrowing. Equal to [feeds] for an untagged feed. Lets a reason line tell "you read this
     * feed" from "you read feeds like it".
     */
    val own: Map<Long, Double>,
)

/**
 * Affinity learning: one number per feed and per tag, 0 to 1, for "how much does this reader
 * care about this source?". Pure; the caller gathers [ArticleEvidence] from the `engagement`
 * and `edition_item` rows and stores the result.
 *
 * **Evidence.** v1 measured positive engagement against impressions. v2 has no scroll-past,
 * skip or dismiss, so the exposure is "the article ran in an edition" (every kind but the
 * back-page index), and running without being opened is the only negative signal.
 *
 * Every event is weighted by its age at the time of learning,
 * `w(t) = 0.5 ^ (age in days / affinityHalfLifeDays)`, so tastes can drift.
 *
 * For one article:
 *
 * ```
 * points   = openWeight·w(opened) + readWeight·w(read) + saveWeight·w(saved) + likeWeight·w(liked)
 * positive = points / (openWeight + readWeight)
 * exposure = max( Σ w(run), largest w of its engagement events )
 * ```
 *
 * `positive` is in units of "a post opened and read": with v1's weights (Open 1, Read 2,
 * Save 2, Like 3) an open alone is worth a third, a read post 1, a read and Liked post 2. A
 * Like or a Save is therefore extra credit that can make up for a post that was ignored. The
 * `max` in `exposure` covers posts read outside an edition (from a source page, search or an
 * old back issue): they count as one exposure at the time of the engagement, so they never add
 * positive evidence without any exposure under it.
 *
 * **Feed and tag affinity.** With `n₀ = affinityPriorStrength` (10 pseudo-exposures) and sums
 * over a feed's articles:
 *
 * ```
 * tag  affinity = clamp01( (n₀·neutral + Σ positive over its feeds) / (n₀ + Σ exposure over its feeds) )
 * feed affinity = clamp01( (n₀·prior   + Σ positive) / (n₀ + Σ exposure) )
 * prior         = mean affinity of the feed's tags, or neutral (0.5) for an untagged feed
 * ```
 *
 * A feed with no evidence sits exactly on its prior; ten read posts in a row lift an untagged
 * feed to 0.75 and ten ignored ones drop it to 0.25. **Tag borrowing** is the prior: a new or
 * rarely seen feed takes the average of its tags' affinity, and its own evidence takes over
 * smoothly as exposures accumulate (half and half at `n₀` decayed exposures) instead of
 * switching at a threshold. Tags learn from the pooled evidence of the feeds carrying them,
 * against the neutral prior, so there is no circularity.
 *
 * **When an unopened run counts.** The caller only passes a run of an untouched article once
 * its edition has been superseded by a later build, the same moment `editionsRunUnopened` is
 * counted; today's paper does not count against a feed while it can still be read.
 *
 * The slider is not part of learning: [effective] applies it on top, and is the single place
 * its caps, floor and pin are implemented.
 */
class Affinity(private val config: RankingConfig = RankingConfig.Default) {
    /** One "post opened and read", in v1's weights. */
    private val fullMarks: Double = config.openWeight + config.readWeight

    /** The weight of an event at [eventAt] when learning at [at]; 1 for now or the future. */
    fun decay(eventAt: Instant, at: Instant): Double {
        val ageDays = Duration.between(eventAt, at).toMillis() / MILLIS_PER_DAY
        if (ageDays <= 0.0) return 1.0
        return 0.5.pow(ageDays / config.affinityHalfLifeDays)
    }

    /** One article's contribution to its feed, as defined in the class comment. */
    fun tally(evidence: ArticleEvidence, at: Instant): Tally {
        var points = 0.0
        var newestEngagement = 0.0
        fun count(eventAt: Instant?, weight: Double) {
            if (eventAt == null) return
            val w = decay(eventAt, at)
            points += weight * w
            if (w > newestEngagement) newestEngagement = w
        }
        count(evidence.openedAt, config.openWeight)
        count(evidence.readAt, config.readWeight)
        count(evidence.savedAt, config.saveWeight)
        count(evidence.likedAt, config.likeWeight)
        val runs = evidence.ranAt.sumOf { decay(it, at) }
        return Tally(
            positive = if (fullMarks > 0.0) points / fullMarks else 0.0,
            exposure = maxOf(runs, newestEngagement),
        )
    }

    /** `(n₀·priorMean + positive) / (n₀ + exposure)`, kept inside 0..1. */
    fun posterior(tally: Tally, priorMean: Double = config.neutralAffinity): Double {
        val weight = config.affinityPriorStrength + tally.exposure
        if (weight <= 0.0) return priorMean.coerceIn(0.0, 1.0)
        return ((config.affinityPriorStrength * priorMean + tally.positive) / weight).coerceIn(0.0, 1.0)
    }

    /**
     * Learns every feed's and tag's affinity as of [at].
     *
     * @param evidence one entry per article with something to say; feeds not in [feedTags]
     *   are ignored
     * @param feedTags every subscribed feed with the IDs of its tags (empty for none)
     * @param tagIds every tag, so that a tag no feed carries still gets a (neutral) value
     */
    fun learn(
        evidence: Collection<ArticleEvidence>,
        feedTags: Map<Long, Collection<Long>>,
        at: Instant,
        tagIds: Collection<Long> = emptyList(),
    ): LearnedAffinities {
        val perFeed = HashMap<Long, Tally>(feedTags.size * 2)
        for (item in evidence) {
            if (item.feedId !in feedTags) continue
            perFeed[item.feedId] = (perFeed[item.feedId] ?: Tally()) + tally(item, at)
        }

        val perTag = HashMap<Long, Tally>()
        tagIds.forEach { perTag[it] = Tally() }
        for ((feedId, tags) in feedTags) {
            val feedTally = perFeed[feedId] ?: Tally()
            for (tagId in tags.toSet()) perTag[tagId] = (perTag[tagId] ?: Tally()) + feedTally
        }
        val tagAffinity = perTag.mapValues { posterior(it.value) }

        val own = HashMap<Long, Double>(feedTags.size * 2)
        val blended = HashMap<Long, Double>(feedTags.size * 2)
        for ((feedId, tags) in feedTags) {
            val feedTally = perFeed[feedId] ?: Tally()
            val distinct = tags.toSet()
            val prior = if (distinct.isEmpty()) config.neutralAffinity else distinct.sumOf { tagAffinity.getValue(it) } / distinct.size
            own[feedId] = posterior(feedTally)
            blended[feedId] = posterior(feedTally, prior)
        }
        return LearnedAffinities(feeds = blended, tags = tagAffinity, own = own)
    }

    /**
     * The slider overrides learning: Rarely caps affinity at 0.15, Less caps it at 0.35, Auto
     * leaves it as learned, More floors it at 0.65 and Favourite pins it at 1.0.
     */
    fun effective(learned: Double, slider: SliderStep): Double {
        val affinity = learned.coerceIn(0.0, 1.0)
        return when (slider) {
            SliderStep.RARELY -> min(affinity, config.sliderRarelyCap)
            SliderStep.LESS -> min(affinity, config.sliderLessCap)
            SliderStep.AUTO -> affinity
            SliderStep.MORE -> maxOf(affinity, config.sliderMoreFloor)
            SliderStep.FAVOURITE -> config.sliderFavouriteValue
        }
    }

    /**
     * A feed is loved with the slider at More or Favourite, or when its affinity after the
     * slider reaches `lovedAffinity` (so Less and Rarely, which cap below it, never are).
     */
    fun isLoved(learned: Double, slider: SliderStep): Boolean =
        slider == SliderStep.MORE || slider == SliderStep.FAVOURITE || effective(learned, slider) >= config.lovedAffinity

    /** Loved Weekly and Rare feeds keep their unread posts eligible for later editions. */
    fun holdsPostsOver(feed: FeedProfile): Boolean =
        (feed.tier == CadenceTier.WEEKLY || feed.tier == CadenceTier.RARE) && isLoved(feed.affinity, feed.sliderStep)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}

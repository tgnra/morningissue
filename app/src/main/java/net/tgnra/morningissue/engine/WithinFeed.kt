package net.tgnra.morningissue.engine

import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import net.tgnra.morningissue.model.EnclosureKind

/**
 * What within-feed ranking knows about one post, apart from what the reader did with it. The
 * caller reads these from storage as they are; nothing here is learned.
 *
 * Not a data class: [embedding] is an array, which a generated `equals` would compare by
 * reference.
 */
class PostTraits(
    val feedId: Long,
    /** Lowercased category keys from the feed's `<category>` elements; often empty. */
    val categories: Set<String> = emptySet(),
    /**
     * Words in the text the feed gave for the post; negative when not known, and the post then
     * has no length key. The caller must measure every post the same way: a count taken from a
     * fetched page for some posts and from the feed's excerpt for others turns "the reader
     * opened it" into "it is long".
     */
    val wordCount: Int = UNKNOWN_WORD_COUNT,
    /** What the post carries besides its text, from the feed's `<enclosure>`. */
    val enclosure: EnclosureKind = EnclosureKind.NONE,
    /** The post mainly points elsewhere. */
    val isLinkPost: Boolean = false,
    val hasCode: Boolean = false,
    /** Stored and carried, not used yet: no key is built from the image or link counts. */
    val imageCount: Int = 0,
    val linkCount: Int = 0,
    /** A recurring-series marker from the title, such as "links" or "podcast"; null for none. */
    val titlePrefix: String? = null,
    /**
     * [isLinkPost] and [hasCode] were computed under the current trait rules, so false means
     * "is not" rather than "not known". Only then does the post carry the keys "not a link
     * post" and "no code".
     */
    val traitsKnown: Boolean = false,
    /**
     * The text embedding, or null when it has not been computed yet. Any length; vectors whose
     * length differs from the first one seen in a learning pass are treated as missing. It need
     * not be normalised: only its direction is used.
     */
    val embedding: FloatArray? = null,
) {
    companion object {
        const val UNKNOWN_WORD_COUNT = -1
    }
}

/**
 * One post the reader has a record with: its evidence (as for [Affinity]) and its traits.
 *
 * @param articleId only used to put the post in one of the two halves of the text model (see
 *   [WithinFeed]), so that the split does not depend on the order posts are passed in
 */
class LabelledPost(val articleId: Long, val evidence: ArticleEvidence, val traits: PostTraits)

/** The kinds of within-feed signal; every kind but [TEXT] is a key learned per feed. */
enum class WithinFeedSignalKind { CATEGORY, LENGTH, ENCLOSURE, LINK_POST, CODE, TITLE_PREFIX, TEXT }

/** One per-feed key of a post: "category space", "length long", "enclosure audio". */
data class TraitKey(val kind: WithinFeedSignalKind, val value: String = "")

/**
 * The signal that decided a post's W.
 *
 * @param label what the reason line names: the category, the length bucket ("long"), the
 *   enclosure ("audio"), the title prefix; empty for a link post, code and text
 * @param effect its contribution to `ln W`, before the cap
 * @param rate for a key, the engagement rate the reader has with this feed's posts carrying
 *   it, in "posts read" (the shrunk posterior); null for [WithinFeedSignalKind.TEXT]
 * @param fromLikes for text, whether a Liked post is among those the taste was learned from
 */
data class WithinFeedSignal(
    val kind: WithinFeedSignalKind,
    val label: String,
    val effect: Double,
    val rate: Double? = null,
    val fromLikes: Boolean = false,
)

/**
 * W for one candidate: the [multiplier] on its score and the [signal] that dominated it (the
 * strongest lift when W is above 1, the strongest drag when it is below; null when W is 1).
 */
data class WithinFeedWeight(val multiplier: Double, val signal: WithinFeedSignal? = null) {
    companion object {
        /** No evidence either way: the score is left exactly as it was. */
        val Neutral = WithinFeedWeight(1.0)
    }
}

/**
 * Where a candidate's W comes from. The default is neutral, which leaves every score as it was
 * before within-feed ranking; the publisher passes [WithinFeedModel.source].
 */
fun interface WithinFeedSource {
    fun weight(feed: FeedProfile, candidate: Candidate): WithinFeedWeight

    companion object {
        val Neutral = WithinFeedSource { _, _ -> WithinFeedWeight.Neutral }
    }
}

/**
 * Within-feed ranking: learns which of a feed's posts this reader takes to, so that two posts
 * of one feed no longer differ by age alone. Pure, like [Affinity]; the caller gathers one
 * [LabelledPost] per post with evidence and gets back a [WithinFeedModel] that gives each
 * candidate a multiplier W for `score = A × F × S × P × C × W`.
 *
 * **Unproven.** There is no engagement history to validate any of this against. Every rule
 * below was chosen to be simple, explainable in one reason line and hard to overfit, and every
 * constant is a guess to be tuned once labels can be exported and tested offline (`todo.md`).
 * A key is capped to `withinFeedKeyMinWeight..withinFeedKeyMaxWeight` (0.7 to 1.4) and W as a
 * whole to `withinFeedMinWeight..withinFeedMaxWeight` (0.5 to 2.0) so that a wrong guess
 * reorders a feed's posts without sinking or crowning them; only a text taste whose two halves
 * agree on plenty of labels can go past a key's bounds (see Combining).
 *
 * **The label.** A post's evidence is turned into the same decayed [Tally] as for feed
 * affinity ([Affinity.tally]: `positive` in "posts read", `exposure` in "posts run", events
 * weighted by a 30-day half-life as of the learning instant). A post with no exposure says
 * nothing. Everything is measured against the post's own feed:
 *
 * ```
 * feed rate r = Σ positive / Σ exposure          over the feed's labelled posts
 * residual    = positive − exposure × r          per post; sums to 0 over a feed
 * ```
 *
 * so a feed the reader always opens does not make all its posts look good, only those that do
 * better than its others. `r` is not clamped: Likes can lift it above 1.
 *
 * **Keys.** Each post carries keys of six kinds: one per category, its length bucket (brief,
 * short, medium, long; `lengthBriefMaxWords` and the two after it), its enclosure when it has
 * one, "link post" and "has code" either way once the post's traits are known (see Binary
 * traits), and its title prefix when it has one. Any other missing trait is no key. For each
 * (feed, key):
 *
 * ```
 * key rate = (n₀·r + Σ positive) / (n₀ + Σ exposure)     over the feed's posts carrying it
 * lift     = key rate − r  =  Σ residual / (n₀ + Σ exposure)
 * ```
 *
 * This is [Affinity.posterior] with the feed's own rate as the prior mean and
 * `n₀ = withinFeedKeyPriorStrength` (10, as for affinity), without the clamp to 0..1, because
 * what is used is the difference of two rates. A key carried by every labelled post of its
 * feed has a lift of exactly 0: it cannot tell the feed's posts apart.
 *
 * **Text taste.** Keys are per feed; text is learned across all feeds so that the few labels
 * pool. Each post with a vector pulls a direction towards itself by its residual:
 *
 * ```
 * direction = Σ residual × (v / |v|)
 * ```
 *
 * Because residuals sum to zero within a feed, a feed's general subject cancels out; what is
 * left is what the posts the reader took to have in common against that feed's other posts.
 *
 * With a few hundred labels in a few hundred dimensions a direction can always be found that
 * separates the read posts from the skipped ones it was built from, whether or not there is
 * any taste to find. So the direction is never judged on its own posts. The labelled posts are
 * split in two halves (by a hash of the article ID), a direction is built from each, and each
 * is tried on the *other* half:
 *
 * ```
 * z(post)  = (cos(post, direction of the other half) − mean) / deviation   over that half
 * slope    = max(0, Σ z × residual / Σ exposure)
 * ```
 *
 * `slope` is the regression slope of a post's engagement above its feed's rate on `z`: "one
 * deviation closer to the taste is worth this many posts read". It is about 0 when the halves
 * do not predict each other (a reader engaging at random), and is floored there: a direction
 * that predicts the opposite of the other half is noise, not a dislike. For a candidate,
 *
 * ```
 * text lift = slope × ½ (z under the first direction + z under the second)
 * ```
 *
 * which is in the same unit as a key's lift, so the two can be compared.
 *
 * *Why standardise.* The raw cosine to the direction depends on the embedding model (static
 * embeddings crowd into a narrow band of cosines), on its dimension and on how many labels
 * there are, so a gain tuned on one model would mean something else on the next. `z` says
 * where the candidate sits among the posts the reader has been shown, which is stable.
 * Standardising alone would be wrong, though: it gives pure noise the same spread as a real
 * taste. The out-of-half slope is what puts the size of the effect back, in honest units. The
 * mean and deviation are taken on the other half too, since a direction's cosines to its own
 * posts are inflated.
 *
 * **Shrinkage.** A feed's keys are trusted by `E / (E + withinFeedFeedPriorPosts)` with `E`
 * the feed's decayed exposure (half at a dozen posts, three quarters at three dozen), on top
 * of each key's own prior. Both use decayed counts, so an old record fades instead of ruling
 * for ever; the cost is that a weekly feed, whose decayed exposure levels off near 7, never
 * gets past about a third of full strength from its keys.
 *
 * The text lift is trusted by how much evidence there is and by how well the halves agree,
 * smoothly, with no threshold anywhere:
 *
 * ```
 * trust     = n / (n + withinFeedTextPriorPosts) × agreement(half 0) × agreement(half 1)
 * agreement = t² / (t² + withinFeedTextConfidenceT²), or 0 when t ≤ 0
 * t         = Σ z × residual / √Σ (z × residual)²      over the half's own posts
 * ```
 *
 * with `n` the decayed exposure of all posts with a vector. `t` is the statistic of "this
 * half's posts are not predicted by the other half's direction". A handful of labels, however
 * unanimous, stay nearly neutral through the first factor (a perfect split of a dozen labels
 * happens by chance); a random reader and halves that disagree stay near 0 through the
 * second; hundreds of labels that agree reach most of full strength. The text lift does not
 * wait for the candidate's own feed to have a record: pooling is its point, and a feed with
 * no labelled posts is still ordered by it.
 *
 * **Binary traits.** Link post and code each give a key either way once the post's traits
 * are known ([PostTraits.traitsKnown]): a link blog's own pieces carry "not a link post"
 * ([NOT]). Without it the rare pieces were told apart only by a small lift down on the many
 * link posts (a key on nine posts in ten sits close to the feed's own rate), and W hardly
 * moved them.
 *
 * **Combining.** Each signal of a candidate has an effect:
 *
 * ```
 * key  effect = clamp(withinFeedGain × trust × lift, ln withinFeedKeyMinWeight, ln withinFeedKeyMaxWeight)
 * text effect = trust × L × tanh(withinFeedGain × text lift / L),   L = withinFeedTextMaxEffect
 * ln W = (largest positive effect, or 0) + (most negative effect, or 0)
 * W    = clamp(exp(ln W), withinFeedMinWeight, withinFeedMaxWeight)
 * ```
 *
 * A key alone stays within 0.7..1.4, as W always did. The text taste's reach grows with its
 * trust: close to `trust × gain × lift` for a small lift, never past `trust × L` (`L = ln 2`),
 * so a text taste with little data is nearly neutral and one whose halves fully agree can
 * double or halve a post's score, enough to beat a day of freshness in a daily feed. W as a
 * whole stays within 0.5..2.0.
 *
 * Only the strongest signal on each side counts. A post's keys are often the same fact told
 * twice (a "podcast" title prefix, an audio enclosure, a "podcast" category; a post about
 * space that is also close to the text taste), and adding or multiplying them would count that
 * fact as many times as the feed happens to mark it. Taking the strongest gives a post marked
 * three ways exactly the weight of a post marked once. Keeping one signal per side, rather
 * than the single strongest, lets a real conflict (a category the reader likes, at a length
 * they skip) net out. What is lost is independent evidence that agrees: two weak, unrelated
 * lifts stay as weak as one. The reason line names the signal that won.
 *
 * A candidate whose feed has no labelled posts and that the text model has nothing to say
 * about gets exactly 1.0. A post without categories, or without a vector, simply has nothing
 * to say for that signal.
 *
 * **Cost.** Linear in posts × dimension: one pass for the tallies, one for the keys and
 * directions, one more over the vectors for the out-of-half check. See `WithinFeedCostTest`.
 */
class WithinFeed(private val config: RankingConfig = RankingConfig.Default) {
    private val affinity = Affinity(config)

    private class Sum {
        var positive = 0.0
        var exposure = 0.0
    }

    private class FeedSums {
        val all = Sum()
        val keys = HashMap<TraitKey, Sum>()
    }

    /**
     * Learns the model as of [at].
     *
     * @param posts one entry per post with evidence, each with its traits. As with
     *   [Affinity.learn], the caller leaves out events older than `affinityWindowDays` and the
     *   unopened runs of an edition that has not been superseded yet. A post's feed is
     *   `traits.feedId`.
     */
    fun learn(posts: Collection<LabelledPost>, at: Instant): WithinFeedModel {
        if (posts.isEmpty()) return WithinFeedModel.empty(config)
        val list = posts as? List<LabelledPost> ?: posts.toList()
        val positive = DoubleArray(list.size)
        val exposure = DoubleArray(list.size)

        // Pass 1: every post's tally, and each feed's and key's sums.
        val sums = HashMap<Long, FeedSums>()
        for (i in list.indices) {
            val post = list[i]
            val tally = affinity.tally(post.evidence, at)
            if (!(tally.exposure > 0.0)) continue
            positive[i] = tally.positive
            exposure[i] = tally.exposure
            val feed = sums.getOrPut(post.traits.feedId) { FeedSums() }
            feed.all.positive += tally.positive
            feed.all.exposure += tally.exposure
            forEachKey(config, post.traits) { key ->
                val sum = feed.keys.getOrPut(key) { Sum() }
                sum.positive += tally.positive
                sum.exposure += tally.exposure
            }
        }
        if (sums.isEmpty()) return WithinFeedModel.empty(config)

        val feeds = HashMap<Long, FeedTaste>(sums.size * 2)
        for ((feedId, feed) in sums) {
            val rate = feed.all.positive / feed.all.exposure
            val lifts = HashMap<TraitKey, Double>(feed.keys.size * 2)
            for ((key, sum) in feed.keys) {
                // Carried by every labelled post: neutral by definition, and exactly so.
                lifts[key] = if (sum.exposure >= feed.all.exposure) {
                    0.0
                } else {
                    (sum.positive - sum.exposure * rate) / (config.withinFeedKeyPriorStrength + sum.exposure)
                }
            }
            feeds[feedId] = FeedTaste(
                rate = rate,
                exposure = feed.all.exposure,
                trust = feed.all.exposure / (feed.all.exposure + config.withinFeedFeedPriorPosts),
                lifts = lifts,
            )
        }
        return WithinFeedModel(config, feeds, learnText(list, positive, exposure, feeds))
    }

    /** Passes 2 and 3: the two half directions and how well each predicts the other half. */
    private fun learnText(
        posts: List<LabelledPost>,
        positive: DoubleArray,
        exposure: DoubleArray,
        feeds: Map<Long, FeedTaste>,
    ): TextTaste? {
        var dimension = 0
        for (post in posts) {
            val size = post.traits.embedding?.size ?: 0
            if (size > 0) {
                dimension = size
                break
            }
        }
        if (dimension == 0) return null

        val residual = DoubleArray(posts.size)
        // 0 marks a post that takes no part: no vector, a zero vector or no exposure.
        val norm = DoubleArray(posts.size)
        val half = ByteArray(posts.size)
        val sums = arrayOf(DoubleArray(dimension), DoubleArray(dimension))
        var labelled = 0.0
        var fromLikes = false
        for (i in posts.indices) {
            if (!(exposure[i] > 0.0)) continue
            val post = posts[i]
            val vector = post.traits.embedding ?: continue
            if (vector.size != dimension) continue
            val length = sqrt(dot(vector, vector))
            if (!(length > 0.0) || !length.isFinite()) continue
            val rate = feeds.getValue(post.traits.feedId).rate
            val r = positive[i] - exposure[i] * rate
            norm[i] = length
            residual[i] = r
            half[i] = halfOf(post.articleId).toByte()
            labelled += exposure[i]
            if (r > 0.0 && post.evidence.likedAt != null) fromLikes = true
            val scale = r / length
            val sum = sums[half[i].toInt()]
            for (j in 0 until dimension) sum[j] += scale * vector[j]
        }
        val directions = arrayOf(unit(sums[0]) ?: return null, unit(sums[1]) ?: return null)

        // Each direction is measured on the posts of the other half only.
        val cosine = DoubleArray(posts.size)
        val weight = DoubleArray(2)
        val first = DoubleArray(2)
        val second = DoubleArray(2)
        for (i in posts.indices) {
            if (norm[i] == 0.0) continue
            val other = 1 - half[i]
            val c = dot(posts[i].traits.embedding!!, directions[other]) / norm[i]
            cosine[i] = c
            weight[other] += exposure[i]
            first[other] += exposure[i] * c
            second[other] += exposure[i] * c * c
        }
        val mean = DoubleArray(2)
        val deviation = DoubleArray(2)
        for (k in 0..1) {
            if (!(weight[k] > 0.0)) return null
            mean[k] = first[k] / weight[k]
            deviation[k] = sqrt((second[k] / weight[k] - mean[k] * mean[k]).coerceAtLeast(0.0))
            if (!(deviation[k] > MIN_DEVIATION)) return null
        }
        // Per half (the posts of that half against the other half's direction): Σ z × residual,
        // and its spread under "no taste", Σ (z × residual)².
        val covariance = DoubleArray(2)
        val spread = DoubleArray(2)
        for (i in posts.indices) {
            if (norm[i] == 0.0) continue
            val own = half[i].toInt()
            val other = 1 - own
            val term = (cosine[i] - mean[other]) / deviation[other] * residual[i]
            covariance[own] += term
            spread[own] += term * term
        }
        // Believed as far as there are labels to believe (a t statistic on a handful of posts
        // is itself noise) and as far as each half predicts the other.
        var confidence = labelled / (labelled + config.withinFeedTextPriorPosts)
        for (k in 0..1) confidence *= agreement(covariance[k], spread[k], config.withinFeedTextConfidenceT)
        return TextTaste(
            directions = directions,
            mean = mean,
            deviation = deviation,
            slope = ((covariance[0] + covariance[1]) / labelled).coerceAtLeast(0.0),
            labelled = labelled,
            trust = confidence,
            fromLikes = fromLikes,
        )
    }

    private fun unit(sum: DoubleArray): FloatArray? {
        var squares = 0.0
        for (x in sum) squares += x * x
        val length = sqrt(squares)
        if (!(length > MIN_DEVIATION) || !length.isFinite()) return null
        return FloatArray(sum.size) { (sum[it] / length).toFloat() }
    }

    internal companion object {
        /** Below this a spread or a length is rounding error, not a measurement. */
        const val MIN_DEVIATION = 1e-9

        /**
         * How far one half's direction predicts the other half: `t² / (t² + t₀²)` for
         * `t = Σ z·residual / √Σ (z·residual)²`, the statistic of "no taste" for that half,
         * and 0 when it predicts the opposite. Half at `t = t₀`, near 1 well past it.
         */
        fun agreement(covariance: Double, spread: Double, t0: Double): Double {
            if (!(covariance > 0.0) || !(spread > 0.0)) return 0.0
            val t2 = covariance * covariance / spread
            return if (t0 > 0.0) t2 / (t2 + t0 * t0) else 1.0
        }

        /** Which half of the text model a post belongs to: one well-mixed bit of its ID. */
        fun halfOf(articleId: Long): Int = ((articleId * -0x61c8864680b583ebL) ushr 63).toInt()

        fun dot(a: FloatArray, b: FloatArray): Double {
            var sum = 0.0
            for (j in a.indices) sum += a[j] * b[j].toDouble()
            return sum
        }

        /** The value of the key for a binary trait the post does not have: "not a link post", "no code". */
        const val NOT = "not"

        fun lengthBucket(config: RankingConfig, wordCount: Int): String? = when {
            wordCount < 0 -> null
            wordCount <= config.lengthBriefMaxWords -> "brief"
            wordCount <= config.lengthShortMaxWords -> "short"
            wordCount <= config.lengthMediumMaxWords -> "medium"
            else -> "long"
        }

        /** The keys of a post, as listed in the class comment. */
        inline fun forEachKey(config: RankingConfig, traits: PostTraits, block: (TraitKey) -> Unit) {
            for (category in traits.categories) block(TraitKey(WithinFeedSignalKind.CATEGORY, category))
            lengthBucket(config, traits.wordCount)?.let { block(TraitKey(WithinFeedSignalKind.LENGTH, it)) }
            if (traits.enclosure != EnclosureKind.NONE) {
                block(TraitKey(WithinFeedSignalKind.ENCLOSURE, traits.enclosure.name.lowercase()))
            }
            if (traits.isLinkPost) {
                block(TraitKey(WithinFeedSignalKind.LINK_POST))
            } else if (traits.traitsKnown) {
                block(TraitKey(WithinFeedSignalKind.LINK_POST, NOT))
            }
            if (traits.hasCode) {
                block(TraitKey(WithinFeedSignalKind.CODE))
            } else if (traits.traitsKnown) {
                block(TraitKey(WithinFeedSignalKind.CODE, NOT))
            }
            traits.titlePrefix?.let { block(TraitKey(WithinFeedSignalKind.TITLE_PREFIX, it)) }
        }
    }
}

/** What was learned about one feed; see [WithinFeed]. */
class FeedTaste internal constructor(
    /** Decayed positive over decayed exposure, in "posts read" per post run. */
    val rate: Double,
    /** The feed's decayed exposure: roughly, how many recent labelled posts it has. */
    val exposure: Double,
    /** How far the feed's keys are believed, 0..1. */
    val trust: Double,
    internal val lifts: Map<TraitKey, Double>,
) {
    /** The key's rate above (or below) the feed's, after shrinkage; 0 for a key never seen. */
    fun lift(key: TraitKey): Double = lifts[key] ?: 0.0
}

/** What was learned from the vectors, across all feeds; see [WithinFeed]. */
class TextTaste internal constructor(
    private val directions: Array<FloatArray>,
    private val mean: DoubleArray,
    private val deviation: DoubleArray,
    /** Posts read gained per deviation of closeness to the taste, as measured out of half; never negative. */
    val slope: Double,
    /** Decayed exposure of the posts with a vector. */
    val labelled: Double,
    /**
     * How far the slope is believed, 0..1: `n / (n + withinFeedTextPriorPosts)` times the two
     * halves' agreement (see [WithinFeed]). It also scales the most the text taste may move a
     * post.
     */
    val trust: Double,
    /** A Liked post is among those that pulled the direction towards themselves. */
    val fromLikes: Boolean,
) {
    val dimension: Int get() = directions[0].size

    /** The candidate's place among the labelled posts, in deviations, averaged over both directions; null when it cannot be placed. */
    fun closeness(vector: FloatArray?): Double? {
        if (vector == null || vector.size != dimension) return null
        val length = sqrt(WithinFeed.dot(vector, vector))
        if (!(length > 0.0) || !length.isFinite()) return null
        var z = 0.0
        for (k in 0..1) z += (WithinFeed.dot(vector, directions[k]) / length - mean[k]) / deviation[k]
        return z / 2.0
    }
}

/**
 * The result of one learning pass: gives any post its W. Immutable and safe to share.
 * [WithinFeed] explains every number.
 */
class WithinFeedModel internal constructor(
    private val config: RankingConfig,
    private val feeds: Map<Long, FeedTaste>,
    /** Null when no direction could be built or checked (no vectors, or too few). */
    val text: TextTaste?,
) {
    /** Nothing was learned: every post gets exactly 1.0 whatever its traits. */
    val isEmpty: Boolean get() = feeds.isEmpty() && text == null

    /** Whether a candidate's vector can change its W: there is a text taste and it predicts something. */
    val usesText: Boolean get() = text != null && text.slope > 0.0

    /** What was learned about a feed; null when it has no labelled posts. */
    fun feed(feedId: Long): FeedTaste? = feeds[feedId]

    /** W for a post with these traits, with the signal that decided it. */
    fun weight(traits: PostTraits): WithinFeedWeight {
        var up = 0.0
        var down = 0.0
        var upSignal: WithinFeedSignal? = null
        var downSignal: WithinFeedSignal? = null

        val feed = feeds[traits.feedId]
        if (feed != null && feed.trust > 0.0) {
            val keyUp = ln(config.withinFeedKeyMaxWeight)
            val keyDown = ln(config.withinFeedKeyMinWeight)
            WithinFeed.forEachKey(config, traits) { key ->
                val lift = feed.lifts[key] ?: return@forEachKey
                val effect = (config.withinFeedGain * feed.trust * lift).coerceIn(keyDown, keyUp)
                // Ties keep the first key, in the fixed order of forEachKey.
                if (effect > up) {
                    up = effect
                    upSignal = WithinFeedSignal(key.kind, key.value, effect, rate = feed.rate + lift)
                } else if (effect < down) {
                    down = effect
                    downSignal = WithinFeedSignal(key.kind, key.value, effect, rate = feed.rate + lift)
                }
            }
        }
        if (text != null && text.slope > 0.0 && text.trust > 0.0) {
            val closeness = text.closeness(traits.embedding)
            if (closeness != null) {
                val effect = textEffect(text.slope * closeness, text.trust)
                if (effect > up) {
                    up = effect
                    upSignal = WithinFeedSignal(WithinFeedSignalKind.TEXT, "", effect, fromLikes = text.fromLikes)
                } else if (effect < down) {
                    down = effect
                    downSignal = WithinFeedSignal(WithinFeedSignalKind.TEXT, "", effect, fromLikes = text.fromLikes)
                }
            }
        }

        val log = up + down
        if (log == 0.0 || log.isNaN()) return WithinFeedWeight.Neutral
        return WithinFeedWeight(
            multiplier = exp(log).coerceIn(config.withinFeedMinWeight, config.withinFeedMaxWeight),
            signal = if (log > 0.0) upSignal else downSignal,
        )
    }

    /**
     * The text taste's contribution to `ln W` for a text lift of [lift] believed [trust]:
     * `trust × L × tanh(gain × lift / L)`, `L = withinFeedTextMaxEffect`. Close to
     * `trust × gain × lift` for a small lift, and never past `trust × L` either way: the most
     * the text taste can move a post grows with how far its two halves agree.
     */
    private fun textEffect(lift: Double, trust: Double): Double {
        val limit = config.withinFeedTextMaxEffect
        if (!(limit > 0.0)) return 0.0
        return trust * limit * tanh(config.withinFeedGain * lift / limit)
    }

    /**
     * The [WithinFeedSource] for a [Scorer]: looks each candidate's traits up by article ID.
     * A candidate not in [traitsByArticleId] is neutral.
     */
    fun source(traitsByArticleId: Map<Long, PostTraits>): WithinFeedSource = WithinFeedSource { _, candidate ->
        traitsByArticleId[candidate.articleId]?.let(::weight) ?: WithinFeedWeight.Neutral
    }

    companion object {
        /** The model of a reader with no record: every post gets exactly 1.0. */
        fun empty(config: RankingConfig = RankingConfig.Default) = WithinFeedModel(config, emptyMap(), null)
    }
}

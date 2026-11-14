package net.tgnra.morningissue.engine

/**
 * Where a candidate's N, the model-interest weight, comes from. The default is neutral, which
 * leaves every score exactly as it was before the factor existed; the publisher passes
 * [Interest.source] over the ratings stored for the build's candidates.
 *
 * Whatever a source returns, [Scorer] holds it to `interestMinWeight..interestMaxWeight`.
 */
fun interface InterestSource {
    fun weight(articleId: Long): Double

    companion object {
        val Neutral = InterestSource { 1.0 }
    }
}

/**
 * The model-interest factor N. The on-device model (Gemini Nano, see `ai/`) rates a post from
 * 1 to 5 for "would this reader want it near the front page"; this maps that rating onto a
 * multiplier, linearly on each side of 3:
 *
 * ```
 * rating 1   2      3    4       5
 * N      0.8 0.9    1.0  1.125   1.25      (with the default bounds)
 * ```
 *
 * A missing rating, or one outside 1..5, is 1: the post is scored as if there were no model.
 * Pure, like the rest of `engine/`.
 */
class Interest(private val config: RankingConfig = RankingConfig.Default) {
    /** N for [rating]; 1 for null or anything outside [MIN_RATING]..[MAX_RATING]. */
    fun weightOf(rating: Int?): Double {
        if (rating == null || rating !in MIN_RATING..MAX_RATING) return 1.0
        val step = (rating - NEUTRAL_RATING).toDouble() / (MAX_RATING - NEUTRAL_RATING)
        return if (step >= 0.0) 1.0 + step * (config.interestMaxWeight - 1.0) else 1.0 + step * (1.0 - config.interestMinWeight)
    }

    /** The [InterestSource] over stored ratings by article ID; a post not in [ratingsByArticleId] is neutral. */
    fun source(ratingsByArticleId: Map<Long, Int>): InterestSource =
        if (ratingsByArticleId.isEmpty()) InterestSource.Neutral else InterestSource { weightOf(ratingsByArticleId[it]) }

    companion object {
        const val MIN_RATING = 1
        const val NEUTRAL_RATING = 3
        const val MAX_RATING = 5
    }
}

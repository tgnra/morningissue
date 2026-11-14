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

    /**
     * Whether [articleId]'s weight comes from the model's own rating of it. Only then does the
     * reason line say the model rated it; an unrated post's weight (the reader's mean) is not
     * a judgement of that post.
     */
    fun isRated(articleId: Long): Boolean = false

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
 * A post the model did not rate takes the reader's **mean rating** instead (`meanRating` in
 * [source]): the mean of the ratings stored over the last `interestMeanWindowDays`, once there
 * are at least `interestMeanMinRatings` of them, mapped the same way and interpolated between
 * whole ratings (a mean of 1.8 is 0.88). Most of a reader's posts are not for them, so most
 * ratings are low; were an unrated post 1, it would gain on every rated post around it just
 * by not having been asked about. With no mean (too few ratings, the setting off, no model), an
 * unrated post is 1, and a rating outside 1..5 counts as no rating.
 *
 * Pure, like the rest of `engine/`.
 */
class Interest(private val config: RankingConfig = RankingConfig.Default) {
    /** N for [rating]; 1 for null or anything outside [MIN_RATING]..[MAX_RATING]. */
    fun weightOf(rating: Int?): Double = if (rating == null || rating !in MIN_RATING..MAX_RATING) 1.0 else weightOf(rating.toDouble())

    /**
     * N for a rating that need not be whole, such as a mean: linear on each side of 3 between
     * the bounds, so a fractional rating falls between the whole ones around it. Held to 1..5;
     * 1 for anything that is not a number.
     */
    fun weightOf(rating: Double): Double {
        if (rating.isNaN()) return 1.0
        val step = (rating.coerceIn(MIN_RATING.toDouble(), MAX_RATING.toDouble()) - NEUTRAL_RATING) / (MAX_RATING - NEUTRAL_RATING)
        return if (step >= 0.0) 1.0 + step * (config.interestMaxWeight - 1.0) else 1.0 + step * (1.0 - config.interestMinWeight)
    }

    /**
     * The [InterestSource] over stored ratings by article ID. A post not in [ratingsByArticleId]
     * (or with a rating outside 1..5) takes [meanRating]'s weight, or 1 when there is none. With
     * neither ratings nor a mean it is [InterestSource.Neutral], and every score is exactly what
     * it was without the model.
     */
    fun source(ratingsByArticleId: Map<Long, Int>, meanRating: Double? = null): InterestSource {
        if (ratingsByArticleId.isEmpty() && meanRating == null) return InterestSource.Neutral
        val unrated = meanRating?.let { weightOf(it) } ?: 1.0
        return object : InterestSource {
            override fun weight(articleId: Long): Double =
                if (isRated(articleId)) weightOf(ratingsByArticleId.getValue(articleId)) else unrated

            override fun isRated(articleId: Long): Boolean = ratingsByArticleId[articleId]?.let { it in MIN_RATING..MAX_RATING } == true
        }
    }

    companion object {
        const val MIN_RATING = 1
        const val NEUTRAL_RATING = 3
        const val MAX_RATING = 5
    }
}

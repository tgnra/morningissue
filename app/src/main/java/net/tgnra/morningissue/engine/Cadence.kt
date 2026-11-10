package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier

/** How often a feed posts, as stored in the cadence columns of `feed`. */
data class CadenceStats(
    /** Median gap between posts in hours; null while the sample is too small. */
    val medianIntervalHours: Double?,
    /** 90th over 10th percentile gap; high for feeds that post in bursts. Null while unknown. */
    val burstiness: Double?,
    val tier: CadenceTier,
    /** Distinct publish times the figures were computed from. */
    val sampleSize: Int,
) {
    val postsPerDay: Double?
        get() = medianIntervalHours?.takeIf { it > 0.0 }?.let { 24.0 / it }
}

/**
 * Feed cadence as defined in "v1 Live feed": computed from the last 30 publish timestamps or
 * the last 90 days, whichever covers more posts. Pure and Android-free; sync calls it after
 * each feed's fetch and writes the result with `FeedDao.updateCadence`.
 */
object Cadence {
    /**
     * [publishTimes] may be in any order and may hold more than the sample needs.
     *
     * Posts sharing a timestamp count once. A feed without dates gets the fetch time for every
     * post of its first sync, and without this it would look like a firehose.
     *
     * With fewer than `cadenceMinSample` distinct timestamps the feed is too new to judge: the
     * tier defaults to Weekly and the figures stay null.
     */
    fun compute(
        publishTimes: Collection<Instant>,
        now: Instant,
        config: RankingConfig = RankingConfig.Default,
    ): CadenceStats {
        val newestFirst = publishTimes.distinct().sortedDescending()
        val windowStart = now.minus(Duration.ofDays(config.cadenceWindowDays.toLong()))
        val inWindow = newestFirst.count { !it.isBefore(windowStart) }
        val sample = newestFirst.take(maxOf(config.cadenceSampleSize, inWindow))

        if (sample.size < config.cadenceMinSample) {
            return CadenceStats(null, null, CadenceTier.WEEKLY, sample.size)
        }

        val intervals = sample
            .zipWithNext { newer, older -> Duration.between(older, newer).toMillis() / MILLIS_PER_HOUR }
            .sorted()
        val median = percentile(intervals, 0.5)
        val p10 = percentile(intervals, 0.1)
        val burstiness = if (p10 > 0.0) percentile(intervals, 0.9) / p10 else null
        return CadenceStats(median, burstiness, tierFor(median, config), sample.size)
    }

    /** Firehose: more than 8 posts a day; Daily: 1 to 8 a day; Weekly: at least 1 a week; else Rare. */
    fun tierFor(medianIntervalHours: Double, config: RankingConfig = RankingConfig.Default): CadenceTier {
        if (medianIntervalHours <= 0.0) return CadenceTier.FIREHOSE
        val postsPerDay = 24.0 / medianIntervalHours
        val postsPerWeek = 168.0 / medianIntervalHours
        return when {
            postsPerDay > config.firehosePostsPerDay -> CadenceTier.FIREHOSE
            postsPerDay >= config.dailyPostsPerDay -> CadenceTier.DAILY
            postsPerWeek >= config.weeklyPostsPerWeek -> CadenceTier.WEEKLY
            else -> CadenceTier.RARE
        }
    }

    /** Linear interpolation between closest ranks; [sorted] is ascending and not empty. */
    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val rank = fraction * (sorted.size - 1)
        val low = rank.toInt()
        val high = minOf(low + 1, sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (rank - low)
    }

    private const val MILLIS_PER_HOUR = 3_600_000.0
}

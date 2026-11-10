package net.tgnra.morningissue.engine

/**
 * Every tunable constant of ranking and edition assembly, from "v1 Live feed" as amended by
 * "v2 Daily edition". Pure Kotlin: nothing in `engine/` may import Android.
 *
 * Durations are in hours unless the name says otherwise.
 */
data class RankingConfig(
    // A: affinity weight = affinityFloor + affinityRange * affinity
    val affinityFloor: Double = 0.3,
    val affinityRange: Double = 0.7,

    // F: freshness = 0.5 ^ (age / h), h = clamp(multiplier * medianInterval, floor, ceiling)
    val halfLifeMultiplier: Double = 1.5,
    /** v2 raises v1's 4 h floor to 12 h so last night's news still counts by morning. */
    val halfLifeFloorHours: Double = 12.0,
    val halfLifeCeilingHours: Double = 240.0,
    val minFreshness: Double = 0.05,
    val maxAgeDays: Int = 30,

    // S: scarcity = min(cap, 1 + coefficient * log2(1 + medianInterval / 24 h))
    val scarcityCoefficient: Double = 0.15,
    val scarcityCap: Double = 1.8,

    // P: seen penalty = base ^ editionsRunUnopened
    val seenPenaltyBase: Double = 0.5,

    // Affinity learning
    val neutralAffinity: Double = 0.5,
    val affinityHalfLifeDays: Double = 30.0,
    /** Pseudo-impressions at neutral affinity (Bayesian prior). */
    val affinityPriorStrength: Double = 10.0,
    val openWeight: Double = 1.0,
    val readWeight: Double = 2.0,
    val saveWeight: Double = 2.0,
    val likeWeight: Double = 3.0,
    val sliderRarelyCap: Double = 0.15,
    val sliderLessCap: Double = 0.35,
    val sliderMoreFloor: Double = 0.65,
    val sliderFavouriteValue: Double = 1.0,
    /** A feed is "loved" at or above this affinity (or with the slider at More or Favourite). */
    val lovedAffinity: Double = 0.7,

    // Read detection
    val readScrollFraction: Float = 0.7f,
    val readDwellFactor: Double = 0.5,

    // Cadence
    val cadenceSampleSize: Int = 30,
    val cadenceWindowDays: Int = 90,
    val cadenceMinSample: Int = 5,
    /** Firehose: more than this many posts a day. */
    val firehosePostsPerDay: Double = 8.0,
    /** Daily: at least this many posts a day. */
    val dailyPostsPerDay: Double = 1.0,
    /** Weekly: at least this many posts a week; below it is Rare. */
    val weeklyPostsPerWeek: Double = 1.0,

    // Edition structure at Standard length
    val frontPageSecondaries: Int = 4,
    val topStoriesPages: Int = 2,
    val topStoriesPerPage: Int = 8,
    val sectionStories: Int = 6,
    val briefLines: Int = 15,
    /** A held-over post may lead the front page at most this many times. */
    val maxLeadRuns: Int = 2,
    /** From the archive skips posts that ran in this many most recent editions. */
    val archiveCooldownEditions: Int = 3,
    /** The early build aims for this long before the reset time. */
    val earlyBuildLeadMinutes: Long = 90,
) {
    companion object {
        val Default = RankingConfig()
    }
}

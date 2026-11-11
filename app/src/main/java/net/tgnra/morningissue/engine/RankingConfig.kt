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
    /**
     * Runs and engagement older than this are not read when learning; after six half-lives
     * their weight is under 2%.
     */
    val affinityWindowDays: Long = 180,

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
    /** A tag (or General) needs at least this many stories on the day to get a section page. */
    val sectionMinStories: Int = 2,
    /** Unread posts a loved Weekly or Rare feed keeps eligible, newest first (v1 used 5). */
    val heldOverPerFeed: Int = 5,
    /**
     * A held-over post is reprinted on the regular pages only while it scores at least this;
     * below it (about three unopened runs for a fresh post of a loved feed) it waits for From
     * the archive instead of filling a thin page day after day.
     */
    val heldOverMinScore: Double = 0.2,
    /** A held-over post may lead the front page at most this many times. */
    val maxLeadRuns: Int = 2,
    /** From the archive skips posts that ran in this many most recent editions. */
    val archiveCooldownEditions: Int = 3,
    /** The early build aims for this long before the reset time. */
    val earlyBuildLeadMinutes: Long = 90,
    /**
     * The early build only builds the coming edition when its reset is at most this far away;
     * a job that ran late (after the reset it aimed for) settles for the current edition.
     */
    val earlyBuildMaxLeadMinutes: Long = 180,

    // Cadence fallback: the interval assumed for a feed whose median interval is not known yet,
    // by tier (v1's "typical median interval" column).
    val firehoseTypicalIntervalHours: Double = 0.5,
    val dailyTypicalIntervalHours: Double = 24.0,
    val weeklyTypicalIntervalHours: Double = 168.0,
    val rareTypicalIntervalHours: Double = 720.0,

    // Edition assembly
    /** In brief only takes posts scoring at least this. */
    val briefMinScore: Double = 0.1,
    /**
     * Top stories diversity: a story's score counts times this for every story from the same
     * feed already on the page (v1's greedy diversification). 1.0 switches it off.
     */
    val sameFeedPagePenalty: Double = 0.7,
    /** Short and Long editions scale the per-page limits by these factors. */
    val shortLengthScale: Double = 0.5,
    val longLengthScale: Double = 1.5,
    /** With no earlier edition, the first one covers posts first seen in this many days. */
    val firstEditionWindowDays: Long = 3,

    // Feed health (engine/FeedHealthRules.kt)
    /** Broken: fetch errors for this many days running (and at least [brokenMinErrors] failed fetches). */
    val brokenAfterDays: Long = 7,
    val brokenMinErrors: Int = 3,
    /** A failed repair of a broken feed is tried again after this many days, and no sooner. */
    val repairRetryDays: Long = 7,
    /** At most this many broken feeds are repaired in one health check. */
    val repairMaxPerRun: Int = 3,
    /** Gone quiet: no posts in this many days. */
    val quietAfterDays: Long = 90,
    /** How often a quiet feed is checked, by how long it has been quiet. Ascending. */
    val quietCheckSteps: List<QuietStep> = listOf(
        QuietStep(quietDays = 90, checkEveryHours = 6),
        QuietStep(quietDays = 180, checkEveryHours = 24),
        QuietStep(quietDays = 365, checkEveryHours = 72),
        QuietStep(quietDays = 730, checkEveryHours = 168),
    ),
    /** Ignored: printed in at least [ignoredMinEditions] editions over this window with no opens. */
    val ignoredWindowDays: Long = 60,
    val ignoredMinEditions: Int = 10,
    /** Noisy: at least [noisyPostsPerWeek] posts a week over this window, with under [noisyReadRate] opened. */
    val noisyWindowDays: Long = 28,
    val noisyPostsPerWeek: Double = 30.0,
    val noisyReadRate: Double = 0.02,
    /**
     * Loved (the flag that suggests Favourite): slider still Auto, learned affinity at least
     * [lovedFlagAffinity], at least [lovedFlagMinStories] stories printed over the window, of
     * which at least [lovedFlagReadRate] were read and at least [lovedFlagLikeRate] Liked.
     */
    val lovedFlagWindowDays: Long = 60,
    val lovedFlagAffinity: Double = 0.7,
    val lovedFlagMinStories: Int = 8,
    val lovedFlagReadRate: Double = 0.5,
    val lovedFlagLikeRate: Double = 0.1,
    /** A dismissed flag stays hidden this long (six months). */
    val flagDismissDays: Long = 183,

    // Storage budget
    /** Trimming brings stored text down to this share of the budget, so it does not run at every sync. */
    val storageTrimTarget: Double = 0.9,
    /** The newest editions (by cutoff) whose stories are never trimmed. */
    val storageKeepEditions: Int = 2,
) {
    companion object {
        val Default = RankingConfig()
    }
}

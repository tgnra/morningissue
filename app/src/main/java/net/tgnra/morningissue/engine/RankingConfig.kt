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
    /** From your reading list runs at most once in this many days. */
    val readingListIntervalDays: Long = 7,
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
    /** Gone quiet: no posts in this many days. A quiet feed is still fetched by every sync. */
    val quietAfterDays: Long = 90,
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

    // Cross-feed deduplication
    /**
     * Two posts of different feeds with the same normalised title are one story only when the
     * title has at least this many words and characters (so "Links" or "Weekly update" from two
     * blogs stay apart) and they were published within [dedupeTitleWindowHours] of each other.
     */
    val dedupeTitleMinWords: Int = 4,
    val dedupeTitleMinChars: Int = 20,
    val dedupeTitleWindowHours: Long = 72,

    // Welcome back
    /** A feed counts as having gone quiet after this many days without a post. */
    val comebackQuietDays: Long = 90,
    /**
     * The first post after such a silence scores times this, on top of scarcity. At 1.5 a
     * comeback from a neutral weekly feed scores 0.65 × 1.45 × 1.5 ≈ 1.41, level with a
     * brand-new post from a Favourite weekly feed (1.45) and above everything else, so with
     * five front-page slots it all but always makes the front page without being guaranteed
     * the lead. Not applied when the slider is below Auto.
     */
    val comebackBoost: Double = 1.5,

    // While you were away
    /** The pull-out stops after this many stories at Standard and Long; Short scales it down. */
    val awayMaxStories: Int = 30,
    /** A missed story needs at least this score, measured from the reader's last visit. */
    val awayMinScore: Double = 0.1,
    /** Only editions released in this many days before the build count as missed. */
    val awayWindowDays: Long = 30,
    /** A Firehose feed is let into the pull-out by a Like or a save within this many days. */
    val awayFirehoseEngagementDays: Long = 30,

    // Evening and Extra editions
    /** In an Extra edition each feed's newest posts are exempt from the age and freshness limits. */
    val extraNewestPerFeed: Int = 5,

    // W: within-feed ranking (engine/WithinFeed.kt). Unproven: every value below is a guess
    // to be tuned against exported labels, not a measurement.
    /** W never pulls a post's score below this share of what it was. */
    val withinFeedMinWeight: Double = 0.7,
    /** W never lifts a post's score above this multiple. */
    val withinFeedMaxWeight: Double = 1.4,
    /**
     * `ln W = gain × trust × lift`, with `lift` in "posts read above the feed's rate". At 1.0
     * a fully trusted signal worth a third of a read post more than its feed's average
     * reaches the upper cap (`ln 1.4 ≈ 0.34`), and the same below reaches the lower one.
     */
    val withinFeedGain: Double = 1.0,
    /**
     * Pseudo-exposures at the feed's own rate that a (feed, key) starts with; the same as
     * [affinityPriorStrength], kept apart so the two can be tuned separately.
     */
    val withinFeedKeyPriorStrength: Double = 10.0,
    /**
     * A feed's keys are trusted by `E / (E + this)`, `E` its decayed exposure: half strength
     * at a dozen recent labelled posts, two thirds at two dozen, three quarters at three.
     */
    val withinFeedFeedPriorPosts: Double = 12.0,
    /**
     * The text taste is trusted by `n / (n + this)`, `n` the decayed exposure of all labelled
     * posts that have a vector: half strength at 200 of them. An edition prints a few dozen
     * stories, so that is about a week of papers; a handful of labels moves nothing.
     */
    val withinFeedTextPriorPosts: Double = 200.0,
    /**
     * "Why is this here?" names the within-feed signal only from this W up, and then only
     * when it pulls harder than the feed's affinity and its scarcity.
     */
    val withinFeedReasonMinWeight: Double = 1.15,
    /**
     * Length buckets by word count: brief up to [lengthBriefMaxWords] (a note or a link with a
     * comment), short up to [lengthShortMaxWords] (about two minutes at 230 words a minute),
     * medium up to [lengthMediumMaxWords] (about six and a half), long above.
     */
    val lengthBriefMaxWords: Int = 150,
    val lengthShortMaxWords: Int = 500,
    val lengthMediumMaxWords: Int = 1500,
) {
    companion object {
        val Default = RankingConfig()
    }
}

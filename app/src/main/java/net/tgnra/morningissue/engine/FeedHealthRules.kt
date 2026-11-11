package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.SliderStep

/** A feed quiet for at least [quietDays] is checked every [checkEveryHours]. */
data class QuietStep(val quietDays: Long, val checkEveryHours: Long)

/** What a feed's stories did in the editions of a window: input to Ignored and Loved. */
data class FeedRunStats(
    /** Editions that printed at least one of the feed's stories. */
    val editions: Int = 0,
    /** Distinct stories printed. */
    val stories: Int = 0,
    /** Of [stories], those the reader opened, read, saved or Liked. */
    val opened: Int = 0,
    val read: Int = 0,
    val liked: Int = 0,
)

/** Everything the health rules know about one feed. */
data class FeedHealthInput(
    val sliderStep: SliderStep = SliderStep.AUTO,
    /** Learned affinity, before the slider. */
    val affinity: Double = 0.5,
    val addedAt: Instant,
    val lastSyncAt: Instant? = null,
    val lastPostAt: Instant? = null,
    val errorStreak: Int = 0,
    val errorSince: Instant? = null,
    val lastHttpStatus: Int? = null,
    val lastRepairAt: Instant? = null,
    /** The flag stored by the previous check (or raised by sync, for [HealthFlag.MOVED]). */
    val previousFlag: HealthFlag? = null,
    val dismissedUntil: Instant? = null,
    /** Over `RankingConfig.ignoredWindowDays`. */
    val ignoredRuns: FeedRunStats = FeedRunStats(),
    /** Over `RankingConfig.lovedFlagWindowDays`. */
    val lovedRuns: FeedRunStats = FeedRunStats(),
    /** Posts first seen over `RankingConfig.noisyWindowDays`, and how many of them were opened. */
    val recentPosts: Int = 0,
    val recentPostsOpened: Int = 0,
)

/** What to store for a feed after a check. */
data class HealthVerdict(val flag: HealthFlag?, val dismissedUntil: Instant?) {
    /** The flag the Subscriptions desk shows: none while it is dismissed. */
    fun visibleFlag(now: Instant): HealthFlag? = FeedHealthRules.visibleFlag(flag, dismissedUntil, now)
}

/**
 * The feed health check: which flag, if any, a feed carries on the Subscriptions desk. Pure;
 * `sync/FeedHealthChecker` gathers the input after a sync and stores the verdict. Nothing here
 * ever unsubscribes or changes a feed.
 */
object FeedHealthRules {
    /** Most urgent first: a feed carries one flag at a time. */
    val PRIORITY = listOf(
        HealthFlag.BROKEN, HealthFlag.MOVED, HealthFlag.NOISY, HealthFlag.IGNORED, HealthFlag.LOVED, HealthFlag.QUIET,
    )

    /** Fetch errors for seven days running, or the server says the feed is gone for good (410). */
    fun isFailing(input: FeedHealthInput, now: Instant, config: RankingConfig = RankingConfig.Default): Boolean {
        if (input.errorStreak <= 0) return false
        if (input.lastHttpStatus == 410) return true
        val since = input.errorSince ?: return false
        return input.errorStreak >= config.brokenMinErrors &&
            Duration.between(since, now) >= Duration.ofDays(config.brokenAfterDays)
    }

    /** Whether an automatic repair has been tried during the current run of errors. */
    private fun repairTried(input: FeedHealthInput): Boolean {
        val tried = input.lastRepairAt ?: return false
        return input.errorSince == null || !tried.isBefore(input.errorSince)
    }

    /**
     * A failing feed is repaired before the reader is asked: once when it starts failing, then
     * again every `repairRetryDays`.
     */
    fun repairDue(input: FeedHealthInput, now: Instant, config: RankingConfig = RankingConfig.Default): Boolean {
        if (!isFailing(input, now, config)) return false
        if (!repairTried(input)) return true
        return Duration.between(input.lastRepairAt, now) >= Duration.ofDays(config.repairRetryDays)
    }

    /** No posts in 90 days. A feed that has never been fetched is not quiet, it is unknown. */
    fun isQuiet(
        lastPostAt: Instant?,
        addedAt: Instant,
        lastSyncAt: Instant?,
        now: Instant,
        config: RankingConfig = RankingConfig.Default,
    ): Boolean {
        if (lastSyncAt == null) return false
        return quietFor(lastPostAt, addedAt, now) >= Duration.ofDays(config.quietAfterDays)
    }

    private fun quietFor(lastPostAt: Instant?, addedAt: Instant, now: Instant): Duration =
        Duration.between(lastPostAt ?: addedAt, now)

    /**
     * How long to leave a feed between checks: zero (every sync) until it has been quiet for
     * 90 days, then stepping down towards weekly. A new post ends the quiet and the wait.
     */
    fun checkInterval(quietFor: Duration, config: RankingConfig = RankingConfig.Default): Duration {
        val step = config.quietCheckSteps.lastOrNull { quietFor >= Duration.ofDays(it.quietDays) } ?: return Duration.ZERO
        return Duration.ofHours(step.checkEveryHours)
    }

    /** Whether a full sync should fetch the feed now. A feed never fetched is always due. */
    fun dueForCheck(
        lastSyncAt: Instant?,
        lastPostAt: Instant?,
        addedAt: Instant,
        now: Instant,
        config: RankingConfig = RankingConfig.Default,
    ): Boolean {
        if (lastSyncAt == null) return true
        val interval = checkInterval(quietFor(lastPostAt, addedAt, now), config)
        return Duration.between(lastSyncAt, now) >= interval
    }

    /** Every rule the feed meets, whatever has been dismissed. */
    fun flags(input: FeedHealthInput, now: Instant, config: RankingConfig = RankingConfig.Default): Set<HealthFlag> = buildSet {
        // Broken is only raised once the automatic repair has been tried and has not worked.
        if (isFailing(input, now, config) && repairTried(input)) add(HealthFlag.BROKEN)
        // Moved is an event recorded by sync or repair; it stands until the reader has seen it.
        if (input.previousFlag == HealthFlag.MOVED) add(HealthFlag.MOVED)
        if (isQuiet(input.lastPostAt, input.addedAt, input.lastSyncAt, now, config)) add(HealthFlag.QUIET)

        // "Show less" is the fix for these two; a feed already turned down needs no reminder.
        val turnedDown = input.sliderStep == SliderStep.LESS || input.sliderStep == SliderStep.RARELY
        if (!turnedDown && input.ignoredRuns.editions >= config.ignoredMinEditions && input.ignoredRuns.opened == 0) {
            add(HealthFlag.IGNORED)
        }
        val noisyWindow = Duration.ofDays(config.noisyWindowDays)
        val perWeek = input.recentPosts / (config.noisyWindowDays / 7.0)
        // A feed newer than the window arrived with its back catalogue; that is not its rate.
        if (!turnedDown && Duration.between(input.addedAt, now) >= noisyWindow && perWeek >= config.noisyPostsPerWeek &&
            input.recentPostsOpened.toDouble() / input.recentPosts < config.noisyReadRate
        ) {
            add(HealthFlag.NOISY)
        }

        val loved = input.lovedRuns
        if (input.sliderStep == SliderStep.AUTO && input.affinity >= config.lovedFlagAffinity &&
            loved.stories >= config.lovedFlagMinStories &&
            loved.read.toDouble() / loved.stories >= config.lovedFlagReadRate &&
            loved.liked.toDouble() / loved.stories >= config.lovedFlagLikeRate
        ) {
            add(HealthFlag.LOVED)
        }
    }

    /** The one flag the feed carries: the most urgent of [flags]. */
    fun flag(input: FeedHealthInput, now: Instant, config: RankingConfig = RankingConfig.Default): HealthFlag? {
        val all = flags(input, now, config)
        return PRIORITY.firstOrNull { it in all }
    }

    /**
     * The flag and dismissal to store. A dismissal hides the flag that was dismissed: while it
     * runs the stored flag is kept as the record of what was dismissed, and a different flag
     * arising in that time ends the dismissal and is shown.
     */
    fun evaluate(input: FeedHealthInput, now: Instant, config: RankingConfig = RankingConfig.Default): HealthVerdict {
        val current = flag(input, now, config)
        val dismissed = input.dismissedUntil?.isAfter(now) == true
        return when {
            // Accepting a Moved flag clears it, so one still stored has not been seen.
            current == HealthFlag.MOVED -> HealthVerdict(current, null)
            dismissed && (current == null || current == input.previousFlag) -> HealthVerdict(input.previousFlag, input.dismissedUntil)
            else -> HealthVerdict(current, null)
        }
    }

    fun visibleFlag(flag: HealthFlag?, dismissedUntil: Instant?, now: Instant): HealthFlag? =
        flag?.takeIf { dismissedUntil == null || !dismissedUntil.isAfter(now) }

    /** Until when a flag dismissed at [now] stays hidden. */
    fun dismissUntil(now: Instant, config: RankingConfig = RankingConfig.Default): Instant =
        now.plus(Duration.ofDays(config.flagDismissDays))

    /** Whether a flag asks the reader to do something. Gone quiet does not. */
    fun needsAttention(flag: HealthFlag): Boolean = flag != HealthFlag.QUIET
}

/**
 * "Once a month the edition carries the Subscriptions desk": a notice on the back page of at
 * most one edition per calendar month, and only while a feed needs attention.
 */
object DeskNoticeRule {
    /** The edition that carries the notice in [month]. */
    data class Claim(val month: YearMonth, val editionId: Long)

    enum class Decision {
        /** This edition already carries the month's notice. */
        SHOW,

        /** No edition carries it yet this month: this one takes it. Store the claim, then show. */
        CLAIM,
        HIDE,
    }

    /**
     * @param isLatestEdition whether this is the newest released edition; a back issue never
     *   takes the notice.
     * @param needingAttention feeds with an undismissed flag other than Gone quiet.
     */
    fun decide(
        editionId: Long,
        editionDate: LocalDate,
        isLatestEdition: Boolean,
        today: LocalDate,
        needingAttention: Int,
        claim: Claim?,
    ): Decision {
        if (needingAttention <= 0) return Decision.HIDE
        val month = YearMonth.from(today)
        if (claim?.month == month) return if (claim.editionId == editionId) Decision.SHOW else Decision.HIDE
        return if (isLatestEdition && YearMonth.from(editionDate) == month) Decision.CLAIM else Decision.HIDE
    }
}

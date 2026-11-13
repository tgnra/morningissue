package net.tgnra.morningissue.server.feeds

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.engine.FeedHealthInput
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.FeedRunStats
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.server.db.FeedDao
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.FeedPostStatsRow
import net.tgnra.morningissue.server.db.FeedRunStatsRow
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SubscriptionRow
import net.tgnra.morningissue.sync.FeedRepairer
import net.tgnra.morningissue.sync.RepairResult

/** What one health check did. */
data class HealthReport(
    /** (user, feed) pairs carrying a flag afterwards, dismissed ones included. */
    val flagged: Int = 0,
    /** Failing feeds an automatic repair was tried on. */
    val repairsTried: Int = 0,
    /** Of those, the ones that work again. */
    val repaired: Int = 0,
)

/**
 * The feed health check, ported from the app and split by section 2.3: repair works on the
 * shared feed (global `feed_status`), and the flag rules run per (user, feed) with the user's
 * slider, affinity, subscription date and engagement, storing each verdict in that user's
 * `feed_flag`. Broken, Moved and Gone quiet come from the global stats; Noisy, Ignored and
 * Loved from the user's engagement. A move shows as Moved to every subscriber until they
 * acknowledge it. Never unsubscribes anyone.
 */
class FeedHealthChecker(
    private val database: ServerDatabase,
    private val repairer: FeedRepairer,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val feedDao = database.feedDao()
    private val healthDao = database.healthDao()
    private val subscriptionDao = database.subscriptionDao()
    private val lock = Mutex()

    /**
     * Repairs failing feeds when [repair] is on (at most `repairMaxPerRun`, rate-limited per
     * feed), then evaluates the flags of [userIds], or of every user with a subscription. With
     * [repair] off nothing touches the network.
     */
    suspend fun run(repair: Boolean = true, userIds: Collection<Long>? = null): HealthReport = lock.withLock {
        val now = clock.instant()
        var tried = 0
        var repaired = 0
        if (repair) {
            val feedIds = feedDao.subscribedFeedIds()
            val statuses = feedIds.chunked(FeedDao.MAX_IDS).flatMap { healthDao.statusesOf(it) }.associateBy { it.feedId }
            for (feedId in feedIds) {
                if (tried >= config.repairMaxPerRun) break
                val status = statuses[feedId] ?: continue
                val feed = feedDao.getById(feedId) ?: continue
                val input = FeedHealthInput(
                    addedAt = feed.addedAt,
                    lastSyncAt = feed.lastSuccessAt,
                    lastPostAt = status.lastPostAt,
                    errorStreak = status.errorStreak,
                    errorSince = status.errorSince,
                    lastHttpStatus = status.lastHttpStatus,
                    lastRepairAt = status.lastRepairAt,
                )
                if (!FeedHealthRules.repairDue(input, now, config)) continue
                tried++
                if (repair(feed, now) != RepairResult.Failed) repaired++
            }
        }
        val users = userIds ?: subscriptionDao.subscribedPairs().map { it.userId }.distinct()
        val flagged = users.sumOf { evaluate(it, now) }
        HealthReport(flagged, tried, repaired)
    }

    /** Re-runs the rules over one user's stored data, without touching the network (opening the desk). */
    suspend fun runForUser(userId: Long): HealthReport = run(repair = false, userIds = listOf(userId))

    /** Repairs one feed now, whatever the rate limit says (the desk's Retry). */
    suspend fun repairNow(feedId: Long): RepairResult = lock.withLock {
        val feed = feedDao.getById(feedId) ?: return@withLock RepairResult.Failed
        repair(feed, clock.instant())
    }

    /** Evaluates and stores the flags of one user's subscribed feeds; returns how many carry one. */
    private suspend fun evaluate(userId: Long, now: Instant): Int {
        val rows = subscriptionDao.subscribedDetails(userId)
        if (rows.isEmpty()) return 0
        val ignored = healthDao.runStatsSince(userId, now.minus(Duration.ofDays(config.ignoredWindowDays)), now, config.pageSeenMinMillis)
            .associateBy { it.feedId }
        val loved = healthDao.runStatsSince(userId, now.minus(Duration.ofDays(config.lovedFlagWindowDays)), now, config.pageSeenMinMillis)
            .associateBy { it.feedId }
        val posts = healthDao.postStatsSince(userId, now.minus(Duration.ofDays(config.noisyWindowDays))).associateBy { it.feedId }
        var flagged = 0
        for (row in rows) {
            val feedId = row.feed.id
            val verdict = FeedHealthRules.evaluate(input(row, ignored[feedId], loved[feedId], posts[feedId]), now, config)
            if (verdict.flag != row.lastFlag || verdict.dismissedUntil != row.flagDismissedUntil) {
                healthDao.setVerdict(userId, feedId, verdict.flag, verdict.dismissedUntil)
            }
            if (verdict.flag != null) flagged++
        }
        return flagged
    }

    private suspend fun repair(feed: FeedEntity, now: Instant): RepairResult {
        // Every other feed's address is taken: `feed.url` is unique and shared by every user.
        val taken = feedDao.getUrls().filter { it.id != feed.id }.map { FeedUrls.normalize(it.url) }.toSet()
        val result = repairer.repair(feed.url, feed.siteUrl, taken)
        healthDao.recordRepair(feed.id, now)
        when (result) {
            RepairResult.Failed -> Unit
            // The error run is over; the next refresh fetches the feed as usual.
            RepairResult.Recovered -> healthDao.recordSuccess(feed.id, null, null)
            is RepairResult.Relocated -> {
                // As for a redirect: a change of scheme or "www." alone is not recorded as a move.
                DefaultFeedRefresher.relocate(database, feed, result.url, now)
                healthDao.recordSuccess(feed.id, null, null)
            }
        }
        return result
    }

    private fun input(row: SubscriptionRow, ignored: FeedRunStatsRow?, loved: FeedRunStatsRow?, posts: FeedPostStatsRow?) =
        FeedHealthInput(
            sliderStep = row.subscription.sliderStep,
            affinity = row.subscription.affinity,
            addedAt = row.subscription.addedAt,
            lastSyncAt = row.feed.lastSuccessAt,
            lastPostAt = row.lastPostAt,
            errorStreak = row.errorStreak,
            errorSince = row.errorSince,
            lastHttpStatus = row.lastHttpStatus,
            lastRepairAt = row.lastRepairAt,
            // A move this user has not acknowledged stands as Moved; one they have is gone.
            previousFlag = if (row.movePending) HealthFlag.MOVED else row.lastFlag?.takeUnless { it == HealthFlag.MOVED },
            dismissedUntil = row.flagDismissedUntil,
            ignoredRuns = ignored.toStats(),
            lovedRuns = loved.toStats(),
            recentPosts = posts?.posts ?: 0,
            recentPostsOpened = posts?.opened ?: 0,
        )

    private fun FeedRunStatsRow?.toStats() =
        if (this == null) FeedRunStats() else FeedRunStats(editions, stories, opened, read, liked)
}

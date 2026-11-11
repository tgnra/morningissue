package net.tgnra.morningissue.sync

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.FeedHealthEntity
import net.tgnra.morningissue.data.db.FeedPostStatsRow
import net.tgnra.morningissue.data.db.FeedRunStatsRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.FeedHealthInput
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.FeedRunStats
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.HealthFlag

/** What one health check did. */
data class HealthReport(
    /** Feeds carrying a flag afterwards, dismissed ones included. */
    val flagged: Int = 0,
    /** Failing feeds an automatic repair was tried on. */
    val repairsTried: Int = 0,
    /** Of those, the ones that work again. */
    val repaired: Int = 0,
)

/**
 * Runs the feed health rules (`engine/FeedHealthRules`) over every feed and stores each
 * verdict in `feed_health`. Runs after a full sync. A feed that has been failing for a week is
 * repaired here first ([FeedRepairer]) and only flagged Broken when that does not work.
 * Never unsubscribes.
 */
class FeedHealthChecker(
    private val database: MorningIssueDatabase,
    private val repairer: FeedRepairer,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val feedDao = database.feedDao()
    private val healthDao = database.feedHealthDao()
    private val lock = Mutex()

    /**
     * Checks every feed. With [repair] off nothing touches the network: failing feeds keep
     * whatever flag their last repair left them.
     */
    suspend fun run(repair: Boolean = true): HealthReport = lock.withLock {
        val now = clock.instant()
        val feeds = feedDao.getAll()
        val health = healthDao.getAll().associateBy { it.feedId }
        val ignored = healthDao.runStatsSince(now.minus(Duration.ofDays(config.ignoredWindowDays)), now).associateBy { it.feedId }
        val loved = healthDao.runStatsSince(now.minus(Duration.ofDays(config.lovedFlagWindowDays)), now).associateBy { it.feedId }
        val posts = healthDao.postStatsSince(now.minus(Duration.ofDays(config.noisyWindowDays))).associateBy { it.feedId }

        var flagged = 0
        var tried = 0
        var repaired = 0
        for (feed in feeds) {
            var row = health[feed.id] ?: FeedHealthEntity(feedId = feed.id)
            var input = input(feed, row, ignored[feed.id], loved[feed.id], posts[feed.id])
            if (repair && tried < config.repairMaxPerRun && FeedHealthRules.repairDue(input, now, config)) {
                tried++
                if (repair(feed, feeds, now) != RepairResult.Failed) repaired++
                row = healthDao.get(feed.id) ?: row
                input = input(feed, row, ignored[feed.id], loved[feed.id], posts[feed.id])
            }
            val verdict = FeedHealthRules.evaluate(input, now, config)
            if (verdict.flag != row.lastFlag || verdict.dismissedUntil != row.flagDismissedUntil) {
                healthDao.setVerdict(feed.id, verdict.flag, verdict.dismissedUntil)
            }
            if (verdict.flag != null) flagged++
        }
        HealthReport(flagged, tried, repaired)
    }

    /** Repairs one feed now, whatever the rate limit says (the desk's Retry). */
    suspend fun repairNow(feedId: Long): RepairResult = lock.withLock {
        val feed = feedDao.getById(feedId) ?: return@withLock RepairResult.Failed
        repair(feed, feedDao.getAll(), clock.instant())
    }

    private suspend fun repair(feed: FeedEntity, all: List<FeedEntity>, now: Instant): RepairResult {
        val taken = all.filter { it.id != feed.id }.map { FeedUrls.normalize(it.url) }.toSet()
        val result = repairer.repair(feed.url, feed.siteUrl, taken)
        healthDao.recordRepair(feed.id, now)
        when (result) {
            RepairResult.Failed -> Unit
            // The error run is over; the next sync fetches the feed as usual.
            RepairResult.Recovered -> healthDao.recordSuccess(feed.id, null, null)
            is RepairResult.Relocated -> {
                feedDao.updateUrl(feed.id, result.url)
                healthDao.recordSuccess(feed.id, null, null)
                // As for a redirect: a change of scheme or "www." alone is not worth a notice.
                if (FeedUrls.normalize(result.url) != FeedUrls.normalize(feed.url)) healthDao.setVerdict(feed.id, HealthFlag.MOVED, null)
            }
        }
        return result
    }

    private fun input(
        feed: FeedEntity,
        health: FeedHealthEntity,
        ignored: FeedRunStatsRow?,
        loved: FeedRunStatsRow?,
        posts: FeedPostStatsRow?,
    ) = FeedHealthInput(
        sliderStep = feed.sliderStep,
        affinity = feed.affinity,
        addedAt = feed.addedAt,
        lastSyncAt = feed.lastSyncAt,
        lastPostAt = health.lastPostAt,
        errorStreak = health.errorStreak,
        errorSince = health.errorSince,
        lastHttpStatus = health.lastHttpStatus,
        lastRepairAt = health.lastRepairAt,
        previousFlag = health.lastFlag,
        dismissedUntil = health.flagDismissedUntil,
        ignoredRuns = ignored.toStats(),
        lovedRuns = loved.toStats(),
        recentPosts = posts?.posts ?: 0,
        recentPostsOpened = posts?.opened ?: 0,
    )

    private fun FeedRunStatsRow?.toStats() =
        if (this == null) FeedRunStats() else FeedRunStats(editions, stories, opened, read, liked)
}

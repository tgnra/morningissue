package net.tgnra.morningissue.server.feeds

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SignalDao

/**
 * Global garbage collection (section 2.3), run daily from the scheduler's maintenance. Deletes
 * the posts of feeds nobody subscribes to any more (in either status), except posts some user's
 * edition placed or someone saved or Liked, and then every such feed left with no posts.
 */
class FeedGarbageCollector(private val database: ServerDatabase) {
    private val articleDao = database.articleDao()
    private val feedDao = database.feedDao()

    data class Result(val articles: Int, val feeds: Int)

    suspend fun run(): Result {
        val articles = articleDao.unreferencedIds()
        for (batch in articles.chunked(SignalDao.MAX_IDS)) {
            database.withWriteTransaction { articleDao.delete(batch) }
        }
        val feeds = database.withWriteTransaction {
            feedDao.orphanIds().onEach { feedDao.delete(it) }
        }
        return Result(articles.size, feeds.size)
    }
}

/**
 * Discovery's housekeeping, after the periodic refresh: a feed a user only looked into
 * (a DISCOVERED subscription) goes once [keepDays] have passed since it was discovered and the
 * user has saved nothing and Liked nothing from it, nor opened anything from it within those
 * days. Its posts then go with the next [FeedGarbageCollector] run, unless another user
 * subscribes to the feed.
 */
class DiscoveryCleanup(
    private val database: ServerDatabase,
    private val clock: Clock,
    private val keepDays: Long = KEEP_DAYS,
) {
    private val subscriptionDao = database.subscriptionDao()

    /** Returns how many discovered subscriptions were removed. */
    suspend fun run(): Int {
        val expired = subscriptionDao.expiredDiscovered(clock.instant().minus(Duration.ofDays(keepDays)))
        database.withWriteTransaction {
            for (row in expired) {
                subscriptionDao.deleteEngagementForFeed(row.userId, row.feedId)
                subscriptionDao.deleteFlag(row.userId, row.feedId)
                subscriptionDao.delete(row.userId, row.feedId)
            }
        }
        return expired.size
    }

    companion object {
        const val KEEP_DAYS = 7L
    }
}

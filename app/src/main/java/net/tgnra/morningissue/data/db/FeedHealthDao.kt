package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.HealthFlag

@Dao
abstract class FeedHealthDao {
    @Query("SELECT * FROM feed_health WHERE feedId = :feedId")
    abstract suspend fun get(feedId: Long): FeedHealthEntity?

    @Query("SELECT * FROM feed_health")
    abstract suspend fun getAll(): List<FeedHealthEntity>

    @Query("SELECT * FROM feed_health")
    abstract fun observeAll(): Flow<List<FeedHealthEntity>>

    @Upsert
    abstract suspend fun upsert(health: FeedHealthEntity)

    @Query("INSERT OR IGNORE INTO feed_health (feedId) VALUES (:feedId)")
    protected abstract suspend fun ensure(feedId: Long)

    @Query(
        "UPDATE feed_health SET errorStreak = 0, errorSince = NULL, lastHttpStatus = :httpStatus, " +
            "lastPostAt = COALESCE(:lastPostAt, lastPostAt) WHERE feedId = :feedId",
    )
    protected abstract suspend fun setSuccess(feedId: Long, httpStatus: Int?, lastPostAt: Instant?)

    @Query(
        "UPDATE feed_health SET errorStreak = errorStreak + 1, errorSince = COALESCE(errorSince, :at), " +
            "lastHttpStatus = :httpStatus WHERE feedId = :feedId",
    )
    protected abstract suspend fun setError(feedId: Long, at: Instant, httpStatus: Int?)

    @Query("UPDATE feed_health SET lastFlag = :flag WHERE feedId = :feedId")
    protected abstract suspend fun setFlagInternal(feedId: Long, flag: HealthFlag?)

    @Query("UPDATE feed_health SET flagDismissedUntil = :until WHERE feedId = :feedId")
    protected abstract suspend fun setDismissedUntil(feedId: Long, until: Instant?)

    @Query("UPDATE feed_health SET lastRepairAt = :at WHERE feedId = :feedId")
    protected abstract suspend fun setRepairAt(feedId: Long, at: Instant)

    @Query("UPDATE feed_health SET lastFlag = :flag, flagDismissedUntil = :dismissedUntil WHERE feedId = :feedId")
    protected abstract suspend fun setVerdictInternal(feedId: Long, flag: HealthFlag?, dismissedUntil: Instant?)

    /**
     * Per feed, what its printed (non-index) stories did in editions released in
     * `[since, now]`; input to the Ignored and Loved rules. Feeds with no printed story are absent.
     */
    @Query(
        "SELECT a.feedId AS feedId, COUNT(DISTINCT i.editionId) AS editions, COUNT(DISTINCT i.articleId) AS stories, " +
            "COUNT(DISTINCT CASE WHEN g.openedAt IS NOT NULL OR g.readAt IS NOT NULL OR g.savedAt IS NOT NULL " +
            "OR g.likedAt IS NOT NULL THEN i.articleId END) AS opened, " +
            "COUNT(DISTINCT CASE WHEN g.readAt IS NOT NULL THEN i.articleId END) AS read, " +
            "COUNT(DISTINCT CASE WHEN g.likedAt IS NOT NULL THEN i.articleId END) AS liked " +
            "FROM edition_item i JOIN edition e ON e.id = i.editionId JOIN article a ON a.id = i.articleId " +
            "JOIN feed f ON f.id = a.feedId LEFT JOIN engagement g ON g.articleId = i.articleId " +
            "WHERE i.kind != 'INDEX' AND e.releaseAt >= :since AND e.releaseAt <= :now AND f.status = 'SUBSCRIBED' GROUP BY a.feedId",
    )
    abstract suspend fun runStatsSince(since: Instant, now: Instant): List<FeedRunStatsRow>

    /** Per feed, the posts first seen since [since] and how many of them were opened; input to Noisy. */
    @Query(
        "SELECT a.feedId AS feedId, COUNT(*) AS posts, " +
            "COALESCE(SUM(CASE WHEN g.openedAt IS NOT NULL OR g.readAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS opened " +
            "FROM article a JOIN feed f ON f.id = a.feedId LEFT JOIN engagement g ON g.articleId = a.id " +
            "WHERE a.firstSeenAt >= :since AND f.status = 'SUBSCRIBED' GROUP BY a.feedId",
    )
    abstract suspend fun postStatsSince(since: Instant): List<FeedPostStatsRow>

    /** Records that an automatic repair was tried at [at]. */
    @Transaction
    open suspend fun recordRepair(feedId: Long, at: Instant) {
        ensure(feedId)
        setRepairAt(feedId, at)
    }

    /** Stores the health check's verdict: the flag and until when it is dismissed. */
    @Transaction
    open suspend fun setVerdict(feedId: Long, flag: HealthFlag?, dismissedUntil: Instant?) {
        ensure(feedId)
        setVerdictInternal(feedId, flag, dismissedUntil)
    }

    /** A fetch worked: clears the error run. [lastPostAt] null keeps the stored value. */
    @Transaction
    open suspend fun recordSuccess(feedId: Long, httpStatus: Int?, lastPostAt: Instant?) {
        ensure(feedId)
        setSuccess(feedId, httpStatus, lastPostAt)
    }

    /** A fetch failed at [at]; [httpStatus] is null when there was no response. */
    @Transaction
    open suspend fun recordError(feedId: Long, at: Instant, httpStatus: Int?) {
        ensure(feedId)
        setError(feedId, at, httpStatus)
    }

    @Transaction
    open suspend fun setFlag(feedId: Long, flag: HealthFlag?) {
        ensure(feedId)
        setFlagInternal(feedId, flag)
    }

    @Transaction
    open suspend fun dismissFlag(feedId: Long, until: Instant?) {
        ensure(feedId)
        setDismissedUntil(feedId, until)
    }
}

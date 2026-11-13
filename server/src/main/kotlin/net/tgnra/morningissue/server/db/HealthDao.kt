package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import net.tgnra.morningissue.model.HealthFlag

/**
 * Feed health: the global `feed_status` (what fetching found) and each user's `feed_flag` (the
 * flag the health check set, dismissals, a seen move). Owned by W-A (`docs/server-design.md`,
 * section 8).
 */
@Dao
abstract class HealthDao {
    // --- feed_status (global) ------------------------------------------------------------------

    @Query("SELECT * FROM feed_status WHERE feed_id = :feedId")
    abstract suspend fun getStatus(feedId: Long): FeedStatusEntity?

    @Query("SELECT * FROM feed_status WHERE feed_id IN (:feedIds)")
    abstract suspend fun statusesOf(feedIds: List<Long>): List<FeedStatusEntity>

    @Query("INSERT OR IGNORE INTO feed_status (feed_id) VALUES (:feedId)")
    protected abstract suspend fun ensureStatus(feedId: Long)

    @Query(
        "UPDATE feed_status SET error_streak = 0, error_since = NULL, last_http_status = :httpStatus, " +
            "last_post_at = COALESCE(:lastPostAt, last_post_at) WHERE feed_id = :feedId",
    )
    protected abstract suspend fun setSuccess(feedId: Long, httpStatus: Int?, lastPostAt: Instant?)

    @Query(
        "UPDATE feed_status SET error_streak = error_streak + 1, error_since = COALESCE(error_since, :at), " +
            "last_http_status = :httpStatus WHERE feed_id = :feedId",
    )
    protected abstract suspend fun setError(feedId: Long, at: Instant, httpStatus: Int?)

    @Query("UPDATE feed_status SET last_repair_at = :at WHERE feed_id = :feedId")
    protected abstract suspend fun setRepairAt(feedId: Long, at: Instant)

    @Query("UPDATE feed_status SET moved_from_url = :fromUrl, moved_at = :at WHERE feed_id = :feedId")
    protected abstract suspend fun setMoved(feedId: Long, fromUrl: String, at: Instant)

    /** A fetch worked: clears the error run. [lastPostAt] null keeps the stored value. */
    @Transaction
    open suspend fun recordSuccess(feedId: Long, httpStatus: Int?, lastPostAt: Instant?) {
        ensureStatus(feedId)
        setSuccess(feedId, httpStatus, lastPostAt)
    }

    /** A fetch failed at [at]; [httpStatus] is null when there was no response. */
    @Transaction
    open suspend fun recordError(feedId: Long, at: Instant, httpStatus: Int?) {
        ensureStatus(feedId)
        setError(feedId, at, httpStatus)
    }

    /** Records that an automatic repair was tried at [at]. */
    @Transaction
    open suspend fun recordRepair(feedId: Long, at: Instant) {
        ensureStatus(feedId)
        setRepairAt(feedId, at)
    }

    /** The feed's address changed from [fromUrl] at [at]: every subscriber sees Moved until they acknowledge it. */
    @Transaction
    open suspend fun recordMove(feedId: Long, fromUrl: String, at: Instant) {
        ensureStatus(feedId)
        setMoved(feedId, fromUrl, at)
    }

    // --- feed_flag (per user) ------------------------------------------------------------------

    @Query("SELECT * FROM feed_flag WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun getFlag(userId: Long, feedId: Long): FeedFlagEntity?

    @Query("SELECT * FROM feed_flag WHERE user_id = :userId")
    abstract suspend fun flagsOf(userId: Long): List<FeedFlagEntity>

    @Query("INSERT OR IGNORE INTO feed_flag (user_id, feed_id) VALUES (:userId, :feedId)")
    protected abstract suspend fun ensureFlag(userId: Long, feedId: Long)

    @Query("UPDATE feed_flag SET last_flag = :flag, flag_dismissed_until = :dismissedUntil WHERE user_id = :userId AND feed_id = :feedId")
    protected abstract suspend fun setVerdictInternal(userId: Long, feedId: Long, flag: HealthFlag?, dismissedUntil: Instant?)

    @Query("UPDATE feed_flag SET flag_dismissed_until = :until WHERE user_id = :userId AND feed_id = :feedId")
    protected abstract suspend fun setDismissedUntil(userId: Long, feedId: Long, until: Instant?)

    @Query(
        "UPDATE feed_flag SET moved_acknowledged_at = :at, last_flag = NULL, flag_dismissed_until = NULL " +
            "WHERE user_id = :userId AND feed_id = :feedId",
    )
    protected abstract suspend fun setAcknowledged(userId: Long, feedId: Long, at: Instant)

    /** Stores the health check's verdict for one user's feed: the flag and until when it is dismissed. */
    @Transaction
    open suspend fun setVerdict(userId: Long, feedId: Long, flag: HealthFlag?, dismissedUntil: Instant?) {
        ensureFlag(userId, feedId)
        setVerdictInternal(userId, feedId, flag, dismissedUntil)
    }

    @Transaction
    open suspend fun dismiss(userId: Long, feedId: Long, until: Instant?) {
        ensureFlag(userId, feedId)
        setDismissedUntil(userId, feedId, until)
    }

    /** The desk's OK: clears the user's flag, and a move up to [at] counts as seen. */
    @Transaction
    open suspend fun acknowledge(userId: Long, feedId: Long, at: Instant) {
        ensureFlag(userId, feedId)
        setAcknowledged(userId, feedId, at)
    }

    // --- Per-user evidence for Ignored, Loved and Noisy ----------------------------------------

    /**
     * Per subscribed feed of [userId], what its printed (non-index) stories did in the user's
     * editions released in `[since, now]`; input to the Ignored and Loved rules. Only runs the
     * reader saw (see [SEEN]) count towards `editions` and `stories`, plus every story they
     * touched wherever it was printed. Feeds with no printed story are absent.
     */
    @Query(
        "SELECT a.feedId AS feedId, COUNT(DISTINCT i.editionId) AS editions, COUNT(DISTINCT i.articleId) AS stories, " +
            "COUNT(DISTINCT CASE WHEN g.openedAt IS NOT NULL OR g.readAt IS NOT NULL OR g.savedAt IS NOT NULL " +
            "OR g.likedAt IS NOT NULL THEN i.articleId END) AS opened, " +
            "COUNT(DISTINCT CASE WHEN g.readAt IS NOT NULL THEN i.articleId END) AS read, " +
            "COUNT(DISTINCT CASE WHEN g.likedAt IS NOT NULL THEN i.articleId END) AS liked " +
            "FROM edition_item i JOIN edition e ON e.id = i.editionId JOIN article a ON a.id = i.articleId " +
            "JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "LEFT JOIN engagement g ON g.articleId = i.articleId AND g.user_id = :userId " +
            "WHERE e.user_id = :userId AND i.kind != 'INDEX' AND e.releaseAt >= :since AND e.releaseAt <= :now " +
            "AND s.status = 'SUBSCRIBED' " +
            "AND ($SEEN OR g.openedAt IS NOT NULL OR g.readAt IS NOT NULL OR g.savedAt IS NOT NULL OR g.likedAt IS NOT NULL) " +
            "GROUP BY a.feedId",
    )
    abstract suspend fun runStatsSince(userId: Long, since: Instant, now: Instant, seenMinMillis: Long): List<FeedRunStatsRow>

    /** Per subscribed feed of [userId], the posts first seen since [since] and how many the user opened; input to Noisy. */
    @Query(
        "SELECT a.feedId AS feedId, COUNT(*) AS posts, " +
            "COALESCE(SUM(CASE WHEN g.openedAt IS NOT NULL OR g.readAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS opened " +
            "FROM article a JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "LEFT JOIN engagement g ON g.articleId = a.id AND g.user_id = :userId " +
            "WHERE a.firstSeenAt >= :since AND s.status = 'SUBSCRIBED' GROUP BY a.feedId",
    )
    abstract suspend fun postStatsSince(userId: Long, since: Instant): List<FeedPostStatsRow>

    companion object {
        /**
         * A run counts as seen when its page was on screen for at least `:seenMinMillis` in all,
         * or the edition predates page views. Needs `edition e` and `edition_item i`; the same
         * rule as the app's `EditionDao.SEEN`.
         */
        const val SEEN =
            "(e.pageViewsTracked = 0 OR COALESCE((SELECT v.msVisible FROM edition_page_view v " +
                "WHERE v.editionId = i.editionId AND v.page = i.page), 0) >= :seenMinMillis)"
    }
}

/** See [HealthDao.runStatsSince]. */
data class FeedRunStatsRow(
    val feedId: Long,
    val editions: Int,
    val stories: Int,
    val opened: Int,
    val read: Int,
    val liked: Int,
)

/** See [HealthDao.postStatsSince]. */
data class FeedPostStatsRow(
    val feedId: Long,
    val posts: Int,
    val opened: Int,
)

/** A (user, feed) pair. */
data class UserFeedRow(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "feed_id") val feedId: Long,
)

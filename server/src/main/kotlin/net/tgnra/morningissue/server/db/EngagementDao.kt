package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.time.Instant
import net.tgnra.morningissue.engine.RankingConfig

/**
 * Per-user engagement, with `change_seq` for the engagement delta. Owned by W-B.
 *
 * The merge writers take the `seq` to stamp on a row they change and return whether they
 * changed it (the event merger allocates one `users.change_seq` per transaction). The
 * publisher's counters (`leadCount`, `editionsRunUnopened`) are not in the delta and leave
 * `change_seq` alone.
 */
@Dao
abstract class EngagementDao {
    @Query("SELECT * FROM engagement WHERE user_id = :userId AND articleId = :articleId")
    abstract suspend fun get(userId: Long, articleId: Long): EngagementEntity?

    @Upsert
    abstract suspend fun upsert(engagement: EngagementEntity)

    /** Creates an empty row if the user has none for the article. */
    @Query("INSERT OR IGNORE INTO engagement (user_id, articleId) VALUES (:userId, :articleId)")
    abstract suspend fun ensure(userId: Long, articleId: Long)

    /**
     * Whether the user "has" the article: it exists, and they subscribe to its feed (either
     * status), it ran in one of their editions, or they have a row for it. Events naming
     * anything else are skipped.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM article a WHERE a.id = :articleId AND (" +
            "EXISTS (SELECT 1 FROM subscription s WHERE s.user_id = :userId AND s.feed_id = a.feedId) " +
            "OR EXISTS (SELECT 1 FROM engagement g WHERE g.user_id = :userId AND g.articleId = a.id) " +
            "OR EXISTS (SELECT 1 FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.articleId = a.id)))",
    )
    abstract suspend fun hasArticle(userId: Long, articleId: Long): Boolean

    @Query(
        "UPDATE engagement SET openedAt = :at, change_seq = :seq WHERE user_id = :userId AND articleId = :articleId " +
            "AND (openedAt IS NULL OR openedAt > :at)",
    )
    protected abstract suspend fun lowerOpenedAt(userId: Long, articleId: Long, at: Instant, seq: Long): Int

    @Query(
        "UPDATE engagement SET readAt = :at, change_seq = :seq WHERE user_id = :userId AND articleId = :articleId " +
            "AND (readAt IS NULL OR readAt > :at)",
    )
    protected abstract suspend fun lowerReadAt(userId: Long, articleId: Long, at: Instant, seq: Long): Int

    @Query(
        "UPDATE engagement SET readFraction = :fraction, change_seq = :seq WHERE user_id = :userId AND articleId = :articleId " +
            "AND readFraction < :fraction",
    )
    protected abstract suspend fun raiseReadFraction(userId: Long, articleId: Long, fraction: Float, seq: Long): Int

    @Query(
        "UPDATE engagement SET savedAt = :at, change_seq = :seq WHERE user_id = :userId AND articleId = :articleId " +
            "AND savedAt IS NOT :at",
    )
    protected abstract suspend fun replaceSavedAt(userId: Long, articleId: Long, at: Instant?, seq: Long): Int

    @Query(
        "UPDATE engagement SET likedAt = :at, change_seq = :seq WHERE user_id = :userId AND articleId = :articleId " +
            "AND likedAt IS NOT :at",
    )
    protected abstract suspend fun replaceLikedAt(userId: Long, articleId: Long, at: Instant?, seq: Long): Int

    /** Keeps the earlier of the stored first open and [at]. True when the row changed. */
    @Transaction
    open suspend fun mergeOpened(userId: Long, articleId: Long, at: Instant, seq: Long): Boolean {
        ensure(userId, articleId)
        return lowerOpenedAt(userId, articleId, at, seq) > 0
    }

    /** Keeps the earlier of the stored read time and [at]. True when the row changed. */
    @Transaction
    open suspend fun mergeRead(userId: Long, articleId: Long, at: Instant, seq: Long): Boolean {
        ensure(userId, articleId)
        return lowerReadAt(userId, articleId, at, seq) > 0
    }

    /** Raises the furthest-read fraction (never lowers it). True when the row changed. */
    @Transaction
    open suspend fun recordReadFraction(userId: Long, articleId: Long, fraction: Float, seq: Long): Boolean {
        ensure(userId, articleId)
        return raiseReadFraction(userId, articleId, fraction.coerceIn(0f, 1f), seq) > 0
    }

    /** [at] null removes the save. True when the row changed. */
    @Transaction
    open suspend fun setSaved(userId: Long, articleId: Long, at: Instant?, seq: Long): Boolean {
        ensure(userId, articleId)
        return replaceSavedAt(userId, articleId, at, seq) > 0
    }

    /** [at] null removes the Like. True when the row changed. */
    @Transaction
    open suspend fun setLiked(userId: Long, articleId: Long, at: Instant?, seq: Long): Boolean {
        ensure(userId, articleId)
        return replaceLikedAt(userId, articleId, at, seq) > 0
    }

    @Query("UPDATE engagement SET leadCount = leadCount + 1 WHERE user_id = :userId AND articleId = :articleId")
    protected abstract suspend fun bumpLeadCount(userId: Long, articleId: Long)

    @Transaction
    open suspend fun incrementLeadCount(userId: Long, articleId: Long) {
        ensure(userId, articleId)
        bumpLeadCount(userId, articleId)
    }

    @Query(
        "INSERT OR IGNORE INTO engagement (user_id, articleId) SELECT :userId, i.articleId FROM edition_item i " +
            "JOIN edition e ON e.id = i.editionId WHERE e.user_id = :userId AND i.editionId = :editionId AND i.kind != 'INDEX'",
    )
    protected abstract suspend fun ensureForEdition(userId: Long, editionId: Long)

    @Query(
        "UPDATE engagement SET editionsRunUnopened = editionsRunUnopened + 1 WHERE user_id = :userId AND openedAt IS NULL " +
            "AND readAt IS NULL AND savedAt IS NULL AND likedAt IS NULL " +
            "AND articleId IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.editionId = :editionId AND i.kind != 'INDEX' AND ${EditionDao.SEEN})",
    )
    protected abstract suspend fun bumpUnopenedForEdition(userId: Long, editionId: Long, seenMinMillis: Long)

    /**
     * Adds one to `editionsRunUnopened` for every printed story of [editionId] that the user
     * saw and has not touched. Call once per edition, when it is superseded by the next build.
     */
    @Transaction
    open suspend fun countUnopenedRun(userId: Long, editionId: Long, seenMinMillis: Long = RankingConfig.Default.pageSeenMinMillis) {
        ensureForEdition(userId, editionId)
        bumpUnopenedForEdition(userId, editionId, seenMinMillis)
    }

    /** Rows changed after [since], for the engagement delta. */
    @Query("SELECT * FROM engagement WHERE user_id = :userId AND change_seq > :since ORDER BY change_seq, articleId")
    abstract suspend fun changedSince(userId: Long, since: Long): List<EngagementEntity>
}

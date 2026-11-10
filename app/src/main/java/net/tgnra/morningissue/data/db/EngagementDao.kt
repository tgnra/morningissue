package net.tgnra.morningissue.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EngagementDao {
    @Query("SELECT * FROM engagement WHERE articleId = :articleId")
    abstract suspend fun get(articleId: Long): EngagementEntity?

    @Query("SELECT * FROM engagement WHERE articleId = :articleId")
    abstract fun observe(articleId: Long): Flow<EngagementEntity?>

    /** Creates an empty row if the article has none. */
    @Query("INSERT OR IGNORE INTO engagement (articleId) VALUES (:articleId)")
    abstract suspend fun ensure(articleId: Long)

    @Query("UPDATE engagement SET openedAt = COALESCE(openedAt, :at) WHERE articleId = :articleId")
    protected abstract suspend fun setOpenedAt(articleId: Long, at: Instant)

    @Query("UPDATE engagement SET readFraction = MAX(readFraction, :fraction) WHERE articleId = :articleId")
    protected abstract suspend fun raiseReadFraction(articleId: Long, fraction: Float)

    @Query("UPDATE engagement SET readAt = COALESCE(readAt, :at) WHERE articleId = :articleId")
    protected abstract suspend fun setReadAt(articleId: Long, at: Instant)

    @Query("UPDATE engagement SET savedAt = :at WHERE articleId = :articleId")
    protected abstract suspend fun setSavedAt(articleId: Long, at: Instant?)

    @Query("UPDATE engagement SET likedAt = :at WHERE articleId = :articleId")
    protected abstract suspend fun setLikedAt(articleId: Long, at: Instant?)

    @Query("UPDATE engagement SET leadCount = leadCount + 1 WHERE articleId = :articleId")
    protected abstract suspend fun bumpLeadCount(articleId: Long)

    @Query("INSERT OR IGNORE INTO engagement (articleId) SELECT articleId FROM edition_item WHERE editionId = :editionId AND kind != 'INDEX'")
    protected abstract suspend fun ensureForEdition(editionId: Long)

    @Query(
        "UPDATE engagement SET editionsRunUnopened = editionsRunUnopened + 1 WHERE openedAt IS NULL " +
            "AND articleId IN (SELECT articleId FROM edition_item WHERE editionId = :editionId AND kind != 'INDEX')",
    )
    protected abstract suspend fun bumpUnopenedForEdition(editionId: Long)

    /** Records the first open; later calls keep the original time. */
    @Transaction
    open suspend fun markOpened(articleId: Long, at: Instant) {
        ensure(articleId)
        setOpenedAt(articleId, at)
    }

    /** Raises the furthest-read fraction (never lowers it). */
    @Transaction
    open suspend fun recordReadFraction(articleId: Long, fraction: Float) {
        ensure(articleId)
        raiseReadFraction(articleId, fraction.coerceIn(0f, 1f))
    }

    /** Marks the article read; later calls keep the original time. */
    @Transaction
    open suspend fun markRead(articleId: Long, at: Instant) {
        ensure(articleId)
        setReadAt(articleId, at)
    }

    /** [at] null removes the save. */
    @Transaction
    open suspend fun setSaved(articleId: Long, at: Instant?) {
        ensure(articleId)
        setSavedAt(articleId, at)
    }

    /** [at] null removes the Like. */
    @Transaction
    open suspend fun setLiked(articleId: Long, at: Instant?) {
        ensure(articleId)
        setLikedAt(articleId, at)
    }

    @Transaction
    open suspend fun incrementLeadCount(articleId: Long) {
        ensure(articleId)
        bumpLeadCount(articleId)
    }

    /**
     * Adds one to `editionsRunUnopened` for every printed (non-index) story of [editionId] that
     * has never been opened. Call once per edition, when it is superseded by the next build.
     */
    @Transaction
    open suspend fun countUnopenedRun(editionId: Long) {
        ensureForEdition(editionId)
        bumpUnopenedForEdition(editionId)
    }
}

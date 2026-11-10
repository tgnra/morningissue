package net.tgnra.morningissue.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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

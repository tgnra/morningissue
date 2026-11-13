package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** The reading events waiting for the server; see [OutboxEventEntity] and `client.Outbox`. */
@Dao
interface OutboxDao {
    /** Returns the row's `seq`: the one given, or the next AUTOINCREMENT value when it is 0. */
    @Insert
    suspend fun insert(event: OutboxEventEntity): Long

    /** The oldest [limit] events, in `seq` order. */
    @Query("SELECT * FROM outbox_event ORDER BY seq LIMIT :limit")
    suspend fun oldest(limit: Int): List<OutboxEventEntity>

    /** Every event, in `seq` order; for re-applying pending changes over the server's state. */
    @Query("SELECT * FROM outbox_event ORDER BY seq")
    suspend fun all(): List<OutboxEventEntity>

    @Query("SELECT MAX(seq) FROM outbox_event")
    suspend fun maxSeq(): Long?

    @Query("SELECT COUNT(*) FROM outbox_event")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox_event")
    fun observeCount(): Flow<Int>

    /** Drops what the server has acknowledged. */
    @Query("DELETE FROM outbox_event WHERE seq <= :acked")
    suspend fun deleteThrough(acked: Long): Int
}

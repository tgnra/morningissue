package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Paired Kobos (`sync_peer`); see `kobo/` and `docs/kobo-sync.md`. */
@Dao
abstract class SyncPeerDao {
    @Query("SELECT * FROM sync_peer WHERE deviceId = :deviceId")
    abstract suspend fun get(deviceId: String): SyncPeerEntity?

    /** Every paired device. There are only ever a few, so the token check reads them all. */
    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    abstract suspend fun all(): List<SyncPeerEntity>

    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    abstract fun observeAll(): Flow<List<SyncPeerEntity>>

    @Query("INSERT OR IGNORE INTO sync_peer (deviceId, name, tokenHash, ackedSeq, pairedAt) VALUES (:deviceId, :name, :tokenHash, 0, :at)")
    protected abstract suspend fun insertIgnore(deviceId: String, name: String, tokenHash: String, at: Instant)

    @Query("UPDATE sync_peer SET name = :name, tokenHash = :tokenHash, pairedAt = :at WHERE deviceId = :deviceId")
    protected abstract suspend fun replaceToken(deviceId: String, name: String, tokenHash: String, at: Instant)

    /**
     * Records a pairing. Pairing a device again replaces its name, token and pairing time but
     * keeps its `ackedSeq`: the Kobo's `seq` only goes up, so an event merged before is never
     * merged twice.
     */
    @Transaction
    open suspend fun pair(deviceId: String, name: String, tokenHash: String, at: Instant) {
        insertIgnore(deviceId, name, tokenHash, at)
        replaceToken(deviceId, name, tokenHash, at)
    }

    @Query("UPDATE sync_peer SET ackedSeq = :ackedSeq, lastSyncAt = :at WHERE deviceId = :deviceId")
    abstract suspend fun setAcked(deviceId: String, ackedSeq: Long, at: Instant)

    @Query("UPDATE sync_peer SET lastSyncAt = :at WHERE deviceId = :deviceId")
    abstract suspend fun setLastSync(deviceId: String, at: Instant)

    @Query("DELETE FROM sync_peer WHERE deviceId = :deviceId")
    abstract suspend fun forget(deviceId: String)

    /** Whether the article exists, so that an event naming one the phone no longer has is skipped. */
    @Query("SELECT EXISTS(SELECT 1 FROM article WHERE id = :articleId)")
    abstract suspend fun articleExists(articleId: Long): Boolean
}

package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Paired Kobos (`sync_peer`), over the local network or a relay; see `kobo/`, `relay/`, `docs/kobo-sync.md` and `docs/relay-sync.md`. */
@Dao
abstract class SyncPeerDao {
    @Query("SELECT * FROM sync_peer WHERE deviceId = :deviceId")
    abstract suspend fun get(deviceId: String): SyncPeerEntity?

    /** Every paired device. There are only ever a few, so the token check reads them all. */
    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    abstract suspend fun all(): List<SyncPeerEntity>

    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    abstract fun observeAll(): Flow<List<SyncPeerEntity>>

    /** Devices paired for local-network sync. */
    @Query("SELECT * FROM sync_peer WHERE tokenHash IS NOT NULL ORDER BY pairedAt")
    abstract fun observeLan(): Flow<List<SyncPeerEntity>>

    /** Devices paired for relay sync. */
    @Query("SELECT * FROM sync_peer WHERE relayAuthKey IS NOT NULL AND relayEncKey IS NOT NULL ORDER BY relayPairedAt")
    abstract fun observeRelay(): Flow<List<SyncPeerEntity>>

    @Query("SELECT * FROM sync_peer WHERE relayAuthKey IS NOT NULL AND relayEncKey IS NOT NULL ORDER BY relayPairedAt")
    abstract suspend fun relayPeers(): List<SyncPeerEntity>

    @Query("INSERT OR IGNORE INTO sync_peer (deviceId, name, tokenHash, ackedSeq, pairedAt) VALUES (:deviceId, :name, :tokenHash, 0, :at)")
    protected abstract suspend fun insertIgnore(deviceId: String, name: String, tokenHash: String?, at: Instant)

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

    @Query("UPDATE sync_peer SET name = :name, relayWidth = :width, relayAuthKey = :authKey, relayEncKey = :encKey, relayPairedAt = :at WHERE deviceId = :deviceId")
    protected abstract suspend fun replaceRelayKeys(deviceId: String, name: String, width: Int, authKey: String, encKey: String, at: Instant)

    /**
     * Records a relay pairing: [authKey] and [encKey] wrapped, in base64url. Scanning the same
     * device again replaces its name, width and keys and keeps its `ackedSeq`, and a device
     * already paired over the local network keeps that pairing too.
     */
    @Transaction
    open suspend fun pairRelay(deviceId: String, name: String, width: Int, authKey: String, encKey: String, at: Instant) {
        insertIgnore(deviceId, name, null, at)
        replaceRelayKeys(deviceId, name, width, authKey, encKey, at)
    }

    @Query("UPDATE sync_peer SET tokenHash = NULL WHERE deviceId = :deviceId")
    protected abstract suspend fun clearToken(deviceId: String)

    @Query("UPDATE sync_peer SET relayWidth = NULL, relayAuthKey = NULL, relayEncKey = NULL, relayPairedAt = NULL WHERE deviceId = :deviceId")
    protected abstract suspend fun clearRelay(deviceId: String)

    /** Drops the row of [deviceId] once it is paired neither way. */
    @Query("DELETE FROM sync_peer WHERE deviceId = :deviceId AND tokenHash IS NULL AND relayAuthKey IS NULL")
    protected abstract suspend fun deleteIfUnpaired(deviceId: String)

    /** Forgets the local-network pairing of [deviceId]; a relay pairing stays. */
    @Transaction
    open suspend fun forget(deviceId: String) {
        clearToken(deviceId)
        deleteIfUnpaired(deviceId)
    }

    /** Forgets the relay pairing of [deviceId]; a local-network pairing stays. */
    @Transaction
    open suspend fun forgetRelay(deviceId: String) {
        clearRelay(deviceId)
        deleteIfUnpaired(deviceId)
    }

    /** Whether the article exists, so that an event naming one the phone no longer has is skipped. */
    @Query("SELECT EXISTS(SELECT 1 FROM article WHERE id = :articleId)")
    abstract suspend fun articleExists(articleId: Long): Boolean
}

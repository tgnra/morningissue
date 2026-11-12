package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Paired Kobos (`sync_peer`); see `relay/` and `docs/relay-sync.md`. */
@Dao
abstract class SyncPeerDao {
    @Query("SELECT * FROM sync_peer WHERE deviceId = :deviceId")
    abstract suspend fun get(deviceId: String): SyncPeerEntity?

    /** Every row, those left from local-network pairing included. */
    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    abstract suspend fun all(): List<SyncPeerEntity>

    /** Devices paired for relay sync: every Kobo the app syncs with. */
    @Query("SELECT * FROM sync_peer WHERE relayAuthKey IS NOT NULL AND relayEncKey IS NOT NULL ORDER BY relayPairedAt")
    abstract fun observeRelay(): Flow<List<SyncPeerEntity>>

    @Query("SELECT * FROM sync_peer WHERE relayAuthKey IS NOT NULL AND relayEncKey IS NOT NULL ORDER BY relayPairedAt")
    abstract suspend fun relayPeers(): List<SyncPeerEntity>

    @Query("INSERT OR IGNORE INTO sync_peer (deviceId, name, tokenHash, ackedSeq, pairedAt) VALUES (:deviceId, :name, NULL, 0, :at)")
    protected abstract suspend fun insertIgnore(deviceId: String, name: String, at: Instant)

    @Query("UPDATE sync_peer SET ackedSeq = :ackedSeq, lastSyncAt = :at WHERE deviceId = :deviceId")
    abstract suspend fun setAcked(deviceId: String, ackedSeq: Long, at: Instant)

    @Query("UPDATE sync_peer SET name = :name, relayWidth = :width, relayAuthKey = :authKey, relayEncKey = :encKey, relayPairedAt = :at WHERE deviceId = :deviceId")
    protected abstract suspend fun replaceRelayKeys(deviceId: String, name: String, width: Int, authKey: String, encKey: String, at: Instant)

    /**
     * Records a relay pairing: [authKey] and [encKey] wrapped, in base64url. Scanning the same
     * device again replaces its name, width and keys and keeps its `ackedSeq`. So does scanning
     * a Kobo that an earlier version paired over the local network: its row is kept for that,
     * though its token is no longer checked by anything.
     */
    @Transaction
    open suspend fun pairRelay(deviceId: String, name: String, width: Int, authKey: String, encKey: String, at: Instant) {
        insertIgnore(deviceId, name, at)
        replaceRelayKeys(deviceId, name, width, authKey, encKey, at)
    }

    /** Forgets [deviceId]: its row goes, with its `ackedSeq` and any token left from local-network pairing. */
    @Query("DELETE FROM sync_peer WHERE deviceId = :deviceId")
    abstract suspend fun forget(deviceId: String)

    /** Whether the article exists, so that an event naming one the phone no longer has is skipped. */
    @Query("SELECT EXISTS(SELECT 1 FROM article WHERE id = :articleId)")
    abstract suspend fun articleExists(articleId: Long): Boolean
}

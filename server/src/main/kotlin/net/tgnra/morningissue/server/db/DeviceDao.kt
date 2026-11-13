package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import java.time.Instant
import net.tgnra.morningissue.api.DeviceKind

/** A device row with what authentication needs of its user. */
data class DeviceWithUser(
    val id: String,
    val userId: Long,
    val kind: DeviceKind,
    val revokedAt: Instant?,
    val mustChangePassword: Boolean,
    val disabled: Boolean,
)

/**
 * Devices and their token hashes, `acked_seq` and last-seen times. Owned by W-C
 * (`docs/server-design.md`, section 8).
 *
 * Device IDs (the clients' own UUIDs) and token hashes are unique across users, so [byTokenHash],
 * [byId] and [touch] are looked up by them alone; every other query names the user.
 */
@Dao
interface DeviceDao {
    /** The device holding the token with hash [tokenHash], with its user's flags. */
    @Query(
        """SELECT d.id AS id, d.user_id AS userId, d.kind AS kind, d.revoked_at AS revokedAt,
                  u.must_change_password AS mustChangePassword, u.disabled AS disabled
           FROM device d JOIN users u ON u.id = d.user_id
           WHERE d.token_hash = :tokenHash""",
    )
    suspend fun byTokenHash(tokenHash: String): DeviceWithUser?

    /** A re-login or re-pairing finds the existing row by the client's ID, whichever user holds it. */
    @Query("SELECT * FROM device WHERE id = :deviceId")
    suspend fun byId(deviceId: String): DeviceEntity?

    @Query("SELECT * FROM device WHERE user_id = :userId AND id = :deviceId")
    suspend fun device(userId: Long, deviceId: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    /** The user's devices that still hold a token, oldest first. */
    @Query("SELECT * FROM device WHERE user_id = :userId AND revoked_at IS NULL ORDER BY created_at, id")
    suspend fun activeDevices(userId: Long): List<DeviceEntity>

    @Query("UPDATE device SET revoked_at = :at WHERE user_id = :userId AND id = :deviceId AND revoked_at IS NULL")
    suspend fun revoke(userId: Long, deviceId: String, at: Instant): Int

    /** A password reset signs every phone out; Kobos keep their tokens. */
    @Query("UPDATE device SET revoked_at = :at WHERE user_id = :userId AND kind = 'PHONE' AND revoked_at IS NULL")
    suspend fun revokePhones(userId: Long, at: Instant): Int

    @Query("UPDATE device SET last_seen_at = :at WHERE id = :deviceId")
    suspend fun touch(deviceId: String, at: Instant)
}

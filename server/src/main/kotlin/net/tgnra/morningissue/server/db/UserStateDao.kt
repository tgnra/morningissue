package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * Small per-user state: `user_state` records by key, the `user_settings` row, the account's
 * `db_id` and `change_seq` counter, and the devices' `acked_seq`. Owned by W-B; the `users`,
 * `user_settings` and `device` tables are W-C's, and only these columns are touched here.
 */
@Dao
abstract class UserStateDao {
    @Query("SELECT json FROM user_state WHERE user_id = :userId AND `key` = :key")
    abstract suspend fun get(userId: Long, key: String): String?

    @Upsert
    abstract suspend fun put(state: UserStateEntity)

    @Query("SELECT * FROM user_settings WHERE user_id = :userId")
    abstract suspend fun settings(userId: Long): UserSettingsEntity?

    @Upsert
    abstract suspend fun putSettings(settings: UserSettingsEntity)

    /** `users.db_id`, the manifest's `dbId`; `/kobo/hello` may read it here too. */
    @Query("SELECT db_id FROM users WHERE id = :userId")
    abstract suspend fun dbId(userId: Long): String?

    @Query("SELECT change_seq FROM users WHERE id = :userId")
    abstract suspend fun changeSeq(userId: Long): Long

    @Query("UPDATE users SET change_seq = change_seq + 1 WHERE id = :userId")
    protected abstract suspend fun bumpChangeSeq(userId: Long)

    /** `++users.change_seq`: the stamp for rows changed in the caller's transaction. */
    @Transaction
    open suspend fun nextChangeSeq(userId: Long): Long {
        bumpChangeSeq(userId)
        return changeSeq(userId)
    }

    /** The scheduler's user scan: every account that is not disabled. */
    @Query("SELECT id FROM users WHERE disabled = 0 ORDER BY id")
    abstract suspend fun enabledUserIds(): List<Long>

    /** A device's `acked_seq`, or null when [deviceId] is not the user's or is revoked. */
    @Query("SELECT acked_seq FROM device WHERE id = :deviceId AND user_id = :userId AND revoked_at IS NULL")
    abstract suspend fun ackedSeq(userId: Long, deviceId: String): Long?

    @Query("UPDATE device SET acked_seq = :acked WHERE id = :deviceId AND user_id = :userId")
    abstract suspend fun setAckedSeq(userId: Long, deviceId: String, acked: Long)

    /** The picture widths of the user's paired Kobos. */
    @Query(
        "SELECT DISTINCT width FROM device WHERE user_id = :userId AND kind = 'KOBO' AND revoked_at IS NULL " +
            "AND width IS NOT NULL ORDER BY width",
    )
    abstract suspend fun koboWidths(userId: Long): List<Int>
}

package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/** Users and `user_settings`. Owned by W-C (`docs/server-design.md`, section 8). */
@Dao
interface AccountDao {
    @Insert
    suspend fun insertUser(user: UserEntity): Long

    @Query("SELECT * FROM users WHERE username = :username")
    suspend fun userByName(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun user(userId: Long): UserEntity?

    @Query("SELECT * FROM users ORDER BY id")
    suspend fun allUsers(): List<UserEntity>

    /** The scheduler's user scan. */
    @Query("SELECT * FROM users WHERE disabled = 0 ORDER BY id")
    suspend fun enabledUsers(): List<UserEntity>

    @Query("UPDATE users SET disabled = :disabled WHERE id = :userId")
    suspend fun setDisabled(userId: Long, disabled: Boolean)

    @Query("UPDATE users SET password_hash = :hash, must_change_password = :mustChange WHERE id = :userId")
    suspend fun setPassword(userId: Long, hash: String, mustChange: Boolean)

    /** `GET /account`'s `hasData`: at least one subscription or edition. */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM subscription WHERE user_id = :userId)
                OR EXISTS(SELECT 1 FROM edition WHERE user_id = :userId)""",
    )
    suspend fun hasData(userId: Long): Boolean

    /** `GET /account`'s `settingsCustomized`; null when the user has no settings row yet. */
    @Query("SELECT customized FROM user_settings WHERE user_id = :userId")
    suspend fun settingsCustomized(userId: Long): Boolean?
}

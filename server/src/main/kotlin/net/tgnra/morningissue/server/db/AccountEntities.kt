package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import net.tgnra.morningissue.api.DeviceKind

// Accounts, devices and per-user state (owned by W-C; `user_state` rows are written by W-B).

/** An account. Users come from the config file; a name removed from it is [disabled], its data kept. */
@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    /** PBKDF2-HMAC-SHA256, with its salt and iteration count, in the auth package's format. */
    @ColumnInfo(name = "password_hash") val passwordHash: String,
    /** True until the generated first password is changed. */
    @ColumnInfo(name = "must_change_password", defaultValue = "1") val mustChangePassword: Boolean = true,
    @ColumnInfo(defaultValue = "0") val disabled: Boolean = false,
    /** A random UUID, the manifest's `dbId`; a new one tells clients the account's data was reset. */
    @ColumnInfo(name = "db_id") val dbId: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    /** The user's counter for engagement and edition deltas: every changed row takes `++change_seq`. */
    @ColumnInfo(name = "change_seq", defaultValue = "0") val changeSeq: Long = 0,
)

/** A phone or Kobo with a bearer token. Replaces the app's `sync_peer`. */
@Entity(
    tableName = "device",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["token_hash"], unique = true),
        Index("user_id"),
    ],
)
data class DeviceEntity(
    /** The client's own UUID (a phone's install ID, a Kobo's `d`). */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    val kind: DeviceKind,
    val name: String,
    /** A Kobo's picture width, 160..2048; null for a phone. */
    val width: Int? = null,
    /** Hex SHA-256 of the bearer token; the token itself is never stored. */
    @ColumnInfo(name = "token_hash") val tokenHash: String,
    /** The highest event `seq` from this device that has been merged. */
    @ColumnInfo(name = "acked_seq", defaultValue = "0") val ackedSeq: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    /** Updated at most once a minute. */
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Instant? = null,
    /** Set when the token was revoked (log out, Forget, a password reset). */
    @ColumnInfo(name = "revoked_at") val revokedAt: Instant? = null,
)

/** The user's [net.tgnra.morningissue.api.ServerSettings] and the server-only fields, as JSON. */
@Entity(
    tableName = "user_settings",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: Long,
    /** `ServerSettings` with `nothingNewFor`, `resetChange` and `eveningChange`. */
    val json: String,
    /** False until the first PATCH or an import applied the phone's settings. */
    @ColumnInfo(defaultValue = "0") val customized: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/** Small per-user records by key: the desk notice's month, the discovery deck, the last build outcome. */
@Entity(
    tableName = "user_state",
    primaryKeys = ["user_id", "key"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserStateEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    val key: String,
    val json: String,
)

/** A finished legacy import, by its `Idempotency-Key`, so a retry gets the report and does nothing. */
@Entity(
    tableName = "import_log",
    primaryKeys = ["user_id", "idempotency_key"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportLogEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    /** An `ImportReportDto` as JSON. */
    @ColumnInfo(name = "report_json") val reportJson: String,
    @ColumnInfo(name = "completed_at") val completedAt: Instant,
)

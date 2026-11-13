package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.SliderStep

// Per-user feed tables (owned by W-A). Every query over them names `user_id`.

/** A user's subscription to a shared feed: the per-user half of the app's `feed`. */
@Entity(
    tableName = "subscription",
    primaryKeys = ["user_id", "feed_id"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feed_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("feed_id")],
)
data class SubscriptionEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    /** The user's own name for the feed; the feed's title when null. */
    @ColumnInfo(name = "title_override") val titleOverride: String? = null,
    @ColumnInfo(name = "slider_step", defaultValue = "AUTO") val sliderStep: SliderStep = SliderStep.AUTO,
    @ColumnInfo(name = "mute_until") val muteUntil: Instant? = null,
    /** Learned affinity, 0..1, cached for the UI; the learner rewrites it. */
    @ColumnInfo(defaultValue = "0.5") val affinity: Double = 0.5,
    /** SUBSCRIBED, or DISCOVERED for a feed that only holds posts opened or saved from discovery. */
    @ColumnInfo(defaultValue = "SUBSCRIBED") val status: FeedStatus = FeedStatus.SUBSCRIBED,
    @ColumnInfo(name = "added_at") val addedAt: Instant,
    @ColumnInfo(name = "discovered_at") val discoveredAt: Instant? = null,
    @ColumnInfo(defaultValue = "0") val notify: Boolean = false,
)

/** A user's tag (section), with its learned affinity. */
@Entity(
    tableName = "tag",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["user_id", "name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    val name: String,
    @ColumnInfo(defaultValue = "0.5") val affinity: Double = 0.5,
)

/** A tag on a subscription; the app's `feed_tag`. */
@Entity(
    tableName = "subscription_tag",
    primaryKeys = ["user_id", "feed_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["user_id", "feed_id"],
            childColumns = ["user_id", "feed_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag_id")],
)
data class SubscriptionTagEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

/** The per-user half of the app's `feed_health`: the flag the health check set, and dismissals. */
@Entity(
    tableName = "feed_flag",
    primaryKeys = ["user_id", "feed_id"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feed_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("feed_id")],
)
data class FeedFlagEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "last_flag") val lastFlag: HealthFlag? = null,
    @ColumnInfo(name = "flag_dismissed_until") val flagDismissedUntil: Instant? = null,
    /** Moved is shown until this is at or after `feed_status.moved_at`. */
    @ColumnInfo(name = "moved_acknowledged_at") val movedAcknowledgedAt: Instant? = null,
)

/** What the user decided about a suggested feed, by its URL key; as the app's, per user. */
@Entity(
    tableName = "discovery_verdict",
    primaryKeys = ["user_id", "urlKey"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["user_id", "decidedAt"])],
)
class DiscoveryVerdictEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    val urlKey: String,
    val url: String,
    val verdict: DiscoveryVerdict,
    val decidedAt: Instant,
    val title: String? = null,
    val clusterId: Int? = null,
    /** The feed's direction in the embedder's space (`StaticEmbedder.toBytes`). */
    val centroid: ByteArray? = null,
)

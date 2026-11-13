package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind

// Per-user reading state and editions (owned by W-B). Columns carried over from the app keep its
// names; `user_id` and `change_seq` are new.

/** A user's state for one article. A row exists only once something happened to it. */
@Entity(
    tableName = "engagement",
    primaryKeys = ["user_id", "articleId"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("articleId"),
        // `GET /engagement?since=`.
        Index(value = ["user_id", "change_seq"]),
    ],
)
data class EngagementEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    val articleId: Long,
    val openedAt: Instant? = null,
    /** Furthest point reached, 0..1. */
    @ColumnInfo(defaultValue = "0") val readFraction: Float = 0f,
    val readAt: Instant? = null,
    val savedAt: Instant? = null,
    val likedAt: Instant? = null,
    /** Editions this article was printed in without being opened; drives the seen penalty. */
    @ColumnInfo(defaultValue = "0") val editionsRunUnopened: Int = 0,
    /** Times this article has led a front page. */
    @ColumnInfo(defaultValue = "0") val leadCount: Int = 0,
    /** `users.change_seq` when the row last changed. */
    @ColumnInfo(name = "change_seq", defaultValue = "0") val changeSeq: Long = 0,
)

/** A user's edition. [id] is global; [number] runs per user. */
@Entity(
    tableName = "edition",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["user_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["user_id", "date", "kind"], unique = true),
        Index(value = ["user_id", "releaseAt"]),
        Index(value = ["user_id", "change_seq"]),
    ],
)
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "user_id") val userId: Long,
    /** The local calendar date (in the user's zone) the edition is published for. */
    val date: LocalDate,
    val kind: EditionKind,
    val cutoffAt: Instant,
    /** Hidden from clients until this instant. */
    val releaseAt: Instant,
    val builtAt: Instant,
    val length: EditionLength,
    val number: Int,
    val openedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Whether page views are recorded; false for imported editions without them. */
    @ColumnInfo(defaultValue = "0") val pageViewsTracked: Boolean = false,
    @ColumnInfo(name = "change_seq", defaultValue = "0") val changeSeq: Long = 0,
)

/** A story's place in an edition. Unchanged from the app. */
@Entity(
    tableName = "edition_item",
    primaryKeys = ["editionId", "articleId"],
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("articleId"),
        Index(value = ["editionId", "page", "slot"]),
    ],
)
data class EditionItemEntity(
    val editionId: Long,
    val articleId: Long,
    val page: Int,
    /** Tag name for SECTION items; null otherwise. */
    val section: String? = null,
    val slot: Int,
    val kind: ItemKind,
    val score: Double,
    val reason: String? = null,
)

/** Time on one page of an edition. Unchanged from the app. */
@Entity(
    tableName = "edition_page_view",
    primaryKeys = ["editionId", "page"],
    foreignKeys = [
        ForeignKey(entity = EditionEntity::class, parentColumns = ["id"], childColumns = ["editionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EditionPageViewEntity(
    val editionId: Long,
    val page: Int,
    /** Total time on screen, in milliseconds. */
    val msVisible: Long,
    val firstSeenAt: Instant,
)

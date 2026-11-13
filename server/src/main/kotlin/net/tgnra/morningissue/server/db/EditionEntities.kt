package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

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

// Read shapes of the W-B DAOs. They are named apart from W-A's rows (`User*`, `Learning*`),
// since both workstreams add classes to this package.

/**
 * An article as one user sees it: its feed's name for them, whether they subscribe, when it
 * was first seen by them ([seenAt] = `MAX(firstSeenAt, subscription.added_at)`) and their
 * engagement. Engagement columns are null when the user has no row for the article.
 */
data class UserArticleRow(
    @Embedded val article: ArticleEntity,
    val feedTitle: String,
    val feedIconUrl: String?,
    /** Null when the user has no subscription to the post's feed at all. */
    val feedStatus: FeedStatus?,
    val seenAt: Instant,
    val openedAt: Instant?,
    val readFraction: Float?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
    val editionsRunUnopened: Int?,
    val leadCount: Int?,
)

/** An edition item with its article, as the user sees it. */
data class UserItemRow(
    @Embedded(prefix = "item_") val item: EditionItemEntity,
    @Embedded val row: UserArticleRow,
)

/** An edition with what its newsstand cover shows. */
data class UserCoverRow(
    @Embedded val edition: EditionEntity,
    val leadTitle: String?,
    val leadImageUrl: String?,
    val leadFeedTitle: String?,
    val storyCount: Int,
)

/** One printed (non-index) story in one of the user's editions, with what they did; input to affinity. */
data class UserRunRow(
    val feedId: Long,
    val articleId: Long,
    val editionId: Long,
    val ranAt: Instant,
    val openedAt: Instant?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
    /** The story's page was on screen for `pageSeenMinMillis` in all, or the edition predates page views. */
    val seen: Boolean = true,
)

/** An article the user engaged with, printed or not; input to affinity. */
data class UserEngagedRow(
    val feedId: Long,
    val articleId: Long,
    val openedAt: Instant?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
)

/** A subscribed feed as the publisher and the learner read it. */
data class UserFeedRow(
    val feedId: Long,
    val title: String,
    val tier: CadenceTier,
    val medianIntervalHrs: Double?,
    val affinity: Double,
    val sliderStep: SliderStep,
    val muteUntil: Instant?,
    val addedAt: Instant,
)

/** A tag on one of the user's subscriptions. */
data class UserTagLinkRow(
    val feedId: Long,
    val tagId: Long,
    val name: String,
    val affinity: Double,
)

/** One of the user's tags. */
data class UserTagRow(
    val id: Long,
    val name: String,
    val affinity: Double,
)

/** An edition's reading state, for the engagement delta. */
data class UserEditionStateRow(
    val id: Long,
    val openedAt: Instant?,
    val finishedAt: Instant?,
)

/** A stored body; see `EditionArticleDao.contents`. */
data class StoredBodyRow(
    val articleId: Long,
    val blocksJson: String,
    val source: ContentSource,
)

/** A feed flag as the desk notice counts it. */
data class UserFlagRow(
    val flag: HealthFlag?,
    val dismissedUntil: Instant?,
)

/** One row of `LearningDao`'s signal reads, before its categories are attached. */
data class LearningSignalRow(
    val articleId: Long,
    val feedId: Long,
    val feedWordCount: Int,
    @Embedded(prefix = "t_") val traits: ArticleTraitsEntity?,
    val vector: ByteArray?,
)

/** What the manifest's bundle cache is keyed by, besides the edition: see `EditionDao.bundleKey`. */
data class BundleKeyRow(
    val editionSeq: Long,
    val engagementSeq: Long,
    val contentAt: Long,
    val contentCount: Int,
)

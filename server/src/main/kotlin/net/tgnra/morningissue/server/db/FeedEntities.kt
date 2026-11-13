package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EnclosureKind

// Global feed and article tables, shared by every user (owned by W-A). Columns carried over from
// the app keep its names, so ported SQL reads the same; new columns are snake_case.

/**
 * A feed document the server fetches, shared by every subscriber. What a user chose about it
 * (title override, slider, mute, affinity) is in `subscription`.
 */
@Entity(
    tableName = "feed",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["url_key"], unique = true),
    ],
)
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The feed document URL as fetched. Repair changes it globally. */
    val url: String,
    /** `FeedUrls.normalize(url)`: a second subscription to the same feed finds this row. */
    @ColumnInfo(name = "url_key") val urlKey: String,
    val siteUrl: String? = null,
    /** The feed document's own title. */
    val title: String,
    val iconUrl: String? = null,
    val medianIntervalHrs: Double? = null,
    val burstiness: Double? = null,
    @ColumnInfo(defaultValue = "WEEKLY") val tier: CadenceTier = CadenceTier.WEEKLY,
    /** HTTP validators from the last successful fetch, for conditional requests. */
    val etag: String? = null,
    val lastModified: String? = null,
    /** When the first user subscribed. */
    val addedAt: Instant,
    /** Every fetch, successful or not; refreshes skip a feed tried within their `maxAge`. */
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Instant? = null,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Instant? = null,
)

/** The global half of the app's `feed_health`: what fetching the feed has found. */
@Entity(
    tableName = "feed_status",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feed_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FeedStatusEntity(
    @PrimaryKey @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "last_post_at") val lastPostAt: Instant? = null,
    /** Consecutive failed fetches. */
    @ColumnInfo(name = "error_streak", defaultValue = "0") val errorStreak: Int = 0,
    /** When the current run of errors began; null while healthy. */
    @ColumnInfo(name = "error_since") val errorSince: Instant? = null,
    @ColumnInfo(name = "last_http_status") val lastHttpStatus: Int? = null,
    /** When an automatic repair was last tried. */
    @ColumnInfo(name = "last_repair_at") val lastRepairAt: Instant? = null,
    /** The address a repair moved the feed from, and when; subscribers see Moved until they acknowledge it. */
    @ColumnInfo(name = "moved_from_url") val movedFromUrl: String? = null,
    @ColumnInfo(name = "moved_at") val movedAt: Instant? = null,
)

/** A post, global: [id] is the ID every client sees. Unchanged from the app. */
@Entity(
    tableName = "article",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        // Candidate queries: "first seen since the last cutoff".
        Index(value = ["firstSeenAt", "feedId"]),
        // Identity of a post, and the archive-import merge key.
        Index(value = ["feedId", "guid"], unique = true),
        // Source pages and cadence: a feed's posts in date order.
        Index(value = ["feedId", "publishedAt"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    /** The feed's own post ID; the parser falls back to the link or a content hash. */
    val guid: String,
    val url: String? = null,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val author: String? = null,
    val publishedAt: Instant,
    /** When the server first fetched this post. A user's effective first-seen is `MAX(firstSeenAt, subscription.added_at)`. */
    val firstSeenAt: Instant,
    /** Words in the stored body. */
    @ColumnInfo(defaultValue = "0") val wordCount: Int = 0,
    /** Words in the feed's own text for the post (`FeedWordCount`); never changed by full-text fetching. */
    @ColumnInfo(defaultValue = "0") val feedWordCount: Int = 0,
    val contentHash: String? = null,
)

/** Article body as block-model JSON, kept out of `article` so candidate queries stay small. */
@Entity(
    tableName = "article_content",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ArticleContentEntity(
    @PrimaryKey val articleId: Long,
    /** `ContentBlocks.encode(...)`, uncompressed. */
    val blocksJson: String,
    val source: ContentSource,
    val storedAt: Instant,
)

/**
 * Full-text index. A standalone FTS4 table whose `rowid` is the article ID, kept in step with
 * `article` and `article_content` by the article DAO. The plain body text lives only here.
 */
@Fts4
@Entity(tableName = "article_fts")
data class ArticleFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val articleId: Long,
    val title: String,
    val summary: String,
    val author: String,
    val body: String,
)

/** A category the feed files a post under; only the feed document has it. */
@Entity(
    tableName = "article_category",
    primaryKeys = ["articleId", "key"],
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("key")],
)
data class ArticleCategoryEntity(
    val articleId: Long,
    /** `ArticleCategories.key`. */
    val key: String,
    /** The feed's own spelling. */
    val label: String,
)

/** Structural features of a post (`ArticleTraits`). */
@Entity(
    tableName = "article_traits",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ArticleTraitsEntity(
    @PrimaryKey val articleId: Long,
    /** `ArticleTraitRules.VERSION` when computed; a row that is behind is recomputed. */
    val traitsVersion: Int,
    val linkCount: Int,
    val imageCount: Int,
    val hasCode: Boolean,
    /** Known only from the feed document; recomputing keeps it. */
    val enclosure: EnclosureKind,
    val isLinkPost: Boolean,
    val titlePrefix: String?,
) {
    fun toTraits() = ArticleTraits(linkCount, imageCount, hasCode, enclosure, isLinkPost, titlePrefix)
}

/** A post's text embedding; [model] says which embedder wrote [vector]. */
@Entity(
    tableName = "article_embedding",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
class ArticleEmbeddingEntity(
    @PrimaryKey val articleId: Long,
    val model: Int,
    val vector: ByteArray,
)

package net.tgnra.morningissue.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

@Entity(tableName = "feed", indices = [Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The feed document URL as fetched. */
    val url: String,
    val siteUrl: String? = null,
    val title: String,
    val iconUrl: String? = null,
    val medianIntervalHrs: Double? = null,
    val burstiness: Double? = null,
    @ColumnInfo(defaultValue = "WEEKLY") val tier: CadenceTier = CadenceTier.WEEKLY,
    @ColumnInfo(defaultValue = "0.5") val affinity: Double = 0.5,
    @ColumnInfo(defaultValue = "AUTO") val sliderStep: SliderStep = SliderStep.AUTO,
    @ColumnInfo(defaultValue = "0") val notify: Boolean = false,
    val muteUntil: Instant? = null,
    val lastSyncAt: Instant? = null,
    /** HTTP validators from the last successful fetch, for conditional requests. */
    val etag: String? = null,
    val lastModified: String? = null,
    val addedAt: Instant,
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0.5") val affinity: Double = 0.5,
)

@Entity(
    tableName = "feed_tag",
    primaryKeys = ["feedId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class FeedTagEntity(
    val feedId: Long,
    val tagId: Long,
)

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
    /** The feed's publish date; the parser falls back to [firstSeenAt] when missing or unreadable. */
    val publishedAt: Instant,
    /** When the app first fetched this post. Decides which edition it belongs to. */
    val firstSeenAt: Instant,
    /** Words in the stored body: the feed's text at first, the fetched page once full text is fetched. Reading time. */
    @ColumnInfo(defaultValue = "0") val wordCount: Int = 0,
    /**
     * Words in the text the feed itself gave for the post (`FeedWordCount`). Set when the post
     * is stored and never changed by full-text fetching, so that it measures a post the reader
     * opened and one they did not in the same way. Within-feed ranking builds its length key
     * from this, not from [wordCount]. 0 when the feed gave no text.
     */
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

/** Per-article reader state. A row exists only once something has happened to the article. */
@Entity(
    tableName = "engagement",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class EngagementEntity(
    @PrimaryKey val articleId: Long,
    /** First time the article was opened in the reader; null if never. */
    val openedAt: Instant? = null,
    /** Furthest scroll position reached, 0..1. */
    @ColumnInfo(defaultValue = "0") val readFraction: Float = 0f,
    /** When the article first counted as read; null if not read. */
    val readAt: Instant? = null,
    val savedAt: Instant? = null,
    val likedAt: Instant? = null,
    /** Editions this article was printed in without being opened; drives the seen penalty P. */
    @ColumnInfo(defaultValue = "0") val editionsRunUnopened: Int = 0,
    /** Times this article has led a front page; a post may lead at most twice. */
    @ColumnInfo(defaultValue = "0") val leadCount: Int = 0,
)

@Entity(
    tableName = "edition",
    indices = [
        Index(value = ["date", "kind"], unique = true),
        Index("releaseAt"),
    ],
)
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The local calendar date the edition is published for. */
    val date: LocalDate,
    val kind: EditionKind,
    /** Posts first seen up to this instant (and after the previous cutoff) belong to this edition. */
    val cutoffAt: Instant,
    /** The edition is hidden until this instant. */
    val releaseAt: Instant,
    val builtAt: Instant,
    val length: EditionLength,
    /** Running edition number, starting at 1. */
    val number: Int,
    /** First time the reader opened this edition. */
    val openedAt: Instant? = null,
    /** When the reader first reached the back page; drives the "read" tick on covers. */
    val finishedAt: Instant? = null,
)

/** A story's place in an edition. See `ItemKind` for the page contract. */
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
    /** 1-based page number. */
    val page: Int,
    /** Tag name for [ItemKind.SECTION] items ("General" for untagged feeds); null otherwise. */
    val section: String? = null,
    /** Order within the page, ascending from 0. */
    val slot: Int,
    val kind: ItemKind,
    val score: Double,
    /** One line for "Why is this here?". */
    val reason: String? = null,
)

@Entity(
    tableName = "feed_health",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FeedHealthEntity(
    @PrimaryKey val feedId: Long,
    val lastPostAt: Instant? = null,
    /** Consecutive failed fetches. */
    @ColumnInfo(defaultValue = "0") val errorStreak: Int = 0,
    /** When the current run of errors began; null while healthy. */
    val errorSince: Instant? = null,
    /** HTTP status of the most recent fetch, when there was a response. */
    val lastHttpStatus: Int? = null,
    val lastFlag: HealthFlag? = null,
    val flagDismissedUntil: Instant? = null,
    /** When an automatic repair of a failing feed was last tried; limits how often it runs. */
    val lastRepairAt: Instant? = null,
)

/**
 * Full-text index. A standalone FTS4 table whose `rowid` is the article ID; `ArticleDao` keeps
 * it in step with `article` and `article_content`. The plain body text lives only here.
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

/**
 * A category the feed files a post under. Categories come only from the feed document, so
 * they cannot be recomputed from what is stored; the archive carries them.
 */
@Entity(
    tableName = "article_category",
    primaryKeys = ["articleId", "key"],
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    // "Every post in this category", for learning an affinity per feed and category.
    indices = [Index("key")],
)
data class ArticleCategoryEntity(
    val articleId: Long,
    /** The category squashed to single spaces and lowercased (`ArticleCategories.key`). */
    val key: String,
    /** The feed's own spelling. */
    val label: String,
)

/**
 * Structural features of a post (`ArticleTraits`), computed by `ArticleTraitRules` at sync and,
 * for posts stored before the table existed or under older rules, by `TraitsBackfill`.
 */
@Entity(
    tableName = "article_traits",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ArticleTraitsEntity(
    @PrimaryKey val articleId: Long,
    /** `ArticleTraitRules.VERSION` when the row was computed; a row that is behind is recomputed. */
    val traitsVersion: Int,
    val linkCount: Int,
    val imageCount: Int,
    val hasCode: Boolean,
    /** Known only from the feed document; recomputing a row keeps it. */
    val enclosure: EnclosureKind,
    val isLinkPost: Boolean,
    val titlePrefix: String?,
) {
    fun toTraits() = ArticleTraits(linkCount, imageCount, hasCode, enclosure, isLinkPost, titlePrefix)
}

/**
 * A post's text embedding. One row per post: [model] says which embedder (and version of it)
 * wrote [vector], so a post embedded by an older model counts as not embedded and is replaced.
 * The layout of [vector] is the embedder's own.
 */
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

/**
 * One image kept in the image store (`data/images/`): the file `fileName` in the store's
 * directory holds the picture at [url], usually re-encoded smaller. The row is the store's
 * bookkeeping; display looks for the file alone.
 *
 * An image belongs to one post, the one it was first stored for, and goes when that post's
 * feed is removed or its images are trimmed. A picture several posts share is fetched from
 * the network again for the others.
 */
@Entity(
    tableName = "stored_image",
    foreignKeys = [
        ForeignKey(entity = ArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("articleId")],
)
data class StoredImageEntity(
    /** The address as the post gives it, which is also what display asks for. */
    @PrimaryKey val url: String,
    val articleId: Long,
    /** Derived from [url] (`ImageFiles.nameFor`), so a file can be found without this row. */
    val fileName: String,
    /** Bytes on disk; what the storage budget counts. */
    val byteSize: Long,
    /** Bytes downloaded. Equal to [byteSize] when the original was kept as it came. */
    val originalBytes: Long,
    /** Pixels of the stored file; 0 for an image stored without being decoded (SVG, animations). */
    val width: Int,
    val height: Int,
    val storedAt: Instant,
)

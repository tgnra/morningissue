package net.tgnra.morningissue.data.db

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation
import java.time.Instant
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.FeedStatus

/** A feed with its tags and health row. */
data class FeedWithDetails(
    @Embedded val feed: FeedEntity,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["id"],
        associateBy = Junction(FeedTagEntity::class, parentColumns = ["feedId"], entityColumns = ["tagId"]),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["feedId"])
    val health: FeedHealthEntity?,
)

/**
 * An article with its feed's name and the reader's state. Engagement columns are null when
 * the article has no engagement row yet.
 */
data class ArticleRow(
    @Embedded val article: ArticleEntity,
    val feedTitle: String,
    val feedIconUrl: String?,
    /** Whether the reader subscribes to the post's feed; a post saved from discovery is not. */
    val feedStatus: FeedStatus,
    val openedAt: Instant?,
    val readFraction: Float?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
    val editionsRunUnopened: Int?,
    val leadCount: Int?,
)

data class SearchRow(
    @Embedded val row: ArticleRow,
    val snippet: String?,
)

data class EditionItemRow(
    @Embedded(prefix = "item_") val item: EditionItemEntity,
    @Embedded val row: ArticleRow,
)

/** An edition with what its newsstand cover shows. */
data class EditionCoverRow(
    @Embedded val edition: EditionEntity,
    val leadTitle: String?,
    val leadImageUrl: String?,
    val leadFeedTitle: String?,
    val storyCount: Int,
)

/** One printed (non-index) story in one edition, with what the reader did; input to affinity. */
data class EditionRunRow(
    val feedId: Long,
    val articleId: Long,
    val editionId: Long,
    val ranAt: Instant,
    val openedAt: Instant?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
    /**
     * The run counts as exposure: the story's page was on screen for at least
     * `pageSeenMinMillis` in all, or the edition predates page views (`pageViewsTracked`).
     */
    val seen: Boolean = true,
)

/** An article the reader engaged with, whether or not it was printed; input to affinity. */
data class EngagedRow(
    val feedId: Long,
    val articleId: Long,
    val openedAt: Instant?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
)

data class FeedUrlRow(
    val id: Long,
    val url: String,
)

/** See `FeedHealthDao.runStatsSince`. */
data class FeedRunStatsRow(
    val feedId: Long,
    val editions: Int,
    val stories: Int,
    val opened: Int,
    val read: Int,
    val liked: Int,
)

/** See `FeedHealthDao.postStatsSince`. */
data class FeedPostStatsRow(
    val feedId: Long,
    val posts: Int,
    val opened: Int,
)

/** A feed-provided body; see `ArticleDao.feedBodiesSince`. */
data class FeedBodyRow(
    val articleId: Long,
    val blocksJson: String,
)

/** A stored body and its size in characters, search-index copy included; see `ArticleDao.trimCandidates`. */
data class ContentSizeRow(
    val articleId: Long,
    val size: Long,
)

/**
 * What sync needs to know about a post it already holds; see `ArticleSignalDao.storedPosts`.
 * [traitsVersion] and [enclosure] are null when the post has no `article_traits` row.
 */
data class StoredPostRow(
    val articleId: Long,
    val guid: String,
    val hasCategories: Boolean,
    val traitsVersion: Int?,
    val enclosure: EnclosureKind?,
)

/**
 * A post whose traits are missing or behind, with what they are computed from; see
 * `ArticleSignalDao.articlesNeedingTraits`. [blocksJson] is null when the post has no stored
 * body; [enclosure] is the outdated row's, when there is one.
 */
data class TraitsInputRow(
    val articleId: Long,
    val title: String,
    val url: String?,
    val siteUrl: String?,
    val blocksJson: String?,
    val enclosure: EnclosureKind?,
)

/** A post to embed; see `ArticleSignalDao.articlesWithoutEmbedding`. [body] is the opening of its plain text. */
data class EmbeddingInputRow(
    val articleId: Long,
    val title: String,
    val summary: String?,
    val body: String?,
)

/** One row of `ArticleSignalDao`'s ranking reads, before its categories are attached. */
data class ArticleSignalRow(
    val articleId: Long,
    val feedId: Long,
    val feedWordCount: Int,
    @Embedded(prefix = "t_") val traits: ArticleTraitsEntity?,
    val vector: ByteArray?,
)

/**
 * Everything stored about a post that the within-feed ranking reads, without its text.
 *
 * @property traits null when not computed yet; check `traitsVersion` against
 *   `ArticleTraitRules.VERSION` before trusting more than the enclosure.
 * @property embedding the vector written by the model asked for; null when the post has not
 *   been embedded by it.
 */
class ArticleSignals(
    val articleId: Long,
    val feedId: Long,
    /** `article.feedWordCount`: the length of the feed's own text, whatever has been fetched since. */
    val feedWordCount: Int,
    val categories: List<ArticleCategory>,
    val traits: ArticleTraitsEntity?,
    val embedding: ByteArray?,
)

/** What `ImageStore` needs of a post to find its pictures: the lead image and the stored body. */
data class ArticleImageSourceRow(
    val articleId: Long,
    val imageUrl: String?,
    val blocksJson: String?,
)

/** A stored image's file and size; see `StoredImageDao`. */
data class StoredImageFileRow(
    val url: String,
    val fileName: String,
    val byteSize: Long,
)

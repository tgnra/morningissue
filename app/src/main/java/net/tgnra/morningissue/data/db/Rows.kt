package net.tgnra.morningissue.data.db

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation
import java.time.Instant

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

/** A stored body and its size in characters, search-index copy included; see `ArticleDao.trimCandidates`. */
data class ContentSizeRow(
    val articleId: Long,
    val size: Long,
)

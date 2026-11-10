package net.tgnra.morningissue.data.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import java.time.Instant

/** A feed with its tags and health row. */
data class FeedWithDetails(
    @Embedded val feed: FeedEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(FeedTagEntity::class, parentColumn = "feedId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "feedId")
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

data class FeedUrlRow(
    val id: Long,
    val url: String,
)

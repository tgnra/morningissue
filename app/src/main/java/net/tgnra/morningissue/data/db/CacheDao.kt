package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.FeedStatus

/**
 * The writes that keep the database a copy of the server (`client.ServerSync`). Rows carry the
 * server's IDs. An upsert updates a row in place, so the rows that hang off it by a cascading
 * foreign key stay; a row that would clash on another unique key (a feed's URL, an edition's
 * date and kind) under a different ID is deleted first, since the server no longer has it.
 */
@Dao
interface CacheDao {
    // --- Feeds and tags -------------------------------------------------------------------

    @Upsert
    suspend fun upsertFeed(feed: FeedEntity)

    @Query("DELETE FROM feed WHERE url = :url AND id != :id")
    suspend fun deleteFeedClashingWith(id: Long, url: String)

    @Query("SELECT id FROM feed")
    suspend fun feedIds(): List<Long>

    @Query("SELECT id FROM feed WHERE id IN (:ids)")
    suspend fun existingFeedIds(ids: List<Long>): List<Long>

    /** Whether an edition places, or the reader saved or Liked, any of the feed's posts. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM article a WHERE a.feedId = :feedId AND (" +
            "a.id IN (SELECT articleId FROM edition_item) OR " +
            "a.id IN (SELECT articleId FROM engagement WHERE savedAt IS NOT NULL OR likedAt IS NOT NULL)))",
    )
    suspend fun feedReferenced(feedId: Long): Boolean

    @Query("UPDATE feed SET status = :status WHERE id = :feedId")
    suspend fun setFeedStatus(feedId: Long, status: FeedStatus)

    @Upsert
    suspend fun upsertTag(tag: TagEntity)

    @Query("DELETE FROM tag WHERE name = :name COLLATE NOCASE AND id != :id")
    suspend fun deleteTagClashingWith(id: Long, name: String)

    @Query("DELETE FROM tag WHERE id NOT IN (:keep)")
    suspend fun deleteTagsExcept(keep: List<Long>)

    @Query("DELETE FROM feed_tag WHERE feedId = :feedId")
    suspend fun unlinkTags(feedId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTag(link: FeedTagEntity)

    // --- Articles ---------------------------------------------------------------------------

    @Upsert
    suspend fun upsertArticle(article: ArticleEntity)

    @Query("DELETE FROM article WHERE feedId = :feedId AND guid = :guid AND id != :id")
    suspend fun deleteArticleClashingWith(id: Long, feedId: Long, guid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM article WHERE id = :articleId)")
    suspend fun articleExists(articleId: Long): Boolean

    @Query("SELECT body FROM article_fts WHERE rowid = :articleId")
    suspend fun indexedBody(articleId: Long): String?

    @Query("DELETE FROM article_fts WHERE rowid = :articleId")
    suspend fun deleteFts(articleId: Long)

    @Insert
    suspend fun insertFts(row: ArticleFtsEntity)

    @Upsert
    suspend fun upsertContent(content: ArticleContentEntity)

    @Upsert
    suspend fun upsertEngagement(engagement: EngagementEntity)

    // --- Editions ---------------------------------------------------------------------------

    @Upsert
    suspend fun upsertEdition(edition: EditionEntity)

    @Query("DELETE FROM edition WHERE date = :date AND kind = :kind AND id != :id")
    suspend fun deleteEditionClashingWith(id: Long, date: LocalDate, kind: EditionKind)

    @Query("DELETE FROM edition_item WHERE editionId = :editionId")
    suspend fun deleteItems(editionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<EditionItemEntity>)

    @Query("SELECT COUNT(*) FROM edition_item WHERE editionId = :editionId")
    suspend fun itemCount(editionId: Long): Int

    @Upsert
    suspend fun upsertPageView(view: EditionPageViewEntity)

    @Query("UPDATE edition SET openedAt = :openedAt, finishedAt = :finishedAt WHERE id = :editionId")
    suspend fun setEditionState(editionId: Long, openedAt: Instant?, finishedAt: Instant?)

    @Query("SELECT EXISTS(SELECT 1 FROM edition WHERE id = :editionId)")
    suspend fun editionExists(editionId: Long): Boolean

    /** Released editions, newest first: the two newest are kept whole by the storage trim. */
    @Query("SELECT id FROM edition WHERE releaseAt <= :now ORDER BY releaseAt DESC LIMIT :limit")
    suspend fun newestReleasedIds(now: Instant, limit: Int): List<Long>
}

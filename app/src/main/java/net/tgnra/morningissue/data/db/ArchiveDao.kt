package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant

/** Reads and writes for archive export and import (`data/archive`). */
@Dao
interface ArchiveDao {
    /** Articles in ID order, a page at a time: pass the last ID of the previous page. */
    @Query("SELECT * FROM article WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun articlesAfter(afterId: Long, limit: Int): List<ArticleEntity>

    @Query("SELECT * FROM article_content WHERE articleId IN (:articleIds)")
    suspend fun contents(articleIds: List<Long>): List<ArticleContentEntity>

    @Query("SELECT * FROM engagement WHERE articleId IN (:articleIds)")
    suspend fun engagements(articleIds: List<Long>): List<EngagementEntity>

    /** Released editions, oldest first. */
    @Query("SELECT * FROM edition WHERE releaseAt <= :now ORDER BY releaseAt")
    suspend fun releasedEditions(now: Instant): List<EditionEntity>

    @Query("SELECT * FROM edition_item WHERE editionId = :editionId ORDER BY page, slot")
    suspend fun items(editionId: Long): List<EditionItemEntity>

    @Upsert
    suspend fun upsertEngagement(engagement: EngagementEntity)
}

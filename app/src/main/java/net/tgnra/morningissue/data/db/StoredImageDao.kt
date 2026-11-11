package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant

/**
 * Bookkeeping of the image store (`stored_image`). The files themselves are `ImageStore`'s:
 * every delete here is paired with a file delete there.
 */
@Dao
abstract class StoredImageDao {
    @Upsert
    abstract suspend fun upsert(image: StoredImageEntity)

    @Query("SELECT * FROM stored_image WHERE url = :url")
    abstract suspend fun get(url: String): StoredImageEntity?

    @Query("SELECT COUNT(*) FROM stored_image")
    abstract suspend fun count(): Int

    /** Bytes of every stored image, for the storage budget. */
    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM stored_image")
    abstract suspend fun totalBytes(): Long

    /** Which of [urls] have a row. At most `ArticleSignalDao.MAX_IDS` per call. */
    @Query("SELECT url, fileName, byteSize FROM stored_image WHERE url IN (:urls)")
    abstract suspend fun stored(urls: List<String>): List<StoredImageFileRow>

    /** Every row, for the sweep that pairs rows with files. */
    @Query("SELECT url, fileName, byteSize FROM stored_image")
    abstract suspend fun all(): List<StoredImageFileRow>

    /** The images of every post of [feedId], to delete with the feed. */
    @Query(
        "SELECT s.url AS url, s.fileName AS fileName, s.byteSize AS byteSize FROM stored_image s " +
            "JOIN article a ON a.id = s.articleId WHERE a.feedId = :feedId",
    )
    abstract suspend fun ofFeed(feedId: Long): List<StoredImageFileRow>

    /** The images of [articleIds], to delete with the posts. At most `ArticleSignalDao.MAX_IDS` per call. */
    @Query("SELECT url, fileName, byteSize FROM stored_image WHERE articleId IN (:articleIds)")
    abstract suspend fun ofArticles(articleIds: List<Long>): List<StoredImageFileRow>

    /** At most `ArticleSignalDao.MAX_IDS` per call. */
    @Query("DELETE FROM stored_image WHERE url IN (:urls)")
    abstract suspend fun delete(urls: List<String>)

    /**
     * Stored images that may be trimmed, in trimming order. The rules are those of
     * `ArticleDao.trimCandidates` for text: the oldest editions' posts first, never a Liked or
     * saved post, a post first seen at or after [protectFrom] or one placed in [keepEditionIds].
     */
    @Query(
        "SELECT s.url AS url, s.fileName AS fileName, s.byteSize AS byteSize FROM stored_image s " +
            "JOIN article a ON a.id = s.articleId LEFT JOIN engagement g ON g.articleId = s.articleId " +
            "WHERE g.savedAt IS NULL AND g.likedAt IS NULL AND a.firstSeenAt < :protectFrom " +
            "AND s.articleId NOT IN (SELECT articleId FROM edition_item WHERE editionId IN (:keepEditionIds)) " +
            "ORDER BY a.firstSeenAt, a.id, s.url LIMIT :limit",
    )
    abstract suspend fun trimCandidates(protectFrom: Instant, keepEditionIds: List<Long>, limit: Int): List<StoredImageFileRow>

    /** The lead image and stored body of each of [articleIds]. At most `ArticleSignalDao.MAX_IDS` per call. */
    @Query(
        "SELECT a.id AS articleId, a.imageUrl AS imageUrl, c.blocksJson AS blocksJson FROM article a " +
            "LEFT JOIN article_content c ON c.articleId = a.id WHERE a.id IN (:articleIds)",
    )
    abstract suspend fun imageSources(articleIds: List<Long>): List<ArticleImageSourceRow>
}

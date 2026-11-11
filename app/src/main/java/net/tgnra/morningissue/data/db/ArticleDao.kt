package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.ContentSource

@Dao
abstract class ArticleDao {
    // --- Articles -------------------------------------------------------------------------

    /** Returns the new row ID, or -1 when the feed already has a post with this GUID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(article: ArticleEntity): Long

    @Update
    abstract suspend fun update(article: ArticleEntity)

    @Query("SELECT * FROM article WHERE id = :articleId")
    abstract suspend fun getById(articleId: Long): ArticleEntity?

    @Query("SELECT * FROM article WHERE feedId = :feedId AND guid = :guid")
    abstract suspend fun getByGuid(feedId: Long, guid: String): ArticleEntity?

    @Query("SELECT COUNT(*) FROM article")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM article WHERE feedId = :feedId AND publishedAt >= :since")
    abstract suspend fun countForFeedSince(feedId: Long, since: Instant): Int

    /** The feed's most recent publish times, newest first; input to cadence. */
    @Query("SELECT publishedAt FROM article WHERE feedId = :feedId ORDER BY publishedAt DESC LIMIT :limit")
    abstract suspend fun recentPublishTimes(feedId: Long, limit: Int): List<Instant>

    /** The feed's publish times since [since], newest first; input to cadence. */
    @Query("SELECT publishedAt FROM article WHERE feedId = :feedId AND publishedAt >= :since ORDER BY publishedAt DESC")
    abstract suspend fun publishTimesSince(feedId: Long, since: Instant): List<Instant>

    @Query("SELECT MAX(publishedAt) FROM article WHERE feedId = :feedId")
    abstract suspend fun latestPublishedAt(feedId: Long): Instant?

    @Query("UPDATE article SET wordCount = :wordCount WHERE id = :articleId")
    protected abstract suspend fun updateWordCount(articleId: Long, wordCount: Int)

    // --- Articles with feed and engagement ------------------------------------------------

    @Query("$ROW_SELECT WHERE a.id = :articleId")
    abstract suspend fun getRow(articleId: Long): ArticleRow?

    @Query("$ROW_SELECT WHERE a.id = :articleId")
    abstract fun observeRow(articleId: Long): Flow<ArticleRow?>

    /** Posts first seen in `(after, upTo]`, i.e. the new posts of one edition. */
    @Query("$ROW_SELECT WHERE a.firstSeenAt > :after AND a.firstSeenAt <= :upTo ORDER BY a.firstSeenAt")
    abstract suspend fun firstSeenBetween(after: Instant, upTo: Instant): List<ArticleRow>

    /**
     * The held-over pool of one loved slow feed: its posts first seen at or before [upTo] that
     * the reader has not touched (never opened, read, saved or Liked), newest first.
     */
    @Query(
        "$ROW_SELECT WHERE a.feedId = :feedId AND a.firstSeenAt <= :upTo AND g.openedAt IS NULL " +
            "AND g.readAt IS NULL AND g.savedAt IS NULL AND g.likedAt IS NULL " +
            "ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit",
    )
    abstract suspend fun heldOverForFeed(feedId: Long, upTo: Instant, limit: Int): List<ArticleRow>

    /**
     * When [feedId] last posted, going by the posts the app had seen by [seenUpTo]; null when
     * it knew of none. Welcome back measures a feed's silence from here.
     */
    @Query("SELECT publishedAt FROM article WHERE feedId = :feedId AND firstSeenAt <= :seenUpTo ORDER BY publishedAt DESC LIMIT 1")
    abstract suspend fun lastKnownPostAt(feedId: Long, seenUpTo: Instant): Instant?

    /** Feeds with a post the reader Liked or saved at or after [since]. */
    @Query(
        "SELECT DISTINCT a.feedId FROM engagement g JOIN article a ON a.id = g.articleId " +
            "WHERE g.likedAt >= :since OR g.savedAt >= :since",
    )
    abstract suspend fun feedsLikedOrSavedSince(since: Instant): List<Long>

    @Query("$ROW_SELECT WHERE a.feedId = :feedId ORDER BY a.publishedAt DESC LIMIT :limit")
    abstract fun observeForFeed(feedId: Long, limit: Int): Flow<List<ArticleRow>>

    @Query("$ROW_SELECT WHERE g.savedAt IS NOT NULL ORDER BY g.savedAt DESC")
    abstract fun observeSaved(): Flow<List<ArticleRow>>

    @Query("$ROW_SELECT WHERE g.likedAt IS NOT NULL ORDER BY g.likedAt DESC")
    abstract fun observeLiked(): Flow<List<ArticleRow>>

    // --- Content ---------------------------------------------------------------------------

    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    abstract suspend fun getContent(articleId: Long): ArticleContentEntity?

    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    abstract fun observeContent(articleId: Long): Flow<ArticleContentEntity?>

    @Query("SELECT source FROM article_content WHERE articleId = :articleId")
    abstract suspend fun getContentSource(articleId: Long): ContentSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertContent(content: ArticleContentEntity)

    /** Bytes of stored article text (approximate: characters of block JSON). */
    @Query("SELECT COALESCE(SUM(LENGTH(blocksJson)), 0) FROM article_content")
    abstract suspend fun contentSize(): Long

    /**
     * Articles whose stored text may be trimmed for the storage budget, oldest first.
     * Liked and saved articles are never returned.
     */
    @Query(
        "SELECT c.articleId FROM article_content c JOIN article a ON a.id = c.articleId " +
            "LEFT JOIN engagement g ON g.articleId = c.articleId " +
            "WHERE g.savedAt IS NULL AND g.likedAt IS NULL ORDER BY a.firstSeenAt LIMIT :limit",
    )
    abstract suspend fun trimmableContentIds(limit: Int): List<Long>

    /** Drops stored text only; the article row and its search entry stay. */
    @Query("DELETE FROM article_content WHERE articleId IN (:articleIds)")
    abstract suspend fun deleteContent(articleIds: List<Long>)

    // --- Full-text index -------------------------------------------------------------------

    @Query("DELETE FROM article_fts WHERE rowid = :articleId")
    protected abstract suspend fun deleteFts(articleId: Long)

    @Insert
    protected abstract suspend fun insertFts(row: ArticleFtsEntity)

    /**
     * Full-text search. [match] is FTS4 MATCH syntax (see `FtsQuery`); the other arguments
     * narrow the result and are ignored when null or false. Newest first.
     */
    @Query(
        "SELECT a.*, f.title AS feedTitle, f.iconUrl AS feedIconUrl, g.openedAt AS openedAt, " +
            "g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
            "g.editionsRunUnopened AS editionsRunUnopened, g.leadCount AS leadCount, " +
            "snippet(article_fts, '', '', '…', -1, 16) AS snippet " +
            "FROM article_fts JOIN article a ON a.id = article_fts.rowid " +
            "JOIN feed f ON f.id = a.feedId LEFT JOIN engagement g ON g.articleId = a.id " +
            "WHERE article_fts MATCH :match " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "AND (:tag IS NULL OR a.feedId IN (SELECT ft.feedId FROM feed_tag ft JOIN tag t ON t.id = ft.tagId WHERE t.name = :tag COLLATE NOCASE)) " +
            "AND (:from IS NULL OR a.publishedAt >= :from) " +
            "AND (:to IS NULL OR a.publishedAt <= :to) " +
            "AND (:likedOnly = 0 OR g.likedAt IS NOT NULL) " +
            "AND (:savedOnly = 0 OR g.savedAt IS NOT NULL) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit",
    )
    abstract suspend fun search(
        match: String,
        feedId: Long?,
        tag: String?,
        from: Instant?,
        to: Instant?,
        likedOnly: Boolean,
        savedOnly: Boolean,
        limit: Int,
    ): List<SearchRow>

    // --- Writes used by sync and full-text fetch -------------------------------------------

    /**
     * Stores a newly fetched post and indexes it for search. Returns the new article ID, or
     * null when the feed already has a post with this GUID (nothing is changed then).
     *
     * [blocksJson] is feed-provided body content, if any; [plainText] is its text for search.
     */
    @Transaction
    open suspend fun insertNew(
        article: ArticleEntity,
        blocksJson: String? = null,
        plainText: String? = null,
    ): Long? {
        val id = insertIgnore(article)
        if (id == -1L) return null
        if (blocksJson != null) {
            upsertContent(ArticleContentEntity(id, blocksJson, ContentSource.FEED, article.firstSeenAt))
        }
        insertFts(ftsRow(article.copy(id = id), plainText))
        return id
    }

    /**
     * Stores or replaces an article's body, refreshes its search entry and word count.
     * Does nothing when the article does not exist.
     */
    @Transaction
    open suspend fun storeContent(
        articleId: Long,
        blocksJson: String,
        plainText: String,
        wordCount: Int,
        source: ContentSource,
        storedAt: Instant,
    ) {
        val article = getById(articleId) ?: return
        upsertContent(ArticleContentEntity(articleId, blocksJson, source, storedAt))
        updateWordCount(articleId, wordCount)
        deleteFts(articleId)
        insertFts(ftsRow(article, plainText))
    }

    private fun ftsRow(article: ArticleEntity, body: String?) = ArticleFtsEntity(
        articleId = article.id,
        title = article.title,
        summary = article.summary.orEmpty(),
        author = article.author.orEmpty(),
        body = body.orEmpty(),
    )

    companion object {
        /** Shared projection for [ArticleRow]; append a WHERE clause. */
        const val ROW_SELECT =
            "SELECT a.*, f.title AS feedTitle, f.iconUrl AS feedIconUrl, g.openedAt AS openedAt, " +
                "g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
                "g.editionsRunUnopened AS editionsRunUnopened, g.leadCount AS leadCount " +
                "FROM article a JOIN feed f ON f.id = a.feedId LEFT JOIN engagement g ON g.articleId = a.id"
    }
}

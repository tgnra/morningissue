package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.FeedStatus

/**
 * Articles, their bodies and the FTS index: the refresher's and full text's writes, garbage
 * collection, and the per-user reads behind articles, shelves and search. Owned by W-A
 * (`docs/server-design.md`, section 8). Articles are global; every per-user read names
 * `:userId` and joins that user's subscription and engagement.
 */
@Dao
abstract class ArticleDao {
    // --- Articles (global) ---------------------------------------------------------------------

    /** Returns the new row ID, or -1 when the feed already has a post with this GUID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(article: ArticleEntity): Long

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

    @Query("UPDATE article SET imageUrl = :imageUrl WHERE id = :articleId AND imageUrl IS NULL")
    abstract suspend fun fillImageUrl(articleId: Long, imageUrl: String)

    /** The stories placed in one edition, briefs and index entries included: what its full text covers. */
    @Query("SELECT articleId FROM edition_item WHERE editionId = :editionId ORDER BY page, slot")
    abstract suspend fun editionArticleIds(editionId: Long): List<Long>

    @Query("DELETE FROM article WHERE id IN (:articleIds)")
    protected abstract suspend fun deleteRows(articleIds: List<Long>)

    /** Deletes posts with everything hanging off them, their search entries included. */
    @Transaction
    open suspend fun delete(articleIds: List<Long>) {
        for (id in articleIds) deleteFts(id)
        deleteRows(articleIds)
    }

    /**
     * Posts of feeds nobody subscribes to (in either status), unless some user's edition placed
     * them or someone saved or Liked them: what garbage collection deletes.
     */
    @Query(
        "SELECT a.id FROM article a WHERE NOT EXISTS (SELECT 1 FROM subscription s WHERE s.feed_id = a.feedId) " +
            "AND NOT EXISTS (SELECT 1 FROM edition_item i WHERE i.articleId = a.id) " +
            "AND NOT EXISTS (SELECT 1 FROM engagement g WHERE g.articleId = a.id AND (g.savedAt IS NOT NULL OR g.likedAt IS NOT NULL)) " +
            "ORDER BY a.id",
    )
    abstract suspend fun unreferencedIds(): List<Long>

    // --- Content -------------------------------------------------------------------------------

    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    abstract suspend fun getContent(articleId: Long): ArticleContentEntity?

    @Query("SELECT source FROM article_content WHERE articleId = :articleId")
    abstract suspend fun getContentSource(articleId: Long): ContentSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertContent(content: ArticleContentEntity)

    /**
     * Feed-provided bodies (source `FEED`) of posts of feeds someone subscribes to, published at
     * or after [since], that have a web address, by article ID after [afterId]; for `TeaserBackfill`.
     */
    @Query(
        "SELECT c.articleId AS articleId, c.blocksJson AS blocksJson FROM article_content c " +
            "JOIN article a ON a.id = c.articleId " +
            "WHERE c.source = 'FEED' AND a.url IS NOT NULL AND a.publishedAt >= :since AND c.articleId > :afterId " +
            "AND EXISTS (SELECT 1 FROM subscription s WHERE s.feed_id = a.feedId AND s.status = 'SUBSCRIBED') " +
            "ORDER BY c.articleId LIMIT :limit",
    )
    abstract suspend fun feedBodiesSince(since: Instant, afterId: Long, limit: Int): List<FeedBodyRow>

    // --- Full-text index -----------------------------------------------------------------------

    @Query("DELETE FROM article_fts WHERE rowid = :articleId")
    protected abstract suspend fun deleteFts(articleId: Long)

    @Insert
    protected abstract suspend fun insertFts(row: ArticleFtsEntity)

    // --- Writes used by refresh and full-text fetch --------------------------------------------

    /**
     * Stores a newly fetched post and indexes it for search. Returns the new article ID, or
     * null when the feed already has a post with this GUID (nothing is changed then).
     * [blocksJson] is feed-provided body content, if any; [plainText] is its text for search.
     */
    @Transaction
    open suspend fun insertNew(article: ArticleEntity, blocksJson: String? = null, plainText: String? = null): Long? {
        val id = insertIgnore(article)
        if (id == -1L) return null
        if (blocksJson != null) upsertContent(ArticleContentEntity(id, blocksJson, ContentSource.FEED, article.firstSeenAt))
        insertFts(ftsRow(article.copy(id = id), plainText))
        return id
    }

    /** Stores or replaces an article's body, refreshes its search entry and word count. Does nothing when the article does not exist. */
    @Transaction
    open suspend fun storeContent(articleId: Long, blocksJson: String, plainText: String, wordCount: Int, source: ContentSource, storedAt: Instant) {
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

    // --- Per-user reads ------------------------------------------------------------------------

    /** One article as [userId] sees it, whether or not they may; check [isVisible]. */
    @Query("$ROW_SELECT WHERE a.id = :articleId")
    abstract suspend fun row(userId: Long, articleId: Long): UserArticleRow?

    /** Whether [userId] may see the article: they subscribe to its feed (either status), or have engagement or an edition item for it. */
    @Query("SELECT EXISTS (SELECT 1 FROM article a WHERE a.id = :articleId AND $VISIBLE)")
    abstract suspend fun isVisible(userId: Long, articleId: Long): Boolean

    /** A feed's posts, newest published first, before [before]. */
    @Query("$ROW_SELECT WHERE a.feedId = :feedId AND a.publishedAt < :before ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit")
    abstract suspend fun forFeed(userId: Long, feedId: Long, before: Instant, limit: Int): List<UserArticleRow>

    /** The user's Saved shelf, newest saved first, saved before [before]. */
    @Query("$ROW_SELECT WHERE g.savedAt IS NOT NULL AND g.savedAt < :before ORDER BY g.savedAt DESC, a.id DESC LIMIT :limit")
    abstract suspend fun saved(userId: Long, before: Instant, limit: Int): List<UserArticleRow>

    /** The user's Liked shelf, newest Liked first, Liked before [before]. */
    @Query("$ROW_SELECT WHERE g.likedAt IS NOT NULL AND g.likedAt < :before ORDER BY g.likedAt DESC, a.id DESC LIMIT :limit")
    abstract suspend fun liked(userId: Long, before: Instant, limit: Int): List<UserArticleRow>

    /**
     * Full-text search over the articles [userId] can see ([VISIBLE]). [match] is FTS4 MATCH
     * syntax (see `FtsQuery`); the other arguments narrow the result and are ignored when null
     * or false. Newest first.
     */
    @Query(
        "SELECT a.*, COALESCE(s.title_override, f.title) AS feedTitle, f.iconUrl AS feedIconUrl, s.status AS feedStatus, " +
            "g.openedAt AS openedAt, g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
            "snippet(article_fts, '', '', '…', -1, 16) AS snippet " +
            "FROM article_fts JOIN article a ON a.id = article_fts.rowid JOIN feed f ON f.id = a.feedId " +
            "LEFT JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "LEFT JOIN engagement g ON g.articleId = a.id AND g.user_id = :userId " +
            "WHERE article_fts MATCH :match AND $VISIBLE " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "AND (:tagId IS NULL OR a.feedId IN (SELECT st.feed_id FROM subscription_tag st WHERE st.user_id = :userId AND st.tag_id = :tagId)) " +
            "AND (:from IS NULL OR a.publishedAt >= :from) " +
            "AND (:likedOnly = 0 OR g.likedAt IS NOT NULL) " +
            "AND (:savedOnly = 0 OR g.savedAt IS NOT NULL) " +
            "ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun search(
        userId: Long,
        match: String,
        feedId: Long?,
        tagId: Long?,
        from: Instant?,
        likedOnly: Boolean,
        savedOnly: Boolean,
        limit: Int,
        offset: Int,
    ): List<UserSearchRow>

    companion object {
        /**
         * The visibility rule of section 4.6 for article `a` and `:userId`: the user subscribes
         * to its feed (either status), or has engagement or an edition item for it.
         */
        const val VISIBLE =
            "(EXISTS (SELECT 1 FROM subscription vs WHERE vs.user_id = :userId AND vs.feed_id = a.feedId) " +
                "OR EXISTS (SELECT 1 FROM engagement vg WHERE vg.user_id = :userId AND vg.articleId = a.id) " +
                "OR EXISTS (SELECT 1 FROM edition_item vi JOIN edition ve ON ve.id = vi.editionId " +
                "WHERE ve.user_id = :userId AND vi.articleId = a.id))"

        /** Shared projection for [UserArticleRow] as `:userId` sees it; append a WHERE clause. */
        const val ROW_SELECT =
            "SELECT a.*, COALESCE(s.title_override, f.title) AS feedTitle, f.iconUrl AS feedIconUrl, s.status AS feedStatus, " +
                "g.openedAt AS openedAt, g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt " +
                "FROM article a JOIN feed f ON f.id = a.feedId " +
                "LEFT JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
                "LEFT JOIN engagement g ON g.articleId = a.id AND g.user_id = :userId"
    }
}

/** An article with its feed's name and one user's relation to it; engagement columns are null without a row. */
data class UserArticleRow(
    @Embedded val article: ArticleEntity,
    val feedTitle: String,
    val feedIconUrl: String?,
    /** The user's subscription status for the post's feed; null when they have none. */
    val feedStatus: FeedStatus?,
    val openedAt: Instant?,
    val readFraction: Float?,
    val readAt: Instant?,
    val savedAt: Instant?,
    val likedAt: Instant?,
)

data class UserSearchRow(
    @Embedded val row: UserArticleRow,
    val snippet: String?,
)

/** A feed-provided body; see [ArticleDao.feedBodiesSince]. */
data class FeedBodyRow(
    val articleId: Long,
    val blocksJson: String,
)

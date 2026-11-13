package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.time.Instant
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EnclosureKind

/**
 * Cross-table writes for the legacy archive import (4.9): global feeds and posts with their
 * bodies, search entries and signals, and the user's subscriptions, tags and import log. Owned
 * by W-B; the feed and article tables are W-A's, and these writes follow the same rules as
 * W-A's refresh (a post's search entry is kept in step with its body). Tests use these to seed
 * feeds and posts too.
 */
@Dao
abstract class ImportDao {
    // --- Global feeds -----------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFeed(feed: FeedEntity): Long

    @Query("SELECT id FROM feed WHERE url_key = :urlKey")
    abstract suspend fun feedIdByUrlKey(urlKey: String): Long?

    @Query("SELECT id FROM feed WHERE url = :url")
    abstract suspend fun feedIdByUrl(url: String): Long?

    // --- The user's subscriptions and tags --------------------------------------------------

    @Query("SELECT * FROM subscription WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun subscription(userId: Long, feedId: Long): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    /** A DISCOVERED subscription becomes a SUBSCRIBED one, added at [at]. Returns 1 when it did. */
    @Query(
        "UPDATE subscription SET status = 'SUBSCRIBED', added_at = :at, discovered_at = NULL " +
            "WHERE user_id = :userId AND feed_id = :feedId AND status = 'DISCOVERED'",
    )
    abstract suspend fun promoteDiscovered(userId: Long, feedId: Long, at: Instant): Int

    @Query("SELECT COUNT(*) > 0 FROM subscription WHERE user_id = :userId")
    abstract suspend fun hasSubscriptions(userId: Long): Boolean

    @Query("SELECT * FROM tag WHERE user_id = :userId AND name = :name COLLATE NOCASE")
    abstract suspend fun tagByName(userId: Long, name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun linkTag(link: SubscriptionTagEntity)

    /** Adds [names] (blank ones ignored) to the user's subscription to [feedId], creating tags as needed. */
    @Transaction
    open suspend fun addSubscriptionTags(userId: Long, feedId: Long, names: List<String>) {
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }) {
            val tag = tagByName(userId, name) ?: TagEntity(id = insertTag(TagEntity(userId = userId, name = name)), userId = userId, name = name)
            linkTag(SubscriptionTagEntity(userId, feedId, tag.id))
        }
    }

    // --- Global posts -----------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertArticleIgnore(article: ArticleEntity): Long

    @Query("SELECT * FROM article WHERE feedId = :feedId AND guid = :guid")
    abstract suspend fun articleByGuid(feedId: Long, guid: String): ArticleEntity?

    @Query("SELECT * FROM article WHERE id = :articleId")
    abstract suspend fun article(articleId: Long): ArticleEntity?

    @Query("SELECT source FROM article_content WHERE articleId = :articleId")
    abstract suspend fun contentSource(articleId: Long): ContentSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertContent(content: ArticleContentEntity)

    @Query("UPDATE article SET wordCount = :wordCount WHERE id = :articleId")
    protected abstract suspend fun updateWordCount(articleId: Long, wordCount: Int)

    @Query("DELETE FROM article_fts WHERE rowid = :articleId")
    protected abstract suspend fun deleteFts(articleId: Long)

    @Insert
    protected abstract suspend fun insertFts(row: ArticleFtsEntity)

    /**
     * Stores a post and indexes it for search. Returns the new article ID, or null when the
     * feed already has a post with this GUID (nothing is changed then). [blocksJson] is a
     * feed-provided body, if any, and [plainText] its text for search.
     */
    @Transaction
    open suspend fun insertNew(article: ArticleEntity, blocksJson: String? = null, plainText: String? = null): Long? {
        val id = insertArticleIgnore(article)
        if (id == -1L) return null
        if (blocksJson != null) upsertContent(ArticleContentEntity(id, blocksJson, ContentSource.FEED, article.firstSeenAt))
        insertFts(ftsRow(article.copy(id = id), plainText))
        return id
    }

    /** Stores or replaces a post's body, refreshing its search entry and word count. Does nothing for a missing post. */
    @Transaction
    open suspend fun storeContent(articleId: Long, blocksJson: String, plainText: String, wordCount: Int, source: ContentSource, storedAt: Instant) {
        val article = article(articleId) ?: return
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

    // --- Signals ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCategories(categories: List<ArticleCategoryEntity>)

    @Query("SELECT COUNT(*) FROM article_category WHERE articleId = :articleId")
    abstract suspend fun categoryCount(articleId: Long): Int

    @Query("SELECT * FROM article_traits WHERE articleId = :articleId")
    abstract suspend fun traits(articleId: Long): ArticleTraitsEntity?

    @Upsert
    abstract suspend fun upsertTraits(traits: ArticleTraitsEntity)

    @Query("UPDATE article_traits SET enclosure = :enclosure WHERE articleId = :articleId")
    abstract suspend fun updateEnclosure(articleId: Long, enclosure: EnclosureKind)

    // --- The import log ---------------------------------------------------------------------

    @Query("SELECT * FROM import_log WHERE user_id = :userId AND idempotency_key = :key")
    abstract suspend fun importLog(userId: Long, key: String): ImportLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertImportLog(log: ImportLogEntity)

    /** `GET /account`'s `hasData`, and the guard on `mode=replace`: a subscription or an edition. */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM subscription WHERE user_id = :userId) " +
            "OR EXISTS (SELECT 1 FROM edition WHERE user_id = :userId)",
    )
    abstract suspend fun hasData(userId: Long): Boolean
}

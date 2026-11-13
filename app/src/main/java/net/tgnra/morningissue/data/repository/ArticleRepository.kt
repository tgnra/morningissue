package net.tgnra.morningissue.data.repository

import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.ReaderArticle
import net.tgnra.morningissue.model.SearchQuery

/**
 * Reading articles and recording what the reader did with them. Called by the UI.
 * Implemented by `client.CachedArticleRepository`: reads come from the cache, and what the
 * reader does is written to the cache at once and sent to the server at the next sync.
 */
interface ArticleRepository {
    /**
     * The article for the reader view. Emits again when its body is stored or its Like, Save or
     * read state changes; emits null if the article does not exist.
     */
    fun observeReaderArticle(articleId: Long): Flow<ReaderArticle?>

    /** A snapshot of [observeReaderArticle]. */
    suspend fun getReaderArticle(articleId: Long): ReaderArticle?

    /** Records that the article was opened in the reader or browser. Only the first call counts. */
    suspend fun markOpened(articleId: Long)

    /**
     * Records how far the reader has scrolled, 0..1. The stored value never goes down. Reaching
     * `RankingConfig.readScrollFraction` marks the article read.
     */
    suspend fun setReadFraction(articleId: Long, fraction: Float) = setReadFraction(articleId, fraction, leaving = false)

    /** As above; [leaving] says the reader is closing the article, so the position is sent whatever it is. */
    suspend fun setReadFraction(articleId: Long, fraction: Float, leaving: Boolean)

    /** Marks the article read regardless of scroll position (for example by dwell time). */
    suspend fun markRead(articleId: Long)

    suspend fun setLiked(articleId: Long, liked: Boolean)

    suspend fun setSaved(articleId: Long, saved: Boolean)

    /** Saved articles, most recently saved first. */
    fun observeSaved(): Flow<List<ArticleSummary>>

    /** Liked articles, most recently liked first. */
    fun observeLiked(): Flow<List<ArticleSummary>>

    /** A source's posts, newest first, for its source page. */
    fun observeFeedArticles(feedId: Long, limit: Int = 200): Flow<List<ArticleSummary>>

    /**
     * Full-text search over every stored post, newest first. A blank [SearchQuery.text] returns
     * an empty list. Results carry a matching [ArticleSummary.snippet].
     */
    suspend fun search(query: SearchQuery): List<ArticleSummary>

    /**
     * Makes sure the article has a body to read: the cache's, or the server's (fetched again if
     * the storage budget trimmed it, or fetched from the post's page). The body arrives through
     * [observeReaderArticle]. False when there is none to be had. Never throws but cancellation.
     */
    suspend fun ensureFullText(articleId: Long): Boolean = true
}

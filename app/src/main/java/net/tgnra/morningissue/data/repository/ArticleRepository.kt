package net.tgnra.morningissue.data.repository

import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.ReaderArticle
import net.tgnra.morningissue.model.SearchQuery

/**
 * Reading articles and recording what the reader did with them. Called by the UI.
 * Implemented by [RoomArticleRepository]. Nothing here touches the network; the reader asks
 * `FullTextFetcher.ensureFullText` for missing body text and the flows below pick it up.
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
    suspend fun setReadFraction(articleId: Long, fraction: Float)

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
}

package net.tgnra.morningissue.client

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.api.ArticleSummaryDto
import net.tgnra.morningissue.api.SearchWhen
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.data.db.ArticleContentEntity
import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.ArticleRepository
import net.tgnra.morningissue.data.repository.FtsQuery
import net.tgnra.morningissue.data.repository.toSummary
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.sync.FullTextExtractor
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ReaderArticle
import net.tgnra.morningissue.model.ReadingTime
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SearchQuery
import net.tgnra.morningissue.api.SearchQuery as ServerSearchQuery

/**
 * Articles from the cache. What the reader does goes through [LocalEngagement]. A source page
 * and search ask the server when it can be reached, and keep to the cache when it cannot.
 */
class CachedArticleRepository(
    private val database: MorningIssueDatabase,
    private val engagement: LocalEngagement,
    private val connection: ServerConnection,
    private val clock: Clock,
) : ArticleRepository {
    private val articleDao = database.articleDao()
    private val writer = CacheWriter(database)

    /** Posts whose page the server was asked for in this process; a page that failed is not asked for at every open. */
    private val askedForPage: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun observeReaderArticle(articleId: Long): Flow<ReaderArticle?> =
        combine(articleDao.observeRow(articleId), articleDao.observeContent(articleId)) { row, content ->
            row?.toReaderArticle(content)
        }

    override suspend fun getReaderArticle(articleId: Long): ReaderArticle? = observeReaderArticle(articleId).first()

    override suspend fun markOpened(articleId: Long) = engagement.markOpened(articleId)

    override suspend fun setReadFraction(articleId: Long, fraction: Float, leaving: Boolean) =
        engagement.setReadFraction(articleId, fraction, leaving)

    override suspend fun markRead(articleId: Long) = engagement.markRead(articleId)

    override suspend fun setLiked(articleId: Long, liked: Boolean) = engagement.setLiked(articleId, liked)

    override suspend fun setSaved(articleId: Long, saved: Boolean) = engagement.setSaved(articleId, saved)

    override fun observeSaved(): Flow<List<ArticleSummary>> = articleDao.observeSaved().map(::toSummaries)

    override fun observeLiked(): Flow<List<ArticleSummary>> = articleDao.observeLiked().map(::toSummaries)

    /** The cache's posts at once; the server's newest are added to the cache when it answers. */
    override fun observeFeedArticles(feedId: Long, limit: Int): Flow<List<ArticleSummary>> = channelFlow {
        launch {
            online { connection.call { api -> writer.storeSummaries(api.feedArticles(feedId, limit = limit.coerceAtMost(200)).articles) } }
        }
        articleDao.observeForFeed(feedId, limit).map(::toSummaries).collect { send(it) }
    }

    override suspend fun search(query: SearchQuery): List<ArticleSummary> {
        val match = FtsQuery.fromUserInput(query.text) ?: return emptyList()
        val fromServer = online {
            val tagId = query.tag?.let { name -> database.tagDao().getByName(name)?.id }
            val results = connection.call { api ->
                api.search(
                    ServerSearchQuery(
                        q = query.text,
                        `when` = whenOf(query),
                        saved = query.savedOnly,
                        liked = query.likedOnly,
                        tagId = tagId,
                        feedId = query.feedId,
                        limit = query.limit,
                    ),
                ).results
            }
            writer.storeSummaries(results.map { it.article })
            results.map { it.article.toSummary(snippet = it.excerpt ?: it.article.snippet) }
        }
        if (fromServer != null) return fromServer
        return articleDao.search(
            match = match,
            feedId = query.feedId,
            tag = query.tag,
            from = query.from,
            to = query.to,
            likedOnly = query.likedOnly,
            savedOnly = query.savedOnly,
            limit = query.limit,
        ).map { it.row.toSummary(snippet = it.snippet) }
    }

    /** The server's search knows a week, a month or a year back; the filter's start is rounded out to one of them. */
    private fun whenOf(query: SearchQuery): SearchWhen {
        val from = query.from ?: return SearchWhen.ANY
        val days = Duration.between(from, clock.instant()).toDays()
        return when {
            days <= 8 -> SearchWhen.WEEK
            days <= 32 -> SearchWhen.MONTH
            days <= 370 -> SearchWhen.YEAR
            else -> SearchWhen.ANY
        }
    }

    override suspend fun ensureFullText(articleId: Long): Boolean {
        val content = articleDao.getContent(articleId)
        if (content != null && content.source != ContentSource.FEED) return true
        // Trimmed, or never stored: the server's copy.
        if (content == null) {
            online { connection.call { api -> writer.storeArticle(articleId, api.article(articleId).article) } }
        }
        val stored = articleDao.getContent(articleId)
        if (stored != null && stored.source != ContentSource.FEED) return true
        // The feed's text reads like the whole article: nothing to ask for.
        if (stored != null && FullTextExtractor.looksComplete(ContentBlocks.decode(stored.blocksJson))) return true
        // Only the feed's text, which may be a teaser: the server fetches the page, once a process.
        if (!askedForPage.add(articleId)) return stored != null
        online { connection.call { api -> writer.storeArticle(articleId, api.fetchFullText(articleId).article) } }
        return articleDao.getContent(articleId) != null
    }

    /** [block]'s result, or null when the server could not be reached or refused. */
    private suspend fun <T> online(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        null
    }

    private fun toSummaries(rows: List<ArticleRow>): List<ArticleSummary> = rows.map { it.toSummary() }

    private fun ArticleSummaryDto.toSummary(snippet: String?) = ArticleSummary(
        articleId = articleId,
        feedId = feedId,
        feedTitle = feedTitle,
        feedIconUrl = feedIconUrl,
        title = title,
        summary = summary,
        imageUrl = imageUrl,
        author = author,
        url = url,
        publishedAt = publishedAt,
        firstSeenAt = firstSeenAt,
        readMinutes = readMinutes,
        isOpened = isOpened,
        isRead = isRead,
        isLiked = isLiked,
        isSaved = isSaved,
        snippet = snippet,
        feedSubscribed = feedSubscribed,
    )

    private fun ArticleRow.toReaderArticle(content: ArticleContentEntity?): ReaderArticle {
        val stored = content?.let { ContentBlocks.decode(it.blocksJson) }.orEmpty()
        val blocks = stored.ifEmpty {
            article.summary?.takeIf { it.isNotBlank() }
                ?.let { listOf<ContentBlock>(ContentBlock.Paragraph(RichText(it))) }
                .orEmpty()
        }
        return ReaderArticle(
            articleId = article.id,
            feedId = article.feedId,
            feedTitle = feedTitle,
            title = article.title,
            author = article.author,
            url = article.url,
            imageUrl = article.imageUrl,
            publishedAt = article.publishedAt,
            wordCount = article.wordCount,
            readMinutes = ReadingTime.minutes(article.wordCount),
            summary = article.summary,
            blocks = blocks,
            contentSource = content?.source?.takeIf { stored.isNotEmpty() },
            isLiked = likedAt != null,
            isSaved = savedAt != null,
            readFraction = readFraction ?: 0f,
            feedSubscribed = feedStatus == FeedStatus.SUBSCRIBED,
        )
    }
}

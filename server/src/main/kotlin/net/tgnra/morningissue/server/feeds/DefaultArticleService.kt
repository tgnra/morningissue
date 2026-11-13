package net.tgnra.morningissue.server.feeds

import java.time.Clock
import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.ArticleFeedStatus
import net.tgnra.morningissue.api.ArticleResponse
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.FullTextState
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchResponse
import net.tgnra.morningissue.api.SearchResultDto
import net.tgnra.morningissue.api.SearchWhen
import net.tgnra.morningissue.data.repository.FtsQuery
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.ArticleService
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.server.service.SearchService

/**
 * Articles and the saved and Liked shelves (4.6). An article is visible to a user who subscribes
 * to its feed (either status), or has engagement or an edition item for it; anything else is
 * `not_found`. Never writes engagement.
 */
class DefaultArticleService(
    database: ServerDatabase,
    private val fullText: FullTextService,
) : ArticleService {
    private val articleDao = database.articleDao()

    override suspend fun article(userId: Long, articleId: Long): ArticleResponse = response(userId, articleId, null)

    override suspend fun fetchFullText(userId: Long, articleId: Long): ArticleResponse {
        if (!articleDao.isVisible(userId, articleId)) throw ApiFailure.notFound("No such article")
        return response(userId, articleId, fullText.ensure(articleId))
    }

    private suspend fun response(userId: Long, articleId: Long, state: FullTextState?): ArticleResponse {
        if (!articleDao.isVisible(userId, articleId)) throw ApiFailure.notFound("No such article")
        val row = articleDao.row(userId, articleId) ?: throw ApiFailure.notFound("No such article")
        val status = when (row.feedStatus) {
            FeedStatus.SUBSCRIBED -> ArticleFeedStatus.SUBSCRIBED
            FeedStatus.DISCOVERED -> ArticleFeedStatus.DISCOVERED
            null -> ArticleFeedStatus.NONE
        }
        return ArticleResponse(row.toBundleArticle(articleDao.getContent(articleId)), status, state)
    }

    override suspend fun shelf(userId: Long, shelf: String, before: Instant?, limit: Int): ArticlesResponse {
        val upTo = before ?: END_OF_TIME
        val count = limit.coerceIn(1, MAX_LIMIT)
        val rows = when (shelf) {
            ApiQuery.SHELF_SAVED -> articleDao.saved(userId, upTo, count)
            ApiQuery.SHELF_LIKED -> articleDao.liked(userId, upTo, count)
            else -> throw ApiFailure.badRequest("Unknown shelf \"$shelf\"")
        }
        return ArticlesResponse(rows.map { it.toSummary() })
    }
}

/** `GET /search` (4.6): `FtsQuery` and FTS4 `snippet`, restricted to the articles the user can see. */
class DefaultSearchService(
    database: ServerDatabase,
    private val clock: Clock,
) : SearchService {
    private val articleDao = database.articleDao()

    override suspend fun search(userId: Long, query: SearchQuery): SearchResponse {
        val match = FtsQuery.fromUserInput(query.q) ?: return SearchResponse(emptyList())
        val now = clock.instant()
        val from = when (query.`when`) {
            SearchWhen.ANY -> null
            SearchWhen.WEEK -> now.minus(Duration.ofDays(7))
            SearchWhen.MONTH -> now.minus(Duration.ofDays(30))
            SearchWhen.YEAR -> now.minus(Duration.ofDays(365))
        }
        val rows = articleDao.search(
            userId = userId,
            match = match,
            feedId = query.feedId,
            tagId = query.tagId,
            from = from,
            likedOnly = query.liked,
            savedOnly = query.saved,
            limit = query.limit.coerceIn(1, MAX_LIMIT),
            offset = query.offset.coerceAtLeast(0),
        )
        return SearchResponse(rows.map { SearchResultDto(it.row.toSummary(snippet = it.snippet), excerpt = it.snippet) })
    }
}

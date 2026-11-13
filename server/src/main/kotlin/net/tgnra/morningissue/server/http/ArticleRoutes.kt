package net.tgnra.morningissue.server.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchWhen
import net.tgnra.morningissue.server.service.ApiFailure

/** 4.6: articles, the saved and liked shelves, search. Engagement is never written here. */
internal fun Route.articleRoutes(s: ServerServices) {
    get(ApiPaths.ARTICLE, Access.PHONE) { p ->
        call.respond(s.articles.article(p.userId, call.longId()))
    }
    post(ApiPaths.ARTICLE_FULLTEXT, Access.PHONE) { p ->
        call.respond(s.articles.fetchFullText(p.userId, call.longId()))
    }
    get(ApiPaths.ARTICLES, Access.PHONE) { p ->
        val shelf = call.requiredQuery(ApiQuery.SHELF)
        if (shelf != ApiQuery.SHELF_SAVED && shelf != ApiQuery.SHELF_LIKED) {
            throw ApiFailure.badRequest("\"shelf\" must be ${ApiQuery.SHELF_SAVED} or ${ApiQuery.SHELF_LIKED}")
        }
        val before = call.queryInstant(ApiQuery.BEFORE)
        val limit = call.queryLimit(ApiQuery.LIMIT, DEFAULT_SHELF, MAX_SHELF)
        call.respond(s.articles.shelf(p.userId, shelf, before, limit))
    }
    get(ApiPaths.SEARCH, Access.PHONE) { p ->
        val q = call.requiredQuery(ApiQuery.Q)
        val offset = call.queryInt(ApiQuery.OFFSET) ?: 0
        if (offset < 0) throw ApiFailure.badRequest("\"offset\" must not be negative")
        val query = SearchQuery(
            q = q,
            `when` = call.queryEnum<SearchWhen>(ApiQuery.WHEN) ?: SearchWhen.ANY,
            saved = call.queryBoolean(ApiQuery.SAVED) ?: false,
            liked = call.queryBoolean(ApiQuery.LIKED) ?: false,
            tagId = call.queryLong(ApiQuery.TAG_ID),
            feedId = call.queryLong(ApiQuery.FEED_ID),
            limit = call.queryLimit(ApiQuery.LIMIT, DEFAULT_SEARCH, MAX_PAGE),
            offset = offset,
        )
        call.respond(s.search.search(p.userId, query))
    }
}

private const val DEFAULT_SHELF = 100
private const val MAX_SHELF = 500
private const val DEFAULT_SEARCH = 50

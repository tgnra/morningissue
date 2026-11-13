package net.tgnra.morningissue.server.http

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiContentTypes
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.server.service.ApiFailure

/** 4.5: feeds, subscriptions, OPML and health. */
internal fun Route.feedRoutes(s: ServerServices) {
    get(ApiPaths.FEEDS, Access.PHONE) { p ->
        call.respond(s.feeds.feeds(p.userId))
    }
    post(ApiPaths.FEEDS_DISCOVER, Access.PHONE) { p ->
        call.respond(s.feeds.discover(p.userId, call.receiveJson<DiscoverFeedRequest>()))
    }
    post(ApiPaths.FEEDS, Access.PHONE) { p ->
        val request = call.receiveJson<AddFeedRequest>()
        if (request.url.isBlank()) throw ApiFailure.badRequest("url is required")
        val outcome = s.feeds.add(p.userId, request)
        call.respond(if (outcome.created) HttpStatusCode.Created else HttpStatusCode.OK, outcome.feed)
    }
    patch(ApiPaths.FEED, Access.PHONE) { p ->
        val id = call.longId()
        call.respond(s.feeds.patch(p.userId, id, call.receiveJson<FeedPatch>()))
    }
    delete(ApiPaths.FEED, Access.PHONE) { p ->
        s.feeds.unsubscribe(p.userId, call.longId())
        call.respond(HttpStatusCode.NoContent)
    }
    get(ApiPaths.FEED_ARTICLES, Access.PHONE) { p ->
        val id = call.longId()
        val before = call.queryInstant(ApiQuery.BEFORE)
        val limit = call.queryLimit(ApiQuery.LIMIT, DEFAULT_FEED_ARTICLES, MAX_PAGE)
        call.respond(s.feeds.articles(p.userId, id, before, limit))
    }
    get(ApiPaths.FEEDS_HEALTH, Access.PHONE) { p ->
        call.respond(s.feeds.health(p.userId))
    }
    post(ApiPaths.FEED_HEALTH_DISMISS, Access.PHONE) { p ->
        s.feeds.dismissFlag(p.userId, call.longId())
        call.respond(HttpStatusCode.NoContent)
    }
    post(ApiPaths.FEED_HEALTH_ACKNOWLEDGE, Access.PHONE) { p ->
        s.feeds.acknowledgeMove(p.userId, call.longId())
        call.respond(HttpStatusCode.NoContent)
    }
    post(ApiPaths.FEED_HEALTH_RETRY, Access.PHONE) { p ->
        call.respond(s.feeds.retry(p.userId, call.longId()))
    }
    post(ApiPaths.OPML, Access.PHONE) { p ->
        val options = call.application.attributes[OPTIONS_KEY]
        val body = call.receiveBytes(options.opmlMaxBytes)
        if (body.isEmpty()) throw ApiFailure.badRequest("The OPML file is empty")
        call.respond(HttpStatusCode.Accepted, s.feeds.importOpml(p.userId, body))
    }
    get(ApiPaths.OPML, Access.PHONE) { p ->
        val opml = s.feeds.exportOpml(p.userId)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "morning-issue.opml").toString(),
        )
        call.respondText(opml, ContentType.parse(ApiContentTypes.OPML).withParameter("charset", "utf-8"))
    }
}

private const val DEFAULT_FEED_ARTICLES = 50

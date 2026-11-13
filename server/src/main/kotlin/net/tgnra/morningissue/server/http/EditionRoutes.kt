package net.tgnra.morningissue.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.server.service.JobStart

/** 4.3: editions, bundles, the saved shelf, engagement, stand, print, Extra and jobs. */
internal fun Route.editionRoutes(s: ServerServices) {
    get(ApiPaths.EDITIONS, Access.PHONE) { p ->
        val before = call.queryInt(ApiQuery.BEFORE)
        val limit = call.queryLimit(ApiQuery.LIMIT, ApiLimits.DEFAULT_EDITIONS_PAGE, MAX_PAGE)
        call.respond(s.editions.editions(p.userId, before, limit))
    }
    get(ApiPaths.EDITION_BUNDLE, Access.KOBO) { p ->
        s.sync.checkGate(p.userId, p.deviceId, call.eventsThrough())
        val id = call.longId()
        call.respondVersioned(EditionBundleDto.serializer(), s.editions.bundle(p.userId, id))
    }
    get(ApiPaths.SAVED, Access.KOBO) { p ->
        s.sync.checkGate(p.userId, p.deviceId, call.eventsThrough())
        call.respondVersioned(SavedBundleDto.serializer(), s.editions.saved(p.userId))
    }
    get(ApiPaths.ENGAGEMENT, Access.PHONE) { p ->
        val since = call.queryLong(ApiQuery.SINCE) ?: 0
        call.respond(s.editions.engagement(p.userId, since))
    }
    post(ApiPaths.EDITION_DESK_NOTICE, Access.PHONE) { p ->
        call.respond(s.editions.deskNotice(p.userId, call.longId()))
    }
    get(ApiPaths.STAND, Access.PHONE) { p ->
        call.respond(s.stand.stand(p.userId))
    }
    post(ApiPaths.PRINT, Access.PHONE) { p ->
        call.respondJob(s.jobs.print(p.userId))
    }
    post(ApiPaths.EDITIONS_EXTRA, Access.PHONE) { p ->
        call.respondJob(s.jobs.extra(p.userId, call.receiveJson<ExtraRequest>()))
    }
    get(ApiPaths.JOB, Access.PHONE) { p ->
        call.respond(s.jobs.job(p.userId, call.stringId()))
    }
}

/** A page of back issues or articles is at most this long. */
internal const val MAX_PAGE = 200

/** `202` for a new job, `200` for the one already running. */
internal suspend fun ApplicationCall.respondJob(start: JobStart) {
    respond(if (start.alreadyRunning) HttpStatusCode.OK else HttpStatusCode.Accepted, JobStartedResponse(start.jobId))
}

/**
 * Answers [value] as `ApiJson` with an `ETag` of the SHA-256 of those bytes, and `304` when the
 * client's `If-None-Match` already names it (section 4.10; optional for clients).
 */
internal suspend fun <T> ApplicationCall.respondVersioned(serializer: KSerializer<T>, value: T) {
    val text = ApiJson.json.encodeToString(serializer, value)
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val etag = "\"" + digest.joinToString("") { "%02x".format(it) } + "\""
    response.header(HttpHeaders.ETag, etag)
    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim() == etag || it.trim() == "*" }) {
        respond(HttpStatusCode.NotModified)
        return
    }
    respondText(text, ContentType.Application.Json.withParameter("charset", "utf-8"))
}

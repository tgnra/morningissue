package net.tgnra.morningissue.server.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.api.ApiError

/** 4.2: event upload and the (gated) manifest. Phones and Kobos alike. */
internal fun Route.syncRoutes(s: ServerServices) {
    post(ApiPaths.SYNC_EVENTS, Access.KOBO) { p ->
        val request = call.receiveJson<EventsRequest>()
        if (request.events.size > ApiLimits.MAX_EVENTS_PER_REQUEST) {
            throw ApiFailure(ApiError.TOO_LARGE, "At most ${ApiLimits.MAX_EVENTS_PER_REQUEST} events per request")
        }
        call.respond(s.sync.postEvents(p.userId, p.deviceId, request))
    }
    get(ApiPaths.SYNC_MANIFEST, Access.KOBO) { p ->
        s.sync.checkGate(p.userId, p.deviceId, call.eventsThrough())
        val editions = call.queryLimit(ApiQuery.EDITIONS, ApiLimits.DEFAULT_MANIFEST_EDITIONS, ApiLimits.MAX_MANIFEST_EDITIONS)
        call.respond(s.sync.manifest(p.userId, p.deviceId, editions))
    }
}

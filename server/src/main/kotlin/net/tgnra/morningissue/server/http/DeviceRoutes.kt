package net.tgnra.morningissue.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.tgnra.morningissue.api.ApiContentTypes
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.server.service.ApiFailure

/** 4.8: devices, Kobo pairing and the Kobo's own endpoints. */
internal fun Route.deviceRoutes(s: ServerServices) {
    get(ApiPaths.DEVICES, Access.PHONE) { p ->
        call.respond(s.devices.devices(p.userId, p.deviceId))
    }
    post(ApiPaths.DEVICES_KOBO, Access.PHONE) { p ->
        call.respond(HttpStatusCode.Created, s.devices.pairKobo(p.userId, call.receiveJson<PairKoboRequest>()))
    }
    delete(ApiPaths.DEVICE, Access.PHONE) { p ->
        s.devices.revoke(p.userId, call.stringId())
        call.respond(HttpStatusCode.NoContent)
    }
    get(ApiPaths.KOBO_HELLO, Access.KOBO) { p ->
        call.respond(s.devices.koboHello(p.userId, p.deviceId))
    }
    post(ApiPaths.KOBO_IMAGES, Access.KOBO) { p ->
        s.sync.checkGate(p.userId, p.deviceId, call.eventsThrough())
        val options = call.application.attributes[OPTIONS_KEY]
        val request = call.receiveJson<KoboImagesRequest>(options.koboImagesMaxBytes)
        if (request.urls.size > ApiLimits.MAX_IMAGE_URLS) {
            throw ApiFailure(ApiError.TOO_LARGE, "At most ${ApiLimits.MAX_IMAGE_URLS} URLs per request")
        }
        if (request.width !in ApiLimits.MIN_KOBO_WIDTH..ApiLimits.MAX_KOBO_WIDTH) {
            throw ApiFailure.badRequest("width must be ${ApiLimits.MIN_KOBO_WIDTH}..${ApiLimits.MAX_KOBO_WIDTH}")
        }
        call.respondOutputStream(ContentType.parse(ApiContentTypes.ZIP)) {
            s.koboImages.writeZip(p.userId, request, this)
        }
    }
    get(ApiPaths.KOBO_CONFIG) {
        call.respond(s.devices.koboConfig())
    }
}

package net.tgnra.morningissue.server.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.time.DateTimeException
import java.time.ZoneId
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.server.service.ApiFailure

/** 4.4: the server-side settings. */
internal fun Route.settingsRoutes(s: ServerServices) {
    get(ApiPaths.SETTINGS, Access.PHONE) { p ->
        call.respond(s.settings.settings(p.userId))
    }
    patch(ApiPaths.SETTINGS, Access.PHONE) { p ->
        val patch = call.receiveJson<SettingsPatch>()
        patch.timeZone?.let { zone ->
            try {
                ZoneId.of(zone)
            } catch (_: DateTimeException) {
                throw ApiFailure.badRequest("\"$zone\" is not a time zone ID, such as Europe/London")
            }
        }
        call.respond(s.settings.patch(p.userId, patch))
    }
    put(ApiPaths.SETTINGS_RELEASE, Access.PHONE) { p ->
        call.respond(s.settings.setReleaseTimes(p.userId, call.receiveJson<ReleaseTimesRequest>()))
    }
}

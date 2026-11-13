package net.tgnra.morningissue.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.HealthResponse
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.server.SERVER_VERSION

/** 4.1: health, login, password, logout, account. */
internal fun Route.authRoutes(s: ServerServices) {
    get(ApiPaths.HEALTH) {
        call.respond(HealthResponse(version = SERVER_VERSION))
    }
    post(ApiPaths.LOGIN) {
        call.respond(s.auth.login(call.receiveJson<LoginRequest>()))
    }
    post(ApiPaths.PASSWORD, Access.ACCOUNT) { p ->
        s.auth.changePassword(p.userId, call.receiveJson<ChangePasswordRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    post(ApiPaths.LOGOUT, Access.ACCOUNT) { p ->
        s.auth.logout(p.userId, p.deviceId)
        call.respond(HttpStatusCode.NoContent)
    }
    get(ApiPaths.ACCOUNT, Access.ACCOUNT) { p ->
        call.respond(s.auth.account(p.userId))
    }
}

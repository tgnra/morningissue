package net.tgnra.morningissue.server.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.AuthService
import net.tgnra.morningissue.server.service.DeviceService
import net.tgnra.morningissue.server.service.Principal

/** Who may call an endpoint (section 4, "Auth"). */
enum class Access {
    /** A phone token, once the first password is changed. */
    PHONE,

    /** A phone token, even while the password must be changed: `/auth/password`, `/auth/logout`, `/account`. */
    ACCOUNT,

    /** A phone or a Kobo token: `/sync/…`, `/editions/{id}/bundle`, `/saved`, `/kobo/…`. */
    KOBO,
}

/** Resolves the bearer token of a call and checks it against an endpoint's [Access]. */
internal class Authorizer(private val auth: AuthService, private val devices: DeviceService) {
    suspend fun authorize(call: ApplicationCall, access: Access): Principal {
        val token = bearer(call) ?: throw ApiFailure(ApiError.UNAUTHENTICATED, "Log in first: this needs an Authorization: Bearer token")
        val principal = auth.authenticate(token)
            ?: throw if (access == Access.KOBO) {
                // What a Kobo polls for while its pairing code is shown.
                ApiFailure(ApiError.UNKNOWN, "This device is not paired with an account, or was removed")
            } else {
                ApiFailure(ApiError.UNAUTHENTICATED, "This token is not valid; log in again")
            }
        if (principal.kind == DeviceKind.KOBO && access != Access.KOBO) {
            throw ApiFailure(ApiError.FORBIDDEN_FOR_DEVICE, "A Kobo cannot call this")
        }
        if (principal.mustChangePassword && access != Access.ACCOUNT) {
            throw ApiFailure(ApiError.PASSWORD_CHANGE_REQUIRED, "Change the generated password first")
        }
        devices.markSeen(principal.deviceId)
        return principal
    }

    private fun bearer(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        val space = header.indexOf(' ')
        if (space < 0 || !header.substring(0, space).equals("Bearer", ignoreCase = true)) return null
        return header.substring(space + 1).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        val KEY = AttributeKey<Authorizer>("MorningIssueAuthorizer")
    }
}

/** An endpoint that needs a token with [access]; [body] gets the caller. */
internal fun Route.endpoint(
    method: HttpMethod,
    path: String,
    access: Access,
    body: suspend RoutingContext.(Principal) -> Unit,
): Route = route(path) {
    method(method) {
        handle {
            val principal = call.application.attributes[Authorizer.KEY].authorize(call, access)
            body(principal)
        }
    }
}

internal fun Route.get(path: String, access: Access, body: suspend RoutingContext.(Principal) -> Unit) = endpoint(HttpMethod.Get, path, access, body)
internal fun Route.post(path: String, access: Access, body: suspend RoutingContext.(Principal) -> Unit) = endpoint(HttpMethod.Post, path, access, body)
internal fun Route.put(path: String, access: Access, body: suspend RoutingContext.(Principal) -> Unit) = endpoint(HttpMethod.Put, path, access, body)
internal fun Route.patch(path: String, access: Access, body: suspend RoutingContext.(Principal) -> Unit) = endpoint(HttpMethod.Patch, path, access, body)
internal fun Route.delete(path: String, access: Access, body: suspend RoutingContext.(Principal) -> Unit) = endpoint(HttpMethod.Delete, path, access, body)

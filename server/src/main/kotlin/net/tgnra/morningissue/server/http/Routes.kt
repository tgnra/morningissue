package net.tgnra.morningissue.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiErrorBody
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.server.service.ApiFailure
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** The code of `501` answers from a service that is not wired yet. Not one of the API's codes: clients never see it from a full server. */
const val NOT_IMPLEMENTED = "not_implemented"

/** Code of `500` answers. */
const val INTERNAL = "internal"

private val log = LoggerFactory.getLogger("net.tgnra.morningissue.server.http")

/**
 * The API under `/api/v1` (section 4.12): JSON, error mapping, body limits and the route files
 * for each area. Every route depends only on [services].
 */
fun Application.installApi(services: ServerServices, options: HttpOptions) {
    attributes.put(Authorizer.KEY, Authorizer(services.auth, services.devices))
    attributes.put(OPTIONS_KEY, options)

    install(ContentNegotiation) { json(ApiJson.json) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith(ApiPaths.BASE) }
    }
    install(StatusPages) {
        exception<ApiFailure> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.status), ApiErrorBody(e.code, e.message ?: e.code, e.acked))
        }
        exception<NotImplementedError> { call, e ->
            call.respond(HttpStatusCode.NotImplemented, ApiErrorBody(NOT_IMPLEMENTED, e.message ?: "Not implemented yet"))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorBody(ApiError.BAD_REQUEST, e.message ?: "Bad request"))
        }
        exception<Throwable> { call, e ->
            log.error("{} {} failed", call.request.local.method.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorBody(INTERNAL, "Something went wrong on the server"))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiErrorBody(ApiError.NOT_FOUND, "No such endpoint: ${call.request.path()}"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.MethodNotAllowed, ApiErrorBody(ApiError.BAD_REQUEST, "Method not allowed here"))
        }
    }
    install(BodyLimit)

    routing {
        route(ApiPaths.BASE) {
            authRoutes(services)
            syncRoutes(services)
            editionRoutes(services)
            settingsRoutes(services)
            feedRoutes(services)
            articleRoutes(services)
            discoveryRoutes(services)
            deviceRoutes(services)
            importRoutes(services, options)
        }
    }
}

/** The body limit of a path (section 4.11): 1 MB except the uploads. */
internal fun bodyLimitOf(call: ApplicationCall, options: HttpOptions): Long = when (call.request.path()) {
    ApiPaths.BASE + ApiPaths.IMPORT -> options.importMaxBytes
    ApiPaths.BASE + ApiPaths.OPML -> options.opmlMaxBytes
    ApiPaths.BASE + ApiPaths.KOBO_IMAGES -> options.koboImagesMaxBytes
    else -> options.maxBodyBytes
}

/** Refuses a declared `Content-Length` over the path's limit before any route reads the body. */
private val BodyLimit = createApplicationPlugin("MorningIssueBodyLimit") {
    onCall { call ->
        checkDeclaredLength(call, bodyLimitOf(call, call.application.attributes[OPTIONS_KEY]))
    }
}

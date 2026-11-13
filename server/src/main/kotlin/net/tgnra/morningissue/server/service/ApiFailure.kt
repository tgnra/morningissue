package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.ApiError

/**
 * How a service refuses a request: the HTTP layer answers with [status] and an
 * `ApiErrorBody(code, message, acked)`. [code] is one of the [ApiError] codes; [acked] is set
 * only for `events_pending`.
 */
class ApiFailure(val code: String, message: String, val acked: Long? = null) : RuntimeException(message) {
    val status: Int get() = ApiError.statusOf(code)

    companion object {
        fun notFound(what: String = "Not found") = ApiFailure(ApiError.NOT_FOUND, what)
        fun badRequest(message: String) = ApiFailure(ApiError.BAD_REQUEST, message)
        fun eventsPending(acked: Long) = ApiFailure(ApiError.EVENTS_PENDING, "Upload the events above $acked first", acked)
    }
}

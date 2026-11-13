package net.tgnra.morningissue.api

import kotlinx.serialization.Serializable

/**
 * Every error answer: `{"error": "<code>", "message": "<human text>"}`. [acked] is set only on
 * `409 events_pending`, with the device's `acked_seq`.
 */
@Serializable
data class ApiErrorBody(
    /** One of the [ApiError] codes. */
    val error: String,
    /** For a person to read; clients may show it but must not parse it. */
    val message: String,
    val acked: Long? = null,
)

/** The error codes and the HTTP status each goes with (section 4, the status table). */
object ApiError {
    // 400
    const val BAD_REQUEST = "bad_request"
    const val EVENTS_THROUGH_REQUIRED = "events_through_required"
    const val WEAK_PASSWORD = "weak_password"

    // 401
    const val UNAUTHENTICATED = "unauthenticated"

    /** A Kobo token that is not paired yet, or was revoked. */
    const val UNKNOWN = "unknown"
    const val INVALID_CREDENTIALS = "invalid_credentials"

    // 403
    const val PASSWORD_CHANGE_REQUIRED = "password_change_required"

    /** A Kobo token calling a phone-only endpoint. */
    const val FORBIDDEN_FOR_DEVICE = "forbidden_for_device"
    const val ACCOUNT_DISABLED = "account_disabled"

    // 404
    const val NOT_FOUND = "not_found"

    // 409
    /** The body also has `acked`. */
    const val EVENTS_PENDING = "events_pending"
    const val CONFLICT = "conflict"

    // 413
    const val TOO_LARGE = "too_large"

    // 422
    /** `POST /feeds/discover`: the address answered but offers no feed. */
    const val NOT_A_FEED = "not_a_feed"

    // 429
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"

    // 503
    /** An import is already running for the account. */
    const val BUSY = "busy"

    /** The HTTP status [code] is sent with; 400 for a code this version does not know. */
    fun statusOf(code: String): Int = when (code) {
        BAD_REQUEST, EVENTS_THROUGH_REQUIRED, WEAK_PASSWORD -> 400
        UNAUTHENTICATED, UNKNOWN, INVALID_CREDENTIALS -> 401
        PASSWORD_CHANGE_REQUIRED, FORBIDDEN_FOR_DEVICE, ACCOUNT_DISABLED -> 403
        NOT_FOUND -> 404
        EVENTS_PENDING, CONFLICT -> 409
        TOO_LARGE -> 413
        NOT_A_FEED -> 422
        TOO_MANY_ATTEMPTS -> 429
        BUSY -> 503
        else -> 400
    }
}

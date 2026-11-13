package net.tgnra.morningissue.server.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.server.service.ApiFailure

/** `413 too_large`. */
internal fun tooLarge(limit: Long) = ApiFailure(ApiError.TOO_LARGE, "The request body is over ${limit / 1024} KB")

/** Refuses a call whose `Content-Length` is over [limit] before reading anything. */
internal fun checkDeclaredLength(call: ApplicationCall, limit: Long) {
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return
    if (declared > limit) throw tooLarge(limit)
}

/** The whole body, at most [limit] bytes (`413 too_large` past it, whatever `Content-Length` said). */
internal suspend fun ApplicationCall.receiveBytes(limit: Long): ByteArray {
    checkDeclaredLength(this, limit)
    val out = java.io.ByteArrayOutputStream()
    copyBody(receiveChannel(), out, limit)
    return out.toByteArray()
}

/** Copies [channel] to [out], failing with `413` once more than [limit] bytes have come. Returns the byte count. */
internal suspend fun copyBody(channel: ByteReadChannel, out: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = channel.readAvailable(buffer, 0, buffer.size)
        if (n < 0) break
        if (n == 0) continue
        total += n
        if (total > limit) throw tooLarge(limit)
        out.write(buffer, 0, n)
    }
    return total
}

/** A JSON body as [T], at most [limit] bytes; `400 bad_request` if it does not read as one. */
internal suspend inline fun <reified T> ApplicationCall.receiveJson(limit: Long = maxBody): T {
    val text = receiveBytes(limit).toString(Charsets.UTF_8)
    if (text.isBlank()) throw ApiFailure.badRequest("A JSON body is required")
    return try {
        ApiJson.json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw ApiFailure.badRequest("The JSON body is not valid: ${e.message?.lineSequence()?.firstOrNull()}")
    } catch (e: IllegalArgumentException) {
        throw ApiFailure.badRequest("The JSON body is not valid: ${e.message?.lineSequence()?.firstOrNull()}")
    }
}

/** The configured JSON body limit of this application. */
internal val ApplicationCall.maxBody: Long get() = application.attributes[OPTIONS_KEY].maxBodyBytes

internal val OPTIONS_KEY = io.ktor.util.AttributeKey<HttpOptions>("MorningIssueHttpOptions")

/** The `{id}` path parameter as a number; `400` if it is not one. */
internal fun ApplicationCall.longId(): Long =
    parameters[ApiPaths.ID]?.toLongOrNull() ?: throw ApiFailure.badRequest("The id in the path must be a number")

/** The `{id}` path parameter as text. */
internal fun ApplicationCall.stringId(): String =
    parameters[ApiPaths.ID]?.takeIf { it.isNotBlank() } ?: throw ApiFailure.badRequest("The path needs an id")

internal fun ApplicationCall.queryString(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotEmpty() }

internal fun ApplicationCall.requiredQuery(name: String): String =
    queryString(name) ?: throw ApiFailure.badRequest("The query parameter \"$name\" is required")

internal fun ApplicationCall.queryLong(name: String): Long? =
    queryString(name)?.let { it.toLongOrNull() ?: throw ApiFailure.badRequest("\"$name\" must be a number") }

internal fun ApplicationCall.queryInt(name: String): Int? =
    queryString(name)?.let { it.toIntOrNull() ?: throw ApiFailure.badRequest("\"$name\" must be a number") }

/** A page size: [default] when absent, `400` outside 1..[max]. */
internal fun ApplicationCall.queryLimit(name: String, default: Int, max: Int): Int {
    val value = queryInt(name) ?: return default
    if (value !in 1..max) throw ApiFailure.badRequest("\"$name\" must be 1..$max")
    return value
}

internal fun ApplicationCall.queryBoolean(name: String): Boolean? = queryString(name)?.let {
    when (it.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> throw ApiFailure.badRequest("\"$name\" must be true or false")
    }
}

internal fun ApplicationCall.queryInstant(name: String): Instant? = queryString(name)?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        throw ApiFailure.badRequest("\"$name\" must be an ISO-8601 instant, such as 2026-11-11T06:30:00Z")
    }
}

internal inline fun <reified E : Enum<E>> ApplicationCall.queryEnum(name: String): E? = queryString(name)?.let { raw ->
    enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw ApiFailure.badRequest("\"$name\" must be one of ${enumValues<E>().joinToString("|") { it.name }}")
}

/** The `X-Events-Through` header: null when absent (the gate answers `events_through_required`), `400` when not a number. */
internal fun ApplicationCall.eventsThrough(): Long? {
    val raw = request.headers[EVENTS_THROUGH_HEADER]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val value = raw.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw ApiFailure(ApiError.EVENTS_THROUGH_REQUIRED, "$EVENTS_THROUGH_HEADER must be a whole number, 0 or more")
    return value
}

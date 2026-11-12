package net.tgnra.morningissue.relay

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The relay answered with an error. [code] is its `{"error": …}`, when it sent one. */
open class RelayHttpException(val status: Int, val code: String?) : IOException("relay answered $status${code?.let { " ($it)" } ?: ""}")

/** A `401`: [code] is `skew`, `unknown`, `signature` or `replay`. */
class RelayUnauthorizedException(code: String?) : RelayHttpException(401, code) {
    val isUnknown: Boolean get() = code == "unknown"
}

@Serializable
data class GroupRequest(val ownerKey: String)

@Serializable
data class GroupResponse(val group: String = "", val created: Boolean = false)

@Serializable
data class DevicesRequest(val devices: List<String>)

@Serializable
data class DevicesResponse(val devices: List<String> = emptyList())

@Serializable
data class PublishRequest(val keys: Map<String, String>)

@Serializable
data class GroupStatus(
    @SerialName("package") val currentPackage: StatusPackage? = null,
    val devices: List<StatusDevice> = emptyList(),
)

@Serializable
data class StatusPackage(val id: String, val size: Long = 0, val publishedAt: String? = null, val fetchedBy: List<String> = emptyList())

@Serializable
data class StatusDevice(val kid: String, val lastSeenAt: String? = null, val pendingChanges: Int = 0)

@Serializable
data class ChangesList(val changes: List<ChangeInfo> = emptyList())

@Serializable
data class ChangeInfo(val id: String, val kid: String, val batch: String, val size: Long = 0, val receivedAt: String? = null)

@Serializable
data class DoneRequest(val changes: List<String>)

@Serializable
data class DoneResponse(val deletedChanges: Int = 0, val packageDeleted: Boolean = false)

@Serializable
private data class ErrorBody(val error: String? = null)

/**
 * The phone's side of the relay API (`docs/relay-sync.md`, "Owner"), every request signed with
 * the owner key. Runs on the app's shared OkHttp client, with longer timeouts for the package
 * upload. A `401` with `skew` or `replay` is retried once, re-signed with a fresh `ts` (the
 * offset taken from the relay's `Date` for `skew`; [Timestamps] gives every repeat a fresh `ts`); other errors are thrown as [RelayHttpException].
 */
class RelayClient(
    baseUrl: String,
    private val ownerKey: ByteArray,
    client: OkHttpClient,
    private val clock: Clock,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val http = client
    private val uploadHttp: OkHttpClient by lazy {
        client.newBuilder().writeTimeout(5, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).callTimeout(0, TimeUnit.SECONDS).build()
    }

    /** Seconds to add to this phone's clock to get the relay's, learnt from a `skew` answer. */
    @Volatile var clockOffsetSeconds: Long = 0
        private set

    private val json = RelayJson.json

    /** `PUT /v1/group`; true when the group was created now. */
    suspend fun register(registrationToken: String?): Boolean {
        val body = json.encodeToString(GroupRequest(RelayCrypto.b64(ownerKey))).toByteArray()
        return send("PUT", "/v1/group", body, extra = registrationToken?.takeIf { it.isNotBlank() }?.let { mapOf("X-Relay-Registration" to it.trim()) }.orEmpty()) {
            json.decodeFromString<GroupResponse>(it.bodyText()).created
        }
    }

    /** `PUT /v1/group/devices` with the devices' auth keys; returns their kids as the relay has them. */
    suspend fun putDevices(authKeys: List<ByteArray>): List<String> {
        val body = json.encodeToString(DevicesRequest(authKeys.map(RelayCrypto::b64))).toByteArray()
        return send("PUT", "/v1/group/devices", body) { json.decodeFromString<DevicesResponse>(it.bodyText()).devices }
    }

    /** `PUT /v1/group/packages/{id}`, streaming [envelope], whose SHA-256 is [sha256]. */
    suspend fun uploadPackage(packageId: String, envelope: File, sha256: ByteArray) {
        val path = "/v1/group/packages/$packageId"
        sendBody("PUT", path, sha256, { envelope.asRequestBody(OCTET) }, uploadHttp) { }
    }

    /** `POST /v1/group/packages/{id}/publish` with each device's wrapped package key, by kid. */
    suspend fun publish(packageId: String, keys: Map<String, String>) {
        val body = json.encodeToString(PublishRequest(keys)).toByteArray()
        send("POST", "/v1/group/packages/$packageId/publish", body) { }
    }

    suspend fun status(): GroupStatus = send("GET", "/v1/group/status", null) { json.decodeFromString<GroupStatus>(it.bodyText()) }

    suspend fun changes(): List<ChangeInfo> = send("GET", "/v1/group/changes", null) { json.decodeFromString<ChangesList>(it.bodyText()).changes }

    /** One changes envelope, or null when the relay no longer has it (`404`). At most [maxBytes]. */
    suspend fun change(id: String, maxBytes: Long): ByteArray? = try {
        send("GET", "/v1/group/changes/$id", null) { response ->
            val body = response.body
            if (body.contentLength() > maxBytes) throw RelayHttpException(413, "too large")
            val source = body.source()
            val out = okio.Buffer()
            while (source.read(out, 8192) >= 0) {
                if (out.size > maxBytes) throw RelayHttpException(413, "too large")
            }
            out.readByteArray()
        }
    } catch (e: RelayHttpException) {
        if (e.status == 404) null else throw e
    }

    suspend fun done(ids: List<String>): DoneResponse {
        val body = json.encodeToString(DoneRequest(ids)).toByteArray()
        return send("POST", "/v1/group/done", body) { json.decodeFromString<DoneResponse>(it.bodyText()) }
    }

    suspend fun deleteGroup() {
        send("DELETE", "/v1/group", null) { }
    }

    private suspend fun <T> send(method: String, path: String, body: ByteArray?, extra: Map<String, String> = emptyMap(), read: (Response) -> T): T {
        val sha = body?.let(RelayCrypto::sha256) ?: RelaySigning.EMPTY_SHA256
        val requestBody: () -> RequestBody? = { body?.toRequestBody(JSON_TYPE) }
        return sendBody(method, path, sha, requestBody, http, extra, read)
    }

    private suspend fun <T> sendBody(
        method: String,
        path: String,
        sha256: ByteArray,
        body: () -> RequestBody?,
        client: OkHttpClient,
        extra: Map<String, String> = emptyMap(),
        read: (Response) -> T,
    ): T {
        var retried = false
        while (true) {
            val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + path).build()
            val pathAndQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            val ts = Timestamps.next("$method\n$pathAndQuery\n${RelayCrypto.hex(sha256)}", clock.instant().epochSecond + clockOffsetSeconds)
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", RelaySigning.header(ownerKey, method, pathAndQuery, ts, sha256))
            extra.forEach { (k, v) -> builder.header(k, v) }
            val requestBody = body() ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null
            builder.method(method, requestBody)
            val outcome = execute(client, builder.build()) { response ->
                if (response.isSuccessful) {
                    Result.success(read(response))
                } else {
                    val code = runCatching { json.decodeFromString<ErrorBody>(response.bodyText()).error }.getOrNull()
                    if (response.code == 401 && (code == "skew" || code == "replay") && !retried) {
                        if (code == "skew") offsetFrom(response)?.let { clockOffsetSeconds = it }
                        Result.failure(Retry)
                    } else if (response.code == 401) {
                        Result.failure(RelayUnauthorizedException(code))
                    } else {
                        Result.failure(RelayHttpException(response.code, code))
                    }
                }
            }
            val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
            if (error === Retry) {
                retried = true
                continue
            }
            throw error
        }
    }

    /** Marks the one retry after a `skew` or `replay` answer; never escapes [sendBody]. */
    private object Retry : IOException("retry")

    private fun offsetFrom(response: Response): Long? {
        val date = response.header("Date") ?: return null
        val relay = runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull() ?: return null
        return relay - clock.instant().epochSecond
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, handle: (Response) -> T): T = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result.success(handle(it)) }
                    } catch (e: CancellationException) {
                        Result.failure(e)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun Response.bodyText(): String = body.string()

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}

/**
 * Keeps this process from signing the same request twice with the same `ts`: the relay refuses
 * a signature it has seen in the last 600 s (`replay`), and the same request in the same
 * second, or a retry, would sign the same. A repeat gets the second after the last one used,
 * which stays well within the relay's 300 s tolerance for anything but a burst of hundreds.
 */
internal object Timestamps {
    private const val WINDOW_SECONDS = 600L
    private val used = HashMap<String, Long>()

    @Synchronized
    fun next(request: String, now: Long): Long {
        if (used.size > 256) used.values.removeAll { it < now - WINDOW_SECONDS }
        val last = used[request]
        val ts = if (last != null && last >= now && last - now < WINDOW_SECONDS) last + 1 else now
        used[request] = ts
        return ts
    }
}

/** JSON for the relay: lenient reading, as for the Kobo protocol. */
object RelayJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

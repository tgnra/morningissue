package net.tgnra.morningissue.relay

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A small relay for tests, written from `docs/relay-sync.md` alone: owner routes over HTTP
 * (checking every signature, the clock and replays as the real one does), and the device side
 * as plain calls, standing in for a Kobo.
 */
class FakeRelay(var registrationToken: String? = null) : Dispatcher() {
    /** The relay's clock, in seconds. */
    @Volatile var clockSeconds: () -> Long = { Instant.now().epochSecond }

    private fun clock(): Long = clockSeconds()

    var ownerKey: ByteArray? = null
        private set
    val devices: MutableMap<String, ByteArray> = ConcurrentHashMap() // kid -> auth key
    val packages: MutableMap<String, ByteArray> = ConcurrentHashMap() // uploaded, by id
    var current: String? = null
    var currentKeys: Map<String, String> = emptyMap()
    val fetchedBy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val changes = java.util.Collections.synchronizedList(mutableListOf<Change>())
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    var groupCreations = 0
        private set
    private var nextChange = 1

    class Change(val id: String, val kid: String, val batch: String, val body: ByteArray)

    /** Loses everything, as a relay whose disk was wiped. */
    fun forgetGroup() {
        ownerKey = null
        devices.clear()
        changes.clear()
        current = null
    }

    // --- The device side, as a Kobo would use it ------------------------------------------

    fun deviceUploads(auth: ByteArray, batch: String, envelope: ByteArray) {
        val kid = RelayCrypto.kid(auth)
        check(kid in devices) { "device not in the group" }
        synchronized(changes) {
            changes.removeAll { it.kid == kid && it.batch == batch }
            changes += Change("ch${nextChange++}xxxxxxxxxxxxx", kid, batch, envelope)
        }
    }

    /** `GET /v1/package` and the download: the package's id and envelope, or null. */
    fun deviceDownloads(auth: ByteArray): Triple<String, String, ByteArray>? {
        val kid = RelayCrypto.kid(auth)
        val id = current ?: return null
        val key = currentKeys[kid] ?: return null
        return Triple(id, key, packages[id]!!)
    }

    fun deviceFetched(auth: ByteArray) {
        fetchedBy += RelayCrypto.kid(auth)
    }

    // --- Owner routes ---------------------------------------------------------------------

    override fun dispatch(request: RecordedRequest): MockResponse {
        val method = request.method
        val target = request.target
        val path = target.substringBefore('?')
        requests += "$method $path"
        val body = request.body?.toByteArray() ?: ByteArray(0)
        val auth = parse(request.headers["Authorization"]) ?: return error(401, "signature")
        val (kid, ts, sig) = auth
        if (kotlin.math.abs(ts - clock()) > 300) return error(401, "skew")
        if (!path.startsWith("/v1/group")) return error(404, "not_found")

        val key: ByteArray = if (method == "PUT" && path == "/v1/group") {
            val presented = RelayCrypto.unb64(RelayJson.json.parseToJsonElement(body.decodeToString()).jsonObject["ownerKey"]!!.jsonPrimitive.content)!!
            if (RelayCrypto.kid(presented) != kid) return error(401, "signature")
            presented
        } else {
            val owner = ownerKey ?: return error(401, "unknown")
            if (RelayCrypto.kid(owner) != kid) return error(401, "unknown")
            owner
        }
        val expected = RelaySigning.signature(key, method, target, ts, RelayCrypto.sha256(body))
        if (expected != sig) return error(401, "signature")
        if (!seen.add(sig)) return error(401, "replay")

        return when {
            method == "PUT" && path == "/v1/group" -> {
                val created = ownerKey == null
                if (created && registrationToken != null && request.headers["X-Relay-Registration"] != registrationToken) return error(403, "registration")
                if (created) groupCreations++
                ownerKey = key
                json(200, buildJsonObject { put("group", kid); put("created", created) })
            }
            method == "PUT" && path == "/v1/group/devices" -> {
                val keys = RelayJson.json.parseToJsonElement(body.decodeToString()).jsonObject["devices"]!!.jsonArray.map { RelayCrypto.unb64(it.jsonPrimitive.content)!! }
                if (keys.size > 32) return error(429, "too_many")
                val kids = keys.map(RelayCrypto::kid)
                devices.keys.retainAll(kids.toSet())
                synchronized(changes) { changes.removeAll { it.kid !in kids } }
                keys.forEach { devices[RelayCrypto.kid(it)] = it }
                json(200, buildJsonObject { put("devices", JsonArray(kids.map(::JsonPrimitive))) })
            }
            method == "PUT" && path.startsWith("/v1/group/packages/") -> {
                val id = path.removePrefix("/v1/group/packages/")
                if (id == current) return error(409, "published")
                packages[id] = body
                empty(204)
            }
            method == "POST" && path.startsWith("/v1/group/packages/") && path.endsWith("/publish") -> {
                val id = path.removePrefix("/v1/group/packages/").removeSuffix("/publish")
                if (id !in packages) return error(409, "not_uploaded")
                val keys = RelayJson.json.parseToJsonElement(body.decodeToString()).jsonObject["keys"]!!.jsonObject
                current?.let { packages.remove(it) }
                current = id
                currentKeys = keys.filterKeys { it in devices }.mapValues { it.value.jsonPrimitive.content }
                fetchedBy.clear()
                empty(204)
            }
            method == "GET" && path == "/v1/group/status" -> json(
                200,
                buildJsonObject {
                    val id = current
                    put(
                        "package",
                        if (id == null) JsonNull else buildJsonObject {
                            put("id", id)
                            put("size", packages[id]!!.size)
                            put("publishedAt", time(clock()))
                            put("fetchedBy", JsonArray(fetchedBy.map(::JsonPrimitive)))
                        },
                    )
                    put(
                        "devices",
                        buildJsonArray {
                            for (d in devices.keys) add(
                                buildJsonObject {
                                    put("kid", d)
                                    put("lastSeenAt", if (d in fetchedBy) JsonPrimitive(time(clock())) else JsonNull)
                                    put("pendingChanges", synchronized(changes) { changes.count { it.kid == d } })
                                },
                            )
                        },
                    )
                },
            )
            method == "GET" && path == "/v1/group/changes" -> json(
                200,
                buildJsonObject {
                    put(
                        "changes",
                        JsonArray(
                            synchronized(changes) { changes.toList() }.map {
                                buildJsonObject {
                                    put("id", it.id); put("kid", it.kid); put("batch", it.batch); put("size", it.body.size); put("receivedAt", time(clock()))
                                }
                            },
                        ),
                    )
                },
            )
            method == "GET" && path.startsWith("/v1/group/changes/") -> {
                val id = path.removePrefix("/v1/group/changes/")
                val change = synchronized(changes) { changes.firstOrNull { it.id == id } } ?: return error(404, "not_found")
                MockResponse.Builder().code(200).setHeader("Date", date()).setHeader("Content-Type", "application/octet-stream")
                    .body(Buffer().write(change.body)).build()
            }
            method == "POST" && path == "/v1/group/done" -> {
                val ids = RelayJson.json.parseToJsonElement(body.decodeToString()).jsonObject["changes"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val deleted = synchronized(changes) {
                    val before = changes.size
                    changes.removeAll { it.id in ids }
                    before - changes.size
                }
                val all = devices.keys.all { it in fetchedBy }
                val packageDeleted = current != null && all
                if (packageDeleted) {
                    packages.remove(current)
                    current = null
                }
                json(200, buildJsonObject { put("deletedChanges", deleted); put("packageDeleted", packageDeleted) })
            }
            else -> error(404, "not_found")
        }
    }

    private fun parse(header: String?): Triple<String, Long, String>? {
        if (header == null || !header.startsWith("MIR1 ")) return null
        val parts = header.removePrefix("MIR1 ").split(", ").associate { it.substringBefore('=') to it.substringAfter('=') }
        return Triple(parts["kid"] ?: return null, parts["ts"]?.toLongOrNull() ?: return null, parts["sig"] ?: return null)
    }

    private fun time(seconds: Long) = Instant.ofEpochSecond(seconds).toString()

    private fun date(): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(clock()).atZone(ZoneOffset.UTC))

    private fun json(code: Int, body: JsonObject) =
        MockResponse.Builder().code(code).setHeader("Date", date()).setHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun empty(code: Int) = MockResponse.Builder().code(code).setHeader("Date", date()).build()

    private fun error(code: Int, error: String) = json(code, buildJsonObject { put("error", error) })
}

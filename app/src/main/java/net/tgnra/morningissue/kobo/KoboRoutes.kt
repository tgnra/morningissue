package net.tgnra.morningissue.kobo

import java.io.File
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.SyncPeerEntity
import net.tgnra.morningissue.data.images.ImageFormats
import net.tgnra.morningissue.data.images.ImageKind

/** Something a Kobo did, for the Sync screen's status line. [eventsMerged] is null for a manifest fetch. */
data class SyncActivity(val deviceId: String, val deviceName: String, val at: Instant, val eventsMerged: Int? = null)

/**
 * The phone's side of protocol v1 (`docs/kobo-sync.md`), as a function from request to
 * response; [LanHttpServer] puts it on the network. Every route but `POST /v1/pair` needs a
 * paired device's token.
 */
class KoboRoutes(
    private val database: MorningIssueDatabase,
    private val pairing: Pairing,
    private val dbId: suspend () -> String,
    /** The image store's file for a source URL (`ImageFiles.fileFor`). */
    private val imageFile: (String) -> File,
    private val clock: Clock,
    private val onActivity: (SyncActivity) -> Unit = {},
    private val pairWaitMillis: Long = KoboProtocol.PAIR_WAIT_MILLIS,
) {
    private val exporter = KoboExporter(database, clock)
    private val merger = KoboEventMerger(database, clock)
    private val peers = database.syncPeerDao()
    private val json = KoboProtocol.json

    suspend fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SerializationException) {
        HttpResponse.error(400, "bad json")
    } catch (_: IllegalArgumentException) {
        HttpResponse.error(400, "bad request")
    }

    private suspend fun route(request: HttpRequest): HttpResponse {
        val path = request.path
        val method = when {
            path == "/v1/pair" || path == "/v1/events" -> "POST"
            path == "/v1/manifest" || path == "/v1/images" || path.startsWith("/v1/editions/") -> "GET"
            else -> return HttpResponse.error(404, "not found")
        }
        if (request.method != method) return HttpResponse.error(405, "method not allowed")
        if (path == "/v1/pair") return pair(request)
        val peer = PeerTokens.match(PeerTokens.bearer(request.headers["authorization"]), peers.all())
            ?: return HttpResponse.error(401, "unauthorized")
        return when {
            path == "/v1/manifest" -> manifest(peer)
            path == "/v1/events" -> events(peer, request)
            path == "/v1/images" -> image(request)
            else -> edition(path.removePrefix("/v1/editions/"))
        }
    }

    private suspend fun pair(request: HttpRequest): HttpResponse {
        val body = json.decodeFromString<PairRequest>(request.body.decodeToString())
        val deviceId = body.deviceId.trim()
        val name = body.name.trim()
        if (deviceId.isEmpty() || deviceId.length > KoboProtocol.MAX_DEVICE_ID_CHARS || name.isEmpty() || name.length > KoboProtocol.MAX_NAME_CHARS) {
            return HttpResponse.error(400, "bad device")
        }
        return when (pairing.ask(deviceId, name, pairWaitMillis)) {
            Pairing.Outcome.ALLOWED -> {
                val token = PeerTokens.newToken()
                peers.pair(deviceId, name, PeerTokens.hash(token), clock.instant())
                HttpResponse.json(200, json.encodeToString(PairResponse(token, dbId())))
            }
            Pairing.Outcome.DENIED -> HttpResponse.error(403, "denied")
            Pairing.Outcome.TIMED_OUT -> HttpResponse.error(408, "no answer")
            Pairing.Outcome.BUSY -> HttpResponse.error(409, "another pairing is waiting")
        }
    }

    private suspend fun manifest(peer: SyncPeerEntity): HttpResponse {
        val now = clock.instant()
        peers.setLastSync(peer.deviceId, now)
        onActivity(SyncActivity(peer.deviceId, peer.name, now))
        return HttpResponse.json(200, json.encodeToString(exporter.manifest(dbId(), peer.ackedSeq)))
    }

    private suspend fun edition(id: String): HttpResponse {
        val bundle = id.toLongOrNull()?.let { exporter.edition(it) } ?: return HttpResponse.error(404, "no such edition")
        return HttpResponse.json(200, bundle)
    }

    private fun image(request: HttpRequest): HttpResponse {
        val url = request.query["url"]?.takeIf { it.isNotBlank() } ?: return HttpResponse.error(400, "no url")
        val file = imageFile(url).takeIf { it.isFile } ?: return HttpResponse.error(404, "not stored")
        return HttpResponse(200, contentTypeOf(file), file = file)
    }

    private suspend fun events(peer: SyncPeerEntity, request: HttpRequest): HttpResponse {
        val batch = json.decodeFromString<EventBatch>(request.body.decodeToString())
        if (batch.events.size > KoboProtocol.MAX_EVENTS_PER_REQUEST) return HttpResponse.error(413, "too many events")
        val result = merger.merge(peer.deviceId, batch.events) ?: return HttpResponse.error(401, "unauthorized")
        onActivity(SyncActivity(peer.deviceId, peer.name, clock.instant(), result.merged))
        return HttpResponse.json(200, json.encodeToString(EventsAck(result.ackedSeq)))
    }

    companion object {
        /** The stored file's type, from its first bytes: the store re-encodes, so the source URL's type may not hold. */
        fun contentTypeOf(file: File): String {
            val head = file.inputStream().use { input -> ByteArray(ImageFormats.HEAD_BYTES).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
            return when (ImageFormats.kindOf(head)) {
                ImageKind.JPEG -> "image/jpeg"
                ImageKind.PNG -> "image/png"
                ImageKind.GIF -> "image/gif"
                ImageKind.WEBP -> "image/webp"
                ImageKind.SVG -> "image/svg+xml"
                ImageKind.OTHER_BITMAP, ImageKind.NOT_AN_IMAGE -> "application/octet-stream"
            }
        }
    }
}

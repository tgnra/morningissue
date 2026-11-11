package net.tgnra.morningissue.kobo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The wire format of the Kobo sync, protocol v1. `docs/kobo-sync.md` is the contract and these
 * classes follow it field for field; the Kobo side (`kobo/` at the repository root) parses the
 * fixtures in `docs/kobo-sync-fixtures/`, which `KoboFixturesTest` writes from them.
 *
 * Times are ISO-8601 instants in UTC to the second (`2026-11-11T06:30:00Z`), dates are
 * `yyyy-MM-dd`, IDs are Room row IDs.
 */
object KoboProtocol {
    const val VERSION = 1
    const val APP = "morningissue"
    const val HTTP_PORT = 47824
    const val BEACON_PORT = 47823

    /** How many released editions the manifest offers. */
    const val EDITIONS_OFFERED = 3

    /** How long `POST /v1/pair` waits for Allow or Deny on the Sync screen. */
    const val PAIR_WAIT_MILLIS = 60_000L

    /** Request bodies past this are refused with 413, before they are read. */
    const val MAX_BODY_BYTES = 1 shl 20

    /** A `POST /v1/events` with more events than this is refused with 413. */
    const val MAX_EVENTS_PER_REQUEST = 500

    /** Longest `deviceId` and `name` a pairing request may carry. */
    const val MAX_DEVICE_ID_CHARS = 64
    const val MAX_NAME_CHARS = 64

    /**
     * Writes every field, nulls included, so that the Kobo sees the shape the spec shows;
     * reads leniently, so that a newer Kobo's extra fields are ignored.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
}

@Serializable
data class Beacon(
    val app: String = KoboProtocol.APP,
    val v: Int = KoboProtocol.VERSION,
    val port: Int = KoboProtocol.HTTP_PORT,
    val name: String,
    val dbId: String,
)

@Serializable
data class PairRequest(val deviceId: String, val name: String)

@Serializable
data class PairResponse(val token: String, val dbId: String)

@Serializable
data class Manifest(
    val v: Int = KoboProtocol.VERSION,
    val dbId: String,
    val ackedSeq: Long,
    val editions: List<ManifestEdition>,
)

@Serializable
data class ManifestEdition(
    val id: Long,
    val date: String,
    val kind: String,
    val number: Int,
    val releaseAt: String,
    /** Changes whenever the bundle would; a hash of the bundle's bytes (`BundleExport.version`). */
    val version: String,
)

/** `GET /v1/editions/{id}`. [articles] is keyed by article ID as a string, as JSON requires. */
@Serializable
data class EditionBundle(
    val id: Long,
    val date: String,
    val kind: String,
    val number: Int,
    val length: String,
    val releaseAt: String,
    val finishedAt: String?,
    val pages: List<BundlePage>,
    val articles: Map<String, BundleArticle>,
)

@Serializable
data class BundlePage(val page: Int, val items: List<BundleItem>)

@Serializable
data class BundleItem(
    val articleId: Long,
    val slot: Int,
    val kind: String,
    val section: String?,
    val reason: String?,
)

@Serializable
data class BundleArticle(
    val feedTitle: String,
    val title: String,
    val author: String?,
    val url: String?,
    val publishedAt: String,
    val summary: String?,
    val imageUrl: String?,
    val wordCount: Int,
    /** `article_content.blocksJson` as stored, as a JSON value; `[]` when there is none. */
    val blocks: JsonElement,
    val engagement: BundleEngagement,
)

@Serializable
data class BundleEngagement(
    val openedAt: String?,
    val readFraction: Float,
    val readAt: String?,
    val savedAt: String?,
    val likedAt: String?,
)

@Serializable
data class EventBatch(val events: List<SyncEvent>)

/**
 * One thing the reader did on the Kobo. One flat shape for every type, so that an event of a
 * type this version does not know still decodes, and is skipped. [value] is a number for
 * `readFraction` and a boolean for `liked` and `saved`.
 */
@Serializable
data class SyncEvent(
    val seq: Long,
    val type: String,
    val articleId: Long? = null,
    val editionId: Long? = null,
    val value: JsonPrimitive? = null,
    val page: Int? = null,
    val ms: Long? = null,
    val at: String? = null,
)

@Serializable
data class EventsAck(val ackedSeq: Long)

package net.tgnra.morningissue.kobo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The bodies a Kobo gets from the phone and sends back: protocol v1's manifest, edition
 * bundle, saved shelf and events (`docs/kobo-sync.md`), which relay sync carries unchanged in
 * its encrypted package and changes (`docs/relay-sync.md`). These classes follow the spec field
 * for field; the Kobo side (`kobo/` at the repository root) parses the fixtures in
 * `docs/kobo-sync-fixtures/`, which `KoboFixturesTest` writes from them.
 *
 * Times are ISO-8601 instants in UTC to the second (`2026-11-11T06:30:00Z`), dates are
 * `yyyy-MM-dd`, IDs are Room row IDs.
 */
object KoboProtocol {
    const val VERSION = 1

    /** How many released editions the manifest offers. */
    const val EDITIONS_OFFERED = 3

    /** A changes batch with more events than this is dropped. */
    const val MAX_EVENTS_PER_REQUEST = 500

    /** Longest `deviceId` and `name` a pairing code may carry. */
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

/** The manifest; a relay package's `manifest.json` adds to it and leaves out [ackedSeq] (`relay/RelayPackage.kt`). */
@Serializable
data class Manifest(
    val v: Int = KoboProtocol.VERSION,
    val dbId: String,
    val ackedSeq: Long,
    val editions: List<ManifestEdition>,
    val saved: ManifestSaved,
)

/** The manifest's line for the saved shelf. */
@Serializable
data class ManifestSaved(
    val count: Int,
    /** Changes whenever the saved list would; a hash of its bytes, as for an edition. */
    val version: String,
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

/** An edition bundle (v1's `GET /v1/editions/{id}`). [articles] is keyed by article ID as a string, as JSON requires. */
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

/** The saved shelf (v1's `GET /v1/saved`): every saved article, [order] newest saved first. [articles] is keyed as in an edition. */
@Serializable
data class SavedBundle(
    val version: String,
    val order: List<Long>,
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

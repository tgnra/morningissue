@file:UseSerializers(IsoInstantSerializer::class, IsoDateSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind

// 4.2 Events and the manifest; 4.3 bundles, the saved shelf and the engagement delta.
// The manifest, bundle and saved shelf are the `docs/kobo-sync.md` formats, which the Kobo
// parses; the fields marked "for the phone" are additions the Kobo ignores.

/**
 * `POST /sync/events`: at most [ApiLimits.MAX_EVENTS_PER_REQUEST] events in the
 * `docs/kobo-sync.md` "Events" format. Events at or below the device's `acked_seq` are skipped,
 * so sending them again is harmless.
 */
@Serializable
data class EventsRequest(
    val events: List<SyncEvent>,
)

/** `POST /sync/events`'s answer: the device's `acked_seq` after the merge. */
@Serializable
data class EventsResponse(
    val acked: Long,
)

/** `GET /sync/manifest?editions=N` (gated): what the client should hold. */
@Serializable
data class ManifestDto(
    val v: Int = 1,
    val protocol: String = SERVER_PROTOCOL,
    /** `users.db_id`. A change means the account's data was reset: the client wipes and starts over. */
    val dbId: String,
    val serverTime: Instant,
    /** The newest N released editions, newest first. */
    val editions: List<ManifestEditionDto>,
    val saved: ManifestSavedDto,
    /** This device's `acked_seq`, keyed by its device ID. */
    val acked: Map<String, Long>,
)

/** One edition in the manifest. */
@Serializable
data class ManifestEditionDto(
    val id: Long,
    val date: LocalDate,
    val kind: EditionKind,
    val number: Int,
    val releaseAt: Instant,
    /** The SHA-256 of the bundle's bytes: fetch the bundle again when it changes. */
    val version: String,
)

/** The manifest's line for the saved shelf. */
@Serializable
data class ManifestSavedDto(
    val count: Int,
    /** Changes whenever `GET /saved` would. */
    val version: String,
)

/** `GET /editions/{id}/bundle` (gated): one edition, its pages and every article on them. 404 for another user's or an unreleased edition. */
@Serializable
data class EditionBundleDto(
    val id: Long,
    val date: LocalDate,
    val kind: EditionKind,
    val number: Int,
    val length: EditionLength,
    val releaseAt: Instant,
    val finishedAt: Instant?,
    /** Sorted by page, items by slot. */
    val pages: List<BundlePageDto>,
    /** Keyed by article ID as a string, as JSON requires. */
    val articles: Map<String, BundleArticleDto>,
    /** For the phone. */
    val cutoffAt: Instant? = null,
    /** For the phone. */
    val builtAt: Instant? = null,
    /** For the phone. */
    val openedAt: Instant? = null,
    /** For the phone: whether the edition records page views (editions printed before page views did not). */
    val pageViewsTracked: Boolean? = null,
    /** For the phone. */
    val pageViews: List<PageViewDto>? = null,
)

/** One page of an edition. */
@Serializable
data class BundlePageDto(
    val page: Int,
    val items: List<BundleItemDto>,
)

/** A story's place on a page; [kind] and [section] mean what they do in the app (`ItemKind`). */
@Serializable
data class BundleItemDto(
    val articleId: Long,
    val slot: Int,
    val kind: ItemKind,
    val section: String?,
    val reason: String?,
)

/** An article in a bundle, the saved shelf or `GET /articles/{id}`. */
@Serializable
data class BundleArticleDto(
    val feedTitle: String,
    val title: String,
    val author: String?,
    val url: String?,
    val publishedAt: Instant,
    val summary: String?,
    val imageUrl: String?,
    val wordCount: Int,
    /** The stored body in the `ContentBlocks` JSON format, as a JSON value; `[]` when there is none. */
    val blocks: JsonElement,
    /** The user's state, so a client can show what is read or saved. */
    val engagement: BundleEngagementDto,
    /** For the phone. */
    val feedId: Long? = null,
    /** For the phone. */
    val guid: String? = null,
    /** For the phone. */
    val firstSeenAt: Instant? = null,
    /** For the phone: the feed body's own word count. */
    val feedWordCount: Int? = null,
    /** For the phone: where the body came from; null when there is none. */
    val contentSource: ContentSource? = null,
)

/** The user's engagement with one article. The Kobo ignores [likedAt]. */
@Serializable
data class BundleEngagementDto(
    val openedAt: Instant? = null,
    val readFraction: Float = 0f,
    val readAt: Instant? = null,
    val savedAt: Instant? = null,
    val likedAt: Instant? = null,
)

/** Time on one page of an edition. */
@Serializable
data class PageViewDto(
    val page: Int,
    /** Total time on screen, in milliseconds. */
    val ms: Long,
    val firstSeenAt: Instant,
)

/** `GET /saved` (gated): every saved article, [order] newest saved first. */
@Serializable
data class SavedBundleDto(
    val version: String,
    val order: List<Long>,
    /** Keyed as in an edition bundle. */
    val articles: Map<String, BundleArticleDto>,
)

/**
 * `GET /engagement?since=<cursor>`: engagement and edition rows changed after [ApiQuery.SINCE],
 * so the phone sees what was done on a Kobo. Pass [cursor] as `since` next time.
 */
@Serializable
data class EngagementDeltaResponse(
    val cursor: Long,
    val engagement: List<EngagementDto>,
    val editions: List<EditionStateDto>,
)

/** One article's engagement. */
@Serializable
data class EngagementDto(
    val articleId: Long,
    val openedAt: Instant? = null,
    val readFraction: Float = 0f,
    val readAt: Instant? = null,
    val savedAt: Instant? = null,
    val likedAt: Instant? = null,
)

/** One edition's reading state. */
@Serializable
data class EditionStateDto(
    val id: Long,
    val openedAt: Instant? = null,
    val finishedAt: Instant? = null,
)

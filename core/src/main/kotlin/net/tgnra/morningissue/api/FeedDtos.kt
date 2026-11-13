@file:UseSerializers(IsoInstantSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep

// 4.5 Feeds, subscriptions, OPML, health

/** One of the user's feeds: the shared feed and the user's subscription to it. */
@Serializable
data class FeedDto(
    val id: Long,
    val url: String,
    val siteUrl: String? = null,
    /** The user's own title if they renamed it, else the feed's. */
    val title: String,
    val iconUrl: String? = null,
    val tier: CadenceTier,
    /** Median hours between posts; null until known. */
    val medianIntervalHrs: Double? = null,
    /** Learned affinity, 0..1, before the slider; 0.5 is neutral. */
    val affinity: Double,
    val sliderStep: SliderStep,
    val muteUntil: Instant? = null,
    /** Tag names, sorted. */
    val tags: List<String> = emptyList(),
    val status: FeedStatus,
    val addedAt: Instant,
    val discoveredAt: Instant? = null,
    /** The last successful fetch. */
    val lastSyncAt: Instant? = null,
    /** The undismissed health flag, if any. */
    val flag: HealthFlag? = null,
)

/** A tag (section) with its learned affinity. */
@Serializable
data class TagDto(
    val id: Long,
    val name: String,
    val affinity: Double,
)

/** `GET /feeds`: subscribed and discovered feeds, and the user's tags. */
@Serializable
data class FeedsResponse(
    val feeds: List<FeedDto>,
    val tags: List<TagDto>,
)

/** `POST /feeds/discover`: find the feeds of a site or feed address. 422 `not_a_feed` when it offers none. */
@Serializable
data class DiscoverFeedRequest(
    val address: String,
)

/** A feed found at an address. */
@Serializable
data class FeedCandidateDto(
    /** The feed document's URL, ready for `POST /feeds`. */
    val url: String,
    val title: String? = null,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    /** `RSS`, `ATOM` or `JSON` when known. */
    val kind: String? = null,
)

/** `POST /feeds/discover`'s answer. */
@Serializable
data class DiscoverFeedResponse(
    val candidates: List<FeedCandidateDto>,
)

/**
 * `POST /feeds`: subscribe. 201 with a new subscription (the shared feed is reused by its
 * normalised URL; the first fetch is done before the answer, within 45 seconds), 200 when
 * already subscribed or a discovered feed was promoted.
 */
@Serializable
data class AddFeedRequest(
    val url: String,
    val title: String? = null,
    val tags: List<String> = emptyList(),
)

/** `PATCH /feeds/{id}`: only what is given changes; [muteUntil] may be set to null to unmute. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FeedPatch(
    /** The user's own title for the feed. */
    val title: String? = null,
    val sliderStep: SliderStep? = null,
    /** Replaces the feed's tags. */
    val tags: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val muteUntil: Patch<Instant?> = Patch.Absent,
)

/** A feed on the Subscriptions desk, with what its fetch record says. */
@Serializable
data class FlaggedFeedDto(
    val feed: FeedDto,
    val flag: HealthFlag,
    /** When the current run of fetch errors began. */
    val errorSince: Instant? = null,
    /** The HTTP status of the latest fetch, when the site answered. */
    val lastHttpStatus: Int? = null,
)

/** `GET /feeds/health`: recomputed for the user without network, as opening the desk does. */
@Serializable
data class FeedHealthResponse(
    val flagged: List<FlaggedFeedDto>,
)

/** `POST /feeds/{id}/health/retry`. */
@Serializable
data class RetryResponse(
    val outcome: RetryOutcome,
)

/** The report of an OPML job (`POST /opml`). */
@Serializable
data class OpmlReportDto(
    val added: Int,
    val skipped: Int,
)

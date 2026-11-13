@file:UseSerializers(IsoInstantSerializer::class, IsoDateSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength

// 4.3 Editions, the stand, Print now and Extra, jobs

/** A release the client shows as "next edition at …". */
@Serializable
data class ReleaseDto(
    val kind: EditionKind,
    val at: Instant,
)

/** One released edition, for the newsstand cover and back issues; the fields of `model.EditionSummary` and more. */
@Serializable
data class EditionSummaryDto(
    val id: Long,
    val date: LocalDate,
    val kind: EditionKind,
    /** The user's running edition number, from 1. */
    val number: Int,
    val length: EditionLength,
    val releaseAt: Instant,
    /** Posts first seen after this were too late for the edition. */
    val cutoffAt: Instant,
    val builtAt: Instant,
    val openedAt: Instant? = null,
    /** Set once the reader reached the back page. */
    val finishedAt: Instant? = null,
    val pageViewsTracked: Boolean = false,
    val leadTitle: String? = null,
    val leadImageUrl: String? = null,
    /** The lead story's feed title. */
    val leadSource: String? = null,
    /** Stories printed, not counting the back-page index. */
    val storyCount: Int,
)

/** `GET /editions?before=<number>&limit=30`: released editions only, newest first. */
@Serializable
data class EditionsResponse(
    val editions: List<EditionSummaryDto>,
)

/** Whether the edition due now was printed (`DueEditions.status`). */
enum class DueState { PRINTED, NOTHING_NEW, NOT_PRINTED }

/** The latest release and what became of it. */
@Serializable
data class DueDto(
    val kind: EditionKind,
    val releaseAt: Instant,
    val state: DueState,
)

/** The newsstand's offer of an Extra edition (the `ExtraOffer` rule). */
@Serializable
data class ExtraOfferDto(
    val feedIds: List<Long>,
    /** The Extra already printed today that a new one would reprint, if any. */
    val reprintOf: Long? = null,
)

/** `GET /stand`: what the newsstand shows, computed in the user's time zone. */
@Serializable
data class StandDto(
    val latestEditionId: Long?,
    /** Null before the first release. */
    val due: DueDto?,
    val nextRelease: ReleaseDto,
    /** True when the user subscribes to at least one feed. */
    val hasSources: Boolean,
    val extraOffer: ExtraOfferDto? = null,
    /** The job of a print running for the user, if any. */
    val jobRunning: String? = null,
)

/** `POST /editions/extra`: an Extra edition from these feeds. */
@Serializable
data class ExtraRequest(
    val feedIds: List<Long>,
)

/** `POST /print`, `POST /editions/extra`, `POST /opml`, `POST /import`: 202 for a new job, 200 with the one already running. */
@Serializable
data class JobStartedResponse(
    val jobId: String,
)

/** What a job does. */
enum class JobKind { PRINT, EXTRA, IMPORT, OPML }

/** Whether a job has finished. */
enum class JobState { RUNNING, DONE, FAILED }

/** How far a job has got; the print stages mirror the app's `BuildStage`. */
enum class JobStage { FETCHING_FEEDS, SETTING_PAGES, FETCHING_TEXT, FETCHING_IMAGES, IMPORTING }

/** How a print or Extra ended (the app's `PrintResult` and Extra outcomes); import and OPML jobs use [DONE]. */
enum class JobResult {
    PRINTED,
    PRINTED_OFFLINE,
    NOTHING_NEW,
    UNREACHABLE,
    FAILED,
    EXTRA_PRINTED,
    EXTRA_REPRINTED,
    EXTRA_NOTHING,

    /** An import or OPML job finished; its `report` says what it did. */
    DONE,
}

/** A finished job's result. */
@Serializable
data class JobOutcomeDto(
    val result: JobResult,
    /** The edition printed or found, for print and Extra jobs. */
    val editionId: Long? = null,
    /** An [ImportReportDto] for an import job, an [OpmlReportDto] for an OPML job; decode with [ApiJson]. */
    val report: JsonElement? = null,
    /** Why a FAILED job failed, for a person to read. */
    val message: String? = null,
)

/** `GET /jobs/{id}`: a job's progress. Jobs are kept in memory for an hour after they end. */
@Serializable
data class JobDto(
    val id: String,
    val kind: JobKind,
    val state: JobState,
    val stage: JobStage? = null,
    /** Progress within [stage], when it has a count. */
    val done: Int? = null,
    val total: Int? = null,
    /** Set once [state] is not RUNNING. */
    val outcome: JobOutcomeDto? = null,
)

/** `POST /editions/{id}/desk-notice`: how many feeds the Subscriptions desk would flag, or null if the notice is not due this month. */
@Serializable
data class DeskNoticeResponse(
    val count: Int?,
)

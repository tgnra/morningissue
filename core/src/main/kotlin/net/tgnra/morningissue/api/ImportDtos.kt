package net.tgnra.morningissue.api

import kotlinx.serialization.Serializable

// 4.9 Legacy import

/** `POST /import?mode=`: merge is what the phone sends; replace is refused (409 `conflict`) on an account with data. */
enum class ImportMode {
    MERGE,
    REPLACE;

    /** The query value, [ApiQuery.MODE_MERGE] or [ApiQuery.MODE_REPLACE]. */
    val query: String get() = name.lowercase()
}

/** What an import did; the `report` of an IMPORT job. */
@Serializable
data class ImportReportDto(
    /** Feeds subscribed by the import. */
    val feeds: Int = 0,
    val posts: Int = 0,
    /** Posts the server already held. */
    val skippedPosts: Int = 0,
    val editions: Int = 0,
    /** Editions the user already had (same date and kind). */
    val skippedEditions: Int = 0,
    /** True when `settings.json` was applied: the account had no settings of its own yet. */
    val settingsApplied: Boolean = false,
)

/**
 * `POST /import`'s answer: 202 with [jobId] for a new import, or 200 with [report] when the
 * `Idempotency-Key` was already used and nothing was done.
 */
@Serializable
data class ImportResponse(
    val jobId: String? = null,
    val report: ImportReportDto? = null,
)

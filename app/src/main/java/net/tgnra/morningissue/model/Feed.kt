package net.tgnra.morningissue.model

import java.time.Instant

/** A subscribed feed as the UI and engine see it. */
data class Feed(
    val id: Long,
    val url: String,
    val siteUrl: String?,
    val title: String,
    val iconUrl: String?,
    val tier: CadenceTier,
    /** Median gap between posts in hours; null until cadence has been computed. */
    val medianIntervalHours: Double?,
    /** Ratio of the 90th to the 10th percentile interval; null until computed. */
    val burstiness: Double?,
    /** Learned affinity, 0..1, before the slider is applied. 0.5 is neutral. */
    val affinity: Double,
    val sliderStep: SliderStep,
    /** Tag names, sorted alphabetically. */
    val tags: List<String>,
    val notify: Boolean,
    val muteUntil: Instant?,
    val lastSyncAt: Instant?,
    val addedAt: Instant,
    /** Current health flag, if the health check has raised one. */
    val healthFlag: HealthFlag?,
) {
    /** Posts per week derived from the median interval; null until cadence is known. */
    val postsPerWeek: Double?
        get() = medianIntervalHours?.takeIf { it > 0.0 }?.let { 168.0 / it }

    fun isMuted(now: Instant): Boolean = muteUntil?.isAfter(now) == true
}

/** A tag (section) with its own learned affinity. */
data class Tag(
    val id: Long,
    val name: String,
    val affinity: Double,
)

/** What is needed to subscribe to a feed whose address is already known. */
data class NewFeed(
    /** The feed document URL (not the site URL). */
    val url: String,
    /** Display title; the URL's host is used when null or blank. */
    val title: String? = null,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    val tags: List<String> = emptyList(),
)

sealed interface AddFeedResult {
    val feedId: Long

    data class Added(override val feedId: Long) : AddFeedResult

    /** A feed with the same normalised URL already exists; nothing was changed. */
    data class AlreadySubscribed(override val feedId: Long) : AddFeedResult
}

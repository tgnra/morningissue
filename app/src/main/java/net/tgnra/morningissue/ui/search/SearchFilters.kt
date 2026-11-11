package net.tgnra.morningissue.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.SearchQuery

/** How far back a search looks. The range always ends now. */
enum class SearchPeriod(val days: Long?) {
    AnyTime(null),
    PastWeek(7),
    PastMonth(30),
    PastYear(365),
}

/**
 * What the search screen's filter controls are set to. Kept apart from the typed text, so the
 * controls, the summary of what is active and the query all come from one value.
 */
@Immutable
data class SearchFilters(
    val tag: String? = null,
    val feedId: Long? = null,
    val period: SearchPeriod = SearchPeriod.AnyTime,
    val savedOnly: Boolean = false,
    val likedOnly: Boolean = false,
) {
    /** How many filters narrow the search; 0 when everything is searched. */
    val activeCount: Int
        get() = listOf(tag != null, feedId != null, period != SearchPeriod.AnyTime, savedOnly, likedOnly).count { it }

    val isActive: Boolean get() = activeCount > 0

    /**
     * The query for [text] under these filters. The date range runs from [SearchPeriod.days]
     * before [now] with no upper bound, so a post fetched a moment ago is still found.
     */
    fun toQuery(text: String, now: Instant): SearchQuery = SearchQuery(
        text = text.trim(),
        tag = tag?.takeIf { it.isNotBlank() },
        feedId = feedId,
        from = period.days?.let { now.minus(Duration.ofDays(it)) },
        to = null,
        likedOnly = likedOnly,
        savedOnly = savedOnly,
    )

    /**
     * These filters with any tag or source that no longer exists dropped, so a filter cannot
     * stay active with no control left to switch it off. Tags match without regard to case.
     */
    fun validFor(tags: Collection<String>, feedIds: Collection<Long>): SearchFilters = copy(
        tag = tag?.let { chosen -> tags.firstOrNull { it.equals(chosen, ignoreCase = true) } },
        feedId = feedId?.takeIf { it in feedIds },
    )

    companion object {
        val None = SearchFilters()

        /** Survives rotation and process death. */
        val Saver: Saver<SearchFilters, Any> = listSaver(
            save = { listOf(it.tag, it.feedId, it.period.name, it.savedOnly, it.likedOnly) },
            restore = {
                SearchFilters(
                    tag = it[0] as String?,
                    feedId = it[1] as Long?,
                    period = SearchPeriod.entries.firstOrNull { period -> period.name == it[2] } ?: SearchPeriod.AnyTime,
                    savedOnly = it[3] as Boolean,
                    likedOnly = it[4] as Boolean,
                )
            },
        )
    }
}

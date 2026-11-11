package net.tgnra.morningissue.ui.newsstand

import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.model.Feed

/** When the stand offers to print an Extra edition from newly added sources. Pure, so it can be tested. */
object ExtraOffer {
    /**
     * The sources an Extra edition would be printed from: those added since the newest edition
     * closed for copy, and since the reader last turned the offer down ([dismissedAt]).
     *
     * Empty when no edition has been printed yet (the first edition takes every source) and when
     * [today] already has an Extra edition, because only one is kept per day; sources added
     * after it wait for the next scheduled edition.
     */
    fun newSources(feeds: List<Feed>, editions: List<EditionSummary>, dismissedAt: Instant?, today: LocalDate): List<Feed> {
        val newest = editions.maxOfOrNull { it.cutoffAt } ?: return emptyList()
        if (editions.any { it.kind == EditionKind.EXTRA && it.date == today }) return emptyList()
        val since = if (dismissedAt != null && dismissedAt > newest) dismissedAt else newest
        return feeds.filter { it.addedAt > since }
    }
}

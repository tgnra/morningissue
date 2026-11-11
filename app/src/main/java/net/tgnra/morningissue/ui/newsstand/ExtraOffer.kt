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
     * Empty when no edition has been printed yet (the first edition takes every source).
     *
     * An Extra edition already printed today does not end the offer: its cutoff counts like
     * any edition's, so sources added after it are offered again, this time as a reprint of
     * that Extra ([reprints]).
     */
    fun newSources(feeds: List<Feed>, editions: List<EditionSummary>, dismissedAt: Instant?): List<Feed> {
        val newest = editions.maxOfOrNull { it.cutoffAt } ?: return emptyList()
        val since = if (dismissedAt != null && dismissedAt > newest) dismissedAt else newest
        return feeds.filter { it.addedAt > since }
    }

    /**
     * Whether printing would reprint an Extra edition rather than print a first one: [today]
     * already has one, and only one is kept per date. The offer then says "Reprint".
     */
    fun reprints(editions: List<EditionSummary>, today: LocalDate): Boolean =
        editions.any { it.kind == EditionKind.EXTRA && it.date == today }
}

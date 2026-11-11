package net.tgnra.morningissue.ui.newsstand

import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraOfferTest {
    private val today = LocalDate.of(2026, 11, 11)
    private val morningCutoff = Instant.parse("2026-11-11T05:00:00Z")

    private fun feed(id: Long, addedAt: String) = Feed(
        id = id, url = "https://example.com/$id", siteUrl = null, title = "Feed $id", iconUrl = null,
        tier = CadenceTier.WEEKLY, medianIntervalHours = null, burstiness = null, affinity = 0.5,
        sliderStep = SliderStep.AUTO, tags = emptyList(), notify = false, muteUntil = null,
        lastSyncAt = null, addedAt = Instant.parse(addedAt), healthFlag = null,
    )

    private fun edition(id: Long, kind: EditionKind, date: LocalDate, cutoffAt: Instant) = EditionSummary(
        id = id, date = date, kind = kind, number = id.toInt(), length = EditionLength.STANDARD,
        releaseAt = cutoffAt, cutoffAt = cutoffAt, leadHeadline = null, leadImageUrl = null,
        leadSource = null, storyCount = 5, isRead = false,
    )

    private val morning = edition(1, EditionKind.MORNING, today, morningCutoff)
    private val old = feed(1, "2026-11-01T10:00:00Z")
    private val added = feed(2, "2026-11-11T09:00:00Z")

    private fun ids(feeds: List<Feed>) = feeds.map { it.id }

    @Test
    fun `sources added after the newest edition closed are offered`() {
        assertEquals(listOf(2L), ids(ExtraOffer.newSources(listOf(old, added), listOf(morning), null)))
    }

    @Test
    fun `nothing is offered before the first edition`() {
        assertEquals(emptyList<Long>(), ids(ExtraOffer.newSources(listOf(old, added), emptyList(), null)))
    }

    @Test
    fun `a dismissed offer is not repeated for the same sources`() {
        val dismissed = Instant.parse("2026-11-11T09:30:00Z")
        assertEquals(emptyList<Long>(), ids(ExtraOffer.newSources(listOf(old, added), listOf(morning), dismissed)))
        val later = feed(3, "2026-11-11T10:00:00Z")
        assertEquals(listOf(3L), ids(ExtraOffer.newSources(listOf(old, added, later), listOf(morning), dismissed)))
    }

    @Test
    fun `a dismissal older than the newest edition does not matter`() {
        val dismissed = Instant.parse("2026-11-10T09:30:00Z")
        assertEquals(listOf(2L), ids(ExtraOffer.newSources(listOf(old, added), listOf(morning), dismissed)))
    }

    @Test
    fun `sources added after today's Extra edition are offered as a reprint of it`() {
        val extra = edition(2, EditionKind.EXTRA, today, Instant.parse("2026-11-11T09:10:00Z"))
        val later = feed(3, "2026-11-11T10:00:00Z")
        val editions = listOf(extra, morning)
        // Only the source added since the Extra went to press; the one it was printed for is in it.
        assertEquals(listOf(3L), ids(ExtraOffer.newSources(listOf(old, added, later), editions, null)))
        assertEquals(true, ExtraOffer.reprints(editions, today))
    }

    @Test
    fun `with today's Extra edition printed and no source added since nothing is offered`() {
        val extra = edition(2, EditionKind.EXTRA, today, Instant.parse("2026-11-11T09:10:00Z"))
        assertEquals(emptyList<Long>(), ids(ExtraOffer.newSources(listOf(old, added), listOf(extra, morning), null)))
    }

    @Test
    fun `a reprint offer turned down is not repeated for the same sources`() {
        val extra = edition(2, EditionKind.EXTRA, today, Instant.parse("2026-11-11T09:10:00Z"))
        val later = feed(3, "2026-11-11T10:00:00Z")
        val dismissed = Instant.parse("2026-11-11T10:05:00Z")
        assertEquals(emptyList<Long>(), ids(ExtraOffer.newSources(listOf(old, added, later), listOf(extra, morning), dismissed)))
    }

    @Test
    fun `the first Extra edition of a day is a printing and not a reprint`() {
        assertEquals(false, ExtraOffer.reprints(listOf(morning), today))
        val yesterdays = edition(2, EditionKind.EXTRA, today.minusDays(1), Instant.parse("2026-11-10T09:10:00Z"))
        assertEquals(false, ExtraOffer.reprints(listOf(morning, yesterdays), today))
    }

    @Test
    fun `yesterday's Extra edition does not block today's offer`() {
        val extra = edition(2, EditionKind.EXTRA, today.minusDays(1), Instant.parse("2026-11-10T09:10:00Z"))
        assertEquals(listOf(2L), ids(ExtraOffer.newSources(listOf(old, added), listOf(morning, extra), null)))
    }
}

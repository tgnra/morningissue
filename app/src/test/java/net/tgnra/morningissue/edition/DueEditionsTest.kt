package net.tgnra.morningissue.edition

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the stand shows the unprinted placeholder: [DueEditions.status] over what is stored,
 * one test per case the rule names.
 */
class DueEditionsTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val reset = LocalTime.of(6, 30)
    private val eveningTime = LocalTime.of(18, 0)

    private fun at(date: String, time: String): Instant = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()
    private fun day(date: String): LocalDate = LocalDate.parse(date)

    private val morningReset = at("2026-11-10", "06:30")
    private val eveningRelease = at("2026-11-10", "18:00")
    private val morning = DueRelease(EditionKind.MORNING, morningReset, day("2026-11-10"))
    private val evening = DueRelease(EditionKind.EVENING, eveningRelease, day("2026-11-10"))

    private var nextId = 1L

    private fun edition(date: String, kind: EditionKind = EditionKind.MORNING, releaseAt: Instant = at(date, "06:30")) = EditionSummary(
        id = nextId++,
        date = day(date),
        kind = kind,
        number = 1,
        length = EditionLength.STANDARD,
        releaseAt = releaseAt,
        cutoffAt = releaseAt.minusSeconds(5400),
        leadHeadline = "Lead",
        leadImageUrl = null,
        leadSource = "Feed",
        storyCount = 12,
        isRead = false,
    )

    private fun status(
        now: Instant,
        released: List<EditionSummary> = emptyList(),
        nothingNewFor: List<Instant> = emptyList(),
        hasSources: Boolean = true,
        eveningOn: Boolean = false,
    ) = DueEditions.status(now, reset, eveningTime.takeIf { eveningOn }, zone, released, nothingNewFor, hasSources)

    private val nine = at("2026-11-10", "09:00")
    private val sevenInTheEvening = at("2026-11-10", "19:00")

    // --- The three outcomes of a morning ------------------------------------------------------

    @Test
    fun anEditionBuiltInTheBackgroundIsPrinted() {
        assertEquals(DueStatus.Printed, status(nine, released = listOf(edition("2026-11-10"), edition("2026-11-09"))))
    }

    @Test
    fun aBuildThatThrewLeavesNoEditionAndNoRecordSoThePlaceholderShows() {
        val yesterday = listOf(edition("2026-11-09"))
        assertEquals(DueStatus.NotPrinted(morning), status(nine, released = yesterday))
    }

    @Test
    fun aRunThatWorkManagerNeverStartedLooksTheSameAndShowsThePlaceholder() {
        // Nothing was stored for this reset at all; the last record is from two days ago.
        val stale = listOf(at("2026-11-08", "06:30"))
        assertEquals(DueStatus.NotPrinted(morning), status(nine, released = listOf(edition("2026-11-07")), nothingNewFor = stale))
    }

    @Test
    fun nothingNewToPrintIsNotAFailure() {
        val yesterday = listOf(edition("2026-11-09"))
        assertEquals(DueStatus.NothingNew(morning), status(nine, released = yesterday, nothingNewFor = listOf(morningReset)))
    }

    @Test
    fun aRecordForAnotherResetDoesNotExcuseThisOne() {
        val yesterday = listOf(edition("2026-11-09"))
        // Tomorrow's early build has already found nothing; today's was never accounted for.
        val records = listOf(at("2026-11-11", "06:30"), at("2026-11-09", "06:30"))
        assertEquals(DueStatus.NotPrinted(morning), status(nine, released = yesterday, nothingNewFor = records))
    }

    @Test
    fun anEditionPrintedAfterANothingNewRecordCounts() {
        // Print now from the "No new edition today" notice found something after all.
        assertEquals(DueStatus.Printed, status(nine, released = listOf(edition("2026-11-10")), nothingNewFor = listOf(morningReset)))
    }

    @Test
    fun beforeTheResetYesterdaysEditionIsTheOneThatIsDue() {
        val early = at("2026-11-10", "05:00")
        assertEquals(DueStatus.Printed, status(early, released = listOf(edition("2026-11-09"))))
        val before = DueRelease(EditionKind.MORNING, at("2026-11-09", "06:30"), day("2026-11-09"))
        assertEquals(DueStatus.NotPrinted(before), status(early, released = listOf(edition("2026-11-08"))))
        // The early build for the coming reset records "nothing new" while yesterday's is still due.
        assertEquals(
            DueStatus.NothingNew(before),
            status(early, released = listOf(edition("2026-11-08")), nothingNewFor = listOf(morningReset, before.at)),
        )
    }

    @Test
    fun exactlyAtTheResetTheNewEditionIsDue() {
        assertEquals(DueStatus.NotPrinted(morning), status(morningReset, released = listOf(edition("2026-11-09"))))
    }

    @Test
    fun anEditionBuiltEarlyButNotYetReleasedIsNotOnTheStand() {
        // `released` only ever holds released editions; tomorrow's pre-built one is not among them.
        val early = at("2026-11-10", "05:30")
        assertEquals(DueStatus.Printed, status(early, released = listOf(edition("2026-11-09"))))
    }

    // --- No sources, first edition, onboarding ------------------------------------------------

    @Test
    fun aBrandNewInstallWithNoSourcesNeverShowsThePlaceholder() {
        assertEquals(DueStatus.NothingNew(morning), status(nine, hasSources = false))
    }

    @Test
    fun withNoSourcesLeftAnOldRackIsNotAFailureEither() {
        assertEquals(DueStatus.NothingNew(morning), status(nine, released = listOf(edition("2026-11-02")), hasSources = false))
    }

    @Test
    fun sourcesButNoEditionYetShowsThePlaceholder() {
        // Onboarding was skipped, or its first sync could not be made.
        assertEquals(DueStatus.NotPrinted(morning), status(nine))
    }

    @Test
    fun sourcesThatHadNothingRecentAreNotAFailure() {
        assertEquals(DueStatus.NothingNew(morning), status(nine, nothingNewFor = listOf(morningReset)))
    }

    @Test
    fun onboardingsFirstEditionIsPrinted() {
        // Onboarding at 21:00 builds the edition for the latest reset in the foreground.
        val night = at("2026-11-10", "21:00")
        assertEquals(DueStatus.Printed, status(night, released = listOf(edition("2026-11-10"))))
    }

    // --- Extra editions ------------------------------------------------------------------------

    @Test
    fun anExtraEditionIsNotTheDaysPaper() {
        val extra = edition("2026-11-10", EditionKind.EXTRA, releaseAt = at("2026-11-10", "08:00"))
        assertEquals(DueStatus.NotPrinted(morning), status(nine, released = listOf(extra, edition("2026-11-09"))))
        assertEquals(DueStatus.Printed, status(nine, released = listOf(extra, edition("2026-11-10"))))
        assertNull(DueEditions.editionFor(morning, listOf(extra)))
    }

    // --- Evening edition ----------------------------------------------------------------------

    @Test
    fun theEveningEditionIsOnlyDueOnceItsTimeHasPassedAndOnlyWhenSwitchedOn() {
        val five = at("2026-11-10", "17:00")
        assertEquals(listOf(morning), DueEditions.due(five, reset, eveningTime, zone))
        assertEquals(listOf(evening, morning), DueEditions.due(sevenInTheEvening, reset, eveningTime, zone))
        assertEquals(listOf(morning), DueEditions.due(sevenInTheEvening, reset, null, zone))
        // After midnight it is still that evening's paper, until the morning reset.
        assertEquals(listOf(evening, morning), DueEditions.due(at("2026-11-11", "02:00"), reset, eveningTime, zone))
        assertEquals(
            listOf(DueRelease(EditionKind.MORNING, at("2026-11-11", "06:30"), day("2026-11-11"))),
            DueEditions.due(at("2026-11-11", "07:00"), reset, eveningTime, zone),
        )
    }

    @Test
    fun anEveningEditionThatFailedToBuildShowsThePlaceholder() {
        val today = listOf(edition("2026-11-10"))
        assertEquals(DueStatus.NotPrinted(evening), status(sevenInTheEvening, released = today, eveningOn = true))
        // With the Evening edition off the morning paper is all that is due.
        assertEquals(DueStatus.Printed, status(sevenInTheEvening, released = today))
    }

    @Test
    fun anEveningWithNothingNewLeavesTheMorningPaperCurrent() {
        val today = listOf(edition("2026-11-10"))
        assertEquals(DueStatus.Printed, status(sevenInTheEvening, released = today, nothingNewFor = listOf(eveningRelease), eveningOn = true))
    }

    @Test
    fun aPrintedEveningEditionSettlesTheDayEvenIfTheMorningOneWasMissed() {
        val papers = listOf(edition("2026-11-10", EditionKind.EVENING, eveningRelease), edition("2026-11-09"))
        assertEquals(DueStatus.Printed, status(sevenInTheEvening, released = papers, eveningOn = true))
    }

    @Test
    fun withNeitherPaperPrintedTheMorningOneIsAskedFor() {
        val yesterday = listOf(edition("2026-11-09"))
        assertEquals(DueStatus.NotPrinted(morning), status(sevenInTheEvening, released = yesterday, eveningOn = true))
        // Nothing new for the evening says nothing about the morning's slow posts.
        assertEquals(
            DueStatus.NotPrinted(morning),
            status(sevenInTheEvening, released = yesterday, nothingNewFor = listOf(eveningRelease), eveningOn = true),
        )
        // The morning had nothing new, the evening run never happened.
        assertEquals(
            DueStatus.NotPrinted(evening),
            status(sevenInTheEvening, released = yesterday, nothingNewFor = listOf(morningReset), eveningOn = true),
        )
    }

    @Test
    fun aDayWithNothingNewMorningOrEveningIsNoNewEdition() {
        val yesterday = listOf(edition("2026-11-09"))
        assertEquals(
            DueStatus.NothingNew(morning),
            status(sevenInTheEvening, released = yesterday, nothingNewFor = listOf(eveningRelease, morningReset), eveningOn = true),
        )
    }

    @Test
    fun yesterdaysEveningEditionDoesNotAnswerForTodays() {
        val papers = listOf(edition("2026-11-10"), edition("2026-11-09", EditionKind.EVENING, at("2026-11-09", "18:00")))
        assertEquals(DueStatus.NotPrinted(evening), status(sevenInTheEvening, released = papers, eveningOn = true))
    }

    // --- Time zones -----------------------------------------------------------------------------

    @Test
    fun anEditionDatedAheadAfterTravellingWestCountsAsPrinted() {
        // The stand holds the paper of the 11th; the local clock says the latest reset was the 10th.
        assertEquals(DueStatus.Printed, status(nine, released = listOf(edition("2026-11-11"))))
    }

    @Test
    fun unprintedListsOnlyWhatIsNewerThanTheNewestPrintedPaper() {
        val due = listOf(evening, morning)
        assertEquals(due, DueEditions.unprinted(due, emptyList()))
        assertEquals(listOf(evening), DueEditions.unprinted(due, listOf(edition("2026-11-10"))))
        assertEquals(emptyList<DueRelease>(), DueEditions.unprinted(due, listOf(edition("2026-11-10", EditionKind.EVENING, eveningRelease))))
    }
}

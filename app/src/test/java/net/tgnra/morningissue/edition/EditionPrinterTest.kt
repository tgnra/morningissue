package net.tgnra.morningissue.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.sync.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The print on open, Print now and the Extra edition: [EditionPrinter] over the real
 * [EditionPress] with fake collaborators. The clock stands at 09:00, after the 06:30 reset of
 * 11 November, with that edition unprinted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditionPrinterTest {
    private val now: Instant = Instant.parse("2026-11-11T09:00:00Z")
    private val reset: Instant = Instant.parse("2026-11-11T06:30:00Z")
    private val clock = MutableClock(now)
    private val journal = Journal()
    private val settings = FakeSettings()
    private val extra = sampleEdition(9).copy(kind = EditionKind.EXTRA)
    private val editions = FakeEditions(mapOf(3L to sampleEdition(3), 9L to extra))
    private val scope = TestScope(UnconfinedTestDispatcher())

    /** What the sync does; the default reaches the feeds. */
    private var sync: suspend () -> SyncReport = { SyncReport(feedsChecked = 3, newArticles = 5) }

    /** When a feed last answered a sync; null: never, so the scheduled print syncs first. */
    private var lastSyncAt: Instant? = null
    private var online = true

    /** Whether the build finds something to print: it then puts edition 3 on the stand. */
    private var somethingToPrint = true
    private var buildFailure: Exception? = null
    private var build: suspend () -> Unit = {}
    private var extraToPrint = true

    /** The model: rates whatever it is given, unless replaced. */
    private var rating = PrintRating { ids, _, onProgress ->
        journal.add("rate:${ids.size}")
        onProgress(0, ids.size)
        onProgress(ids.size, ids.size)
        ids.size
    }

    /** The stand's verdict; the default asks for today's morning edition. */
    private var status: DueStatus? = DueStatus.NotPrinted(DueRelease(EditionKind.MORNING, reset, LocalDate.of(2026, 11, 11)))

    private val publisher = object : EditionPublisher {
        override suspend fun ensureCurrentEdition(): Long? {
            journal.add("ensureCurrent")
            build()
            buildFailure?.let { throw it }
            if (somethingToPrint) editions.released = listOf(3L)
            return editions.released.firstOrNull()
        }

        override suspend fun nextReleaseAt(): Instant = Instant.parse("2026-11-12T06:30:00Z")

        override suspend fun shortlist(limit: Int): List<Long> {
            journal.add("shortlist")
            return (1L..limit.toLong().coerceAtMost(12)).toList()
        }

        override suspend fun extraShortlist(feedIds: Set<Long>, limit: Int): List<Long> {
            journal.add("extraShortlist:${feedIds.sorted()}")
            return listOf(21L, 22L)
        }

        override suspend fun publishExtraEdition(feedIds: Set<Long>): Long? {
            journal.add("publishExtra:${feedIds.sorted()}")
            if (!extraToPrint) return null
            editions.released = editions.released + 9L
            return 9L
        }
    }

    private fun press() = EditionPress(
        feedSyncer = FakeSyncer(journal) { sync() },
        publisher = publisher,
        editions = editions,
        settings = settings,
        clock = clock,
        rating = rating,
        lastSyncAt = { lastSyncAt },
        isOnline = { online },
        zone = { ZoneOffset.UTC },
    )

    private fun printer(cap: Duration = Duration.ofMinutes(3)) = EditionPrinter(
        scope = scope.backgroundScope,
        press = ::press,
        clock = clock,
        dueStatus = { status },
        prefetch = { EditionPrefetch(editions, FakeFullText(journal), { ids, _ -> journal.add("images:${ids.size}") }) },
        cap = cap,
    )

    private fun finished(result: PrintResult) = PrintState.Finished(result, now)

    /**
     * Lets the print run its course on the test's virtual time, its time limits included. (The
     * printer works in the background scope, which `advanceUntilIdle` does not wait for.)
     */
    private fun TestScope.settle() {
        advanceTimeBy(300_000)
        runCurrent()
    }

    @Test
    fun startsIdle() {
        assertEquals(PrintState.Idle, printer().state.value)
    }

    // --- The print on open -----------------------------------------------------------------

    @Test
    fun theDueEditionIsPrintedWhenTheStandOpensSyncedRatedThenBuilt() = scope.runTest {
        val printer = printer()

        assertTrue(printer.printIfDue())
        settle()

        assertEquals(finished(PrintResult.Printed), printer.state.value)
        val steps = journal.all()
        assertEquals(listOf("sync", "shortlist", "rate:12", "ensureCurrent"), steps.take(4))
        // Afterwards, without holding up the result: the stories' text, then the pictures.
        assertEquals((1..5L).map { "text:$it" }.toSet(), steps.filter { it.startsWith("text:") }.toSet())
        assertTrue(steps.last().startsWith("images:"))
        assertTrue("an edition was printed, so nothing is written down", settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun aRecentBackgroundSyncIsNotRepeated() = scope.runTest {
        lastSyncAt = now.minus(Duration.ofMinutes(110))
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.Printed), printer.state.value)
        assertFalse("sync" in journal.all())
    }

    @Test
    fun anOlderSyncIsRefreshedFirst() = scope.runTest {
        lastSyncAt = now.minus(Duration.ofMinutes(121))
        printer().printIfDue()
        settle()
        assertEquals("sync", journal.all().first())
    }

    @Test
    fun offlineTheSyncIsSkippedAndTheEditionComesFromStoredPosts() = scope.runTest {
        online = false
        lastSyncAt = now.minus(Duration.ofHours(5))
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.PrintedFromStored), printer.state.value)
        assertFalse("sync" in journal.all())
        assertTrue("ensureCurrent" in journal.all())
    }

    @Test
    fun offlineAfterARecentSyncIsAnOrdinaryPrint() = scope.runTest {
        online = false
        lastSyncAt = now.minus(Duration.ofMinutes(5))
        val printer = printer()
        printer.printIfDue()
        settle()
        assertEquals(finished(PrintResult.Printed), printer.state.value)
    }

    @Test
    fun aSyncThatRunsOverIsLeftAfterTwentySecondsAndTheBuildGoesAhead() = scope.runTest {
        sync = { awaitCancellation() }
        val printer = printer()

        printer.printIfDue()
        advanceTimeBy(19_000)
        assertEquals(PrintStage.Syncing, (printer.state.value as PrintState.Printing).stage)
        settle()

        assertEquals(finished(PrintResult.PrintedFromStored), printer.state.value)
        assertTrue("ensureCurrent" in journal.all())
    }

    @Test
    fun withoutTheModelTheRatingIsSkipped() = scope.runTest {
        rating = PrintRating.None
        val stages = mutableListOf<PrintStage>()
        val outcome = press().run(PrintJob.Due(forceSync = false)) { stages += it }

        assertEquals(3L, outcome.editionId)
        assertEquals(listOf(PrintStage.Syncing, PrintStage.Building), stages)
    }

    @Test
    fun theStagesAreReportedInOrderWithTheRatingCount() = scope.runTest {
        val stages = mutableListOf<PrintStage>()
        val outcome = press().run(PrintJob.Due(forceSync = false)) { stages += it }

        assertEquals(listOf(PrintStage.Syncing, PrintStage.Rating(0, 12), PrintStage.Rating(12, 12), PrintStage.Building), stages)
        assertEquals(12, outcome.rated)
    }

    @Test
    fun aRatingThatNeverEndsCannotHoldUpTheBuild() = scope.runTest {
        // OnDeviceAi holds the rating to its 45 s; even a rating that ignored it is cut off
        // with the sync at 75 s, and the edition is built.
        rating = PrintRating { _, _, onProgress ->
            onProgress(0, 12)
            awaitCancellation()
        }
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.Printed), printer.state.value)
    }

    @Test
    fun aBuildThatRunsOverFailsAndThePlaceholderOffersTryAgain() = scope.runTest {
        build = { awaitCancellation() }
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.Failed), printer.state.value)
        assertTrue(settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun aPrintThatRunsOverItsCapEndsAsAFailure() = scope.runTest {
        build = { awaitCancellation() }
        val printer = printer(cap = Duration.ofSeconds(30))
        printer.printIfDue()
        settle()
        assertEquals(finished(PrintResult.Failed), printer.state.value)
    }

    @Test
    fun nothingIsPrintedWhenNothingIsDue() = scope.runTest {
        status = DueStatus.Printed
        assertFalse(printer().printIfDue())
        status = DueStatus.NothingNew(DueRelease(EditionKind.MORNING, reset, LocalDate.of(2026, 11, 11)))
        assertFalse(printer().printIfDue())
        status = null
        assertFalse(printer().printIfDue())
        assertTrue(journal.all().isEmpty())
    }

    @Test
    fun aSecondOpenDuringAPrintJoinsIt() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        sync = {
            gate.await()
            SyncReport(feedsChecked = 3)
        }
        val printer = printer()

        assertTrue(printer.printIfDue())
        assertTrue("joined, not started again", printer.printIfDue())
        assertFalse("Print now while printing starts nothing", printer.printNow())
        assertFalse(printer.printExtra(setOf(4L)))
        gate.complete(Unit)
        settle()

        assertEquals(1, journal.all().count { it == "sync" })
        assertEquals(1, journal.all().count { it == "ensureCurrent" })
        assertEquals(finished(PrintResult.Printed), printer.state.value)
    }

    @Test
    fun aFailedPrintOnOpenIsNotRepeatedOnItsOwnButCanBeTriedAgain() = scope.runTest {
        buildFailure = IllegalStateException("disk full")
        val printer = printer()
        printer.printIfDue()
        settle()
        assertEquals(finished(PrintResult.Failed), printer.state.value)

        // Coming back to the stand does not start it again: the placeholder offers Try again.
        assertFalse(printer.printIfDue())
        assertEquals(1, journal.all().count { it == "ensureCurrent" })

        buildFailure = null
        clock.now = now.plusSeconds(60)
        assertTrue(printer.printNow())
        settle()
        assertEquals(PrintState.Finished(PrintResult.Printed, now.plusSeconds(60)), printer.state.value)
    }

    @Test
    fun printNowSyncsEvenAfterARecentSync() = scope.runTest {
        lastSyncAt = now.minus(Duration.ofMinutes(5))
        printer().printNow()
        settle()
        assertEquals("sync", journal.all().first())
    }

    @Test
    fun nothingNewFromCurrentPostsIsRecordedSoThePlaceholderGivesWayToTheNotice() = scope.runTest {
        somethingToPrint = false
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.NothingNew), printer.state.value)
        assertEquals(listOf(reset), settings.current.nothingNewFor)
        assertFalse(journal.all().any { it.startsWith("text:") })
    }

    @Test
    fun nothingNewAfterARecentSyncIsRecordedToo() = scope.runTest {
        somethingToPrint = false
        lastSyncAt = now.minus(Duration.ofMinutes(10))
        online = false
        val printer = printer()
        printer.printIfDue()
        settle()
        assertEquals(finished(PrintResult.NothingNew), printer.state.value)
        assertEquals(listOf(reset), settings.current.nothingNewFor)
    }

    @Test
    fun offlineWithNothingStoredIsUnreachableAndRecordsNothing() = scope.runTest {
        sync = { throw IOException("no network") }
        somethingToPrint = false
        val printer = printer()

        printer.printIfDue()
        settle()

        assertEquals(finished(PrintResult.Unreachable), printer.state.value)
        assertTrue("not known to be nothing new", settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun everyFeedFailingCountsAsOffline() = scope.runTest {
        sync = { SyncReport(feedsChecked = 40, feedsFailed = 40) }
        val printer = printer()
        printer.printNow()
        settle()
        assertEquals(finished(PrintResult.PrintedFromStored), printer.state.value)
    }

    @Test
    fun aFailedPrefetchStillCountsAsPrinted() = scope.runTest {
        val printer = EditionPrinter(
            scope = backgroundScope,
            press = ::press,
            clock = clock,
            dueStatus = { status },
            prefetch = { EditionPrefetch(editions, FakeFullText(journal) { throw IllegalStateException("parser") }, { _, _ -> }) },
        )
        printer.printIfDue()
        settle()
        assertEquals(finished(PrintResult.Printed), printer.state.value)
    }

    @Test
    fun theCoverDoesNotWaitForTheStories() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        val printer = EditionPrinter(
            scope = backgroundScope,
            press = ::press,
            clock = clock,
            dueStatus = { status },
            prefetch = { EditionPrefetch(editions, FakeFullText(journal), { _, _ -> gate.await() }) },
        )
        printer.printIfDue()
        runCurrent()
        assertEquals(finished(PrintResult.Printed), printer.state.value)
        gate.complete(Unit)
    }

    // --- Notices ---------------------------------------------------------------------------

    @Test
    fun aClosedNoticeIsForgottenUntilThereIsANewResult() = scope.runTest {
        sync = { throw IOException("no network") }
        val printer = printer()
        printer.printNow()
        settle()
        assertEquals(finished(PrintResult.PrintedFromStored), printer.state.value)

        printer.dismissResult()
        assertEquals("nothing to say after a rotation or on coming back", PrintState.Idle, printer.state.value)
        printer.dismissResult()
        assertEquals(PrintState.Idle, printer.state.value)

        somethingToPrint = false
        editions.released = emptyList()
        clock.now = now.plusSeconds(600)
        printer.printNow()
        settle()
        assertEquals(PrintState.Finished(PrintResult.Unreachable, now.plusSeconds(600)), printer.state.value)
    }

    @Test
    fun closingANoticeDoesNothingWhileAPrintIsUnderWay() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        sync = {
            gate.await()
            SyncReport(feedsChecked = 3)
        }
        val printer = printer()
        printer.printNow()

        printer.dismissResult()

        assertEquals(PrintState.Printing(PrintJob.Due(forceSync = true), PrintStage.Syncing), printer.state.value)
        gate.complete(Unit)
        settle()
        assertEquals(finished(PrintResult.Printed), printer.state.value)
    }

    // --- The Extra edition -----------------------------------------------------------------

    @Test
    fun anExtraEditionSyncsItsSourcesHasThemRatedAndPrints() = scope.runTest {
        status = DueStatus.Printed
        val printer = printer()

        assertTrue(printer.printExtra(setOf(5L, 4L)))
        settle()

        assertEquals(finished(PrintResult.ExtraPrinted), printer.state.value)
        val steps = journal.all()
        assertEquals(setOf("syncFeed:4", "syncFeed:5"), steps.take(2).toSet())
        assertEquals(listOf("extraShortlist:[4, 5]", "rate:2", "publishExtra:[4, 5]"), steps.subList(2, 5))
        assertFalse("sync" in steps)
    }

    @Test
    fun anExtraOnADateThatHasOneIsAReprint() = scope.runTest {
        editions.released = listOf(9L)
        val printer = printer()
        printer.printExtra(setOf(4L))
        settle()
        assertEquals(finished(PrintResult.ExtraReprinted), printer.state.value)
    }

    @Test
    fun anExtraWithNothingToPrintIsNotOfferedAgain() = scope.runTest {
        extraToPrint = false
        val dismissed = mutableListOf<Instant>()
        val recording = object : net.tgnra.morningissue.data.settings.SettingsRepository by settings {
            override suspend fun setExtraOfferDismissedAt(at: Instant) {
                dismissed += at
            }
        }
        val outcome = EditionPress(FakeSyncer(journal), publisher, editions, recording, clock, rating, zone = { ZoneOffset.UTC })
            .run(PrintJob.Extra(setOf(4L)))
        assertEquals(null, outcome.editionId)
        assertEquals(listOf(now), dismissed)
    }

}

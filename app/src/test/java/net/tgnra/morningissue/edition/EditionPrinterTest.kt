package net.tgnra.morningissue.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult
import net.tgnra.morningissue.sync.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Print now: [EditionPrinter] over the real [EarlyBuild] with fake collaborators. The clock
 * stands at 09:00, after the 06:30 reset of 11 November, with that edition unprinted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditionPrinterTest {
    private val now: Instant = Instant.parse("2026-11-11T09:00:00Z")
    private val reset: Instant = Instant.parse("2026-11-11T06:30:00Z")
    private val clock = MutableClock(now)
    private val journal = Journal()
    private val settings = FakeSettings()
    private val editions = FakeEditions(mapOf(3L to sampleEdition(3)))
    private val scope = TestScope(UnconfinedTestDispatcher())

    /** What the sync does; the default reaches the feeds. */
    private var sync: suspend () -> SyncReport = { SyncReport(feedsChecked = 3, newArticles = 5) }

    /** Whether the build finds something to print: it then puts edition 3 on the stand. */
    private var somethingToPrint = true
    private var buildFailure: Exception? = null

    private val publisher = object : EditionPublisher {
        override suspend fun ensureCurrentEdition(): Long? {
            journal.add("ensureCurrent")
            buildFailure?.let { throw it }
            if (somethingToPrint) editions.released = listOf(3L)
            return editions.released.firstOrNull()
        }

        override suspend fun buildNextEdition(): Long? = error("Print now never builds the next edition")
        override suspend fun nextReleaseAt(): Instant = Instant.parse("2026-11-12T06:30:00Z")
    }

    private fun printer() = EditionPrinter(
        scope = scope.backgroundScope,
        earlyBuild = {
            EarlyBuild(
                feedSyncer = FakeSyncer(journal) { sync() },
                publisher = publisher,
                editions = editions,
                fullTextFetcher = FakeFullText(journal),
                images = ImagePrefetcher { journal.add("images") },
                scheduleNext = { journal.add("schedule") },
                clock = clock,
                settings = settings,
                zone = { ZoneOffset.UTC },
            )
        },
        clock = clock,
    )

    @Test
    fun startsIdle() {
        assertEquals(PrintState.Idle, printer().state.value)
    }

    @Test
    fun printsTheDueEditionThroughEveryStage() = scope.runTest {
        val printer = printer()

        assertTrue(printer.printNow())
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.Printed, now), printer.state.value)
        val steps = journal.all()
        assertEquals(listOf("sync", "ensureCurrent"), steps.take(2))
        assertEquals((1..5L).map { "text:$it" }.toSet(), steps.filter { it.startsWith("text:") }.toSet())
        assertTrue(steps.indexOf("images") > steps.indexOf("ensureCurrent"))
        assertEquals("schedule", steps.last())
        assertTrue("an edition was printed, so nothing is written down", settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun showsEachStageWhileItRuns() = scope.runTest {
        val syncGate = CompletableDeferred<Unit>()
        val textGate = CompletableDeferred<Unit>()
        sync = {
            syncGate.await()
            SyncReport(feedsChecked = 3)
        }
        val printer = EditionPrinter(
            scope = backgroundScope,
            earlyBuild = {
                EarlyBuild(
                    feedSyncer = FakeSyncer(journal) { sync() },
                    publisher = publisher,
                    editions = editions,
                    fullTextFetcher = object : FullTextFetcher {
                        override suspend fun ensureFullText(articleId: Long): FullTextResult {
                            textGate.await()
                            return FullTextResult.Stored
                        }
                    },
                    images = ImagePrefetcher { },
                    scheduleNext = { },
                    clock = clock,
                    settings = settings,
                    zone = { ZoneOffset.UTC },
                )
            },
            clock = clock,
        )

        printer.printNow()
        assertEquals(PrintState.Printing(BuildStage.Syncing), printer.state.value)
        assertNull("no edition to hide yet", (printer.state.value as PrintState.Printing).editionId)

        syncGate.complete(Unit)
        // Not advanceUntilIdle: that would run the clock into the stage's own timeout.
        runCurrent()
        // The edition is stored; the stand keeps the placeholder up while its stories are fetched.
        val fetching = printer.state.value as PrintState.Printing
        assertEquals(BuildStage.FetchingText(3, 0, 5), fetching.stage)
        assertEquals(3L, fetching.editionId)

        textGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PrintState.Finished(PrintResult.Printed, now), printer.state.value)
    }

    @Test
    fun cannotBeStartedTwice() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        sync = {
            gate.await()
            SyncReport(feedsChecked = 3)
        }
        val printer = printer()

        assertTrue(printer.printNow())
        assertFalse("already under way", printer.printNow())
        assertFalse(printer.printNow())
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, journal.all().count { it == "sync" })
        assertEquals(1, journal.all().count { it == "ensureCurrent" })
        assertEquals(PrintState.Finished(PrintResult.Printed, now), printer.state.value)
    }

    @Test
    fun canBeStartedAgainOnceARunHasFinished() = scope.runTest {
        buildFailure = IllegalStateException("disk full")
        val printer = printer()
        printer.printNow()
        advanceUntilIdle()
        assertEquals(PrintState.Finished(PrintResult.Failed, now), printer.state.value)

        // Try again.
        buildFailure = null
        clock.now = now.plusSeconds(60)
        assertTrue(printer.printNow())
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.Printed, now.plusSeconds(60)), printer.state.value)
        assertEquals(2, journal.all().count { it == "sync" })
    }

    @Test
    fun aBuildThatThrowsIsAFailureAndRecordsNothing() = scope.runTest {
        buildFailure = IllegalStateException("disk full")
        val printer = printer()

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.Failed, now), printer.state.value)
        assertTrue("the placeholder stays", settings.current.nothingNewFor.isEmpty())
        assertEquals("the next background run is still scheduled", "schedule", journal.all().last())
    }

    @Test
    fun offlineItPrintsFromWhatIsStoredAndSaysSo() = scope.runTest {
        sync = { throw IOException("no network") }
        val printer = printer()

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.PrintedFromStored, now), printer.state.value)
        assertTrue(journal.all().containsAll(listOf("ensureCurrent", "text:1", "images")))
    }

    @Test
    fun everyFeedFailingCountsAsOffline() = scope.runTest {
        sync = { SyncReport(feedsChecked = 40, feedsFailed = 40) }
        val printer = printer()

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.PrintedFromStored, now), printer.state.value)
    }

    @Test
    fun offlineWithNothingStoredIsUnreachableAndThePlaceholderStays() = scope.runTest {
        sync = { throw IOException("no network") }
        somethingToPrint = false
        val printer = printer()

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.Unreachable, now), printer.state.value)
        assertTrue("not known to be nothing new", settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun nothingNewAfterAGoodSyncIsRecordedSoThePlaceholderGivesWayToTheNotice() = scope.runTest {
        somethingToPrint = false
        val printer = printer()

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.NothingNew, now), printer.state.value)
        assertEquals(listOf(reset), settings.current.nothingNewFor)
        assertFalse(journal.all().any { it.startsWith("text:") })
    }

    @Test
    fun aFailedPrefetchStillCountsAsPrinted() = scope.runTest {
        val printer = EditionPrinter(
            scope = backgroundScope,
            earlyBuild = {
                EarlyBuild(
                    feedSyncer = FakeSyncer(journal) { sync() },
                    publisher = publisher,
                    editions = editions,
                    fullTextFetcher = FakeFullText(journal) { throw IllegalStateException("parser") },
                    images = ImagePrefetcher { },
                    scheduleNext = { },
                    clock = clock,
                    settings = settings,
                    zone = { ZoneOffset.UTC },
                )
            },
            clock = clock,
        )

        printer.printNow()
        advanceUntilIdle()

        assertEquals(PrintState.Finished(PrintResult.Printed, now), printer.state.value)
    }

    @Test
    fun thePicturesAreTheLastStage() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        val printer = EditionPrinter(
            scope = backgroundScope,
            earlyBuild = {
                EarlyBuild(
                    feedSyncer = FakeSyncer(journal) { sync() },
                    publisher = publisher,
                    editions = editions,
                    fullTextFetcher = FakeFullText(journal),
                    images = ImagePrefetcher { gate.await() },
                    scheduleNext = { },
                    clock = clock,
                    settings = settings,
                    zone = { ZoneOffset.UTC },
                )
            },
            clock = clock,
        )

        printer.printNow()
        runCurrent()
        // Held at the pictures, after all five stories.
        assertEquals(PrintState.Printing(BuildStage.FetchingImages(3, 2)), printer.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PrintState.Finished(PrintResult.Printed, now), printer.state.value)
    }
}

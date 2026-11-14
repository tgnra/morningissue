package net.tgnra.morningissue.edition

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.images.ImageStoreReport
import net.tgnra.morningissue.data.images.ImageSweepReport
import net.tgnra.morningissue.data.images.ImageTrimReport
import net.tgnra.morningissue.sync.FullTextResult
import net.tgnra.morningissue.sync.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyBuildTest {
    private val now: Instant = Instant.parse("2026-11-11T05:00:00Z")
    private val clock = MutableClock(now)
    private val journal = Journal()
    private val publisher = FakePublisher(journal, nextRelease = now.plus(Duration.ofMinutes(90)))
    private val prefetchedImages = mutableListOf<String>()
    private val settings = FakeSettings()
    private val editions = FakeEditions(mapOf(7L to sampleEdition(7), 3L to sampleEdition(3)))

    /** The 06:30 reset of 11 November, which [now] is 90 minutes before, and the one before it. */
    private val reset: Instant = Instant.parse("2026-11-11T06:30:00Z")
    private val previousReset: Instant = Instant.parse("2026-11-10T06:30:00Z")

    private fun earlyBuild(
        syncer: FakeSyncer = FakeSyncer(journal),
        fullText: FakeFullText = FakeFullText(journal),
    ) = EarlyBuild(
        feedSyncer = syncer,
        publisher = publisher,
        editions = editions,
        fullTextFetcher = fullText,
        images = ImagePrefetcher { urls ->
            journal.add("images")
            prefetchedImages += urls
        },
        scheduleNext = { journal.add("schedule") },
        clock = clock,
        settings = settings,
        zone = { ZoneOffset.UTC },
    )

    @Test
    fun syncsBuildsPrefetchesThenSchedulesTheNextRun() = runTest {
        val outcome = earlyBuild().run()

        assertEquals(EarlyBuildOutcome(synced = true, editionId = 7, fullTextStored = 5, imagesRequested = 2), outcome)
        val steps = journal.all()
        assertEquals(listOf("sync", "buildNext"), steps.take(2))
        // Full text for every printed story, briefs included, but not for the index.
        assertEquals((1..5L).map { "text:$it" }.toSet(), steps.filter { it.startsWith("text:") }.toSet())
        // Images of the stories the pages show, once each; briefs and the index have none.
        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/3.jpg"), prefetchedImages)
        assertEquals("schedule", steps.last())
        assertTrue(steps.indexOf("images") > steps.indexOf("buildNext"))
    }

    @Test
    fun thePicturesOfEveryPrintedStoryGoToTheImageStoreTheOnesOnThePagesFirst() = runTest {
        val kept = mutableListOf<Long>()
        val store = object : ImageStore {
            override suspend fun storeForArticles(articleIds: List<Long>): ImageStoreReport {
                journal.add("store")
                kept += articleIds
                return ImageStoreReport()
            }
            override suspend fun totalBytes(): Long = 0
            override suspend fun trim(protectFrom: Instant, keepEditionIds: List<Long>, bytesToFree: Long) = ImageTrimReport()
            override suspend fun deleteForFeed(feedId: Long) = Unit
            override suspend fun sweep() = ImageSweepReport()
        }
        val build = EarlyBuild(
            feedSyncer = FakeSyncer(journal),
            publisher = publisher,
            editions = FakeEditions(mapOf(7L to sampleEdition(7))),
            fullTextFetcher = FakeFullText(journal),
            images = StoringImagePrefetcher(store),
            scheduleNext = { journal.add("schedule") },
            clock = clock,
            settings = settings,
            zone = { ZoneOffset.UTC },
        )

        val outcome = build.run()

        assertNull(outcome.error)
        // Every printed story once: the stories whose pictures the pages show, then the briefs.
        // The index is not printed.
        assertEquals(5, kept.size)
        assertEquals((1..5L).toSet(), kept.toSet())
        val briefs = sampleEdition(7).pages.filterIsInstance<net.tgnra.morningissue.model.EditionPage.InBrief>().flatMap { it.stories }.map { it.articleId }
        assertTrue(briefs.isNotEmpty())
        assertEquals(briefs, kept.takeLast(briefs.size))
        // After the full text, whose bodies name the pictures, and before the next run is scheduled.
        val steps = journal.all()
        assertTrue(steps.indexOf("store") > steps.indexOfLast { it.startsWith("text:") })
        assertTrue(steps.indexOf("store") < steps.indexOf("schedule"))
    }

    @Test
    fun aFailedSyncDoesNotPreventTheBuild() = runTest {
        val outcome = earlyBuild(syncer = FakeSyncer(journal) { throw IOException("offline") }).run()

        assertFalse(outcome.synced)
        assertEquals(7L, outcome.editionId)
        assertNull(outcome.error)
        assertTrue(journal.all().containsAll(listOf("sync", "buildNext", "images", "schedule")))
    }

    @Test
    fun aSyncThatNeverFinishesIsGivenUpOn() = runTest {
        val outcome = earlyBuild(syncer = FakeSyncer(journal) { awaitCancellation() }).run()

        assertFalse(outcome.synced)
        assertEquals(7L, outcome.editionId)
        assertEquals("schedule", journal.all().last())
    }

    @Test
    fun aFailedBuildStillSchedulesTheNextRun() = runTest {
        publisher.buildFailure = IllegalStateException("disk full")

        val outcome = earlyBuild().run()

        assertTrue(outcome.error is IllegalStateException)
        assertNull(outcome.editionId)
        assertEquals(listOf("sync", "buildNext", "schedule"), journal.all())
    }

    @Test
    fun nothingToBuildSkipsThePrefetch() = runTest {
        publisher.nextEditionId = null

        val outcome = earlyBuild().run()

        assertEquals(EarlyBuildOutcome(synced = true, editionId = null), outcome)
        assertEquals(listOf("sync", "buildNext", "schedule"), journal.all())
    }

    @Test
    fun aFailedPrefetchKeepsTheEdition() = runTest {
        val outcome = earlyBuild(fullText = FakeFullText(journal) { throw IllegalStateException("parser") }).run()

        assertEquals(7L, outcome.editionId)
        assertTrue(outcome.error is IllegalStateException)
        assertEquals("schedule", journal.all().last())
    }

    @Test
    fun onlyNewlyStoredTextIsCounted() = runTest {
        val outcome = earlyBuild(
            fullText = FakeFullText(journal) { if (it <= 2) FullTextResult.Stored else FullTextResult.AlreadyStored },
        ).run()

        assertEquals(2, outcome.fullTextStored)
    }

    @Test
    fun aRunThatMissedItsResetOnlyMakesSureOfTheCurrentEdition() = runTest {
        // Held back by Doze until after the 06:30 reset: the next one is 23 hours away.
        clock.now = Instant.parse("2026-11-11T07:30:00Z")
        publisher.nextRelease = Instant.parse("2026-11-12T06:30:00Z")
        // Today's edition (dated 11 November) is on the stand once the publisher has made sure of it.
        editions.released = listOf(3L)

        val outcome = earlyBuild(syncer = FakeSyncer(journal) { SyncReport() }).run()

        assertEquals(3L, outcome.editionId)
        assertTrue("an edition was printed, so nothing is written down", settings.current.nothingNewFor.isEmpty())
        val steps = journal.all()
        assertTrue("ensureCurrent" in steps)
        assertFalse("buildNext" in steps)
        assertEquals("schedule", steps.last())
    }

    @Test
    fun aRunAtTheEdgeOfTheWindowStillBuildsTheNextEdition() = runTest {
        publisher.nextRelease = now.plus(Duration.ofMinutes(180))
        earlyBuild().run()
        assertTrue("buildNext" in journal.all())
    }

    // --- Nothing new, as opposed to failed -----------------------------------------------------

    @Test
    fun aRunThatReachedTheFeedsAndFoundNothingWritesTheReleaseDown() = runTest {
        publisher.nextEditionId = null

        earlyBuild().run()

        assertEquals(listOf(reset), settings.current.nothingNewFor)
    }

    @Test
    fun aPrintedEditionWritesNothingDown() = runTest {
        earlyBuild().run()

        assertTrue(settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun aRunThatCouldNotSyncDoesNotClaimThereWasNothingNew() = runTest {
        publisher.nextEditionId = null

        val threw = earlyBuild(syncer = FakeSyncer(journal) { throw IOException("offline") }).run()
        val timedOut = earlyBuild(syncer = FakeSyncer(journal) { awaitCancellation() }).run()
        // The sync finished, but not one feed answered: the same as no network.
        val allFailed = earlyBuild(syncer = FakeSyncer(journal) { SyncReport(feedsChecked = 12, feedsFailed = 12) }).run()

        assertFalse(threw.synced)
        assertFalse(timedOut.synced)
        assertFalse(allFailed.synced)
        assertTrue(settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun someFeedsFailingIsStillASync() = runTest {
        publisher.nextEditionId = null

        val outcome = earlyBuild(syncer = FakeSyncer(journal) { SyncReport(feedsChecked = 12, feedsFailed = 11) }).run()

        assertTrue(outcome.synced)
        assertEquals(listOf(reset), settings.current.nothingNewFor)
    }

    @Test
    fun aBuildThatThrewWritesNothingDown() = runTest {
        publisher.buildFailure = IllegalStateException("disk full")

        earlyBuild().run()

        assertTrue(settings.current.nothingNewFor.isEmpty())
    }

    @Test
    fun aLateRunThatFindsNothingNewWritesTheResetItMissedDown() = runTest {
        clock.now = Instant.parse("2026-11-11T07:30:00Z")
        publisher.nextRelease = Instant.parse("2026-11-12T06:30:00Z")
        // The publisher answers with the newest paper on the stand, which is an older one.
        publisher.currentEditionId = 3

        val outcome = earlyBuild().run()

        assertNull("the older paper is not the edition this run was for", outcome.editionId)
        assertEquals(listOf(reset), settings.current.nothingNewFor)
        assertFalse("no prefetch of a paper that was not printed now", journal.all().any { it.startsWith("text:") })
    }

    @Test
    fun aLateRunWithTheEveningEditionOnAccountsForBothPapers() = runTest {
        settings.setEveningEditionEnabled(true)
        settings.setEveningEditionTime(LocalTime.of(18, 0))
        clock.now = Instant.parse("2026-11-11T19:00:00Z")
        publisher.nextRelease = Instant.parse("2026-11-12T06:30:00Z")
        val evening = Instant.parse("2026-11-11T18:00:00Z")

        // Neither paper exists afterwards: nothing new for either.
        earlyBuild().run()
        assertEquals(setOf(evening, reset), settings.current.nothingNewFor.toSet())
    }

    @Test
    fun aLateRunWithTheMorningPaperPrintedOnlyWritesTheEveningDown() = runTest {
        settings.setEveningEditionEnabled(true)
        clock.now = Instant.parse("2026-11-11T19:00:00Z")
        publisher.nextRelease = Instant.parse("2026-11-12T06:30:00Z")
        editions.released = listOf(3L)

        val outcome = earlyBuild().run()

        assertEquals(3L, outcome.editionId)
        assertEquals(listOf(Instant.parse("2026-11-11T18:00:00Z")), settings.current.nothingNewFor)
    }

    // --- Print now's use of the same stages ------------------------------------------------------

    @Test
    fun printNowBuildsTheDueEditionEvenWhenTheNextReleaseIsNear() = runTest {
        // 05:00, ninety minutes before the reset: the background job would build tomorrow's paper.
        // Yesterday's reset is the one that is due; edition 3 is dated on or after it.
        editions.released = listOf(3L)

        val outcome = earlyBuild().run(BuildTarget.Current)

        assertEquals(3L, outcome.editionId)
        val steps = journal.all()
        assertTrue("ensureCurrent" in steps)
        assertFalse("buildNext" in steps)
        assertEquals("schedule", steps.last())
    }

    @Test
    fun printNowThatFindsNothingWritesTheDueResetDown() = runTest {
        val outcome = earlyBuild().run(BuildTarget.Current)

        assertNull(outcome.editionId)
        assertTrue(outcome.synced)
        assertEquals(listOf(previousReset), settings.current.nothingNewFor)
    }

    @Test
    fun theStagesAreReportedInOrder() = runTest {
        val stages = mutableListOf<BuildStage>()

        earlyBuild().run { stages += it }

        assertEquals(BuildStage.Syncing, stages[0])
        assertEquals(BuildStage.Building, stages[1])
        assertEquals(BuildStage.FetchingText(7, 0, 5), stages[2])
        val text = stages.filterIsInstance<BuildStage.FetchingText>()
        assertEquals((0..5).toList(), text.map { it.done })
        assertEquals(BuildStage.FetchingImages(7, 2), stages.last())
    }

    @Test
    fun aRunThatPrintsNothingReportsNoFetchingStage() = runTest {
        publisher.nextEditionId = null
        val stages = mutableListOf<BuildStage>()

        earlyBuild().run { stages += it }

        assertEquals(listOf(BuildStage.Syncing, BuildStage.Building), stages)
    }
}

package net.tgnra.morningissue.edition

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
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

    private fun earlyBuild(
        syncer: FakeSyncer = FakeSyncer(journal),
        fullText: FakeFullText = FakeFullText(journal),
    ) = EarlyBuild(
        feedSyncer = syncer,
        publisher = publisher,
        editions = FakeEditions(mapOf(7L to sampleEdition(7), 3L to sampleEdition(3))),
        fullTextFetcher = fullText,
        images = ImagePrefetcher { urls ->
            journal.add("images")
            prefetchedImages += urls
        },
        scheduleNext = { journal.add("schedule") },
        clock = clock,
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

        val outcome = earlyBuild(syncer = FakeSyncer(journal) { SyncReport() }).run()

        assertEquals(3L, outcome.editionId)
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
}

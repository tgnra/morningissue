package net.tgnra.morningissue.server.editions

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.engine.Release
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.RefreshReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The stages of a build ([BuildRunner]), shared by the scheduler and Print now, and the Print
 * now and Extra jobs ([Jobs], [EditionJobActions]). Ported from the app's `EarlyBuildTest`:
 * the WorkManager parts (scheduling the next run) are the scheduler's now
 * ([SchedulerTest]), and the image store is the Kobo picture cache, warmed at the user's Kobo
 * widths.
 */
class RunBuildTest {
    private val now: Instant = Instant.parse("2026-11-11T05:00:00Z")
    private val world = TestWorld(now)
    private val clock = world.clock
    private val db = world.db
    private val journal = Journal()
    private val refresher = FakeRefresher(clock, journal)
    private val fullText = FakeFullText(journal)
    private val images = FakeKoboImages(journal)
    private val publisher = EditionPublisher(db, world.settings, clock)
    private val timings = EditionTimings(refreshTimeout = Duration.ofMillis(300))
    private val runner = BuildRunner(db, publisher, refresher, fullText, images, world.settings, clock, timings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Jobs(scope, clock, EditionJobActions(db, runner, publisher, refresher, world.settings, clock, timings))

    /** The 06:30 reset of 11 November, which [now] is 90 minutes before, and the one before it. */
    private val reset: Instant = Instant.parse("2026-11-11T06:30:00Z")
    private val previousReset: Instant = Instant.parse("2026-11-10T06:30:00Z")

    @After
    fun tearDown() {
        scope.cancel()
        world.close()
    }

    /** A user with [feeds] weekly feeds and a post each, first seen an hour before [now]. */
    private suspend fun user(feeds: Int = 6): Long {
        val user = world.addUser()
        repeat(feeds) { i ->
            val feed = world.addFeed(user, "Blog $user-$i")
            world.addPost(feed, firstSeenAt = now.minus(Duration.ofHours(1)), publishedAt = now.minus(Duration.ofHours(2L + i)))
        }
        return user
    }

    /** A user whose one feed has no posts at all. */
    private suspend fun quietUser(): Long = world.addUser().also { world.addFeed(it, "Empty $it") }

    private suspend fun scheduled(userId: Long, onStage: (JobStage, Int?, Int?) -> Unit = { _, _, _ -> }) =
        runner.run(userId, BuildTarget.Scheduled(Release(reset, EditionKind.MORNING)), refreshMaxAge = timings.prePrintMaxAge, onStage = onStage)

    private suspend fun current(userId: Long) = runner.run(userId, BuildTarget.Current, refreshMaxAge = Duration.ZERO)

    private suspend fun nothingNew(userId: Long) = world.settings.get(userId).nothingNew

    @Test
    fun refreshesBuildsThenFetchesTheTextAndTheKoboPictures() = runBlocking {
        val user = user()
        world.addDevice(user, width = 1264)

        val outcome = scheduled(user)

        val edition = db.editionDao().getByDate(user, LocalDate.of(2026, 11, 11), EditionKind.MORNING)!!
        assertEquals(BuildOutcome(synced = true, editionId = edition.id, fullTextStored = 0, imagesPrepared = true), outcome)
        assertEquals(reset, edition.releaseAt)
        val steps = journal.all()
        assertTrue(steps[0].startsWith("refresh:"))
        assertEquals(listOf("fulltext:${edition.id}", "images:${edition.id}"), steps.drop(1))
        assertEquals(listOf(edition.id to setOf(1264)), images.prepared)
    }

    @Test
    fun withoutAKoboNoPicturesArePrepared() = runBlocking {
        val user = user()
        world.addDevice(user)

        val outcome = scheduled(user)

        assertNotNull(outcome.editionId)
        assertFalse(outcome.imagesPrepared)
        assertTrue(images.prepared.isEmpty())
    }

    @Test
    fun aFailedRefreshDoesNotPreventTheBuild() = runBlocking {
        val user = user()
        refresher.behaviour = { throw IOException("offline") }

        val outcome = scheduled(user)

        assertFalse(outcome.synced)
        assertNotNull(outcome.editionId)
        assertNull(outcome.error)
        assertEquals(1, fullText.editions.size)
    }

    @Test
    fun aRefreshThatNeverFinishesIsGivenUpOn() = runBlocking {
        val user = user()
        refresher.behaviour = { awaitCancellation() }

        val outcome = scheduled(user)

        assertFalse(outcome.synced)
        assertNotNull(outcome.editionId)
    }

    @Test
    fun aFailedPrefetchKeepsTheEdition() = runBlocking {
        val user = user()
        world.addDevice(user, width = 1264)
        fullText.behaviour = { throw IllegalStateException("parser") }

        val outcome = scheduled(user)

        assertNotNull(outcome.editionId)
        assertNotNull(world.edition(user, outcome.editionId!!))
        assertTrue(outcome.error is IllegalStateException)
        assertFalse(outcome.imagesPrepared)
    }

    @Test
    fun onlyNewlyStoredTextIsCounted() = runBlocking {
        val user = user()
        fullText.behaviour = { 2 }

        assertEquals(2, scheduled(user).fullTextStored)
    }

    @Test
    fun nothingToBuildSkipsThePrefetch() = runBlocking {
        val user = quietUser()

        val outcome = scheduled(user)

        assertEquals(BuildOutcome(synced = true, editionId = null), outcome)
        assertTrue(fullText.editions.isEmpty())
        assertEquals(JobResult.NOTHING_NEW, outcome.printResult)
    }

    // --- Nothing new, as opposed to failed -----------------------------------------------------

    @Test
    fun aRunThatReachedTheFeedsAndFoundNothingWritesTheReleaseDown() = runBlocking {
        val user = quietUser()

        scheduled(user)

        assertEquals(listOf(reset), nothingNew(user))
    }

    @Test
    fun aPrintedEditionWritesNothingDown() = runBlocking {
        val user = user()

        scheduled(user)

        assertTrue(nothingNew(user).isEmpty())
    }

    @Test
    fun aRunThatCouldNotRefreshDoesNotClaimThereWasNothingNew() = runBlocking {
        val user = quietUser()

        refresher.behaviour = { throw IOException("offline") }
        val threw = current(user)
        refresher.behaviour = { awaitCancellation() }
        val timedOut = current(user)
        // The refresh finished, but not one feed answered: the same as no network.
        refresher.behaviour = { RefreshReport(it.size, 0, 0, finishedInTime = true) }
        val allFailed = current(user)

        assertFalse(threw.synced)
        assertFalse(timedOut.synced)
        assertFalse(allFailed.synced)
        assertEquals(JobResult.UNREACHABLE, allFailed.printResult)
        assertTrue(nothingNew(user).isEmpty())
    }

    @Test
    fun someFeedsFailingIsStillARefresh() = runBlocking {
        val user = quietUser()
        refresher.behaviour = { RefreshReport(12, 1, 0, finishedInTime = true) }

        val outcome = scheduled(user)

        assertTrue(outcome.synced)
        assertEquals(listOf(reset), nothingNew(user))
    }

    @Test
    fun theSchedulersOwnRefreshDecidesWhetherTheFeedsWereReached() = runBlocking {
        val user = quietUser()
        refresher.reached = false

        val outcome = runner.run(user, BuildTarget.Scheduled(Release(reset, EditionKind.MORNING)), refreshMaxAge = null)

        assertFalse(outcome.synced)
        assertTrue("no refresh of its own", refresher.calls.isEmpty())
        assertTrue(nothingNew(user).isEmpty())
    }

    // --- Late runs and Print now: the current edition --------------------------------------------

    @Test
    fun aRunThatMissedItsResetBuildsTheCurrentEdition() = runBlocking {
        val user = user()
        clock.now = Instant.parse("2026-11-11T07:30:00Z")

        val outcome = current(user)

        val edition = world.edition(user, outcome.editionId!!)!!
        assertEquals(LocalDate.of(2026, 11, 11), edition.date)
        assertEquals(reset, edition.releaseAt)
        assertEquals(clock.now, edition.cutoffAt)
        assertTrue("an edition was printed, so nothing is written down", nothingNew(user).isEmpty())
        assertEquals(JobResult.PRINTED, outcome.printResult)
    }

    @Test
    fun aLateRunThatFindsNothingNewWritesTheResetItMissedDown() = runBlocking {
        val user = quietUser()
        // Yesterday's paper exists, and nothing has been posted since.
        clock.now = Instant.parse("2026-11-10T07:00:00Z")
        world.addPost(world.addFeed(user, "Yesterday"), firstSeenAt = Instant.parse("2026-11-10T06:00:00Z"))
        val yesterday = publisher.ensureCurrentEdition(user)!!
        clock.now = Instant.parse("2026-11-11T07:30:00Z")

        val outcome = current(user)

        assertNull("the older paper is not the edition this run was for", outcome.editionId)
        assertEquals(listOf(reset), nothingNew(user))
        assertTrue("no prefetch of a paper that was not printed now", fullText.editions.isEmpty())
        assertEquals(1, db.editionDao().count(user))
        assertNotNull(world.edition(user, yesterday))
    }

    @Test
    fun aLateRunWithTheEveningEditionOnAccountsForBothPapers() = runBlocking {
        val user = quietUser()
        world.settings.update(user, customize = true) { it.copy(eveningEnabled = true) }
        clock.now = Instant.parse("2026-11-11T19:00:00Z")

        current(user)

        assertEquals(setOf(Instant.parse("2026-11-11T18:00:00Z"), reset), nothingNew(user).toSet())
    }

    @Test
    fun aLateRunWithTheMorningPaperPrintedOnlyWritesTheEveningDown() = runBlocking {
        val user = user()
        world.settings.update(user, customize = true) { it.copy(eveningEnabled = true) }
        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        val morning = publisher.ensureCurrentEdition(user)!!
        clock.now = Instant.parse("2026-11-11T19:00:00Z")

        val outcome = current(user)

        assertEquals(morning, outcome.editionId)
        assertEquals(listOf(Instant.parse("2026-11-11T18:00:00Z")), nothingNew(user))
    }

    @Test
    fun printNowBuildsTheDueEditionEvenWhenTheNextReleaseIsNear() = runBlocking {
        // 05:00, ninety minutes before the reset: the scheduler would build today's paper.
        // Yesterday's reset is the one that is due.
        val user = user()

        val outcome = current(user)

        val edition = world.edition(user, outcome.editionId!!)!!
        assertEquals(LocalDate.of(2026, 11, 10), edition.date)
        assertEquals(previousReset, edition.releaseAt)
        assertNull(db.editionDao().getByDate(user, LocalDate.of(2026, 11, 11), EditionKind.MORNING))
    }

    @Test
    fun printNowThatFindsNothingWritesTheDueResetDown() = runBlocking {
        val user = quietUser()

        val outcome = current(user)

        assertNull(outcome.editionId)
        assertTrue(outcome.synced)
        assertEquals(listOf(previousReset), nothingNew(user))
    }

    @Test
    fun theStagesAreReportedInOrder() = runBlocking {
        val user = user()
        world.addDevice(user, width = 1072)
        val stages = mutableListOf<Triple<JobStage, Int?, Int?>>()

        val outcome = scheduled(user) { stage, done, total -> stages += Triple(stage, done, total) }

        val printed = db.editionDao().getItems(user, outcome.editionId!!).count { it.item.kind != ItemKind.INDEX }
        assertEquals(
            listOf(
                Triple(JobStage.FETCHING_FEEDS, null, null),
                Triple(JobStage.SETTING_PAGES, null, null),
                Triple(JobStage.FETCHING_TEXT, 0, printed),
                Triple(JobStage.FETCHING_TEXT, printed, printed),
                Triple(JobStage.FETCHING_IMAGES, null, null),
            ),
            stages,
        )
    }

    @Test
    fun aRunThatPrintsNothingReportsNoFetchingStage() = runBlocking {
        val user = quietUser()
        val stages = mutableListOf<JobStage>()

        scheduled(user) { stage, _, _ -> stages += stage }

        assertEquals(listOf(JobStage.FETCHING_FEEDS, JobStage.SETTING_PAGES), stages)
    }

    @Test
    fun theOutcomeOfAPrint() {
        assertEquals(JobResult.PRINTED, BuildOutcome(synced = true, editionId = 1).printResult)
        assertEquals(JobResult.PRINTED_OFFLINE, BuildOutcome(synced = false, editionId = 1).printResult)
        assertEquals(JobResult.PRINTED, BuildOutcome(synced = true, editionId = 1, error = IOException()).printResult)
        assertEquals(JobResult.FAILED, BuildOutcome(synced = true, editionId = null, error = IOException()).printResult)
        assertEquals(JobResult.NOTHING_NEW, BuildOutcome(synced = true, editionId = null).printResult)
        assertEquals(JobResult.UNREACHABLE, BuildOutcome(synced = false, editionId = null).printResult)
    }

    @Test
    fun eachUsersPicturesArePreparedAtTheirOwnKoboWidths() = runBlocking {
        val a = user()
        val b = user()
        world.addDevice(a, width = 1264)
        world.addDevice(b, width = 758)
        world.addDevice(b, width = 1072)

        val first = scheduled(a)
        val second = scheduled(b)

        assertEquals(setOf(first.editionId!! to setOf(1264), second.editionId!! to setOf(758, 1072)), images.prepared.toSet())
        // Each built from their own feeds only.
        val feedsOfA = db.editionArticleDao().subscribedFeedIds(a).toSet()
        assertTrue(db.editionDao().getItems(b, second.editionId!!).none { it.row.article.feedId in feedsOfA })
    }

    // --- The Print now and Extra jobs --------------------------------------------------------------

    private suspend fun finished(userId: Long, jobId: String) = jobs.coroutineOf(jobId)!!.join().let { jobs.job(userId, jobId) }

    @Test
    fun printNowRefreshesEveryFeedWithNoLeewayAndPrints() = runBlocking {
        val user = user()
        // Every feed was tried a minute ago; Print now fetches them anyway.
        db.editionArticleDao().subscribedFeedIds(user).forEach { refresher.lastAttempt[it] = now.minus(Duration.ofMinutes(1)) }

        val start = jobs.print(user)
        val job = finished(user, start.jobId)

        assertFalse(start.alreadyRunning)
        assertEquals(JobState.DONE, job.state)
        assertEquals(JobResult.PRINTED, job.outcome!!.result)
        assertNotNull(world.edition(user, job.outcome!!.editionId!!))
        assertEquals(db.editionArticleDao().subscribedFeedIds(user).toSet(), refresher.fetches.keys)
        assertEquals(JobStage.FETCHING_TEXT, job.stage)
    }

    @Test
    fun printNowAskedAgainWhileRunningAnswersTheRunningJob() = runBlocking {
        val user = user()
        val other = user()
        val release = CompletableDeferred<Unit>()
        refresher.behaviour = { release.await(); RefreshReport(it.size, it.size, 0, finishedInTime = true) }

        val first = jobs.print(user)
        val again = jobs.print(user)
        val theirs = jobs.print(other)

        assertTrue(again.alreadyRunning)
        assertEquals(first.jobId, again.jobId)
        assertFalse("another user's print is their own", theirs.alreadyRunning)
        assertEquals(first.jobId, jobs.runningJobId(user, net.tgnra.morningissue.api.JobKind.PRINT))
        assertEquals(JobState.RUNNING, jobs.job(user, first.jobId).state)
        release.complete(Unit)
        assertEquals(JobState.DONE, finished(user, first.jobId).state)
        assertEquals(JobState.DONE, finished(other, theirs.jobId).state)
    }

    @Test
    fun anotherUsersJobIsNotFound() = runBlocking {
        val user = user()
        val other = world.addUser()
        val start = jobs.print(user)
        finished(user, start.jobId)

        try {
            jobs.job(other, start.jobId)
            fail("another user's job")
        } catch (e: ApiFailure) {
            assertEquals(ApiError.NOT_FOUND, e.code)
        }
    }

    @Test
    fun printNowWithNothingNewSaysSo() = runBlocking {
        val user = quietUser()

        val job = finished(user, jobs.print(user).jobId)

        assertEquals(JobResult.NOTHING_NEW, job.outcome!!.result)
        assertNull(job.outcome!!.editionId)
    }

    @Test
    fun anExtraPrintsThenReprintsAndRefreshesOnlyTheChosenFeeds() = runBlocking {
        val user = user()
        clock.now = Instant.parse("2026-11-11T10:00:00Z")
        val essays = world.addFeed(user, "New essays", tier = CadenceTier.RARE, medianHours = 720.0)
        (1L..3L).forEach { world.addPost(essays, firstSeenAt = clock.now, publishedAt = clock.now.minus(Duration.ofDays(it))) }

        val printed = finished(user, jobs.extra(user, ExtraRequest(listOf(essays))).jobId)

        assertEquals(JobResult.EXTRA_PRINTED, printed.outcome!!.result)
        val extra = world.edition(user, printed.outcome!!.editionId!!)!!
        assertEquals(EditionKind.EXTRA, extra.kind)
        assertEquals(setOf(essays), refresher.calls.last())

        clock.now = Instant.parse("2026-11-11T12:00:00Z")
        val notes = world.addFeed(user, "New notes")
        world.addPost(notes, firstSeenAt = clock.now)
        val reprinted = finished(user, jobs.extra(user, ExtraRequest(listOf(notes))).jobId)

        assertEquals(JobResult.EXTRA_REPRINTED, reprinted.outcome!!.result)
        assertEquals(extra.id, reprinted.outcome!!.editionId)
        assertNull(world.settings.get(user).extraOfferDismissedAt)
    }

    @Test
    fun anExtraWithNothingToPrintTurnsTheOfferDown() = runBlocking {
        val user = user()
        val other = user()
        clock.now = Instant.parse("2026-11-11T10:00:00Z")
        val empty = world.addFeed(user, "Empty")
        // Another user's feed with news: not the user's to print.
        val theirs = world.addFeed(other, "Theirs")
        world.addPost(theirs, firstSeenAt = clock.now)

        val job = finished(user, jobs.extra(user, ExtraRequest(listOf(empty, theirs))).jobId)

        assertEquals(JobResult.EXTRA_NOTHING, job.outcome!!.result)
        assertEquals(setOf(empty), refresher.calls.last())
        assertEquals(clock.now.toEpochMilli(), world.settings.get(user).extraOfferDismissedAt)
        assertFalse("turning the offer down is not a setting the user chose", world.settings.isCustomized(user))
        assertNull(world.settings.get(other).extraOfferDismissedAt)
        assertEquals(0, db.editionDao().count(user))
    }

    @Test
    fun anExtraNamesAtLeastOneFeed() = runBlocking {
        val user = user()
        try {
            jobs.extra(user, ExtraRequest(emptyList()))
            fail("no feeds")
        } catch (e: ApiFailure) {
            assertEquals(ApiError.BAD_REQUEST, e.code)
        }
    }
}

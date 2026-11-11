package net.tgnra.morningissue.edition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.sync.SyncReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `WorkManagerEditionScheduler` and `EarlyBuildWorker` against the work-testing WorkManager. */
@RunWith(AndroidJUnit4::class)
class EarlyBuildSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The evening before the 06:30 reset of 11 November (UTC). */
    private val evening: Instant = Instant.parse("2026-11-10T20:00:00Z")
    private val nextReset: Instant = Instant.parse("2026-11-11T06:30:00Z")
    private val followingReset: Instant = Instant.parse("2026-11-12T06:30:00Z")

    private val clock = MutableClock(evening)
    private val settings = FakeSettings()
    private var zone: ZoneId = ZoneOffset.UTC
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal = Journal()
    private val publisher = FakePublisher(journal, nextRelease = nextReset)

    /** What the worker's sync does; tests swap it to hold a run open. */
    private var syncBehaviour: suspend () -> SyncReport = { SyncReport() }

    private lateinit var db: MorningIssueDatabase
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerEditionScheduler

    private val workerFactory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == EarlyBuildWorker::class.java.name) {
                EarlyBuildWorker(appContext, workerParameters, earlyBuild())
            } else {
                null
            }
    }

    private fun earlyBuild() = EarlyBuild(
        feedSyncer = FakeSyncer(journal) { syncBehaviour() },
        publisher = publisher,
        editions = FakeEditions(mapOf(7L to sampleEdition(7))),
        fullTextFetcher = FakeFullText(journal),
        images = ImagePrefetcher { journal.add("images") },
        scheduleNext = { scheduler.reschedule() },
        clock = clock,
        settings = settings,
        zone = { zone },
    )

    @Before
    fun setUp() {
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(workerFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
        db = MorningIssueDatabase.inMemory(context)
        scheduler = WorkManagerEditionScheduler(
            workManager = { workManager },
            settings = settings,
            editionDao = db.editionDao(),
            clock = clock,
            scope = scope,
            zone = { zone },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWorkFlow(WorkManagerEditionScheduler.UNIQUE_WORK_NAME).first()

    private suspend fun awaitInfos(predicate: (List<WorkInfo>) -> Boolean): List<WorkInfo> = withTimeout(20_000) {
        workManager.getWorkInfosForUniqueWorkFlow(WorkManagerEditionScheduler.UNIQUE_WORK_NAME).first(predicate)
    }

    private fun List<WorkInfo>.inState(state: WorkInfo.State) = filter { it.state == state }

    private fun WorkInfo.aimsAt(reset: Instant) = WorkManagerEditionScheduler.resetTag(reset) in tags

    private suspend fun storeEdition(date: LocalDate, releaseAt: Instant, kind: EditionKind = EditionKind.MORNING) {
        db.editionDao().insertEditionWithItems(
            EditionEntity(
                date = date,
                kind = kind,
                cutoffAt = clock.now,
                releaseAt = releaseAt,
                builtAt = clock.now,
                length = EditionLength.STANDARD,
                number = 1,
            ),
            emptyList(),
        )
    }

    @Test
    fun schedulesUniqueWorkNinetyMinutesBeforeTheNextReset() = runBlocking {
        scheduler.reschedule()

        val work = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        // 20:00 to 05:00 the next morning.
        assertEquals(Duration.ofHours(9).toMillis(), work.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(work.aimsAt(nextReset))
        assertTrue(EarlyBuildWorker::class.java.name in work.tags)
    }

    @Test
    fun schedulingAgainKeepsASingleSchedule() = runBlocking {
        scheduler.reschedule()
        val first = infos().single()

        clock.now = evening.plus(Duration.ofHours(1))
        scheduler.reschedule()
        scheduler.reschedule()

        assertEquals(first.id, infos().single().id)
    }

    @Test
    fun changingTheResetTimeReplacesTheSchedule() = runBlocking {
        scheduler.reschedule()
        val first = infos().single()

        settings.setResetTime(LocalTime.of(4, 0))
        scheduler.reschedule()

        val work = infos().single()
        assertFalse(work.id == first.id)
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        // 20:00 to 02:30.
        assertEquals(Duration.ofMinutes(390).toMillis(), work.initialDelayMillis)
        assertTrue(work.aimsAt(Instant.parse("2026-11-11T04:00:00Z")))
    }

    @Test
    fun aTimeZoneChangeReplacesTheSchedule() = runBlocking {
        scheduler.reschedule()

        // 05:00 on the 11th in Tokyo: the 06:30 reset there is 90 minutes away, so build now.
        zone = ZoneId.of("Asia/Tokyo")
        scheduler.reschedule()

        val work = infos().single()
        assertEquals(0L, work.initialDelayMillis)
        assertTrue(work.aimsAt(Instant.parse("2026-11-10T21:30:00Z")))
    }

    @Test
    fun whenTheBuildMomentHasPassedItRunsSoonRatherThanSkippingTheDay() = runBlocking {
        clock.now = Instant.parse("2026-11-11T05:45:00Z")

        scheduler.reschedule()

        val work = infos().single()
        assertEquals(0L, work.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(work.aimsAt(nextReset))
    }

    @Test
    fun withTheEveningEditionOnItAimsAtWhicheverReleaseComesNext() = runBlocking {
        val eveningRelease = Instant.parse("2026-11-11T18:00:00Z")
        settings.setEveningEditionEnabled(true)

        // 20:00: tomorrow's morning reset comes before tomorrow's evening release.
        scheduler.reschedule()
        assertTrue(infos().single().aimsAt(nextReset))

        // 07:00 the next day: the evening release at 18:00 is next, built from 16:30.
        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        scheduler.reschedule()
        var work = infos().single()
        assertTrue(work.aimsAt(eveningRelease))
        assertEquals(Duration.ofMinutes(9 * 60 + 30).toMillis(), work.initialDelayMillis)

        // Once the evening edition is stored, the following morning is the target.
        storeEdition(LocalDate.of(2026, 11, 11), releaseAt = eveningRelease, kind = EditionKind.EVENING)
        scheduler.reschedule()
        assertTrue(infos().single().aimsAt(followingReset))

        // A morning edition for that date does not count as the evening one, and a new
        // evening time moves the schedule.
        clock.now = Instant.parse("2026-11-12T07:00:00Z")
        storeEdition(LocalDate.of(2026, 11, 12), releaseAt = followingReset)
        settings.setEveningEditionTime(LocalTime.of(21, 0))
        scheduler.reschedule()
        work = infos().single()
        assertTrue(work.aimsAt(Instant.parse("2026-11-12T21:00:00Z")))

        // Switched off again: back to the morning reset.
        settings.setEveningEditionEnabled(false)
        scheduler.reschedule()
        assertTrue(infos().single().aimsAt(Instant.parse("2026-11-13T06:30:00Z")))
    }

    @Test
    fun whenTheComingEditionIsAlreadyBuiltItAimsForTheOneAfter() = runBlocking {
        clock.now = Instant.parse("2026-11-11T05:45:00Z")
        storeEdition(LocalDate.of(2026, 11, 11), releaseAt = nextReset)

        scheduler.reschedule()

        val work = infos().single()
        // 05:45 to 05:00 the next day.
        assertEquals(Duration.ofMinutes(23 * 60 + 15).toMillis(), work.initialDelayMillis)
        assertTrue(work.aimsAt(followingReset))
    }

    @Test
    fun scheduleEarlyBuildReturnsAtOnceAndSchedulesInTheBackground() = runBlocking {
        scheduler.scheduleEarlyBuild()

        val work = awaitInfos { it.isNotEmpty() }.single()
        assertEquals(Duration.ofHours(9).toMillis(), work.initialDelayMillis)
    }

    @Test
    fun workerRunsTheEarlyBuildAndSucceeds() = runBlocking {
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        val worker = TestListenableWorkerBuilder<EarlyBuildWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val steps = journal.all()
        assertEquals(listOf("sync", "buildNext"), steps.take(2))
        assertTrue(steps.containsAll(listOf("text:1", "text:5", "images")))
        // It scheduled the next run through the real scheduler.
        assertEquals(1, infos().inState(WorkInfo.State.ENQUEUED).size)
    }

    @Test
    fun workerSucceedsEvenWhenSyncAndBuildFail() = runBlocking {
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        syncBehaviour = { throw java.io.IOException("offline") }
        publisher.buildFailure = IllegalStateException("broken")
        val worker = TestListenableWorkerBuilder<EarlyBuildWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf("sync", "buildNext"), journal.all())
        assertEquals(1, infos().inState(WorkInfo.State.ENQUEUED).size)
    }

    @Test
    fun aScheduledRunBuildsAndChainsTheNextDaysRun() = runBlocking {
        scheduler.reschedule()
        val first = infos().single()
        // The job fires at 05:00.
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(first.id)
        driver.setAllConstraintsMet(first.id)

        val after = awaitInfos { all ->
            all.any { it.id == first.id && it.state == WorkInfo.State.SUCCEEDED } &&
                all.any { it.state == WorkInfo.State.ENQUEUED }
        }

        assertEquals(2, after.size)
        val next = after.inState(WorkInfo.State.ENQUEUED).single()
        assertTrue(next.aimsAt(followingReset))
        assertEquals(Duration.ofHours(24).toMillis(), next.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
        assertEquals(1, journal.all().count { it == "sync" })
        assertEquals(1, journal.all().count { it == "buildNext" })
    }

    @Test
    fun aRunThatFoundNothingToBuildIsNotRepeatedForTheSameReset() = runBlocking {
        publisher.nextEditionId = null
        clock.now = Instant.parse("2026-11-11T05:45:00Z")
        scheduler.reschedule()
        val first = infos().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        awaitInfos { all ->
            all.any { it.id == first.id && it.state == WorkInfo.State.SUCCEEDED } &&
                all.any { it.state == WorkInfo.State.ENQUEUED }
        }

        // The app starts a few times before the reset; no edition exists for it.
        repeat(3) { scheduler.reschedule() }

        val all = infos()
        val waiting = all.inState(WorkInfo.State.ENQUEUED).single()
        assertTrue(waiting.aimsAt(followingReset))
        assertTrue(waiting.initialDelayMillis > 0)
        assertEquals(1, journal.all().count { it == "sync" })
    }

    @Test
    fun schedulingDuringARunDoesNotCancelIt() = runBlocking {
        val syncStarted = CompletableDeferred<Unit>()
        val letSyncFinish = CompletableDeferred<Unit>()
        syncBehaviour = {
            syncStarted.complete(Unit)
            letSyncFinish.await()
            SyncReport()
        }
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        scheduler.reschedule()
        val first = infos().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        withTimeout(20_000) { syncStarted.await() }
        awaitInfos { all -> all.any { it.id == first.id && it.state == WorkInfo.State.RUNNING } }

        // The app is opened mid-run, twice.
        scheduler.reschedule()
        scheduler.reschedule()

        val during = infos()
        assertEquals(WorkInfo.State.RUNNING, during.single { it.id == first.id }.state)
        val chained = during.single { it.id != first.id }
        assertEquals(WorkInfo.State.BLOCKED, chained.state)
        assertTrue(chained.aimsAt(followingReset))

        letSyncFinish.complete(Unit)
        val after = awaitInfos { all ->
            all.any { it.id == first.id && it.state == WorkInfo.State.SUCCEEDED } &&
                all.any { it.state == WorkInfo.State.ENQUEUED }
        }

        // The run finished its build, and its own rescheduling added nothing more.
        assertEquals(2, after.size)
        assertEquals(chained.id, after.inState(WorkInfo.State.ENQUEUED).single().id)
        assertTrue("buildNext" in journal.all())
    }
}

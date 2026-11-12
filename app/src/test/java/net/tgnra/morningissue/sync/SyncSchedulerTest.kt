package net.tgnra.morningissue.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalProvider = SyncWorker.syncerProvider
    private val originalRelay = SyncWorker.relayDone

    @Before
    fun setUp() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @After
    fun tearDown() {
        SyncWorker.syncerProvider = originalProvider
        SyncWorker.relayDone = originalRelay
    }

    private fun infos(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun schedulesTwoUniquePeriodicRequests() {
        WorkManagerSyncScheduler(context).ensurePeriodicSync()

        val any = infos(WorkManagerSyncScheduler.WORK_ANY_NETWORK).single()
        assertEquals(WorkInfo.State.ENQUEUED, any.state)
        assertEquals(NetworkType.CONNECTED, any.constraints.requiredNetworkType)
        assertTrue(any.constraints.requiresBatteryNotLow())
        assertFalse(any.constraints.requiresCharging())
        assertEquals(TimeUnit.HOURS.toMillis(2), any.periodicityInfo!!.repeatIntervalMillis)

        val charging = infos(WorkManagerSyncScheduler.WORK_CHARGING).single()
        assertEquals(WorkInfo.State.ENQUEUED, charging.state)
        assertEquals(NetworkType.UNMETERED, charging.constraints.requiredNetworkType)
        assertTrue(charging.constraints.requiresCharging())
        assertEquals(TimeUnit.HOURS.toMillis(1), charging.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun ensureIsIdempotent() {
        val scheduler = WorkManagerSyncScheduler(context)
        scheduler.ensurePeriodicSync()
        val before = listOf(WorkManagerSyncScheduler.WORK_ANY_NETWORK, WorkManagerSyncScheduler.WORK_CHARGING).map { infos(it).single().id }

        repeat(3) { scheduler.ensurePeriodicSync() }

        val after = listOf(WorkManagerSyncScheduler.WORK_ANY_NETWORK, WorkManagerSyncScheduler.WORK_CHARGING).map { infos(it) }
        // Still one request each, and the same ones: the schedule was not restarted.
        assertEquals(before, after.map { it.single().id })
        assertTrue(after.all { it.single().state == WorkInfo.State.ENQUEUED })
    }

    private class FakeSyncer(private val report: SyncReport) : FeedSyncer {
        var calls = 0
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
        override suspend fun syncAll(): SyncReport = report.also { calls++ }
        override suspend fun syncFeed(feedId: Long): SyncReport = SyncReport()
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<SyncWorker>(context).build().doWork() }

    @Test
    fun workerRunsAFullSync() {
        val syncer = FakeSyncer(SyncReport(feedsChecked = 10, feedsFailed = 2, newArticles = 7))
        SyncWorker.syncerProvider = { syncer }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, syncer.calls)
    }

    @Test
    fun workerRetriesWhenEveryFeedFailed() {
        SyncWorker.syncerProvider = { FakeSyncer(SyncReport(feedsChecked = 10, feedsFailed = 10)) }
        assertEquals(ListenableWorker.Result.retry(), runWorker())

        // No subscriptions is not a failure.
        SyncWorker.syncerProvider = { FakeSyncer(SyncReport()) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun workerRunsRelayDoneAfterTheSyncAndIgnoresItsFailure() {
        val syncer = FakeSyncer(SyncReport(feedsChecked = 3))
        SyncWorker.syncerProvider = { syncer }
        var relayRuns = 0
        SyncWorker.relayDone = {
            relayRuns++
            assertEquals(1, syncer.calls)
            throw java.io.IOException("relay down")
        }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(1, relayRuns)
    }

    @Test
    fun workerFailsCleanlyWithoutTheApp() {
        // Unit tests run with a plain Application, so there is no container to ask.
        assertEquals(ListenableWorker.Result.failure(), runWorker())
    }
}

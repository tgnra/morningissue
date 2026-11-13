package net.tgnra.morningissue.client

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import net.tgnra.morningissue.MorningIssueApp

/**
 * Background [ServerSync]. Offline or a failed run is retried with WorkManager's backoff; a
 * phone that is not logged in (or not migrated yet) has nothing to sync.
 */
class ClientSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = syncProvider(applicationContext) ?: return Result.failure()
        return when (sync.sync(inputData.getString(KEY_REASON) ?: "background")) {
            is SyncOutcome.Synced, SyncOutcome.NotReady, SyncOutcome.SignedOut -> Result.success()
            SyncOutcome.Offline, is SyncOutcome.Failed -> Result.retry()
        }
    }

    companion object {
        internal const val KEY_REASON = "reason"

        /** Where the worker finds the sync; replaced in tests, which have no [MorningIssueApp]. */
        @Volatile
        internal var syncProvider: (Context) -> ServerSync? = { context ->
            (context.applicationContext as? MorningIssueApp)?.container?.serverSync
        }
    }
}

/**
 * When [ClientSyncWorker] runs (`docs/server-design.md`, 5.2): every [PERIOD] on any network;
 * once at the next release plus [AFTER_RELEASE], re-aimed after every sync; and at once when
 * the app starts more than [STALE_AFTER] after the last sync, or comes back to the foreground.
 */
class ClientSyncScheduler(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** UPDATE keeps the schedule's timing, so calling this on every start does not postpone the next run. */
    fun ensurePeriodicSync() {
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest())
    }

    /** Aims the one-off run at [releaseAt] plus [AFTER_RELEASE] (replacing the last aim). */
    fun aimAtRelease(releaseAt: Instant, now: Instant = Instant.now()) {
        workManager.enqueueUniqueWork(WORK_RELEASE, ExistingWorkPolicy.REPLACE, releaseRequest(releaseAt, now))
    }

    /** A run now (on start, on coming back to the foreground); one already waiting is kept. */
    fun syncNow(reason: String) {
        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, nowRequest(reason))
    }

    /** On app start: a run now when the last one is older than [STALE_AFTER], or there was none. */
    fun syncIfStale(lastSyncAt: Instant?, now: Instant = Instant.now()) {
        if (isStale(lastSyncAt, now)) syncNow("start")
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_PERIODIC)
        workManager.cancelUniqueWork(WORK_RELEASE)
        workManager.cancelUniqueWork(WORK_NOW)
    }

    companion object {
        const val WORK_PERIODIC = "client-sync"
        const val WORK_RELEASE = "client-sync-release"
        const val WORK_NOW = "client-sync-now"

        val PERIOD: Duration = Duration.ofHours(2)
        val AFTER_RELEASE: Duration = Duration.ofMinutes(2)
        val STALE_AFTER: Duration = Duration.ofMinutes(30)

        fun isStale(lastSyncAt: Instant?, now: Instant): Boolean =
            lastSyncAt == null || Duration.between(lastSyncAt, now) > STALE_AFTER

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ClientSyncWorker>(PERIOD.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(network)
                .setInputData(workDataOf(ClientSyncWorker.KEY_REASON to "periodic"))
                .build()

        internal fun releaseRequest(releaseAt: Instant, now: Instant): OneTimeWorkRequest {
            val delay = Duration.between(now, releaseAt.plus(AFTER_RELEASE)).coerceAtLeast(Duration.ZERO)
            return OneTimeWorkRequestBuilder<ClientSyncWorker>()
                .setConstraints(network)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ClientSyncWorker.KEY_REASON to "release"))
                .build()
        }

        internal fun nowRequest(reason: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClientSyncWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(ClientSyncWorker.KEY_REASON to reason))
                .build()
    }
}

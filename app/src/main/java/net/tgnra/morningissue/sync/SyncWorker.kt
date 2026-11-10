package net.tgnra.morningissue.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import net.tgnra.morningissue.MorningIssueApp

/**
 * Background sync: runs [FeedSyncer.syncAll]. A run that overlaps another sync (pull to
 * refresh, the early edition build, the other periodic request) joins it instead of fetching
 * twice.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val syncer = syncerProvider(applicationContext) ?: return Result.failure()
        val report = syncer.syncAll()
        // Every feed failing means the connection went away; let WorkManager back off and retry.
        return if (report.feedsChecked > 0 && report.feedsFailed == report.feedsChecked) Result.retry() else Result.success()
    }

    companion object {
        /** Where the worker finds the syncer; replaced in tests, which have no [MorningIssueApp]. */
        @Volatile
        internal var syncerProvider: (Context) -> FeedSyncer? = { context ->
            (context.applicationContext as? MorningIssueApp)?.container?.feedSyncer
        }
    }
}

/**
 * The real [SyncScheduler]. The plan asks for a sync about every hour when unmetered and
 * charging, and every two hours otherwise. WorkManager cannot vary one request's interval by
 * conditions, so there are two unique periodic requests:
 *
 * - [WORK_ANY_NETWORK]: every 2 h on any connection, unless the battery is low;
 * - [WORK_CHARGING]: every 1 h, only while charging on an unmetered network.
 *
 * On mobile data or battery only the 2 h request runs. When both are eligible their runs may
 * fall close together; that costs little, because overlapping runs share one sync and feeds
 * that have not changed answer 304.
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    override fun ensurePeriodicSync() {
        val workManager = WorkManager.getInstance(context)
        // UPDATE keeps an existing schedule's timing and only refreshes its settings, so
        // calling this on every process start does not postpone the next run.
        workManager.enqueueUniquePeriodicWork(WORK_ANY_NETWORK, ExistingPeriodicWorkPolicy.UPDATE, anyNetworkRequest())
        workManager.enqueueUniquePeriodicWork(WORK_CHARGING, ExistingPeriodicWorkPolicy.UPDATE, chargingRequest())
    }

    companion object {
        const val WORK_ANY_NETWORK = "periodic-sync"
        const val WORK_CHARGING = "periodic-sync-charging"

        internal fun anyNetworkRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

        internal fun chargingRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()
    }
}

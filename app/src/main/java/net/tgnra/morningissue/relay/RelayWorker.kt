package net.tgnra.morningissue.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.tgnra.morningissue.MorningIssueApp

/**
 * Relay sync after the early build: Done, so the package carries what was read on the Kobos,
 * then Publish. A job of its own, queued by the build's last stage, so that it has its own
 * time limit and a relay that is down cannot hold up or fail the build. It does nothing when
 * no relay or no relay device is set.
 */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val relay = (applicationContext as? MorningIssueApp)?.container?.relaySync ?: return Result.success()
        relay.afterEarlyBuild()
        // A failure waits for the next build, or the Publish button: the package would be stale by a retry anyway.
        return Result.success()
    }

    companion object {
        const val WORK_PUBLISH = "relay-publish"

        fun enqueuePublish(context: Context) {
            val request = OneTimeWorkRequestBuilder<RelayWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_PUBLISH, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

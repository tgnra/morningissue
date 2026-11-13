package net.tgnra.morningissue.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.tgnra.morningissue.MorningIssueApp
import net.tgnra.morningissue.R

/**
 * Deletes the relay group after a sync session was cancelled by leaving the app. The session
 * sends its own `DELETE /v1/group` from the app's scope; this is the backup, in case the
 * process dies before that is sent. Harmless when the group is already gone, and it leaves
 * alone a session opened since (`RelaySync.deleteAbandonedGroup`).
 */
class RelayCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val relay = (applicationContext as? MorningIssueApp)?.container?.relaySync ?: return Result.success()
        return if (relay.deleteAbandonedGroup()) Result.success() else Result.retry()
    }

    /** Before Android 12 an expedited job runs as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.relay_cleanup_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.relay_cleanup_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_CLEANUP = "relay-cleanup"
        private const val CHANNEL = "relay-cleanup"
        private const val NOTIFICATION_ID = 7301

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RelayCleanupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_CLEANUP, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

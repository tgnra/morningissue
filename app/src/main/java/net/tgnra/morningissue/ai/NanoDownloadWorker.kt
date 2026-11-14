package net.tgnra.morningissue.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import net.tgnra.morningissue.appContainer

/**
 * Fetches Gemini Nano when the phone supports it but does not have it yet. The model is large
 * (AICore downloads it, not the app), so the job only runs on an unmetered network while the
 * phone is charging. A failed download is tried again later, a few times; after that the next
 * start of the app asks again.
 */
class NanoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val done = applicationContext.appContainer.onDeviceAi.download()
        return when {
            done -> Result.success()
            runAttemptCount + 1 >= MAX_ATTEMPTS -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "nano-download"
        private const val MAX_ATTEMPTS = 5

        /** The request: unmetered and charging, retried after an hour and then longer. */
        fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<NanoDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()

        /** Asks for the download unless it is already waiting or running. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request())
        }
    }
}

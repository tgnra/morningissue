package net.tgnra.morningissue.edition

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.tgnra.morningissue.appContainer

/**
 * The one-off job aimed at `earlyBuildLead` before the reset: see [EarlyBuild] for what it
 * does and [WorkManagerEditionScheduler] for when it runs. Best effort by design: WorkManager
 * may hold it back or never start it (Doze, a force stop, the device off). Nothing is built
 * silently in its place; the newsstand shows the edition as unprinted and offers Print now
 * (see [DueEditions] for exactly when).
 *
 * It always reports success: retrying a sync or build that failed is the next run's and the
 * reader's job, and a failure result would cancel the next run chained behind it. What the
 * stand needs to know is not WorkManager's result but what [EarlyBuild] stores: the edition,
 * or the record that there was nothing new to print.
 */
class EarlyBuildWorker(
    context: Context,
    params: WorkerParameters,
    private val earlyBuild: EarlyBuild?,
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory; dependencies come from the app container. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    override suspend fun doWork(): Result {
        val build = earlyBuild ?: EarlyBuild.from(applicationContext.appContainer)
        val outcome = build.run()
        if (outcome.error != null) {
            Log.w(TAG, "Early build failed; the stand will offer Print now", outcome.error)
        } else {
            Log.i(TAG, "Early build: synced=${outcome.synced} edition=${outcome.editionId} " +
                "fullText=${outcome.fullTextStored} images=${outcome.imagesRequested}")
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "EarlyBuildWorker"
    }
}

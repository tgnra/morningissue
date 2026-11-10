package net.tgnra.morningissue.edition

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.tgnra.morningissue.appContainer

/**
 * The one-off job aimed at `earlyBuildLead` before the reset: see [EarlyBuild] for what it
 * does and [WorkManagerEditionScheduler] for when it runs. Best effort by design; when it does
 * not run in time, opening the app builds the edition instead.
 *
 * It always reports success: retrying a sync or build that failed is the next run's and the
 * on-open fallback's job, and a failure result would cancel the next run chained behind it.
 */
class EarlyBuildWorker(
    context: Context,
    params: WorkerParameters,
    private val earlyBuild: EarlyBuild?,
) : CoroutineWorker(context, params) {
    /** Used by WorkManager's default factory; dependencies come from the app container. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    override suspend fun doWork(): Result {
        val build = earlyBuild ?: EarlyBuild.from(applicationContext, applicationContext.appContainer)
        val outcome = build.run()
        if (outcome.error != null) {
            Log.w(TAG, "Early build failed; the edition will be built on open", outcome.error)
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

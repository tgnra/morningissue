package net.tgnra.morningissue

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import net.tgnra.morningissue.client.ClientSyncScheduler

class MorningIssueApp : Application(), SingletonImageLoader.Factory {
    /** Created on first use so tests and receivers do not pay for what they do not touch. */
    val container: DefaultAppContainer by lazy { DefaultAppContainer(this) }

    /** Process start counts as the first foreground; later ones sync when they come. */
    private var startedOnce = false

    override fun onCreate() {
        super.onCreate()
        // Coming back to the foreground syncs, so the stand is current when the reader looks.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    if (startedOnce) container.appScope.launch { syncIfLoggedIn("foreground") }
                    startedOnce = true
                }
            },
        )
        // Off the main thread: building the container opens the database and settings.
        container.appScope.launch {
            retireLocalWork()
            val auth = container.auth.current()
            if (!auth.isLoggedIn) return@launch
            container.syncScheduler.ensurePeriodicSync()
            if (ClientSyncScheduler.isStale(auth.lastSyncAt, container.clock.instant())) container.syncScheduler.syncNow("start")
            // Image files whose rows went with a removed post, and rows whose files are gone.
            try {
                container.images.sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Swept again at the next start.
            }
        }
    }

    private suspend fun syncIfLoggedIn(reason: String) {
        if (container.auth.current().isLoggedIn) container.syncScheduler.syncNow(reason)
    }

    /** The work an earlier version scheduled to sync feeds and build editions on the phone. */
    private fun retireLocalWork() {
        val workManager = WorkManager.getInstance(this)
        for (name in RETIRED_WORK) workManager.cancelUniqueWork(name)
    }

    /** Coil's `AsyncImage` and `context.imageLoader` use the container's loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader

    private companion object {
        val RETIRED_WORK = listOf(
            "periodic-sync",
            "periodic-sync-charging",
            "early-edition-build",
            "relay-publish",
            "relay-cleanup",
            "teaser-backfill",
        )
    }
}

/** The process-wide container, for workers, receivers and other non-Compose code. */
val Context.appContainer: DefaultAppContainer
    get() = (applicationContext as MorningIssueApp).container

package net.tgnra.morningissue

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.tgnra.morningissue.edition.PrintState
import net.tgnra.morningissue.sync.SyncStatus
import net.tgnra.morningissue.sync.TeaserBackfillWorker

class MorningIssueApp : Application(), SingletonImageLoader.Factory {
    /** Created on first use so tests and receivers do not pay for what they do not touch. */
    val container: DefaultAppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(container.foreground)
        // Off the main thread: building the modules opens the database and settings.
        container.appScope.launch {
            container.syncScheduler.ensurePeriodicSync()
            // Once after upgrading: fetch the pages of stored posts whose feed gave only a teaser.
            TeaserBackfillWorker.ensureDone(this@MorningIssueApp)
        }
        watchOnDeviceAi()
        // Work that earlier versions scheduled and this one no longer has: the Kobo relay's, the
        // server client's sync, and the early edition build (editions are now printed in the
        // foreground when the app is opened). A worker class that is gone would only fail.
        val workManager = WorkManager.getInstance(this)
        for (name in RETIRED_WORK) workManager.cancelUniqueWork(name)
        // Posts stored before database version 2, or brought in while the app was not running,
        // get their traits and embeddings. On the app scope's background threads, and only once
        // the newsstand has had time to show and to start printing today's edition if it has
        // to: the pass writes in short transactions, and the build should not queue behind one.
        container.appScope.launch {
            delay(SIGNALS_START_DELAY_MILLIS)
            try {
                container.signals.run()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again after the next sync.
            }
            // Image files whose rows went with a removed post, and rows whose files are gone.
            try {
                container.images.sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Swept again after the next sync.
            }
        }
    }

    /**
     * The on-device model (Gemini Nano): where it stands, checked once the start has settled,
     * which also asks for its download when the phone supports it but does not have it; then a
     * rating pass after every sync that finishes while the app is in the foreground, so the next
     * print has less to rate. AICore refuses the background, so nothing is rated otherwise. A
     * print rates its own shortlist (`EditionPress`), so no pass is started while one runs.
     */
    private fun watchOnDeviceAi() {
        container.appScope.launch {
            delay(SIGNALS_START_DELAY_MILLIS)
            container.onDeviceAi.refresh()
        }
        container.appScope.launch {
            var syncing = false
            container.feedSyncer.status.collect { status ->
                val running = status is SyncStatus.Running
                val printing = container.editionPrinter.state.value is PrintState.Printing
                if (syncing && !running && container.foreground.isForeground && !printing) container.onDeviceAi.rateSoon()
                syncing = running
            }
        }
    }

    /** Coil's `AsyncImage` and `context.imageLoader` use the container's loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

/** Unique work names of earlier versions: the relay sync's, the server client's, and the early edition build's. */
private val RETIRED_WORK = listOf("relay-publish", "relay-cleanup", "client-sync", "client-sync-release", "client-sync-now", "early-edition-build")

/** How long after process start the first `SignalsUpkeep` pass begins. */
private const val SIGNALS_START_DELAY_MILLIS = 5_000L

/** The process-wide container, for workers, receivers and other non-Compose code. */
val Context.appContainer: DefaultAppContainer
    get() = (applicationContext as MorningIssueApp).container

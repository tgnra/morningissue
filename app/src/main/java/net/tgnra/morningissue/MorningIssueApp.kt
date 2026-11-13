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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.sync.TeaserBackfillWorker

class MorningIssueApp : Application(), SingletonImageLoader.Factory {
    /** Created on first use so tests and receivers do not pay for what they do not touch. */
    val container: DefaultAppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // A relay sync session lasts only while the app is in front: leaving it cancels the
        // session, which deletes its group on the relay.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) container.relaySyncIfMade?.cancelForLeaving()
            },
        )
        // Off the main thread: building the modules opens the database and settings.
        container.appScope.launch {
            container.syncScheduler.ensurePeriodicSync()
            // Once after upgrading: fetch the pages of stored posts whose feed gave only a teaser.
            TeaserBackfillWorker.ensureDone(this@MorningIssueApp)
            // Runs once now and again whenever a release time changes.
            container.settings.settings
                .map { listOf(it.resetTime, it.eveningEditionEnabled, it.eveningEditionTime, it.resetChange, it.eveningChange) }
                .distinctUntilChanged()
                .collect { container.editionScheduler.scheduleEarlyBuild() }
        }
        // Once after upgrading: delete the group an earlier version kept on the relay between
        // syncs. A network request of its own, so that nothing above waits for it.
        container.appScope.launch {
            // The job that published after each build is gone; an earlier version may have queued it.
            WorkManager.getInstance(this@MorningIssueApp).cancelUniqueWork("relay-publish")
            try {
                container.relaySync.clearLeftoverGroup()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tried again at the next start.
            }
        }
        // Posts stored before database version 2, or brought in while the app was not running,
        // get their traits and embeddings. On the app scope's background threads, and only once
        // the newsstand has had time to show and to build today's edition if it has to: the
        // pass writes in short transactions, and the build should not queue behind one.
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

    /** Coil's `AsyncImage` and `context.imageLoader` use the container's loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

/** How long after process start the first `SignalsUpkeep` pass begins. */
private const val SIGNALS_START_DELAY_MILLIS = 5_000L

/** The process-wide container, for workers, receivers and other non-Compose code. */
val Context.appContainer: DefaultAppContainer
    get() = (applicationContext as MorningIssueApp).container

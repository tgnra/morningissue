package net.tgnra.morningissue

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MorningIssueApp : Application(), SingletonImageLoader.Factory {
    /** Created on first use so tests and receivers do not pay for what they do not touch. */
    val container: DefaultAppContainer by lazy { DefaultAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Off the main thread: building the modules opens the database and settings.
        container.appScope.launch {
            container.syncScheduler.ensurePeriodicSync()
            // Runs once now and again whenever a release time changes.
            container.settings.settings
                .map { Triple(it.resetTime, it.eveningEditionEnabled, it.eveningEditionTime) }
                .distinctUntilChanged()
                .collect { container.editionScheduler.scheduleEarlyBuild() }
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

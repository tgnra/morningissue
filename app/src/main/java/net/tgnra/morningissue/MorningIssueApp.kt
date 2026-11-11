package net.tgnra.morningissue

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
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
    }

    /** Coil's `AsyncImage` and `context.imageLoader` use the container's loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

/** The process-wide container, for workers, receivers and other non-Compose code. */
val Context.appContainer: DefaultAppContainer
    get() = (applicationContext as MorningIssueApp).container

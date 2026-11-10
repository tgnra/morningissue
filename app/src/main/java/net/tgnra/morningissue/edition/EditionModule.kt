package net.tgnra.morningissue.edition

import androidx.work.WorkManager
import net.tgnra.morningissue.CoreComponents
import net.tgnra.morningissue.sync.SyncComponents

/** What the edition layer offers to the rest of the app. */
class EditionComponents(
    val publisher: EditionPublisher,
    val scheduler: EditionScheduler,
)

/**
 * The edition layer's wiring. **Owned by the engine/edition agent**: replace the stubs below
 * with the real implementations here, so `AppContainer` never needs to change. [sync] gives
 * the early build its final sync (`feedSyncer`) and full-text prefetch (`fullTextFetcher`).
 *
 * `EarlyBuildWorker` is created by WorkManager, not here: it takes the syncer, the fetcher and
 * the two components below from the app container when it runs (see `EarlyBuild.from`), so
 * [sync] is not needed at wiring time.
 */
object EditionModule {
    @Suppress("UNUSED_PARAMETER")
    fun create(core: CoreComponents, sync: SyncComponents): EditionComponents = EditionComponents(
        publisher = RoomEditionPublisher(
            database = core.database,
            settings = core.settings,
            clock = core.clock,
        ),
        scheduler = WorkManagerEditionScheduler(
            workManager = { WorkManager.getInstance(core.appContext) },
            settings = core.settings,
            editionDao = core.database.editionDao(),
            clock = core.clock,
            scope = core.appScope,
        ),
    )
}

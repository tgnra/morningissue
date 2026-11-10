package net.tgnra.morningissue.sync

import net.tgnra.morningissue.CoreComponents

/** What the sync layer offers to the rest of the app. */
class SyncComponents(
    val feedSyncer: FeedSyncer,
    val syncScheduler: SyncScheduler,
    val feedDiscovery: FeedDiscovery,
    val fullTextFetcher: FullTextFetcher,
    val opmlTransfer: OpmlTransfer,
)

/**
 * The sync layer's wiring. **Owned by the sync agent**: replace the stubs below with the real
 * implementations here, so `AppContainer` never needs to change.
 */
object SyncModule {
    @Suppress("UNUSED_PARAMETER")
    fun create(core: CoreComponents): SyncComponents = SyncComponents(
        feedSyncer = NoOpFeedSyncer(),
        syncScheduler = NoOpSyncScheduler(),
        feedDiscovery = NoOpFeedDiscovery(),
        fullTextFetcher = NoOpFullTextFetcher(),
        opmlTransfer = NoOpOpmlTransfer(),
    )
}

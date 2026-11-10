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
 * The sync layer's wiring. **Owned by the sync agent**: the real implementations are built
 * here, so `AppContainer` never needs to change.
 */
object SyncModule {
    fun create(core: CoreComponents): SyncComponents {
        val fetcher = FeedFetcher(core.okHttpClient)
        return SyncComponents(
            feedSyncer = DefaultFeedSyncer(core.database, fetcher, core.clock, core.appScope),
            syncScheduler = WorkManagerSyncScheduler(core.appContext),
            feedDiscovery = DefaultFeedDiscovery(fetcher, core.clock),
            fullTextFetcher = DefaultFullTextFetcher(core.database, fetcher, core.clock),
            opmlTransfer = DefaultOpmlTransfer(core.feeds, core.clock),
        )
    }
}

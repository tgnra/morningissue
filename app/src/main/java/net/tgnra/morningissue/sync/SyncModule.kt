package net.tgnra.morningissue.sync

import net.tgnra.morningissue.CoreComponents
import net.tgnra.morningissue.data.repository.FeedHealthRepository

/** What the sync layer offers to the rest of the app. */
class SyncComponents(
    val feedSyncer: FeedSyncer,
    val syncScheduler: SyncScheduler,
    val feedDiscovery: FeedDiscovery,
    val fullTextFetcher: FullTextFetcher,
    val opmlTransfer: OpmlTransfer,
    val feedHealth: FeedHealthRepository,
)

/**
 * The sync layer's wiring. **Owned by the sync agent**: the real implementations are built
 * here, so `AppContainer` never needs to change.
 */
object SyncModule {
    fun create(core: CoreComponents): SyncComponents {
        val fetcher = FeedFetcher(core.okHttpClient)
        val discovery = DefaultFeedDiscovery(fetcher, core.clock)
        val healthChecker = FeedHealthChecker(core.database, FeedRepairer(fetcher, discovery, core.clock), core.clock)
        // Quiet feeds are fetched less often; after every full sync the health check runs, the
        // new posts get their traits and embeddings (before the trim, which removes the text
        // they are computed from), the image store is swept of files and rows that have lost
        // their other half, then stored images and text are trimmed to the storage budget.
        val syncer = MaintainedFeedSyncer(
            delegate = DefaultFeedSyncer(core.database, fetcher, core.clock, core.appScope, dueFeeds = healthChecker::dueFeeds),
            afterSync = listOf({ healthChecker.run() }, { core.signals.run() }, { core.images.sweep() }, { core.storage.trimToBudget() }),
        )
        return SyncComponents(
            feedSyncer = syncer,
            syncScheduler = WorkManagerSyncScheduler(core.appContext),
            feedDiscovery = discovery,
            fullTextFetcher = DefaultFullTextFetcher(core.database, fetcher, core.clock),
            opmlTransfer = DefaultOpmlTransfer(core.feeds, core.clock),
            feedHealth = DefaultFeedHealthRepository(
                core.database, healthChecker, syncer, PrefsDeskNoticeStore(core.appContext), core.clock,
            ),
        )
    }
}

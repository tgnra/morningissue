package net.tgnra.morningissue.sync

import kotlinx.coroutines.flow.StateFlow

/** The outcome of one sync run. */
data class SyncReport(
    val feedsChecked: Int = 0,
    val feedsFailed: Int = 0,
    /** Posts stored for the first time; they belong to the next edition. */
    val newArticles: Int = 0,
)

sealed interface SyncStatus {
    data object Idle : SyncStatus

    /** [feedsDone] of [feedsTotal] feeds have been fetched so far. */
    data class Running(val feedsDone: Int, val feedsTotal: Int) : SyncStatus
}

/**
 * Fetches feeds and stores new posts. **Owned by the sync agent** (`sync/`); the stub wired in
 * milestone 0 is [NoOpFeedSyncer]. Called by onboarding and Sources in the UI, by
 * `SyncWorker`, and by the early edition build (which is also what Print now runs).
 *
 * Implementations write through the DAOs: new posts with `ArticleDao.insertNew` (first-seen
 * time = now), validators with `FeedDao.updateSyncState`, cadence with `FeedDao.updateCadence`,
 * and fetch outcomes with `FeedHealthDao.recordSuccess` / `recordError`. Syncing never builds
 * or changes an edition.
 */
interface FeedSyncer {
    /** [SyncStatus.Running] with progress while a sync is in flight, otherwise [SyncStatus.Idle]. */
    val status: StateFlow<SyncStatus>

    /**
     * Fetches every subscribed feed and returns once all have been tried. A feed that fails is
     * counted in [SyncReport.feedsFailed] and does not fail the run; this only throws on
     * cancellation. If a sync is already running, joins it and returns its report.
     */
    suspend fun syncAll(): SyncReport

    /** Fetches one feed now (for example right after it was added). Same error rules as [syncAll]. */
    suspend fun syncFeed(feedId: Long): SyncReport
}

/**
 * Keeps the periodic background sync scheduled. **Owned by the sync agent**; the stub is
 * [NoOpSyncScheduler]. Called from `MorningIssueApp` on every process start.
 */
interface SyncScheduler {
    /**
     * Enqueues the periodic `SyncWorker` if it is not already scheduled. Idempotent and cheap;
     * must not block.
     */
    fun ensurePeriodicSync()
}

class NoOpFeedSyncer : FeedSyncer {
    override val status: StateFlow<SyncStatus> = kotlinx.coroutines.flow.MutableStateFlow(SyncStatus.Idle)
    override suspend fun syncAll(): SyncReport = SyncReport()
    override suspend fun syncFeed(feedId: Long): SyncReport = SyncReport()
}

class NoOpSyncScheduler : SyncScheduler {
    override fun ensurePeriodicSync() = Unit
}

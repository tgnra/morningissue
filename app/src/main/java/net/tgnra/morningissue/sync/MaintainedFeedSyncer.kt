package net.tgnra.morningissue.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * A [FeedSyncer] that runs the housekeeping due after every full sync: the feed health check
 * and the storage trim. Both are background work on the sync's own thread; a failure in either
 * never fails the sync, and a run that finds the previous one still going skips its turn.
 */
class MaintainedFeedSyncer(
    private val delegate: FeedSyncer,
    private val afterSync: List<suspend () -> Unit>,
) : FeedSyncer {
    private val busy = Mutex()

    override val status: StateFlow<SyncStatus> get() = delegate.status

    override suspend fun syncAll(): SyncReport {
        val report = delegate.syncAll()
        if (busy.tryLock()) {
            try {
                for (task in afterSync) {
                    try {
                        task()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Housekeeping is retried after the next sync.
                    }
                }
            } finally {
                busy.unlock()
            }
        }
        return report
    }

    override suspend fun syncFeed(feedId: Long): SyncReport = delegate.syncFeed(feedId)
}

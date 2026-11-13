package net.tgnra.morningissue.server.sync

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.AffinityLearner
import org.slf4j.LoggerFactory

/**
 * Relearns a user's affinity a while after their opened, read, saved or Liked state changed
 * (4.2): debounced by [delay], so a batch of events, or several batches in a row, cost one
 * pass. Runs in [scope].
 */
class Relearner(
    private val database: ServerDatabase,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val delay: Duration = Duration.ofSeconds(10),
    config: RankingConfig = RankingConfig.Default,
) {
    private val learner = AffinityLearner(database, config)
    private val pending = ConcurrentHashMap<Long, Job>()

    /** Schedules a relearn for [userId], replacing one still waiting. Returns the job. */
    fun schedule(userId: Long): Job {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(delay.toMillis())
            try {
                relearnNow(userId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("Relearning affinity for user {} failed", userId, e)
            }
        }
        pending.put(userId, job)?.cancel()
        job.invokeOnCompletion { pending.remove(userId, job) }
        job.start()
        return job
    }

    /** Relearns and stores [userId]'s affinity at once. */
    suspend fun relearnNow(userId: Long) {
        database.withWriteTransaction { learner.refresh(userId, clock.instant()) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Relearner::class.java)
    }
}

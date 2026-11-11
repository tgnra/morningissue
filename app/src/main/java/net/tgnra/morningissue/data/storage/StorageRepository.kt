package net.tgnra.morningissue.data.storage

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.RankingConfig

/** Stored article text against the budget, in bytes. */
data class StorageUsage(val usedBytes: Long, val budgetBytes: Long) {
    val overBudget: Boolean get() = usedBytes > budgetBytes
}

data class TrimReport(val articlesTrimmed: Int = 0, val bytesFreed: Long = 0)

/**
 * The storage budget. It governs stored article text: the bodies in `article_content` and
 * their copy in the search index. Images are not counted; they live in Coil's disk cache,
 * which keeps to its own fixed cap. Implemented by [RoomStorageRepository].
 *
 * Trimming drops the text of the oldest editions' posts first. It never touches a Liked or
 * saved post, the newest editions, or posts waiting for the next edition, and it never removes
 * a post, a headline, a summary or an edition: a trimmed post opens with its summary and the
 * reader fetches the page again.
 */
interface StorageRepository {
    /** Reads every stored body's length, so not for the main thread's every frame; cheap enough per screen. */
    suspend fun usage(): StorageUsage

    /** Trims only when over budget, and then down to a little under it. Runs after each full sync. */
    suspend fun trimToBudget(): TrimReport

    /** Trims everything that may be trimmed, whatever the budget ("export and free up space"). */
    suspend fun trimAll(): TrimReport
}

class RoomStorageRepository(
    private val database: MorningIssueDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StorageRepository {
    private val articleDao = database.articleDao()
    private val editionDao = database.editionDao()
    private val lock = Mutex()

    override suspend fun usage(): StorageUsage = withContext(dispatcher) {
        StorageUsage(used(), settings.settings.first().storageBudgetMb * BYTES_PER_MB)
    }

    /** Characters, which is bytes for most text and a slight undercount for the rest. */
    private suspend fun used(): Long = articleDao.contentSize() + articleDao.indexedBodySize()

    override suspend fun trimToBudget(): TrimReport = withContext(dispatcher) {
        lock.withLock {
            val budget = settings.settings.first().storageBudgetMb * BYTES_PER_MB
            val used = used()
            if (used <= budget) TrimReport() else trimTo((budget * config.storageTrimTarget).toLong(), used)
        }
    }

    override suspend fun trimAll(): TrimReport = withContext(dispatcher) {
        lock.withLock { trimTo(0, used()) }
    }

    private suspend fun trimTo(target: Long, used: Long): TrimReport {
        val keepEditionIds = editionDao.getRecent(config.storageKeepEditions).map { it.id }
        // Posts first seen after the latest cutoff are waiting for the next edition.
        val protectFrom = editionDao.getLatestByCutoff()?.cutoffAt
            ?: clock.instant().minus(Duration.ofDays(config.firstEditionWindowDays))
        var freed = 0L
        var trimmed = 0
        while (used - freed > target) {
            val batch = articleDao.trimCandidates(protectFrom, keepEditionIds, BATCH)
            if (batch.isEmpty()) break
            val take = ArrayList<Long>(batch.size)
            for (row in batch) {
                if (used - freed <= target) break
                take += row.articleId
                freed += row.size
            }
            articleDao.trimContent(take)
            trimmed += take.size
        }
        return TrimReport(trimmed, freed)
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val BATCH = 200
    }
}

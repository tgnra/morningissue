package net.tgnra.morningissue.data.storage

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.images.ImageTrimReport
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.RankingConfig

/** Stored article text and stored images against the budget, in bytes. */
data class StorageUsage(
    /** Text and images together. */
    val usedBytes: Long,
    val budgetBytes: Long,
    /** Of [usedBytes], the stored images. The rest is text. */
    val imageBytes: Long = 0,
) {
    val textBytes: Long get() = usedBytes - imageBytes
    val overBudget: Boolean get() = usedBytes > budgetBytes
}

/** [bytesFreed] counts text and images together; [articlesTrimmed] is posts that lost their text. */
data class TrimReport(val articlesTrimmed: Int = 0, val bytesFreed: Long = 0, val imagesTrimmed: Int = 0)

/**
 * The storage budget. It governs stored article text (the bodies in `article_content` and
 * their copy in the search index) and the images in the image store (`data/images/`).
 * Implemented by [RoomStorageRepository].
 *
 * Trimming takes images before text, and within each the oldest editions' posts first. An
 * image can be fetched again for as long as its site keeps it and a post reads without it;
 * a post's text often cannot be had again once the post has left its feed, and without it
 * there is nothing to read or search. So every image that may go is gone before the first
 * body is dropped.
 *
 * The phone holds a cache of the server's library, so the budget runs after every sync.
 * Trimming never touches a Liked or saved post (text or images) or the newest editions, and it
 * never removes a post, a headline, a summary or an edition: a trimmed post opens with its
 * summary while its text is fetched again from the server (`GET /articles/{id}`), and its
 * pictures are fetched again too.
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
    /** The image store, whose files count against the budget; null counts and trims text alone. */
    private val images: ImageStore? = null,
) : StorageRepository {
    private val articleDao = database.articleDao()
    private val editionDao = database.editionDao()
    private val lock = Mutex()

    override suspend fun usage(): StorageUsage = withContext(dispatcher) {
        val imageBytes = images?.totalBytes() ?: 0
        StorageUsage(textUsed() + imageBytes, settings.settings.first().storageBudgetMb * BYTES_PER_MB, imageBytes)
    }

    /** Characters, which is bytes for most text and a slight undercount for the rest. */
    private suspend fun textUsed(): Long = articleDao.contentSize() + articleDao.indexedBodySize()

    private suspend fun used(): Long = textUsed() + (images?.totalBytes() ?: 0)

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
        // The server builds the editions: no post on the phone is waiting for the next one.
        val protectFrom = NO_PROTECTION
        // Images first; see the interface for why.
        val imageTrim = images?.trim(protectFrom, keepEditionIds, bytesToFree = used - target) ?: ImageTrimReport()
        var freed = imageTrim.bytesFreed
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
        return TrimReport(trimmed, freed, imageTrim.imagesTrimmed)
    }

    private companion object {
        /** A `Long` on purpose: budgets go up to 16,000 MB, and an `Int` product overflows from 2,048 MB. */
        const val BYTES_PER_MB = 1024L * 1024L
        const val BATCH = 200

        /** A `protectFrom` that protects nothing: every post was first seen before it. */
        val NO_PROTECTION: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
    }
}

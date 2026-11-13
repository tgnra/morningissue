package net.tgnra.morningissue.server.feeds

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.FullTextExtractor
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult

/**
 * Goes through the stored posts and fetches the page of each one whose feed body is only a
 * teaser ([FullTextExtractor.isTeaser]), as a refresh does for new posts. Only posts of feeds
 * someone subscribes to, from the last [RankingConfig.maxAgeDays] days: the ones an edition can
 * still print. Ported from the app; on the server it suits posts brought in by an archive import.
 */
class TeaserBackfill(
    database: ServerDatabase,
    private val fullText: FullTextFetcher,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val articleDao = database.articleDao()

    /** Returns how many pages were fetched and stored. */
    suspend fun run(): Int {
        val since = clock.instant().minus(Duration.ofDays(config.maxAgeDays.toLong()))
        val permits = Semaphore(PARALLEL)
        var stored = 0
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = articleDao.feedBodiesSince(since, afterId, BATCH)
            if (batch.isEmpty()) return stored
            afterId = batch.last().articleId
            val teasers = withContext(dispatcher) {
                batch.filter { FullTextExtractor.isTeaser(ContentBlocks.decode(it.blocksJson)) }.map { it.articleId }
            }
            stored += coroutineScope {
                teasers.map { id -> async { permits.withPermit { fullText.ensureFullText(id) } } }.awaitAll()
            }.count { it is FullTextResult.Stored }
        }
    }

    private companion object {
        const val BATCH = 200
        const val PARALLEL = 4
    }
}

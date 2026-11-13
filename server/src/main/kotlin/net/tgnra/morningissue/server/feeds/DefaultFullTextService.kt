package net.tgnra.morningissue.server.feeds

import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.tgnra.morningissue.api.FullTextState
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FullTextExtractor
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult

/**
 * Full text on the server, ported from the app's `DefaultFullTextFetcher`: [FullTextExtractor]
 * downloads the article's page and extracts the text, and this stores it as blocks in the
 * global `article_content` (source `FETCHED`), so one fetch serves every user.
 *
 * Feed-provided text counts as complete when it is long and does not end like an excerpt; then
 * no page is fetched. If the page offers no more than the feed did, the feed's own blocks are
 * kept but re-stored with source `FETCHED`, which records that the page has been checked.
 */
class DefaultFullTextService(
    database: ServerDatabase,
    fetcher: FeedFetcher,
    private val clock: Clock,
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FullTextService, FullTextFetcher {
    private val articleDao = database.articleDao()
    private val extractor = FullTextExtractor(fetcher, parseDispatcher)

    /** Concurrent callers for one article: the second waits and then finds the body stored. */
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    override suspend fun ensure(articleId: Long): FullTextState = when (ensureFullText(articleId)) {
        FullTextResult.Stored -> FullTextState.FETCHED
        FullTextResult.AlreadyStored ->
            if (articleDao.getContentSource(articleId) == ContentSource.FETCHED) FullTextState.FETCHED else FullTextState.FEED_COMPLETE
        is FullTextResult.Unavailable -> FullTextState.UNAVAILABLE
    }

    override suspend fun ensureForEdition(editionId: Long, concurrency: Int): Int {
        val ids = articleDao.editionArticleIds(editionId).distinct()
        val permits = Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope {
            ids.map { id -> async { permits.withPermit { ensureFullText(id) } } }.awaitAll()
        }.count { it == FullTextResult.Stored }
    }

    override suspend fun ensureFullText(articleId: Long): FullTextResult =
        locks[Math.floorMod(articleId, LOCK_STRIPES.toLong()).toInt()].withLock {
            try {
                fetchAndStore(articleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FullTextResult.Unavailable("The article could not be read")
            }
        }

    private suspend fun fetchAndStore(articleId: Long): FullTextResult {
        val article = articleDao.getById(articleId) ?: return FullTextResult.Unavailable("The article no longer exists")
        val stored = articleDao.getContent(articleId)
        if (stored?.source == ContentSource.FETCHED) return FullTextResult.AlreadyStored
        val feedBlocks = stored?.let { ContentBlocks.decode(it.blocksJson) }.orEmpty()
        val feedText = ContentBlocks.plainText(feedBlocks)
        val feedWords = ContentBlocks.wordCount(feedText)
        if (FullTextExtractor.looksComplete(feedBlocks)) return FullTextResult.AlreadyStored

        val url = article.url ?: return FullTextResult.Unavailable("The article has no web address")
        val extracted = when (val result = extractor.extract(url, article.title)) {
            is FullTextExtractor.Extraction.Page -> result
            is FullTextExtractor.Extraction.Unavailable -> return FullTextResult.Unavailable(result.message)
        }

        val now = clock.instant()
        if (stored != null && FullTextExtractor.feedWasComplete(feedWords, extracted.words)) {
            // The feed already had it all; keep its version and remember that we looked.
            articleDao.storeContent(articleId, stored.blocksJson, feedText, feedWords, ContentSource.FETCHED, now)
        } else {
            articleDao.storeContent(articleId, ContentBlocks.encode(extracted.blocks), extracted.text, extracted.words, ContentSource.FETCHED, now)
        }
        extracted.imageUrl?.let { articleDao.fillImageUrl(articleId, it) }
        return FullTextResult.Stored
    }

    private companion object {
        const val LOCK_STRIPES = 16
    }
}

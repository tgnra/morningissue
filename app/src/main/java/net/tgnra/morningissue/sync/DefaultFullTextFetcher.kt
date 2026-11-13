package net.tgnra.morningissue.sync

import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource

/**
 * The real [FullTextFetcher]: has [FullTextExtractor] download the article's page and extract
 * the text, and stores it as blocks.
 *
 * Feed-provided text counts as complete when it is long and does not end like an excerpt
 * ([FullTextExtractor.looksComplete]). Otherwise the page is fetched once. If the page offers no
 * more than the feed did, the feed's own blocks are kept but re-stored with source `FETCHED`,
 * which records that the page has been checked so later calls return without touching the
 * network.
 */
class DefaultFullTextFetcher(
    database: MorningIssueDatabase,
    fetcher: FeedFetcher,
    private val clock: Clock,
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FullTextFetcher {
    private val articleDao = database.articleDao()
    private val extractor = FullTextExtractor(fetcher, parseDispatcher)

    /** The reader and the prefetch may ask for the same article at once; one fetch serves both. */
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    override suspend fun ensureFullText(articleId: Long): FullTextResult =
        locks[(articleId % LOCK_STRIPES).toInt().let { if (it < 0) it + LOCK_STRIPES else it }].withLock {
            try {
                ensure(articleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FullTextResult.Unavailable("The article could not be read")
            }
        }

    private suspend fun ensure(articleId: Long): FullTextResult {
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
        if (FullTextExtractor.feedWasComplete(feedWords, extracted.words)) {
            // The feed already had it all; keep its version and remember that we looked.
            articleDao.storeContent(articleId, stored!!.blocksJson, feedText, feedWords, ContentSource.FETCHED, now)
        } else {
            articleDao.storeContent(articleId, ContentBlocks.encode(extracted.blocks), extracted.text, extracted.words, ContentSource.FETCHED, now)
        }
        if (article.imageUrl == null && extracted.imageUrl != null) {
            // Re-read: storeContent has just changed the word count on this row.
            articleDao.getById(articleId)?.let { articleDao.update(it.copy(imageUrl = extracted.imageUrl)) }
        }
        return FullTextResult.Stored
    }

    private companion object {
        const val LOCK_STRIPES = 16
    }
}

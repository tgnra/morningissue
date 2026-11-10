package net.tgnra.morningissue.sync

import java.io.ByteArrayInputStream
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import org.jsoup.Jsoup

/**
 * The real [FullTextFetcher]: downloads the article's page, extracts the text with
 * Readability4J and stores it as blocks.
 *
 * Feed-provided text counts as complete when it is long and does not end like an excerpt
 * ([looksComplete]). Otherwise the page is fetched once. If the page offers no more than the
 * feed did, the feed's own blocks are kept but re-stored with source `FETCHED`, which records
 * that the page has been checked so later calls return without touching the network.
 */
class DefaultFullTextFetcher(
    database: MorningIssueDatabase,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FullTextFetcher {
    private val articleDao = database.articleDao()

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
        if (looksComplete(feedBlocks)) return FullTextResult.AlreadyStored

        val url = article.url ?: return FullTextResult.Unavailable("The article has no web address")
        val page = when (val result = fetcher.fetch(url, accept = FeedFetcher.ACCEPT_PAGE, maxBytes = FeedFetcher.MAX_PAGE_BYTES)) {
            is FetchResult.Success -> result
            is FetchResult.NetworkError -> return FullTextResult.Unavailable(result.message)
            is FetchResult.HttpError -> return FullTextResult.Unavailable("The site answered with an error (HTTP ${result.status})")
            is FetchResult.NotModified -> return FullTextResult.Unavailable("The site sent no page")
        }
        if (page.contentType != null && page.contentType !in HTML_TYPES) {
            return FullTextResult.Unavailable("The link is not a web page")
        }

        val extracted = withContext(parseDispatcher) { extract(page, article) }
        val text = ContentBlocks.plainText(extracted.blocks)
        val words = ContentBlocks.wordCount(text)
        if (words < MIN_EXTRACTED_WORDS) return FullTextResult.Unavailable("No article text was found on the page")

        val now = clock.instant()
        if (feedWords > 0 && words <= feedWords * FEED_WAS_COMPLETE_RATIO) {
            // The feed already had it all; keep its version and remember that we looked.
            articleDao.storeContent(articleId, stored!!.blocksJson, feedText, feedWords, ContentSource.FETCHED, now)
        } else {
            articleDao.storeContent(articleId, ContentBlocks.encode(extracted.blocks), text, words, ContentSource.FETCHED, now)
        }
        if (article.imageUrl == null && extracted.imageUrl != null) {
            // Re-read: storeContent has just changed the word count on this row.
            articleDao.getById(articleId)?.let { articleDao.update(it.copy(imageUrl = extracted.imageUrl)) }
        }
        return FullTextResult.Stored
    }

    private class Extracted(val blocks: List<ContentBlock>, val imageUrl: String?)

    private fun extract(page: FetchResult.Success, article: ArticleEntity): Extracted {
        val document = Jsoup.parse(ByteArrayInputStream(page.body), page.charset?.name(), page.finalUrl)
        val leadImage = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let { Texts.resolve(page.finalUrl, it) }
        // Readability4J rebuilds relative addresses itself and loses the port; hand it absolute ones.
        for (element in document.select("a[href], img[src]")) {
            val attribute = if (element.normalName() == "a") "href" else "src"
            element.absUrl(attribute).takeIf { it.isNotEmpty() }?.let { element.attr(attribute, it) }
        }
        val content = Readability4J(page.finalUrl, document).parse().articleContent
            ?: return Extracted(emptyList(), leadImage)
        content.setBaseUri(page.finalUrl)
        var blocks = HtmlBlocks.convert(content)
        // Readability often keeps the page's own headline; the reader prints the title itself.
        val first = blocks.firstOrNull()
        if (first is ContentBlock.Heading && Texts.squash(first.text.text).equals(Texts.squash(article.title), ignoreCase = true)) {
            blocks = blocks.drop(1)
        }
        return Extracted(blocks, leadImage ?: blocks.filterIsInstance<ContentBlock.Image>().firstOrNull()?.url)
    }

    companion object {
        private const val LOCK_STRIPES = 16
        private const val MIN_EXTRACTED_WORDS = 40
        private const val COMPLETE_WORDS = 250

        /** A page with at most this much more text than the feed is the same article plus page furniture. */
        private const val FEED_WAS_COMPLETE_RATIO = 1.2
        private val HTML_TYPES = setOf("text/html", "application/xhtml+xml", "text/plain")
        private val EXCERPT_ENDING = Regex(
            """(…|\.\.\.|\[…]|\[\.\.\.]|\[&#8230;]|read more\W*|continue reading\W*|read the rest.*|appeared first on .*)$""",
            RegexOption.IGNORE_CASE,
        )

        /** True when feed-provided [blocks] read like a whole article rather than a teaser. */
        internal fun looksComplete(blocks: List<ContentBlock>): Boolean {
            val text = ContentBlocks.plainText(blocks)
            if (ContentBlocks.wordCount(text) < COMPLETE_WORDS) return false
            return !EXCERPT_ENDING.containsMatchIn(text.takeLast(200).trim())
        }
    }
}

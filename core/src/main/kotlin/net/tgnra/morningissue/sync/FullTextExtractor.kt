package net.tgnra.morningissue.sync

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import org.jsoup.Jsoup

/**
 * Gets an article's text from its web page: downloads the page, extracts the text with
 * Readability4J and converts it to blocks. It stores nothing; the app's
 * `DefaultFullTextFetcher` (and the server's full-text service) decide what to keep.
 *
 * Feed-provided text counts as complete when it is long and does not end like an excerpt
 * ([looksComplete]); such a post needs no page. A page with little more text than the feed
 * ([feedWasComplete]) is the same article plus page furniture.
 */
class FullTextExtractor(
    private val fetcher: FeedFetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What [extract] got from a page. */
    sealed interface Extraction {
        /** The article's [blocks], their plain [text] and its [words], and a lead image if the page names one. */
        data class Page(val blocks: List<ContentBlock>, val text: String, val words: Int, val imageUrl: String?) : Extraction

        /** No article text could be had; [message] says why, for the reader. */
        data class Unavailable(val message: String) : Extraction
    }

    /**
     * Fetches [url] and extracts the article. [title] is the post's own title: a first heading
     * that repeats it is dropped, because the reader prints the title itself. Throws only what
     * the fetch or the parser throws; callers catch.
     */
    suspend fun extract(url: String, title: String): Extraction {
        val page = when (val result = fetcher.fetch(url, accept = FeedFetcher.ACCEPT_PAGE, maxBytes = FeedFetcher.MAX_PAGE_BYTES)) {
            is FetchResult.Success -> result
            is FetchResult.NetworkError -> return Extraction.Unavailable(result.message)
            is FetchResult.HttpError -> return Extraction.Unavailable("The site answered with an error (HTTP ${result.status})")
            is FetchResult.NotModified -> return Extraction.Unavailable("The site sent no page")
        }
        if (page.contentType != null && page.contentType !in HTML_TYPES) {
            return Extraction.Unavailable("The link is not a web page")
        }

        val (blocks, imageUrl) = withContext(parseDispatcher) { parse(page, title) }
        val text = ContentBlocks.plainText(blocks)
        val words = ContentBlocks.wordCount(text)
        if (words < MIN_EXTRACTED_WORDS) return Extraction.Unavailable("No article text was found on the page")
        return Extraction.Page(blocks, text, words, imageUrl)
    }

    private fun parse(page: FetchResult.Success, title: String): Pair<List<ContentBlock>, String?> {
        val document = Jsoup.parse(ByteArrayInputStream(page.body), page.charset?.name(), page.finalUrl)
        val leadImage = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let { Texts.resolve(page.finalUrl, it) }
        // Readability4J rebuilds relative addresses itself and loses the port; hand it absolute ones.
        for (element in document.select("a[href], img[src]")) {
            val attribute = if (element.normalName() == "a") "href" else "src"
            element.absUrl(attribute).takeIf { it.isNotEmpty() }?.let { element.attr(attribute, it) }
        }
        val content = Readability4J(page.finalUrl, document).parse().articleContent
            ?: return emptyList<ContentBlock>() to leadImage
        content.setBaseUri(page.finalUrl)
        var blocks = HtmlBlocks.convert(content)
        // Readability often keeps the page's own headline; the reader prints the title itself.
        val first = blocks.firstOrNull()
        if (first is ContentBlock.Heading && Texts.squash(first.text.text).equals(Texts.squash(title), ignoreCase = true)) {
            blocks = blocks.drop(1)
        }
        return blocks to (leadImage ?: blocks.filterIsInstance<ContentBlock.Image>().firstOrNull()?.url)
    }

    companion object {
        private const val MIN_EXTRACTED_WORDS = 40
        private const val COMPLETE_WORDS = 250

        /** A page with at most this much more text than the feed is the same article plus page furniture. */
        private const val FEED_WAS_COMPLETE_RATIO = 1.2
        private val HTML_TYPES = setOf("text/html", "application/xhtml+xml", "text/plain")
        private val EXCERPT_ENDING = Regex(
            """(…|\.\.\.|\[…]|\[\.\.\.]|\[&#8230;]|read more\W*|continue reading\W*|read the rest.*|read (it )?in full\b.*|appeared first on .*)$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * A feed body that ends by sending the reader to the site ("Read in full on …", "Continue
         * reading", "Read more"). Such a post is fetched when the feed is refreshed, not only
         * when it is opened, so its text is there to read anywhere (the Kobo has no browser).
         */
        private val SENDS_TO_PAGE = Regex(
            """(read (it |this |the (full|whole|rest of the) (story|article|post|piece) )?(in full|more|on)\b.*|continue reading\b.*|keep reading\b.*|read the rest\b.*|full (story|article|post)\b.*|…|\.\.\.|\[…]|\[\.\.\.])$""",
            RegexOption.IGNORE_CASE,
        )

        /** True when feed-provided [blocks] are a short teaser that points the reader to the web page. */
        fun isTeaser(blocks: List<ContentBlock>): Boolean {
            if (looksComplete(blocks)) return false
            val text = ContentBlocks.plainText(blocks)
            return SENDS_TO_PAGE.containsMatchIn(text.takeLast(TEASER_TAIL).trim())
        }

        private const val TEASER_TAIL = 120

        /** True when feed-provided [blocks] read like a whole article rather than a teaser. */
        fun looksComplete(blocks: List<ContentBlock>): Boolean {
            val text = ContentBlocks.plainText(blocks)
            if (ContentBlocks.wordCount(text) < COMPLETE_WORDS) return false
            return !EXCERPT_ENDING.containsMatchIn(text.takeLast(200).trim())
        }

        /** True when a page of [pageWords] offers no more than a feed body of [feedWords] did. */
        fun feedWasComplete(feedWords: Int, pageWords: Int): Boolean =
            feedWords > 0 && pageWords <= feedWords * FEED_WAS_COMPLETE_RATIO
    }
}

package net.tgnra.morningissue.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The page half of full text, with no database: the app's `FullTextFetcherTest` covers storing it. */
class FullTextExtractorTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val extractor = FullTextExtractor(FeedFetcher(OkHttpClient()))

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        routes["/article"] = { response(Fixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun paragraphs(words: Int, ending: String = "the end.") =
        listOf(ContentBlock.Paragraph(RichText((1..words).joinToString(" ") { "word$it" } + " " + ending)))

    @Test
    fun extractsThePageAsBlocks() = runBlocking<Unit> {
        val page = extractor.extract(url("/article"), "The Long Way Round") as FullTextExtractor.Extraction.Page
        val blocks = page.blocks
        assertTrue(page.text, "The ferry leaves the harbour at six in the morning" in page.text)
        assertFalse(page.text, "Popular" in page.text)
        assertFalse(page.text, "Copyright" in page.text)
        // The page's own headline is not repeated above the text.
        assertFalse(blocks.any { it is ContentBlock.Heading && it.text.text == "The Long Way Round" })
        assertTrue(blocks.any { it is ContentBlock.Heading && it.text.text == "The crossing" })
        assertEquals(url("/images/ferry.jpg"), blocks.filterIsInstance<ContentBlock.Image>().single().url)
        val spans = blocks.filterIsInstance<ContentBlock.Paragraph>().flatMap { it.text.spans }
        assertEquals(url("/timetable"), spans.single { it.style == InlineStyle.LINK }.href)
        assertTrue(page.words > 200)
        assertEquals(url("/images/lead-photo.jpg"), page.imageUrl)
    }

    @Test
    fun aPageWithoutArticleTextOrNotHtmlIsUnavailable() = runBlocking<Unit> {
        routes["/empty"] = { response("<html><body><div id=\"app\"></div><script>render()</script></body></html>", 200, "Content-Type" to "text/html") }
        routes["/paper.pdf"] = { response("%PDF-1.7", 200, "Content-Type" to "application/pdf") }
        for (path in listOf("/empty", "/paper.pdf", "/gone")) {
            val result = extractor.extract(url(path), "Title")
            assertTrue("$path: $result", result is FullTextExtractor.Extraction.Unavailable)
            assertTrue((result as FullTextExtractor.Extraction.Unavailable).message.isNotBlank())
        }
    }

    @Test
    fun longFeedTextIsCompleteUnlessItEndsLikeAnExcerpt() {
        assertTrue(FullTextExtractor.looksComplete(paragraphs(300)))
        assertFalse(FullTextExtractor.looksComplete(paragraphs(300, "Continue reading →")))
        assertFalse(FullTextExtractor.looksComplete(paragraphs(300, "The post Ferries appeared first on Example Journal.")))
        assertFalse(FullTextExtractor.looksComplete(paragraphs(300, "and so on…")))
        assertFalse(FullTextExtractor.looksComplete(paragraphs(100)))
    }

    @Test
    fun teasersAreShortBodiesThatSendTheReaderToThePage() {
        assertTrue(FullTextExtractor.isTeaser(paragraphs(40, "Read in full on Analog.Cafe.")))
        assertTrue(FullTextExtractor.isTeaser(paragraphs(40, "Continue reading →")))
        assertTrue(FullTextExtractor.isTeaser(paragraphs(40, "Read the full story at Example")))
        assertTrue(FullTextExtractor.isTeaser(paragraphs(40, "and so on…")))
        assertFalse(FullTextExtractor.isTeaser(paragraphs(40)))
        // Long text is the article, unless it too ends by sending the reader away.
        assertFalse(FullTextExtractor.isTeaser(paragraphs(300)))
        assertFalse(FullTextExtractor.looksComplete(paragraphs(300, "Read in full on Analog.Cafe.")))
    }

    @Test
    fun aPageWithLittleMoreThanTheFeedMeansTheFeedWasComplete() {
        assertTrue(FullTextExtractor.feedWasComplete(feedWords = 100, pageWords = 120))
        assertFalse(FullTextExtractor.feedWasComplete(feedWords = 100, pageWords = 121))
        assertFalse(FullTextExtractor.feedWasComplete(feedWords = 0, pageWords = 50))
    }
}

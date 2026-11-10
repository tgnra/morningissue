package net.tgnra.morningissue.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullTextFetcherTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private lateinit var db: MorningIssueDatabase
    private lateinit var fullText: DefaultFullTextFetcher
    private var feedId = 0L

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        fullText = DefaultFullTextFetcher(db, FeedFetcher(OkHttpClient()), clock)
        feedId = runBlocking { db.feedDao().insert(FeedEntity(url = "https://example.com/feed", title = "Example", addedAt = NOW)) }
        routes["/article"] = { response(Fixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun paragraphs(words: Int, ending: String = "the end.") =
        listOf(ContentBlock.Paragraph(RichText((1..words).joinToString(" ") { "word$it" } + " " + ending)))

    /** Stores an article, with feed-provided [feedBlocks] if given. */
    private suspend fun article(
        guid: String,
        path: String? = "/article",
        feedBlocks: List<ContentBlock>? = null,
        title: String = "The Long Way Round",
    ): Long {
        val entity = ArticleEntity(
            feedId = feedId, guid = guid, url = path?.let(::url), title = title,
            summary = "A ferry trip.", publishedAt = NOW, firstSeenAt = NOW,
        )
        return db.articleDao().insertNew(entity, feedBlocks?.let(ContentBlocks::encode), feedBlocks?.let(ContentBlocks::plainText))!!
    }

    @Test
    fun fetchesExtractsAndStoresThePage() = runBlocking<Unit> {
        val id = article("a")
        clock.now = NOW.plusSeconds(60)

        assertEquals(FullTextResult.Stored, fullText.ensureFullText(id))

        val stored = db.articleDao().getContent(id)!!
        assertEquals(ContentSource.FETCHED, stored.source)
        assertEquals(NOW.plusSeconds(60), stored.storedAt)
        val blocks = ContentBlocks.decode(stored.blocksJson)
        val text = ContentBlocks.plainText(blocks)
        assertTrue(text, "The ferry leaves the harbour at six in the morning" in text)
        assertTrue(text, "none of them leave you feeling" in text)
        // Page furniture is gone.
        assertFalse(text, "Popular" in text)
        assertFalse(text, "Copyright" in text)
        assertFalse(text, "tracker" in text)
        // The page's own headline is not repeated above the text.
        assertFalse(blocks.any { it is ContentBlock.Heading && it.text.text == "The Long Way Round" })

        // Structure survives: heading, image with caption, quote, list, inline styles, absolute links.
        assertTrue(blocks.any { it is ContentBlock.Heading && it.text.text == "The crossing" })
        val image = blocks.filterIsInstance<ContentBlock.Image>().single()
        assertEquals(url("/images/ferry.jpg"), image.url)
        assertEquals("The morning ferry, seen from the breakwater.", image.caption?.text)
        assertEquals(1, blocks.filterIsInstance<ContentBlock.Quote>().size)
        assertEquals(3, blocks.filterIsInstance<ContentBlock.ItemList>().single().items.size)
        val spans = blocks.filterIsInstance<ContentBlock.Paragraph>().flatMap { it.text.spans }
        assertTrue(spans.any { it.style == InlineStyle.BOLD })
        assertTrue(spans.any { it.style == InlineStyle.ITALIC })
        assertEquals(url("/timetable"), spans.single { it.style == InlineStyle.LINK }.href)

        // Word count, lead image and the search index follow the new text.
        val article = db.articleDao().getById(id)!!
        assertEquals(ContentBlocks.wordCount(text), article.wordCount)
        assertTrue(article.wordCount > 200)
        assertEquals(url("/images/lead-photo.jpg"), article.imageUrl)
        val hits = db.articleDao().search("lighthouse", null, null, null, null, likedOnly = false, savedOnly = false, limit = 5)
        assertEquals(listOf(id), hits.map { it.row.article.id })
    }

    @Test
    fun isIdempotent() = runBlocking<Unit> {
        val id = article("a")
        assertEquals(FullTextResult.Stored, fullText.ensureFullText(id))
        val first = db.articleDao().getContent(id)

        assertEquals(FullTextResult.AlreadyStored, fullText.ensureFullText(id))
        assertEquals(FullTextResult.AlreadyStored, fullText.ensureFullText(id))
        assertEquals(1, routes.hits("/article"))
        assertEquals(first, db.articleDao().getContent(id))
    }

    @Test
    fun excerptFromTheFeedIsReplacedByThePage() = runBlocking<Unit> {
        val excerpt = listOf(ContentBlock.Paragraph(RichText("The ferry leaves the harbour at six in the morning […]")))
        val id = article("a", feedBlocks = excerpt)

        assertEquals(FullTextResult.Stored, fullText.ensureFullText(id))
        val stored = db.articleDao().getContent(id)!!
        assertEquals(ContentSource.FETCHED, stored.source)
        assertTrue(ContentBlocks.decode(stored.blocksJson).size > 5)
    }

    @Test
    fun completeFeedTextIsNotFetched() = runBlocking<Unit> {
        val id = article("a", feedBlocks = paragraphs(300))
        assertEquals(FullTextResult.AlreadyStored, fullText.ensureFullText(id))
        assertEquals(0, routes.hits("/article"))
        assertEquals(ContentSource.FEED, db.articleDao().getContentSource(id))
    }

    @Test
    fun longFeedTextThatEndsLikeAnExcerptIsFetched() = runBlocking<Unit> {
        assertTrue(DefaultFullTextFetcher.looksComplete(paragraphs(300)))
        assertFalse(DefaultFullTextFetcher.looksComplete(paragraphs(300, "Continue reading →")))
        assertFalse(DefaultFullTextFetcher.looksComplete(paragraphs(300, "The post Ferries appeared first on Example Journal.")))
        assertFalse(DefaultFullTextFetcher.looksComplete(paragraphs(300, "and so on…")))
        assertFalse(DefaultFullTextFetcher.looksComplete(paragraphs(100)))

        val id = article("a", feedBlocks = paragraphs(300, "Read more"))
        assertEquals(FullTextResult.Stored, fullText.ensureFullText(id))
        assertEquals(1, routes.hits("/article"))
    }

    @Test
    fun shortButCompleteFeedTextIsKeptAndThePageIsCheckedOnlyOnce() = runBlocking<Unit> {
        // Too short to be taken for complete on sight, but the page turns out to have no more.
        val shortFeed = paragraphs(120)
        val shortId = article("b", path = "/short", feedBlocks = shortFeed, title = "Short post")
        routes["/short"] = {
            response("<html><body><article><h1>Short post</h1><p>${ContentBlocks.plainText(shortFeed)}</p></article></body></html>", 200, "Content-Type" to "text/html")
        }

        assertEquals(FullTextResult.Stored, fullText.ensureFullText(shortId))
        val stored = db.articleDao().getContent(shortId)!!
        // The feed's blocks are kept, marked as checked against the page.
        assertEquals(ContentSource.FETCHED, stored.source)
        assertEquals(shortFeed, ContentBlocks.decode(stored.blocksJson))

        assertEquals(FullTextResult.AlreadyStored, fullText.ensureFullText(shortId))
        assertEquals(1, routes.hits("/short"))
    }

    @Test
    fun failureLeavesStoredContentAlone() = runBlocking<Unit> {
        val excerpt = listOf(ContentBlock.Paragraph(RichText("Just a teaser…")))
        val gone = article("gone", path = "/gone", feedBlocks = excerpt)
        val empty = article("empty", path = "/empty", feedBlocks = excerpt)
        val pdf = article("pdf", path = "/paper.pdf", feedBlocks = excerpt)
        val noUrl = article("no-url", path = null, feedBlocks = excerpt)
        routes["/empty"] = { response("<html><body><div id=\"app\"></div><script>render()</script></body></html>", 200, "Content-Type" to "text/html") }
        routes["/paper.pdf"] = { response("%PDF-1.7", 200, "Content-Type" to "application/pdf") }

        for (id in listOf(gone, empty, pdf, noUrl)) {
            val result = fullText.ensureFullText(id)
            assertTrue("$result", result is FullTextResult.Unavailable)
            assertTrue((result as FullTextResult.Unavailable).message.isNotBlank())
            val stored = db.articleDao().getContent(id)!!
            assertEquals(ContentSource.FEED, stored.source)
            assertEquals(excerpt, ContentBlocks.decode(stored.blocksJson))
        }
        assertTrue(fullText.ensureFullText(123456) is FullTextResult.Unavailable)

        // An article with no stored text at all stays that way, and still has its summary.
        val bare = article("bare", path = "/gone")
        assertTrue(fullText.ensureFullText(bare) is FullTextResult.Unavailable)
        assertNull(db.articleDao().getContent(bare))
        assertEquals("A ferry trip.", db.articleDao().getById(bare)!!.summary)

        // A failure is retried next time.
        routes["/gone"] = { response(Fixtures.text("article.html"), 200, "Content-Type" to "text/html") }
        assertEquals(FullTextResult.Stored, fullText.ensureFullText(gone))
    }
}

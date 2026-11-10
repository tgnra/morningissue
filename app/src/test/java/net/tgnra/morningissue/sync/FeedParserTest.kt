package net.tgnra.morningissue.sync

import java.time.Instant
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.InlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

open class FeedParserTest {
    private fun parse(name: String, url: String, at: Instant = NOW) = FeedParser.parse(Fixtures.text(name), url, at)

    // --- RSS ------------------------------------------------------------------------------------

    private val rss by lazy { parse("rss.xml", "https://feeds.example.net/example") }

    @Test
    fun rssChannel() {
        assertEquals(FeedFormat.RSS, rss.format)
        assertEquals("The Example & Co. Journal", rss.title)
        assertEquals("https://example.com/", rss.siteUrl)
        assertEquals("https://example.com/images/logo.png", rss.iconUrl)
        // The sixth item repeats the first one's GUID.
        assertEquals(5, rss.items.size)
    }

    @Test
    fun rssFullItem() {
        val item = rss.items[0]
        assertEquals("post-1", item.guid)
        assertEquals("Full post with everything", item.title)
        assertEquals("https://example.com/posts/full?a=1&b=2", item.url)
        assertEquals("Ada Lovelace", item.author)
        assertEquals(Instant.parse("2026-11-09T07:30:00Z"), item.publishedAt)
        assertTrue(item.hasDate)

        val content = item.content
        assertEquals("A short standfirst for the post.", content.summary)
        // media:content beats its nested thumbnail, the enclosure and the inline image.
        assertEquals("https://cdn.example.com/lead.jpg", content.imageUrl)

        assertEquals(4, content.blocks.size)
        val first = content.blocks[0] as ContentBlock.Paragraph
        assertEquals("First paragraph with a relative link & an entity.", first.text.text)
        val link = first.text.spans.single()
        assertEquals(InlineStyle.LINK, link.style)
        assertEquals("https://example.com/about", link.href)
        assertEquals("relative link", first.text.text.substring(link.start, link.end))
        assertEquals(ContentBlock.Heading(2, net.tgnra.morningissue.model.RichText("A heading")), content.blocks[1])
        assertEquals(
            ContentBlock.Image("https://example.com/images/inline.jpg", alt = "Inline", width = 800, height = 600),
            content.blocks[2],
        )
        assertEquals("Second paragraph.", (content.blocks[3] as ContentBlock.Paragraph).text.text)
        assertEquals(13, content.wordCount)
        assertEquals(32, content.contentHash.length)
    }

    @Test
    fun rssMissingGuidFallsBackToLink() {
        val item = rss.items[1]
        assertEquals("https://example.com/posts/no-guid", item.url)
        assertEquals(item.url, item.guid)
        assertEquals("No guid, relative link, markup in title", item.title)
        assertEquals("Grace Hopper", item.author)
        // Two-digit year, one-digit day and hour, no seconds, named zone.
        assertEquals(Instant.parse("2026-11-08T12:05:00Z"), item.publishedAt)
        assertEquals("Plain description with a café and an image inside.", item.content.summary)
        // The enclosure is audio, so the lead image is the first one in the text.
        assertEquals("https://example.com/images/desc.png", item.content.imageUrl)
    }

    @Test
    fun rssMissingGuidAndLinkFallsBackToStableHash() {
        val item = rss.items[2]
        assertNull(item.url)
        assertTrue(item.guid.startsWith("hash:"))
        // Unreadable date: the fetch time stands in.
        assertFalse(item.hasDate)
        assertEquals(NOW, item.publishedAt)

        val later = parse("rss.xml", "https://feeds.example.net/example", NOW.plusSeconds(7200)).items[2]
        assertEquals(item.guid, later.guid)
        assertEquals(NOW.plusSeconds(7200), later.publishedAt)
    }

    @Test
    fun rssPermalinkGuidAndGroupThumbnail() {
        val item = rss.items[3]
        assertEquals("https://example.com/posts/permalink", item.guid)
        assertEquals("https://example.com/posts/permalink", item.url)
        assertEquals(Instant.parse("2026-11-07T12:00:00Z"), item.publishedAt)
        assertEquals("https://cdn.example.com/group-thumb.jpg", item.content.imageUrl)
        assertNull(item.content.summary)
        assertTrue(item.content.blocks.isEmpty())
    }

    @Test
    fun rssTitlelessItemTakesItsOpeningWords() {
        val item = rss.items[4]
        assertEquals("micro-1", item.guid)
        assertEquals("A titleless microblog post that says just this.", item.title)
        assertEquals("https://cdn.example.com/photo.jpg", item.content.imageUrl)
    }

    @Test
    fun rss1ItemsBesideTheChannel() {
        val feed = FeedParser.parse(
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rdf.example/"><title>RDF feed</title><link>https://rdf.example/</link></channel>
              <item rdf:about="https://rdf.example/a"><title>A</title><link>https://rdf.example/a</link>
                <dc:date>2026-11-01T10:00+01:00</dc:date></item>
            </rdf:RDF>
            """.trimIndent(),
            "https://rdf.example/index.rdf",
            NOW,
        )
        assertEquals("RDF feed", feed.title)
        assertEquals("https://rdf.example/a", feed.items.single().guid)
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), feed.items.single().publishedAt)
    }

    @Test
    fun truncatedFeedKeepsThePostsBeforeTheDamage() {
        val whole = rssOf("a", "b", "c")
        val cut = whole.substring(0, whole.indexOf("<item>", whole.indexOf("Post b")) + 20)
        assertEquals(listOf("a", "b"), FeedParser.parse(cut, "https://site.example/feed", NOW).items.map { it.guid })
    }

    // --- Atom -----------------------------------------------------------------------------------

    private val atom by lazy { parse("atom.xml", "https://blog.example.org/atom.xml") }

    @Test
    fun atomFeed() {
        assertEquals(FeedFormat.ATOM, atom.format)
        assertEquals("Field & Stream Notes", atom.title)
        assertEquals("https://blog.example.org/", atom.siteUrl)
        assertEquals("https://blog.example.org/favicon.ico", atom.iconUrl)
        assertEquals(4, atom.items.size)
    }

    @Test
    fun atomXhtmlEntry() {
        val item = atom.items[0]
        assertEquals("tag:blog.example.org,2026:1", item.guid)
        assertEquals("XHTML entry", item.title)
        // Relative to xml:base; the "replies" link is not the post's address.
        assertEquals("https://blog.example.org/base/posts/xhtml", item.url)
        assertEquals("Entry Author", item.author)
        assertEquals(Instant.parse("2026-11-09T08:00:00Z"), item.publishedAt)
        assertEquals("A plain summary.", item.content.summary)

        val paragraph = item.content.blocks[0] as ContentBlock.Paragraph
        assertEquals("Written in XHTML with a link.", paragraph.text.text)
        assertEquals(listOf(InlineStyle.ITALIC, InlineStyle.LINK), paragraph.text.spans.map { it.style })
        assertEquals("https://blog.example.org/base/posts/other", paragraph.text.spans[1].href)
        val quote = item.content.blocks[1] as ContentBlock.Quote
        assertEquals(listOf("Quoted & escaped."), quote.paragraphs.map { it.text })
    }

    @Test
    fun atomEscapedHtmlEntryWithOnlyUpdated() {
        val item = atom.items[1]
        assertEquals("Feed Author", item.author)
        assertEquals(Instant.parse("2026-11-08T09:15:00Z"), item.publishedAt)
        assertEquals("Escaped HTML content.", item.content.summary)
        assertEquals("https://blog.example.org/img/a.png", item.content.imageUrl)
        val bold = (item.content.blocks[0] as ContentBlock.Paragraph).text.spans.single()
        assertEquals(InlineStyle.BOLD, bold.style)
    }

    @Test
    fun atomMediaGroup() {
        val item = atom.items[2]
        assertEquals("yt:video:abc", item.guid)
        assertEquals("A video", item.title)
        // The Flash media:content is not an image; the thumbnail is.
        assertEquals("https://img.example.org/abc/hq.jpg", item.content.imageUrl)
        assertEquals(
            listOf("First line of the description.", "Second paragraph."),
            item.content.blocks.map { (it as ContentBlock.Paragraph).text.text },
        )
    }

    @Test
    fun atomMissingIdAndPlaceholderDate() {
        val item = atom.items[3]
        assertEquals("https://blog.example.org/posts/no-id", item.guid)
        assertFalse(item.hasDate)
        assertEquals(NOW, item.publishedAt)
        assertEquals("Only a summary.", item.content.summary)
    }

    // --- JSON Feed ------------------------------------------------------------------------------

    private val json by lazy { parse("feed.json", "https://json.example.net/feed.json") }

    @Test
    fun jsonFeed() {
        assertEquals(FeedFormat.JSON, json.format)
        assertEquals("JSON Example", json.title)
        assertEquals("https://json.example.net/", json.siteUrl)
        assertEquals("https://json.example.net/icon.png", json.iconUrl)
        assertEquals(3, json.items.size)

        val first = json.items[0]
        assertEquals("https://json.example.net/1", first.guid)
        assertEquals("Json Author", first.author)
        assertEquals(Instant.parse("2026-11-09T17:00:00Z"), first.publishedAt)
        assertEquals("A greeting.", first.content.summary)
        assertEquals("https://json.example.net/1.jpg", first.content.imageUrl)
        assertEquals(ContentBlock.ItemList(false, listOf("One", "Two").map { net.tgnra.morningissue.model.RichText(it) }), first.content.blocks[1])
    }

    @Test
    fun jsonNumericIdTextContentAndBadDate() {
        val item = json.items[1]
        assertEquals("42", item.guid)
        assertEquals("https://json.example.net/2", item.url)
        assertEquals("Titleless text post. Second paragraph.", item.title)
        assertFalse(item.hasDate)
        assertEquals("https://json.example.net/2.png", item.content.imageUrl)
        assertEquals(2, item.content.blocks.size)
    }

    @Test
    fun jsonMissingIdFallsBackToLink() {
        val item = json.items[2]
        assertEquals("https://elsewhere.example/linked", item.guid)
        assertEquals("Guest", item.author)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), item.publishedAt)
    }

    // --- Detection and errors ---------------------------------------------------------------------

    @Test
    fun formatIsDetectedFromTheDocument() {
        assertEquals(FeedFormat.RSS, FeedParser.sniff(Fixtures.text("rss.xml")))
        assertEquals(FeedFormat.ATOM, FeedParser.sniff(Fixtures.text("atom.xml")))
        assertEquals(FeedFormat.JSON, FeedParser.sniff(Fixtures.text("feed.json")))
        assertEquals(FeedFormat.RSS, FeedParser.sniff("<!-- hello -->\n<!DOCTYPE rss>\n<RSS version='0.91'>"))
        assertNull(FeedParser.sniff("<!DOCTYPE html><html><head><title>A page</title></head></html>"))
        assertNull(FeedParser.sniff("{\"error\": \"not found\"}"))
        assertNull(FeedParser.sniff("just some text"))
    }

    @Test
    fun nonFeedsAndBrokenDocumentsThrow() {
        assertThrows(FeedParseException::class.java) { FeedParser.parse("<html><body>Hi</body></html>", "https://x.example/", NOW) }
        assertThrows(FeedParseException::class.java) { FeedParser.parse("<rss><channel><title>Cut off", "https://x.example/", NOW) }
        assertThrows(FeedParseException::class.java) { FeedParser.parse("{\"version\": \"https://jsonfeed.org/version/1\", \"items\": 3}", "https://x.example/", NOW) }
    }

    @Test
    fun longDescriptionIsCutToAnExcerpt() {
        val words = (1..300).joinToString(" ") { "word$it" }
        val feed = FeedParser.parse(
            "<rss><channel><title>T</title><item><title>Long</title><link>https://x.example/1</link><description>$words</description></item></channel></rss>",
            "https://x.example/feed",
            NOW,
        )
        val content = feed.items.single().content
        assertTrue(content.summary!!.length <= 401)
        assertTrue(content.summary!!.endsWith("…"))
        assertEquals(300, content.wordCount)
    }
}

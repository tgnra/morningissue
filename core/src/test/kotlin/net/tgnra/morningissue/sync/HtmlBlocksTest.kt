package net.tgnra.morningissue.sync

import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBlocksTest {
    private fun convert(html: String) = HtmlBlocks.convert(html, "https://example.com/posts/one")

    private fun RichText.styled(style: InlineStyle): List<String> =
        spans.filter { it.style == style }.map { text.substring(it.start, it.end) }

    @Test
    fun paragraphsAndHeadings() {
        val blocks = convert("<h1>Title</h1><p>One.</p>\n\n<p>  Two\n   lines.  </p><h3>Sub <em>head</em></h3><p>&nbsp;</p><p></p>")
        assertEquals(
            listOf(
                ContentBlock.Heading(1, RichText("Title")),
                ContentBlock.Paragraph(RichText("One.")),
                ContentBlock.Paragraph(RichText("Two lines.")),
                ContentBlock.Heading(3, RichText("Sub head", listOf(InlineSpan(4, 8, InlineStyle.ITALIC)))),
            ),
            blocks,
        )
    }

    @Test
    fun inlineBoldItalicAndLinks() {
        val paragraph = convert(
            """<p>Plain <b>bold</b> and <strong>strong</strong>, <i>italic</i> <em>em</em>,
               <a href="/rel">a <b>bold</b> link</a>, <a href="https://other.example/x">abs</a>,
               <code>code()</code> <del>gone</del> x<sup>2</sup> H<sub>2</sub>O
               <a href="javascript:alert(1)">script</a> <a>anchor</a>.</p>""",
        ).single() as ContentBlock.Paragraph
        val text = paragraph.text
        assertEquals("Plain bold and strong, italic em, a bold link, abs, code() gone x2 H2O script anchor.", text.text)
        assertEquals(listOf("bold", "strong", "bold"), text.styled(InlineStyle.BOLD))
        assertEquals(listOf("italic", "em"), text.styled(InlineStyle.ITALIC))
        assertEquals(listOf("a bold link", "abs"), text.styled(InlineStyle.LINK))
        assertEquals(
            listOf("https://example.com/rel", "https://other.example/x"),
            text.spans.filter { it.style == InlineStyle.LINK }.map { it.href },
        )
        assertEquals(listOf("code()"), text.styled(InlineStyle.CODE))
        assertEquals(listOf("gone"), text.styled(InlineStyle.STRIKETHROUGH))
        assertEquals(listOf("2"), text.styled(InlineStyle.SUPERSCRIPT))
        assertEquals(listOf("2"), text.styled(InlineStyle.SUBSCRIPT))
    }

    @Test
    fun looseTextAndLineBreaks() {
        val blocks = convert("Loose text<br>second line<br><br>New paragraph<div>In a div</div>tail")
        assertEquals(
            listOf("Loose text\nsecond line", "New paragraph", "In a div", "tail"),
            blocks.map { (it as ContentBlock.Paragraph).text.text },
        )
    }

    @Test
    fun styleSpanningABlockBreakIsSplit() {
        val blocks = convert("<b>one<p>two</p>three</b>")
        assertEquals(3, blocks.size)
        for (block in blocks) {
            val text = (block as ContentBlock.Paragraph).text
            assertEquals(listOf(InlineSpan(0, text.text.length, InlineStyle.BOLD)), text.spans)
        }
    }

    @Test
    fun images() {
        val blocks = convert(
            """<p>Before <img src="/a.jpg" alt="A" width="640" height="480"> after</p>
               <img src="data:image/gif;base64,R0lGOD" data-src="https://cdn.example.com/lazy.png">
               <img srcset="small.jpg 480w, big.jpg 1200w">
               <img src="https://stats.example.com/pixel.gif" width="1" height="1">
               <p>Smile <img class="wp-smiley" alt=":)" src="/smile.png"></p>
               <figure><img src="fig.png"><figcaption>A <i>caption</i></figcaption></figure>
               <a href="/full"><img src="/linked.jpg"></a>""",
        )
        assertEquals(
            listOf(
                ContentBlock.Paragraph(RichText("Before")),
                ContentBlock.Image("https://example.com/a.jpg", alt = "A", width = 640, height = 480),
                ContentBlock.Paragraph(RichText("after")),
                ContentBlock.Image("https://cdn.example.com/lazy.png"),
                ContentBlock.Image("https://example.com/posts/big.jpg"),
                ContentBlock.Paragraph(RichText("Smile :)")),
                ContentBlock.Image(
                    "https://example.com/posts/fig.png",
                    caption = RichText("A caption", listOf(InlineSpan(2, 9, InlineStyle.ITALIC))),
                ),
                ContentBlock.Image("https://example.com/linked.jpg"),
            ),
            blocks,
        )
    }

    @Test
    fun blockquotes() {
        val blocks = convert(
            "<blockquote><p>First <b>quoted</b>.</p><p>Second.</p><footer>— <cite>Someone</cite></footer></blockquote>" +
                "<blockquote>Bare quote</blockquote>",
        )
        val quote = blocks[0] as ContentBlock.Quote
        assertEquals(listOf("First quoted.", "Second."), quote.paragraphs.map { it.text })
        assertEquals(listOf("quoted"), quote.paragraphs[0].styled(InlineStyle.BOLD))
        assertEquals("— Someone", quote.citation!!.text)
        assertEquals(ContentBlock.Quote(listOf(RichText("Bare quote"))), blocks[1])
    }

    @Test
    fun listsAreFlattened() {
        val blocks = convert(
            "<ul><li>One</li><li>Two <a href='https://x.example/'>link</a><ul><li>Two A</li><li>Two B</li></ul></li><li><p>Three</p></li><li> </li></ul>" +
                "<ol><li>First</li><li>Second</li></ol>",
        )
        val unordered = blocks[0] as ContentBlock.ItemList
        assertEquals(false, unordered.ordered)
        assertEquals(listOf("One", "Two link", "Two A", "Two B", "Three"), unordered.items.map { it.text })
        assertEquals(listOf("link"), unordered.items[1].styled(InlineStyle.LINK))
        assertEquals(ContentBlock.ItemList(true, listOf(RichText("First"), RichText("Second"))), blocks[1])
    }

    @Test
    fun preformattedKeepsWhitespace() {
        val blocks = convert("<pre><code class=\"language-kotlin\">fun main() {\n    println(\"a &lt; b\")\n}\n</code></pre><pre>plain\n  text</pre>")
        assertEquals(
            listOf(
                ContentBlock.Preformatted("fun main() {\n    println(\"a < b\")\n}", "kotlin"),
                ContentBlock.Preformatted("plain\n  text"),
            ),
            blocks,
        )
    }

    @Test
    fun scriptsFormsAndEmbedsAreDropped() {
        val blocks = convert(
            "<script>alert(1)</script><style>p{}</style><p>Kept</p><iframe src='https://x.example/'></iframe>" +
                "<form><input value='x'><button>Go</button></form><noscript>No JS</noscript>",
        )
        assertEquals(listOf(ContentBlock.Paragraph(RichText("Kept"))), blocks)
    }

    @Test
    fun tablesDegradeToParagraphs() {
        val blocks = convert("<table><tr><td>A1</td><td>B1</td></tr><tr><td>A2</td></tr></table>")
        assertEquals(listOf("A1", "B1", "A2"), blocks.map { (it as ContentBlock.Paragraph).text.text })
    }

    @Test
    fun blankInputAndPlainText() {
        assertTrue(HtmlBlocks.convert("").isEmpty())
        assertTrue(HtmlBlocks.convert("   \n ").isEmpty())
        assertEquals(
            listOf("One < two\nsame paragraph", "Next"),
            HtmlBlocks.convert(HtmlBlocks.textToHtml("One < two\nsame paragraph\n\n\nNext\n")).map { (it as ContentBlock.Paragraph).text.text },
        )
        assertEquals("Tom & Jerry say hi", HtmlBlocks.plainText("<p>Tom &amp; <b>Jerry</b>\n say hi</p>"))
    }

    @Test
    fun blocksSurviveTheStorageCodec() {
        val blocks = convert(
            "<h2>H</h2><p>A <a href='/x'>link</a></p><img src='/i.png' alt='i'><blockquote>Q</blockquote><ul><li>L</li></ul><pre>P</pre>",
        )
        assertEquals(6, blocks.size)
        assertEquals(blocks, ContentBlocks.decode(ContentBlocks.encode(blocks)))
        assertEquals("H\nA link\nQ\nL\nP", ContentBlocks.plainText(blocks))
        assertNull((blocks[2] as ContentBlock.Image).caption)
    }
}

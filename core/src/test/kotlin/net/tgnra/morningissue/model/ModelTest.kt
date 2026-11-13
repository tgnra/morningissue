package net.tgnra.morningissue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun contentBlocksRoundTripThroughJson() {
        val blocks = listOf(
            ContentBlock.Heading(2, RichText("Heading")),
            ContentBlock.Paragraph(RichText("Some bold link", listOf(InlineSpan(5, 9, InlineStyle.BOLD), InlineSpan(10, 14, InlineStyle.LINK, "https://example.com")))),
            ContentBlock.Image("https://example.com/a.png", alt = "A", caption = RichText("Caption"), width = 640, height = 480),
            ContentBlock.Quote(listOf(RichText("Quoted")), citation = RichText("Someone")),
            ContentBlock.ItemList(ordered = true, items = listOf(RichText("one"), RichText("two"))),
            ContentBlock.Preformatted("val x = 1\n  indented", language = "kotlin"),
        )

        val json = ContentBlocks.encode(blocks)

        assertEquals(blocks, ContentBlocks.decode(json))
        assertTrue(json.startsWith("[{\"t\":\"h\""))
    }

    @Test
    fun unreadableContentDecodesToNothing() {
        assertEquals(emptyList<ContentBlock>(), ContentBlocks.decode(""))
        assertEquals(emptyList<ContentBlock>(), ContentBlocks.decode("not json"))
    }

    @Test
    fun plainTextAndWordCount() {
        val text = ContentBlocks.plainText(
            listOf(
                ContentBlock.Heading(1, RichText("A title")),
                ContentBlock.Image("https://example.com/a.png"),
                ContentBlock.ItemList(false, listOf(RichText("first item"), RichText("second"))),
            ),
        )
        assertEquals("A title\nfirst item\nsecond", text)
        assertEquals(5, ContentBlocks.wordCount(text))
        assertEquals(0, ContentBlocks.wordCount(""))
    }

    @Test
    fun feedUrlsNormalise() {
        val expected = "example.com/feed"
        listOf(
            "https://example.com/feed",
            "http://www.example.com/feed/",
            "HTTPS://Example.COM:443/feed#top",
            "example.com/feed",
        ).forEach { assertEquals(it, expected, FeedUrls.normalize(it)) }
        assertEquals("example.com:8080/feed?format=rss", FeedUrls.normalize("http://example.com:8080/feed?format=rss"))
        assertEquals("example.com", FeedUrls.hostOf("https://www.example.com/feed.xml"))
    }

    @Test
    fun sliderStepsStopAtTheEnds() {
        assertEquals(SliderStep.MORE, SliderStep.AUTO.stepUp())
        assertEquals(SliderStep.FAVOURITE, SliderStep.FAVOURITE.stepUp())
        assertEquals(SliderStep.RARELY, SliderStep.RARELY.stepDown())
    }

    @Test
    fun readingTimeRoundsUp() {
        assertEquals(1, ReadingTime.minutes(0))
        assertEquals(1, ReadingTime.minutes(230))
        assertEquals(2, ReadingTime.minutes(231))
    }
}

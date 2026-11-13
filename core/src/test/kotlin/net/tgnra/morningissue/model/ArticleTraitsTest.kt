package net.tgnra.morningissue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleTraitsTest {
    private fun text(words: String, vararg spans: InlineSpan) = RichText(words, spans.toList())

    private fun link(href: String) = InlineSpan(0, 4, InlineStyle.LINK, href)

    private fun paragraph(words: String, vararg hrefs: String) =
        ContentBlock.Paragraph(text(words, *hrefs.map(::link).toTypedArray()))

    private fun words(n: Int) = (1..n).joinToString(" ") { "word$it" }

    private val site = "https://blog.example/"

    // --- Categories -----------------------------------------------------------------------------

    @Test
    fun aCategoryKeyIsSquashedAndLowercasedAndTheLabelKeepsTheFeedsSpelling() {
        assertEquals(ArticleCategory("long reads", "Long Reads"), ArticleCategories.of("  Long \n\t Reads "))
        assertEquals(ArticleCategory("rust", "The Rust language"), ArticleCategories.of("Rust", " The  Rust language "))
        // A blank label falls back to the name; a blank name is no category.
        assertEquals(ArticleCategory("rust", "Rust"), ArticleCategories.of("Rust", "  "))
        assertNull(ArticleCategories.of(" \n "))
        assertEquals("élan vital", ArticleCategories.key("ÉLAN  Vital"))
    }

    @Test
    fun aCategoryIsCutAtSixtyFourCharacters() {
        val long = "Category " + "x".repeat(100)
        val category = ArticleCategories.of(long)!!
        assertEquals(64, category.key.length)
        assertEquals(64, category.label.length)
        assertEquals(long.take(64).lowercase(), category.key)
    }

    @Test
    fun aPostKeepsItsFirstTwelveDistinctCategories() {
        val many = (1..20).map { ArticleCategories.of("Tag $it")!! }
        val repeated = listOf(ArticleCategories.of("Tag 1")!!, ArticleCategories.of("TAG 1", "Shouted")!!) + many
        val clean = ArticleCategories.clean(repeated)
        assertEquals(12, clean.size)
        // One per key, the first spelling wins, the feed's order is kept.
        assertEquals((1..12).map { ArticleCategory("tag $it", "Tag $it") }, clean)
    }

    // --- Title prefix ---------------------------------------------------------------------------

    @Test
    fun aShortPrefixBeforeASeparatorIsTheSeriesMarker() {
        assertEquals("links", ArticleTraitRules.titlePrefix("Links: week 12"))
        assertEquals("show hn", ArticleTraitRules.titlePrefix("Show HN: A thing I made"))
        assertEquals("podcast", ArticleTraitRules.titlePrefix("Podcast | Episode 40"))
        assertEquals("weekly review", ArticleTraitRules.titlePrefix("Weekly  Review – the good parts"))
        assertEquals("notes", ArticleTraitRules.titlePrefix("Notes — on a book"))
        assertEquals("til", ArticleTraitRules.titlePrefix("TIL - pipes are files"))
        assertEquals("one two three", ArticleTraitRules.titlePrefix("One two three: four"))
    }

    @Test
    fun theFirstSeparatorInTheTitleDecides() {
        assertEquals("quoting", ArticleTraitRules.titlePrefix("Quoting: someone - on something | somewhere"))
        assertEquals("a", ArticleTraitRules.titlePrefix("A - B: C"))
    }

    @Test
    fun aLongPrefixOrATitleWithoutASeparatorHasNoMarker() {
        assertNull(ArticleTraitRules.titlePrefix("An ordinary headline"))
        assertNull(ArticleTraitRules.titlePrefix("Why the four word rule: explained"))
        // A colon or dash inside a word or a time is not a separator.
        assertNull(ArticleTraitRules.titlePrefix("Meeting at 10:30 about well-known things"))
        // Nothing before, or nothing after, the separator.
        assertNull(ArticleTraitRules.titlePrefix(": odd"))
        assertNull(ArticleTraitRules.titlePrefix("Links: "))
        assertNull(ArticleTraitRules.titlePrefix("Supercalifragilisticexpialidocious-and-then-some-more: x"))
        assertNull(ArticleTraitRules.titlePrefix(""))
    }

    // --- Link posts -----------------------------------------------------------------------------

    @Test
    fun aPostWhoseOwnLinkLeavesTheSiteIsALinkPost() {
        assertTrue(ArticleTraitRules.isLinkPost("https://elsewhere.example/story", site, emptyList(), 0))
        assertTrue(ArticleTraitRules.isLinkPost("https://elsewhere.example/story", site, emptyList(), 2000))
        assertFalse(ArticleTraitRules.isLinkPost("https://blog.example/2026/post", site, emptyList(), 0))
    }

    @Test
    fun wwwAndSubdomainsAreTheSameSite() {
        assertFalse(ArticleTraitRules.isLinkPost("http://www.blog.example/post", site, emptyList(), 0))
        assertFalse(ArticleTraitRules.isLinkPost("https://notes.blog.example/post", site, emptyList(), 0))
        assertFalse(ArticleTraitRules.isLinkPost("https://example.com/post", "https://blog.example.com", emptyList(), 0))
        // A shared suffix is not a subdomain.
        assertTrue(ArticleTraitRules.isLinkPost("https://myblog.example/post", site, emptyList(), 0))
    }

    @Test
    fun aShortBodyWithOneLinkOffTheSiteIsALinkPost() {
        val own = "https://blog.example/2026/pointer"
        val out = "https://elsewhere.example/story"
        assertTrue(ArticleTraitRules.isLinkPost(own, site, listOf(out), 40))
        // The same address twice (with a fragment, with a slash) is still one address.
        assertTrue(ArticleTraitRules.isLinkPost(own, site, listOf(out, "$out#part", "$out/"), 40))
        // Links within the site, and things that are not web addresses, do not count.
        assertTrue(ArticleTraitRules.isLinkPost(own, site, listOf(out, "https://blog.example/about", "mailto:me@blog.example"), 40))
        assertTrue(ArticleTraitRules.isLinkPost(own, site, listOf(out), ArticleTraitRules.LINK_POST_MAX_WORDS))
    }

    @Test
    fun aLongBodyOrSeveralLinksOutOrNoneIsNotALinkPost() {
        val own = "https://blog.example/2026/essay"
        val out = "https://elsewhere.example/story"
        assertFalse(ArticleTraitRules.isLinkPost(own, site, listOf(out), ArticleTraitRules.LINK_POST_MAX_WORDS + 1))
        assertFalse(ArticleTraitRules.isLinkPost(own, site, listOf(out, "https://third.example/"), 40))
        assertFalse(ArticleTraitRules.isLinkPost(own, site, listOf("https://blog.example/about"), 40))
        assertFalse(ArticleTraitRules.isLinkPost(own, site, emptyList(), 40))
    }

    @Test
    fun withoutASiteAddressTheBodyRuleGoesByThePostsOwnHostAndWithNeitherTheAnswerIsNo() {
        val out = "https://elsewhere.example/story"
        assertFalse(ArticleTraitRules.isLinkPost("https://elsewhere.example/story", null, emptyList(), 0))
        assertTrue(ArticleTraitRules.isLinkPost("https://blog.example/p", null, listOf(out), 10))
        assertTrue(ArticleTraitRules.isLinkPost(null, site, listOf(out), 10))
        assertFalse(ArticleTraitRules.isLinkPost(null, null, listOf(out), 10))
        assertFalse(ArticleTraitRules.isLinkPost("not a url", "also not", listOf(out), 10))
    }

    // --- The whole function ---------------------------------------------------------------------

    @Test
    fun countsLinksImagesAndCodeAcrossEveryKindOfBlock() {
        val blocks = listOf(
            paragraph("Read this and that", "https://blog.example/a", "https://one.example/"),
            ContentBlock.Heading(2, text("Head", link("https://two.example/"))),
            ContentBlock.Image("https://blog.example/i.png", caption = text("Shot", link("https://three.example/"))),
            ContentBlock.Image("https://blog.example/j.png"),
            ContentBlock.Quote(listOf(text("Said", link("https://four.example/"))), citation = text("Them", link("https://five.example/"))),
            ContentBlock.ItemList(false, listOf(text("Item", link("https://six.example/")), text("Bare"))),
            // A link span without an address is not a link.
            ContentBlock.Paragraph(text("Dead link", InlineSpan(0, 4, InlineStyle.LINK), InlineSpan(0, 4, InlineStyle.BOLD))),
        )
        val traits = ArticleTraitRules.compute("An essay", "https://blog.example/essay", site, blocks)
        assertEquals(
            ArticleTraits(linkCount = 7, imageCount = 2, hasCode = false, enclosure = EnclosureKind.NONE, isLinkPost = false, titlePrefix = null),
            traits,
        )
    }

    @Test
    fun aCodeBlockOrInlineCodeCountsAsCode() {
        val url = "https://blog.example/p"
        assertTrue(ArticleTraitRules.compute("T", url, site, listOf(ContentBlock.Preformatted("fun main() {}"))).hasCode)
        val inline = ContentBlock.Paragraph(text("Run make now", InlineSpan(4, 8, InlineStyle.CODE)))
        assertTrue(ArticleTraitRules.compute("T", url, site, listOf(inline)).hasCode)
        assertFalse(ArticleTraitRules.compute("T", url, site, listOf(paragraph("Plain prose"))).hasCode)
    }

    @Test
    fun aLinkBlogEntryAndAPodcastEpisode() {
        val pointer = ArticleTraitRules.compute(
            title = "Links: a fine piece on compilers",
            url = "https://blog.example/links/12",
            siteUrl = site,
            blocks = listOf(paragraph("This is worth your time.", "https://elsewhere.example/compilers")),
        )
        assertEquals(ArticleTraits(1, 0, false, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "links"), pointer)

        val episode = ArticleTraitRules.compute(
            title = "Podcast: Episode 40",
            url = "https://blog.example/podcast/40",
            siteUrl = site,
            blocks = listOf(paragraph(words(300), "https://elsewhere.example/guest")),
            enclosure = EnclosureKind.AUDIO,
        )
        assertEquals(ArticleTraits(1, 0, false, EnclosureKind.AUDIO, isLinkPost = false, titlePrefix = "podcast"), episode)
    }

    @Test
    fun withoutABodyThereIsOnlyTheTitleAndTheLinkToGoBy() {
        val trimmed = ArticleTraitRules.compute("Show HN: A thing", "https://thing.example/", "https://news.example/", emptyList())
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "show hn"), trimmed)
    }
}

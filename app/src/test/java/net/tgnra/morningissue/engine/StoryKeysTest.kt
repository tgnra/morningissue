package net.tgnra.morningissue.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryKeysTest {
    @Test
    fun canonicalUrlIgnoresSchemeHostCaseWwwSlashFragmentAndTracking() {
        val expected = "example.com/2026/story"
        listOf(
            "https://example.com/2026/story",
            "http://example.com/2026/story",
            "https://WWW.Example.COM/2026/story/",
            "https://example.com:443/2026/story#comments",
            "http://example.com:80/2026/story?utm_source=rss&utm_medium=feed",
            "https://example.com/2026/story?fbclid=abc123&UTM_Campaign=x",
            "  //example.com/2026/story  ",
        ).forEach { assertEquals(it, expected, StoryKeys.canonicalUrl(it)) }
    }

    @Test
    fun canonicalUrlKeepsWhatIdentifiesThePage() {
        assertEquals("example.com/story?id=7", StoryKeys.canonicalUrl("https://example.com/story?utm_source=x&id=7"))
        assertNotEquals(StoryKeys.canonicalUrl("https://example.com/story?id=7"), StoryKeys.canonicalUrl("https://example.com/story?id=8"))
        // Paths are case-sensitive, ports other than the default are a different server.
        assertNotEquals(StoryKeys.canonicalUrl("https://example.com/Story"), StoryKeys.canonicalUrl("https://example.com/story"))
        assertNotEquals(StoryKeys.canonicalUrl("https://example.com:8080/story"), StoryKeys.canonicalUrl("https://example.com/story"))
        assertNotEquals(StoryKeys.canonicalUrl("https://blog.example.com/story"), StoryKeys.canonicalUrl("https://example.com/story"))
        assertEquals("example.com", StoryKeys.canonicalUrl("https://www.example.com/"))
    }

    @Test
    fun canonicalUrlOfNothingUsableIsNull() {
        listOf(null, "", "   ", "/relative/path", "mailto:someone@example.com", "ftp://example.com/file", "https://")
            .forEach { assertNull(it, StoryKeys.canonicalUrl(it)) }
    }

    @Test
    fun titleKeyIgnoresCasePunctuationAccentsAndSpacing() {
        val expected = "the cafe that wasn t there a review"
        assertEquals(expected, StoryKeys.titleKey("The Café That Wasn’t There: A Review"))
        assertEquals(expected, StoryKeys.titleKey("  the cafe that wasn't   there — a review!  "))
        assertNull(StoryKeys.titleKey("  "))
        assertNull(StoryKeys.titleKey(null))
        assertNull(StoryKeys.titleKey("?!"))
    }

    @Test
    fun titleKeyDropsTheFeedsOwnNameFromEitherEnd() {
        val plain = StoryKeys.titleKey("Rust 1.90 is out")
        assertEquals(plain, StoryKeys.titleKey("Rust 1.90 is out | The Register", feedTitle = "The Register"))
        assertEquals(plain, StoryKeys.titleKey("The Register: Rust 1.90 is out", feedTitle = "the register"))
        assertEquals(plain, StoryKeys.titleKey("Rust 1.90 is out - The Register", feedTitle = "The Register"))
        // Another site's name is part of the headline, and a title that is only the feed's name stays.
        assertNotEquals(plain, StoryKeys.titleKey("Rust 1.90 is out | Ars", feedTitle = "The Register"))
        assertEquals("the register", StoryKeys.titleKey("The Register", feedTitle = "The Register"))
    }

    @Test
    fun titleKeyKeepsEveryWordAndNumber() {
        assertNotEquals(StoryKeys.titleKey("Security updates for Tuesday"), StoryKeys.titleKey("Security updates for Wednesday"))
        assertNotEquals(StoryKeys.titleKey("Rust 1.90 released"), StoryKeys.titleKey("Rust 1.91 released"))
        assertNotEquals(StoryKeys.titleKey("This week in Rust 571"), StoryKeys.titleKey("This week in Rust 572"))
    }
}

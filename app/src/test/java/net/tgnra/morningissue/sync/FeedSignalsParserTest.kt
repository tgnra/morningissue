package net.tgnra.morningissue.sync

import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.EnclosureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Categories and enclosures in each feed format, and the traits that follow from them. */
open class FeedSignalsParserTest {
    private fun parse(text: String, url: String = "https://blog.example/feed") = FeedParser.parse(text, url, NOW)

    // --- RSS ------------------------------------------------------------------------------------

    private val rss by lazy {
        parse(
            """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <title>A blog</title>
                <link>https://blog.example/</link>
                <category>Channel category, not a post's</category>
                <item>
                  <title>Links: three things</title>
                  <link>https://blog.example/links/3</link>
                  <guid isPermaLink="false">1</guid>
                  <category>Tech</category>
                  <category domain="https://blog.example/tags"><![CDATA[  Long
                     Reads ]]></category>
                  <category>  </category>
                  <dc:subject>TECH</dc:subject>
                  <dc:subject>Caf&#233;s &amp; bars</dc:subject>
                  <description>&lt;p&gt;See &lt;a href="https://elsewhere.example/a"&gt;this&lt;/a&gt;.&lt;/p&gt;</description>
                </item>
                <item>
                  <title>Episode 40</title>
                  <link>https://blog.example/podcast/40</link>
                  <guid isPermaLink="false">2</guid>
                  <enclosure url="/media/40.mp3" type="audio/mpeg" length="123"/>
                  <enclosure url="https://cdn.example/40.jpg" type="image/jpeg" length="1"/>
                  <description>Show notes.</description>
                </item>
                <item>
                  <title>A talk, a paper and a picture</title>
                  <guid isPermaLink="false">3</guid>
                  <enclosure url="https://cdn.example/paper.pdf" type="application/pdf"/>
                  <enclosure url="https://cdn.example/talk.MP4?dl=1"/>
                </item>
                <item>
                  <title>Only a picture</title>
                  <guid isPermaLink="false">4</guid>
                  <enclosure url="https://cdn.example/photo.png"/>
                </item>
                <item>
                  <title>Only a paper</title>
                  <guid isPermaLink="false">5</guid>
                  <enclosure url="https://cdn.example/paper.pdf" type="application/pdf"/>
                </item>
                <item>
                  <title>Many keywords</title>
                  <guid isPermaLink="false">6</guid>
                  ${(1..20).joinToString("") { "<category>Keyword $it</category>" }}
                  <category>${"y".repeat(80)}</category>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
    }

    @Test
    fun rssCategoriesAndDublinCoreSubjects() {
        assertEquals(
            listOf(ArticleCategory("tech", "Tech"), ArticleCategory("long reads", "Long Reads"), ArticleCategory("cafés & bars", "Cafés & bars")),
            rss.items[0].categories,
        )
        assertEquals(emptyList<ArticleCategory>(), rss.items[1].categories)
    }

    @Test
    fun rssKeepsTwelveCategoriesPerPost() {
        assertEquals((1..12).map { ArticleCategory("keyword $it", "Keyword $it") }, rss.items[5].categories)
    }

    @Test
    fun rssEnclosuresAndTheirKind() {
        val episode = rss.items[1]
        assertEquals(
            listOf(ItemEnclosure("https://blog.example/media/40.mp3", "audio/mpeg"), ItemEnclosure("https://cdn.example/40.jpg", "image/jpeg")),
            episode.enclosures,
        )
        assertEquals(EnclosureKind.AUDIO, episode.enclosure)
        // The image is still the lead image.
        assertEquals("https://cdn.example/40.jpg", episode.content.imageUrl)

        // Without a stated type the file extension decides; video beats the PDF listed first.
        assertEquals(EnclosureKind.VIDEO, rss.items[2].enclosure)
        // A picture alone is the post's image, not an enclosure.
        assertEquals(EnclosureKind.NONE, rss.items[3].enclosure)
        assertEquals(EnclosureKind.OTHER, rss.items[4].enclosure)
        assertEquals(EnclosureKind.NONE, rss.items[0].enclosure)
        assertTrue(rss.items[0].enclosures.isEmpty())
    }

    @Test
    fun traitsComeFromTheFeedsBodyTitleLinkAndEnclosure() {
        val links = rss.items[0].content.traits
        assertEquals(1, links.linkCount)
        assertTrue(links.isLinkPost)
        assertEquals("links", links.titlePrefix)
        assertEquals(EnclosureKind.NONE, links.enclosure)

        val episode = rss.items[1].content.traits
        assertEquals(EnclosureKind.AUDIO, episode.enclosure)
        assertFalse(episode.isLinkPost)
        assertEquals(null, episode.titlePrefix)
    }

    // --- Atom -----------------------------------------------------------------------------------

    private val atom by lazy {
        parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://blog.example/">
              <title>A blog</title>
              <link rel="alternate" type="text/html" href="/"/>
              <category term="feed-level"/>
              <entry>
                <title>An entry</title>
                <id>tag:blog.example,2026:1</id>
                <link rel="alternate" href="/posts/1"/>
                <link rel="enclosure" type="video/mp4" href="media/talk.mp4" length="1000"/>
                <link rel="enclosure" type="image/png" href="media/talk.png"/>
                <category term="rust" label="The Rust language" scheme="https://blog.example/tags"/>
                <category term="Essays"/>
                <category label="A label without a term"/>
                <category term="RUST" label="Again"/>
                <content type="html">&lt;p&gt;Text with &lt;code&gt;code&lt;/code&gt;.&lt;/p&gt;</content>
              </entry>
              <entry>
                <title>A link elsewhere</title>
                <id>tag:blog.example,2026:2</id>
                <link href="https://elsewhere.example/story"/>
                <summary>Plain.</summary>
              </entry>
            </feed>
            """.trimIndent(),
        )
    }

    @Test
    fun atomCategoriesAreKeyedByTermAndShownByLabel() {
        assertEquals(
            listOf(ArticleCategory("rust", "The Rust language"), ArticleCategory("essays", "Essays")),
            atom.items[0].categories,
        )
        assertTrue(atom.items[1].categories.isEmpty())
    }

    @Test
    fun atomEnclosureLinks() {
        val entry = atom.items[0]
        assertEquals(
            listOf(ItemEnclosure("https://blog.example/media/talk.mp4", "video/mp4"), ItemEnclosure("https://blog.example/media/talk.png", "image/png")),
            entry.enclosures,
        )
        assertEquals(EnclosureKind.VIDEO, entry.enclosure)
        assertEquals("https://blog.example/media/talk.png", entry.content.imageUrl)
        assertEquals("https://blog.example/posts/1", entry.url)

        val traits = entry.content.traits
        assertEquals(EnclosureKind.VIDEO, traits.enclosure)
        assertTrue(traits.hasCode)
        assertFalse(traits.isLinkPost)
        // The second entry's own link leaves the site.
        assertTrue(atom.items[1].content.traits.isLinkPost)
        assertEquals(EnclosureKind.NONE, atom.items[1].enclosure)
    }

    // --- JSON Feed ------------------------------------------------------------------------------

    private val json by lazy {
        parse(
            """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "A blog",
              "home_page_url": "https://blog.example/",
              "items": [
                { "id": "1", "url": "https://blog.example/1", "title": "Tagged", "content_text": "Text.",
                  "tags": ["Tech", " long  reads ", "tech", "", 7, null, ["nested"]] },
                { "id": "2", "url": "https://blog.example/2", "title": "Episode", "content_text": "Notes.",
                  "attachments": [
                    { "url": "https://cdn.example/2.png", "mime_type": "image/png" },
                    { "url": "/media/2.m4a", "mime_type": "audio/x-m4a", "size_in_bytes": 9 },
                    { "mime_type": "audio/mpeg" }
                  ] },
                { "id": "3", "url": "https://blog.example/3", "title": "One tag, written as a string", "content_text": "Text.", "tags": "solo" },
                { "id": "4", "url": "https://blog.example/4", "title": "Tags of the wrong shape", "content_text": "Text.", "tags": { "a": 1 } }
              ]
            }
            """.trimIndent(),
            "https://blog.example/feed.json",
        )
    }

    @Test
    fun jsonFeedTags() {
        assertEquals(
            listOf(ArticleCategory("tech", "Tech"), ArticleCategory("long reads", "long reads"), ArticleCategory("7", "7")),
            json.items[0].categories,
        )
        assertEquals(listOf(ArticleCategory("solo", "solo")), json.items[2].categories)
        // Tags that are not a list do not sink the feed.
        assertEquals(4, json.items.size)
        assertTrue(json.items[3].categories.isEmpty())
    }

    @Test
    fun jsonFeedAttachments() {
        val episode = json.items[1]
        assertEquals(
            listOf(ItemEnclosure("https://cdn.example/2.png", "image/png"), ItemEnclosure("https://blog.example/media/2.m4a", "audio/x-m4a")),
            episode.enclosures,
        )
        assertEquals(EnclosureKind.AUDIO, episode.enclosure)
        assertEquals(EnclosureKind.AUDIO, episode.content.traits.enclosure)
        assertEquals(EnclosureKind.NONE, json.items[0].enclosure)
    }

    // --- The saved fixtures ---------------------------------------------------------------------

    @Test
    fun theSavedFixturesCarryTheirTagsAndEnclosures() {
        val feed = FeedParser.parse(Fixtures.text("feed.json"), "https://json.example.net/feed.json", NOW)
        assertEquals(listOf(ArticleCategory("a", "a"), ArticleCategory("b", "b")), feed.items[0].categories)
        assertEquals(EnclosureKind.AUDIO, feed.items[1].enclosure)
        // Its only link is `external_url`, off the feed's own site.
        assertTrue(feed.items[2].content.traits.isLinkPost)

        val saved = FeedParser.parse(Fixtures.text("rss.xml"), "https://feeds.example.net/example", NOW)
        assertEquals(EnclosureKind.NONE, saved.items[0].enclosure)
        assertEquals(EnclosureKind.AUDIO, saved.items[1].enclosure)
    }
}

package net.tgnra.morningissue.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

open class OpmlTest {
    private fun read(name: String) = Opml.read(ByteArrayInputStream(Fixtures.bytes(name)))

    @Test
    fun nestedFoldersBecomeTags() {
        val document = read("nested.opml")
        assertEquals("My subscriptions", document.title)
        assertEquals(
            listOf(
                OpmlFeed("https://top.example/feed.xml", "Top Level Blog", "https://top.example/", emptyList()),
                // Listed again under News with a different scheme, www and trailing slash: one feed, both tags.
                OpmlFeed("https://kernel.example/rss", "Kernel News", "https://kernel.example", listOf("Tech", "News")),
                OpmlFeed("https://distro.example/atom.xml", "Distro Watch", null, listOf("Tech", "Linux")),
                OpmlFeed("https://deep.example/feed", "Deeply Nested", null, listOf("Tech", "Linux", "Deep")),
                // The category attribute adds the last segment of each path.
                OpmlFeed("https://world.example/feed?format=rss&lang=en", "World & Local", null, listOf("News", "Europe", "Daily")),
            ),
            document.feeds,
        )
    }

    @Test
    fun sloppyRealWorldInput() {
        val document = read("sloppy.opml")
        assertEquals("Export 2019", document.title)
        assertEquals(
            listOf(
                // Upper-case tags, lower-case attribute names, no type, HTML entity, bare ampersand.
                OpmlFeed("https://cafe.example/feed?a=1&b=2", "Café Blog", "https://cafe.example/", emptyList()),
                OpmlFeed("HTTPS://Shout.Example/RSS.xml", "Shouting", null, emptyList()),
                OpmlFeed("http://old.example/rss", "feed scheme", null, listOf("Folder & Co")),
                OpmlFeed("https://prefixed.example/rss", "feed prefix", null, listOf("Folder & Co")),
                OpmlFeed("https://bare.example/atom.xml", "no scheme", null, listOf("Folder & Co")),
                OpmlFeed("https://spaces.example/feed", null, null, listOf("Folder & Co")),
                OpmlFeed("https://urlattr.example/rss", "url attribute", null, listOf("Folder & Co")),
                OpmlFeed("https://titleisurl.example/feed", null, null, listOf("Folder & Co")),
                // The file declares ISO-8859-1.
                OpmlFeed("https://latin.example/feed", "Trémas", null, emptyList()),
            ),
            document.feeds,
        )
    }

    @Test
    fun byteOrderMarkAndMissingDeclaration() {
        val body = "<opml><body><outline text=\"Ünïcode\" xmlUrl=\"https://bom.example/feed\"/></body></opml>"
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(Charsets.UTF_8)
        assertEquals("Ünïcode", Opml.read(ByteArrayInputStream(utf8)).feeds.single().title)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals("Ünïcode", Opml.read(ByteArrayInputStream(utf16)).feeds.single().title)
    }

    @Test
    fun damagedFileKeepsTheFeedsBeforeTheDamage() {
        val cut = "<opml><body><outline text=\"A\" xmlUrl=\"https://a.example/feed\"/><outline text=\"B\" xmlUrl=\"https://b.exa"
        assertEquals(listOf("https://a.example/feed"), Opml.read(cut).feeds.map { it.url })
    }

    @Test
    fun whatIsNotOpmlIsRefused() {
        assertThrows(IOException::class.java) { Opml.read("") }
        assertThrows(IOException::class.java) { Opml.read("Just a text file.") }
        assertThrows(IOException::class.java) { Opml.read("<html><body><outline xmlUrl=\"https://a.example/\"/></body></html>") }
        assertThrows(IOException::class.java) { Opml.read(Fixtures.text("rss.xml")) }
        assertThrows(IOException::class.java) { Opml.read("{\"feeds\": []}") }
        assertThrows(IOException::class.java) { Opml.read("<opml><body><outline text=\"A\" xmlUrl=\"https://a.exa") }
        // Valid but empty is fine.
        assertTrue(Opml.read("<opml version=\"2.0\"><head/><body/></opml>").feeds.isEmpty())
    }

    @Test
    fun writesOneFolderPerTag() {
        val xml = Opml.write(
            listOf(
                OpmlFeed("https://b.example/feed", "Bravo", "https://b.example/", listOf("Tech")),
                OpmlFeed("https://a.example/feed?x=1&y=2", "Alpha & \"Omega\" <1>", null, listOf("tech", "News")),
                OpmlFeed("https://z.example/feed", "Zulu"),
                OpmlFeed("https://c.example/feed", null, null, listOf(" ")),
            ),
            title = "Mine",
            created = Instant.parse("2026-11-10T06:30:00Z"),
        )
        assertEquals(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head>
                <title>Mine</title>
                <dateCreated>Tue, 10 Nov 2026 06:30:00 GMT</dateCreated>
              </head>
              <body>
                <outline type="rss" text="c.example" title="c.example" xmlUrl="https://c.example/feed"/>
                <outline type="rss" text="Zulu" title="Zulu" xmlUrl="https://z.example/feed"/>
                <outline text="News" title="News">
                  <outline type="rss" text="Alpha &amp; &quot;Omega&quot; &lt;1&gt;" title="Alpha &amp; &quot;Omega&quot; &lt;1&gt;" xmlUrl="https://a.example/feed?x=1&amp;y=2"/>
                </outline>
                <outline text="Tech" title="Tech">
                  <outline type="rss" text="Alpha &amp; &quot;Omega&quot; &lt;1&gt;" title="Alpha &amp; &quot;Omega&quot; &lt;1&gt;" xmlUrl="https://a.example/feed?x=1&amp;y=2"/>
                  <outline type="rss" text="Bravo" title="Bravo" xmlUrl="https://b.example/feed" htmlUrl="https://b.example/"/>
                </outline>
              </body>
            </opml>

            """.trimIndent(),
            xml,
        )
    }

    @Test
    fun roundTrip() {
        val feeds = listOf(
            OpmlFeed("https://plain.example/feed", "Plain"),
            OpmlFeed("https://one.example/rss?a=1&b=2", "One \"quoted\" & <angled> 'single'", "https://one.example/", listOf("Tech")),
            OpmlFeed("https://two.example/atom.xml", "Zwei – ünïcödé 日本語 🗞", "https://two.example/blog", listOf("News", "Tech")),
            OpmlFeed("https://three.example/feed", "Tabs\tand  spaces", null, listOf("Arts & Letters", "Long / reads")),
        )
        val out = ByteArrayOutputStream()
        Opml.write(feeds, out)
        val read = Opml.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(Opml.DEFAULT_TITLE, read.title)
        val byUrl = read.feeds.associateBy { it.url }
        assertEquals(feeds.map { it.url }.toSet(), byUrl.keys)
        for (feed in feeds) {
            val back = byUrl.getValue(feed.url)
            assertEquals(Texts.squash(feed.title!!), back.title)
            assertEquals(feed.siteUrl, back.siteUrl)
            assertEquals(feed.tags.toSet(), back.tags.toSet())
        }

        // A second trip changes nothing more.
        assertEquals(Opml.write(read.feeds), Opml.write(Opml.read(Opml.write(read.feeds)).feeds))
        assertNull(byUrl.getValue("https://plain.example/feed").siteUrl)
    }
}

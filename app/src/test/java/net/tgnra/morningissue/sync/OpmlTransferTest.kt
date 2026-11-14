package net.tgnra.morningissue.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.RoomFeedRepository
import net.tgnra.morningissue.model.NewFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** OPML import and export through the real repository and an in-memory database. */
@RunWith(AndroidJUnit4::class)
class OpmlTransferTest {
    private val clock = MutableClock()
    private lateinit var db: MorningIssueDatabase
    private lateinit var feeds: RoomFeedRepository
    private lateinit var transfer: DefaultOpmlTransfer

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = RoomFeedRepository(db, clock)
        transfer = DefaultOpmlTransfer(feeds, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String) = ByteArrayInputStream(Fixtures.bytes(name))

    @Test
    fun importAddsFeedsWithFolderNamesAsTags() = runBlocking<Unit> {
        assertEquals(OpmlImportReport(added = 5, skipped = 0), transfer.importFrom(fixture("nested.opml")))

        val byUrl = feeds.getFeeds().associateBy { it.url }
        assertEquals(5, byUrl.size)
        val top = byUrl.getValue("https://top.example/feed.xml")
        assertEquals("Top Level Blog", top.title)
        assertEquals("https://top.example/", top.siteUrl)
        assertTrue(top.tags.isEmpty())
        assertEquals(listOf("News", "Tech"), byUrl.getValue("https://kernel.example/rss").tags)
        assertEquals(listOf("Deep", "Linux", "Tech"), byUrl.getValue("https://deep.example/feed").tags)
        assertEquals(listOf("Daily", "Europe", "News"), byUrl.getValue("https://world.example/feed?format=rss&lang=en").tags)
        // Nothing was fetched: the feeds have never been synced.
        assertNull(top.lastSyncAt)
        assertEquals(0, db.articleDao().count())
    }

    @Test
    fun importSkipsFeedsAlreadySubscribed() = runBlocking<Unit> {
        // Same feeds as in the file, written differently; one has the user's own title and tag.
        feeds.addFeed(NewFeed(url = "http://www.kernel.example/rss/", title = "My kernel feed", tags = listOf("Mine")))
        feeds.addFeed(NewFeed(url = "https://TOP.example/feed.xml"))

        assertEquals(OpmlImportReport(added = 3, skipped = 2), transfer.importFrom(fixture("nested.opml")))

        val all = feeds.getFeeds()
        assertEquals(5, all.size)
        val kernel = all.single { "kernel" in it.url }
        // The existing subscription is untouched.
        assertEquals("http://www.kernel.example/rss/", kernel.url)
        assertEquals("My kernel feed", kernel.title)
        assertEquals(listOf("Mine"), kernel.tags)

        // Importing the same file again adds nothing.
        assertEquals(OpmlImportReport(added = 0, skipped = 5), transfer.importFrom(fixture("nested.opml")))
        assertEquals(5, feeds.getFeeds().size)
    }

    @Test
    fun importToleratesSloppyFiles() = runBlocking<Unit> {
        assertEquals(OpmlImportReport(added = 9, skipped = 0), transfer.importFrom(fixture("sloppy.opml")))
        val all = feeds.getFeeds()
        assertEquals("Café Blog", all.single { "cafe" in it.url }.title)
        // No usable title in the file: the host stands in until the first sync.
        assertEquals("spaces.example", all.single { "spaces" in it.url }.title)
        assertEquals(listOf("Folder & Co"), all.single { "bare.example" in it.url }.tags)
    }

    @Test
    fun importRefusesWhatIsNotOpmlAndAddsNothing() = runBlocking<Unit> {
        assertThrows(IOException::class.java) { runBlocking { transfer.importFrom(fixture("rss.xml")) } }
        assertThrows(IOException::class.java) { runBlocking { transfer.importFrom(ByteArrayInputStream(ByteArray(0))) } }
        assertTrue(feeds.getFeeds().isEmpty())
    }

    @Test
    fun exportThenImportRestoresTheSubscriptions() = runBlocking<Unit> {
        feeds.addFeed(NewFeed("https://plain.example/feed", "Plain"))
        feeds.addFeed(NewFeed("https://one.example/rss?a=1&b=2", "One \"quoted\" & <angled>", "https://one.example/", tags = listOf("Tech")))
        feeds.addFeed(NewFeed("https://two.example/atom.xml", "Zwei – ünïcödé 日本語", "https://two.example/blog", tags = listOf("News", "Tech", "Arts & Letters")))
        feeds.addFeed(NewFeed("https://untitled.example/feed"))
        val before = feeds.getFeeds()

        val out = ByteArrayOutputStream()
        assertEquals(4, transfer.exportTo(out))
        val xml = out.toString("UTF-8")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">"))
        // One folder per tag; a feed with three tags is written three times.
        assertEquals(3, Regex("<outline text=").findAll(xml).count())
        assertEquals(3, Regex("xmlUrl=\"https://two.example/atom.xml\"").findAll(xml).count())

        // Importing into the same list changes nothing.
        assertEquals(OpmlImportReport(added = 0, skipped = 4), transfer.importFrom(ByteArrayInputStream(out.toByteArray())))

        // Importing into an empty list gives the same subscriptions back.
        before.forEach { feeds.removeFeed(it.id) }
        assertTrue(feeds.getFeeds().isEmpty())
        assertEquals(OpmlImportReport(added = 4, skipped = 0), transfer.importFrom(ByteArrayInputStream(out.toByteArray())))
        val after = feeds.getFeeds()
        assertEquals(before.map { it.url }, after.map { it.url })
        assertEquals(before.map { it.title }, after.map { it.title })
        assertEquals(before.map { it.siteUrl }, after.map { it.siteUrl })
        assertEquals(before.map { it.tags }, after.map { it.tags })

        // And a second export is byte-for-byte the first (apart from nothing: the clock is fixed).
        val again = ByteArrayOutputStream()
        transfer.exportTo(again)
        assertEquals(xml, again.toString("UTF-8"))
    }

    @Test
    fun exportOfNothingIsAValidEmptyDocument() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()
        assertEquals(0, transfer.exportTo(out))
        assertTrue(Opml.read(ByteArrayInputStream(out.toByteArray())).feeds.isEmpty())
    }
}

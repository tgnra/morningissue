package net.tgnra.morningissue.server.feeds

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.OpmlReportDto
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.Opml
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** OPML import and export of one user's subscriptions; ported from the app's `OpmlTransferTest`. */
class OpmlTransferTest {
    private val clock = MutableClock()
    private lateinit var db: ServerDatabase
    private lateinit var subscriptions: Subscriptions
    private lateinit var transfer: OpmlTransfer
    private var user = 0L

    @Before
    fun setUp() = runBlocking {
        db = ServerDatabase.inMemory()
        user = db.newUser("tania")
        subscriptions = Subscriptions(db, clock)
        transfer = OpmlTransfer(db, subscriptions, clock)
    }

    @After
    fun tearDown() = db.close()

    private data class Feed(val id: Long, val url: String, val title: String, val siteUrl: String?, val tags: List<String>, val lastSyncAt: Instant?)

    private suspend fun feeds(userId: Long = user): List<Feed> {
        val tags = db.subscriptionDao().tagLinks(userId).groupBy({ it.feedId }) { it.name }
        return db.subscriptionDao().subscribedDetails(userId).map {
            Feed(it.feed.id, it.feed.url, it.title, it.feed.siteUrl, tags[it.feed.id].orEmpty().sorted(), it.feed.lastSuccessAt)
        }
    }

    private suspend fun import(name: String, userId: Long = user) = transfer.import(userId, CoreFixtures.bytes(name))

    @Test
    fun importAddsFeedsWithFolderNamesAsTags() = runBlocking<Unit> {
        assertEquals(OpmlReportDto(added = 5, skipped = 0), import("nested.opml"))

        val byUrl = feeds().associateBy { it.url }
        assertEquals(5, byUrl.size)
        val top = byUrl.getValue("https://top.example/feed.xml")
        assertEquals("Top Level Blog", top.title)
        assertEquals("https://top.example/", top.siteUrl)
        assertTrue(top.tags.isEmpty())
        assertEquals(listOf("News", "Tech"), byUrl.getValue("https://kernel.example/rss").tags)
        assertEquals(listOf("Deep", "Linux", "Tech"), byUrl.getValue("https://deep.example/feed").tags)
        assertEquals(listOf("Daily", "Europe", "News"), byUrl.getValue("https://world.example/feed?format=rss&lang=en").tags)
        // Nothing was fetched.
        assertNull(top.lastSyncAt)
        assertEquals(0, db.articleDao().count())
    }

    @Test
    fun importSkipsFeedsAlreadySubscribed() = runBlocking<Unit> {
        subscriptions.add(user, "http://www.kernel.example/rss/", title = "My kernel feed", tags = listOf("Mine"))
        subscriptions.add(user, "https://TOP.example/feed.xml")

        assertEquals(OpmlReportDto(added = 3, skipped = 2), import("nested.opml"))

        val all = feeds()
        assertEquals(5, all.size)
        val kernel = all.single { "kernel" in it.url }
        assertEquals("http://www.kernel.example/rss/", kernel.url)
        assertEquals("My kernel feed", kernel.title)
        assertEquals(listOf("Mine"), kernel.tags)

        assertEquals(OpmlReportDto(added = 0, skipped = 5), import("nested.opml"))
        assertEquals(5, feeds().size)
    }

    @Test
    fun anotherUsersImportSharesTheFeedsButNotTheTagsOrTitles() = runBlocking<Unit> {
        val other = db.newUser("other")
        subscriptions.add(user, "http://www.kernel.example/rss/", title = "My kernel feed", tags = listOf("Mine"))

        assertEquals(OpmlReportDto(added = 5, skipped = 0), import("nested.opml", other))
        assertEquals(5L, db.long("SELECT COUNT(*) FROM feed"))
        assertEquals(listOf("News", "Tech"), feeds(other).single { "kernel" in it.url }.tags)
        val mine = feeds(user).single()
        assertEquals(listOf("Mine"), mine.tags)
        assertEquals("My kernel feed", mine.title)
    }

    @Test
    fun importToleratesSloppyFiles() = runBlocking<Unit> {
        assertEquals(OpmlReportDto(added = 9, skipped = 0), import("sloppy.opml"))
        val all = feeds()
        assertEquals("Café Blog", all.single { "cafe" in it.url }.title)
        assertEquals("spaces.example", all.single { "spaces" in it.url }.title)
        assertEquals(listOf("Folder & Co"), all.single { "bare.example" in it.url }.tags)
    }

    @Test
    fun importRefusesWhatIsNotOpmlAndAddsNothing() = runBlocking<Unit> {
        assertThrows(IOException::class.java) { runBlocking { import("rss.xml") } }
        assertThrows(IOException::class.java) { runBlocking { transfer.import(user, ByteArray(0)) } }
        assertTrue(feeds().isEmpty())
    }

    @Test
    fun exportThenImportRestoresTheSubscriptions() = runBlocking<Unit> {
        subscriptions.add(user, "https://plain.example/feed", "Plain")
        subscriptions.add(user, "https://one.example/rss?a=1&b=2", "One \"quoted\" & <angled>", "https://one.example/", tags = listOf("Tech"))
        subscriptions.add(user, "https://two.example/atom.xml", "Zwei – ünïcödé 日本語", "https://two.example/blog", tags = listOf("News", "Tech", "Arts & Letters"))
        subscriptions.add(user, "https://untitled.example/feed")
        val before = feeds()

        val xml = transfer.export(user)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">"))
        assertEquals(3, Regex("<outline text=").findAll(xml).count())
        assertEquals(3, Regex("xmlUrl=\"https://two.example/atom.xml\"").findAll(xml).count())

        assertEquals(OpmlReportDto(added = 0, skipped = 4), transfer.import(user, xml.toByteArray()))

        before.forEach { subscriptions.remove(user, it.id) }
        assertTrue(feeds().isEmpty())
        assertEquals(OpmlReportDto(added = 4, skipped = 0), transfer.import(user, xml.toByteArray()))
        val after = feeds()
        assertEquals(before.map { it.url }, after.map { it.url })
        assertEquals(before.map { it.title }, after.map { it.title })
        assertEquals(before.map { it.siteUrl }, after.map { it.siteUrl })
        assertEquals(before.map { it.tags }, after.map { it.tags })

        assertEquals(xml, transfer.export(user))
    }

    @Test
    fun exportOfNothingIsAValidEmptyDocument() = runBlocking<Unit> {
        assertTrue(Opml.read(ByteArrayInputStream(transfer.export(user).toByteArray())).feeds.isEmpty())
    }
}

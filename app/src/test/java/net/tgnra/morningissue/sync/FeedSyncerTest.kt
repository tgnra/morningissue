package net.tgnra.morningissue.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.HealthFlag
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The syncer against a real (in-memory) database and a local web server. */
@RunWith(AndroidJUnit4::class)
class FeedSyncerTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: MorningIssueDatabase
    private lateinit var syncer: DefaultFeedSyncer

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        syncer = DefaultFeedSyncer(db, FeedFetcher(OkHttpClient()), clock, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun addFeed(path: String, title: String = "Feed $path"): Long =
        db.feedDao().insert(FeedEntity(url = url(path), title = title, addedAt = clock.now))

    private fun rfc822(daysAgo: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.minus(Duration.ofDays(daysAgo)).atOffset(ZoneOffset.UTC))

    @Test
    fun storesNewPostsOnceWithFirstSeenNow() = runBlocking<Unit> {
        val feedId = addFeed("/feed")
        routes["/feed"] = { response(rssOf("a", "b", pubDate = { rfc822(it + 1L) })) }

        val first = syncer.syncAll()
        assertEquals(SyncReport(feedsChecked = 1, feedsFailed = 0, newArticles = 2), first)
        val a = db.articleDao().getByGuid(feedId, "a")!!
        assertEquals("Post a", a.title)
        assertEquals("https://site.example/a", a.url)
        assertEquals("Summary of a", a.summary)
        assertEquals(NOW, a.firstSeenAt)
        assertEquals(NOW.minus(Duration.ofDays(1)), a.publishedAt)
        assertEquals(3, a.wordCount)
        assertNotNull(a.contentHash)

        // Nothing new: a second sync adds nothing and leaves the stored posts alone.
        clock.now = NOW.plusSeconds(3600)
        assertEquals(SyncReport(feedsChecked = 1, feedsFailed = 0, newArticles = 0), syncer.syncAll())
        assertEquals(2, db.articleDao().count())
        assertEquals(NOW, db.articleDao().getByGuid(feedId, "a")!!.firstSeenAt)

        // One new post: only it is added, first seen at the later time.
        clock.now = NOW.plusSeconds(7200)
        routes["/feed"] = { response(rssOf("c", "a", "b")) }
        assertEquals(1, syncer.syncAll().newArticles)
        assertEquals(3, db.articleDao().count())
        val c = db.articleDao().getByGuid(feedId, "c")!!
        assertEquals(NOW.plusSeconds(7200), c.firstSeenAt)
        // No date in the feed: the fetch time stands in.
        assertEquals(NOW.plusSeconds(7200), c.publishedAt)
        assertEquals(NOW.plusSeconds(7200), db.feedDao().getById(feedId)!!.lastSyncAt)
    }

    @Test
    fun storesFeedContentAsBlocksAndIndexesIt() = runBlocking<Unit> {
        val feedId = addFeed("/rss")
        // Wrong Content-Type on purpose: the format is read from the document.
        routes["/rss"] = { response(Fixtures.bytes("rss.xml"), "Content-Type" to "text/html") }

        assertEquals(5, syncer.syncAll().newArticles)
        val article = db.articleDao().getByGuid(feedId, "post-1")!!
        assertEquals("https://cdn.example.com/lead.jpg", article.imageUrl)
        assertEquals("Ada Lovelace", article.author)
        assertEquals(13, article.wordCount)

        val content = db.articleDao().getContent(article.id)!!
        assertEquals(ContentSource.FEED, content.source)
        val blocks = ContentBlocks.decode(content.blocksJson)
        assertEquals(4, blocks.size)
        assertTrue(blocks[1] is ContentBlock.Heading)
        assertTrue(blocks[2] is ContentBlock.Image)

        // The body is searchable.
        val hits = db.articleDao().search("heading", null, null, null, null, likedOnly = false, savedOnly = false, limit = 10)
        assertEquals(listOf(article.id), hits.map { it.row.article.id })

        // A post without any text has no content row.
        val bare = db.articleDao().getByGuid(feedId, "https://example.com/posts/permalink")!!
        assertNull(db.articleDao().getContent(bare.id))

        // Site link and icon come from the feed.
        val feed = db.feedDao().getById(feedId)!!
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals("https://example.com/images/logo.png", feed.iconUrl)
    }

    @Test
    fun conditionalRequestsUseStoredValidators() = runBlocking<Unit> {
        val feedId = addFeed("/feed")
        routes["/feed"] = { response(rssOf("a"), 200, "ETag" to "\"v1\"", "Last-Modified" to "Mon, 09 Nov 2026 10:00:00 GMT") }
        syncer.syncAll()
        assertNull(server.takeRequest().headers["If-None-Match"])
        assertEquals("\"v1\"", db.feedDao().getById(feedId)!!.etag)

        clock.now = NOW.plusSeconds(3600)
        routes["/feed"] = { response(code = 304) }
        assertEquals(SyncReport(feedsChecked = 1, feedsFailed = 0, newArticles = 0), syncer.syncAll())
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.headers["If-None-Match"])
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", second.headers["If-Modified-Since"])

        // A 304 keeps the validators, counts as a successful sync and as healthy.
        val feed = db.feedDao().getById(feedId)!!
        assertEquals("\"v1\"", feed.etag)
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", feed.lastModified)
        assertEquals(NOW.plusSeconds(3600), feed.lastSyncAt)
        val health = db.feedHealthDao().get(feedId)!!
        assertEquals(304, health.lastHttpStatus)
        assertEquals(0, health.errorStreak)
    }

    @Test
    fun failuresAreCountedRecordedAndDoNotStopOtherFeeds() = runBlocking<Unit> {
        val good = addFeed("/good")
        val missing = addFeed("/missing")
        val broken = addFeed("/broken")
        val notAFeed = addFeed("/page")
        val unreachable = db.feedDao().insert(FeedEntity(url = "http://no-such-host.invalid/feed", title = "Dead", addedAt = NOW))
        routes["/good"] = { response(rssOf("a")) }
        routes["/broken"] = { response("oops", 503) }
        routes["/page"] = { response("<html><body>Not a feed</body></html>") }

        assertEquals(SyncReport(feedsChecked = 5, feedsFailed = 4, newArticles = 1), syncer.syncAll())

        val health = db.feedHealthDao()
        assertEquals(0, health.get(good)!!.errorStreak)
        assertEquals(200, health.get(good)!!.lastHttpStatus)
        assertNotNull(health.get(good)!!.lastPostAt)
        assertEquals(404, health.get(missing)!!.lastHttpStatus)
        assertEquals(503, health.get(broken)!!.lastHttpStatus)
        // Answered, but not with a feed: an error that carries the 200.
        assertEquals(200, health.get(notAFeed)!!.lastHttpStatus)
        assertEquals(1, health.get(notAFeed)!!.errorStreak)
        // No answer at all.
        assertNull(health.get(unreachable)!!.lastHttpStatus)
        assertEquals(NOW, health.get(unreachable)!!.errorSince)
        // A failed fetch is not a sync.
        assertNull(db.feedDao().getById(missing)!!.lastSyncAt)

        // The streak grows, then clears when the feed comes back.
        clock.now = NOW.plusSeconds(3600)
        syncer.syncAll()
        assertEquals(2, health.get(broken)!!.errorStreak)
        assertEquals(NOW, health.get(broken)!!.errorSince)
        routes["/broken"] = { response(rssOf("x")) }
        assertEquals(SyncReport(feedsChecked = 1, feedsFailed = 0, newArticles = 1), syncer.syncFeed(broken))
        assertEquals(0, health.get(broken)!!.errorStreak)
        assertNull(health.get(broken)!!.errorSince)
    }

    @Test
    fun updatesCadenceAfterSync() = runBlocking<Unit> {
        val daily = addFeed("/daily")
        val rare = addFeed("/rare")
        val fresh = addFeed("/fresh")
        val undated = addFeed("/undated")
        val guids = (0 until 12).map { "p$it" }.toTypedArray()
        routes["/daily"] = { response(rssOf(*guids, pubDate = { rfc822(it.toLong()) })) }
        routes["/rare"] = { response(rssOf(*guids, pubDate = { rfc822(it * 20L) })) }
        routes["/fresh"] = { response(rssOf("a", "b", "c", pubDate = { rfc822(it.toLong()) })) }
        routes["/undated"] = { response(rssOf(*guids)) }

        syncer.syncAll()

        val dailyFeed = db.feedDao().getById(daily)!!
        assertEquals(CadenceTier.DAILY, dailyFeed.tier)
        assertEquals(24.0, dailyFeed.medianIntervalHrs!!, 1e-6)
        assertEquals(1.0, dailyFeed.burstiness!!, 1e-6)

        val rareFeed = db.feedDao().getById(rare)!!
        assertEquals(CadenceTier.RARE, rareFeed.tier)
        assertEquals(480.0, rareFeed.medianIntervalHrs!!, 1e-6)

        // Fewer than five posts: Weekly by default, no figures yet.
        val freshFeed = db.feedDao().getById(fresh)!!
        assertEquals(CadenceTier.WEEKLY, freshFeed.tier)
        assertNull(freshFeed.medianIntervalHrs)

        // Twelve posts without dates all carry the fetch time; that is not a firehose.
        assertEquals(CadenceTier.WEEKLY, db.feedDao().getById(undated)!!.tier)
    }

    @Test
    fun permanentRedirectUpdatesTheAddressAndFlagsTheMove() = runBlocking<Unit> {
        val moved = addFeed("/old")
        val temporary = addFeed("/temp")
        routes["/old"] = { response(code = 301, headers = arrayOf("Location" to "/new")) }
        routes["/new"] = { response(rssOf("a"), 200, "ETag" to "\"new\"") }
        routes["/temp"] = { response(code = 302, headers = arrayOf("Location" to "/new")) }

        assertEquals(SyncReport(feedsChecked = 2, feedsFailed = 0, newArticles = 2), syncer.syncAll())

        val feed = db.feedDao().getById(moved)!!
        assertEquals(url("/new"), feed.url)
        // The new address's validators are kept for the next request.
        assertEquals("\"new\"", feed.etag)
        assertEquals(HealthFlag.MOVED, db.feedHealthDao().get(moved)!!.lastFlag)
        assertEquals(301, db.feedHealthDao().get(moved)!!.lastHttpStatus)

        // A temporary redirect changes nothing.
        assertEquals(url("/temp"), db.feedDao().getById(temporary)!!.url)
        assertNull(db.feedHealthDao().get(temporary)!!.lastFlag)

        // Next time the new address is asked directly.
        syncer.syncFeed(moved)
        assertEquals(1, routes.hits("/old"))
        assertEquals(200, db.feedHealthDao().get(moved)!!.lastHttpStatus)
    }

    @Test
    fun redirectOntoAnotherSubscriptionLeavesTheAddressAlone() = runBlocking<Unit> {
        val old = addFeed("/old")
        addFeed("/new")
        routes["/old"] = { response(code = 301, headers = arrayOf("Location" to "/new")) }
        routes["/new"] = { response(rssOf("a")) }

        assertEquals(0, syncer.syncAll().feedsFailed)
        assertEquals(url("/old"), db.feedDao().getById(old)!!.url)
    }

    @Test
    fun hostNameTitleIsReplacedByTheFeedsOwnButARealTitleIsKept() = runBlocking<Unit> {
        val untitled = db.feedDao().insert(FeedEntity(url = url("/a"), title = server.hostName, addedAt = NOW))
        val named = addFeed("/b", title = "My name for it")
        routes["/a"] = { response(rssOf("a", title = "Real Title")) }
        routes["/b"] = { response(rssOf("b", title = "Real Title")) }

        syncer.syncAll()
        assertEquals("Real Title", db.feedDao().getById(untitled)!!.title)
        assertEquals("My name for it", db.feedDao().getById(named)!!.title)
    }

    @Test
    fun futureDatesAreClampedToNow() = runBlocking<Unit> {
        val feedId = addFeed("/feed")
        routes["/feed"] = { response(rssOf("a", pubDate = { rfc822(-30) })) }
        syncer.syncAll()
        assertEquals(NOW, db.articleDao().getByGuid(feedId, "a")!!.publishedAt)
    }

    @Test
    fun statusReportsProgressAndASecondCallJoinsTheRunningSync() = runBlocking<Unit> {
        addFeed("/slow")
        addFeed("/fast")
        val release = CompletableDeferred<Unit>()
        routes["/slow"] = {
            runBlocking { release.await() }
            response(rssOf("a"))
        }
        routes["/fast"] = { response(rssOf("b")) }
        assertEquals(SyncStatus.Idle, syncer.status.value)

        val first = async(Dispatchers.Default) { syncer.syncAll() }
        withTimeout(10_000) { syncer.status.first { it == SyncStatus.Running(1, 2) } }
        val second = async(Dispatchers.Default) { syncer.syncAll() }
        release.complete(Unit)

        val report = first.await()
        assertEquals(SyncReport(feedsChecked = 2, feedsFailed = 0, newArticles = 2), report)
        // The second caller got the same run's report, and no feed was fetched twice.
        assertEquals(report, second.await())
        assertEquals(1, routes.hits("/slow"))
        assertEquals(1, routes.hits("/fast"))
        assertEquals(SyncStatus.Idle, syncer.status.value)
    }

    @Test
    fun syncFeedFetchesOnlyThatFeedAndUnknownFeedIsEmpty() = runBlocking<Unit> {
        val one = addFeed("/one")
        addFeed("/two")
        routes["/one"] = { response(rssOf("a")) }
        routes["/two"] = { response(rssOf("b")) }

        assertEquals(SyncReport(feedsChecked = 1, feedsFailed = 0, newArticles = 1), syncer.syncFeed(one))
        assertEquals(0, routes.hits("/two"))
        assertEquals(SyncReport(), syncer.syncFeed(9999))
        assertEquals(SyncStatus.Idle, syncer.status.value)
    }

    @Test
    fun noFeedsIsAnEmptyReport() = runBlocking<Unit> {
        assertEquals(SyncReport(), syncer.syncAll())
    }

    @Test
    fun teaserPostsAreFetchedFromTheirPageOnRefresh() = runBlocking<Unit> {
        val fetching = DefaultFeedSyncer(
            db, FeedFetcher(OkHttpClient()), clock, scope,
            fullText = DefaultFullTextFetcher(db, FeedFetcher(OkHttpClient()), clock),
        )
        val feedId = addFeed("/teasers")
        val page = url("/article")
        routes["/article"] = { response(Fixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
        routes["/teasers"] = {
            response(
                """<?xml version="1.0"?><rss version="2.0"><channel><title>Cafe</title><link>https://site.example/</link>""" +
                    """<item><title>The Long Way Round</title><guid>t</guid><link>$page</link>""" +
                    """<description><![CDATA[<p>A ferry trip.</p><p>Read in full on <a href="$page">Cafe</a>.</p>]]></description></item>""" +
                    """<item><title>Short note</title><guid>n</guid><link>$page</link>""" +
                    """<description><![CDATA[<p>A short note, complete as it is.</p>]]></description></item>""" +
                    "</channel></rss>",
            )
        }

        assertEquals(2, fetching.syncAll().newArticles)

        val teaser = db.articleDao().getByGuid(feedId, "t")!!
        val content = db.articleDao().getContent(teaser.id)!!
        assertEquals(ContentSource.FETCHED, content.source)
        assertTrue("The ferry leaves the harbour at six in the morning" in ContentBlocks.plainText(ContentBlocks.decode(content.blocksJson)))
        assertTrue(db.articleDao().getById(teaser.id)!!.wordCount > 200)
        // A short post that does not send the reader away is left as the feed gave it.
        val note = db.articleDao().getByGuid(feedId, "n")!!
        assertEquals(ContentSource.FEED, db.articleDao().getContent(note.id)!!.source)
        assertEquals(1, routes.hits("/article"))
    }
}

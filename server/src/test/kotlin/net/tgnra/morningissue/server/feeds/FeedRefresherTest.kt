package net.tgnra.morningissue.server.feeds

import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.RefreshReport
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import net.tgnra.morningissue.sync.rssOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The refresher against a real (in-memory) database and a local web server; ported from the app's `FeedSyncerTest`. */
class FeedRefresherTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: ServerDatabase
    private lateinit var refresher: DefaultFeedRefresher
    private var user = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        user = db.newUser("tania")
        refresher = DefaultFeedRefresher(db, FeedFetcher(OkHttpClient()), clock, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun addFeed(path: String, title: String = "Feed $path"): Long = db.feedFor(url(path), clock.now, user, title = title)

    private suspend fun refreshAll(r: DefaultFeedRefresher = refresher) = r.refresh(db.feedDao().subscribedFeedIds(), Duration.ZERO)

    private suspend fun refreshOne(feedId: Long) = refresher.refresh(listOf(feedId), Duration.ZERO)

    private fun report(checked: Int, failed: Int, new: Int) = RefreshReport(attempted = checked, answered = checked - failed, newArticles = new, finishedInTime = true)

    private fun rfc822(daysAgo: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.minus(Duration.ofDays(daysAgo)).atOffset(ZoneOffset.UTC))

    @Test
    fun storesNewPostsOnceWithFirstSeenNow() = runBlocking<Unit> {
        val feedId = addFeed("/feed")
        routes["/feed"] = { response(rssOf("a", "b", pubDate = { rfc822(it + 1L) })) }

        assertEquals(report(1, 0, 2), refreshAll())
        val a = db.articleDao().getByGuid(feedId, "a")!!
        assertEquals("Post a", a.title)
        assertEquals("https://site.example/a", a.url)
        assertEquals("Summary of a", a.summary)
        assertEquals(NOW, a.firstSeenAt)
        assertEquals(NOW.minus(Duration.ofDays(1)), a.publishedAt)
        assertEquals(3, a.wordCount)
        assertNotNull(a.contentHash)

        // Nothing new: a second refresh adds nothing and leaves the stored posts alone.
        clock.now = NOW.plusSeconds(3600)
        assertEquals(report(1, 0, 0), refreshAll())
        assertEquals(2, db.articleDao().count())
        assertEquals(NOW, db.articleDao().getByGuid(feedId, "a")!!.firstSeenAt)

        // One new post: only it is added, first seen at the later time.
        clock.now = NOW.plusSeconds(7200)
        routes["/feed"] = { response(rssOf("c", "a", "b")) }
        assertEquals(1, refreshAll().newArticles)
        assertEquals(3, db.articleDao().count())
        val c = db.articleDao().getByGuid(feedId, "c")!!
        assertEquals(NOW.plusSeconds(7200), c.firstSeenAt)
        // No date in the feed: the fetch time stands in.
        assertEquals(NOW.plusSeconds(7200), c.publishedAt)
        val feed = db.feedDao().getById(feedId)!!
        assertEquals(NOW.plusSeconds(7200), feed.lastSuccessAt)
        assertEquals(NOW.plusSeconds(7200), feed.lastAttemptAt)
    }

    @Test
    fun storesFeedContentAsBlocksAndIndexesIt() = runBlocking<Unit> {
        val feedId = addFeed("/rss")
        // Wrong Content-Type on purpose: the format is read from the document.
        routes["/rss"] = { response(CoreFixtures.bytes("rss.xml"), "Content-Type" to "text/html") }

        assertEquals(5, refreshAll().newArticles)
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

        // The body is searchable by the subscriber.
        val hits = db.articleDao().search(user, "heading", null, null, null, likedOnly = false, savedOnly = false, limit = 10, offset = 0)
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
        refreshAll()
        assertNull(server.takeRequest().headers["If-None-Match"])
        assertEquals("\"v1\"", db.feedDao().getById(feedId)!!.etag)

        clock.now = NOW.plusSeconds(3600)
        routes["/feed"] = { response(code = 304) }
        assertEquals(report(1, 0, 0), refreshAll())
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.headers["If-None-Match"])
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", second.headers["If-Modified-Since"])

        // A 304 keeps the validators, counts as a successful fetch and as healthy.
        val feed = db.feedDao().getById(feedId)!!
        assertEquals("\"v1\"", feed.etag)
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", feed.lastModified)
        assertEquals(NOW.plusSeconds(3600), feed.lastSuccessAt)
        val status = db.healthDao().getStatus(feedId)!!
        assertEquals(304, status.lastHttpStatus)
        assertEquals(0, status.errorStreak)
    }

    @Test
    fun failuresAreCountedRecordedAndDoNotStopOtherFeeds() = runBlocking<Unit> {
        val good = addFeed("/good")
        val missing = addFeed("/missing")
        val broken = addFeed("/broken")
        val notAFeed = addFeed("/page")
        val unreachable = db.feedFor("http://no-such-host.invalid/feed", NOW, user, title = "Dead")
        routes["/good"] = { response(rssOf("a")) }
        routes["/broken"] = { response("oops", 503) }
        routes["/page"] = { response("<html><body>Not a feed</body></html>") }

        assertEquals(report(5, 4, 1), refreshAll())

        val health = db.healthDao()
        assertEquals(0, health.getStatus(good)!!.errorStreak)
        assertEquals(200, health.getStatus(good)!!.lastHttpStatus)
        assertNotNull(health.getStatus(good)!!.lastPostAt)
        assertEquals(404, health.getStatus(missing)!!.lastHttpStatus)
        assertEquals(503, health.getStatus(broken)!!.lastHttpStatus)
        // Answered, but not with a feed: an error that carries the 200.
        assertEquals(200, health.getStatus(notAFeed)!!.lastHttpStatus)
        assertEquals(1, health.getStatus(notAFeed)!!.errorStreak)
        // No answer at all.
        assertNull(health.getStatus(unreachable)!!.lastHttpStatus)
        assertEquals(NOW, health.getStatus(unreachable)!!.errorSince)
        // A failed fetch is an attempt, not a success.
        assertNull(db.feedDao().getById(missing)!!.lastSuccessAt)
        assertEquals(NOW, db.feedDao().getById(missing)!!.lastAttemptAt)

        // The streak grows, then clears when the feed comes back.
        clock.now = NOW.plusSeconds(3600)
        refreshAll()
        assertEquals(2, health.getStatus(broken)!!.errorStreak)
        assertEquals(NOW, health.getStatus(broken)!!.errorSince)
        routes["/broken"] = { response(rssOf("x")) }
        assertEquals(report(1, 0, 1), refreshOne(broken))
        assertEquals(0, health.getStatus(broken)!!.errorStreak)
        assertNull(health.getStatus(broken)!!.errorSince)
    }

    @Test
    fun updatesCadenceAfterRefresh() = runBlocking<Unit> {
        val daily = addFeed("/daily")
        val rare = addFeed("/rare")
        val fresh = addFeed("/fresh")
        val undated = addFeed("/undated")
        val guids = (0 until 12).map { "p$it" }.toTypedArray()
        routes["/daily"] = { response(rssOf(*guids, pubDate = { rfc822(it.toLong()) })) }
        routes["/rare"] = { response(rssOf(*guids, pubDate = { rfc822(it * 20L) })) }
        routes["/fresh"] = { response(rssOf("a", "b", "c", pubDate = { rfc822(it.toLong()) })) }
        routes["/undated"] = { response(rssOf(*guids)) }

        refreshAll()

        val dailyFeed = db.feedDao().getById(daily)!!
        assertEquals(CadenceTier.DAILY, dailyFeed.tier)
        assertEquals(24.0, dailyFeed.medianIntervalHrs!!, 1e-6)
        assertEquals(1.0, dailyFeed.burstiness!!, 1e-6)
        val rareFeed = db.feedDao().getById(rare)!!
        assertEquals(CadenceTier.RARE, rareFeed.tier)
        assertEquals(480.0, rareFeed.medianIntervalHrs!!, 1e-6)
        val freshFeed = db.feedDao().getById(fresh)!!
        assertEquals(CadenceTier.WEEKLY, freshFeed.tier)
        assertNull(freshFeed.medianIntervalHrs)
        assertEquals(CadenceTier.WEEKLY, db.feedDao().getById(undated)!!.tier)
    }

    @Test
    fun permanentRedirectUpdatesTheSharedAddressAndRecordsTheMove() = runBlocking<Unit> {
        val moved = addFeed("/old")
        val temporary = addFeed("/temp")
        routes["/old"] = { response(code = 301, headers = arrayOf("Location" to "/new")) }
        routes["/new"] = { response(rssOf("a"), 200, "ETag" to "\"new\"") }
        routes["/temp"] = { response(code = 302, headers = arrayOf("Location" to "/new")) }

        assertEquals(report(2, 0, 2), refreshAll())

        val feed = db.feedDao().getById(moved)!!
        assertEquals(url("/new"), feed.url)
        assertEquals(net.tgnra.morningissue.model.FeedUrls.normalize(url("/new")), feed.urlKey)
        assertEquals("\"new\"", feed.etag)
        val status = db.healthDao().getStatus(moved)!!
        assertEquals(NOW, status.movedAt)
        assertEquals(url("/old"), status.movedFromUrl)
        assertEquals(301, status.lastHttpStatus)

        // A temporary redirect changes nothing.
        assertEquals(url("/temp"), db.feedDao().getById(temporary)!!.url)
        assertNull(db.healthDao().getStatus(temporary)!!.movedAt)

        // Next time the new address is asked directly.
        refreshOne(moved)
        assertEquals(1, routes.hits("/old"))
        assertEquals(200, db.healthDao().getStatus(moved)!!.lastHttpStatus)
    }

    @Test
    fun redirectOntoAnotherFeedLeavesTheAddressAlone() = runBlocking<Unit> {
        val old = addFeed("/old")
        addFeed("/new")
        routes["/old"] = { response(code = 301, headers = arrayOf("Location" to "/new")) }
        routes["/new"] = { response(rssOf("a")) }

        assertEquals(2, refreshAll().answered)
        assertEquals(url("/old"), db.feedDao().getById(old)!!.url)
    }

    @Test
    fun theFeedTitleFollowsTheDocumentAndTheUsersOwnNameStands() = runBlocking<Unit> {
        val untitled = db.feedFor(url("/a"), NOW, user, title = server.hostName)
        val named = addFeed("/b", title = "Feed b")
        db.subscriptionDao().updateTitle(user, named, "My name for it")
        routes["/a"] = { response(rssOf("a", title = "Real Title")) }
        routes["/b"] = { response(rssOf("b", title = "Real Title")) }

        refreshAll()
        assertEquals("Real Title", db.feedDao().getById(untitled)!!.title)
        assertEquals("Real Title", db.feedDao().getById(named)!!.title)
        assertEquals("My name for it", db.subscriptionDao().detail(user, named)!!.title)
        assertEquals("Real Title", db.subscriptionDao().detail(user, untitled)!!.title)
    }

    @Test
    fun futureDatesAreClampedToNow() = runBlocking<Unit> {
        val feedId = addFeed("/feed")
        routes["/feed"] = { response(rssOf("a", pubDate = { rfc822(-30) })) }
        refreshAll()
        assertEquals(NOW, db.articleDao().getByGuid(feedId, "a")!!.publishedAt)
    }

    @Test
    fun refreshingOneFeedFetchesOnlyItAndUnknownFeedsAreSkipped() = runBlocking<Unit> {
        val one = addFeed("/one")
        addFeed("/two")
        routes["/one"] = { response(rssOf("a")) }
        routes["/two"] = { response(rssOf("b")) }

        assertEquals(report(1, 0, 1), refreshOne(one))
        assertEquals(0, routes.hits("/two"))
        assertEquals(report(0, 0, 0), refreshOne(9999))
    }

    @Test
    fun nothingToRefreshIsAnEmptyReportThatReachedTheFeeds() = runBlocking<Unit> {
        val empty = refreshAll()
        assertEquals(report(0, 0, 0), empty)
        assertTrue(empty.reachedFeeds)
    }

    @Test
    fun teaserPostsAreFetchedFromTheirPageOnRefresh() = runBlocking<Unit> {
        val fetching = DefaultFeedRefresher(
            db, FeedFetcher(OkHttpClient()), clock, scope,
            fullText = DefaultFullTextService(db, FeedFetcher(OkHttpClient()), clock),
        )
        val feedId = addFeed("/teasers")
        val page = url("/article")
        routes["/article"] = { response(CoreFixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
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

        assertEquals(2, refreshAll(fetching).newArticles)

        val teaser = db.articleDao().getByGuid(feedId, "t")!!
        val content = db.articleDao().getContent(teaser.id)!!
        assertEquals(ContentSource.FETCHED, content.source)
        assertTrue("The ferry leaves the harbour at six in the morning" in ContentBlocks.plainText(ContentBlocks.decode(content.blocksJson)))
        assertTrue(db.articleDao().getById(teaser.id)!!.wordCount > 200)
        val note = db.articleDao().getByGuid(feedId, "n")!!
        assertEquals(ContentSource.FEED, db.articleDao().getContent(note.id)!!.source)
        assertEquals(1, routes.hits("/article"))
    }

    @Test
    fun afterABatchWithNewPostsTheUpkeepRuns() = runBlocking<Unit> {
        var runs = 0
        val upkept = DefaultFeedRefresher(db, FeedFetcher(OkHttpClient()), clock, scope, afterBatch = { runs++ })
        addFeed("/feed")
        routes["/feed"] = { response(rssOf("a")) }
        refreshAll(upkept)
        assertEquals(1, runs)
        // Nothing new: nothing to upkeep.
        refreshAll(upkept)
        assertEquals(1, runs)
    }
}

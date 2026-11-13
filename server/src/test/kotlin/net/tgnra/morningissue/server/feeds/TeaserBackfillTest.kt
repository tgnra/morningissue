package net.tgnra.morningissue.server.feeds

import java.time.Duration
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Ported from the app's `TeaserBackfillTest`: "subscribed" means someone subscribes. */
class TeaserBackfillTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private lateinit var db: ServerDatabase

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        routes["/article"] = { response(CoreFixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun post(feedId: Long, guid: String, ending: String, daysAgo: Long = 1): Long {
        val blocks = listOf(ContentBlock.Paragraph(RichText("A ferry trip across the bay. $ending")))
        val at = NOW.minus(Duration.ofDays(daysAgo))
        val entity = ArticleEntity(feedId = feedId, guid = guid, url = server.url("/article").toString(), title = "The Long Way Round", summary = "A ferry trip.", publishedAt = at, firstSeenAt = at)
        return db.articleDao().insertNew(entity, ContentBlocks.encode(blocks), ContentBlocks.plainText(blocks))!!
    }

    private suspend fun source(id: Long) = db.articleDao().getContentSource(id)

    @Test
    fun fetchesRecentTeasersOfSubscribedFeedsOnly() = runBlocking<Unit> {
        val user = db.newUser("tania")
        val feed = db.feedFor("https://example.com/feed", NOW, user, title = "Cafe")
        val other = db.feedFor("https://other.example/feed", NOW, user, title = "Other", status = FeedStatus.DISCOVERED)
        val teaser = post(feed, "t", "Read in full on Cafe.")
        val complete = post(feed, "c", "That was all.")
        val old = post(feed, "o", "Read in full on Cafe.", daysAgo = 60)
        val unsubscribed = post(other, "u", "Continue reading →")

        val stored = TeaserBackfill(db, DefaultFullTextService(db, FeedFetcher(OkHttpClient()), clock), clock).run()

        assertEquals(1, stored)
        assertEquals(ContentSource.FETCHED, source(teaser))
        assertEquals(ContentSource.FEED, source(complete))
        assertEquals(ContentSource.FEED, source(old))
        assertEquals(ContentSource.FEED, source(unsubscribed))
        assertEquals(1, routes.hits("/article"))
    }
}

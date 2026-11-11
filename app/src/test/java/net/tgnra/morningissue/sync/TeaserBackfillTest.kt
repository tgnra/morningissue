package net.tgnra.morningissue.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.RichText
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeaserBackfillTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private lateinit var db: MorningIssueDatabase

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        routes["/article"] = { response(Fixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
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
        val feed = db.feedDao().insert(FeedEntity(url = "https://example.com/feed", title = "Cafe", addedAt = NOW))
        val other = db.feedDao().insert(FeedEntity(url = "https://other.example/feed", title = "Other", addedAt = NOW, status = FeedStatus.DISCOVERED))
        val teaser = post(feed, "t", "Read in full on Cafe.")
        val complete = post(feed, "c", "That was all.")
        val old = post(feed, "o", "Read in full on Cafe.", daysAgo = 60)
        val unsubscribed = post(other, "u", "Continue reading →")

        val stored = TeaserBackfill(db, DefaultFullTextFetcher(db, FeedFetcher(OkHttpClient()), clock), clock).run()

        assertEquals(1, stored)
        assertEquals(ContentSource.FETCHED, source(teaser))
        assertEquals(ContentSource.FEED, source(complete))
        assertEquals(ContentSource.FEED, source(old))
        assertEquals(ContentSource.FEED, source(unsubscribed))
        assertEquals(1, routes.hits("/article"))
    }
}

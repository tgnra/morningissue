package net.tgnra.morningissue.server.feeds

import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.ArticleFeedStatus
import net.tgnra.morningissue.api.FullTextState
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchWhen
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Articles, shelves and search, each restricted to what the user can see. */
class ArticleSearchTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private lateinit var db: ServerDatabase
    private lateinit var articles: DefaultArticleService
    private lateinit var search: DefaultSearchService
    private var ann = 0L
    private var bob = 0L
    private var annFeed = 0L
    private var bobFeed = 0L
    private var shared = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        ann = db.newUser("ann")
        bob = db.newUser("bob")
        articles = DefaultArticleService(db, DefaultFullTextService(db, FeedFetcher(OkHttpClient()), clock))
        search = DefaultSearchService(db, clock)
        annFeed = db.feedFor("https://ann.example/feed", NOW, ann, title = "Ann's paper")
        bobFeed = db.feedFor("https://bob.example/feed", NOW, bob, title = "Bob's paper")
        shared = db.feedFor("https://shared.example/feed", NOW, ann, bob, title = "Shared")
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun post(feedId: Long, guid: String, text: String, daysAgo: Long = 1, url: String? = null): Long {
        val blocks = listOf(ContentBlock.Paragraph(RichText(text)))
        val at = NOW.minus(Duration.ofDays(daysAgo))
        val entity = ArticleEntity(feedId = feedId, guid = guid, url = url, title = "Post $guid", publishedAt = at, firstSeenAt = at)
        return db.articleDao().insertNew(entity, ContentBlocks.encode(blocks), ContentBlocks.plainText(blocks))!!
    }

    private fun failure(block: suspend () -> Unit): ApiFailure =
        assertThrows(ApiFailure::class.java) { runBlocking { block() } }

    private suspend fun hits(userId: Long, query: SearchQuery) = search.search(userId, query).results.map { it.article.articleId }

    @Test
    fun searchFindsOnlyTheUsersOwnArticles() = runBlocking<Unit> {
        val mine = post(annFeed, "a", "The harbour ferry was late again")
        val theirs = post(bobFeed, "b", "The harbour ferry was early for once")
        val common = post(shared, "c", "A harbour seal was seen near the ferry")

        assertEquals(setOf(mine, common), hits(ann, SearchQuery("harbour ferry")).toSet())
        assertEquals(setOf(theirs, common), hits(bob, SearchQuery("harbour ferry")).toSet())
        val result = search.search(ann, SearchQuery("seal")).results.single()
        assertEquals(common, result.article.articleId)
        assertTrue(result.excerpt!!.contains("seal"))
        assertTrue(hits(ann, SearchQuery("   ")).isEmpty())

        // Narrowed to a feed the user cannot see: nothing, not Bob's posts.
        assertTrue(hits(ann, SearchQuery("harbour", feedId = bobFeed)).isEmpty())
        assertEquals(listOf(mine), hits(ann, SearchQuery("harbour", feedId = annFeed)))
    }

    @Test
    fun searchFiltersBySavedLikedTagAndTime() = runBlocking<Unit> {
        val recent = post(annFeed, "r", "Notes on the harbour", daysAgo = 2)
        val older = post(annFeed, "o", "More notes on the harbour", daysAgo = 20)
        val oldest = post(shared, "x", "Old notes on the harbour", daysAgo = 200)
        db.engage(ann, older, savedAt = NOW)
        // Bob's Like is not Ann's.
        db.engage(bob, oldest, likedAt = NOW)
        db.engage(ann, oldest, likedAt = NOW)
        db.subscriptionDao().setFeedTags(ann, shared, listOf("Archive"))
        val tag = db.subscriptionDao().tagByName(ann, "Archive")!!

        assertEquals(listOf(recent), hits(ann, SearchQuery("harbour", `when` = SearchWhen.WEEK)))
        assertEquals(setOf(recent, older), hits(ann, SearchQuery("harbour", `when` = SearchWhen.MONTH)).toSet())
        assertEquals(3, hits(ann, SearchQuery("harbour", `when` = SearchWhen.YEAR)).size)
        assertEquals(listOf(older), hits(ann, SearchQuery("harbour", saved = true)))
        assertEquals(listOf(oldest), hits(ann, SearchQuery("harbour", liked = true)))
        assertTrue(hits(bob, SearchQuery("harbour", saved = true)).isEmpty())
        assertEquals(listOf(oldest), hits(ann, SearchQuery("harbour", tagId = tag.id)))
        // Paging.
        assertEquals(2, hits(ann, SearchQuery("harbour", limit = 2)).size)
        assertEquals(1, hits(ann, SearchQuery("harbour", limit = 2, offset = 2)).size)
    }

    @Test
    fun aSavedArticleStaysFindableAfterUnsubscribingButNotForOthers() = runBlocking<Unit> {
        val kept = post(annFeed, "k", "A recipe for harbour stew")
        db.engage(ann, kept, savedAt = NOW)
        // Ann's subscription goes, the engagement row stays (as after an archive import).
        db.subscriptionDao().delete(ann, annFeed)

        assertEquals(listOf(kept), hits(ann, SearchQuery("stew")))
        assertEquals(ArticleFeedStatus.NONE, articles.article(ann, kept).feedStatus)
        assertTrue(hits(bob, SearchQuery("stew")).isEmpty())
    }

    @Test
    fun anArticleIsVisibleBySubscriptionEngagementOrEdition() = runBlocking<Unit> {
        val mine = post(annFeed, "a", "Mine")
        val theirs = post(bobFeed, "b", "Theirs")
        val printed = post(bobFeed, "p", "Printed for Ann")

        val response = articles.article(ann, mine)
        assertEquals("Post a", response.article.title)
        assertEquals(annFeed, response.article.feedId)
        assertEquals(ArticleFeedStatus.SUBSCRIBED, response.feedStatus)
        assertNull(response.fullText)
        assertEquals(ApiError.NOT_FOUND, failure { articles.article(ann, theirs) }.code)
        assertEquals(ApiError.NOT_FOUND, failure { articles.article(ann, 99_999) }.code)
        assertEquals(ApiError.NOT_FOUND, failure { articles.fetchFullText(ann, theirs) }.code)

        db.edition(ann, NOW, 1, listOf(printed))
        assertEquals(ArticleFeedStatus.NONE, articles.article(ann, printed).feedStatus)

        val looked = db.feedFor("https://looked.example/feed", NOW, ann, status = FeedStatus.DISCOVERED)
        assertEquals(ArticleFeedStatus.DISCOVERED, articles.article(ann, post(looked, "l", "Looked")).feedStatus)
    }

    @Test
    fun shelvesAreTheUsersOwn() = runBlocking<Unit> {
        val a = post(shared, "a", "One")
        val b = post(shared, "b", "Two")
        db.engage(ann, a, savedAt = NOW.minusSeconds(60))
        db.engage(ann, b, savedAt = NOW, likedAt = NOW)
        db.engage(bob, a, likedAt = NOW)

        assertEquals(listOf(b, a), articles.shelf(ann, ApiQuery.SHELF_SAVED, null, 50).articles.map { it.articleId })
        assertEquals(listOf(b), articles.shelf(ann, ApiQuery.SHELF_LIKED, null, 50).articles.map { it.articleId })
        assertEquals(listOf(a), articles.shelf(bob, ApiQuery.SHELF_LIKED, null, 50).articles.map { it.articleId })
        assertTrue(articles.shelf(bob, ApiQuery.SHELF_SAVED, null, 50).articles.isEmpty())
        assertEquals(listOf(a), articles.shelf(ann, ApiQuery.SHELF_SAVED, NOW.minusSeconds(1), 50).articles.map { it.articleId })
        assertEquals(ApiError.BAD_REQUEST, failure { articles.shelf(ann, "read", null, 50) }.code)
    }

    @Test
    fun fullTextIsFetchedOnceAndSharedByEveryReader() = runBlocking<Unit> {
        routes["/article"] = { response(CoreFixtures.text("article.html"), 200, "Content-Type" to "text/html; charset=utf-8") }
        val id = post(shared, "t", "A ferry trip. Read in full on Cafe.", url = server.url("/article").toString())

        assertEquals(FullTextState.FETCHED, articles.fetchFullText(ann, id).fullText)
        assertEquals(FullTextState.FETCHED, articles.fetchFullText(bob, id).fullText)
        assertEquals(1, routes.hits("/article"))
        assertTrue(articles.article(bob, id).article.blocks.jsonArray.size > 1)
    }
}

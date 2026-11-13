package net.tgnra.morningissue.server.feeds

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SignalDao
import net.tgnra.morningissue.server.feeds.signals.TraitsBackfill
import net.tgnra.morningissue.server.service.RefreshReport
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.EnclosureKind
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ported from the app's `FeedSyncerSignalsTest`. What a refresh stores for the within-feed ranking: categories and traits, for new posts and for posts held since version 1. */
class FeedRefresherSignalsTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: ServerDatabase
    private lateinit var dao: SignalDao
    private lateinit var syncer: DefaultFeedRefresher
    private var user = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        user = db.newUser("tania")
        dao = db.signalDao()
        syncer = DefaultFeedRefresher(db, FeedFetcher(OkHttpClient()), clock, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun syncAll() = syncer.refresh(db.feedDao().subscribedFeedIds(), Duration.ZERO)

    private suspend fun addFeed(): Long =
        db.feedFor(url("/feed"), clock.now, user, title = "A blog", siteUrl = url("/"))

    private fun item(guid: String, title: String = "Post $guid", body: String = "Summary of $guid", extra: String = "") =
        "<item><title>$title</title><guid isPermaLink=\"false\">$guid</guid><link>${url("/posts/$guid")}</link>" +
            "<description><![CDATA[$body]]></description>$extra</item>"

    private fun feedOf(vararg items: String) =
        "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>A blog</title><link>${url("/")}</link>${items.joinToString("")}</channel></rss>"

    private fun serve(vararg items: String) {
        val document = feedOf(*items)
        routes["/feed"] = { response(document) }
    }

    /** A post as version 1 stored it: an article row and nothing else. */
    private suspend fun storedBefore(feedId: Long, guid: String): Long = db.articleDao().insertNew(
        ArticleEntity(
            feedId = feedId, guid = guid, url = url("/posts/$guid"), title = "Post $guid", summary = "Summary of $guid",
            publishedAt = NOW.minusSeconds(86_400), firstSeenAt = NOW.minusSeconds(86_400),
        ),
    )!!

    private val episode = "<category>Podcast</category><category>Tech</category><enclosure url=\"https://cdn.example/1.mp3\" type=\"audio/mpeg\"/>"

    @Test
    fun newPostsAreStoredWithTheirCategoriesAndTraits() = runBlocking<Unit> {
        val feedId = addFeed()
        serve(
            item("a", title = "Podcast: Episode 1", extra = episode),
            item("b", title = "Links: a good read", body = "<p>See <a href=\"https://elsewhere.example/x\">this</a> and <code>that</code>.</p>"),
            item("c"),
        )
        assertEquals(3, syncAll().newArticles)

        val a = db.articleDao().getByGuid(feedId, "a")!!.id
        assertEquals(
            listOf(ArticleCategoryEntity(a, "podcast", "Podcast"), ArticleCategoryEntity(a, "tech", "Tech")),
            dao.categoriesOf(listOf(a)),
        )
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.AUDIO, isLinkPost = false, titlePrefix = "podcast"), dao.getTraits(a)!!.toTraits())
        assertEquals(ArticleTraitRules.VERSION, dao.getTraits(a)!!.traitsVersion)

        val b = db.articleDao().getByGuid(feedId, "b")!!.id
        assertEquals(ArticleTraits(1, 0, true, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "links"), dao.getTraits(b)!!.toTraits())
        assertTrue(dao.categoriesOf(listOf(b)).isEmpty())

        // A plain post still gets its row, so nothing is left for the backfill.
        val c = db.articleDao().getByGuid(feedId, "c")!!.id
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = false, titlePrefix = null), dao.getTraits(c)!!.toTraits())
        assertEquals(0, TraitsBackfill(db).run())
    }

    @Test
    fun postsHeldSinceBeforeTheTablesExistedAreCompletedWhenTheirFeedIsFetchedAgain() = runBlocking<Unit> {
        val feedId = addFeed()
        val a = storedBefore(feedId, "a")
        val gone = storedBefore(feedId, "no-longer-in-the-feed")
        serve(item("a", title = "Podcast: Episode 1", extra = episode), item("new"))

        // Only the new post counts as new, and the held post is otherwise untouched.
        assertEquals(RefreshReport(attempted = 1, answered = 1, newArticles = 1, finishedInTime = true), syncAll())
        val held = db.articleDao().getById(a)!!
        assertEquals("Post a", held.title)
        assertEquals(NOW.minusSeconds(86_400), held.firstSeenAt)

        assertEquals(listOf("podcast", "tech"), dao.categoriesOf(listOf(a)).map { it.key })
        assertEquals(EnclosureKind.AUDIO, dao.getTraits(a)!!.enclosure)
        assertEquals("podcast", dao.getTraits(a)!!.titlePrefix)
        // A post the feed no longer lists is left to the backfill.
        assertNull(dao.getTraits(gone))
        assertEquals(1, TraitsBackfill(db).run())
    }

    @Test
    fun traitsTheBackfillComputedFirstLearnTheirEnclosureFromTheFeed() = runBlocking<Unit> {
        val feedId = addFeed()
        val a = storedBefore(feedId, "a")
        assertEquals(1, TraitsBackfill(db).run())
        assertEquals(EnclosureKind.NONE, dao.getTraits(a)!!.enclosure)

        serve(item("a", extra = episode))
        assertEquals(0, syncAll().newArticles)
        assertEquals(EnclosureKind.AUDIO, dao.getTraits(a)!!.enclosure)
        assertEquals(2, dao.categoryCount())
    }

    @Test
    fun aRowLeftPendingByAnArchiveImportIsComputedFromTheFeed() = runBlocking<Unit> {
        val feedId = addFeed()
        val a = storedBefore(feedId, "a")
        dao.insertPendingEnclosure(a, EnclosureKind.OTHER)
        serve(item("a", title = "Links: one", body = "<p><a href=\"https://elsewhere.example/x\">This</a>.</p>", extra = episode))

        syncAll()
        val traits = dao.getTraits(a)!!
        assertEquals(ArticleTraitRules.VERSION, traits.traitsVersion)
        assertEquals(ArticleTraits(1, 0, false, EnclosureKind.AUDIO, isLinkPost = true, titlePrefix = "links"), traits.toTraits())
    }

    @Test
    fun aPostThatIsCompleteIsLeftAsItIs() = runBlocking<Unit> {
        val feedId = addFeed()
        serve(item("a", title = "Podcast: Episode 1", extra = episode))
        syncAll()
        val a = db.articleDao().getByGuid(feedId, "a")!!.id
        val categories = dao.categoriesOf(listOf(a))
        val traits = dao.getTraits(a)

        // The feed later renames the post, refiles it and drops the audio: what was stored stands.
        clock.now = NOW.plusSeconds(3600)
        serve(item("a", title = "Renamed", extra = "<category>Other</category>"))
        assertEquals(0, syncAll().newArticles)
        assertEquals(categories, dao.categoriesOf(listOf(a)))
        assertEquals(traits, dao.getTraits(a))
    }

    @Test
    fun aFeedWithMorePostsThanOneQueryCanBindIsStoredOnceAndCompletedOnce() = runBlocking<Unit> {
        val feedId = addFeed()
        val guids = (1..(SignalDao.MAX_IDS + 30)).map { "p$it" }
        serve(*guids.map { item(it, extra = "<category>All</category>") }.toTypedArray())

        assertEquals(guids.size, syncAll().newArticles)
        assertEquals(0, syncAll().newArticles)
        assertEquals(guids.size, db.articleDao().count())
        assertEquals(guids.size, dao.categoryCount())
        assertEquals(guids.size, dao.traitsCount())
        assertEquals(guids.size, db.articleDao().countForFeedSince(feedId, NOW.minusSeconds(1)))
    }
}

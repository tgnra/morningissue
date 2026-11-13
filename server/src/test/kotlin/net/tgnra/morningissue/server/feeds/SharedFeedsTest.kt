package net.tgnra.morningissue.server.feeds

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.NOW
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import net.tgnra.morningissue.sync.rssOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Feeds shared between users: one fetch, global posts, per-user engagement; the refresher's dedupe and `maxAge`. */
class SharedFeedsTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: ServerDatabase
    private lateinit var refresher: DefaultFeedRefresher
    private lateinit var subscriptions: Subscriptions
    private var ann = 0L
    private var bob = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        ann = db.newUser("ann")
        bob = db.newUser("bob")
        refresher = DefaultFeedRefresher(db, FeedFetcher(OkHttpClient()), clock, scope)
        subscriptions = Subscriptions(db, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun twoSubscribersShareOneFeedOneFetchAndGlobalPosts() = runBlocking<Unit> {
        routes["/feed"] = { response(rssOf("a", "b")) }
        val first = subscriptions.add(ann, url("/feed"), title = "Ann's name")
        // The same feed, written a little differently, is the same global feed.
        val second = subscriptions.add(bob, url("/feed").replace("http://", "HTTP://"))
        assertEquals(Subscriptions.Added.CREATED, first.added)
        assertEquals(Subscriptions.Added.CREATED, second.added)
        assertEquals(first.feedId, second.feedId)
        assertEquals(1L, db.long("SELECT COUNT(*) FROM feed"))

        val report = refresher.refresh(listOf(first.feedId, second.feedId), Duration.ZERO)
        assertEquals(1, report.attempted)
        assertEquals(2, report.newArticles)
        assertEquals(1, routes.hits("/feed"))
        assertEquals(2, db.articleDao().count())

        // Both see the posts; each sees their own name for the feed.
        val forAnn = db.articleDao().forFeed(ann, first.feedId, END_OF_TIME, 10)
        val forBob = db.articleDao().forFeed(bob, first.feedId, END_OF_TIME, 10)
        assertEquals(forAnn.map { it.article.id }, forBob.map { it.article.id })
        assertEquals("Ann's name", forAnn.first().feedTitle)
        assertEquals("Test feed", forBob.first().feedTitle)
        assertTrue(refresher.reachedFeedsOf(ann))
        assertTrue(refresher.reachedFeedsOf(bob))
    }

    @Test
    fun engagementIsPerUser() = runBlocking<Unit> {
        routes["/feed"] = { response(rssOf("a")) }
        val feedId = db.feedFor(url("/feed"), NOW, ann, bob)
        refresher.refresh(listOf(feedId), Duration.ZERO)
        val article = db.articleDao().getByGuid(feedId, "a")!!

        db.engage(ann, article.id, openedAt = NOW, savedAt = NOW, likedAt = NOW)

        val annRow = db.articleDao().row(ann, article.id)!!
        val bobRow = db.articleDao().row(bob, article.id)!!
        assertEquals(NOW, annRow.savedAt)
        assertEquals(NOW, annRow.likedAt)
        assertNull(bobRow.savedAt)
        assertNull(bobRow.openedAt)
        assertEquals(listOf(article.id), db.articleDao().saved(ann, END_OF_TIME, 10).map { it.article.id })
        assertTrue(db.articleDao().saved(bob, END_OF_TIME, 10).isEmpty())
        assertTrue(db.articleDao().liked(bob, END_OF_TIME, 10).isEmpty())
    }

    @Test
    fun concurrentRefreshesOfOneFeedMakeOneRequest() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        routes["/slow"] = {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            response(rssOf("a"))
        }
        val feedId = db.feedFor(url("/slow"), NOW, ann, bob)

        val first = async(Dispatchers.Default) { refresher.refresh(listOf(feedId), Duration.ZERO) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // A second caller, even one that would skip a recently tried feed, joins the fetch in flight.
        val second = async(Dispatchers.Default) { refresher.refresh(listOf(feedId), Duration.ofHours(1)) }
        val third = async(Dispatchers.Default) { refresher.refresh(listOf(feedId), Duration.ZERO) }
        delay(200)
        release.countDown()

        val reports = listOf(first.await(), second.await(), third.await())
        assertEquals(1, server.requestCount)
        assertEquals(1, routes.hits("/slow"))
        reports.forEach {
            assertEquals(1, it.attempted)
            assertEquals(1, it.answered)
        }
        assertEquals(1, db.articleDao().count())
    }

    @Test
    fun maxAgeSkipsFeedsTriedRecently() = runBlocking<Unit> {
        routes["/feed"] = { response(rssOf("a")) }
        routes["/broken"] = { response("down", 503) }
        val feedId = db.feedFor(url("/feed"), NOW, ann)
        val broken = db.feedFor(url("/broken"), NOW, ann)
        refresher.refresh(listOf(feedId, broken), Duration.ZERO)
        assertEquals(1, routes.hits("/feed"))

        // Tried 30 minutes ago: within an hour's maxAge, skipped (a failed attempt counts too).
        clock.now = NOW.plus(Duration.ofMinutes(30))
        val skipped = refresher.refresh(listOf(feedId, broken), Duration.ofHours(1))
        assertEquals(0, skipped.attempted)
        assertTrue(skipped.reachedFeeds)
        assertEquals(1, routes.hits("/feed"))
        assertEquals(1, routes.hits("/broken"))

        // A shorter maxAge, or none, fetches again.
        val fetched = refresher.refresh(listOf(feedId, broken), Duration.ofMinutes(10))
        assertEquals(2, fetched.attempted)
        assertEquals(2, routes.hits("/feed"))
        assertEquals(NOW.plus(Duration.ofMinutes(30)), db.feedDao().getById(feedId)!!.lastAttemptAt)
        refresher.refresh(listOf(feedId), Duration.ZERO)
        assertEquals(3, routes.hits("/feed"))
    }

    @Test
    fun aFeedOnlyDiscoveredIsNotFetched() = runBlocking<Unit> {
        routes["/feed"] = { response(rssOf("a")) }
        val feedId = db.feedFor(url("/feed"), NOW, ann, status = FeedStatus.DISCOVERED)
        assertEquals(0, refresher.refresh(listOf(feedId), Duration.ZERO).attempted)
        assertEquals(0, routes.hits("/feed"))

        // Once someone subscribes, it is.
        db.subscribe(bob, feedId, NOW)
        assertEquals(1, refresher.refresh(listOf(feedId), Duration.ZERO).attempted)
    }

    @Test
    fun reachedFeedsIsPerUserAndFollowsTheLatestCoveringRefresh() = runBlocking<Unit> {
        routes["/a"] = { response(rssOf("a")) }
        routes["/b"] = { response("down", 503) }
        val a = db.feedFor(url("/a"), NOW, ann, bob)
        val b = db.feedFor(url("/b"), NOW, bob)
        assertFalse(refresher.reachedFeedsOf(ann))

        // Only feed a: covers Ann (all her feeds), not Bob.
        refresher.refresh(listOf(a), Duration.ZERO)
        assertTrue(refresher.reachedFeedsOf(ann))
        assertFalse(refresher.reachedFeedsOf(bob))

        // Only b, which fails: covers nobody fully, so Ann keeps her result.
        refresher.refresh(listOf(b), Duration.ZERO)
        assertTrue(refresher.reachedFeedsOf(ann))
        assertFalse(refresher.reachedFeedsOf(bob))

        // Both: one answered, so both reached.
        clock.now = NOW.plusSeconds(60)
        refresher.refresh(listOf(a, b), Duration.ZERO)
        assertTrue(refresher.reachedFeedsOf(bob))
        assertNotNull(db.feedDao().getById(b)!!.lastAttemptAt)
    }

    @Test
    fun aDeadlineStopsWaitingButTheFetchFinishes() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        routes["/slow"] = {
            release.await(10, TimeUnit.SECONDS)
            response(rssOf("a"))
        }
        val feedId = db.feedFor(url("/slow"), NOW, ann)
        val deadline = java.time.Instant.now().plusMillis(200)
        val realClockRefresher = DefaultFeedRefresher(db, FeedFetcher(OkHttpClient()), java.time.Clock.systemUTC(), scope)

        val report = realClockRefresher.refresh(listOf(feedId), Duration.ZERO, deadline)
        assertFalse(report.finishedInTime)
        assertFalse(report.reachedFeeds)
        assertFalse(realClockRefresher.reachedFeedsOf(ann))

        release.countDown()
        // The fetch carried on in the background and stores its post.
        repeat(100) {
            if (db.articleDao().count() == 1) return@runBlocking
            delay(50)
        }
        error("the fetch never finished")
    }
}

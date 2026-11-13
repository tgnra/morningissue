package net.tgnra.morningissue.server.feeds

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromJsonElement
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.OpmlReportDto
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.sync.DefaultFeedDiscovery
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedRepairer
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Adding, changing and removing subscriptions through [DefaultFeedService]; unsubscribe clean-up and global GC. */
class FeedServiceTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = InlineJobs()
    private lateinit var db: ServerDatabase
    private lateinit var refresher: DefaultFeedRefresher
    private lateinit var service: DefaultFeedService
    private val changed = mutableListOf<Long>()
    private var ann = 0L
    private var bob = 0L

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = ServerDatabase.inMemory()
        ann = db.newUser("ann")
        bob = db.newUser("bob")
        val fetcher = FeedFetcher(OkHttpClient())
        val discovery = DefaultFeedDiscovery(fetcher, clock)
        refresher = DefaultFeedRefresher(db, fetcher, clock, scope)
        val subscriptions = Subscriptions(db, clock, onChanged = { changed += it })
        service = DefaultFeedService(
            db, subscriptions, refresher, FeedHealthChecker(db, FeedRepairer(fetcher, discovery, clock), clock),
            discovery, OpmlTransfer(db, subscriptions, clock), jobs, clock,
        )
        routes["/feed"] = { response(rssOf("a", "b", title = "The Feed")) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun failure(block: suspend () -> Unit): ApiFailure =
        assertThrows(ApiFailure::class.java) { runBlocking { block() } }

    @Test
    fun addingFetchesANewFeedOnceAndASecondSubscriberReusesIt() = runBlocking<Unit> {
        val first = service.add(ann, AddFeedRequest(url("/feed"), tags = listOf("News")))
        assertTrue(first.created)
        assertEquals("The Feed", first.feed.title)
        assertEquals(listOf("News"), first.feed.tags)
        assertNotNull(first.feed.lastSyncAt)
        assertEquals(2, db.articleDao().count())
        assertEquals(1, routes.hits("/feed"))

        // Bob's add of the same feed (written differently) reuses it and does not fetch it again.
        val second = service.add(bob, AddFeedRequest(url("/feed").replace("http://", "HTTP://"), title = "Bob's"))
        assertTrue(second.created)
        assertEquals(first.feed.id, second.feed.id)
        assertEquals("Bob's", second.feed.title)
        assertTrue(second.feed.tags.isEmpty())
        assertEquals(1, routes.hits("/feed"))
        assertEquals(2, service.articles(bob, second.feed.id, null, 50).articles.size)

        // Adding again is a 200 that changes nothing.
        val again = service.add(ann, AddFeedRequest(url("/feed"), title = "Ignored", tags = listOf("Other")))
        assertFalse(again.created)
        assertEquals("The Feed", again.feed.title)
        assertEquals(listOf("News"), again.feed.tags)
        assertEquals(listOf(ann, bob), changed)
    }

    @Test
    fun addingADiscoveredFeedPromotesItAsA200() = runBlocking<Unit> {
        val feedId = db.feedFor(url("/feed"), NOW, ann, status = FeedStatus.DISCOVERED)
        val outcome = service.add(ann, AddFeedRequest(url("/feed")))
        assertFalse(outcome.created)
        assertEquals(feedId, outcome.feed.id)
        assertEquals(FeedStatus.SUBSCRIBED, outcome.feed.status)
        assertEquals(DiscoveryVerdict.SUBSCRIBED, db.discoveryDao().verdict(ann, FeedUrls.normalize(url("/feed")))!!.verdict)
        // It had never been fetched (discovered feeds are not): the add fetches it.
        assertEquals(1, routes.hits("/feed"))
    }

    @Test
    fun aFeedThatDoesNotAnswerIsStillAdded() = runBlocking<Unit> {
        routes["/down"] = { response("no", 503) }
        val outcome = service.add(ann, AddFeedRequest(url("/down")))
        assertTrue(outcome.created)
        assertEquals(server.hostName, outcome.feed.title)
        assertNull(outcome.feed.lastSyncAt)
        assertEquals(400, failure { service.add(ann, AddFeedRequest("  ")) }.status)
    }

    @Test
    fun feedsListsTheUsersOwnSubscriptionsAndTags() = runBlocking<Unit> {
        service.add(ann, AddFeedRequest(url("/feed"), tags = listOf("Tech", "News")))
        db.feedFor(url("/other"), NOW, bob)
        db.feedFor(url("/looked"), NOW, ann, status = FeedStatus.DISCOVERED)

        val feeds = service.feeds(ann)
        assertEquals(setOf(url("/feed"), url("/looked")), feeds.feeds.map { it.url }.toSet())
        assertEquals(listOf("News", "Tech"), feeds.feeds.single { it.url == url("/feed") }.tags)
        assertEquals(setOf("News", "Tech"), feeds.tags.map { it.name }.toSet())
        assertTrue(service.feeds(bob).tags.isEmpty())
        assertEquals(1, service.feeds(bob).feeds.size)
    }

    @Test
    fun patchChangesOnlyTheUsersOwnSubscription() = runBlocking<Unit> {
        val feedId = service.add(ann, AddFeedRequest(url("/feed"), tags = listOf("Old"))).feed.id
        service.add(bob, AddFeedRequest(url("/feed")))
        val until = NOW.plus(Duration.ofDays(3))

        val patched = service.patch(ann, feedId, FeedPatch(title = " Mine ", sliderStep = SliderStep.MORE, tags = listOf("New"), muteUntil = Patch.Set(until)))
        assertEquals("Mine", patched.title)
        assertEquals(SliderStep.MORE, patched.sliderStep)
        assertEquals(listOf("New"), patched.tags)
        assertEquals(until, patched.muteUntil)
        // The unused tag went with it.
        assertEquals(listOf("New"), service.feeds(ann).tags.map { it.name })

        // Absent fields stay; null unmutes.
        val unmuted = service.patch(ann, feedId, FeedPatch(muteUntil = Patch.Set(null)))
        assertNull(unmuted.muteUntil)
        assertEquals("Mine", unmuted.title)
        assertEquals(SliderStep.MORE, unmuted.sliderStep)

        val bobs = service.feeds(bob).feeds.single()
        assertEquals("The Feed", bobs.title)
        assertEquals(SliderStep.AUTO, bobs.sliderStep)
        assertEquals("The Feed", db.feedDao().getById(feedId)!!.title)

        assertEquals(ApiError.NOT_FOUND, failure { service.patch(ann, 9999, FeedPatch(title = "x")) }.code)
    }

    @Test
    fun unsubscribingRemovesTheUsersRowsAndGarbageCollectionTheOrphans() = runBlocking<Unit> {
        val feedId = service.add(ann, AddFeedRequest(url("/feed"), tags = listOf("News"))).feed.id
        service.add(bob, AddFeedRequest(url("/feed")))
        val a = db.articleDao().getByGuid(feedId, "a")!!.id
        val b = db.articleDao().getByGuid(feedId, "b")!!.id
        db.engage(ann, a, openedAt = NOW, savedAt = NOW)
        db.engage(bob, b, openedAt = NOW)
        val annEdition = db.edition(ann, NOW, 1, listOf(a, b))
        db.exec("INSERT INTO feed_flag (user_id, feed_id, last_flag) VALUES ($ann, $feedId, 'NOISY')")

        service.unsubscribe(ann, feedId)

        assertNull(db.subscriptionDao().get(ann, feedId))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM engagement WHERE user_id = $ann"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM edition_item WHERE editionId = $annEdition"))
        assertNull(db.healthDao().getFlag(ann, feedId))
        assertTrue(db.subscriptionDao().tags(ann).isEmpty())
        assertEquals(DiscoveryVerdict.DROPPED, db.discoveryDao().verdict(ann, FeedUrls.normalize(url("/feed")))!!.verdict)
        // Bob's rows and the shared feed and posts are untouched.
        assertNotNull(db.subscriptionDao().get(bob, feedId))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM engagement WHERE user_id = $bob"))
        assertEquals(FeedGarbageCollector.Result(0, 0), FeedGarbageCollector(db).run())
        assertEquals(2, db.articleDao().count())
        // Ann can no longer see them.
        assertEquals(ApiError.NOT_FOUND, failure { service.articles(ann, feedId, null, 10) }.code)
        assertFalse(db.articleDao().isVisible(ann, a))

        // Bob saves one, has the other printed, then leaves too: the feed stays while they are referenced.
        db.engage(bob, a, savedAt = NOW)
        service.unsubscribe(bob, feedId)
        val keeper = db.newUser("keeper")
        db.engage(keeper, a, likedAt = NOW)
        db.edition(keeper, NOW, 1, listOf(b))
        assertEquals(FeedGarbageCollector.Result(0, 0), FeedGarbageCollector(db).run())
        assertTrue(db.articleDao().isVisible(keeper, a))
        assertTrue(db.articleDao().isVisible(keeper, b))

        // Once nothing refers to them, the posts go, then the feed with them.
        db.exec("DELETE FROM engagement WHERE user_id = $keeper", "DELETE FROM edition WHERE user_id = $keeper")
        assertEquals(FeedGarbageCollector.Result(2, 1), FeedGarbageCollector(db).run())
        assertNull(db.feedDao().getById(feedId))
        assertEquals(0, db.articleDao().count())
        assertEquals(0L, db.long("SELECT COUNT(*) FROM article_fts"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM article_content"))

        assertEquals(ApiError.NOT_FOUND, failure { service.unsubscribe(ann, feedId) }.code)
    }

    @Test
    fun aFeedNobodyFetchedAndNobodySubscribesToIsCollected() = runBlocking<Unit> {
        val feedId = db.feedFor(url("/never"), NOW, ann)
        service.unsubscribe(ann, feedId)
        assertEquals(FeedGarbageCollector.Result(0, 1), FeedGarbageCollector(db).run())
        // A feed someone only discovered is not an orphan.
        val looked = db.feedFor(url("/looked"), NOW, bob, status = FeedStatus.DISCOVERED)
        assertEquals(FeedGarbageCollector.Result(0, 0), FeedGarbageCollector(db).run())
        assertNotNull(db.feedDao().getById(looked))
    }

    @Test
    fun discoveredSubscriptionsExpireUnlessTheUserKeptSomething() = runBlocking<Unit> {
        val idle = db.feedFor(url("/idle"), NOW, ann, status = FeedStatus.DISCOVERED)
        val saved = db.feedFor(url("/saved"), NOW, ann, status = FeedStatus.DISCOVERED)
        db.exec("INSERT INTO article (feedId, guid, title, publishedAt, firstSeenAt) VALUES ($saved, 's', 'S', 0, 0)")
        val post = db.long("SELECT id FROM article WHERE feedId = $saved")
        db.engage(ann, post, savedAt = NOW)

        clock.now = NOW.plus(Duration.ofDays(6))
        assertEquals(0, DiscoveryCleanup(db, clock).run())
        clock.now = NOW.plus(Duration.ofDays(8))
        assertEquals(1, DiscoveryCleanup(db, clock).run())
        assertNull(db.subscriptionDao().get(ann, idle))
        assertNotNull(db.subscriptionDao().get(ann, saved))
        assertEquals(FeedGarbageCollector.Result(0, 1), FeedGarbageCollector(db).run())
        assertNull(db.feedDao().getById(idle))
    }

    @Test
    fun discoverFindsTheFeedsOfAnAddress() = runBlocking<Unit> {
        val found = service.discover(ann, DiscoverFeedRequest(url("/feed")))
        assertEquals(url("/feed"), found.candidates.single().url)
        routes.clear()
        routes["/page"] = { response("<html><body>Nothing here</body></html>", 200, "Content-Type" to "text/html") }
        assertEquals(ApiError.NOT_A_FEED, failure { service.discover(ann, DiscoverFeedRequest(url("/page"))) }.code)
    }

    @Test
    fun opmlImportRunsAsAJobAndRefusesWhatIsNotOpml() = runBlocking<Unit> {
        assertEquals(ApiError.BAD_REQUEST, failure { service.importOpml(ann, "not opml".toByteArray()) }.code)
        assertTrue(jobs.outcomes.isEmpty())

        service.importOpml(ann, CoreFixtures.bytes("nested.opml"))
        val outcome = jobs.outcomes.single()
        assertEquals(JobResult.DONE, outcome.result)
        assertEquals(OpmlReportDto(added = 5, skipped = 0), ApiJson.json.decodeFromJsonElement<OpmlReportDto>(outcome.report!!))
        assertEquals(5, service.feeds(ann).feeds.size)
        assertTrue(service.exportOpml(ann).contains("https://kernel.example/rss"))
        assertFalse(service.exportOpml(bob).contains("https://kernel.example/rss"))
    }
}

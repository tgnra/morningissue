package net.tgnra.morningissue.server.feeds.discovery

import java.io.File
import java.io.FileInputStream
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.VerdictRequest
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.feeds.DefaultFeedRefresher
import net.tgnra.morningissue.server.feeds.DefaultFeedService
import net.tgnra.morningissue.server.feeds.FeedHealthChecker
import net.tgnra.morningissue.server.feeds.InlineJobs
import net.tgnra.morningissue.server.feeds.OpmlTransfer
import net.tgnra.morningissue.server.feeds.Subscriptions
import net.tgnra.morningissue.server.feeds.feedFor
import net.tgnra.morningissue.server.feeds.newUser
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.sync.CandidateFetcher
import net.tgnra.morningissue.sync.DefaultFeedDiscovery
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedRepairer
import net.tgnra.morningissue.sync.MutableClock
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

/** Discovery per user against a small catalogue; ported from the app's `DiscoveryRepositoryTest`. */
class DiscoveryServiceTest {
    private val now = Instant.parse("2026-11-11T08:00:00Z")
    private val clock = MutableClock(now)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: ServerDatabase
    private lateinit var root: File
    private lateinit var subscriptions: Subscriptions
    private var ann = 0L
    private var bob = 0L
    private var guid = 0

    /** No network in these tests: every host is unknown at once. */
    private val fetcher = FeedFetcher(OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build())

    @Before
    fun setUp() = runBlocking {
        db = ServerDatabase.inMemory()
        root = Files.createTempDirectory("discovery-test").toFile()
        subscriptions = Subscriptions(db, clock)
        ann = db.newUser("ann")
        bob = db.newUser("bob")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
    }

    private val asset get() = File(root, "asset/catalogue.db")

    /** Twelve catalogue feeds on two subjects (axes 0 and 40), with headlines. */
    private fun writeCatalogue() {
        val feeds = (1..12).map { i ->
            val subject = if (i <= 6) 0 else 40
            CatalogueFixture.Feed(
                id = i.toLong(),
                url = "https://blog$i.example/feed.xml",
                title = "Blog $i",
                centroid = CatalogueFixture.axis(subject + i, tilt = 0.5f).also { v -> v[subject] += 1.5f; Discovery.normalise(v) },
                clusterId = if (i <= 6) 1 else 2,
                host = "blog$i.example",
                siteUrl = "https://blog$i.example/",
                headlines = listOf(CatalogueFixture.Headline("Post one of blog $i", "https://blog$i.example/one", now.minus(Duration.ofDays(2)).toEpochMilli(), 900)),
            )
        }
        CatalogueFixture.write(
            asset,
            feeds,
            clusters = listOf(CatalogueFixture.Cluster(1, "Woodworking", CatalogueFixture.axis(0)), CatalogueFixture.Cluster(2, "Rust", CatalogueFixture.axis(40))),
        )
    }

    private fun service(withCatalogue: Boolean = true): DefaultDiscoveryService {
        if (withCatalogue) writeCatalogue()
        val store = CatalogueStore(
            source = { if (asset.exists()) FileInputStream(asset) else null },
            directory = File(root, "installed"),
            versionStamp = { "test" },
            dispatcher = Dispatchers.Unconfined,
        )
        val discovery = DefaultFeedDiscovery(fetcher, clock)
        val feeds = DefaultFeedService(
            db, subscriptions, DefaultFeedRefresher(db, fetcher, clock, scope), FeedHealthChecker(db, FeedRepairer(fetcher, discovery, clock), clock),
            discovery, OpmlTransfer(db, subscriptions, clock), InlineJobs(), clock,
        )
        val candidates = CandidateFetcher(fetcher, File(root, "candidates"), clock, isMetered = { false }, embedder = { EmbeddingFixtures.embedder })
        return DefaultDiscoveryService(db, store, subscriptions, feeds, candidates, clock, dispatcher = Dispatchers.Unconfined)
    }

    private suspend fun subscribedFeed(userId: Long, url: String, vectorAxis: Int, posts: Int = 4, step: SliderStep = SliderStep.FAVOURITE): Long {
        val id = db.feedDao().getByUrl(url)?.id
            ?: db.feedFor(url, now.minus(Duration.ofDays(60)), title = "Mine $vectorAxis", siteUrl = url.substringBefore("/feed") + "/")
        if (db.subscriptionDao().get(userId, id) == null) {
            subscriptions.add(userId, url)
            db.subscriptionDao().updateSliderStep(userId, id, step)
        }
        repeat(posts) { i ->
            val articleId = db.articleDao().insertNew(
                ArticleEntity(feedId = id, guid = "g${guid++}", url = "$url/$i", title = "Post $i", publishedAt = now.minus(Duration.ofDays(i.toLong())), firstSeenAt = now.minus(Duration.ofDays(i.toLong()))),
            )!!
            val v = CatalogueFixture.axis(vectorAxis + i, tilt = 0.5f).also { it[vectorAxis] += 1.5f; Discovery.normalise(it) }
            db.signalDao().upsertEmbeddings(listOf(ArticleEmbeddingEntity(articleId, StaticEmbedder.MODEL_ID, StaticEmbedder.toBytes(v))))
        }
        return id
    }

    private suspend fun cards(discovery: DefaultDiscoveryService, userId: Long, topics: Set<Int>? = null) =
        discovery.deckState(userId, topics) as DeckState.Cards

    @Test
    fun withoutACatalogueThereAreNoSuggestions() = runBlocking<Unit> {
        val discovery = service(withCatalogue = false)
        assertEquals(DeckState.Unavailable, discovery.deckState(ann))
        val response = discovery.deck(ann)
        assertTrue(response.cards.isEmpty())
        assertFalse(response.needsTopics)
        assertTrue(discovery.topics(ann).topics.isEmpty())
    }

    @Test
    fun aColdStartAsksForTopicsThenKeepsTheWeeksDeck() = runBlocking<Unit> {
        val discovery = service()
        val cold = discovery.deck(ann)
        assertTrue(cold.needsTopics)
        assertEquals("2026-W46", cold.week)
        assertEquals(listOf("Rust", "Woodworking"), discovery.topics(ann).topics.map { it.label })

        val picked = discovery.chooseTopics(ann, TopicsRequest(listOf(2)))
        assertEquals(12, picked.cards.size)
        val first = cards(discovery, ann).suggestions.first()
        assertEquals(2, first.card.feed.clusterId)
        assertEquals("Because you picked Rust", first.card.reason)
        assertEquals("Post one of blog ${first.card.feed.id}", first.headlines.single().title)
        assertEquals("Post one of blog ${first.card.feed.id}", picked.cards.first().headlines.single().title)

        // Opening again shows the same deck, not a cold start, until the week changes.
        assertEquals(picked.cards.map { it.urlKey }, discovery.deck(ann).cards.map { it.urlKey })
        // Ann's picks are not Bob's.
        assertTrue(discovery.deck(bob).needsTopics)
        clock.now = now.plus(Duration.ofDays(7))
        assertTrue(discovery.deck(ann).needsTopics)
    }

    @Test
    fun theProfileComesFromTheUsersSubscriptionsAndLeavesOutDiscoveredFeeds() = runBlocking<Unit> {
        val discovery = service()
        subscribedFeed(ann, "https://someone.substack.com/feed", vectorAxis = 0)
        subscribedFeed(ann, "https://quiet.example/feed", vectorAxis = 40, posts = 2)
        db.feedFor("https://peek.example/feed", now, ann, title = "Peek", status = FeedStatus.DISCOVERED)
        // Bob's taste is his own.
        subscribedFeed(bob, "https://bobs.example/feed", vectorAxis = 40)

        val profile = discovery.profile(ann)
        assertEquals(1, profile.feeds.size)
        assertEquals(1.0, profile.feeds.single().weight, 1e-9)
        assertTrue("someone.substack.com" in profile.excludedHosts)
        assertFalse("substack.com" in profile.excludedHosts)
        assertFalse("peek.example" in profile.excludedHosts)
        assertFalse("bobs.example" in profile.excludedHosts)
        assertTrue("quiet.example/feed" in profile.excludedKeys)
        assertTrue(profile.hasTaste)

        assertEquals(1, cards(discovery, ann).suggestions.first().card.feed.clusterId)
        assertEquals(2, cards(discovery, bob).suggestions.first().card.feed.clusterId)
    }

    @Test
    fun passingHidesACardForGoodForThatUserAndUndoLeavesNoTrace() = runBlocking<Unit> {
        val discovery = service()
        subscribedFeed(ann, "https://mine.example/feed", vectorAxis = 0)
        subscribedFeed(bob, "https://mine.example/feed", vectorAxis = 0, posts = 0)
        val first = cards(discovery, ann).suggestions.first().card.feed

        assertNull(discovery.decide(ann, VerdictRequest(first.url, CardVerdict.PASSED)).feed)
        val verdict = db.discoveryDao().verdict(ann, first.urlKey)!!
        assertEquals(DiscoveryVerdict.PASSED, verdict.verdict)
        assertEquals(first.clusterId, verdict.clusterId)
        assertNotNull(verdict.centroid)
        assertFalse(cards(discovery, ann).suggestions.any { it.card.feed.id == first.id })
        assertEquals(listOf(first.title), discovery.passed(ann).passed.map { it.title })
        assertEquals(1, discovery.profile(ann).negatives.size)
        // Bob still sees it.
        assertTrue(cards(discovery, bob).suggestions.any { it.card.feed.id == first.id })
        assertTrue(discovery.passed(bob).passed.isEmpty())

        discovery.undo(ann, first.url)
        assertNull(db.discoveryDao().verdict(ann, first.urlKey))
        assertEquals(first.id, cards(discovery, ann).suggestions.first().card.feed.id)
        assertTrue(discovery.passed(ann).passed.isEmpty())
    }

    @Test
    fun subscribingAddsTheFeedAndUndoRemovesTheSubscription() = runBlocking<Unit> {
        val discovery = service()
        subscribedFeed(ann, "https://mine.example/feed", vectorAxis = 0)
        val card = cards(discovery, ann).suggestions.first().card.feed

        val feed = discovery.decide(ann, VerdictRequest(card.url, CardVerdict.SUBSCRIBED)).feed!!
        assertEquals(FeedStatus.SUBSCRIBED, feed.status)
        assertEquals(card.url, feed.url)
        assertEquals(card.title, feed.title)
        assertEquals(DiscoveryVerdict.SUBSCRIBED, db.discoveryDao().verdict(ann, card.urlKey)!!.verdict)
        // The first fetch was tried (and found no network).
        assertNotNull(db.feedDao().getById(feed.id)!!.lastAttemptAt)
        assertFalse(cards(discovery, ann).suggestions.any { it.card.feed.id == card.id })

        discovery.undo(ann, card.url)
        assertNull(db.subscriptionDao().get(ann, feed.id))
        assertNull(db.discoveryDao().verdict(ann, card.urlKey))
    }

    @Test
    fun readingAHeadlineStoresADiscoveredFeedThatSubscribingFlips() = runBlocking<Unit> {
        val discovery = service()
        val suggestion = cards(discovery, ann, topics = setOf(1)).suggestions.first()
        val headline = suggestion.headlines.single()

        val opened = discovery.open(ann, OpenDiscoveredRequest(suggestion.card.feed.url, headline.url!!))
        val article = db.articleDao().getById(opened.articleId)!!
        val subscription = db.subscriptionDao().get(ann, opened.feedId)!!
        assertEquals(FeedStatus.DISCOVERED, subscription.status)
        assertEquals(now, subscription.discoveredAt)
        assertEquals(headline.title, article.title)
        assertEquals(headline.url, article.url)
        assertEquals(900, article.wordCount)
        assertTrue(db.articleDao().isVisible(ann, article.id))
        assertFalse(db.articleDao().isVisible(bob, article.id))
        // Opening it again is the same post.
        assertEquals(opened, discovery.open(ann, OpenDiscoveredRequest(suggestion.card.feed.url, headline.url!!)))
        assertEquals(1, db.feedDao().getUrls().size)
        // A headline without a link is stored too.
        assertNotNull(discovery.openPost(ann, suggestion.card.feed.url, suggestion.card.feed, CatalogueHeadline("No link", null, null, null)))
        assertThrows(ApiFailure::class.java) {
            runBlocking { discovery.open(ann, OpenDiscoveredRequest(suggestion.card.feed.url, "https://elsewhere.example/x")) }
        }

        val feed = discovery.decide(ann, VerdictRequest(suggestion.card.feed.url, CardVerdict.SUBSCRIBED)).feed!!
        assertEquals(opened.feedId, feed.id)
        assertEquals(FeedStatus.SUBSCRIBED, db.subscriptionDao().get(ann, feed.id)!!.status)
        assertNull(db.subscriptionDao().get(ann, feed.id)!!.discoveredAt)

        // Undo puts the subscription back as it was, posts and all.
        discovery.undo(ann, suggestion.card.feed.url)
        val reverted = db.subscriptionDao().get(ann, feed.id)!!
        assertEquals(FeedStatus.DISCOVERED, reverted.status)
        assertEquals(now, reverted.discoveredAt)
        assertNotNull(db.articleDao().getById(opened.articleId))
    }

    @Test
    fun removingASubscriptionWritesADroppedVerdictThatUndoLeavesAlone() = runBlocking<Unit> {
        val id = subscribedFeed(ann, "https://mine.example/feed", vectorAxis = 0)
        subscriptions.remove(ann, id)
        val verdict = db.discoveryDao().verdict(ann, "mine.example/feed")!!
        assertEquals(DiscoveryVerdict.DROPPED, verdict.verdict)
        assertNotNull(verdict.centroid)

        val discovery = service()
        val profile = discovery.profile(ann)
        assertEquals(1, profile.negatives.size)
        assertTrue("mine.example/feed" in profile.excludedKeys)
        discovery.undo(ann, "https://mine.example/feed")
        assertEquals(DiscoveryVerdict.DROPPED, db.discoveryDao().verdict(ann, "mine.example/feed")!!.verdict)
        assertTrue(discovery.profile(bob).negatives.isEmpty())
    }
}

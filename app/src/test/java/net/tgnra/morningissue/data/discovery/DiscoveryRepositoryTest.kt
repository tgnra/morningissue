package net.tgnra.morningissue.data.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.RoomFeedRepository
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-11-11T08:00:00Z")
    private val clock = MutableClock(now)
    private lateinit var db: MorningIssueDatabase
    private lateinit var root: File
    private lateinit var feeds: RoomFeedRepository
    private val deckStore = DeckStore.InMemory()
    private var guid = 0

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(context)
        root = File(context.cacheDir, "discovery-test-${System.nanoTime()}").apply { mkdirs() }
        feeds = RoomFeedRepository(db, clock)
    }

    @After
    fun tearDown() {
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
                centroid = CatalogueFixture.axis(subject + i, tilt = 0.5f).also { v -> v[subject] += 1.5f; net.tgnra.morningissue.engine.Discovery.normalise(v) },
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

    private fun repository(withCatalogue: Boolean = true): DiscoveryRepository {
        if (withCatalogue) writeCatalogue()
        val store = CatalogueStore(
            source = { if (asset.exists()) FileInputStream(asset) else null },
            directory = File(root, "installed"),
            versionStamp = { "test" },
            dispatcher = Dispatchers.Unconfined,
        )
        return DiscoveryRepository(db, store, feeds, deckStore, clock, zone = { ZoneOffset.UTC }, dispatcher = Dispatchers.Unconfined)
    }

    private suspend fun subscribedFeed(url: String, vectorAxis: Int, posts: Int = 4, step: SliderStep = SliderStep.FAVOURITE): Long {
        val id = db.feedDao().insert(FeedEntity(url = url, siteUrl = url.substringBefore("/feed") + "/", title = "Mine $vectorAxis", sliderStep = step, addedAt = now.minus(Duration.ofDays(60))))
        repeat(posts) { i ->
            val articleId = db.articleDao().insertNew(
                ArticleEntity(feedId = id, guid = "g${guid++}", url = "$url/$i", title = "Post $i", publishedAt = now.minus(Duration.ofDays(i.toLong())), firstSeenAt = now.minus(Duration.ofDays(i.toLong()))),
            )!!
            val v = CatalogueFixture.axis(vectorAxis + i, tilt = 0.5f).also { it[vectorAxis] += 1.5f; net.tgnra.morningissue.engine.Discovery.normalise(it) }
            db.articleSignalDao().upsertEmbeddings(listOf(ArticleEmbeddingEntity(articleId, StaticEmbedder.MODEL_ID, StaticEmbedder.toBytes(v))))
        }
        return id
    }

    @Test
    fun withoutACatalogueThereAreNoSuggestions() = runTest {
        assertEquals(DeckState.Unavailable, repository(withCatalogue = false).deck())
    }

    @Test
    fun aColdStartAsksForTopicsThenKeepsTheWeeksDeck() = runTest {
        val discovery = repository()
        val cold = discovery.deck() as DeckState.NeedsTopics
        assertEquals(listOf("Rust", "Woodworking"), cold.topics.map { it.label })

        val cards = discovery.deck(topics = setOf(2)) as DeckState.Cards
        assertEquals(12, cards.total)
        assertEquals(2, cards.suggestions.first().feed.clusterId)
        assertEquals("Because you picked Rust", cards.suggestions.first().card.reason)
        assertEquals("Post one of blog ${cards.suggestions.first().feed.id}", cards.suggestions.first().headlines.single().title)

        // Opening again shows the same deck, not a cold start, until the week changes.
        val again = discovery.deck() as DeckState.Cards
        assertEquals(cards.suggestions.map { it.feed.id }, again.suggestions.map { it.feed.id })
        clock.now = now.plus(Duration.ofDays(7))
        assertTrue(discovery.deck() is DeckState.NeedsTopics)
    }

    @Test
    fun theProfileComesFromSubscriptionsAndPassesAndLeavesOutDiscoveredFeeds() = runTest {
        val discovery = repository()
        subscribedFeed("https://someone.substack.com/feed", vectorAxis = 0)
        subscribedFeed("https://quiet.example/feed", vectorAxis = 40, posts = 2)
        // A feed only looked into: not part of the taste, not hiding its host.
        db.feedDao().insert(FeedEntity(url = "https://peek.example/feed", title = "Peek", addedAt = now, status = FeedStatus.DISCOVERED, discoveredAt = now))

        val profile = discovery.profile()
        // Two posts are not enough for a centroid.
        assertEquals(1, profile.feeds.size)
        assertEquals(1.0, profile.feeds.single().weight, 1e-9)
        assertTrue("someone.substack.com" in profile.excludedHosts)
        assertFalse("substack.com" in profile.excludedHosts)
        assertFalse("peek.example" in profile.excludedHosts)
        assertTrue("quiet.example/feed" in profile.excludedKeys)
        assertTrue(profile.hasTaste)

        val deck = discovery.deck() as DeckState.Cards
        assertEquals(1, deck.suggestions.first().feed.clusterId)
    }

    @Test
    fun passingHidesACardForGoodAndTheUndoLeavesNoTrace() = runTest {
        val discovery = repository()
        subscribedFeed("https://mine.example/feed", vectorAxis = 0)
        val first = (discovery.deck() as DeckState.Cards).suggestions.first()

        val decision = discovery.pass(first.feed)
        val verdict = db.discoveryDao().verdict(first.feed.urlKey)!!
        assertEquals(DiscoveryVerdict.PASSED, verdict.verdict)
        assertEquals(first.feed.clusterId, verdict.clusterId)
        assertNotNull(verdict.centroid)
        val after = discovery.deck() as DeckState.Cards
        assertFalse(after.suggestions.any { it.feed.id == first.feed.id })
        assertEquals(listOf(first.feed.title), discovery.observePassed().first().map { it.title })
        // It counts against feeds like it.
        assertEquals(1, discovery.profile().negatives.size)

        discovery.undo(decision)
        assertNull(db.discoveryDao().verdict(first.feed.urlKey))
        assertEquals(first.feed.id, (discovery.deck() as DeckState.Cards).suggestions.first().feed.id)

        discovery.pass(first.feed)
        discovery.unpass(first.feed.urlKey)
        assertTrue(discovery.observePassed().first().isEmpty())
    }

    @Test
    fun subscribingAddsTheFeedAndUndoRemovesIt() = runTest {
        val discovery = repository()
        subscribedFeed("https://mine.example/feed", vectorAxis = 0)
        val card = (discovery.deck() as DeckState.Cards).suggestions.first()

        val decision = discovery.subscribe(card.feed)
        assertTrue(decision.created)
        val row = db.feedDao().getById(decision.feedId)!!
        assertEquals(FeedStatus.SUBSCRIBED, row.status)
        assertEquals(card.feed.url, row.url)
        assertEquals(DiscoveryVerdict.SUBSCRIBED, db.discoveryDao().verdict(card.feed.urlKey)!!.verdict)
        assertTrue(feeds.getFeeds().any { it.id == decision.feedId })
        assertFalse(discovery.undoLosesPosts(decision))

        discovery.undo(decision)
        assertNull(db.feedDao().getById(decision.feedId))
        assertNull(db.discoveryDao().verdict(card.feed.urlKey))
    }

    @Test
    fun readingAHeadlineStoresADiscoveredFeedThatSubscribingFlips() = runTest {
        val discovery = repository()
        val card = (discovery.deck(topics = setOf(1)) as DeckState.Cards).suggestions.first()
        val headline = card.headlines.single()

        val articleId = discovery.openPost(card.feed, headline)!!
        val article = db.articleDao().getById(articleId)!!
        val feed = db.feedDao().getById(article.feedId)!!
        assertEquals(FeedStatus.DISCOVERED, feed.status)
        assertEquals(now, feed.discoveredAt)
        assertEquals(headline.title, article.title)
        assertEquals(headline.url, article.url)
        assertEquals(900, article.wordCount)
        // Not a subscription anywhere, and opening it again is the same post.
        assertTrue(feeds.getFeeds().none { it.id == feed.id })
        assertEquals(articleId, discovery.openPost(card.feed, headline))
        assertEquals(1, db.feedDao().getUrls().size)
        // A headline without a link is stored too.
        assertNotNull(discovery.openPost(card.feed, CatalogueHeadline("No link", null, null, null)))

        val decision = discovery.subscribe(card.feed)
        assertFalse(decision.created)
        assertEquals(feed.id, decision.feedId)
        assertEquals(FeedStatus.SUBSCRIBED, db.feedDao().getById(feed.id)!!.status)
        assertNull(db.feedDao().getById(feed.id)!!.discoveredAt)

        // Undo puts the row back as it was, posts and all.
        discovery.undo(decision)
        val reverted = db.feedDao().getById(feed.id)!!
        assertEquals(FeedStatus.DISCOVERED, reverted.status)
        assertEquals(now, reverted.discoveredAt)
        assertNotNull(db.articleDao().getById(articleId))
    }

    @Test
    fun addingADiscoveredFeedByAddressSubscribesTheSameRow() = runTest {
        val id = db.feedDao().insert(FeedEntity(url = "https://peek.example/feed", title = "Peek", addedAt = now.minus(Duration.ofDays(2)), status = FeedStatus.DISCOVERED, discoveredAt = now.minus(Duration.ofDays(2))))
        val result = feeds.addFeed(NewFeed(url = "https://peek.example/feed/"))
        assertEquals(AddFeedResult.Added(id), result)
        val row = db.feedDao().getById(id)!!
        assertEquals(FeedStatus.SUBSCRIBED, row.status)
        assertEquals(now, row.addedAt)
        assertEquals(DiscoveryVerdict.SUBSCRIBED, db.discoveryDao().verdict("peek.example/feed")!!.verdict)
        // Once subscribed it is simply already there.
        assertEquals(AddFeedResult.AlreadySubscribed(id), feeds.addFeed(NewFeed(url = "https://peek.example/feed")))
    }

    @Test
    fun removingASubscriptionWritesADroppedVerdict() = runTest {
        val id = subscribedFeed("https://mine.example/feed", vectorAxis = 0)
        feeds.removeFeed(id)
        val verdict = db.discoveryDao().verdict("mine.example/feed")!!
        assertEquals(DiscoveryVerdict.DROPPED, verdict.verdict)
        assertNotNull(verdict.centroid)
        assertNull(db.feedDao().getById(id))
        // A dropped feed is a lasting negative, and never suggested again.
        val profile = repository().profile()
        assertEquals(1, profile.negatives.size)
        assertTrue("mine.example/feed" in profile.excludedKeys)
    }

    @Test
    fun theCleanupDeletesUnsavedDiscoveredPostsAfterAWeek() = runTest {
        val discovered = db.feedDao().insert(FeedEntity(url = "https://peek.example/feed", title = "Peek", addedAt = now, status = FeedStatus.DISCOVERED, discoveredAt = now))
        val lonely = db.feedDao().insert(FeedEntity(url = "https://gone.example/feed", title = "Gone", addedAt = now, status = FeedStatus.DISCOVERED, discoveredAt = now))
        val mine = db.feedDao().insert(FeedEntity(url = "https://mine.example/feed", title = "Mine", addedAt = now))
        suspend fun post(feedId: Long, seenDaysAgo: Long): Long = db.articleDao().insertNew(
            ArticleEntity(feedId = feedId, guid = "g${guid++}", url = null, title = "T", publishedAt = now.minus(Duration.ofDays(seenDaysAgo)), firstSeenAt = now.minus(Duration.ofDays(seenDaysAgo))),
        )!!
        val old = post(discovered, 9)
        val saved = post(discovered, 9).also { db.engagementDao().setSaved(it, now.minus(Duration.ofDays(9))) }
        val liked = post(discovered, 9).also { db.engagementDao().setLiked(it, now.minus(Duration.ofDays(9))) }
        val openedLately = post(discovered, 9).also { db.engagementDao().markOpened(it, now.minus(Duration.ofDays(2))) }
        val recent = post(discovered, 2)
        val onlyPost = post(lonely, 10)
        val subscribedOld = post(mine, 30)

        assertEquals(2, DiscoveryCleanup(db, images = null, clock = clock).run())

        assertNull(db.articleDao().getById(old))
        assertNull(db.articleDao().getById(onlyPost))
        for (kept in listOf(saved, liked, openedLately, recent, subscribedOld)) assertNotNull(db.articleDao().getById(kept))
        // A discovered feed with nothing left goes too; the others stay.
        assertNull(db.feedDao().getById(lonely))
        assertNotNull(db.feedDao().getById(discovered))
        assertNotNull(db.feedDao().getById(mine))
        assertEquals(0, DiscoveryCleanup(db, images = null, clock = clock).run())
    }
}

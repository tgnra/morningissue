package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import net.tgnra.morningissue.model.CadenceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery's ranking against simulated readers in a simulated catalogue: eight topics whose
 * vectors share a strong common direction, as static embeddings do, so that nothing works
 * unless the centring does.
 */
class DiscoveryTest {
    private val now = Instant.parse("2026-11-11T08:00:00Z")
    private val dims = 256
    private val random = Random(42)

    private fun gaussian(): FloatArray = FloatArray(dims) { random.nextDouble(-1.0, 1.0).toFloat() }

    private fun unit(v: FloatArray): FloatArray = v.copyOf().also { Discovery.normalise(it) }

    private fun mix(vararg parts: Pair<Double, FloatArray>): FloatArray {
        val out = FloatArray(dims)
        for ((w, v) in parts) for (d in out.indices) out[d] += (w * v[d]).toFloat()
        return unit(out)
    }

    private val common = unit(gaussian())
    private val labels = listOf("Woodworking", "Rust", "Gardening", "Astronomy", "Cooking", "Cycling", "Poetry", "Economics")
    private val topics = labels.map { unit(gaussian()) }
    private val clusters = labels.mapIndexed { i, label -> CatalogueCluster(i, label, mix(1.5 to common, 1.0 to topics[i])) }

    /** A post or feed vector about [topic]: mostly the shared direction, then the topic, then noise. */
    private fun about(topic: Int, noise: Double = 0.6): FloatArray = mix(1.5 to common, 1.0 to topics[topic], noise to unit(gaussian()))

    private var nextId = 1L

    private fun feed(
        topic: Int,
        title: String = "${labels[topic]} ${nextId}",
        host: String = "site$nextId.example",
        tier: CadenceTier = CadenceTier.WEEKLY,
        language: String? = "en",
        categories: List<String> = emptyList(),
        source: String? = "kagi",
        fullTextShare: Double = 1.0,
        lastPostAt: Instant = now.minus(Duration.ofDays(3)),
        quality: Double = 1.0,
        centroid: FloatArray? = about(topic, noise = 0.4),
        url: String = "https://$host/feed.xml",
    ): CatalogueFeed {
        val id = nextId++
        return CatalogueFeed(
            id = id, url = url, urlKey = url.removePrefix("https://"), siteUrl = "https://$host/", host = host, title = title,
            description = null, iconUrl = null, language = language, tier = tier, medianIntervalHours = 48.0, postsPerWeek = 3.0,
            fullTextShare = fullTextShare, lastPostAt = lastPostAt, clusterId = topic, categories = categories, source = source,
            quality = quality, centroid = centroid,
        )
    }

    private val catalogue: List<CatalogueFeed> = labels.indices.flatMap { topic -> List(40) { feed(topic) } }

    private val mean: FloatArray = FloatArray(dims).also { sum ->
        for (f in catalogue) for (d in sum.indices) sum[d] += f.centroid!![d] / catalogue.size
    }

    private val discovery = Discovery(mean, clusters, DiscoveryConfig.Default, now)

    private fun subscribed(topic: Int, title: String = "My ${labels[topic]} blog", weight: Double = 1.0) =
        ProfileFeed(title, about(topic, noise = 0.4), weight)

    private fun topicOf(card: DeckCard) = card.feed.clusterId!!

    @Test
    fun aWoodworkerIsShownWoodworkingAndEveryFifthCardIsSomethingElse() {
        val profile = DiscoveryProfile(feeds = listOf(subscribed(0, "Lost Art Press"), subscribed(0, "Paul Sellers"), subscribed(0, "Fine Woodworking")))
        val deck = discovery.deck(catalogue, profile, Random(1))

        assertEquals(20, deck.size)
        val regular = deck.filterNot { it.exploration }
        val exploring = deck.filter { it.exploration }
        assertEquals(listOf(5, 10, 15, 20), deck.mapIndexedNotNull { i, card -> (i + 1).takeIf { card.exploration } })
        // MMR spreads the regular cards a little, but woodworking still dominates.
        assertTrue(regular.count { topicOf(it) == 0 } >= 14)
        assertEquals(0, topicOf(deck.first()))
        // Exploration comes from clusters the reader's sources do not cover.
        assertTrue(exploring.none { topicOf(it) == 0 })
        assertTrue(exploring.all { it.reason.startsWith("Something different: ") })
        assertTrue(deck.first().reason.startsWith("Close to "))
        // Never two cards from one host.
        assertEquals(deck.size, deck.map { it.feed.host }.distinct().size)
    }

    @Test
    fun aReaderOfTwoSubjectsGetsBothRatherThanSomethingInBetween() {
        val profile = DiscoveryProfile(feeds = List(3) { subscribed(0) } + List(3) { subscribed(1) })
        val regular = discovery.deck(catalogue, profile, Random(1)).filterNot { it.exploration }
        val woodworking = regular.count { topicOf(it) == 0 }
        val rust = regular.count { topicOf(it) == 1 }
        assertTrue("woodworking $woodworking, rust $rust", woodworking >= 5 && rust >= 5)
        assertEquals(regular.size, woodworking + rust)
    }

    @Test
    fun withoutCentringTheSharedDirectionWouldHideTheTopic() {
        // The raw vectors of two different topics are already very alike...
        assertTrue(Discovery.cosine(about(0), about(4)) > 0.55f)
        // ...and centred they are not.
        assertTrue(Discovery.cosine(discovery.centre(about(0)), discovery.centre(about(4))) < 0.3f)
    }

    @Test
    fun likedPostsAloneAreEnoughAndSaySo() {
        val posts = List(6) { ProfilePost(about(2), weight = 1.0, liked = true) }
        val profile = DiscoveryProfile(posts = posts)
        assertTrue(profile.hasTaste)
        val deck = discovery.deck(catalogue, profile, Random(1))
        val regular = deck.filterNot { it.exploration }
        assertTrue(regular.count { topicOf(it) == 2 } >= 14)
        assertEquals("Like posts you have Liked", deck.first().reason)

        val read = DiscoveryProfile(posts = List(6) { ProfilePost(about(2), weight = 0.5, liked = false) })
        assertEquals("Like posts you have read", discovery.deck(catalogue, read, Random(1)).first().reason)
    }

    @Test
    fun aColdStartWithPickedTopicsShowsThoseTopics() {
        assertFalse(DiscoveryProfile().hasTaste)
        val profile = DiscoveryProfile(pickedClusters = setOf(3, 6))
        assertTrue(profile.hasTaste)
        val deck = discovery.deck(catalogue, profile, Random(1))
        val regular = deck.filterNot { it.exploration }
        assertTrue(regular.all { topicOf(it) == 3 || topicOf(it) == 6 })
        assertTrue(regular.first().reason == "Because you picked Astronomy" || regular.first().reason == "Because you picked Poetry")
        // Picked topics count as covered: exploration goes elsewhere.
        assertTrue(deck.filter { it.exploration }.none { topicOf(it) == 3 || topicOf(it) == 6 })
    }

    @Test
    fun passesPushSimilarFeedsDown() {
        val base = DiscoveryProfile(feeds = List(3) { subscribed(0) } + List(3) { subscribed(1) })
        val before = discovery.deck(catalogue, base, Random(1)).filterNot { it.exploration }.count { topicOf(it) == 1 }
        val passedRust = catalogue.filter { it.clusterId == 1 }.take(6)
        val withPasses = DiscoveryProfile(
            feeds = base.feeds,
            negatives = passedRust.map { ProfileNegative(it.centroid!!, Discovery.passWeight(now.minus(Duration.ofDays(1)), now)) },
            excludedKeys = passedRust.mapTo(HashSet()) { it.urlKey },
        )
        val deck = discovery.deck(catalogue, withPasses, Random(1))
        val after = deck.filterNot { it.exploration }.count { topicOf(it) == 1 }
        assertTrue("rust cards before $before, after $after", after < before)
        // A passed feed is never shown again.
        assertTrue(deck.none { it.feed in passedRust })
    }

    @Test
    fun hardFiltersRemoveWhatMayNeverBeSuggested() {
        val mine = subscribed(0)
        val eligible = feed(0)
        val french = feed(0, language = "fr")
        val youtube = feed(0, host = "youtube.com", url = "https://www.youtube.com/feeds/videos.xml?channel_id=x")
        val comic = feed(0, categories = listOf("Webcomics"))
        val comicSource = feed(0, source = "smallcomic")
        val noCentroid = feed(0, centroid = null)
        val sameHost = feed(0, host = "mine.example")
        val passed = feed(0)
        val duplicate = feed(0, centroid = mine.vector.copyOf())
        val unknownLanguage = feed(0, language = null)
        val profile = DiscoveryProfile(feeds = listOf(mine), excludedHosts = setOf("mine.example"), excludedKeys = setOf(passed.urlKey))

        for (f in listOf(french, youtube, comic, comicSource, noCentroid, sameHost, passed)) {
            assertFalse("$f should be filtered", discovery.isEligible(f, profile))
        }
        assertTrue(discovery.isEligible(eligible, profile))
        assertTrue(discovery.isEligible(unknownLanguage, profile))
        // The near-duplicate of a subscription is eligible in itself but never scored.
        val scored = discovery.score(listOf(eligible, duplicate, unknownLanguage), profile).map { it.feed }
        assertEquals(setOf(eligible, unknownLanguage), scored.toSet())
    }

    @Test
    fun aSubscriptionOnASharedPlatformHidesOnlyThatSite() {
        val profile = DiscoveryProfile(feeds = listOf(subscribed(0)), excludedHosts = setOf("someone.substack.com"))
        val same = feed(0, host = "substack.com", url = "https://someone.substack.com/feed")
        val other = feed(0, host = "substack.com", url = "https://another.substack.com/feed")
        assertFalse(discovery.isEligible(same, profile))
        assertTrue(discovery.isEligible(other, profile))
    }

    @Test
    fun cadenceAndQualityBreakTiesBetweenFeedsOnTheSameSubject() {
        val vector = about(5, noise = 0.4)
        val weekly = feed(5, centroid = vector, tier = CadenceTier.WEEKLY)
        val firehose = feed(5, centroid = vector, tier = CadenceTier.FIREHOSE)
        val excerpts = feed(5, centroid = vector, fullTextShare = 0.1)
        val stale = feed(5, centroid = vector, lastPostAt = now.minus(Duration.ofDays(90)))
        val poor = feed(5, centroid = vector, quality = 0.0)
        val profile = DiscoveryProfile(feeds = listOf(subscribed(5)))
        val scores = discovery.score(catalogue + listOf(weekly, firehose, excerpts, stale, poor), profile).associate { it.feed to it.score }
        val best = scores.getValue(weekly)
        assertEquals(kotlin.math.ln(0.5), scores.getValue(firehose) - best, 1e-9)
        assertEquals(kotlin.math.ln(0.8), scores.getValue(excerpts) - best, 1e-9)
        assertEquals(kotlin.math.ln(0.7), scores.getValue(stale) - best, 1e-9)
        assertEquals(kotlin.math.ln(0.5), scores.getValue(poor) - best, 1e-9)

        // A reader who loves a firehose feed minds them less.
        val lover = DiscoveryProfile(feeds = listOf(subscribed(5), ProfileFeed("Wire", about(7), 1.0, CadenceTier.FIREHOSE)))
        assertEquals(0.8, discovery.cadenceFit(CadenceTier.FIREHOSE, lovesFirehose = true), 0.0)
        assertTrue(discovery.score(listOf(firehose), lover).single().score > discovery.score(listOf(firehose), profile).single().score - 1.0)
    }

    @Test
    fun sharedCategoriesLiftAFeed() {
        val vector = about(4, noise = 0.4)
        val plain = feed(4, centroid = vector)
        val tagged = feed(4, centroid = vector, categories = listOf("Baking", "Bread"))
        val profile = DiscoveryProfile(feeds = listOf(subscribed(4)), readerTerms = setOf("bread", "baking"))
        val scores = discovery.score(listOf(plain, tagged), profile).associate { it.feed to it.score }
        assertTrue(scores.getValue(tagged) > scores.getValue(plain))
        assertEquals(2.0 / 3.0, discovery.categoryOverlap(tagged, setOf("bread", "baking")), 1e-9)
    }

    @Test
    fun theSameSeedGivesTheSameDeckAndTheBanditFollowsSubscribes() {
        val profile = DiscoveryProfile(feeds = List(3) { subscribed(0) })
        val first = discovery.deck(catalogue, profile, Random(7)).map { it.feed.id }
        assertEquals(first, discovery.deck(catalogue, profile, Random(7)).map { it.feed.id })

        // Subscribes in Cycling and passes everywhere else: exploration goes to Cycling.
        val tallies = (1 until labels.size).associateWith { if (it == 5) ClusterTally(subscribes = 30) else ClusterTally(passes = 30) }
        val keen = DiscoveryProfile(feeds = profile.feeds, clusterTallies = tallies)
        val exploring = (1..10).flatMap { seed -> discovery.deck(catalogue, keen, Random(seed)).filter { it.exploration } }
        val cycling = exploring.count { topicOf(it) == 5 }
        assertTrue("cycling $cycling of ${exploring.size}", cycling >= exploring.size * 0.9)
    }

    @Test
    fun aSmallCatalogueGivesAShortDeckRatherThanRepeats() {
        val small = catalogue.filter { it.clusterId == 0 }.take(7)
        val deck = discovery.deck(small, DiscoveryProfile(feeds = listOf(subscribed(0))), Random(1))
        assertEquals(7, deck.size)
        assertEquals(7, deck.map { it.feed.id }.distinct().size)
        assertTrue(discovery.deck(emptyList(), DiscoveryProfile(feeds = listOf(subscribed(0))), Random(1)).isEmpty())
    }

    @Test
    fun weightsFollowTheSliderAndPassesFade() {
        assertEquals(0.0, Discovery.feedWeight(0.2), 1e-9)
        assertEquals(1.0, Discovery.feedWeight(1.0), 1e-9)
        assertEquals(0.23, Discovery.feedWeight(0.5), 0.01)
        assertEquals(1.0, Discovery.passWeight(now, now), 1e-9)
        assertEquals(0.5, Discovery.passWeight(now.minus(Duration.ofDays(90)), now), 1e-9)
        assertEquals(0.25, Discovery.passWeight(now.minus(Duration.ofDays(180)), now), 1e-9)
        // A feed on Less or Rarely weighs nothing, so it does not shape suggestions.
        val ignored = DiscoveryProfile(feeds = listOf(subscribed(0, weight = 0.0)))
        assertFalse(ignored.hasTaste)
    }

    @Test
    fun betaDrawsHaveTheRightMean() {
        val r = Random(3)
        val draws = List(4000) { Discovery.sampleBeta(3, 1, r) }
        assertEquals(0.75, draws.average(), 0.02)
        assertTrue(draws.all { it in 0.0..1.0 })
        assertNotEquals(Discovery.sampleBeta(1, 1, Random(1)), Discovery.sampleBeta(1, 1, Random(2)), 0.0)
    }

    @Test
    fun exactHostsIgnoreWwwAndCase() {
        assertEquals("example.com", Discovery.exactHost("https://WWW.Example.com/feed"))
        assertEquals("someone.substack.com", Discovery.exactHost("someone.substack.com/feed"))
        assertEquals(null, Discovery.exactHost(null))
    }
}

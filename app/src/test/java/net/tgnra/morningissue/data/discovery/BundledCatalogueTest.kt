package net.tgnra.morningissue.data.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.engine.DiscoveryProfile
import net.tgnra.morningissue.engine.ProfileFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the catalogue that ships in the APK through the app's own [CatalogueStore] and builds
 * decks from it, so a pipeline rebuild that breaks the format or the vector space fails here.
 */
@RunWith(AndroidJUnit4::class)
class BundledCatalogueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val asset = File("src/main/assets/discovery/catalogue.db")
    private lateinit var installDir: File

    @Before
    fun setUp() {
        installDir = File(context.cacheDir, "bundled-catalogue-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        installDir.deleteRecursively()
    }

    private suspend fun catalogue(): Catalogue {
        assumeTrue("no bundled catalogue in this checkout", asset.exists())
        val status = CatalogueStore(CatalogueSource { FileInputStream(asset) }, installDir, versionStamp = { "test" }, dispatcher = Dispatchers.Unconfined).load()
        assertTrue("bundled catalogue did not open: $status", status is CatalogueStatus.Ready)
        return (status as CatalogueStatus.Ready).catalogue
    }

    @Test
    fun theBundledCatalogueOpensWithFeedsInEveryCluster() = runTest {
        val catalogue = catalogue()
        assertTrue(catalogue.feeds.size > 10_000)
        assertTrue(catalogue.clusters.size >= 40)
        val withCentroid = catalogue.feeds.count { it.centroid != null }
        assertEquals(catalogue.feeds.size, withCentroid)
        assertTrue(catalogue.headlines(catalogue.feeds.first().id).isNotEmpty())
    }

    @Test
    fun aPickedTopicGivesADeckMostlyFromThatTopic() = runTest {
        val catalogue = catalogue()
        val discovery = Discovery(catalogue.mean, catalogue.clusters, now = catalogue.builtAt ?: java.time.Instant.now())
        for (label in listOf("Food", "Photography", "Poetry", "History")) {
            val cluster = catalogue.clusters.first { it.label == label }
            val deck = discovery.deck(catalogue.feeds, DiscoveryProfile(pickedClusters = setOf(cluster.id)), Random(1))
            assertEquals(20, deck.size)
            val regular = deck.filterNot { it.exploration }
            val inTopic = regular.count { it.feed.clusterId == cluster.id }
            assertTrue("$label: $inTopic of ${regular.size} in topic", inTopic * 2 >= regular.size)
        }
    }

    @Test
    fun aSubscribedFeedPullsInFeedsLikeIt() = runTest {
        val catalogue = catalogue()
        val discovery = Discovery(catalogue.mean, catalogue.clusters, now = catalogue.builtAt ?: java.time.Instant.now())
        val food = catalogue.clusters.first { it.label == "Food" }
        val seed = catalogue.feeds.first { it.clusterId == food.id }
        val profile = DiscoveryProfile(
            feeds = listOf(ProfileFeed(seed.title, seed.centroid!!, 1.0)),
            excludedKeys = setOf(seed.urlKey),
            excludedHosts = setOfNotNull(seed.host),
        )
        val deck = discovery.deck(catalogue.feeds, profile, Random(1))
        val top = deck.filterNot { it.exploration }.take(8)
        val foodish = top.count { card -> catalogue.clusters.firstOrNull { it.id == card.feed.clusterId }?.label?.contains("Food") == true }
        assertTrue("${top.map { it.feed.title }}", foodish >= 4)
        assertTrue(deck.none { it.feed.urlKey == seed.urlKey })
    }
}

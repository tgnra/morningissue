package net.tgnra.morningissue.server.feeds.discovery

import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.model.CadenceTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ported from the app's `CatalogueStoreTest`: the catalogue comes off the classpath and installs under the data directory. */
class CatalogueStoreTest {
    private lateinit var root: File
    private lateinit var installDir: File
    private lateinit var asset: File
    private var opens = 0
    private var stamp = "v1"

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("catalogue-test").toFile()
        installDir = File(root, "installed")
        asset = File(root, "asset/catalogue.db")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(source: CatalogueSource = CatalogueSource { opens++; if (asset.exists()) FileInputStream(asset) else null }) =
        CatalogueStore(source, installDir, versionStamp = { stamp }, dispatcher = Dispatchers.Unconfined)

    private val woodworking = CatalogueFixture.Feed(
        id = 1,
        url = "https://plane.example/feed.xml",
        title = "The Plane",
        centroid = CatalogueFixture.axis(0),
        clusterId = 7,
        host = "plane.example",
        siteUrl = "https://plane.example/",
        tier = "RARE",
        categories = listOf("Woodworking", "Hand tools"),
        description = "Notes from the bench.",
        lastPostAt = 1_761_000_000_000L,
        headlines = listOf(
            CatalogueFixture.Headline("Sharpening, again", "https://plane.example/sharpening", 1_761_000_000_000L, 1200),
            CatalogueFixture.Headline("A new bench", "https://plane.example/bench", 1_760_000_000_000L, null),
        ),
    )

    private fun writeAsset(feeds: List<CatalogueFixture.Feed> = listOf(woodworking), builtAt: Long = 1_762_000_000_000L, modelId: String = "1", userVersion: Int = 1, dims: String = "256", mean: FloatArray? = FloatArray(256)) =
        CatalogueFixture.write(
            asset,
            feeds,
            clusters = listOf(CatalogueFixture.Cluster(7, "Woodworking", CatalogueFixture.axis(0, tilt = 0.3f))),
            builtAt = builtAt,
            modelId = modelId,
            userVersion = userVersion,
            dims = dims,
            mean = mean,
        )

    @Test
    fun aBuildWithoutTheAssetHasNoSuggestionsAndDoesNotCrash() = runTest {
        assertEquals(CatalogueStatus.Missing, store().load())
        assertEquals(CatalogueStatus.Missing, store(CatalogueSource { throw java.io.IOException("gone") }.let { failing -> CatalogueSource { runCatching { failing.open() }.getOrNull() } }).load())
    }

    @Test
    fun theCatalogueIsInstalledAndReadReadOnly() = runTest {
        writeAsset(listOf(woodworking, CatalogueFixture.Feed(2, "https://other.example/rss", "", centroid = null, tier = null, categories = emptyList())))
        val status = store().load()
        val catalogue = (status as CatalogueStatus.Ready).catalogue
        assertTrue(File(installDir, CatalogueStore.FILE_NAME).exists())
        assertEquals(Instant.ofEpochMilli(1_762_000_000_000L), catalogue.builtAt)
        assertEquals(256, catalogue.mean.size)

        val cluster = catalogue.clusters.single()
        assertEquals(7, cluster.id)
        assertEquals("Woodworking", cluster.label)

        val feed = catalogue.feeds.first()
        assertEquals("The Plane", feed.title)
        assertEquals("plane.example/feed.xml", feed.urlKey)
        assertEquals(CadenceTier.RARE, feed.tier)
        assertEquals(listOf("Woodworking", "Hand tools"), feed.categories)
        assertEquals(7, feed.clusterId)
        assertEquals(Instant.ofEpochMilli(1_761_000_000_000L), feed.lastPostAt)
        // int8 × scale, back to a unit vector close to the one written.
        assertTrue(Discovery.cosine(feed.centroid!!, CatalogueFixture.axis(0)) > 0.999f)

        // A feed without a title or centroid is still read; discovery's filters deal with it.
        val bare = catalogue.feeds[1]
        assertEquals("other.example", bare.title)
        assertNull(bare.centroid)
        assertNull(bare.tier)

        val headlines = catalogue.headlines(1)
        assertEquals(listOf("Sharpening, again", "A new bench"), headlines.map { it.title })
        assertEquals(1200, headlines[0].wordCount)
        assertNull(headlines[1].wordCount)
        assertEquals(1, catalogue.headlines(1, limit = 1).size)
    }

    @Test
    fun aCatalogueForAnotherEmbedderOrFormatIsRefused() = runTest {
        writeAsset(modelId = "2")
        assertTrue((store().load() as CatalogueStatus.Refused).reason.contains("model_id"))

        // Each a new build, so that it replaces the one before.
        stamp = "v2"
        writeAsset(userVersion = 2, builtAt = 2L)
        assertTrue((store().load() as CatalogueStatus.Refused).reason.contains("user_version"))

        stamp = "v3"
        writeAsset(dims = "128", builtAt = 3L)
        assertTrue((store().load() as CatalogueStatus.Refused).reason.contains("dims"))

        stamp = "v4"
        writeAsset(mean = null, builtAt = 4L)
        assertTrue((store().load() as CatalogueStatus.Refused).reason.contains("mean"))

        stamp = "v5"
        asset.writeText("not a database at all")
        assertTrue(store().load() is CatalogueStatus.Refused)
    }

    @Test
    fun theAssetIsCopiedOnlyWhenItsBuildChanges() = runTest {
        writeAsset()
        assertTrue(store().load() is CatalogueStatus.Ready)
        assertEquals(1, opens)

        // The same server version: the installed copy is used, and the asset is not even opened.
        assertTrue(store().load() is CatalogueStatus.Ready)
        assertEquals(1, opens)

        // A new server version with the same catalogue build: looked at, not replaced.
        stamp = "v2"
        writeAsset(feeds = listOf(woodworking, CatalogueFixture.Feed(3, "https://new.example/feed", "New", CatalogueFixture.axis(3))))
        val same = (store().load() as CatalogueStatus.Ready).catalogue
        assertEquals(2, opens)
        assertEquals(1, same.feeds.size)

        // A new build: replaced.
        stamp = "v3"
        writeAsset(feeds = listOf(woodworking, CatalogueFixture.Feed(3, "https://new.example/feed", "New", CatalogueFixture.axis(3))), builtAt = 1_763_000_000_000L)
        val fresh = (store().load() as CatalogueStatus.Ready).catalogue
        assertEquals(2, fresh.feeds.size)
        assertEquals(Instant.ofEpochMilli(1_763_000_000_000L), fresh.builtAt)

        // The asset gone (it cannot be, but the installed copy would still serve).
        stamp = "v4"
        asset.delete()
        assertTrue(store().load() is CatalogueStatus.Ready)
    }

    @Test
    fun aLoadedCatalogueIsRemembered() = runTest {
        writeAsset()
        val store = store()
        val first = store.load()
        assertTrue(first === store.load())
        assertEquals(1, opens)
    }
}

package net.tgnra.morningissue.kobo

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.images.ImageFiles
import net.tgnra.morningissue.data.images.TestImages
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * [KoboImages] end to end: Android's decoder under Robolectric's native graphics (the real
 * Skia `BitmapFactory`, built for the host), a local web server for the internet, and real
 * files for the image store and the cache. What comes out is read back with the JDK's ImageIO.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KoboImagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val routes = Routes()
    private lateinit var store: ImageFiles
    private lateinit var cache: File

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        store = ImageFiles(File(folder.root, "images"))
        store.directory.mkdirs()
        cache = File(folder.root, "cache")
    }

    @After
    fun tearDown() = server.close()

    private fun images(maxCacheBytes: Long = KoboImageConfig.CACHE_MAX_BYTES) =
        KoboImages(store::fileFor, { OkHttpClient() }, cache, maxCacheBytes = maxCacheBytes)

    private fun serve(path: String, bytes: ByteArray): String {
        routes[path] = { response(bytes) }
        return server.url(path).toString()
    }

    private fun decode(image: PreparedImage): BufferedImage = ImageIO.read(ByteArrayInputStream(image.bytes)) ?: error("ImageIO cannot read it")

    @Test
    fun theStoresFileIsUsedWithoutTheNetwork() = runBlocking {
        val url = server.url("/stored.jpg").toString()
        store.fileFor(url).writeBytes(TestImages.jpeg(TestImages.photo(800, 600)))

        val prepared = images().prepare(url, 1264)!!

        assertEquals(GreyFormat.JPEG, prepared.format)
        val decoded = decode(prepared)
        // Never enlarged.
        assertEquals(800 to 600, decoded.width to decoded.height)
        assertEquals(1, decoded.raster.numBands)
        assertEquals(0, routes.hits("/stored.jpg"))
    }

    @Test
    fun anImageTheStoreLacksIsFetchedAndScaledToTheWidth() = runBlocking {
        val url = serve("/big.png", TestImages.png(TestImages.photo(2000, 1500)))

        val prepared = images().prepare(url, 1264)!!

        assertEquals(GreyFormat.JPEG, prepared.format)
        assertEquals(1264 to 948, decode(prepared).let { it.width to it.height })
        assertEquals(1, routes.hits("/big.png"))
        // The photo's top-left quarter is red, darker in grey than the blue-green rest: still the right way up.
        val grey = decode(prepared).raster
        assertTrue(grey.getSample(100, 100, 0) < grey.getSample(1100, 800, 0) + 40)
    }

    @Test
    fun aDrawingIsAPngOnWhite() = runBlocking {
        val url = serve("/chart.png", TestImages.png(TestImages.drawing(400, 200)))

        val prepared = images().prepare(url, 1264)!!

        assertEquals(GreyFormat.PNG, prepared.format)
        val decoded = decode(prepared)
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, decoded.type)
        // The transparent right half is flattened onto white.
        assertEquals(255, decoded.raster.getSample(300, 100, 0))
        // The blue squares are dark grey.
        assertTrue(decoded.raster.getSample(1, 1, 0) < 100)
    }

    @Test
    fun whatCannotBeGotOrDecodedIsNull() = runBlocking {
        val images = images()
        assertNull(images.prepare(server.url("/missing.jpg").toString(), 1264))
        assertNull(images.prepare(serve("/page", TestImages.HTML.toByteArray()), 1264))
        assertNull(images.prepare(serve("/drawn", TestImages.SVG.toByteArray()), 1264))
        assertNull(images.prepare(serve("/broken.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)), 1264))
        // SVG by its address is refused without a download; so is anything that is not a web address.
        val svg = serve("/logo.svg", TestImages.SVG.toByteArray())
        assertNull(images.prepare(svg, 1264))
        assertEquals(0, routes.hits("/logo.svg"))
        assertNull(images.prepare("data:image/png;base64,AAAA", 1264))
        // A failure is remembered: asked again, the phone does not go back to the site.
        assertNull(images.prepare(server.url("/missing.jpg").toString(), 1072))
        assertEquals(1, routes.hits("/missing.jpg"))
    }

    @Test
    fun aPreparedImageComesFromTheCacheAfterwards() = runBlocking {
        val url = serve("/photo.jpg", TestImages.jpeg(TestImages.photo(1600, 900)))
        val first = images().prepare(url, 1264)!!
        assertTrue(images().isCached(url, 1264))
        assertTrue(!images().isCached(url, 1072))

        // A new object, as at the next opening of the Sync screen, with the site gone.
        routes.clear()
        val again = images().prepare(url, 1264)!!
        assertEquals(first.bytes.toList(), again.bytes.toList())
        assertEquals(0, routes.hits("/photo.jpg"))
    }

    @Test
    fun eachWidthIsPreparedAndCachedOnItsOwn() = runBlocking {
        val url = serve("/wide.jpg", TestImages.jpeg(TestImages.photo(1600, 800)))
        val images = images()
        assertEquals(1264, decode(images.prepare(url, KoboImagePlan.width(null))!!).width)
        assertEquals(1072, decode(images.prepare(url, KoboImagePlan.width("1072"))!!).width)
        assertEquals(KoboImageConfig.MIN_WIDTH, decode(images.prepare(url, KoboImagePlan.width("3"))!!).width)
        assertTrue(images.isCached(url, 1264) && images.isCached(url, 1072))
    }

    @Test
    fun theCacheKeepsToItsCapDroppingTheLeastRecentlyUsed() = runBlocking {
        val urls = (1..4).map { serve("/p$it.jpg", TestImages.jpeg(TestImages.photo(600, 400, seed = it))) }
        val size = images().prepare(urls[0], 600)!!.bytes.size.toLong()
        val images = images(maxCacheBytes = size * 5 / 2)
        File(cache, KoboImagePlan.cacheKey(urls[0], 600)).setLastModified(System.currentTimeMillis() - 60_000)
        images.prepare(urls[1], 600)
        images.prepare(urls[2], 600)
        val kept = urls.take(3).map { images.isCached(it, 600) }
        assertEquals(listOf(false, true, true), kept)
    }

    @Test
    fun twoRequestsForOneImageShareOneDownload() = runBlocking {
        val bytes = TestImages.jpeg(TestImages.photo(900, 600))
        val release = CountDownLatch(1)
        routes["/slow.jpg"] = {
            release.await(5, TimeUnit.SECONDS)
            response(bytes)
        }
        val url = server.url("/slow.jpg").toString()
        val images = images()
        val both = (1..2).map { async(kotlinx.coroutines.Dispatchers.IO) { images.prepare(url, 1264) } }
        Thread.sleep(300)
        release.countDown()
        val results = both.awaitAll()
        assertNotNull(results[0])
        assertEquals(results[0]!!.bytes.toList(), results[1]!!.bytes.toList())
        assertEquals(1, routes.hits("/slow.jpg"))
    }

    @Test
    fun theFirstFrameOfAnAnimationIsSent() = runBlocking {
        val url = serve("/moving.gif", TestImages.gif(frames = 3))
        val prepared = images().prepare(url, 1264)!!
        assertEquals(1 to 1, decode(prepared).let { it.width to it.height })
    }
}

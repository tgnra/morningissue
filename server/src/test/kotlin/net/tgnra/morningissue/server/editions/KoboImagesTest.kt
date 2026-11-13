package net.tgnra.morningissue.server.editions

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.KoboImageEntry
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.kobo.GreyFormat
import net.tgnra.morningissue.kobo.KoboImageConfig
import net.tgnra.morningissue.kobo.KoboImagePlan
import net.tgnra.morningissue.kobo.PreparedImage
import net.tgnra.morningissue.server.sync.SyncTestData
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Paths a [MockWebServer] answers, counting the requests for each. */
class Routes : Dispatcher() {
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    operator fun set(path: String, answer: () -> MockResponse) {
        routes[path] = answer
    }

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    fun clear() = routes.clear()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        return routes[path]?.invoke() ?: MockResponse.Builder().code(404).build()
    }
}

fun response(bytes: ByteArray): MockResponse = MockResponse.Builder().body(Buffer().write(bytes)).build()

/** Images made in the test with java.awt, so that nothing downloaded is needed. */
object TestImages {
    /**
     * A picture with the statistics of a photograph. Its top-left quarter is distinctly red, so
     * a test can tell which way up it is.
     */
    fun photo(width: Int, height: Int, seed: Int = 1, noise: Int = 10): BufferedImage {
        val random = Random(seed)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x.toFloat() / width
                val v = y.toFloat() / height
                val wave = (sin(u * 9f + seed) * sin(v * 7f) * 40f).toInt()
                val n = random.nextInt(-noise, noise + 1)
                val corner = x < width / 2 && y < height / 2
                val r = ((if (corner) 200 else 60) + u * 50f + wave + n).toInt().coerceIn(0, 255)
                val g = (90 + v * 110f + wave + n).toInt().coerceIn(0, 255)
                val b = ((if (corner) 40 else 150) + (1 - u) * 60f - wave + n).toInt().coerceIn(0, 255)
                image.setRGB(x, y, Color(r, g, b).rgb)
            }
        }
        return image
    }

    /** A flat two-colour drawing with a transparent right half. */
    fun drawing(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = when {
                    x >= width / 2 -> 0
                    (x / 8 + y / 8) % 2 == 0 -> Color(20, 60, 160).rgb
                    else -> Color.WHITE.rgb
                }
                image.setRGB(x, y, argb)
            }
        }
        return image
    }

    /** A picture whose left half is black and right half white. */
    fun halves(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, if (x < width / 2) Color.BLACK.rgb else Color.WHITE.rgb)
        return image
    }

    fun jpeg(image: BufferedImage): ByteArray = encode(image, "jpg")

    fun png(image: BufferedImage): ByteArray = encode(image, "png")

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()

    /** [jpeg] with an EXIF APP1 segment giving [orientation], right after the start marker. */
    fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** A GIF with [frames] images of one pixel. */
    fun gif(frames: Int): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray())
        write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0))
        write(byteArrayOf(-1, -1, -1, 0, 0, 0))
        repeat(frames) {
            write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            write(byteArrayOf(2, 2, 0x44, 0x01, 0))
        }
        write(0x3B)
    }.toByteArray()

    const val SVG = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="#c00"/></svg>"""

    const val HTML = "<!DOCTYPE html><html><body><p>Not found</p><svg></svg></body></html>"
}

/**
 * [KoboImages] end to end on the JVM: ImageIO (with TwelveMonkeys) decoding, a local web server
 * for the internet and real files for the cache; what comes out is read back with ImageIO. The
 * app's `KoboImagesTest`, less the image store (the server keeps none), plus EXIF orientation
 * and the `POST /kobo/images` zip.
 */
class KoboImagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val routes = Routes()
    private lateinit var cache: File
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        cache = File(folder.root, "cache")
    }

    @After
    fun tearDown() = server.close()

    private fun images(maxCacheBytes: Long = KoboImageConfig.CACHE_MAX_BYTES) =
        KoboImages({ OkHttpClient() }, cache, maxCacheBytes = maxCacheBytes, now = { clock })

    private fun serve(path: String, bytes: ByteArray): String {
        routes[path] = { response(bytes) }
        return server.url(path).toString()
    }

    private fun decode(image: PreparedImage): BufferedImage = ImageIO.read(ByteArrayInputStream(image.bytes)) ?: error("ImageIO cannot read it")

    @Test
    fun anImageIsFetchedAndScaledToTheWidth() = runBlocking {
        val url = serve("/big.png", TestImages.png(TestImages.photo(2000, 1500)))

        val prepared = images().prepare(url, 1264)!!

        assertEquals(GreyFormat.JPEG, prepared.format)
        val decoded = decode(prepared)
        assertEquals(1264 to 948, decoded.width to decoded.height)
        assertEquals(1, decoded.raster.numBands)
        assertEquals(1, routes.hits("/big.png"))
        // The photo's top-left quarter is red, darker in grey than the blue-green rest: still the right way up.
        val grey = decoded.raster
        assertTrue(grey.getSample(100, 100, 0) < grey.getSample(1100, 800, 0) + 40)
    }

    @Test
    fun aSmallImageIsNeverEnlarged() = runBlocking {
        val url = serve("/small.jpg", TestImages.jpeg(TestImages.photo(800, 600)))
        val decoded = decode(images().prepare(url, 1264)!!)
        assertEquals(800 to 600, decoded.width to decoded.height)
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
    fun theExifOrientationIsApplied() = runBlocking {
        // Black on the left, as stored; orientation 6 means the camera was turned a quarter clockwise.
        val stored = TestImages.jpeg(TestImages.halves(400, 200))
        val turned = TestImages.withOrientation(stored, 6)
        val file = File(folder.root, "turned.jpg").apply { writeBytes(turned) }
        assertEquals(6, ExifOrientation.of(file))
        assertEquals(1, ExifOrientation.of(File(folder.root, "plain.jpg").apply { writeBytes(stored) }))

        val decoded = decode(images().prepare(serve("/turned.jpg", turned), 1264)!!)
        assertEquals(200 to 400, decoded.width to decoded.height)
        // Turned a quarter clockwise, the black half is at the top.
        assertTrue(decoded.raster.getSample(100, 50, 0) < 60)
        assertTrue(decoded.raster.getSample(100, 350, 0) > 200)
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
        // A failure is remembered: asked again, the server does not go back to the site.
        assertNull(images.prepare(server.url("/missing.jpg").toString(), 1072))
        assertEquals(1, routes.hits("/missing.jpg"))
        // Until the memory runs out.
        clock += 16 * 60 * 1000L
        assertNull(images.prepare(server.url("/missing.jpg").toString(), 1072))
        assertEquals(2, routes.hits("/missing.jpg"))
    }

    @Test
    fun aPreparedImageComesFromTheCacheAfterwards() = runBlocking {
        val url = serve("/photo.jpg", TestImages.jpeg(TestImages.photo(1600, 900)))
        val first = images().prepare(url, 1264)!!
        assertTrue(images().isCached(url, 1264))
        assertTrue(!images().isCached(url, 1072))

        // A new object, as after a restart, with the site gone.
        routes.clear()
        val again = images().prepare(url, 1264)!!
        assertEquals(first.bytes.toList(), again.bytes.toList())
        assertEquals(1, routes.hits("/photo.jpg"))
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
    fun severalWidthsComeFromOneDownload() = runBlocking {
        val url = serve("/both.jpg", TestImages.jpeg(TestImages.photo(1600, 800)))
        val prepared = images().prepare(url, listOf(1264, 1072))
        assertEquals(setOf(1264, 1072), prepared.keys)
        assertEquals(1072, decode(prepared.getValue(1072)).width)
        assertEquals(1, routes.hits("/both.jpg"))
    }

    @Test
    fun theCacheKeepsToItsCapDroppingTheLeastRecentlyUsed() = runBlocking {
        val urls = (1..4).map { serve("/p$it.jpg", TestImages.jpeg(TestImages.photo(600, 400, seed = it))) }
        val size = images().prepare(urls[0], 600)!!.bytes.size.toLong()
        val images = images(maxCacheBytes = size * 5 / 2)
        File(File(cache, "prepared"), KoboImagePlan.cacheKey(urls[0], 600)).setLastModified(System.currentTimeMillis() - 60_000)
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
        val both = (1..2).map { async(Dispatchers.IO) { images.prepare(url, 1264) } }
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

    @Test
    fun theZipHoldsEachPictureOnceAndAnIndex() = runBlocking {
        val world = TestWorld(java.time.Instant.parse("2026-11-11T07:00:00Z"))
        try {
            val images = images()
            val service = KoboImageServiceImpl(world.db, images, EditionBundles(world.db, world.clock))
            val photo = serve("/photo.jpg", TestImages.jpeg(TestImages.photo(1600, 900)))
            val chart = serve("/chart.png", TestImages.png(TestImages.drawing(400, 200)))
            val missing = server.url("/gone.jpg").toString()
            val out = ByteArrayOutputStream()
            service.writeZip(1, KoboImagesRequest(width = 1072, urls = listOf(photo, chart, missing, photo)), out)

            val entries = LinkedHashMap<String, Pair<ZipEntry, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = it to zip.readBytes() }
            }
            val index = ApiJson.json.decodeFromString(ListSerializer(KoboImageEntry.serializer()), String(entries.getValue("images.json").second))
            // The missing one is left out; the photo, named twice, is there once.
            assertEquals(listOf(photo, chart), index.map { it.url })
            assertEquals(index.map { it.file } + "images.json", entries.keys.toList())
            assertTrue(entries.values.all { it.first.method == ZipEntry.STORED })
            val first = index[0]
            assertEquals(KoboImageServiceImpl.fileName(photo, GreyFormat.JPEG), first.file)
            assertTrue(first.file.matches(Regex("images/[0-9a-f]{64}\\.jpg")))
            assertEquals("image/jpeg", first.type)
            assertEquals(1072 to 603, first.width to first.height)
            val read = ImageIO.read(ByteArrayInputStream(entries.getValue(first.file).second))
            assertEquals(first.width to first.height, read.width to read.height)
            assertEquals("image/png", index[1].type)
            assertTrue(index[1].file.endsWith(".png"))
            assertEquals(400 to 200, index[1].width to index[1].height)
        } finally {
            world.close()
        }
    }

    @Test
    fun anEditionsPicturesAreWarmedAtEachWidth() = runBlocking {
        val world = TestWorld(java.time.Instant.parse("2026-11-11T07:00:00Z"))
        try {
            val images = images()
            val ids = SyncTestData.seed(world)
            // The seeded lead names garden.example; serve its pictures here instead.
            val lead = serve("/img/lead.jpg", TestImages.jpeg(TestImages.photo(1600, 900)))
            val body = serve("/img/frost.jpg", TestImages.jpeg(TestImages.photo(1200, 800)))
            world.exec(
                "UPDATE article SET imageUrl = '$lead' WHERE id = ${ids.lead}",
                "UPDATE article_content SET blocksJson = replace(blocksJson, 'https://garden.example/img/frost.jpg', '$body') WHERE articleId = ${ids.lead}",
            )
            KoboImageServiceImpl(world.db, images, EditionBundles(world.db, world.clock)).prepare(ids.unreleased, setOf(1264))
            // The unreleased edition carries only the index story, which has no pictures.
            assertTrue(!images.isCached(lead, 1264))
            KoboImageServiceImpl(world.db, images, EditionBundles(world.db, world.clock)).prepare(ids.edition, setOf(1264, 1072, 5))
            for (url in listOf(lead, body)) {
                assertTrue(images.isCached(url, 1264))
                assertTrue(images.isCached(url, 1072))
                assertTrue(images.isCached(url, KoboImageConfig.MIN_WIDTH))
            }
            assertEquals(1, routes.hits("/img/lead.jpg"))
        } finally {
            world.close()
        }
    }
}

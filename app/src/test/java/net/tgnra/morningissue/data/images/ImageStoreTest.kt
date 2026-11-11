package net.tgnra.morningissue.data.images

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.db.feed
import net.tgnra.morningissue.data.repository.RoomFeedRepository
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The image store against a real database, real files and a local web server. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = MutableClock(T0)
    private val server = MockWebServer()
    private val routes = Routes()
    private lateinit var db: MorningIssueDatabase
    private lateinit var files: ImageFiles
    private lateinit var store: FileImageStore
    private var feedId = 0L

    private val photo = TestImages.jpeg(TestImages.photo(2000, 1500))

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        files = ImageFiles(File(folder.root, "images"))
        store = FileImageStore(db, files, { OkHttpClient() }, clock)
        feedId = db.feedDao().insert(feed())
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun serve(path: String, bytes: ByteArray): String {
        routes[path] = { response(bytes) }
        return url(path)
    }

    private suspend fun post(guid: String, lead: String? = null, vararg body: ContentBlock, day: Int = 0, toFeed: Long = feedId): Long =
        db.articleDao().insertNew(
            article(toFeed, guid, firstSeenAt = T0.plus(Duration.ofDays(day.toLong()))).copy(imageUrl = lead),
            body.takeIf { it.isNotEmpty() }?.let { ContentBlocks.encode(it.toList()) },
            "text",
        )!!

    private fun image(url: String, width: Int? = null) = ContentBlock.Image(url = url, width = width)

    private fun storedFiles(): List<File> = files.directory.listFiles().orEmpty().filter { it.isFile }

    @Test
    fun aPhotographIsDownloadedReencodedSmallerAndRecorded() = runBlocking<Unit> {
        val lead = serve("/lead.jpg", photo)
        val inBody = serve("/body.jpg", TestImages.jpeg(TestImages.photo(1600, 900, seed = 2)))
        val id = post("a", lead, ContentBlock.Paragraph(RichText("Words")), image(inBody), image(lead))
        clock.now = T0.plusSeconds(60)

        val report = store.storeForArticles(listOf(id))

        assertEquals(2, report.stored)
        assertEquals(0, report.keptUnchanged)
        assertEquals(0, report.skipped)
        val row = db.storedImageDao().get(lead)!!
        assertEquals(id, row.articleId)
        assertEquals(files.nameFor(lead), row.fileName)
        assertEquals(photo.size.toLong(), row.originalBytes)
        assertEquals(1280 to 960, row.width to row.height)
        assertEquals(T0.plusSeconds(60), row.storedAt)
        val file = files.fileFor(lead)
        assertEquals(row.byteSize, file.length())
        assertTrue("${row.byteSize} of ${row.originalBytes}", row.byteSize < row.originalBytes / 3)
        assertEquals(ImageKind.WEBP, ImageFormats.kindOf(file.readBytes().copyOf(32)))
        assertEquals(1280, TestImages.decode(file).width)
        // The picture named twice, as lead and in the body, is stored once.
        assertEquals(2, db.storedImageDao().count())
        assertEquals(2, storedFiles().size)
        assertEquals(report.bytesStored, store.totalBytes())
        assertEquals(storedFiles().sumOf { it.length() }, store.totalBytes())
        // Nothing is left behind in the temporary directory.
        assertTrue(files.tempDirectory.listFiles().orEmpty().isEmpty())

        // A second call finds them and asks the server for nothing.
        val again = store.storeForArticles(listOf(id))
        assertEquals(ImageStoreReport(alreadyStored = 2), again)
        assertEquals(1, routes.hits("/lead.jpg"))
        assertEquals(1, routes.hits("/body.jpg"))
    }

    @Test
    fun theOriginalIsKeptWhenReencodingWouldBeLarger() = runBlocking<Unit> {
        // Already compressed harder than the store would: re-encoding its artefacts costs more.
        val small = TestImages.jpeg(TestImages.photo(640, 480), quality = 12)
        val lead = serve("/small.jpg", small)
        val id = post("a", lead)

        val report = store.storeForArticles(listOf(id))

        assertEquals(1, report.stored)
        assertEquals(1, report.keptUnchanged)
        assertArrayEquals(small, files.fileFor(lead).readBytes())
        val row = db.storedImageDao().get(lead)!!
        assertEquals(small.size.toLong(), row.byteSize)
        assertEquals(row.byteSize, row.originalBytes)
        assertEquals(640 to 480, row.width to row.height)
    }

    @Test
    fun animationsAndDrawingsAreStoredAsTheyCameAndLargeOnesNotAtAll() = runBlocking<Unit> {
        val gif = TestImages.gif(frames = 3, paddingBytes = 5000)
        val moving = serve("/moving.gif", gif)
        val drawing = serve("/drawing.svg", TestImages.SVG.toByteArray())
        val huge = serve("/huge.gif", TestImages.gif(frames = 2, paddingBytes = (ImageStoreConfig.MAX_UNCHANGED_BYTES / 2).toInt() + 1000))
        // A single-frame GIF is a still picture and is re-encoded like one.
        val still = serve("/still.gif", TestImages.gif(frames = 1))
        val id = post("a", moving, image(drawing), image(huge), image(still))

        val report = store.storeForArticles(listOf(id))

        assertEquals(3, report.stored)
        assertEquals(1, report.skipped)
        assertArrayEquals(gif, files.fileFor(moving).readBytes())
        assertArrayEquals(TestImages.SVG.toByteArray(), files.fileFor(drawing).readBytes())
        assertEquals(0, db.storedImageDao().get(moving)!!.width)
        assertFalse(files.fileFor(huge).exists())
        assertNull(db.storedImageDao().get(huge))
        assertEquals(1, db.storedImageDao().get(still)!!.width)
    }

    @Test
    fun aFailedImageIsSkippedAndTheOthersAreStillStored() = runBlocking<Unit> {
        val good = serve("/good.jpg", photo)
        routes["/page.jpg"] = { response(TestImages.HTML) }
        routes["/broken.jpg"] = { response(photo.copyOf(200)) }
        routes["/dropped.jpg"] = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        val id = post(
            "a", url("/missing.jpg"),
            image(url("/page.jpg")), image(url("/broken.jpg")), image(url("/dropped.jpg")), image("data:image/png;base64,AAAA"),
            image("ftp://example.com/a.png"), image(good),
        )

        val report = store.storeForArticles(listOf(id, 9999L))

        assertEquals(1, report.stored)
        assertEquals(4, report.skipped)
        assertEquals(listOf(files.nameFor(good)), storedFiles().map { it.name })
        assertEquals(1, db.storedImageDao().count())
        assertTrue(files.tempDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun leadImagesComeFirstAndIconsAndTheTailOfAPhotoEssayAreLeftOut() = runBlocking<Unit> {
        val tiny = TestImages.jpeg(TestImages.photo(16, 16))
        val lead = serve("/lead.jpg", tiny)
        val essay = (1..ImageStoreConfig.MAX_BODY_IMAGES_PER_ARTICLE + 5).map { serve("/essay$it.jpg", tiny) }
        val icon = serve("/icon.jpg", tiny)
        val a = post("a", lead, image(icon, width = 16), *essay.map { image(it) }.toTypedArray())

        val report = store.storeForArticles(listOf(a))

        assertEquals(1 + ImageStoreConfig.MAX_BODY_IMAGES_PER_ARTICLE, report.stored)
        assertNull(db.storedImageDao().get(icon))
        assertNull(db.storedImageDao().get(essay.last()))
        assertEquals(0, routes.hits("/icon.jpg"))
    }

    @Test
    fun bytesAlreadyOnTheDeviceAreUsedInsteadOfASecondDownload() = runBlocking<Unit> {
        val lead = url("/cached.jpg")
        val forgotten = ArrayList<String>()
        val cached = object : OriginalSource {
            override fun copyTo(url: String, target: File): Boolean {
                if (url != lead) return false
                target.writeBytes(photo)
                return true
            }

            override fun forget(url: String) {
                forgotten += url
            }
        }
        val fromCache = FileImageStore(db, files, { OkHttpClient() }, clock, originals = cached)
        val id = post("a", lead)

        assertEquals(1, fromCache.storeForArticles(listOf(id)).stored)
        assertEquals(0, routes.hits("/cached.jpg"))
        assertEquals(1280, db.storedImageDao().get(lead)!!.width)
        // The cache is told it can drop its copy.
        assertEquals(listOf(lead), forgotten)
    }

    @Test
    fun theSweepRemovesFilesWithoutARowAndRowsWithoutAFile() = runBlocking<Unit> {
        val kept = serve("/kept.jpg", photo)
        val lost = serve("/lost.jpg", photo)
        val id = post("a", kept, image(lost))
        store.storeForArticles(listOf(id))
        files.fileFor(lost).delete()
        val stray = File(files.directory, "0123abcd").apply { writeBytes(photo) }
        val fresh = files.newTemp().apply { writeText("in flight") }
        val stale = files.newTemp().apply {
            writeText("abandoned")
            setLastModified(System.currentTimeMillis() - 2 * ImageStoreConfig.TEMP_MAX_AGE_MILLIS)
        }

        assertEquals(ImageSweepReport(rowsWithoutFile = 1, filesWithoutRow = 1), store.sweep())

        assertNull(db.storedImageDao().get(lost))
        assertFalse(stray.exists())
        assertTrue(files.fileFor(kept).isFile)
        assertEquals(1, db.storedImageDao().count())
        // A download in progress is left alone; one abandoned by a killed process is not.
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
        assertEquals(ImageSweepReport(), store.sweep())
        // The lost one is simply fetched again when asked for.
        assertEquals(1, store.storeForArticles(listOf(id)).stored)
    }

    @Test
    fun removingAFeedDeletesItsFilesAsWellAsItsRows() = runBlocking<Unit> {
        val other = db.feedDao().insert(feed("https://other.example/feed.xml"))
        val gone = serve("/gone.jpg", photo)
        val stays = serve("/stays.jpg", photo)
        val a = post("a", gone, image(serve("/gone2.jpg", photo)))
        val b = post("b", stays, toFeed = other)
        store.storeForArticles(listOf(a, b))
        assertEquals(3, storedFiles().size)

        RoomFeedRepository(db, clock, images = store).removeFeed(feedId)

        assertEquals(listOf(files.nameFor(stays)), storedFiles().map { it.name })
        assertEquals(1, db.storedImageDao().count())
        assertEquals(storedFiles().single().length(), store.totalBytes())
        // Nothing for the sweep to find.
        assertEquals(ImageSweepReport(), store.sweep())
    }

    @Test
    fun trimmingTakesTheOldestPostsImagesFirstAndNeverALikedOrSavedPosts() = runBlocking<Unit> {
        val ids = (1..5).map { day -> post("p$day", serve("/p$day.jpg", TestImages.jpeg(TestImages.photo(600, 400, seed = day))), day = day) }
        store.storeForArticles(ids)
        db.engagementDao().setLiked(ids[0], T0)
        db.engagementDao().setSaved(ids[1], T0)
        val sizes = ids.map { db.storedImageDao().get(url("/p${ids.indexOf(it) + 1}.jpg"))!!.byteSize }
        val protectFrom = T0.plus(Duration.ofDays(5))

        // One byte asked for: the oldest image that may go, the third post's, goes whole.
        val first = store.trim(protectFrom, emptyList(), bytesToFree = 1)
        assertEquals(ImageTrimReport(1, sizes[2]), first)
        assertFalse(files.fileFor(url("/p3.jpg")).exists())
        assertNull(db.storedImageDao().get(url("/p3.jpg")))

        // Everything asked for: the fourth goes; Liked, saved and the post waiting for the next edition stay.
        val rest = store.trim(protectFrom, emptyList(), bytesToFree = Long.MAX_VALUE)
        assertEquals(ImageTrimReport(1, sizes[3]), rest)
        assertEquals(setOf("/p1.jpg", "/p2.jpg", "/p5.jpg").map { files.nameFor(url(it)) }.toSet(), storedFiles().map { it.name }.toSet())
        assertEquals(3, db.storedImageDao().count())
        assertEquals(ImageSweepReport(), store.sweep())
    }
}

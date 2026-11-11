package net.tgnra.morningissue.kobo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageFiles
import net.tgnra.morningissue.data.images.TestImages
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** [KoboSync.prewarm]: saved articles' text first fetched, then every offered image prepared at each Kobo's width. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KoboPrewarmTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val routes = Routes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: MorningIssueDatabase
    private lateinit var ids: Seeded
    private lateinit var store: ImageFiles
    private lateinit var widths: KoboImageWidths

    /** Stands in for the reader's full-text fetch: stores a body with a picture for each article asked for. */
    private inner class FakeFullText(private val pictureUrl: String) : FullTextFetcher {
        val asked: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override suspend fun ensureFullText(articleId: Long): FullTextResult {
            asked += articleId
            val blocks = listOf(ContentBlock.Paragraph(RichText("Fetched.")), ContentBlock.Image(url = pictureUrl))
            db.articleDao().storeContent(articleId, ContentBlocks.encode(blocks), "Fetched.", 1, ContentSource.FETCHED, NOW)
            return FullTextResult.Stored
        }
    }

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = routes
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        ids = KoboTestData.seed(db)
        store = ImageFiles(File(folder.root, "images")).also { it.directory.mkdirs() }
        widths = KoboImageWidths(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") })
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        scope.cancel()
    }

    /** Puts the seeded pictures in the store, so that nothing here reaches for the internet. */
    private fun storeSeededPictures() {
        for (url in listOf("https://garden.example/img/lead.jpg", "https://garden.example/img/frost.jpg")) {
            store.fileFor(url).writeBytes(TestImages.jpeg(TestImages.photo(300, 200)))
        }
    }

    private fun images() = KoboImages(store::fileFor, { OkHttpClient() }, File(folder.root, "cache"))

    private fun sync(fullText: FullTextFetcher) = KoboSync(
        db, InstallId(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "id.preferences_pb") }),
        ::images, fullText, widths, NOW_CLOCK, deviceName = { "Pixel 9" },
    )

    @Test
    fun everyOfferedImageIsPreparedAtEachKobosWidth() = runBlocking {
        storeSeededPictures()
        // The lead's picture is in the store; the one in its body is on the web.
        val body = serve("/frost.jpg")
        db.articleDao().storeContent(
            ids.lead, ContentBlocks.encode(listOf(ContentBlock.Image(url = body))), "", 0, ContentSource.FETCHED, NOW,
        )
        // The top story is saved without text: its fetched body brings one more picture.
        db.engagementDao().setSaved(ids.top, RELEASE.plusSeconds(60))
        val saved = serve("/saved.jpg")
        val fullText = FakeFullText(saved)
        // Two Kobos: one that asked at 1072 last time, one that has never asked.
        db.syncPeerDao().pair("clara", "Kobo Clara", PeerTokens.hash("a"), RELEASE)
        db.syncPeerDao().pair("libra", "Kobo Libra", PeerTokens.hash("b"), RELEASE)
        widths.record("clara", 1072)

        val sync = sync(fullText)
        val images = images()
        withTimeout(30_000) { sync.prewarm(images) }

        assertEquals(listOf(ids.top), fullText.asked)
        for (url in listOf("https://garden.example/img/lead.jpg", body, saved)) {
            for (w in listOf(1072, 1264)) assertTrue("$url at $w", images.isCached(url, w))
        }
        // One download for both widths.
        assertEquals(1, routes.hits("/frost.jpg"))
        assertNull(sync.status.value.images)
    }

    @Test
    fun aSavedArticleWithFetchedTextIsNotFetchedAgain() = runBlocking {
        storeSeededPictures()
        db.engagementDao().setSaved(ids.section, RELEASE.plusSeconds(60))
        db.engagementDao().setSaved(ids.top, RELEASE.plusSeconds(120))
        db.articleDao().storeContent(ids.section, ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText("Dry.")))), "Dry.", 1, ContentSource.FETCHED, NOW)
        val fullText = FakeFullText(server.url("/none.jpg").toString())
        withTimeout(30_000) { sync(fullText).prewarm(images()) }
        assertEquals(listOf(ids.top), fullText.asked)
    }

    @Test
    fun theProgressCountsUpAndItStopsWhenCancelled() = runBlocking {
        storeSeededPictures()
        val release = CountDownLatch(1)
        routes["/frost.jpg"] = {
            release.await(10, TimeUnit.SECONDS)
            response(TestImages.jpeg(TestImages.photo(400, 300)))
        }
        val body = server.url("/frost.jpg").toString()
        db.articleDao().storeContent(ids.lead, ContentBlocks.encode(listOf(ContentBlock.Image(url = body))), "", 0, ContentSource.FETCHED, NOW)
        val sync = sync(FakeFullText(body))
        val job = launch(Dispatchers.IO) { sync.prewarm(images()) }
        withTimeout(10_000) {
            while (sync.status.value.images == null) delay(20)
        }
        // The lead's picture (not on the server) and the body's: two to prepare.
        assertEquals(2, sync.status.value.images!!.total)
        withTimeout(5_000) { job.cancelAndJoin() }
        release.countDown()
        assertTrue(job.isCancelled)
    }

    @Test
    fun theWidthsAreKeptPerDevice() = runBlocking {
        assertEquals(emptyMap<String, Int>(), widths.all())
        widths.record("clara", 1072)
        widths.record("libra", 1264)
        widths.record("clara", 1448)
        assertEquals(mapOf("clara" to 1448, "libra" to 1264), widths.all())
        assertEquals(mapOf("a=b" to 3), KoboImageWidths.parse(KoboImageWidths.format(mapOf("a=b" to 3))))
        assertEquals(mapOf("x" to 2), KoboImageWidths.parse("x=2\nbroken\n=4\ny=wide"))
        assertEquals(listOf(1072, 1264), KoboImageWidths.toPrepare(listOf("clara", "libra", "sage"), mapOf("clara" to 1072)))
        assertEquals(listOf(KoboImageConfig.DEFAULT_WIDTH), KoboImageWidths.toPrepare(emptyList(), mapOf("gone" to 1072)))
    }

    private fun serve(path: String): String {
        routes[path] = { response(TestImages.jpeg(TestImages.photo(1600, 900))) }
        return server.url(path).toString()
    }
}

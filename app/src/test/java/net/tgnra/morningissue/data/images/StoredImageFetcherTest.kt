package net.tgnra.morningissue.data.images

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.images.StoredImageFetcher.Companion.original
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** How display finds stored images: the app's loader, built as `DefaultAppContainer` builds it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoredImageFetcherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val routes = Routes()
    private lateinit var files: ImageFiles
    private lateinit var loader: ImageLoader

    /** What the site serves: 400 × 300. What the store holds for the same address: 200 × 150. */
    private val onTheSite = TestImages.jpeg(TestImages.photo(400, 300))
    private val inTheStore = TestImages.jpeg(TestImages.photo(200, 150))

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        routes["/picture.jpg"] = { response(onTheSite) }
        files = ImageFiles(File(folder.root, "images"))
        val client = OkHttpClient()
        loader = ImageLoader.Builder(context)
            .components {
                add(StoredImageFetcher.Factory(files))
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .build()
    }

    @After
    fun tearDown() {
        loader.shutdown()
        server.close()
    }

    private val url get() = server.url("/picture.jpg").toString()

    private fun store(url: String) {
        files.directory.mkdirs()
        files.fileFor(url).writeBytes(inTheStore)
    }

    private fun load(build: ImageRequest.Builder.() -> Unit = {}): SuccessResult = runBlocking {
        val result = loader.execute(ImageRequest.Builder(context).data(url).apply(build).build())
        if (result is ErrorResult) throw AssertionError("The image did not load", result.throwable)
        result as SuccessResult
    }

    @Test
    fun theStoredCopyIsShownAndTheNetworkIsNotAsked() {
        store(url)

        val result = load()

        assertEquals(200 to 150, result.image.width to result.image.height)
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals(0, routes.hits("/picture.jpg"))
    }

    @Test
    fun anImageThatIsNotStoredComesFromTheNetworkAsBefore() {
        // Another address is stored; this one is not.
        store(server.url("/other.jpg").toString())

        val result = load()

        assertEquals(400 to 300, result.image.width to result.image.height)
        assertEquals(DataSource.NETWORK, result.dataSource)
        assertEquals(1, routes.hits("/picture.jpg"))

        // Coil's own disk cache still serves it the second time.
        loader.memoryCache?.clear()
        assertEquals(DataSource.DISK, load().dataSource)
        assertEquals(1, routes.hits("/picture.jpg"))
    }

    @Test
    fun aRequestForTheOriginalPassesTheStoreByAndHasItsOwnCacheEntry() {
        store(url)
        val kept = load()

        val original = load { original() }

        assertEquals(400 to 300, original.image.width to original.image.height)
        assertEquals(1, routes.hits("/picture.jpg"))
        assertNotEquals(kept.memoryCacheKey, original.memoryCacheKey)
        // The stored copy is still what an ordinary request gets, from memory now.
        assertEquals(200, load().image.width)
    }

    @Test
    fun onlyWebAddressesAreLookedUp() {
        val local = folder.newFile("local.jpg").apply { writeBytes(onTheSite) }
        val result = runBlocking { loader.execute(ImageRequest.Builder(context).data(local).build()) }
        assertTrue(result is SuccessResult)
        assertEquals(400, (result as SuccessResult).image.width)
    }

    @Test
    fun aStoredImageThatHasBeenTrimmedAwayIsFetchedAgain() {
        store(url)
        assertEquals(200, load().image.width)
        files.fileFor(url).delete()
        loader.memoryCache?.clear()

        assertEquals(400, load().image.width)
        assertEquals(1, routes.hits("/picture.jpg"))
    }
}

package net.tgnra.morningissue.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.images.TestImages
import net.tgnra.morningissue.ui.components.Dither
import net.tgnra.morningissue.ui.components.ImageTone
import net.tgnra.morningissue.ui.components.ImageTreatment
import net.tgnra.morningissue.ui.components.ToneTransformation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The greyscale option through Coil: the request `PaperImage` makes for each tone, executed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageToneTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var loader: ImageLoader
    private lateinit var url: String

    @Before
    fun setUp() {
        loader = ImageLoader.Builder(context).build()
        url = folder.newFile("photo.jpg").apply { writeBytes(TestImages.jpeg(TestImages.photo(800, 600))) }.toURI().toString()
    }

    @After
    fun tearDown() = loader.shutdown()

    /** As a 300 × 200 slot on screen asks for it. */
    private fun load(tone: ImageTone, dither: Boolean = true, width: Int = 300, height: Int = 200): SuccessResult = runBlocking {
        // Cropped to fill its slot, as `PaperImage` shows most pictures.
        val request = ImageTreatment.request(context, url, tone, dither).size(width, height).scale(Scale.FILL).build()
        val result = loader.execute(request)
        if (result is ErrorResult) throw AssertionError("The image did not load", result.throwable)
        result as SuccessResult
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun isGrey(pixel: Int) = Color.red(pixel) == Color.green(pixel) && Color.red(pixel) == Color.blue(pixel)

    @Test
    fun colourIsThePlainRequestAndTheGreyTonesCarryTheTransformation() {
        val colour = ImageTreatment.request(context, url, ImageTone.COLOUR).build()
        assertEquals(ImageRequest.Builder(context).data(url).build(), colour)
        val grey = ImageTreatment.request(context, url, ImageTone.GREY).build()
        assertEquals(Precision.EXACT, grey.defined.precision)
        // Colour leaves the precision to `AsyncImage`, which decodes at a convenient size.
        assertNull(colour.defined.precision)
        assertNotEquals(colour, grey)
        // The same tone asked for again is the same request, so nothing is loaded twice.
        assertEquals(grey, ImageTreatment.request(context, url, ImageTone.GREY).build())
        assertNotEquals(grey, ImageTreatment.request(context, url, ImageTone.EINK).build())
        assertNotEquals(ToneTransformation(ImageTone.GREY).cacheKey, ToneTransformation(ImageTone.EINK).cacheKey)
        assertNotEquals(ToneTransformation(ImageTone.GREY).cacheKey, ToneTransformation(ImageTone.GREY, dither = false).cacheKey)
    }

    @Test
    fun switchingTheOptionGivesADifferentCacheEntrySoTheChangeShowsAtOnce() {
        val colour = load(ImageTone.COLOUR)
        val grey = load(ImageTone.GREY)
        val eink = load(ImageTone.EINK)

        val keys = listOf(colour.memoryCacheKey, grey.memoryCacheKey, eink.memoryCacheKey)
        assertTrue(keys.all { it != null })
        assertEquals(3, keys.toSet().size)
        assertFalse(colour.isPlaceholderCached)
        assertFalse(pixels(colour.image.toBitmap()).all(::isGrey))
        assertTrue(pixels(grey.image.toBitmap()).all(::isGrey))

        // Switched back: the colour picture is still in the cache under its own key, untouched.
        val back = load(ImageTone.COLOUR)
        assertEquals(colour.memoryCacheKey, back.memoryCacheKey)
        assertSame(colour.image.toBitmap(), back.image.toBitmap())
        assertEquals(grey.memoryCacheKey, load(ImageTone.GREY).memoryCacheKey)
    }

    @Test
    fun theDitherIsLaidOnThePixelsOfTheSizeShown() {
        // 800 × 600 into a 300 × 200 slot, cropped to fill: decoded 300 wide exactly, not at a
        // convenient power of two, so each dithered pixel is a screen pixel.
        val grey = load(ImageTone.GREY).image.toBitmap()
        assertEquals(300 to 225, grey.width to grey.height)
        val values = pixels(grey).map { Color.red(it) }
        assertTrue(values.all { it % 17 == 0 })
        assertTrue("several of the 16 levels are used: ${values.toSet()}", values.toSet().size in 4..Dither.LEVELS)
        // Colour is decoded as before, to whatever size is cheapest at or above the slot.
        assertTrue(load(ImageTone.COLOUR).image.width >= 300)
    }

    @Test
    fun einkKeepsItsRaisedContrastAndTheViewersToneIsNotDithered() {
        val grey = pixels(load(ImageTone.GREY).image.toBitmap()).map { Color.red(it) }
        val eink = pixels(load(ImageTone.EINK).image.toBitmap()).map { Color.red(it) }
        fun spread(values: List<Int>) = values.sortedBy { it }.let { it[it.size * 9 / 10] - it[it.size / 10] }
        assertTrue("contrast: ${spread(eink)} against ${spread(grey)}", spread(eink) > spread(grey))

        val smooth = ImageTreatment.request(context, url, ImageTone.GREY, dither = false).build()
        assertNull(smooth.defined.precision)
        val plain = pixels(load(ImageTone.GREY, dither = false).image.toBitmap())
        assertTrue(plain.all(::isGrey))
        assertTrue("continuous greys, not 16 levels", plain.map { Color.red(it) }.toSet().size > Dither.LEVELS)
    }
}

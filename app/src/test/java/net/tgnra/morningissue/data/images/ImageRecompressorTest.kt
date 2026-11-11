package net.tgnra.morningissue.data.images

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The recompressor against Robolectric's host build of Android's own decoders and encoders. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageRecompressorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val recompressor = ImageRecompressor(maxEdge = 1280, quality = 80)

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    private fun isWebp(file: File): Boolean = ImageFormats.kindOf(file.readBytes().copyOf(32)) == ImageKind.WEBP

    @Test
    fun aLargePhotographIsScaledToTheMaximumEdgeAndComesOutSmaller() {
        val source = file(TestImages.jpeg(TestImages.photo(4000, 1000)))
        val target = folder.newFile()

        val result = recompressor.recompress(source, target)!!

        assertEquals(1280, result.width)
        assertEquals(320, result.height)
        assertTrue(isWebp(target))
        val stored = TestImages.decode(target)
        assertEquals(1280 to 320, stored.width to stored.height)
        assertTrue("${target.length()} of ${source.length()}", target.length() < source.length() / 4)
    }

    @Test
    fun aSmallPictureIsNeverEnlarged() {
        val target = folder.newFile()
        val result = recompressor.recompress(file(TestImages.jpeg(TestImages.photo(300, 200))), target)!!
        assertEquals(300 to 200, result.width to result.height)
        assertEquals(300, TestImages.decode(target).width)
        // Exactly at the limit is left alone too.
        assertEquals(1280, recompressor.recompress(file(TestImages.jpeg(TestImages.photo(1280, 64))), folder.newFile())!!.width)
    }

    @Test
    fun theDecoderIsAskedForTheLargestReductionThatStillCoversTheMaximumEdge() {
        assertEquals(1, ImageRecompressor.sampleSize(1280, 720, 1280))
        assertEquals(1, ImageRecompressor.sampleSize(2559, 1000, 1280))
        assertEquals(2, ImageRecompressor.sampleSize(2560, 1000, 1280))
        // 40 megapixels: decoded at a quarter of each edge, 2.5 megapixels, never in full.
        assertEquals(4, ImageRecompressor.sampleSize(7300, 5500, 1280))
        assertEquals(4, ImageRecompressor.sampleSize(5500, 7300, 1280))
        assertEquals(16, ImageRecompressor.sampleSize(30000, 500, 1280))
        assertEquals(1, ImageRecompressor.sampleSize(10, 10, 1280))
    }

    @Test
    fun aPhotographTakenOnItsSideIsStoredUpright() {
        // 400 × 200 as stored by the camera, to be shown turned a quarter clockwise: 200 × 400,
        // with the red quarter, top-left in the file, at the top right.
        val source = file(TestImages.jpeg(TestImages.photo(400, 200, noise = 0)))
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val target = folder.newFile()

        val result = recompressor.recompress(source, target)!!

        assertEquals(200 to 400, result.width to result.height)
        val stored = TestImages.decode(target)
        assertEquals(200 to 400, stored.width to stored.height)
        assertTrue("top right is the red quarter", Color.red(stored.getPixel(150, 50)) > 150)
        assertTrue("top left is not", Color.red(stored.getPixel(50, 50)) < 150)
        assertTrue("bottom right is not", Color.red(stored.getPixel(150, 350)) < 150)
    }

    @Test
    fun transparencyIsKept() {
        val target = folder.newFile()
        assertNotNull(recompressor.recompress(file(TestImages.png(TestImages.drawing(200, 100))), target))
        assertTrue(isWebp(target))
        val stored = TestImages.decode(target)
        assertEquals(0, Color.alpha(stored.getPixel(150, 50)))
        assertEquals(255, Color.alpha(stored.getPixel(20, 50)))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid11TheOlderWebpFormatIsLossyAndKeepsTransparencyToo() {
        @Suppress("DEPRECATION")
        assertEquals(Bitmap.CompressFormat.WEBP, ImageRecompressor.webpFormat())
        val photo = file(TestImages.jpeg(TestImages.photo(2000, 1500)))
        val target = folder.newFile()
        val result = recompressor.recompress(photo, target)!!
        assertEquals(1280 to 960, result.width to result.height)
        assertTrue(isWebp(target))
        // Lossy: far smaller than the same pixels stored without loss would be.
        assertTrue("${target.length()}", target.length() < TestImages.png(TestImages.decode(target)).size / 3)

        val drawing = folder.newFile()
        assertNotNull(recompressor.recompress(file(TestImages.png(TestImages.drawing(200, 100))), drawing))
        assertEquals(0, Color.alpha(TestImages.decode(drawing).getPixel(150, 50)))
    }

    @Test
    fun theFormatGoesByTheAndroidVersion() {
        @Suppress("DEPRECATION")
        assertEquals(Bitmap.CompressFormat.WEBP, ImageRecompressor.webpFormat(29))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, ImageRecompressor.webpFormat(30))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, ImageRecompressor.webpFormat())
    }

    @Test
    fun whatCannotBeDecodedLeavesNoFile() {
        val target = File(folder.root, "out")
        assertNull(recompressor.recompress(file(TestImages.HTML.toByteArray()), target))
        assertNull(recompressor.recompress(file(TestImages.SVG.toByteArray()), target))
        assertNull(recompressor.recompress(file(TestImages.jpeg(TestImages.photo(64, 64)).copyOf(40)), target))
        assertFalse(target.exists())
    }
}

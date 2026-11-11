package net.tgnra.morningissue.data.images

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageFormatsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    private fun head(bytes: ByteArray) = bytes.copyOf(minOf(bytes.size, ImageFormats.HEAD_BYTES))

    @Test
    fun aFileIsWhatItsFirstBytesSayItIs() {
        assertEquals(ImageKind.JPEG, ImageFormats.kindOf(TestImages.jpeg(TestImages.photo(8, 8))))
        assertEquals(ImageKind.PNG, ImageFormats.kindOf(TestImages.png(TestImages.photo(8, 8))))
        assertEquals(ImageKind.GIF, ImageFormats.kindOf(TestImages.gif(1)))
        assertEquals(ImageKind.WEBP, ImageFormats.kindOf(TestImages.animatedWebpHeader()))
        assertEquals(ImageKind.SVG, ImageFormats.kindOf(TestImages.SVG.toByteArray()))
        assertEquals(ImageKind.SVG, ImageFormats.kindOf("﻿  <svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
        assertEquals(ImageKind.OTHER_BITMAP, ImageFormats.kindOf(byteArrayOf(0, 0, 0, 28) + "ftypavif".toByteArray() + ByteArray(16)))
        // An error page served with status 200, even one with an inline drawing in it.
        assertEquals(ImageKind.NOT_AN_IMAGE, ImageFormats.kindOf(TestImages.HTML.toByteArray()))
        assertEquals(ImageKind.NOT_AN_IMAGE, ImageFormats.kindOf("{\"error\":\"gone\"}".toByteArray()))
        assertEquals(ImageKind.NOT_AN_IMAGE, ImageFormats.kindOf(ByteArray(0)))
    }

    @Test
    fun aGifIsAnimatedWhenItHasASecondImage() {
        val still = TestImages.gif(1)
        val moving = TestImages.gif(3, paddingBytes = 1000)
        assertEquals(1, ImageFormats.gifFrames(file(still)))
        assertEquals(3, ImageFormats.gifFrames(file(moving)))
        assertFalse(ImageFormats.isAnimated(ImageKind.GIF, head(still), file(still)))
        assertTrue(ImageFormats.isAnimated(ImageKind.GIF, head(moving), file(moving)))
        // A file cut short mid-frame counts what it has and does not throw.
        assertEquals(0, ImageFormats.gifFrames(file(moving.copyOf(20))))
    }

    @Test
    fun aWebpIsAnimatedWhenItsHeaderSaysSoAndAPngWhenItHasAnAnimationChunk() {
        val webp = TestImages.animatedWebpHeader()
        assertTrue(ImageFormats.isAnimated(ImageKind.WEBP, webp, file(webp)))
        val plain = webp.copyOf().also { it[20] = 0 }
        assertFalse(ImageFormats.isAnimated(ImageKind.WEBP, plain, file(plain)))

        val png = TestImages.png(TestImages.photo(8, 8))
        assertFalse(ImageFormats.isAnimated(ImageKind.PNG, png, file(png)))
        val pixels = String(png, Charsets.ISO_8859_1).indexOf("IDAT")
        val apng = png.copyOfRange(0, pixels - 4) + byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + ByteArray(12) + png.copyOfRange(pixels - 4, png.size)
        assertTrue(ImageFormats.isAnimated(ImageKind.PNG, apng, file(apng)))
    }
}

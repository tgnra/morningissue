package net.tgnra.morningissue.ui

import net.tgnra.morningissue.ui.components.Dither
import net.tgnra.morningissue.ui.components.ImageTreatment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dither on small known pixels. Pure arithmetic: no bitmap, no Android. */
class DitherTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun grey(value: Int) = argb(255, value, value, value)

    private fun red(pixel: Int) = pixel shr 16 and 0xFF

    private fun green(pixel: Int) = pixel shr 8 and 0xFF

    private fun blue(pixel: Int) = pixel and 0xFF

    private fun alpha(pixel: Int) = pixel ushr 24

    /** A [width] × [height] picture of every colour a little, opaque. */
    private fun colours(width: Int, height: Int) = IntArray(width * height) { argb(255, it * 7 % 256, it * 13 % 256, it * 29 % 256) }

    @Test
    fun theBayerMatrixHoldsEveryThresholdOnceAndTheSmallOnesAreTheKnownOnes() {
        assertArrayEquals(intArrayOf(0), Dither.bayer(1))
        assertArrayEquals(intArrayOf(0, 2, 3, 1), Dither.bayer(2))
        assertArrayEquals(intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5), Dither.bayer(4))
        for (size in listOf(2, 4, 8, 16)) {
            assertEquals((0 until size * size).toList(), Dither.bayer(size).sorted())
        }
    }

    @Test
    fun everyPixelComesOutGreyOnOneOfTheLevelsWithItsAlpha() {
        for (levels in listOf(2, 4, 16)) {
            val pixels = colours(37, 23)
            pixels[5] = argb(0, 200, 10, 10)
            pixels[6] = argb(128, 10, 200, 10)
            Dither.greyscale(pixels, width = 37, levels = levels)

            val allowed = (0 until levels).map { Math.round(it * 255f / (levels - 1)) }.toSet()
            for (pixel in pixels) {
                assertEquals(red(pixel), green(pixel))
                assertEquals(red(pixel), blue(pixel))
                assertTrue("${red(pixel)} is not one of $levels levels", red(pixel) in allowed)
            }
            assertEquals(0, alpha(pixels[5]))
            assertEquals(128, alpha(pixels[6]))
            assertEquals(255, alpha(pixels[7]))
        }
        // The app's own setting: 16 levels are 0, 17, 34 ... 255.
        assertEquals(16, Dither.LEVELS)
        val ramp = IntArray(256) { grey(it) }
        Dither.greyscale(ramp, width = 16)
        assertTrue(ramp.all { red(it) % 17 == 0 })
        assertEquals(16, ramp.map(::red).toSet().size)
    }

    @Test
    fun theSamePictureAlwaysDithersToTheSamePixelsWhereverItsRowsAreCut() {
        val whole = colours(40, 24)
        val again = whole.copyOf()
        Dither.greyscale(whole, width = 40)
        Dither.greyscale(again, width = 40)
        assertArrayEquals(whole, again)

        // In strips, as the transformation does it: the pattern goes by the row's place in the picture.
        val source = colours(40, 24)
        val top = source.copyOfRange(0, 40 * 10)
        val bottom = source.copyOfRange(40 * 10, source.size)
        Dither.greyscale(top, width = 40, firstRow = 0)
        Dither.greyscale(bottom, width = 40, firstRow = 10)
        assertArrayEquals(whole, top + bottom)
    }

    @Test
    fun aGreyOnALevelIsLeftAloneAndOneBetweenLevelsBecomesAMixOfItsTwoNeighbours() {
        // Black, white and every exact level stay flat: no pattern is laid where none is needed.
        for (level in listOf(0, 17, 136, 255)) {
            val flat = IntArray(64) { grey(level) }
            Dither.greyscale(flat, width = 8)
            assertTrue(flat.all { red(it) == level })
        }
        // Half-way between 119 and 136: half the pixels go each way, and the average is kept.
        val between = IntArray(64) { grey(128) }
        Dither.greyscale(between, width = 8)
        assertEquals(setOf(119, 136), between.map(::red).toSet())
        assertEquals(128.0, between.map(::red).average(), 1.5)
        // A quarter of the way: about a quarter of the pixels take the upper level.
        val quarter = IntArray(64) { grey(123) }
        Dither.greyscale(quarter, width = 8)
        assertEquals(123.0, quarter.map(::red).average(), 1.5)
        // The pattern repeats with the matrix: every tile is the same.
        val tile = Dither.MATRIX_SIZE
        for (index in between.indices) {
            val x = index % 8
            val y = index / 8
            assertEquals(between[(y % tile) * 8 + x % tile], between[index])
        }
    }

    @Test
    fun withoutLevelsItIsTheEinkMatrixArithmetic() {
        val contrast = ImageTreatment.EINK_CONTRAST
        val matrix = ImageTreatment.einkMatrix(contrast)
        val pixels = colours(16, 16)
        val expected = pixels.map { pixel ->
            val value = matrix[0] * red(pixel) + matrix[1] * green(pixel) + matrix[2] * blue(pixel) + matrix[4]
            Math.round(value.coerceIn(0f, 255f))
        }
        Dither.greyscale(pixels, width = 16, contrast = contrast, levels = 0)
        for (index in pixels.indices) assertEquals(expected[index].toDouble(), red(pixels[index]).toDouble(), 1.0)
        // Raised contrast pushes light lighter and dark darker around mid-grey, before quantising.
        val light = intArrayOf(grey(200))
        val dark = intArrayOf(grey(50))
        Dither.greyscale(light, 1, contrast = contrast, levels = 0)
        Dither.greyscale(dark, 1, contrast = contrast, levels = 0)
        assertTrue(red(light[0]) > 200)
        assertTrue(red(dark[0]) < 50)
    }
}

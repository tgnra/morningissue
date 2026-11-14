package net.tgnra.morningissue.kobo

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of preparing an image for a Kobo, on the plain JVM: no Android graphics are
 * involved, and the encoders' output is read back with the JDK's own ImageIO decoders, so a
 * file that only this code could read would fail here.
 */
class GreyImageTest {
    private fun image(width: Int, height: Int, tone: (Int, Int) -> Int) =
        GreyImage(width, height, ByteArray(width * height) { tone(it % width, it / width).coerceIn(0, 255).toByte() })

    /** A smooth photograph-like picture: ramps, waves and noise. */
    private fun photo(width: Int, height: Int, seed: Int = 1): GreyImage {
        val random = Random(seed)
        return image(width, height) { x, y ->
            (60 + 120 * x / width + (sin(x / 17.0) * sin(y / 11.0) * 50).toInt() + random.nextInt(-12, 13))
        }
    }

    /** A line drawing: white paper, black strokes two pixels wide, and grey anti-aliasing beside each. */
    private fun drawing(width: Int, height: Int) = image(width, height) { x, y ->
        when ((x + 2 * y) % 40) {
            0, 1 -> 0
            2 -> 90
            3 -> 180
            39 -> 140
            else -> 255
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("ImageIO cannot read it")

    private fun tones(decoded: BufferedImage): IntArray {
        val raster = decoded.raster
        return IntArray(decoded.width * decoded.height) { raster.getSample(it % decoded.width, it / decoded.width, 0) }
    }

    @Test
    fun aPixelIsLaidOnWhiteThenTakenAsLuma() {
        assertEquals(255, GreyImage.greyOnWhite(0x00000000))
        assertEquals(255, GreyImage.greyOnWhite(0x00FF0000))
        assertEquals(0, GreyImage.greyOnWhite(0xFF000000.toInt()))
        assertEquals(255, GreyImage.greyOnWhite(0xFFFFFFFF.toInt()))
        // Half-transparent black (alpha 128 of 255) is mid-grey on paper.
        assertEquals(127, GreyImage.greyOnWhite(0x80000000.toInt()))
        // Rec. 601 weights: green is brightest, blue darkest.
        assertEquals(77, GreyImage.greyOnWhite(0xFFFF0000.toInt()))
        assertEquals(149, GreyImage.greyOnWhite(0xFF00FF00.toInt()))
        assertEquals(29, GreyImage.greyOnWhite(0xFF0000FF.toInt()))
        val grey = GreyImage.fromArgb(intArrayOf(0xFF000000.toInt(), 0, 0xFF808080.toInt(), 0x40FFFFFF), 2, 2)
        assertArrayEquals(intArrayOf(0, 255, 128, 255), IntArray(4) { grey[it % 2, it / 2] })
    }

    @Test
    fun everyExifOrientationIsTurnedUpright() {
        // 3 wide, 2 high: 0 1 2 / 3 4 5.
        val source = image(3, 2) { x, y -> y * 3 + x }
        fun rows(image: GreyImage) = (0 until image.height).map { y -> (0 until image.width).map { x -> image[x, y] } }
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), rows(source.oriented(1)))
        assertEquals(listOf(listOf(2, 1, 0), listOf(5, 4, 3)), rows(source.oriented(2)))
        assertEquals(listOf(listOf(5, 4, 3), listOf(2, 1, 0)), rows(source.oriented(3)))
        assertEquals(listOf(listOf(3, 4, 5), listOf(0, 1, 2)), rows(source.oriented(4)))
        assertEquals(listOf(listOf(0, 3), listOf(1, 4), listOf(2, 5)), rows(source.oriented(5)))
        // 6 is "turn 90° clockwise to view": the left column, read upwards, becomes the top row.
        assertEquals(listOf(listOf(3, 0), listOf(4, 1), listOf(5, 2)), rows(source.oriented(6)))
        assertEquals(listOf(listOf(5, 2), listOf(4, 1), listOf(3, 0)), rows(source.oriented(7)))
        assertEquals(listOf(listOf(2, 5), listOf(1, 4), listOf(0, 3)), rows(source.oriented(8)))
        assertTrue(source.oriented(0) === source)
    }

    @Test
    fun scalingAveragesTheAreaEachPixelCovers() {
        val four = image(4, 2) { x, _ -> listOf(0, 100, 200, 255)[x] }
        val two = four.scaledTo(2, 1)
        assertEquals(listOf(50, 228), listOf(two[0, 0], two[1, 0]))
        // Three into two: the middle pixel is shared half and half.
        val three = image(3, 1) { x, _ -> listOf(0, 90, 255)[x] }.scaledTo(2, 1)
        assertEquals(listOf(30, 200), listOf(three[0, 0], three[1, 0]))
        // A hairline survives a tenfold reduction as a grey line, where sampling could drop it.
        val hairline = image(1000, 10) { x, _ -> if (x == 503) 0 else 255 }.scaledTo(100, 1)
        assertTrue(hairline[50, 0] in 229..230)
        assertTrue(hairline.histogram()[255] == 99)
        assertTrue(four.scaledTo(4, 2) === four)
    }

    @Test
    fun theSizeIsAtMostTheWidthAndNeverEnlarged() {
        assertEquals(1264 to 948, GreyPlan.targetSize(2000, 1500, 1264, 4_000_000))
        assertEquals(800 to 600, GreyPlan.targetSize(800, 600, 1264, 4_000_000))
        // Portrait: the width is what is limited, not the long side.
        assertEquals(1000 to 1500, GreyPlan.targetSize(1000, 1500, 1264, 4_000_000))
        // A long infographic is narrowed to stay within the pixel cap.
        val (w, h) = GreyPlan.targetSize(1264, 20_000, 1264, 4_000_000)
        assertTrue("$w x $h", w * h <= 4_000_000 && w in 490..510)
        assertEquals(1 to 1, GreyPlan.targetSize(1, 1, 1264, 4_000_000))
        assertEquals(160 to 1, GreyPlan.targetSize(5000, 2, 160, 4_000_000))
    }

    @Test
    fun theDecoderReducesByPowersOfTwoButNeverBelowTheTarget() {
        assertEquals(1, GreyPlan.sampleSize(2000, 1500, 1264, 948))
        assertEquals(2, GreyPlan.sampleSize(4000, 3000, 1264, 948))
        assertEquals(4, GreyPlan.sampleSize(8000, 6000, 1264, 948))
        assertEquals(1, GreyPlan.sampleSize(800, 600, 800, 600))
        // Both sides must stay at or above the target.
        assertEquals(1, GreyPlan.sampleSize(4000, 1000, 1264, 600))
    }

    @Test
    fun lineArtAndFewTonesArePngAndPhotographsJpeg() {
        assertEquals(GreyFormat.JPEG, GreyPlan.formatFor(photo(400, 300).histogram()))
        assertEquals(GreyFormat.PNG, GreyPlan.formatFor(drawing(400, 300).histogram()))
        // A bar chart: four flat greys.
        assertEquals(GreyFormat.PNG, GreyPlan.formatFor(image(400, 300) { x, _ -> listOf(255, 40, 120, 200)[x / 100] }.histogram()))
        // A screenshot saved as JPEG: flat areas with a little compression noise.
        val random = Random(3)
        assertEquals(GreyFormat.PNG, GreyPlan.formatFor(image(400, 300) { x, _ -> (if (x < 200) 250 else 30) + random.nextInt(-2, 3) }.histogram()))
        // A product shot on a white page: four fifths paper, but the rest a photograph.
        val product = photo(400, 300)
        assertEquals(GreyFormat.JPEG, GreyPlan.formatFor(image(400, 300) { x, y -> if (x < 320) 255 else product[x, y] }.histogram()))
        // The boundary: sixteen tones of nine pixels and sixteen of one is exactly nine tenths.
        fun counts(others: Int) = IntArray(256).apply {
            for (t in 0 until 16) this[t] = 9
            for (t in 100 until 100 + others) this[t] = 1
        }
        assertEquals(GreyFormat.PNG, GreyPlan.formatFor(counts(16)))
        assertEquals(GreyFormat.JPEG, GreyPlan.formatFor(counts(17)))
        assertEquals(GreyFormat.PNG, GreyPlan.formatFor(IntArray(256)))
    }

    @Test
    fun thePngIsLosslessGreyscale() {
        for ((w, h) in listOf(1 to 1, 37 to 21, 300 to 200)) {
            val source = photo(w, h, seed = w)
            val bytes = GreyPng.encode(source)
            val decoded = decode(bytes)
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, decoded.type)
            assertEquals(w to h, decoded.width to decoded.height)
            assertArrayEquals(IntArray(w * h) { source.pixels[it].toInt() and 0xFF }, tones(decoded))
        }
        // Line art compresses far below its raw size.
        val art = drawing(800, 600)
        assertTrue(GreyPng.encode(art).size < 800 * 600 / 10)
    }

    @Test
    fun theJpegIsOneComponentAndCloseToTheSource() {
        for ((w, h) in listOf(8 to 8, 37 to 21, 640 to 480)) {
            val source = photo(w, h, seed = h)
            val bytes = GreyJpeg.encode(source, 90)
            assertEquals(0xFF, bytes[0].toInt() and 0xFF)
            assertEquals(0xD8, bytes[1].toInt() and 0xFF)
            val decoded = decode(bytes)
            assertEquals(w to h, decoded.width to decoded.height)
            assertEquals(1, decoded.raster.numBands)
            val out = tones(decoded)
            val error = out.indices.sumOf { abs(out[it] - (source.pixels[it].toInt() and 0xFF)) }.toDouble() / out.size
            assertTrue("mean error $error at ${w}x$h", error < 4.0)
        }
        // Flat white stays white; 0xFF bytes in the data are stuffed, or ImageIO would stop.
        val white = decode(GreyJpeg.encode(image(64, 64) { _, _ -> 255 }, 80))
        assertTrue(tones(white).all { it >= 253 })
        val black = decode(GreyJpeg.encode(image(17, 9) { _, _ -> 0 }, 80))
        assertTrue(tones(black).all { it <= 2 })
        // Lower quality, smaller file.
        val big = photo(320, 240)
        assertTrue(GreyJpeg.encode(big, 50).size < GreyJpeg.encode(big, 90).size)
        assertEquals(1, GreyJpeg.quantisation(100)[0])
        assertEquals(16, GreyJpeg.quantisation(50)[0])
    }

    @Test
    fun aPreparedImageIsScaledThenEncodedByItsTones() {
        val prepared = KoboImagePlan.encode(photo(2000, 1000), 1264)
        assertEquals(GreyFormat.JPEG, prepared.format)
        assertEquals(1264 to 632, decode(prepared.bytes).let { it.width to it.height })
        val art = KoboImagePlan.encode(drawing(600, 400), 1264)
        assertEquals(GreyFormat.PNG, art.format)
        assertEquals(600, decode(art.bytes).width)
    }

    @Test
    fun theRequestedWidthIsClampedAndDefaulted() {
        assertEquals(1264, KoboImagePlan.width(null))
        assertEquals(1264, KoboImagePlan.width(""))
        assertEquals(1264, KoboImagePlan.width("wide"))
        assertEquals(1072, KoboImagePlan.width("1072"))
        assertEquals(KoboImageConfig.MIN_WIDTH, KoboImagePlan.width("10"))
        assertEquals(KoboImageConfig.MIN_WIDTH, KoboImagePlan.width("-5"))
        assertEquals(KoboImageConfig.MAX_WIDTH, KoboImagePlan.width("99999"))
    }

    @Test
    fun onlyWebAddressesThatAreNotSvgAreFetched() {
        assertTrue(KoboImagePlan.mayFetch("https://a.example/x.jpg"))
        assertTrue(KoboImagePlan.mayFetch("http://a.example/x.svg.png?v=2"))
        assertTrue(!KoboImagePlan.mayFetch("https://a.example/logo.svg"))
        assertTrue(!KoboImagePlan.mayFetch("https://a.example/logo.SVG?v=3#top"))
        assertTrue(!KoboImagePlan.mayFetch("data:image/png;base64,AAAA"))
        assertTrue(!KoboImagePlan.mayFetch("/relative.jpg"))
        assertTrue(KoboImagePlan.cacheKey("https://a.example/x.jpg", 1264) != KoboImagePlan.cacheKey("https://a.example/x.jpg", 1072))
    }
}

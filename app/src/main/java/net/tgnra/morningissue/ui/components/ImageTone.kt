package net.tgnra.morningissue.ui.components

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/** How images are drawn: as they are, in greyscale (the Settings option), or as E-ink mode wants them. */
enum class ImageTone(
    /** Contrast multiplier around mid-grey; 1 leaves it alone. */
    val contrast: Float,
) {
    COLOUR(1f),

    /** Greyscale, dithered: the Settings option, in any theme. */
    GREY(1f),

    /** Greyscale with raised contrast, dithered: E-ink mode, whatever the option says. */
    EINK(ImageTreatment.EINK_CONTRAST),
}

/**
 * Greyscale and ordered dithering on raw pixels. Pure Kotlin on ARGB integers, so it is tested
 * without a bitmap.
 *
 * **Why ordered.** An ordered dither compares each pixel with a fixed threshold that depends
 * only on where the pixel is. The pattern therefore stays put: the same picture at the same
 * size dithers to the same pixels every time, and a page turned back to looks as it did. Error
 * diffusion carries each pixel's rounding error into its neighbours, so a one-pixel change in
 * the crop redraws the whole texture, which on an e-ink panel shows as crawling.
 *
 * **The constants** were chosen by looking at `DitherSamplesTest`'s output: photographs and a
 * black-to-white gradient at 2, 4, 6, 8 and 16 levels with 2×2, 4×4 and 8×8 matrices, whole and
 * enlarged four times.
 *
 * - [LEVELS] = 16. An e-ink panel has 16 greys, so every value written is one it can show and
 *   the panel's own, cruder rounding never runs. Fewer levels are more than "a bit of
 *   texture": at 8 a sky is visibly dotted, at 4 the pattern covers the whole picture and the
 *   shadows lift, at 2 it is a halftone. At 16 a photograph reads as a photograph, with a fine
 *   grain in skies and soft backgrounds, which would otherwise show bands. On a phone's dense
 *   screen that grain is faint; lower this to 8 for a stronger one.
 * - [MATRIX_SIZE] = 4. Between two neighbouring levels of 16 there are only 17 input values,
 *   and the 16 thresholds of a 4×4 Bayer matrix tell them all apart, so the 8×8 matrix gave
 *   the same pictures. The 2×2 matrix has 4 thresholds and left stair-step bands in the gradient.
 *
 * The dither works on the gamma-encoded values, as the E-ink colour matrix always did. With 16
 * levels the brightness error of not linearising first is under one level (it is what lifts
 * the shadows at 4).
 */
object Dither {
    const val LEVELS = 16
    const val MATRIX_SIZE = 4

    /** Rec. 709 luma weights, the ones `ImageTreatment.einkMatrix` uses. */
    private const val RED = 0.2126f
    private const val GREEN = 0.7152f
    private const val BLUE = 0.0722f

    /**
     * The Bayer threshold matrix of [size] × [size] (a power of two), row by row, with the
     * values 0 until size². Each is used as the threshold `(value + 0.5) / size²`.
     */
    fun bayer(size: Int): IntArray {
        require(size >= 1 && size and (size - 1) == 0) { "The matrix size must be a power of two" }
        var matrix = intArrayOf(0)
        var n = 1
        while (n < size) {
            val next = IntArray(4 * n * n)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val value = 4 * matrix[y * n + x]
                    next[y * 2 * n + x] = value
                    next[y * 2 * n + x + n] = value + 2
                    next[(y + n) * 2 * n + x] = value + 3
                    next[(y + n) * 2 * n + x + n] = value + 1
                }
            }
            matrix = next
            n *= 2
        }
        return matrix
    }

    /**
     * Turns [pixels] (non-premultiplied ARGB, rows of [width], the first being row [firstRow]
     * of the picture) to greyscale in place. Alpha is kept.
     *
     * @param contrast multiplies the distance from mid-grey, before quantising
     * @param levels the number of greys to dither to, black and white included; under 2 leaves
     *   the greys continuous (no dither)
     * @param matrixSize the Bayer matrix's side, a power of two
     */
    fun greyscale(
        pixels: IntArray,
        width: Int,
        firstRow: Int = 0,
        contrast: Float = 1f,
        levels: Int = LEVELS,
        matrixSize: Int = MATRIX_SIZE,
    ) {
        val matrix = if (levels >= 2) bayer(matrixSize) else null
        val cells = matrixSize * matrixSize
        val top = levels - 1
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val luma = RED * (pixel shr 16 and 0xFF) + GREEN * (pixel shr 8 and 0xFF) + BLUE * (pixel and 0xFF)
            val toned = ((luma - 127.5f) * contrast + 127.5f).coerceIn(0f, 255f)
            val grey = if (matrix == null) {
                toned.roundToInt()
            } else {
                val x = index % width
                val y = firstRow + index / width
                val threshold = (matrix[(y % matrixSize) * matrixSize + x % matrixSize] + 0.5f) / cells
                val scaled = toned / 255f * top
                val floor = scaled.toInt()
                val level = if (scaled - floor > threshold) floor + 1 else floor
                (level * 255f / top).roundToInt()
            }
            pixels[index] = (pixel and ALPHA_MASK) or (grey shl 16) or (grey shl 8) or grey
        }
    }

    private const val ALPHA_MASK = 0xFF shl 24
}

/**
 * The Coil transformation that draws an image in [tone]: greyscale, with E-ink's raised
 * contrast when the tone has it, dithered to [Dither.LEVELS] greys unless [dither] is off.
 *
 * It runs on the decoded bitmap, which `PaperImage` asks Coil to decode at exactly the size it
 * is shown, so the dither pattern is laid on the pixels the screen will light. (A colour filter
 * cannot do this: it sees one colour at a time and not where it is.) Coil adds [cacheKey] to
 * the memory-cache key, so a picture in another tone is another cache entry and changing the
 * Settings option redraws every image at once. Stored images are untouched: this is display.
 */
class ToneTransformation(private val tone: ImageTone, private val dither: Boolean = true) : Transformation() {
    init {
        require(tone != ImageTone.COLOUR) { "Colour needs no transformation" }
    }

    override val cacheKey: String =
        "tone:${tone.name}:c${tone.contrast}:" + if (dither) "bayer${Dither.MATRIX_SIZE}x${Dither.LEVELS}" else "plain"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // In strips, so a large picture is never held a third time as an array.
        val rows = (STRIP_PIXELS / width).coerceIn(1, height)
        val strip = IntArray(width * rows)
        var y = 0
        while (y < height) {
            val count = minOf(rows, height - y)
            val pixels = if (count == rows) strip else IntArray(width * count)
            input.getPixels(pixels, 0, width, 0, y, width, count)
            Dither.greyscale(pixels, width, firstRow = y, contrast = tone.contrast, levels = if (dither) Dither.LEVELS else 0)
            output.setPixels(pixels, 0, width, 0, y, width, count)
            y += count
        }
        return output
    }

    private companion object {
        const val STRIP_PIXELS = 256 * 1024
    }
}

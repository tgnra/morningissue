package net.tgnra.morningissue.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.toBitmap
import java.io.File
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.images.ImageFormats
import net.tgnra.morningissue.data.images.ImageKind
import net.tgnra.morningissue.ui.components.Dither
import net.tgnra.morningissue.ui.components.ImageTone
import net.tgnra.morningissue.ui.components.ImageTreatment
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Renders before-and-after pictures to look at, through the code the app draws with: the
 * request `PaperImage` makes for each tone, executed by a Coil loader at a slot's size. Not a
 * check: it is skipped unless told where to find pictures and where to put the results.
 *
 * ```
 * MORNING_ISSUE_IMAGE_SAMPLES=/path/to/pictures MORNING_ISSUE_DITHER_OUT=/path/to/out \
 *     ./gradlew :app:testDebugUnitTest --tests '*DitherSamplesTest'
 * ```
 *
 * For each photograph it writes `name-colour.png`, `name-grey.png` (the Settings option) and
 * `name-eink.png` (E-ink mode), then the alternatives the constants in [Dither] were chosen
 * against, as `name-L<levels>-M<matrix>.png` (with a piece of each enlarged four times as
 * `...-x4.png`), and the same for a smooth gradient, where a dither shows most.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DitherSamplesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun writeSamples() {
        val samples = System.getenv("MORNING_ISSUE_IMAGE_SAMPLES")?.let(::File)
        val out = System.getenv("MORNING_ISSUE_DITHER_OUT")?.let(::File)
        assumeTrue("MORNING_ISSUE_IMAGE_SAMPLES and MORNING_ISSUE_DITHER_OUT are not set", samples != null && samples.isDirectory && out != null)
        out!!.mkdirs()
        val loader = ImageLoader.Builder(context).build()
        try {
            val gradient = File(out, "gradient-source.png").also { save(gradient(), it) }
            val pictures = samples!!.listFiles().orEmpty().sortedBy { it.name }.filter { file ->
                val kind = ImageFormats.kindOf(file.inputStream().use { it.readNBytes(64) })
                (kind == ImageKind.JPEG || kind == ImageKind.PNG) && file.length() < 3_000_000
            }
            for (source in pictures + gradient) {
                val name = source.nameWithoutExtension.take(40)
                for (tone in ImageTone.entries) {
                    save(load(loader, source, tone), File(out, "$name-${tone.name.lowercase()}.png"))
                }
                // The alternatives, on the grey picture as the app would hand it to the dither.
                val grey = load(loader, source, ImageTone.GREY, dither = false)
                for ((levels, matrix) in ALTERNATIVES) {
                    val pixels = IntArray(grey.width * grey.height).also { grey.getPixels(it, 0, grey.width, 0, 0, grey.width, grey.height) }
                    Dither.greyscale(pixels, grey.width, levels = levels, matrixSize = matrix)
                    val dithered = Bitmap.createBitmap(pixels, grey.width, grey.height, Bitmap.Config.ARGB_8888)
                    save(dithered, File(out, "$name-L$levels-M$matrix.png"))
                    save(magnified(dithered), File(out, "$name-L$levels-M$matrix-x4.png"))
                }
            }
        } finally {
            loader.shutdown()
        }
    }

    /** As the reader's lead picture on a phone asks for it: 720 px wide, 3:2, cropped to fill. */
    private fun load(loader: ImageLoader, source: File, tone: ImageTone, dither: Boolean = true): Bitmap = runBlocking {
        val request = ImageTreatment.request(context, source.toURI().toString(), tone, dither).size(SLOT_WIDTH, SLOT_HEIGHT).scale(Scale.FILL).build()
        (loader.execute(request) as SuccessResult).image.toBitmap()
    }

    private fun gradient(): Bitmap {
        val pixels = IntArray(SLOT_WIDTH * SLOT_HEIGHT) { index ->
            val x = index % SLOT_WIDTH
            val y = index / SLOT_WIDTH
            // Left to right black to white, with a slow vertical tint so the colour version is not grey.
            val value = x * 255 / (SLOT_WIDTH - 1)
            Color.rgb(value, (value + y * 20 / SLOT_HEIGHT).coerceAtMost(255), value)
        }
        return Bitmap.createBitmap(pixels, SLOT_WIDTH, SLOT_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /** A 180 × 120 piece from the upper middle, each pixel drawn as 4 × 4, to see the pattern itself. */
    private fun magnified(bitmap: Bitmap): Bitmap {
        val piece = Bitmap.createBitmap(bitmap, (bitmap.width - 180) / 2, bitmap.height / 5, 180, 120)
        return Bitmap.createScaledBitmap(piece, 720, 480, false)
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SLOT_WIDTH = 720
        const val SLOT_HEIGHT = 480

        /** Levels to matrix size. */
        val ALTERNATIVES = listOf(2 to 8, 4 to 4, 4 to 8, 6 to 4, 8 to 4, 8 to 8, 16 to 2, 16 to 4, 16 to 8)
    }
}

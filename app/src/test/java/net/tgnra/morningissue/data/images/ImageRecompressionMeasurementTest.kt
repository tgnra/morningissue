package net.tgnra.morningissue.data.images

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Locale
import kotlin.math.log10
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Measures what re-encoding does to real pictures, to choose `ImageStoreConfig.QUALITY`. Not a
 * check: it asserts nothing and is skipped unless told where the pictures are.
 *
 * ```
 * MORNING_ISSUE_IMAGE_SAMPLES=/path/to/pictures MORNING_ISSUE_IMAGE_REPORT=/path/to/report.txt \
 *     ./gradlew :app:testDebugUnitTest --tests '*ImageRecompressionMeasurementTest'
 * ```
 *
 * For each picture and quality it reports the original's bytes, the stored bytes and how close
 * the stored picture is to the pixels that were encoded (the original scaled to the stored
 * size): PSNR over the colour channels in decibels, and mean SSIM over 8 × 8 blocks of luma
 * (1 is identical). The encoder is Android's, as built for the host by Robolectric's native
 * graphics; a device's may differ in version.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageRecompressionMeasurementTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun reportSizesAndSimilarityAtEachQuality() {
        val directory = System.getenv("MORNING_ISSUE_IMAGE_SAMPLES")?.let(::File)
        assumeTrue("MORNING_ISSUE_IMAGE_SAMPLES is not set", directory != null && directory.isDirectory)
        val report = StringBuilder()
        report.appendLine(String.format(Locale.ROOT, "%-58s %11s %11s  %s", "picture", "original", "stored at", QUALITIES.joinToString("  ") { "q$it: bytes (share) PSNR SSIM" }))
        val totals = LongArray(QUALITIES.size)
        var totalOriginal = 0L
        for (source in directory!!.listFiles().orEmpty().sortedBy { it.name }) {
            val head = source.inputStream().use { it.readNBytes(ImageFormats.HEAD_BYTES) }
            val kind = ImageFormats.kindOf(head)
            if (kind == ImageKind.SVG || kind == ImageKind.NOT_AN_IMAGE || ImageFormats.isAnimated(kind, head, source)) {
                report.appendLine(String.format(Locale.ROOT, "%-58s %,11d  kept as it came (%s)", source.name.take(58), source.length(), kind))
                continue
            }
            val reference = ImageRecompressor().decode(source)
            if (reference == null) {
                report.appendLine(String.format(Locale.ROOT, "%-58s %,11d  does not decode", source.name.take(58), source.length()))
                continue
            }
            val cells = QUALITIES.mapIndexed { index, quality ->
                val target = folder.newFile()
                ImageRecompressor(quality = quality).recompress(source, target)
                val stored = TestImages.decode(target)
                totals[index] += minOf(target.length(), source.length())
                String.format(
                    Locale.ROOT, "%,9d (%4.1f%%) %5.1f %.4f",
                    target.length(), 100.0 * target.length() / source.length(), psnr(reference, stored), ssim(reference, stored),
                )
            }
            totalOriginal += source.length()
            report.appendLine(
                String.format(Locale.ROOT, "%-58s %,11d %5d×%-5d  %s", source.name.take(58), source.length(), reference.width, reference.height, cells.joinToString("  ")),
            )
        }
        report.appendLine(
            String.format(
                Locale.ROOT, "%-58s %,11d %11s  %s", "total (the smaller of original and stored)", totalOriginal, "",
                totals.joinToString("  ") { String.format(Locale.ROOT, "%,9d (%4.1f%%)             ", it, 100.0 * it / totalOriginal) },
            ),
        )
        println(report)
        System.getenv("MORNING_ISSUE_IMAGE_REPORT")?.let { File(it).writeText(report.toString()) }
    }

    private fun psnr(a: Bitmap, b: Bitmap): Double {
        val pa = pixels(a)
        val pb = pixels(b)
        var squared = 0.0
        for (i in pa.indices) {
            val dr = Color.red(pa[i]) - Color.red(pb[i])
            val dg = Color.green(pa[i]) - Color.green(pb[i])
            val db = Color.blue(pa[i]) - Color.blue(pb[i])
            squared += dr * dr + dg * dg + db * db
        }
        val mse = squared / (pa.size * 3.0)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    /** Mean structural similarity over non-overlapping 8 × 8 blocks of luma. */
    private fun ssim(a: Bitmap, b: Bitmap): Double {
        val la = luma(a)
        val lb = luma(b)
        val width = a.width
        var sum = 0.0
        var blocks = 0
        var y = 0
        while (y + 8 <= a.height) {
            var x = 0
            while (x + 8 <= width) {
                var ma = 0.0
                var mb = 0.0
                for (j in 0 until 8) for (i in 0 until 8) {
                    ma += la[(y + j) * width + x + i]
                    mb += lb[(y + j) * width + x + i]
                }
                ma /= 64
                mb /= 64
                var va = 0.0
                var vb = 0.0
                var cov = 0.0
                for (j in 0 until 8) for (i in 0 until 8) {
                    val da = la[(y + j) * width + x + i] - ma
                    val db = lb[(y + j) * width + x + i] - mb
                    va += da * da
                    vb += db * db
                    cov += da * db
                }
                va /= 63
                vb /= 63
                cov /= 63
                sum += ((2 * ma * mb + C1) * (2 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2))
                blocks++
                x += 8
            }
            y += 8
        }
        return if (blocks == 0) 1.0 else sum / blocks
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun luma(bitmap: Bitmap): DoubleArray = pixels(bitmap).let { p ->
        DoubleArray(p.size) { 0.2126 * Color.red(p[it]) + 0.7152 * Color.green(p[it]) + 0.0722 * Color.blue(p[it]) }
    }

    private companion object {
        val QUALITIES = listOf(70, 80, 90)
        const val C1 = 6.5025
        const val C2 = 58.5225
    }
}

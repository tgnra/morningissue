package net.tgnra.morningissue.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/** Images made in the test, so that nothing downloaded is needed to exercise the store. */
internal object TestImages {
    /**
     * A picture with the statistics of a photograph: smooth colour ramps, a few soft shapes and
     * sensor-like noise. Its top-left quarter is distinctly red, so a test can tell which way up it is.
     */
    fun photo(width: Int, height: Int, seed: Int = 1, noise: Int = 10): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x.toFloat() / width
                val v = y.toFloat() / height
                val wave = (sin(u * 9f + seed) * sin(v * 7f) * 40f).toInt()
                val n = random.nextInt(-noise, noise + 1)
                val corner = x < width / 2 && y < height / 2
                val r = ((if (corner) 200 else 60) + u * 50f + wave + n).toInt().coerceIn(0, 255)
                val g = (90 + v * 110f + wave + n).toInt().coerceIn(0, 255)
                val b = ((if (corner) 40 else 150) + (1 - u) * 60f - wave + n).toInt().coerceIn(0, 255)
                pixels[y * width + x] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** A flat two-colour drawing with a transparent right half. */
    fun drawing(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            when {
                x >= width / 2 -> Color.TRANSPARENT
                (x / 8 + index / width / 8) % 2 == 0 -> Color.rgb(20, 60, 160)
                else -> Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 95): ByteArray = encode(bitmap, Bitmap.CompressFormat.JPEG, quality)

    fun png(bitmap: Bitmap): ByteArray = encode(bitmap, Bitmap.CompressFormat.PNG, 100)

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { check(bitmap.compress(format, quality, it)) }.toByteArray()

    fun decode(file: File): Bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "${file.name} does not decode" }

    /**
     * A GIF with [frames] images of one pixel, each followed by [paddingBytes] of comment
     * extension, which makes a file of any size that still parses.
     */
    fun gif(frames: Int, paddingBytes: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray())
        write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0))
        write(byteArrayOf(-1, -1, -1, 0, 0, 0))
        repeat(frames) {
            write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            write(byteArrayOf(2, 2, 0x44, 0x01, 0))
            var left = paddingBytes
            while (left > 0) {
                val block = minOf(left, 255)
                write(byteArrayOf(0x21, 0xFE.toByte(), block.toByte()))
                write(ByteArray(block) { 'x'.code.toByte() })
                write(0)
                left -= block
            }
        }
        write(0x3B)
    }.toByteArray()

    /** The first bytes of an animated WebP: enough for the store to see the animation flag. */
    fun animatedWebpHeader(): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray())
        write(byteArrayOf(40, 0, 0, 0))
        write("WEBPVP8X".toByteArray())
        write(byteArrayOf(10, 0, 0, 0, 0x02, 0, 0, 0, 1, 0, 0, 1, 0, 0))
        write(ByteArray(16))
    }.toByteArray()

    const val SVG = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="#c00"/></svg>"""

    const val HTML = "<!DOCTYPE html><html><body><p>Not found</p><svg></svg></body></html>"
}

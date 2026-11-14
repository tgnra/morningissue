package net.tgnra.morningissue.kobo

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An 8-bit greyscale picture, one byte a pixel, row by row: what a Kobo is sent
 * (`GET /v1/images`, `docs/kobo-sync.md`). Everything here is pure Kotlin, so the steps after
 * decoding (flattening, turning, scaling, the choice of format and both encoders) are unit
 * tested on the JVM without Android's graphics.
 */
class GreyImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "${width}x$height with ${pixels.size} pixels" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** The picture as an EXIF orientation (1..8) says it should be shown; any other value leaves it as it is. */
    fun oriented(orientation: Int): GreyImage {
        val w = width
        val h = height
        val swaps = orientation in 5..8
        val outW = if (swaps) h else w
        val outH = if (swaps) w else h
        val source: (Int, Int) -> Int = when (orientation) {
            2 -> { x, y -> y * w + (w - 1 - x) }
            3 -> { x, y -> (h - 1 - y) * w + (w - 1 - x) }
            4 -> { x, y -> (h - 1 - y) * w + x }
            5 -> { x, y -> x * w + y }
            6 -> { x, y -> (h - 1 - x) * w + y }
            7 -> { x, y -> (h - 1 - x) * w + (w - 1 - y) }
            8 -> { x, y -> x * w + (w - 1 - y) }
            else -> return this
        }
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) out[y * outW + x] = pixels[source(x, y)]
        return GreyImage(outW, outH, out)
    }

    /**
     * The picture reduced to [targetWidth] × [targetHeight] by area averaging: each output
     * pixel is the mean of the source area it covers, which keeps fine lines and text legible
     * where sampling would drop them. Never enlarges; a target as large as the picture returns it.
     */
    fun scaledTo(targetWidth: Int, targetHeight: Int): GreyImage {
        require(targetWidth in 1..width && targetHeight in 1..height) { "${targetWidth}x$targetHeight from ${width}x$height" }
        if (targetWidth == width && targetHeight == height) return this
        val across = contributions(width, targetWidth)
        val down = contributions(height, targetHeight)
        // Across first, into floats, then down.
        val rows = FloatArray(targetWidth * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until targetWidth) {
                val c = across[x]
                var sum = 0f
                for (i in c.weights.indices) sum += (pixels[row + c.first + i].toInt() and 0xFF) * c.weights[i]
                rows[y * targetWidth + x] = sum
            }
        }
        val out = ByteArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val c = down[y]
            for (x in 0 until targetWidth) {
                var sum = 0f
                for (i in c.weights.indices) sum += rows[(c.first + i) * targetWidth + x] * c.weights[i]
                out[y * targetWidth + x] = sum.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GreyImage(targetWidth, targetHeight, out)
    }

    /** How many pixels have each of the 256 tones. */
    fun histogram(): IntArray {
        val counts = IntArray(256)
        for (p in pixels) counts[p.toInt() and 0xFF]++
        return counts
    }

    /** The source pixels from [first] on that one output pixel covers, each with the share of it that falls inside. */
    private class Contribution(val first: Int, val weights: FloatArray)

    private fun contributions(source: Int, target: Int): Array<Contribution> {
        val step = source.toDouble() / target
        return Array(target) { i ->
            val start = i * step
            val end = (i + 1) * step
            val first = start.toInt()
            val last = minOf(source - 1, kotlin.math.ceil(end).toInt() - 1)
            val weights = FloatArray(last - first + 1) { k ->
                val from = maxOf(start, (first + k).toDouble())
                val to = minOf(end, (first + k + 1).toDouble())
                ((to - from) / step).toFloat()
            }
            Contribution(first, weights)
        }
    }

    companion object {
        /**
         * Greyscale from unpremultiplied ARGB pixels (Android's `Bitmap.getPixels`), each laid
         * on white by its alpha, as Rec. 601 luma, the weights JPEG uses.
         */
        fun fromArgb(argb: IntArray, width: Int, height: Int): GreyImage {
            val out = ByteArray(width * height)
            for (i in out.indices) out[i] = greyOnWhite(argb[i]).toByte()
            return GreyImage(width, height, out)
        }

        /** One ARGB pixel as a tone on white, 0 (black) to 255 (white). */
        fun greyOnWhite(argb: Int): Int {
            val a = argb ushr 24
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val luma = (77 * r + 150 * g + 29 * b + 128) shr 8
            return (luma * a + 255 * (255 - a) + 127) / 255
        }
    }
}

/** Which encoding a prepared image gets. */
enum class GreyFormat(val contentType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}

/**
 * The decisions about one image, as pure functions: its size on the Kobo, how far to let
 * the decoder reduce it, and whether it is sent as JPEG or PNG.
 */
object GreyPlan {
    /**
     * The size to send a [width] × [height] picture at: at most [maxWidth] wide, never wider
     * than it is, and no more than [maxPixels] in all (a very long infographic is narrowed
     * rather than held whole in memory). Proportions are kept; neither side is below 1.
     */
    fun targetSize(width: Int, height: Int, maxWidth: Int, maxPixels: Int): Pair<Int, Int> {
        var w = minOf(width, maxWidth).toDouble()
        var h = height * w / width
        if (w * h > maxPixels) {
            val f = sqrt(maxPixels / (w * h))
            w *= f
            h *= f
        }
        return w.toInt().coerceIn(1, width) to h.toInt().coerceIn(1, height)
    }

    /**
     * The decoder's `inSampleSize` for a picture to end at [targetWidth] × [targetHeight]: the
     * largest power of two that leaves both sides at or above the target, so that the area
     * averaging afterwards only ever reduces.
     */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** How many of the most common tones [formatFor] looks at. */
    const val FEW_TONES = 16

    /** The share of the pixels those tones must cover for the picture to count as line art. */
    const val FEW_TONES_SHARE = 0.9

    /**
     * PNG for line art, diagrams, screenshots and other pictures of few tones, JPEG for the
     * rest. The test: do the [FEW_TONES] most common of the 256 tones cover at least
     * [FEW_TONES_SHARE] of the pixels? A drawing is mostly paper and ink, with its
     * anti-aliased edges a thin minority; even a photograph on a white background spreads well
     * over a tenth of its pixels across many tones. JPEG's ringing would show around a drawing's
     * hard edges on e-ink, and PNG would be several times larger for a photograph.
     */
    fun formatFor(histogram: IntArray): GreyFormat {
        val total = histogram.sum()
        if (total == 0) return GreyFormat.PNG
        val common = histogram.sortedDescending().take(FEW_TONES).sum()
        return if (common >= total * FEW_TONES_SHARE) GreyFormat.PNG else GreyFormat.JPEG
    }
}

/** A greyscale PNG: colour type 0, 8 bits, one adaptive filter per row, deflated. */
object GreyPng {
    fun encode(image: GreyImage): ByteArray {
        val w = image.width
        val raw = ByteArray((w + 1) * image.height)
        val candidates = Array(5) { ByteArray(w) }
        for (y in 0 until image.height) {
            val row = y * w
            for (filter in 0..4) {
                val out = candidates[filter]
                for (x in 0 until w) {
                    val p = image.pixels[row + x].toInt() and 0xFF
                    val a = if (x > 0) image.pixels[row + x - 1].toInt() and 0xFF else 0
                    val b = if (y > 0) image.pixels[row - w + x].toInt() and 0xFF else 0
                    val c = if (x > 0 && y > 0) image.pixels[row - w + x - 1].toInt() and 0xFF else 0
                    val predicted = when (filter) {
                        0 -> 0
                        1 -> a
                        2 -> b
                        3 -> (a + b) / 2
                        else -> paeth(a, b, c)
                    }
                    out[x] = (p - predicted).toByte()
                }
            }
            // The usual heuristic: the filter whose output, read as signed bytes, sums smallest.
            val best = (0..4).minBy { f -> candidates[f].sumOf { abs(it.toInt()).toLong() } }
            val at = y * (w + 1)
            raw[at] = best.toByte()
            candidates[best].copyInto(raw, at + 1)
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val compressed = try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArrayOutputStream(raw.size / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) buffer.write(chunk, 0, deflater.deflate(chunk))
            buffer.toByteArray()
        } finally {
            deflater.end()
        }
        val out = ByteArrayOutputStream(compressed.size + 64)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(image.width)
                writeInt(image.height)
                writeByte(8) // bit depth
                writeByte(0) // greyscale
                writeByte(0) // deflate
                writeByte(0) // adaptive filtering
                writeByte(0) // not interlaced
            }
        }.toByteArray()
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", compressed)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }
}

/**
 * A baseline greyscale JPEG: one component, the standard luminance quantisation table scaled
 * by quality as libjpeg does, and the standard Huffman tables. Android's own encoder always
 * writes three components, which for a grey picture is a third more to decode on the Kobo.
 */
object GreyJpeg {
    fun encode(image: GreyImage, quality: Int): ByteArray {
        val table = quantisation(quality)
        val out = ByteArrayOutputStream(image.width * image.height / 4 + 1024)
        writeHeaders(out, image, table)
        val bits = BitWriter(out)
        val block = FloatArray(64)
        val coefficients = IntArray(64)
        var previousDc = 0
        val blocksAcross = (image.width + 7) / 8
        val blocksDown = (image.height + 7) / 8
        for (by in 0 until blocksDown) {
            for (bx in 0 until blocksAcross) {
                // Pixels past the edge repeat the last row and column.
                for (y in 0 until 8) {
                    val sy = minOf(by * 8 + y, image.height - 1)
                    for (x in 0 until 8) {
                        val sx = minOf(bx * 8 + x, image.width - 1)
                        block[y * 8 + x] = (image[sx, sy] - 128).toFloat()
                    }
                }
                forwardDct(block)
                for (i in 0 until 64) coefficients[i] = (block[i] / table[i]).roundToInt()
                previousDc = encodeBlock(bits, coefficients, previousDc)
            }
        }
        bits.flush()
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    /** The luminance table in natural order, scaled for [quality] (1..100) the way libjpeg's `jpeg_quality_scaling` does. */
    fun quantisation(quality: Int): IntArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        return IntArray(64) { ((LUMINANCE[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    private fun writeHeaders(out: ByteArrayOutputStream, image: GreyImage, table: IntArray) {
        fun marker(code: Int, vararg body: Int) {
            out.write(0xFF)
            out.write(code)
            val length = body.size + 2
            out.write(length shr 8)
            out.write(length and 0xFF)
            body.forEach { out.write(it) }
        }
        out.write(0xFF)
        out.write(0xD8)
        marker(0xE0, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        marker(0xDB, 0, *IntArray(64) { table[ZIGZAG[it]] })
        marker(0xC0, 8, image.height shr 8, image.height and 0xFF, image.width shr 8, image.width and 0xFF, 1, 1, 0x11, 0)
        marker(0xC4, 0x00, *DC_BITS, *DC_VALUES, 0x10, *AC_BITS, *AC_VALUES)
        marker(0xDA, 1, 1, 0x00, 0, 63, 0)
    }

    /** Encodes one quantised block, natural order, and returns its DC value for the next. */
    private fun encodeBlock(bits: BitWriter, c: IntArray, previousDc: Int): Int {
        val dc = c[0]
        val diff = dc - previousDc
        val dcSize = magnitudeBits(diff)
        bits.write(DC_CODES[dcSize], DC_LENGTHS[dcSize])
        if (dcSize > 0) bits.write(valueBits(diff, dcSize), dcSize)
        var run = 0
        for (k in 1 until 64) {
            val v = c[ZIGZAG[k]]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                bits.write(AC_CODES[0xF0], AC_LENGTHS[0xF0])
                run -= 16
            }
            val size = magnitudeBits(v)
            val symbol = (run shl 4) or size
            bits.write(AC_CODES[symbol], AC_LENGTHS[symbol])
            bits.write(valueBits(v, size), size)
            run = 0
        }
        if (run > 0) bits.write(AC_CODES[0x00], AC_LENGTHS[0x00])
        return dc
    }

    private fun magnitudeBits(value: Int): Int {
        var v = abs(value)
        var n = 0
        while (v > 0) {
            n++
            v = v shr 1
        }
        return n
    }

    /** A negative value is sent as its ones' complement in [size] bits. */
    private fun valueBits(value: Int, size: Int): Int = if (value >= 0) value else value + (1 shl size) - 1

    /** The 8×8 forward DCT in place, rows then columns, with the JPEG normalisation. */
    private fun forwardDct(block: FloatArray) {
        val tmp = FloatArray(64)
        for (y in 0 until 8) {
            for (u in 0 until 8) {
                var sum = 0f
                for (x in 0 until 8) sum += COSINES[u * 8 + x] * block[y * 8 + x]
                tmp[y * 8 + u] = sum
            }
        }
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0f
                for (y in 0 until 8) sum += COSINES[v * 8 + y] * tmp[y * 8 + u]
                block[v * 8 + u] = sum
            }
        }
    }

    /** Collects bits most significant first and writes whole bytes, stuffing a zero after every 0xFF. */
    private class BitWriter(private val out: ByteArrayOutputStream) {
        private var buffer = 0
        private var count = 0

        fun write(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                buffer = (buffer shl 1) or ((value shr i) and 1)
                count++
                if (count == 8) emit()
            }
        }

        private fun emit() {
            out.write(buffer)
            if (buffer == 0xFF) out.write(0)
            buffer = 0
            count = 0
        }

        /** Pads the last byte with ones, as the standard asks. */
        fun flush() {
            if (count > 0) write((1 shl (8 - count)) - 1, 8 - count)
        }
    }

    /** `COSINES[u * 8 + x]` = C(u)/2 · cos((2x+1)uπ/16), so rows then columns give the ¼ C(u) C(v) of the definition. */
    private val COSINES = FloatArray(64) { i ->
        val u = i / 8
        val x = i % 8
        val c = if (u == 0) 1 / sqrt(2.0) else 1.0
        (c / 2 * cos((2 * x + 1) * u * PI / 16)).toFloat()
    }

    /** The natural index of each coefficient in zigzag order. */
    val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    /** Annex K, table K.1, natural order. */
    private val LUMINANCE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** Annex K, tables K.3 and K.5: code counts by length 1..16, then the symbols. */
    private val DC_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_VALUES = IntArray(12) { it }
    private val AC_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    private val AC_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa,
    )

    private val DC_CODES = IntArray(12)
    private val DC_LENGTHS = IntArray(12)
    private val AC_CODES = IntArray(256)
    private val AC_LENGTHS = IntArray(256)

    init {
        canonicalCodes(DC_BITS, DC_VALUES, DC_CODES, DC_LENGTHS)
        canonicalCodes(AC_BITS, AC_VALUES, AC_CODES, AC_LENGTHS)
    }

    /** Annex C: codes of each length run on from the last, doubled at each new length. */
    private fun canonicalCodes(counts: IntArray, symbols: IntArray, codes: IntArray, lengths: IntArray) {
        check(counts.sum() == symbols.size)
        var code = 0
        var k = 0
        for (length in 1..16) {
            repeat(counts[length - 1]) {
                codes[symbols[k]] = code
                lengths[symbols[k]] = length
                code++
                k++
            }
            code = code shl 1
        }
    }
}

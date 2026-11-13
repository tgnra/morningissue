package net.tgnra.morningissue.server.editions

import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.data.images.ImageDownload
import net.tgnra.morningissue.data.images.ImageFormats
import net.tgnra.morningissue.data.images.ImageKind
import net.tgnra.morningissue.data.images.ImageStoreConfig
import net.tgnra.morningissue.kobo.GreyFormat
import net.tgnra.morningissue.kobo.GreyImage
import net.tgnra.morningissue.kobo.GreyPlan
import net.tgnra.morningissue.kobo.KoboImageConfig
import net.tgnra.morningissue.kobo.KoboImagePlan
import net.tgnra.morningissue.kobo.PreparedImage
import okhttp3.OkHttpClient

/**
 * Pictures for Kobos (4.8): a source address turned into a greyscale JPEG or PNG at most `w`
 * pixels wide. The app's `KoboImages`, decoding with ImageIO (TwelveMonkeys adds CMYK JPEG,
 * EXIF-aware reading and WebP) instead of Android's `BitmapFactory`; everything after decoding
 * is core's ([GreyPlan], [GreyImage], [KoboImagePlan]).
 *
 * The source is downloaded with the image client's size limit and timeout ([ImageDownload]).
 * Prepared images are kept in [cacheDirectory], named by address and width, up to
 * [maxCacheBytes] (least recently used go first). Two requests for the same image at once
 * share one preparation. An address that could not be got or decoded is remembered for
 * [failureMemoryMillis], so a Kobo's next chunk does not ask the site again at once.
 */
class KoboImages(
    /** The server's shared client, asked for on the first download. */
    private val client: () -> OkHttpClient,
    private val cacheDirectory: File,
    private val maxCacheBytes: Long = KoboImageConfig.CACHE_MAX_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val failureMemoryMillis: Long = 15 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val imageClient: OkHttpClient by lazy { ImageDownload.client(client()) }
    private val fetches = Semaphore(KoboImageConfig.PARALLEL_FETCHES)
    private val decodes = Semaphore(KoboImageConfig.PARALLEL_DECODES)
    private val cache = PreparedCache(File(cacheDirectory, "prepared"), maxCacheBytes)

    /** Addresses that failed, and when. */
    private val failed = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tempsCleared = AtomicBoolean(false)

    /** The image for [url] at most [width] pixels wide, or null when it cannot be had. Never throws except on cancellation. */
    suspend fun prepare(url: String, width: Int): PreparedImage? = prepare(url, listOf(width))[width]

    /** [url] at each of [widths], from one download and one decode; a width missing from the result could not be prepared. */
    suspend fun prepare(url: String, widths: List<Int>): Map<Int, PreparedImage> = withContext(dispatcher) { shared(url, widths.distinct()) }

    /** Whether [url] at [width] is already prepared. */
    fun isCached(url: String, width: Int): Boolean = cache.has(KoboImagePlan.cacheKey(url, width))

    private suspend fun shared(url: String, widths: List<Int>): Map<Int, PreparedImage> {
        if (!KoboImagePlan.mayFetch(url) || widths.isEmpty()) return emptyMap()
        while (true) {
            val ready = widths.mapNotNull { w -> cache.get(KoboImagePlan.cacheKey(url, w))?.let { w to it } }.toMap()
            val missing = widths - ready.keys
            if (missing.isEmpty()) return ready
            failed[url]?.let { at -> if (now() - at < failureMemoryMillis) return ready else failed.remove(url, at) }
            val mine = CompletableDeferred<Unit>()
            val other = inFlight.putIfAbsent(url, mine)
            if (other != null) {
                other.await()
                continue
            }
            try {
                val made = try {
                    make(url, missing)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (made == null) failed[url] = now() else made.forEach { (w, image) -> cache.put(KoboImagePlan.cacheKey(url, w), image) }
                return ready + made.orEmpty()
            } finally {
                inFlight.remove(url, mine)
                mine.complete(Unit)
            }
        }
    }

    private suspend fun make(url: String, widths: List<Int>): Map<Int, PreparedImage>? {
        val temp = newTemp()
        try {
            val fetched = fetches.withPermit {
                withTimeoutOrNull(ImageStoreConfig.DOWNLOAD_TIMEOUT_MILLIS) { ImageDownload.toFile(imageClient, url, temp) } ?: false
            }
            if (!fetched) return null
            return decodes.withPermit { runInterruptible { encode(temp, widths) } }
        } finally {
            temp.delete()
        }
    }

    private fun newTemp(): File {
        val directory = File(cacheDirectory, "tmp")
        if (tempsCleared.compareAndSet(false, true)) directory.listFiles().orEmpty().forEach { it.delete() }
        directory.mkdirs()
        return File(directory, UUID.randomUUID().toString())
    }

    companion object {
        /**
         * Decodes [source] once, at the largest of [widths], and prepares it at each; null for
         * what is not a picture ImageIO can decode, SVG included.
         */
        fun encode(source: File, widths: List<Int>): Map<Int, PreparedImage>? {
            val head = source.inputStream().use { it.readNBytes(ImageFormats.HEAD_BYTES) }
            val kind = ImageFormats.kindOf(head)
            if (kind == ImageKind.SVG || kind == ImageKind.NOT_AN_IMAGE) return null
            val orientation = if (kind == ImageKind.JPEG) ExifOrientation.of(source) else 1
            val grey = decodeGrey(source, widths.max(), orientation) ?: return null
            return widths.associateWith { KoboImagePlan.encode(grey, it) }
        }

        /**
         * [source] upright and grey, decoded with the largest whole subsampling that keeps it at
         * least as large as it will be sent. The first frame of an animated GIF or WebP.
         */
        fun decodeGrey(source: File, maxWidth: Int, orientation: Int): GreyImage? {
            val input: ImageInputStream = ImageIO.createImageInputStream(source) ?: return null
            input.use { stream ->
                val reader: ImageReader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(stream, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return null
                    val turned = orientation in 5..8
                    val uprightWidth = if (turned) height else width
                    val uprightHeight = if (turned) width else height
                    val (targetWidth, targetHeight) = GreyPlan.targetSize(uprightWidth, uprightHeight, maxWidth, KoboImageConfig.MAX_PIXELS)
                    val sample = GreyPlan.sampleSize(uprightWidth, uprightHeight, targetWidth, targetHeight)
                    val param = reader.defaultReadParam.apply { if (sample > 1) setSourceSubsampling(sample, sample, 0, 0) }
                    val image = try {
                        reader.read(0, param)
                    } catch (_: OutOfMemoryError) {
                        null
                    } ?: return null
                    return greyOf(image).oriented(orientation)
                } catch (_: IOException) {
                    return null
                } catch (_: RuntimeException) {
                    return null
                } finally {
                    reader.dispose()
                }
            }
        }

        /** The image as grey on white, read a band of rows at a time. */
        private fun greyOf(image: BufferedImage): GreyImage {
            val w = image.width
            val h = image.height
            val out = ByteArray(w * h)
            val band = maxOf(1, BAND_PIXELS / w)
            val row = IntArray(w * band)
            var y = 0
            while (y < h) {
                val rows = minOf(band, h - y)
                image.getRGB(0, y, w, rows, row, 0, w)
                for (i in 0 until w * rows) out[y * w + i] = GreyImage.greyOnWhite(row[i]).toByte()
                y += rows
            }
            return GreyImage(w, h, out)
        }

        private const val BAND_PIXELS = 256 * 1024
    }
}

/** The EXIF orientation (1..8) of a JPEG, read from its APP1 segment; 1 when there is none. */
object ExifOrientation {
    fun of(file: File): Int = try {
        DataInputStream(file.inputStream().buffered()).use(::read)
    } catch (_: Exception) {
        1
    }

    private fun read(input: DataInputStream): Int {
        if (input.readUnsignedShort() != 0xFFD8) return 1
        while (true) {
            val marker = input.readUnsignedShort()
            if (marker and 0xFF00 != 0xFF00 || marker == 0xFFDA || marker == 0xFFD9) return 1
            val length = input.readUnsignedShort() - 2
            if (length < 0) return 1
            if (marker != 0xFFE1) {
                input.skipNBytes(length.toLong())
                continue
            }
            val segment = ByteArray(length).also(input::readFully)
            return orientationIn(segment) ?: continue
        }
    }

    /** The orientation in an APP1 [segment] that starts `Exif\0\0`, or null when it is not EXIF. */
    fun orientationIn(segment: ByteArray): Int? {
        if (segment.size < 14 || String(segment, 0, 4, Charsets.US_ASCII) != "Exif") return null
        val tiff = 6
        val little = segment[tiff].toInt() == 'I'.code
        fun u16(at: Int): Int {
            val a = segment[at].toInt() and 0xFF
            val b = segment[at + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Int): Int = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
        val ifd = tiff + u32(tiff + 4)
        if (ifd + 2 > segment.size) return 1
        val entries = u16(ifd)
        for (i in 0 until entries) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > segment.size) break
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }
}

/**
 * Prepared images on disk, one file each, written whole under a temporary name and renamed.
 * Reading one marks it used; past [maxBytes] the least recently used go until it is down to
 * nine tenths. The total is learned from the directory on first use, so it survives restarts.
 */
private class PreparedCache(private val directory: File, private val maxBytes: Long) {
    private val lock = Mutex()
    private var total = -1L

    private fun file(key: String) = File(directory, key)

    fun has(key: String): Boolean = file(key).isFile

    fun get(key: String): PreparedImage? {
        val file = file(key)
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val format = when (ImageFormats.kindOf(bytes.copyOf(minOf(bytes.size, 16)))) {
            ImageKind.JPEG -> GreyFormat.JPEG
            ImageKind.PNG -> GreyFormat.PNG
            else -> return null
        }
        file.setLastModified(System.currentTimeMillis())
        return PreparedImage(bytes, format)
    }

    suspend fun put(key: String, image: PreparedImage) = lock.withLock {
        directory.mkdirs()
        val temp = File(directory, "$key.tmp")
        temp.writeBytes(image.bytes)
        val target = file(key)
        val replaced = target.length()
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withLock
        }
        if (total < 0) total = entries().sumOf { it.length() } else total += image.bytes.size - replaced
        if (total > maxBytes) trim()
    }

    private fun entries(): List<File> = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }

    private fun trim() {
        val target = maxBytes / 10 * 9
        for (file in entries().sortedBy { it.lastModified() }) {
            if (total <= target) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}

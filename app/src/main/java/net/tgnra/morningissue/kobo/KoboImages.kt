package net.tgnra.morningissue.kobo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
import net.tgnra.morningissue.data.images.OriginalSource
import okhttp3.OkHttpClient

/** Every number the Kobo's image preparation works to. */
object KoboImageConfig {
    /** The width for a Kobo whose pairing code gives none: a Kobo Libra's short side. */
    const val DEFAULT_WIDTH = 1264

    /** `w` is held to this range: below it a picture is a smudge, above it no Kobo's screen. */
    const val MIN_WIDTH = 160
    const val MAX_WIDTH = 2048

    /**
     * The most pixels a prepared image has. A Libra's whole screen is 1264 × 1680, 2.1 million;
     * a picture longer than about two screens at full width is sent narrower.
     */
    const val MAX_PIXELS = 4_000_000

    /** Quality of the greyscale JPEGs. E-ink shows 16 greys, so more buys nothing visible. */
    const val JPEG_QUALITY = 80

    /** Downloads at a time, however many pictures a package build prepares at once. */
    const val PARALLEL_FETCHES = 3

    /** Images decoded at a time; a large original decoded for a 1264-pixel width can take 60 MB. */
    const val PARALLEL_DECODES = 2

    /** The cache of prepared images in the app's cache directory; the least recently used go past this. */
    const val CACHE_MAX_BYTES = 256L * 1024 * 1024
}

/** A prepared image, ready to send. */
class PreparedImage(val bytes: ByteArray, val format: GreyFormat)

/**
 * A picture for a Kobo, as protocol v1's `GET /v1/images` defined it (`docs/kobo-sync.md`) and
 * a relay package carries it: a source address turned into a greyscale JPEG or PNG at most `w`
 * pixels wide.
 *
 * The source is the image store's file when it has one, then Coil's disk cache ([originals]),
 * then the internet with the store's client, size limit and timeout ([ImageDownload]).
 * Prepared images are kept in [cacheDirectory], named by address and width, up to
 * [maxCacheBytes], so the next package finds them ready. Two requests for the same image at once
 * share one preparation, and an address that could not be got or decoded is remembered for the
 * life of this object, which relay sync makes once per package, so it is not tried again.
 *
 * What is tested how: the decisions and every step after decoding are pure ([GreyPlan],
 * [GreyImage], [GreyPng], [GreyJpeg]) and run on the JVM; decoding itself is Android's
 * `BitmapFactory`, exercised end to end under Robolectric's native graphics in `KoboImagesTest`.
 */
class KoboImages(
    /** The image store's file for an address (`ImageFiles.fileFor`); it may not exist. */
    private val storeFile: (String) -> File,
    /** The app's shared client, asked for on the first download. */
    private val client: () -> OkHttpClient,
    private val cacheDirectory: File,
    private val originals: OriginalSource = OriginalSource.None,
    private val maxCacheBytes: Long = KoboImageConfig.CACHE_MAX_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val imageClient: OkHttpClient by lazy { ImageDownload.client(client()) }
    private val fetches = Semaphore(KoboImageConfig.PARALLEL_FETCHES)
    private val decodes = Semaphore(KoboImageConfig.PARALLEL_DECODES)
    private val cache = PreparedCache(cacheDirectory, maxCacheBytes)

    /** Addresses that failed since this object was made. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Preparations under way, by address; each completes when it ends, however it ends. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tempsCleared = AtomicBoolean(false)

    /** The image for [url] at most [width] pixels wide, or null when it cannot be had. Never throws except on cancellation. */
    suspend fun prepare(url: String, width: Int): PreparedImage? = prepare(url, listOf(width))[width]

    /**
     * [url] at each of [widths], from one download and one decode; a width missing from the
     * result could not be prepared. Never throws except on cancellation.
     */
    suspend fun prepare(url: String, widths: List<Int>): Map<Int, PreparedImage> = withContext(dispatcher) { shared(url, widths.distinct()) }

    private suspend fun shared(url: String, widths: List<Int>): Map<Int, PreparedImage> {
        if (!KoboImagePlan.mayFetch(url)) return emptyMap()
        while (true) {
            val ready = cached(url, widths)
            val missing = widths - ready.keys
            if (missing.isEmpty()) return ready
            // One preparation of an address at a time. Whoever comes second waits for it to end
            // and looks again: it finds the cache filled, the address failed, or (if the first
            // was cancelled, or wanted other widths) its own turn.
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
                if (made == null) failed += url else made.forEach { (w, image) -> cache.put(KoboImagePlan.cacheKey(url, w), image) }
                return ready + made.orEmpty()
            } finally {
                inFlight.remove(url, mine)
                mine.complete(Unit)
            }
        }
    }

    private fun cached(url: String, widths: List<Int>): Map<Int, PreparedImage> =
        widths.mapNotNull { w -> cache.get(KoboImagePlan.cacheKey(url, w))?.let { w to it } }.toMap()

    /** Whether [url] at [width] is already prepared. */
    fun isCached(url: String, width: Int): Boolean = cache.has(KoboImagePlan.cacheKey(url, width))

    /** [url] prepared at each of [widths]; null when it cannot be got or decoded. */
    private suspend fun make(url: String, widths: List<Int>): Map<Int, PreparedImage>? {
        val stored = storeFile(url).takeIf { it.isFile && it.length() > 0 }
        if (stored != null) return decodes.withPermit { runInterruptible { encode(stored, widths) } }
        // It failed earlier in this run: the site is not asked again, though the store is looked in each time.
        if (url in failed) return null
        val temp = newTemp()
        try {
            val fromCache = originals.copyTo(url, temp) && temp.length() in 1..ImageStoreConfig.MAX_DOWNLOAD_BYTES
            // The clock starts once the download has its turn, not while it waits behind others.
            val fetched = fromCache || fetches.withPermit {
                withTimeoutOrNull(ImageStoreConfig.DOWNLOAD_TIMEOUT_MILLIS) { ImageDownload.toFile(imageClient, url, temp) } ?: false
            }
            if (!fetched) return null
            return decodes.withPermit { runInterruptible { encode(temp, widths) } }
        } finally {
            temp.delete()
        }
    }

    /** A file for a download; the first call clears what a killed process left. */
    private fun newTemp(): File {
        val directory = File(cacheDirectory, "tmp")
        if (tempsCleared.compareAndSet(false, true)) directory.listFiles().orEmpty().forEach { it.delete() }
        directory.mkdirs()
        return File(directory, UUID.randomUUID().toString())
    }

    /**
     * Decodes [source] once, at the largest of [widths], and prepares it at each; null for what
     * is not a picture this phone can decode, SVG included.
     */
    private fun encode(source: File, widths: List<Int>): Map<Int, PreparedImage>? {
        val head = source.inputStream().use { it.readNBytes(ImageFormats.HEAD_BYTES) }
        val kind = ImageFormats.kindOf(head)
        if (kind == ImageKind.SVG || kind == ImageKind.NOT_AN_IMAGE) return null
        val grey = decodeGrey(source, widths.max()) ?: return null
        return widths.associateWith { KoboImagePlan.encode(grey, it) }
    }

    /**
     * [source] upright and grey, decoded at the largest power-of-two reduction that keeps it
     * at least as large as it will be sent. `BitmapFactory` gives the first frame of an
     * animated GIF or WebP.
     */
    private fun decodeGrey(source: File, maxWidth: Int): GreyImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = orientationOf(source)
        val turned = orientation in 5..8
        val uprightWidth = if (turned) bounds.outHeight else bounds.outWidth
        val uprightHeight = if (turned) bounds.outWidth else bounds.outHeight
        val (targetWidth, targetHeight) = GreyPlan.targetSize(uprightWidth, uprightHeight, maxWidth, KoboImageConfig.MAX_PIXELS)
        val options = BitmapFactory.Options().apply {
            inSampleSize = GreyPlan.sampleSize(uprightWidth, uprightHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = try {
            BitmapFactory.decodeFile(source.path, options)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        val grey = try {
            greyOf(bitmap)
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            bitmap.recycle()
        }
        return grey.oriented(orientation)
    }

    /** The bitmap as grey on white, read a band of rows at a time so that no second full-size copy is made. */
    private fun greyOf(bitmap: Bitmap): GreyImage {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h)
        val band = maxOf(1, BAND_PIXELS / w)
        val row = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            bitmap.getPixels(row, 0, w, 0, y, w, rows)
            for (i in 0 until w * rows) out[y * w + i] = GreyImage.greyOnWhite(row[i]).toByte()
            y += rows
        }
        return GreyImage(w, h, out)
    }

    private fun orientationOf(source: File): Int = try {
        ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val BAND_PIXELS = 256 * 1024
    }
}

/** The pure parts of preparing an image for a Kobo. */
object KoboImagePlan {
    /** `w` from a pairing code: absent or unreadable is [KoboImageConfig.DEFAULT_WIDTH], anything else is held to the allowed range. */
    fun width(raw: String?): Int =
        (raw?.trim()?.toIntOrNull() ?: KoboImageConfig.DEFAULT_WIDTH).coerceIn(KoboImageConfig.MIN_WIDTH, KoboImageConfig.MAX_WIDTH)

    /**
     * Whether [url] is worth trying: a web address whose path does not end in `.svg`. SVG is
     * not rasterised in v1, so it is refused before anything is downloaded; one served from
     * an address without the extension is refused when its bytes are seen.
     */
    fun mayFetch(url: String): Boolean {
        if (!ImageDownload.isWebAddress(url)) return false
        val path = url.substringBefore('#').substringBefore('?')
        return !path.endsWith(".svg", ignoreCase = true) && !path.endsWith(".svgz", ignoreCase = true)
    }

    /** The cache's name for [url] at [width]. */
    fun cacheKey(url: String, width: Int): String =
        MessageDigest.getInstance("SHA-256").digest("$width\n$url".toByteArray()).take(20).joinToString("") { "%02x".format(it) }

    /** [image] at most [maxWidth] wide (and within [KoboImageConfig.MAX_PIXELS]), as JPEG or PNG by [GreyPlan.formatFor]. */
    fun encode(image: GreyImage, maxWidth: Int): PreparedImage {
        val (w, h) = GreyPlan.targetSize(image.width, image.height, maxWidth, KoboImageConfig.MAX_PIXELS)
        val scaled = image.scaledTo(w, h)
        return when (val format = GreyPlan.formatFor(scaled.histogram())) {
            GreyFormat.PNG -> PreparedImage(GreyPng.encode(scaled), format)
            GreyFormat.JPEG -> PreparedImage(GreyJpeg.encode(scaled, KoboImageConfig.JPEG_QUALITY), format)
        }
    }
}

/**
 * Prepared images on disk, one file each, written whole under a temporary name and renamed.
 * Reading one marks it used; past [maxBytes] the least recently used go until it is down to
 * nine tenths, so that the trim does not run on every write.
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

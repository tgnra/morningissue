package net.tgnra.morningissue.kobo

import java.security.MessageDigest
import net.tgnra.morningissue.data.images.ImageDownload

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

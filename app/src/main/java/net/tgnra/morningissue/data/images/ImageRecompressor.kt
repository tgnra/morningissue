package net.tgnra.morningissue.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File

/** What [ImageRecompressor.recompress] wrote. */
data class RecompressedImage(val width: Int, val height: Int, val format: Bitmap.CompressFormat)

/**
 * Decodes an image file no larger than it will be shown, turns it the right way up and writes
 * it again as lossy WebP.
 *
 * - **Size.** The file is decoded at the largest power-of-two reduction that still leaves its
 *   longest edge at [maxEdge] or more, then scaled to [maxEdge] exactly. A picture already
 *   within [maxEdge] keeps its size: nothing is ever enlarged. The decoder never holds the
 *   full-size pixels, so a 40-megapixel photograph costs about 10 MB while it is handled.
 * - **Rotation.** `BitmapFactory` ignores the EXIF orientation and the WebP written here
 *   carries no EXIF, so the turn or mirror the tag asks for is applied to the pixels.
 * - **Format.** `WEBP_LOSSY` from Android 11. Before that the one `WEBP` format is used, which
 *   is lossy below quality 100 and keeps transparency, so no picture is flattened onto a
 *   background. Should the encoder refuse (it has not been seen to), an opaque picture is
 *   written as JPEG and one with transparency is reported as not re-encoded, and the caller
 *   keeps its original.
 *
 * Whether the result is worth keeping (it may be larger than a well-compressed original) is
 * the caller's decision.
 */
class ImageRecompressor(
    private val maxEdge: Int = ImageStoreConfig.MAX_EDGE_PX,
    private val quality: Int = ImageStoreConfig.QUALITY,
) {
    /** Returns null, leaving no file at [target], when [source] cannot be decoded or encoded. */
    fun recompress(source: File, target: File): RecompressedImage? {
        val bitmap = decode(source) ?: return null
        try {
            val format = encode(bitmap, target)
            if (format == null) {
                target.delete()
                return null
            }
            return RecompressedImage(bitmap.width, bitmap.height, format)
        } finally {
            bitmap.recycle()
        }
    }

    /** [source] as it will be stored: at most [maxEdge] on its longest edge, upright, in sRGB. */
    fun decode(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = try {
            BitmapFactory.decodeFile(source.path, options)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null

        val matrix = Matrix()
        val longest = maxOf(decoded.width, decoded.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
        if (scale < 1f) matrix.postScale(scale, scale)
        val turned = applyOrientation(matrix, orientationOf(source))
        if (scale == 1f && !turned) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            null
        }
    }

    private fun orientationOf(source: File): Int = try {
        ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        // Not a format that carries EXIF, or a damaged block: as decoded.
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Adds the EXIF turn to [matrix]; false when the picture is already upright. */
    private fun applyOrientation(matrix: Matrix, orientation: Int): Boolean {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return false
        }
        return true
    }

    private fun encode(bitmap: Bitmap, target: File): Bitmap.CompressFormat? {
        val webp = webpFormat()
        if (write(bitmap, webp, target)) return webp
        if (!bitmap.hasAlpha() && write(bitmap, Bitmap.CompressFormat.JPEG, target)) return Bitmap.CompressFormat.JPEG
        return null
    }

    private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat, target: File): Boolean = try {
        target.outputStream().use { bitmap.compress(format, quality, it) } && target.length() > 0
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Lossy WebP by whichever name this Android has for it. */
        @Suppress("DEPRECATION")
        fun webpFormat(sdk: Int = Build.VERSION.SDK_INT): Bitmap.CompressFormat =
            if (sdk >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

        /**
         * The `inSampleSize` for a [width] × [height] image: the largest power of two that
         * leaves the longest edge at [maxEdge] or more, so the final scaling only ever reduces.
         */
        fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
            val longest = maxOf(width, height)
            var sample = 1
            while (longest / (sample * 2) >= maxEdge) sample *= 2
            return sample
        }
    }
}

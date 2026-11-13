package net.tgnra.morningissue.ui.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in one camera frame's luminance (the Y plane), with ZXing's core library, so
 * that scanning needs no Google Play services. Pure: the camera hands in bytes.
 */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** The text of a QR code in the frame, or null. [rowStride] is the Y plane's bytes per row. */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        // An e-reader's screen is dark on light, but a phone in a dark room may see it the other way round.
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: ReaderException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        reader.reset()
    }
}

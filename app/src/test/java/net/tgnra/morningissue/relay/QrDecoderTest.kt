package net.tgnra.morningissue.relay

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A pairing code drawn as a camera frame would see it, read back by [QrDecoder]. */
class QrDecoderTest {
    private val code = "morningissue:pair?v=1&s=${RelayCrypto.b64(ByteArray(32) { it.toByte() })}&d=b7e2-41aa&n=Kobo%20Libra%202&w=1264"

    /** The code drawn into a [width] × [height] frame with padding bytes at the end of each row. */
    private fun frame(text: String, width: Int, height: Int, stride: Int, inverted: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400, mapOf(EncodeHintType.MARGIN to 4))
        val out = ByteArray(stride * height) { 0x55 }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until height) for (x in 0 until width) {
            val mx = x - left
            val my = y - top
            val dark = mx in 0 until matrix.width && my in 0 until matrix.height && matrix[mx, my]
            val light = if (inverted) 30 else 230
            val ink = if (inverted) 230 else 20
            out[y * stride + x] = (if (dark) ink else if (mx in 0 until matrix.width && my in 0 until matrix.height) light else 128).toByte()
        }
        return out
    }

    @Test
    fun readsACodeInAFrame() {
        val decoded = QrDecoder().decode(frame(code, 640, 480, 704), 640, 480, 704)
        assertEquals(code, decoded)
        assertEquals(PairingParse.Ok::class, RelayPairingUri.parse(decoded!!)::class)
    }

    @Test
    fun readsALightOnDarkCode() {
        assertEquals(code, QrDecoder().decode(frame(code, 640, 480, 640, inverted = true), 640, 480))
    }

    @Test
    fun aFrameWithoutACodeIsNull() {
        assertNull(QrDecoder().decode(ByteArray(640 * 480) { (it % 251).toByte() }, 640, 480))
    }
}

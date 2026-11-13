package net.tgnra.morningissue.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bearer tokens. A phone's is 32 random bytes; a Kobo's is derived from the secret in its
 * pairing code. Either way the token is sent as unpadded base64url and only the hex SHA-256 of
 * that string is stored.
 */
object Tokens {
    private val random = SecureRandom()

    /** The HKDF `info` that turns a Kobo's pairing secret into its bearer token (section 6). */
    const val KOBO_TOKEN_INFO = "morningissue server token v1"

    const val SECRET_BYTES = 32

    /** A new phone token. */
    fun newToken(): String = b64url(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /** What the database keeps of [token]: the hex SHA-256 of its UTF-8 text. */
    fun hash(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    /** A Kobo's token: `b64url(HKDF-SHA256(S, salt = empty, info = "morningissue server token v1", 32 bytes))`. */
    fun koboToken(secret: ByteArray): String = b64url(hkdf(secret, KOBO_TOKEN_INFO, SECRET_BYTES))

    /** HKDF-SHA256 (RFC 5869) with an empty salt, which means a salt of 32 zero bytes. */
    fun hkdf(secret: ByteArray, info: String, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(ByteArray(32), secret)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - filled)
            System.arraycopy(previous, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes unpadded or padded base64url; null if it is not. */
    fun unb64url(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text.trimEnd('=')) }.getOrNull()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

package net.tgnra.morningissue.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of relay sync, protocol relay/1 (`docs/relay-sync.md`): HKDF, kids, the
 * chunked MIR1 envelope and request signing. Platform APIs only (`javax.crypto`), no Android
 * imports, so all of it runs and is tested on the JVM against `docs/relay-sync-fixtures/`.
 */
object RelayCrypto {
    const val KEY_BYTES = 32

    const val INFO_AUTH = "morningissue relay auth v1"
    const val INFO_ENC = "morningissue relay enc v1"
    const val INFO_OWNER = "morningissue relay owner v1"

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    /**
     * HKDF-SHA256 (RFC 5869) with an empty salt and [length] bytes of output. An empty salt is
     * HashLen zero bytes; `SecretKeySpec` refuses an empty key, and HMAC pads a short key with
     * zeros anyway, so the two are the same.
     */
    fun hkdf(secret: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(ByteArray(32), secret)
        val out = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[2 * i] = HEX[v ushr 4]
            chars[2 * i + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** RFC 4648 §5 without padding, as everywhere in relay/1. */
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Base64url, with or without padding; null when it does not decode. */
    fun unb64(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text.trim()) }.getOrNull()

    /** A key's kid: `base64url(sha256(key))`, 43 characters. */
    fun kid(key: ByteArray): String = b64(sha256(key))

    fun authKey(deviceSecret: ByteArray): ByteArray = hkdf(deviceSecret, INFO_AUTH)
    fun encKey(deviceSecret: ByteArray): ByteArray = hkdf(deviceSecret, INFO_ENC)
    fun ownerKey(ownerSecret: ByteArray): ByteArray = hkdf(ownerSecret, INFO_OWNER)
}

/** An envelope that is not one, or that fails: wrong key, tampered, truncated, reordered. */
class EnvelopeException(message: String) : IOException(message)

/**
 * The MIR1 envelope: a 17-byte header, then the plaintext in AES-256-GCM chunks, each bound to
 * the header, its index, whether it is the last, and a context string naming the envelope's
 * place. There is always a final chunk, empty when the plaintext fills the chunks exactly.
 */
object Mir1 {
    const val KIND_PACKAGE: Byte = 1
    const val KIND_CHANGES: Byte = 2
    const val KIND_WRAPPED_KEY: Byte = 3

    const val HEADER_BYTES = 17
    const val TAG_BYTES = 16
    const val PREFIX_BYTES = 8

    /** What writers use. */
    const val CHUNK_SIZE = 1 shl 20
    const val MIN_CHUNK = 1 shl 10
    const val MAX_CHUNK = 16 shl 20

    private val MAGIC = "MIR1".toByteArray(Charsets.US_ASCII)

    fun packageContext(packageId: String) = "package|$packageId"
    fun changesContext(deviceKid: String, batch: String) = "changes|$deviceKid|$batch"
    fun wrapContext(deviceKid: String, packageId: String) = "wrap|$deviceKid|$packageId"

    fun header(kind: Byte, prefix: ByteArray, chunkSize: Int): ByteArray {
        require(prefix.size == PREFIX_BYTES)
        val out = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(out)
        out[4] = kind
        prefix.copyInto(out, 5)
        putInt(out, 13, chunkSize)
        return out
    }

    private fun putInt(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }

    private fun int(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
            ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)

    private fun nonce(prefix: ByteArray, index: Int): ByteArray {
        val out = ByteArray(12)
        prefix.copyInto(out)
        putInt(out, 8, index)
        return out
    }

    private fun aad(header: ByteArray, index: Int, final: Boolean, context: ByteArray): ByteArray {
        val out = ByteArray(header.size + 5 + context.size)
        header.copyInto(out)
        putInt(out, header.size, index)
        out[header.size + 4] = if (final) 1 else 0
        context.copyInto(out, header.size + 5)
        return out
    }

    private fun sealChunk(key: ByteArray, header: ByteArray, prefix: ByteArray, index: Int, final: Boolean, context: ByteArray, plain: ByteArray, length: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index)))
        cipher.updateAAD(aad(header, index, final, context))
        return cipher.doFinal(plain, 0, length)
    }

    /**
     * An output stream that encrypts what is written to it into [out] as one envelope, a chunk
     * at a time; [close] writes the final chunk and closes [out]. Memory use is one chunk.
     */
    class SealingOutputStream(
        out: OutputStream,
        private val key: ByteArray,
        kind: Byte,
        context: String,
        private val chunkSize: Int = CHUNK_SIZE,
        private val prefix: ByteArray = RelayCrypto.randomBytes(PREFIX_BYTES),
    ) : FilterOutputStream(out) {
        private val header = header(kind, prefix, chunkSize)
        private val context = context.toByteArray(Charsets.UTF_8)
        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var index = 0
        private var closed = false

        init {
            require(key.size == RelayCrypto.KEY_BYTES)
            require(chunkSize > 0)
            out.write(header)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var from = off
            var left = len
            while (left > 0) {
                // A full buffer is only sealed once more bytes arrive, since only then is it known not to be the last.
                if (filled == chunkSize) emit(final = false)
                val n = minOf(left, chunkSize - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        private fun emit(final: Boolean) {
            out.write(sealChunk(key, header, prefix, index, final, context, buffer, filled))
            index++
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                // A full buffer is not last: an empty final chunk follows it.
                if (filled == chunkSize) emit(final = false)
                emit(final = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    /** [plain] as one envelope. */
    fun seal(
        key: ByteArray,
        kind: Byte,
        context: String,
        plain: ByteArray,
        chunkSize: Int = CHUNK_SIZE,
        prefix: ByteArray = RelayCrypto.randomBytes(PREFIX_BYTES),
    ): ByteArray {
        val out = ByteArrayOutputStream(HEADER_BYTES + plain.size + TAG_BYTES * (plain.size / chunkSize + 1))
        SealingOutputStream(out, key, kind, context, chunkSize, prefix).use { it.write(plain) }
        return out.toByteArray()
    }

    /**
     * Decrypts the envelope read from [input] into [output], chunk by chunk, checking every
     * chunk before its plaintext is written. Throws [EnvelopeException] for a bad header, a
     * kind other than [kind], a chunk size outside 1 KiB..16 MiB, any chunk that fails, a last
     * chunk not marked final, or data after the final chunk. On a throw, what was written to
     * [output] so far must be discarded. Returns the plaintext's length.
     */
    fun open(
        key: ByteArray,
        kind: Byte,
        context: String,
        input: InputStream,
        output: OutputStream,
        maxPlainBytes: Long = Long.MAX_VALUE,
        /** Only for the test vectors, which use 16-byte chunks that a reader refuses. */
        minChunk: Int = MIN_CHUNK,
    ): Long {
        val header = ByteArray(HEADER_BYTES)
        if (readFully(input, header, 0, HEADER_BYTES) != HEADER_BYTES) throw EnvelopeException("short header")
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) throw EnvelopeException("not MIR1")
        if (header[4] != kind) throw EnvelopeException("kind ${header[4]}, expected $kind")
        val chunkSize = int(header, 13)
        if (chunkSize !in minChunk.toLong()..MAX_CHUNK.toLong()) throw EnvelopeException("chunk size $chunkSize")
        val prefix = header.copyOfRange(5, 13)
        val contextBytes = context.toByteArray(Charsets.UTF_8)
        val sealedSize = chunkSize.toInt() + TAG_BYTES
        val buffer = ByteArray(sealedSize)
        val body = PushbackInputStream(input, 1)
        var index = 0L
        var total = 0L
        while (true) {
            val got = readFully(body, buffer, 0, sealedSize)
            if (got < TAG_BYTES) throw EnvelopeException("truncated")
            // A chunk is the last when it ends the body.
            val last = got < sealedSize || body.read().also { if (it >= 0) body.unread(it) } < 0
            if (index > 0xffffffffL) throw EnvelopeException("too many chunks")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index.toInt())))
            cipher.updateAAD(aad(header, index.toInt(), last, contextBytes))
            val plain = try {
                cipher.doFinal(buffer, 0, got)
            } catch (_: AEADBadTagException) {
                throw EnvelopeException("chunk $index fails")
            } catch (e: java.security.GeneralSecurityException) {
                throw EnvelopeException("chunk $index: ${e.message}")
            }
            total += plain.size
            if (total > maxPlainBytes) throw EnvelopeException("too large")
            output.write(plain)
            if (last) return total
            index++
        }
    }

    /** The plaintext of an envelope held in memory. */
    fun open(key: ByteArray, kind: Byte, context: String, envelope: ByteArray, maxPlainBytes: Long = Long.MAX_VALUE, minChunk: Int = MIN_CHUNK): ByteArray {
        val out = ByteArrayOutputStream()
        open(key, kind, context, ByteArrayInputStream(envelope), out, maxPlainBytes, minChunk)
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = try {
                input.read(target, offset + read, length - read)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            read += n
        }
        return read
    }
}

/**
 * Request signing: `Authorization: MIR1 kid=…, ts=…, sig=…`, an HMAC-SHA256 over the method,
 * the path and query exactly as sent, the time and the body's SHA-256.
 */
object RelaySigning {
    fun canonical(method: String, pathAndQuery: String, ts: Long, bodySha256: ByteArray): String =
        "MIR1\n$method\n$pathAndQuery\n$ts\n${RelayCrypto.hex(bodySha256)}"

    fun signature(key: ByteArray, method: String, pathAndQuery: String, ts: Long, bodySha256: ByteArray): String =
        RelayCrypto.b64(RelayCrypto.hmac(key, canonical(method, pathAndQuery, ts, bodySha256).toByteArray(Charsets.UTF_8)))

    fun header(key: ByteArray, method: String, pathAndQuery: String, ts: Long, bodySha256: ByteArray): String =
        "MIR1 kid=${RelayCrypto.kid(key)}, ts=$ts, sig=${signature(key, method, pathAndQuery, ts, bodySha256)}"

    val EMPTY_SHA256: ByteArray = RelayCrypto.sha256(ByteArray(0))
}

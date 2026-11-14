package net.tgnra.morningissue.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Every vector in `docs/relay-sync-fixtures/vectors.json`, which the relay (Go) and the Kobo
 * (Rust) reproduce too, and what a reader must refuse: tampering, truncation, reordering.
 */
class RelayCryptoTest {
    private val vectors: JsonObject = Json.parseToJsonElement(File("../docs/relay-sync-fixtures/vectors.json").readText()).jsonObject

    private fun str(key: String, from: JsonObject = vectors) = from[key]!!.jsonPrimitive.content
    private fun hex(key: String, from: JsonObject = vectors) = unhex(str(key, from))
    private fun obj(key: String) = vectors[key]!!.jsonObject

    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val secret = hex("deviceSecret")
    private val auth = RelayCrypto.authKey(secret)
    private val enc = RelayCrypto.encKey(secret)
    private val prefix = hex("noncePrefix")
    private val packageKey = hex("packageKey")
    private val packageId = str("packageId")

    @Test
    fun keysAndKids() {
        assertEquals(str("deviceAuthKey"), RelayCrypto.hex(auth))
        assertEquals(str("deviceAuthKey_b64"), RelayCrypto.b64(auth))
        assertEquals(str("deviceEncKey"), RelayCrypto.hex(enc))
        assertEquals(str("deviceKid_b64"), RelayCrypto.kid(auth))
        val owner = RelayCrypto.ownerKey(hex("ownerSecret"))
        assertEquals(str("ownerKey"), RelayCrypto.hex(owner))
        assertEquals(str("ownerKey_b64"), RelayCrypto.b64(owner))
        assertEquals(str("ownerKid_b64"), RelayCrypto.kid(owner))
        assertEquals(43, RelayCrypto.kid(owner).length)
    }

    @Test
    fun hkdfMatchesRfc5869TestCase3() {
        // RFC 5869 A.3: SHA-256, empty salt and info, 42 bytes.
        val ikm = ByteArray(22) { 0x0b }
        val okm = RelayCrypto.hkdf(ikm, "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", RelayCrypto.hex(okm))
    }

    /** Seals the vector's plaintext and compares bytes, then opens it back. */
    private fun checkPackage(name: String, plain: ByteArray) {
        val vector = obj(name)
        val chunk = vector["chunkSize"]!!.jsonPrimitive.int
        val context = str("context", vector)
        assertEquals(Mir1.packageContext(packageId), context)
        val sealed = Mir1.seal(packageKey, Mir1.KIND_PACKAGE, context, plain, chunk, prefix)
        assertEquals(name, str("envelope", vector), RelayCrypto.hex(sealed))
        // Streamed in small writes, the same bytes.
        val streamed = ByteArrayOutputStream()
        Mir1.SealingOutputStream(streamed, packageKey, Mir1.KIND_PACKAGE, context, chunk, prefix).use { out -> plain.forEach { out.write(it.toInt()) } }
        assertArrayEquals(sealed, streamed.toByteArray())
        assertArrayEquals(plain, Mir1.open(packageKey, Mir1.KIND_PACKAGE, context, sealed, minChunk = 1))
    }

    @Test
    fun packageEnvelopes() {
        val plain = hex("plaintext")
        checkPackage("package_chunk16", plain)
        checkPackage("package_chunk1MiB", plain)
        checkPackage("package_exact_multiple", hex("plaintext", obj("package_exact_multiple")))
        checkPackage("package_empty", ByteArray(0))
    }

    @Test
    fun aReaderRefusesChunksBelowOneKibibyte() {
        // The 16-byte vectors exercise chunking; a real reader holds chunk sizes to 1 KiB..16 MiB.
        val sealed = unhex(str("envelope", obj("package_chunk16")))
        assertThrows(EnvelopeException::class.java) { Mir1.open(packageKey, Mir1.KIND_PACKAGE, Mir1.packageContext(packageId), sealed) }
        val huge = Mir1.header(Mir1.KIND_PACKAGE, prefix, (16 shl 20) + 1) + ByteArray(32)
        assertThrows(EnvelopeException::class.java) { Mir1.open(packageKey, Mir1.KIND_PACKAGE, "x", huge) }
    }

    @Test
    fun wrappedKey() {
        val vector = obj("wrap")
        val context = Mir1.wrapContext(RelayCrypto.kid(auth), packageId)
        assertEquals(str("context", vector), context)
        val sealed = Mir1.seal(enc, Mir1.KIND_WRAPPED_KEY, context, packageKey, vector["chunkSize"]!!.jsonPrimitive.int, prefix)
        assertEquals(str("envelope_b64", vector), RelayCrypto.b64(sealed))
        assertArrayEquals(hex("plaintext", vector), Mir1.open(enc, Mir1.KIND_WRAPPED_KEY, context, sealed))
    }

    @Test
    fun changesEnvelopeOpensAndReads() {
        val vector = obj("changes")
        val context = Mir1.changesContext(RelayCrypto.kid(auth), str("batch", vector))
        assertEquals(str("context", vector), context)
        val plain = hex("plaintext", vector)
        assertEquals(str("envelope", vector), RelayCrypto.hex(Mir1.seal(enc, Mir1.KIND_CHANGES, context, plain, Mir1.CHUNK_SIZE, prefix)))
        val envelope = hex("envelope", vector)
        assertArrayEquals(plain, Mir1.open(enc, Mir1.KIND_CHANGES, context, envelope))
        val events = RelaySync.readBatch("b7e2", enc, RelayCrypto.kid(auth), "1-2", envelope)!!
        assertEquals(listOf(1L, 2L), events.map { it.seq })
        assertEquals(listOf("opened", "saved"), events.map { it.type })
        // The same batch read for another device, or another range, is dropped.
        assertEquals(null, RelaySync.readBatch("someone-else", enc, RelayCrypto.kid(auth), "1-2", envelope))
        assertEquals(null, RelaySync.readBatch("b7e2", enc, RelayCrypto.kid(auth), "2-2", envelope))
    }

    @Test
    fun signatures() {
        val signed = obj("signature")
        val ts = signed["ts"]!!.jsonPrimitive.long
        val body = hex("body", signed)
        assertEquals(str("sig_b64", signed), RelaySigning.signature(auth, str("method", signed), str("path", signed), ts, RelayCrypto.sha256(body)))
        assertEquals(str("header", signed), RelaySigning.header(auth, str("method", signed), str("path", signed), ts, RelayCrypto.sha256(body)))
        val empty = obj("signature_empty_body")
        val owner = RelayCrypto.ownerKey(hex("ownerSecret"))
        assertEquals(
            str("sig_b64", empty),
            RelaySigning.signature(owner, str("method", empty), str("path", empty), empty["ts"]!!.jsonPrimitive.long, RelaySigning.EMPTY_SHA256),
        )
    }

    // --- What a reader refuses -----------------------------------------------------------

    private val context = Mir1.packageContext(packageId)

    /** 3000 bytes in 1 KiB chunks: chunks of 1024, 1024 and a final 952. */
    private val plain = ByteArray(3000) { (it * 7).toByte() }
    private val sealed = Mir1.seal(packageKey, Mir1.KIND_PACKAGE, context, plain, 1024, prefix)
    private val sealedChunk = 1024 + Mir1.TAG_BYTES

    private fun refuses(envelope: ByteArray, key: ByteArray = packageKey, kind: Byte = Mir1.KIND_PACKAGE, ctx: String = context) {
        assertThrows(EnvelopeException::class.java) { Mir1.open(key, kind, ctx, envelope) }
    }

    @Test
    fun opensAStreamChunkByChunk() {
        val out = ByteArrayOutputStream()
        assertEquals(3000L, Mir1.open(packageKey, Mir1.KIND_PACKAGE, context, OneByteAtATime(sealed), out))
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun refusesTamperedBytes() {
        for (at in listOf(0, 4, 5, 13, 16, Mir1.HEADER_BYTES, Mir1.HEADER_BYTES + sealedChunk + 3, sealed.size - 1)) {
            val tampered = sealed.copyOf()
            tampered[at] = (tampered[at].toInt() xor 1).toByte()
            refuses(tampered)
        }
    }

    @Test
    fun refusesTheWrongKeyKindOrContext() {
        refuses(sealed, key = ByteArray(32))
        refuses(sealed, kind = Mir1.KIND_CHANGES)
        refuses(sealed, ctx = Mir1.packageContext("another"))
    }

    @Test
    fun refusesTruncation() {
        // Cut at a chunk boundary: the last chunk left was not marked final.
        refuses(sealed.copyOf(Mir1.HEADER_BYTES + 2 * sealedChunk))
        refuses(sealed.copyOf(Mir1.HEADER_BYTES + sealedChunk))
        // Cut inside a chunk, inside the header, and down to nothing.
        refuses(sealed.copyOf(sealed.size - 1))
        refuses(sealed.copyOf(Mir1.HEADER_BYTES + 10))
        refuses(sealed.copyOf(Mir1.HEADER_BYTES))
        refuses(sealed.copyOf(10))
        // An exact multiple loses its empty final chunk.
        val exact = Mir1.seal(packageKey, Mir1.KIND_PACKAGE, context, ByteArray(2048), 1024, prefix)
        assertEquals(Mir1.HEADER_BYTES + 3 * sealedChunk - 1024, exact.size)
        refuses(exact.copyOf(exact.size - Mir1.TAG_BYTES))
    }

    @Test
    fun refusesReorderedOrRepeatedChunks() {
        val h = Mir1.HEADER_BYTES
        val first = sealed.copyOfRange(h, h + sealedChunk)
        val second = sealed.copyOfRange(h + sealedChunk, h + 2 * sealedChunk)
        val last = sealed.copyOfRange(h + 2 * sealedChunk, sealed.size)
        val header = sealed.copyOf(h)
        refuses(header + second + first + last)
        refuses(header + first + first + last)
        refuses(header + first + last)
    }

    @Test
    fun refusesDataAfterTheFinalChunk() {
        refuses(sealed + ByteArray(1))
        refuses(sealed + sealed.copyOfRange(Mir1.HEADER_BYTES, Mir1.HEADER_BYTES + sealedChunk))
    }

    @Test
    fun base64urlRoundTripsWithoutPadding() {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        val text = RelayCrypto.b64(bytes)
        assertEquals(false, text.contains('='))
        assertArrayEquals(bytes, RelayCrypto.unb64(text))
        assertEquals(null, RelayCrypto.unb64("not base64!"))
    }

    /** An input stream that hands over one byte per read, as a slow network might. */
    private class OneByteAtATime(bytes: ByteArray) : java.io.InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else inner.read(b, off, 1)
    }
}

package net.tgnra.morningissue.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The asset file itself: that it is the one the code was written for, and what loading it costs. */
class StaticEmbedderAssetTest {
    private val text = "Heatwave pushes electricity demand to a record high across southern Europe"

    /**
     * Stored vectors are keyed by [StaticEmbedder.MODEL_ID], so the asset must not change under
     * an unchanged ID. If this fails because the asset was rebuilt on purpose, bump `MODEL_ID`
     * in `StaticEmbedder.kt` and in `tools/embedding/build_asset.py`, rebuild, regenerate
     * `reference.json`, and only then update the hash here.
     */
    @Test
    fun theAssetIsTheOneThisModelIdStandsFor() {
        val bytes = EmbeddingFixtures.assetFile.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(1, StaticEmbedder.MODEL_ID)
        assertEquals("f272e0bf9c10d076a20bfb3a9551700c0b7219f91a6e935473ae1edc790dc971", sha256)
        assertEquals(7_897_002, bytes.size)
        assertEquals(StaticEmbedder.MODEL_ID, EmbeddingFixtures.reference.modelId)
    }

    @Test
    fun aStreamAndAMappedFileGiveTheSameVectors() {
        val fromStream = EmbeddingFixtures.assetFile.inputStream().use { StaticEmbedder.load(it) }
        assertArrayEquals(EmbeddingFixtures.embedder.embed(text), fromStream.embed(text), 0f)
    }

    @Test
    fun aBufferIsReadFromItsPositionAndLeftWhereItWas() {
        val asset = EmbeddingFixtures.assetFile.readBytes()
        val padded = ByteBuffer.allocate(asset.size + 7)
        padded.position(7)
        padded.put(asset)
        padded.position(7)
        val embedder = StaticEmbedder.load(padded)
        assertEquals(7, padded.position())
        assertArrayEquals(EmbeddingFixtures.embedder.embed(text), embedder.embed(text), 0f)
    }

    @Test
    fun anythingButTheExpectedAssetIsRefused() {
        val asset = EmbeddingFixtures.assetFile.readBytes()
        fun altered(change: (ByteBuffer) -> Unit): ByteBuffer =
            ByteBuffer.wrap(asset.copyOf()).order(ByteOrder.LITTLE_ENDIAN).also(change).also { it.position(0) }

        fun refused(buffer: ByteBuffer, because: String) {
            val error = assertThrows(IllegalArgumentException::class.java) { StaticEmbedder.load(buffer) }
            assertTrue("\"${error.message}\" should mention \"$because\"", error.message.orEmpty().contains(because))
        }

        refused(ByteBuffer.allocate(8), "too short")
        refused(altered { it.put(0, 'X'.code.toByte()) }, "not an embedding asset")
        refused(altered { it.putInt(4, 2) }, "format 2")
        // An asset built for another model ID must not be used with vectors stored under this one.
        refused(altered { it.putInt(8, StaticEmbedder.MODEL_ID + 1) }, "model ${StaticEmbedder.MODEL_ID + 1}")
        refused(altered { it.putInt(16, 128) }, "128 dimensions")
        refused(ByteBuffer.wrap(asset.copyOf(asset.size - 1)), "bytes")
        refused(ByteBuffer.wrap(asset.copyOf(asset.size + 1)), "bytes")
    }

    /** What loading costs here, printed for the record. The heap figure is exact; the times are this machine's. */
    @Test
    fun loadCostOnThisMachine() {
        repeat(3) { StaticEmbedder.load(EmbeddingFixtures.mapAsset()) } // warm-up
        val rounds = 10
        var start = System.nanoTime()
        lateinit var mapped: StaticEmbedder
        repeat(rounds) { mapped = StaticEmbedder.load(EmbeddingFixtures.mapAsset()) }
        val mappedMs = (System.nanoTime() - start) / 1e6 / rounds
        start = System.nanoTime()
        repeat(rounds) { EmbeddingFixtures.assetFile.inputStream().use { StaticEmbedder.load(it) } }
        val streamMs = (System.nanoTime() - start) / 1e6 / rounds
        println("EMBEDDING load: mapped %.1f ms and %d bytes of heap; from a stream %.1f ms and %d bytes of heap".format(
            mappedMs, mapped.heapBytes, streamMs, mapped.heapBytes + EmbeddingFixtures.assetFile.length()))
        // The vocabulary must stay small: it is the part that cannot be paged out.
        assertTrue(mapped.heapBytes < 1_200_000)
    }
}

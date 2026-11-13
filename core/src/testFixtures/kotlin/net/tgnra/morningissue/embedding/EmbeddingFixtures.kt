package net.tgnra.morningissue.embedding

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The real asset and the reference output, shared by the embedding tests. These are plain JVM
 * tests: the asset is read from the top-level `assets/` (Gradle runs unit tests in the module
 * directory, one level below the root), which is the point of [StaticEmbedder.load] taking a
 * buffer and not a `Context`. They live in core's test fixtures so the app's tests share them.
 */
object EmbeddingFixtures {
    val assetFile = File("../assets/${StaticEmbedder.ASSET_PATH}")

    /** The asset memory-mapped, as on a device. */
    fun mapAsset(): ByteBuffer = RandomAccessFile(assetFile, "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }

    /** One embedder for every test; loading is itself tested in `StaticEmbedderAssetTest`. */
    val embedder: StaticEmbedder by lazy { StaticEmbedder.load(mapAsset()) }

    /** A text with the piece IDs the reference tokenizer gives it, unknown words removed. */
    class TokenizerCase(val text: String, val ids: List<Int>)

    /** A text with its piece IDs and the float32 model's unit vector. */
    class EmbeddingCase(val text: String, val ids: List<Int>, val vector: FloatArray)

    /**
     * `reference.json`, written by `tools/embedding/make_reference.py` from the float model and
     * the Hugging Face tokenizer.
     */
    class Reference(
        val modelId: Int,
        val maxPieces: Int,
        val maxChars: Int,
        val tokenizer: List<TokenizerCase>,
        val embeddings: List<EmbeddingCase>,
    )

    val reference: Reference by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/embedding/reference.json")) {
            "reference.json is missing from the test resources"
        }.use { it.readBytes().decodeToString() }
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.ids() = getValue("ids").jsonArray.map { it.jsonPrimitive.int }
        fun JsonObject.text() = getValue("text").jsonPrimitive.content
        Reference(
            modelId = root.getValue("modelId").jsonPrimitive.int,
            maxPieces = root.getValue("maxPieces").jsonPrimitive.int,
            maxChars = root.getValue("maxChars").jsonPrimitive.int,
            tokenizer = root.getValue("tokenizer").jsonArray.map { it.jsonObject }
                .map { TokenizerCase(it.text(), it.ids()) },
            embeddings = root.getValue("embeddings").jsonArray.map { it.jsonObject }.map { case ->
                val vector = case.getValue("vector").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                EmbeddingCase(case.text(), case.ids(), vector)
            },
        )
    }
}

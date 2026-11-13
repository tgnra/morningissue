package net.tgnra.morningissue.embedding

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a post's title and opening text into one vector, so that posts about similar things
 * point in similar directions. No neural network runs: the model is a **static embedding**, a
 * table with one vector per word piece, and a text's vector is the average of its pieces'
 * vectors. That is a few hundred table rows added up per post, cheap enough for a low-RAM phone
 * or an e-ink tablet.
 *
 * The table is `minishlab/potion-base-8M` (MIT licence), a model2vec distillation of
 * `BAAI/bge-base-en-v1.5`: 29,528 BERT uncased word pieces by 256 dimensions, stored in the
 * asset [ASSET_PATH] with 8 bits per value. `tools/embedding/build_asset.py` builds the asset
 * and documents its layout.
 *
 * The rules, which follow model2vec's own inference so that results agree with the reference:
 *
 * - The text is cut to its first [MAX_CODE_POINTS] characters, tokenized (see
 *   [WordPieceTokenizer]) and cut to its first [MAX_PIECES] pieces. Both limits are model2vec's
 *   defaults; a title and two hundred words stay well inside them.
 * - Unknown words are dropped, not averaged in as an "unknown" vector.
 * - The remaining pieces' vectors are averaged with equal weight. The weighting of common
 *   against rare words is already in the table: a frequent piece has a short vector.
 * - The average is scaled to length 1, so the cosine of two results is their dot product.
 * - A text with no known piece (empty, only emoji, a script the vocabulary lacks) has no
 *   vector: [embed] returns null and the caller ranks that post without a text signal.
 *
 * The model is English. Other languages in Latin script are cut into short pieces that carry
 * little meaning, and their vectors are mostly noise; see `StaticEmbedderTest` for what that
 * looks like.
 *
 * Instances are immutable and safe to share between threads. Loading reads the vocabulary into
 * about 1 MB of heap; the 7.5 MB of vectors stay in the buffer passed in, which on a device is
 * the memory-mapped asset (see [EmbedderAsset]).
 */
class StaticEmbedder private constructor(
    private val tokenizer: WordPieceTokenizer,
    /** Row `r` of the table is `scales[r]` times its stored bytes. */
    private val scales: FloatArray,
    /** The int8 table, row-major, positioned at its first byte. Never moved; read through duplicates. */
    private val vectors: ByteBuffer,
) {
    /** Heap this instance holds beyond [vectors], in bytes. */
    internal val heapBytes: Long get() = tokenizer.heapBytes + 4L * scales.size

    /**
     * The unit-length vector of [text], [DIMENSIONS] long, or null when the text has no piece
     * the model knows. Pass the title, a space or line break, then the opening of the body as
     * plain text; anything past the limits is ignored.
     */
    fun embed(text: String): FloatArray? {
        val ids = pieceIds(text)
        if (ids.isEmpty()) return null
        val sum = FloatArray(DIMENSIONS)
        val row = ByteArray(DIMENSIONS)
        val table = vectors.duplicate()
        val base = vectors.position()
        for (id in ids) {
            table.position(base + id * DIMENSIONS)
            table.get(row, 0, DIMENSIONS)
            val scale = scales[id]
            for (d in 0 until DIMENSIONS) sum[d] += scale * row[d]
        }
        return if (normalise(sum)) sum else null
    }

    /**
     * The IDs of the word pieces [embed] averages for [text]: tokenized, cut to the limits, and
     * with unknown words removed. Exposed so that tests can compare against the reference.
     */
    internal fun pieceIds(text: String): IntArray {
        val cut = if (text.length <= MAX_CODE_POINTS) text else {
            text.substring(0, text.offsetByCodePoints(0, minOf(MAX_CODE_POINTS, text.codePointCount(0, text.length))))
        }
        val ids = tokenizer.tokenize(cut, MAX_PIECES)
        val unknown = tokenizer.unknownId
        var known = 0
        for (id in ids) if (id != unknown) ids[known++] = id
        return if (known == ids.size) ids else ids.copyOf(known)
    }

    /** The word pieces of [text] as strings, unknown words as `[UNK]`. For tests and debugging. */
    internal fun pieces(text: String): List<String> =
        tokenizer.tokenize(text, MAX_PIECES).map(tokenizer::piece)

    companion object {
        /**
         * Identifies the table and the tokenizer together. Stored vectors are keyed by it: a
         * vector made under another ID cannot be compared with one made under this one and must
         * be recomputed. Bump it, together with `MODEL_ID` in `tools/embedding/build_asset.py`,
         * whenever the asset's bytes change or [WordPieceTokenizer] would cut any text
         * differently. [toBytes] is part of it too: change the stored form, change the ID.
         */
        const val MODEL_ID: Int = 1

        /** Length of every vector, and of its stored form in bytes. */
        const val DIMENSIONS: Int = 256

        /** Pieces read from one text, unknown words included. model2vec's default `max_length`. */
        const val MAX_PIECES: Int = 512

        /**
         * Characters (code points) read from one text: model2vec cuts to `max_length` times the
         * vocabulary's median piece length, 6, before tokenizing. About 500 English words.
         */
        const val MAX_CODE_POINTS: Int = MAX_PIECES * 6

        /** Where the table lives under `assets/`. */
        const val ASSET_PATH: String = "embedding/potion-base-8m.bin"

        private const val MAGIC = 0x4D45494D // "MIEM", little-endian
        private const val FORMAT_VERSION = 1
        private const val HEADER_BYTES = 32

        /**
         * Reads the asset from [buffer], from its position to its limit. The vectors are not
         * copied: the embedder keeps reading them from [buffer], so pass a memory-mapped file
         * where there is one and do not modify the buffer afterwards.
         *
         * @throws IllegalArgumentException if the bytes are not this app's asset for [MODEL_ID].
         */
        fun load(buffer: ByteBuffer): StaticEmbedder {
            val data = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            require(data.remaining() >= HEADER_BYTES) { "embedding asset is too short" }
            require(data.getInt() == MAGIC) { "not an embedding asset" }
            val version = data.getInt()
            require(version == FORMAT_VERSION) { "embedding asset has format $version, expected $FORMAT_VERSION" }
            val modelId = data.getInt()
            require(modelId == MODEL_ID) { "embedding asset is model $modelId, the code expects $MODEL_ID" }
            val rows = data.getInt()
            val dim = data.getInt()
            val vocabBytes = data.getInt()
            val unknownId = data.getInt()
            data.getInt() // reserved
            require(dim == DIMENSIONS) { "embedding asset has $dim dimensions, expected $DIMENSIONS" }
            require(rows > 0 && vocabBytes > 0) { "embedding asset is empty" }
            val expected = HEADER_BYTES + rows.toLong() * (4 + dim) + vocabBytes
            require(data.limit().toLong() == expected) {
                "embedding asset is ${data.limit()} bytes, its header says $expected"
            }

            val scales = FloatArray(rows)
            data.asFloatBuffer().get(scales)
            data.position(HEADER_BYTES + 4 * rows)
            val vectors = data.slice()
            vectors.limit(rows * dim)
            data.position(data.position() + rows * dim)
            val tokenizer = WordPieceTokenizer.read(data.slice(), rows, unknownId)
            return StaticEmbedder(tokenizer, scales, vectors.asReadOnlyBuffer())
        }

        /**
         * Reads the asset from [stream] to its end, onto the heap (about 8 MB). For tests and
         * for an asset that cannot be memory-mapped. The stream is not closed.
         */
        fun load(stream: InputStream): StaticEmbedder = load(ByteBuffer.wrap(stream.readBytes()))

        /**
         * The compact form of a vector for storage: one signed byte per dimension, the largest
         * component mapped to 127. The vector's length is not kept, and need not be: [fromBytes]
         * returns a unit vector again. A round trip turns the vector by well under a degree
         * (cosine above 0.9999 with the original; `StaticEmbedderTest` measures it).
         */
        fun toBytes(vector: FloatArray): ByteArray {
            require(vector.size == DIMENSIONS) { "expected $DIMENSIONS dimensions, got ${vector.size}" }
            var peak = 0f
            for (v in vector) peak = maxOf(peak, abs(v))
            val bytes = ByteArray(DIMENSIONS)
            if (peak == 0f || !peak.isFinite()) return bytes
            val gain = 127f / peak
            for (d in 0 until DIMENSIONS) bytes[d] = (vector[d] * gain).roundToInt().coerceIn(-127, 127).toByte()
            return bytes
        }

        /**
         * The unit vector stored by [toBytes]. All zeros if the bytes are all zero, which
         * [toBytes] only writes for a zero vector and [embed] never returns.
         */
        fun fromBytes(bytes: ByteArray): FloatArray {
            require(bytes.size == DIMENSIONS) { "expected $DIMENSIONS bytes, got ${bytes.size}" }
            val vector = FloatArray(DIMENSIONS) { bytes[it].toFloat() }
            normalise(vector)
            return vector
        }

        /**
         * Cosine similarity of two vectors from [embed] or [fromBytes], -1 to 1. Both are unit
         * length, so this is their dot product.
         */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "vectors differ in length" }
            var dot = 0f
            for (d in a.indices) dot += a[d] * b[d]
            return dot
        }

        /** Scales [vector] to length 1 in place. False, leaving it alone, if its length is zero. */
        private fun normalise(vector: FloatArray): Boolean {
            var squares = 0.0
            for (v in vector) squares += v.toDouble() * v
            if (squares == 0.0) return false
            val factor = (1.0 / sqrt(squares)).toFloat()
            for (d in vector.indices) vector[d] *= factor
            return true
        }
    }
}

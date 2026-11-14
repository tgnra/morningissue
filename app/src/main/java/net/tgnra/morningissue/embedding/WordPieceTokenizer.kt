package net.tgnra.morningissue.embedding

import java.nio.ByteBuffer
import java.text.Normalizer

/**
 * The BERT "uncased" WordPiece tokenizer, as the model's `tokenizer.json` configures the Hugging
 * Face `tokenizers` library, reduced to what a static embedding needs: text in, piece IDs out.
 *
 * The rules, in the order the reference applies them:
 *
 * 1. **Clean.** U+0000, U+FFFD and every control, format and private-use character is removed
 *    (so a soft hyphen or zero-width space joins its neighbours), except tab, line feed and
 *    carriage return, which with every other Unicode space become a plain space. An unassigned
 *    code point is kept, and makes its word unknown.
 * 2. **CJK.** Each CJK ideograph becomes a word of its own.
 * 3. **Accents.** The text is decomposed (NFD) and every non-spacing mark is dropped: "é" is "e".
 * 4. **Case.** Everything is lowercased.
 * 5. **Words.** The text is split at spaces, and every punctuation character (any ASCII symbol, or
 *    any Unicode punctuation) is a word of its own.
 * 6. **Pieces.** Each word is cut greedily into the longest vocabulary pieces, left to right; a
 *    piece that does not start the word is looked up with a `##` prefix. A word that cannot be
 *    cut completely, or is longer than [MAX_WORD_CODE_POINTS], is one unknown token as a whole.
 *
 * Unknown tokens are reported as [unknownId] and left for the caller to drop, because they still
 * count towards the reference's length limit.
 *
 * Known differences from the reference, none of which matter for ordinary prose:
 * - The literal strings `[CLS]`, `[SEP]`, `[MASK]`, `[PAD]` and `[UNK]` in a text are special
 *   tokens there; here they are punctuation and letters like any other.
 * - Character classes and NFD come from this runtime's Unicode tables, the reference's from the
 *   Rust crates it was built with. A character assigned in a Unicode version that only one side
 *   knows may be classed differently (an invisible format character kept there and removed
 *   here, say). Six thousand random strings across scripts, symbols and control characters
 *   tokenized identically on a desktop JVM when this was written.
 *
 * The vocabulary is held as one character array and an open-addressing hash table over it, not
 * as a map of strings: about 0.9 MB of heap instead of several, and a lookup allocates nothing.
 * Instances are immutable and safe to share between threads.
 */
internal class WordPieceTokenizer private constructor(
    /** Every piece's characters, back to back, in ID order. */
    private val pieceChars: CharArray,
    /** Piece `id` is `pieceChars[pieceStart[id] until pieceStart[id + 1]]`. */
    private val pieceStart: IntArray,
    /** Open addressing with linear probing: a piece ID, or -1 for an empty slot. */
    private val slots: IntArray,
    private val maxPieceLength: Int,
    val unknownId: Int,
) {
    val size: Int get() = pieceStart.size - 1

    /** Heap held by the vocabulary, in bytes (array payloads; headers are negligible). */
    val heapBytes: Long
        get() = 2L * pieceChars.size + 4L * pieceStart.size + 4L * slots.size

    /** The piece with this ID, for tests and debugging. */
    fun piece(id: Int): String = String(pieceChars, pieceStart[id], pieceStart[id + 1] - pieceStart[id])

    /**
     * The piece IDs of [text], at most [maxPieces] of them, unknown words included as
     * [unknownId]. The caller cuts the text to a sensible length first; this reads all of it.
     */
    fun tokenize(text: String, maxPieces: Int): IntArray {
        val out = IntArray(maxPieces)
        var count = 0
        // Canonical decompositions start at U+00C0, so plain ASCII and Latin-1 punctuation skip NFD.
        val source = if (text.any { it >= 'À' }) Normalizer.normalize(text, Normalizer.Form.NFD) else text

        val word = CharArray(MAX_WORD_CODE_POINTS * 2)
        var wordLength = 0
        var wordCodePoints = 0
        val wordPieces = IntArray(word.size)
        val hashes = IntArray(maxPieceLength + 1)

        /** Cuts the pending word into pieces and appends them. Returns false once [out] is full. */
        fun flush(): Boolean {
            if (wordCodePoints == 0) return true
            val length = wordLength
            val tooLong = wordCodePoints > MAX_WORD_CODE_POINTS
            wordLength = 0
            wordCodePoints = 0
            if (count == maxPieces) return false
            if (tooLong) {
                out[count++] = unknownId
                return count < maxPieces
            }
            // The word is cut completely before anything is kept: one uncuttable part makes the
            // whole word unknown, even when the limit would have cut it off before that part.
            var cut = 0
            var start = 0
            while (start < length) {
                val id = longestPiece(word, start, length, hashes)
                if (id < 0) {
                    wordPieces[0] = unknownId
                    cut = 1
                    break
                }
                wordPieces[cut++] = id
                start += pieceStart[id + 1] - pieceStart[id] - (if (start > 0) CONTINUATION.length else 0)
            }
            val kept = minOf(cut, maxPieces - count)
            wordPieces.copyInto(out, count, 0, kept)
            count += kept
            return count < maxPieces
        }

        var i = 0
        while (i < source.length) {
            val raw = source.codePointAt(i)
            i += Character.charCount(raw)
            if (isSpace(raw)) {
                if (!flush()) break
                continue
            }
            if (raw == 0 || raw == 0xFFFD) continue
            when (Character.getType(raw).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
                Character.SURROGATE, Character.NON_SPACING_MARK -> continue
            }
            val c = Character.toLowerCase(raw)
            if (isPunctuation(c) || isCjk(c)) {
                if (!flush()) break
                wordLength = Character.toChars(c, word, 0)
                wordCodePoints = 1
                if (!flush()) break
                continue
            }
            // A word past the limit is unknown whatever follows; count it but stop storing it.
            if (wordCodePoints < MAX_WORD_CODE_POINTS) wordLength += Character.toChars(c, word, wordLength)
            wordCodePoints++
        }
        flush()
        return if (count == maxPieces) out else out.copyOf(count)
    }

    /**
     * The ID of the longest piece that matches [word] from [start], or -1. Hashes of every
     * candidate length are computed in one pass, then tried longest first, so a word costs
     * its length times the longest piece and not its length squared.
     */
    private fun longestPiece(word: CharArray, start: Int, end: Int, hashes: IntArray): Int {
        val continuation = start > 0
        val prefix = if (continuation) CONTINUATION.length else 0
        val longest = minOf(end - start, maxPieceLength - prefix)
        var hash = if (continuation) CONTINUATION_HASH else 0
        for (n in 1..longest) {
            hash = 31 * hash + word[start + n - 1].code
            hashes[n] = hash
        }
        for (n in longest downTo 1) {
            var slot = spread(hashes[n]) and (slots.size - 1)
            while (true) {
                val id = slots[slot]
                if (id < 0) break
                if (matches(id, word, start, n, continuation)) return id
                slot = (slot + 1) and (slots.size - 1)
            }
        }
        return -1
    }

    private fun matches(id: Int, word: CharArray, start: Int, length: Int, continuation: Boolean): Boolean {
        var at = pieceStart[id]
        val prefix = if (continuation) CONTINUATION.length else 0
        if (pieceStart[id + 1] - at != length + prefix) return false
        if (continuation) {
            if (pieceChars[at] != '#' || pieceChars[at + 1] != '#') return false
            at += prefix
        }
        for (k in 0 until length) if (pieceChars[at + k] != word[start + k]) return false
        return true
    }

    companion object {
        /** The reference's `max_input_chars_per_word`: a longer word is unknown without a look. */
        const val MAX_WORD_CODE_POINTS = 100

        private const val CONTINUATION = "##"
        private const val CONTINUATION_HASH = 31 * '#'.code + '#'.code

        private fun spread(hash: Int): Int = hash xor (hash ushr 15)

        /**
         * Reads the vocabulary block of the asset: per piece, in ID order, one length byte and
         * that many UTF-8 bytes. [block] is consumed from its position to its limit.
         */
        fun read(block: ByteBuffer, pieces: Int, unknownId: Int): WordPieceTokenizer {
            val chars = CharArray(block.remaining())
            val starts = IntArray(pieces + 1)
            var used = 0
            var longest = 0
            val bytes = ByteArray(255)
            for (id in 0 until pieces) {
                require(block.hasRemaining()) { "vocabulary ends at piece $id of $pieces" }
                val length = block.get().toInt() and 0xFF
                require(length in 1..block.remaining()) { "vocabulary piece $id is cut short" }
                block.get(bytes, 0, length)
                val piece = String(bytes, 0, length, Charsets.UTF_8)
                starts[id] = used
                piece.toCharArray(chars, used)
                used += piece.length
                longest = maxOf(longest, piece.length)
            }
            require(!block.hasRemaining()) { "vocabulary has bytes left after $pieces pieces" }
            starts[pieces] = used
            require(unknownId in 0 until pieces) { "unknown-token id $unknownId is outside the vocabulary" }

            // At most half full, so probe runs stay short.
            var capacity = 1024
            while (capacity < pieces * 2) capacity = capacity shl 1
            val slots = IntArray(capacity) { -1 }
            for (id in 0 until pieces) {
                var hash = 0
                for (k in starts[id] until starts[id + 1]) hash = 31 * hash + chars[k].code
                var slot = spread(hash) and (capacity - 1)
                while (slots[slot] >= 0) slot = (slot + 1) and (capacity - 1)
                slots[slot] = id
            }
            return WordPieceTokenizer(chars.copyOf(used), starts, slots, longest, unknownId)
        }

        /**
         * Unicode White_Space, less the control characters that cleaning removes first
         * (vertical tab, form feed, U+0085).
         */
        private fun isSpace(c: Int): Boolean = when (c) {
            ' '.code, '\t'.code, '\n'.code, '\r'.code, 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> c in 0x2000..0x200A
        }

        /** Every ASCII character that is not a letter, digit or space, and all Unicode punctuation. */
        private fun isPunctuation(c: Int): Boolean {
            if (c < 0x80) return c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        /** The ideograph blocks BERT separates into single characters. Kana and Hangul are not among them. */
        private fun isCjk(c: Int): Boolean =
            c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF ||
                c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F ||
                c in 0x2B920..0x2CEAF || c in 0x2F800..0x2FA1F
    }
}

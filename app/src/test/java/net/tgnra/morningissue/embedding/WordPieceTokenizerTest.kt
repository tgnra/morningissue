package net.tgnra.morningissue.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer against the real vocabulary. The hand-written cases say what the rules mean; the
 * reference cases at the end prove that the pieces are the ones Hugging Face's tokenizer gives.
 */
class WordPieceTokenizerTest {
    private val embedder = EmbeddingFixtures.embedder

    private fun pieces(text: String): List<String> = embedder.pieces(text)

    @Test
    fun wordsAreLowercasedAndEveryPunctuationMarkStandsAlone() {
        assertEquals(
            listOf("hello", ",", "world", "!", "it", "'", "s", "state", "-", "of", "-", "the", "-", "art", "(", "really", ")", "."),
            pieces("Hello, World! It's state-of-the-art (really)."),
        )
        // ASCII symbols count as punctuation too, so numbers and identifiers come apart.
        assertEquals(
            listOf("$", "1", ",", "200", ".", "50", "3", ".", "14", "%", "x", "##86", "_", "64"),
            pieces("$1,200.50 3.14% x86_64"),
        )
    }

    @Test
    fun accentsAreStrippedBeforeLookup() {
        assertEquals(
            listOf("zoe", "'", "s", "cafe", ",", "naive", "facade"),
            pieces("Zoë's Café, naïve façade"),
        )
        // A combining accent typed separately, a dotted capital I, and Greek capitals.
        assertEquals(listOf("ecole", "istanbul"), pieces("école İstanbul"))
        assertEquals(pieces("σοφιασ"), pieces("ΣΟΦΊΑΣ"))
    }

    @Test
    fun aWordIsCutIntoTheLongestPiecesFromTheLeft() {
        assertEquals(listOf("un", "##bel", "##ie", "##va", "##bly"), pieces("unbelievably"))
        assertEquals(listOf("sour", "##dou", "##gh"), pieces("sourdough"))
        // Nonsense is not "unknown": it falls apart into single letters, which are all pieces.
        assertEquals(listOf("x", "##q", "##z", "##v", "##k"), pieces("xqzvk"))
    }

    @Test
    fun aWordWithACharacterOutsideTheVocabularyIsUnknownAsAWhole() {
        assertEquals(listOf("good", "[UNK]", "news"), pieces("good 😀 news"))
        assertEquals(listOf("good", "[UNK]", "news"), pieces("good new😀s news"))
        // What gets averaged leaves the unknown words out.
        assertEquals(embedder.pieceIds("good news").toList(), embedder.pieceIds("good 😀 news").toList())
        assertTrue(embedder.pieceIds("😀 🚀").isEmpty())
    }

    @Test
    fun aWordOverAHundredCharactersIsUnknownWithoutALook() {
        assertTrue(pieces("a".repeat(100)).none { it == "[UNK]" })
        assertEquals(listOf("[UNK]"), pieces("a".repeat(101)))
        assertEquals(listOf("before", "[UNK]", "after"), pieces("before " + "ab".repeat(500) + " after"))
    }

    @Test
    fun invisibleCharactersVanishAndEverySpaceSeparates() {
        // A soft hyphen and a zero-width space join their neighbours; a no-break space splits.
        assertEquals(pieces("softhyphen zerowidth non breaking"), pieces("soft­hyphen zero​width non breaking"))
        assertEquals(listOf("one", "two", "three", "four"), pieces("one\ttwo\r\nthree　four"))
        assertEquals(pieces("nulbyte"), pieces("nul\u0000by\u0007te�"))
    }

    @Test
    fun emptyAndBlankTextHaveNoPieces() {
        assertEquals(emptyList<String>(), pieces(""))
        assertEquals(emptyList<String>(), pieces(" \t\n  "))
    }

    @Test
    fun ideographsAreOneWordEachAndOtherScriptsFallIntoSingleLetters() {
        assertEquals(listOf("東", "京", "is", "中", "文"), pieces("東京 is 中文"))
        assertEquals(listOf("カ", "##タ", "##カ", "##ナ"), pieces("カタカナ"))
        assertEquals(listOf("м", "##о", "##с", "##к", "##в", "##а"), pieces("Москва"))
        assertEquals(listOf("α", "##θ", "##η", "##ν", "##α"), pieces("Αθήνα"))
        assertEquals(7, pieces("القاهرة").size)
    }

    @Test
    fun everyReferenceCaseGetsTheReferenceIds() {
        val reference = EmbeddingFixtures.reference
        assertTrue(reference.tokenizer.size >= 40)
        for (case in reference.tokenizer + reference.embeddings.map { EmbeddingFixtures.TokenizerCase(it.text, it.ids) }) {
            assertEquals("pieces of \"${case.text.take(60)}\"", case.ids, embedder.pieceIds(case.text).toList())
        }
    }

    @Test
    fun textPastTheLimitsIsCutWhereTheReferenceCutsIt() {
        val reference = EmbeddingFixtures.reference
        assertEquals(StaticEmbedder.MAX_PIECES, reference.maxPieces)
        assertEquals(StaticEmbedder.MAX_CODE_POINTS, reference.maxChars)
        // The reference set holds one text cut by the character limit and one by the piece limit.
        // (Their IDs are checked in everyReferenceCaseGetsTheReferenceIds; this guards the set itself.)
        assertTrue(reference.tokenizer.any {
            it.text.length > StaticEmbedder.MAX_CODE_POINTS && it.ids.size < StaticEmbedder.MAX_PIECES
        })
        assertTrue(reference.tokenizer.any { it.ids.size == StaticEmbedder.MAX_PIECES })
        // The character limit counts code points, not UTF-16 units: these are two units each.
        // Each unit below is 16 code points but 17 units, and gives one unknown and one known piece.
        val unit = "𝒶 international "
        assertEquals(16, unit.codePointCount(0, unit.length))
        assertEquals(StaticEmbedder.MAX_CODE_POINTS / 16, embedder.pieceIds(unit.repeat(400)).size)
    }
}

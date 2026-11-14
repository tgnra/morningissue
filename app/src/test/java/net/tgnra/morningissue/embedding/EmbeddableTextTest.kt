package net.tgnra.morningissue.embedding

import net.tgnra.morningissue.data.signals.EmbeddingBackfill
import net.tgnra.morningissue.data.signals.StoredEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which text of a post is embedded, and which posts are not embedded at all. */
class EmbeddableTextTest {
    @Test
    fun textInAScriptTheModelCannotReadIsMostlyNonLatin() {
        for (text in listOf(
            "Учёные обнаружили новую экзопланету в зоне обитаемости", // Russian
            "Οι επιστήμονες ανακάλυψαν έναν νέο εξωπλανήτη", // Greek
            "اكتشف العلماء كوكبا جديدا خارج المجموعة الشمسية", // Arabic
            "מדענים גילו כוכב לכת חדש מחוץ למערכת השמש", // Hebrew
            "科学者たちは新しい系外惑星を発見しました", // Japanese, kana and kanji
            "カタカナとひらがなだけのぶんしょう", // kana alone
            "科学家发现了一颗新的系外行星", // Chinese
            "과학자들이 새로운 외계 행성을 발견했다", // Korean
            "वैज्ञानिकों ने एक नया ग्रह खोजा", // Devanagari: any script but Latin counts
        )) {
            assertEquals(text, 1.0, EmbeddableText.nonLatinShare(text), 0.0)
            assertTrue(text, EmbeddableText.isMostlyNonLatin(text))
        }
    }

    @Test
    fun otherLanguagesInLatinScriptAreLetThrough() {
        for (text in listOf(
            "Scientists have found a new exoplanet in the habitable zone",
            "Wissenschaftler haben einen neuen Exoplaneten entdeckt, größer als die Erde",
            "Des scientifiques ont découvert une nouvelle exoplanète à côté d’une étoile naine",
            "Vědci objevili novou exoplanetu v obyvatelné zóně",
            "Các nhà khoa học đã phát hiện một ngoại hành tinh mới",
            "Bilim insanları yaşanabilir bölgede yeni bir ötegezegen keşfetti",
        )) {
            assertEquals(text, 0.0, EmbeddableText.nonLatinShare(text), 0.0)
            assertFalse(text, EmbeddableText.isMostlyNonLatin(text))
        }
    }

    @Test
    fun theShareIsCountedOverLettersAndTheThresholdIsOneHalf() {
        assertEquals(0.5, EmbeddableText.MAX_NON_LATIN_SHARE, 0.0)
        // Four Latin letters and four Cyrillic: exactly half is not "mostly".
        assertEquals(0.5, EmbeddableText.nonLatinShare("abcd абвг"), 0.0)
        assertFalse(EmbeddableText.isMostlyNonLatin("abcd абвг"))
        assertTrue(EmbeddableText.isMostlyNonLatin("abcd абвгд"))
        // An English post quoting some Greek passes; a Russian post naming an English product does not.
        assertFalse(EmbeddableText.isMostlyNonLatin("The word λόγος has meant many things to many philosophers"))
        assertTrue(EmbeddableText.isMostlyNonLatin("Новый iPhone представили в Купертино на этой неделе"))
        // Digits, punctuation, emoji and scriptless letters count for neither side.
        assertEquals(0.0, EmbeddableText.nonLatinShare("2026-11-10: 42 % 🚀🚀🚀 … µ"), 0.0)
        assertFalse(EmbeddableText.isMostlyNonLatin(""))
        assertEquals(1.0, EmbeddableText.nonLatinShare("12345 🚀 я"), 0.0)
        // A character outside the basic plane is one letter, not two.
        assertEquals(0.5, EmbeddableText.nonLatinShare("a 𠀋"), 0.0)
    }

    @Test
    fun theTextIsTheTitleThenTheSummaryThenTheBodyCutToTwoHundredWords() {
        assertEquals("A title\nA standfirst.\nThe body opens here.", EmbeddableText.of("A title", "A standfirst.", "The body opens here."))
        assertEquals("A title\nA standfirst.", EmbeddableText.of("  A   title ", "A standfirst.", null))
        assertEquals("A title", EmbeddableText.of("A title", null, ""))
        assertEquals("", EmbeddableText.of(" ", null, null))

        // A summary cut from the body's own opening is not said twice.
        val body = "The harbour was quiet that morning, and the boats were still. " + "More follows here. ".repeat(10)
        assertEquals("A title\n${body.trim()}", EmbeddableText.of("A title", "The harbour was quiet that morning, and the boats…", body))

        val long = (1..500).joinToString(" ") { "word$it" }
        val cut = EmbeddableText.of("One two", null, long).split(Regex("""\s+"""))
        assertEquals(EmbeddableText.MAX_WORDS, cut.size)
        assertEquals(listOf("One", "two", "word1"), cut.take(3))
        assertEquals("word198", cut.last())
    }

    @Test
    fun aPostGetsAVectorOrAMarkSayingWhyNot() {
        val embedder = EmbeddingFixtures.embedder
        val english = EmbeddingBackfill.stored(embedder, "Heatwave pushes electricity demand to a record high", "Across southern Europe.", null)
        assertEquals(StaticEmbedder.DIMENSIONS, english.size)
        assertNull(StoredEmbedding.markOf(english))
        assertNotNull(StoredEmbedding.vectorOf(english))

        // The model would give this a vector (its vocabulary has Cyrillic letters); it must not be kept.
        val russian = "Учёные обнаружили новую экзопланету"
        assertNotNull(embedder.embed(russian))
        val marked = EmbeddingBackfill.stored(embedder, russian, "В зоне обитаемости.", null)
        assertEquals(StoredEmbedding.NON_LATIN, StoredEmbedding.markOf(marked))
        assertNull(StoredEmbedding.vectorOf(marked))

        val nothing = EmbeddingBackfill.stored(embedder, "🚀🚀🚀", null, null)
        assertEquals(StoredEmbedding.NO_KNOWN_WORDS, StoredEmbedding.markOf(nothing))
        assertNull(StoredEmbedding.vectorOf(nothing))
        assertNull(StoredEmbedding.vectorOf(null))

        // German passes the script test and is embedded, for what its vector is worth.
        assertNull(StoredEmbedding.markOf(EmbeddingBackfill.stored(embedder, "Wissenschaftler entdecken einen neuen Exoplaneten", null, null)))
    }
}

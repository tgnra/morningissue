package net.tgnra.morningissue.embedding

/**
 * What of a post is handed to [StaticEmbedder], and whether it should be handed over at all.
 * Pure, like the embedder.
 *
 * **The text** is the title, then the summary, then the opening of the body, cut to
 * [MAX_WORDS] words: what a reader sees of a story before deciding to open it, and a little
 * more. The summary is usually the body's own opening, in which case it is not said twice.
 *
 * **Script.** The model is English. Its vocabulary knows other scripts only as single
 * characters, so the vector of a Russian, Greek, Arabic, Hebrew, Japanese or Chinese text says
 * "this is written in Cyrillic" and nothing about its subject. In a feed that mixes languages
 * that would rank posts by language under the name of taste. So a text whose letters are
 * mostly not Latin gets no vector ([isMostlyNonLatin]) and its post is ranked on categories
 * and structure alone. Other languages in Latin script are let through: their vectors are
 * weak (see [StaticEmbedder]) but not systematically wrong.
 */
object EmbeddableText {
    /** About what the body's opening adds to a title and a standfirst; the embedder itself reads up to about 500. */
    const val MAX_WORDS = 200

    /** Characters of the body worth reading from storage to be sure of [MAX_WORDS] words. */
    const val BODY_CHARS = 2000

    /**
     * A text is not embedded when more than this share of its letters are in a script other
     * than Latin. Half: an English post quoting a line of Greek passes, a Russian post naming
     * a few English products does not.
     */
    const val MAX_NON_LATIN_SHARE = 0.5

    private val SPACES = Regex("""\s+""")

    /** How far into the summary the body is compared with it to tell that one opens the other. */
    private const val SAME_OPENING_CHARS = 60

    /** The text to embed for a post; blank when the post has no words at all. */
    fun of(title: String, summary: String?, body: String?): String {
        val heading = squash(title)
        val standfirst = squash(summary.orEmpty())
        val opening = squash(body.orEmpty())
        // A summary cut from the body ends in an ellipsis the body does not have.
        val probe = standfirst.trimEnd('…', '.', ' ').take(SAME_OPENING_CHARS)
        val parts = when {
            opening.isEmpty() -> listOf(heading, standfirst)
            probe.isEmpty() || opening.startsWith(probe) -> listOf(heading, opening)
            else -> listOf(heading, standfirst, opening)
        }
        return firstWords(parts.filter { it.isNotEmpty() }.joinToString("\n"), MAX_WORDS)
    }

    /**
     * The share of [text]'s letters that are in a script other than Latin, 0 to 1; 0 for a
     * text without letters. Digits, punctuation, emoji and the few letters Unicode files under
     * no script (such as µ) are not counted either way. Every ideograph and kana counts as one
     * letter, as a Latin letter does.
     */
    fun nonLatinShare(text: String): Double {
        var latin = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> Unit
                else -> other++
            }
        }
        val letters = latin + other
        return if (letters == 0) 0.0 else other.toDouble() / letters
    }

    /** Whether [text] is written mostly in a script the model cannot read; see the class comment. */
    fun isMostlyNonLatin(text: String): Boolean = nonLatinShare(text) > MAX_NON_LATIN_SHARE

    private fun squash(text: String): String = text.trim().replace(SPACES, " ")

    /** [text] up to the end of its [count]th word, words being runs without whitespace. */
    private fun firstWords(text: String, count: Int): String {
        var words = 0
        var inWord = false
        for (index in text.indices) {
            val space = text[index].isWhitespace()
            if (space && inWord && ++words == count) return text.substring(0, index)
            inWord = !space
        }
        return text
    }
}

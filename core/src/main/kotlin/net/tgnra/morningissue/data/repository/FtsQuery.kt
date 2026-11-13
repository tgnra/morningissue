package net.tgnra.morningissue.data.repository

/** Builds FTS4 MATCH expressions from what a user typed. */
object FtsQuery {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Each word becomes a prefix term and all must match, so `kotlin corout` finds
     * "Kotlin coroutines". Punctuation and FTS operators are dropped. Returns null when
     * [input] has no searchable words.
     */
    fun fromUserInput(input: String): String? {
        val terms = input.split(SEPARATORS).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "\"$it*\"" }
    }
}

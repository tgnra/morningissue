package net.tgnra.morningissue.ai

import java.security.MessageDigest
import net.tgnra.morningissue.model.SliderStep

/**
 * What the on-device model is told about the reader, built on the phone from what the app has
 * already learned, and short enough for Gemini Nano's small context:
 *
 * - [topics]: the tags with the strongest learned affinity, above neutral, strongest first;
 * - [sources]: the feeds set to Favourite, then those set to More;
 * - [recent]: the titles of the posts most recently Liked or saved.
 *
 * Nothing here leaves the phone: the model runs on it (AICore).
 */
data class TasteProfile(
    val topics: List<String>,
    val sources: List<String>,
    val recent: List<String>,
) {
    /** Nothing is known about the reader yet; the model is then not asked, since it would rate for nobody in particular. */
    val isEmpty: Boolean get() = topics.isEmpty() && sources.isEmpty() && recent.isEmpty()

    /** The profile as the prompt shows it: a line or a short list per part that has anything. */
    fun text(): String = buildString {
        if (topics.isNotEmpty()) append("Topics they read most: ").append(topics.joinToString(", ")).append('\n')
        if (sources.isNotEmpty()) append("Sources they asked to see more of: ").append(sources.joinToString(", ")).append('\n')
        if (recent.isNotEmpty()) {
            append("Posts they recently liked or saved:\n")
            recent.forEach { append("- ").append(it).append('\n') }
        }
    }.trimEnd()

    /**
     * Identifies this profile: the first 16 hex digits of the SHA-256 of [text]. Stored with
     * each rating, so that a rating given under an older profile can be told apart and re-rated.
     */
    fun hash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_TOPICS = 5
        const val MAX_SOURCES = 8
        const val MAX_RECENT = 8

        /** Titles and names are cut to this many characters, at a word where possible. */
        const val MAX_TITLE_CHARS = 90

        /**
         * Builds the profile.
         *
         * @param tags every tag with its learned affinity
         * @param feeds every subscribed feed's title with its slider
         * @param recentTitles the titles of recently Liked or saved posts, newest first
         * @param neutralAffinity a tag must be above this to count as a topic the reader reads most
         */
        fun build(
            tags: List<Pair<String, Double>>,
            feeds: List<Pair<String, SliderStep>>,
            recentTitles: List<String>,
            neutralAffinity: Double = 0.5,
        ): TasteProfile = TasteProfile(
            topics = tags.filter { it.second > neutralAffinity }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .map { clean(it.first) }.filter { it.isNotEmpty() }.distinct().take(MAX_TOPICS),
            sources = (feeds.filter { it.second == SliderStep.FAVOURITE } + feeds.filter { it.second == SliderStep.MORE })
                .map { clean(it.first) }.filter { it.isNotEmpty() }.distinct().take(MAX_SOURCES),
            recent = recentTitles.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_RECENT),
        )

        /** One line, single spaces, at most [max] characters. */
        fun clean(text: String, max: Int = MAX_TITLE_CHARS): String {
            val line = text.replace(WHITESPACE, " ").trim()
            if (line.length <= max) return line
            val cut = line.take(max)
            val space = cut.lastIndexOf(' ')
            return (if (space > max / 2) cut.take(space) else cut).trimEnd() + "…"
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

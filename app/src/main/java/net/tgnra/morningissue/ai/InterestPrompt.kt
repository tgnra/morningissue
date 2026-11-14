package net.tgnra.morningissue.ai

/** One post as the model sees it: its title, its feed's name and the first line or two of its text. */
data class PostForRating(
    val articleId: Long,
    val title: String,
    val feed: String,
    val summary: String? = null,
)

/**
 * The prompt that asks Gemini Nano to rate a batch of posts for one reader. Kept small: Nano's
 * context is a few thousand tokens, and a batch of [BATCH_SIZE] posts with the profile comes
 * to well under a thousand. Posts are numbered from 1 in the prompt, not by article ID, so the
 * reply is short and the numbers cannot be confused with anything else; [InterestReply] maps
 * them back.
 */
object InterestPrompt {
    /** Posts per call. */
    const val BATCH_SIZE = 10

    /** The snippet of a post is cut to this many characters. */
    const val MAX_SNIPPET_CHARS = 160

    /** Builds the prompt for [posts] (at most [BATCH_SIZE]) and the reader described by [profile]. */
    fun build(profile: TasteProfile, posts: List<PostForRating>): String {
        require(posts.size in 1..BATCH_SIZE) { "between 1 and $BATCH_SIZE posts, not ${posts.size}" }
        return buildString {
            append("You help choose the front page of one reader's personal morning newspaper.\n\n")
            append("About this reader:\n")
            append(profile.text()).append("\n\n")
            append("Rate each post below from 1 to 5: would this reader want it near the front page?\n")
            append("1 = not for them, 3 = maybe, 5 = they would want it on the front page.\n\n")
            append("Posts:\n")
            posts.forEachIndexed { index, post ->
                append(index + 1).append(". ").append(TasteProfile.clean(post.title))
                append(" (").append(TasteProfile.clean(post.feed, max = 40)).append(')')
                snippet(post.summary)?.let { append(": ").append(it) }
                append('\n')
            }
            append('\n')
            append("Reply with JSON only: an object from each post's number to its rating, ")
            append("with every post from 1 to ").append(posts.size).append(", for example {\"1\": 4, \"2\": 2}.")
        }
    }

    /**
     * The first line or two of a post's text: its first two sentences, at most
     * [MAX_SNIPPET_CHARS] characters. Null when there is no text.
     */
    fun snippet(summary: String?): String? {
        val text = summary?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val ends = SENTENCE_END.findAll(text).map { it.range.first + 1 }.take(2).toList()
        val twoSentences = ends.getOrNull(1)?.let { text.take(it) } ?: text
        return TasteProfile.clean(twoSentences, max = MAX_SNIPPET_CHARS)
    }

    private val SENTENCE_END = Regex("[.!?](?=\\s)")
}

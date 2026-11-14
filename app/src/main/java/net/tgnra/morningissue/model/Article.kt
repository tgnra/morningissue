package net.tgnra.morningissue.model

import java.time.Instant

/** A headline-level view of an article, used by search, Saved/Liked and source pages. */
data class ArticleSummary(
    val articleId: Long,
    val feedId: Long,
    val feedTitle: String,
    val feedIconUrl: String?,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    val author: String?,
    val url: String?,
    val publishedAt: Instant,
    val firstSeenAt: Instant,
    val readMinutes: Int,
    val isOpened: Boolean,
    val isRead: Boolean,
    val isLiked: Boolean,
    val isSaved: Boolean,
    /** Matching excerpt for search results; null elsewhere. */
    val snippet: String? = null,
    /** False for a post saved or opened from discovery whose feed the reader does not subscribe to. */
    val feedSubscribed: Boolean = true,
)

/** Everything the reader view needs for one article. */
data class ReaderArticle(
    val articleId: Long,
    val feedId: Long,
    val feedTitle: String,
    val title: String,
    val author: String?,
    val url: String?,
    val imageUrl: String?,
    val publishedAt: Instant,
    val wordCount: Int,
    val readMinutes: Int,
    val summary: String?,
    /**
     * The body. When no content is stored yet this is the feed summary as a single paragraph
     * (or empty), and [contentSource] is null.
     */
    val blocks: List<ContentBlock>,
    val contentSource: ContentSource?,
    val isLiked: Boolean,
    val isSaved: Boolean,
    /** Furthest scroll position reached, 0..1. */
    val readFraction: Float,
    /** False when the post comes from a feed the reader does not subscribe to (opened from discovery). */
    val feedSubscribed: Boolean = true,
)

/** Filters for full-text search. [text] is what the user typed, not FTS syntax. */
data class SearchQuery(
    val text: String,
    val tag: String? = null,
    val feedId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val likedOnly: Boolean = false,
    val savedOnly: Boolean = false,
    val limit: Int = 100,
)

object ReadingTime {
    const val WORDS_PER_MINUTE = 230

    /** Estimated minutes to read [wordCount] words; at least 1. */
    fun minutes(wordCount: Int): Int = ((wordCount + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE).coerceAtLeast(1)
}

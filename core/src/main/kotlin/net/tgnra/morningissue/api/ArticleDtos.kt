@file:UseSerializers(IsoInstantSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// 4.6 Articles and search. Engagement is never written here: only `POST /sync/events` writes it.

/** One article in a list; mirrors `model.ArticleSummary`. */
@Serializable
data class ArticleSummaryDto(
    val articleId: Long,
    val feedId: Long,
    val feedTitle: String,
    val feedIconUrl: String? = null,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val author: String? = null,
    val url: String? = null,
    val publishedAt: Instant,
    val firstSeenAt: Instant,
    val readMinutes: Int,
    val isOpened: Boolean = false,
    val isRead: Boolean = false,
    val isLiked: Boolean = false,
    val isSaved: Boolean = false,
    /** The matching excerpt in search results; null elsewhere. */
    val snippet: String? = null,
    /** False for a post from a feed the user has only discovered. */
    val feedSubscribed: Boolean = true,
)

/** `GET /feeds/{id}/articles` and `GET /articles?shelf=saved|liked`. */
@Serializable
data class ArticlesResponse(
    val articles: List<ArticleSummaryDto>,
)

/** How the user is related to an article's feed. */
enum class ArticleFeedStatus { SUBSCRIBED, DISCOVERED, NONE }

/** What `POST /articles/{id}/fulltext` got. */
enum class FullTextState {
    /** The page was fetched and its text stored (now or before). */
    FETCHED,

    /** The feed's own text is complete; no page was needed. */
    FEED_COMPLETE,

    /** No better text could be had; the stored body is unchanged. */
    UNAVAILABLE,
}

/**
 * `GET /articles/{id}` and `POST /articles/{id}/fulltext`. Visible when the user subscribes to
 * its feed (either status) or has engagement or an edition item for it; 404 otherwise.
 */
@Serializable
data class ArticleResponse(
    /** The bundle's article object, with `feedId` set. */
    val article: BundleArticleDto,
    val feedStatus: ArticleFeedStatus,
    /** Set by `POST /articles/{id}/fulltext` only. */
    val fullText: FullTextState? = null,
)

/** `GET /search`'s `when`: how far back. */
enum class SearchWhen { ANY, WEEK, MONTH, YEAR }

/** One search hit. */
@Serializable
data class SearchResultDto(
    val article: ArticleSummaryDto,
    /** FTS4 `snippet()` around the match. */
    val excerpt: String? = null,
)

/** `GET /search?q=&when=&saved=&liked=&tagId=&feedId=&limit=50&offset=0`: restricted to articles the user can see. */
@Serializable
data class SearchResponse(
    val results: List<SearchResultDto>,
)

/** The parameters of `GET /search`, for the client interface; they travel as query parameters ([ApiQuery]). */
data class SearchQuery(
    val q: String,
    val `when`: SearchWhen = SearchWhen.ANY,
    val saved: Boolean = false,
    val liked: Boolean = false,
    val tagId: Long? = null,
    val feedId: Long? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

package net.tgnra.morningissue.server.service

import java.time.Instant
import net.tgnra.morningissue.api.ArticleResponse
import net.tgnra.morningissue.api.ArticlesResponse

/** Articles and the saved and liked shelves (4.6). Implemented by W-A. Never writes engagement. */
interface ArticleService {
    /** `GET /articles/{id}`; [ApiFailure] `not_found` when the user cannot see it. */
    suspend fun article(userId: Long, articleId: Long): ArticleResponse

    /** `POST /articles/{id}/fulltext`: runs `FullTextExtractor`, then answers as [article] with `fullText` set. */
    suspend fun fetchFullText(userId: Long, articleId: Long): ArticleResponse

    /** `GET /articles?shelf=saved|liked&before=&limit=`; [shelf] is `ApiQuery.SHELF_SAVED` or `SHELF_LIKED`. */
    suspend fun shelf(userId: Long, shelf: String, before: Instant?, limit: Int): ArticlesResponse
}

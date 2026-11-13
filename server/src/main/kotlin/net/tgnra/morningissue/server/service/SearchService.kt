package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchResponse

/** `GET /search` (4.6): `FtsQuery` and FTS4 `snippet`, restricted to articles the user can see. Implemented by W-A. */
interface SearchService {
    suspend fun search(userId: Long, query: SearchQuery): SearchResponse
}

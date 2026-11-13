package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.FullTextState

/** Full text on the server, through core's `FullTextExtractor`. Implemented by W-A. */
interface FullTextService {
    /** Makes sure the best body for [articleId] is stored; one fetch serves concurrent callers. */
    suspend fun ensure(articleId: Long): FullTextState

    /** [ensure] for every story printed in [editionId], briefs included, [concurrency] at a time; returns how many were fetched. */
    suspend fun ensureForEdition(editionId: Long, concurrency: Int = 4): Int
}

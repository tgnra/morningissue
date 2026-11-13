package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.DeskNoticeResponse
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.SavedBundleDto

/** Editions, the saved shelf and the engagement delta (4.3). Implemented by W-B. */
interface EditionService {
    /** `GET /editions?before=&limit=`: released editions only, newest first; [before] is an edition number. */
    suspend fun editions(userId: Long, before: Int?, limit: Int): EditionsResponse

    /**
     * `GET /editions/{id}/bundle` (the route checks the events gate first). [ApiFailure]
     * `not_found` for another user's or an unreleased edition. Encoded with `ApiJson`, its
     * bytes are what the manifest's `version` hashes.
     */
    suspend fun bundle(userId: Long, editionId: Long): EditionBundleDto

    /** `GET /saved` (gated by the route). */
    suspend fun saved(userId: Long): SavedBundleDto

    /** `GET /engagement?since=`. */
    suspend fun engagement(userId: Long, since: Long): EngagementDeltaResponse

    /** `POST /editions/{id}/desk-notice`. */
    suspend fun deskNotice(userId: Long, editionId: Long): DeskNoticeResponse
}

package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.LiveResponse
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.OpenDiscoveredResponse
import net.tgnra.morningissue.api.PassedResponse
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.TopicsResponse
import net.tgnra.morningissue.api.VerdictRequest
import net.tgnra.morningissue.api.VerdictResponse

/** Discovery (4.7): catalogue, profile, deck and live headlines, all on the server. Implemented by W-A. */
interface DiscoveryService {
    /** `GET /discovery/deck`. */
    suspend fun deck(userId: Long): DeckResponse

    /** `GET /discovery/topics`. */
    suspend fun topics(userId: Long): TopicsResponse

    /** `POST /discovery/topics`. */
    suspend fun chooseTopics(userId: Long, request: TopicsRequest): DeckResponse

    /** `GET /discovery/live?url=`. */
    suspend fun live(userId: Long, url: String): LiveResponse

    /** `POST /discovery/verdicts`. */
    suspend fun decide(userId: Long, request: VerdictRequest): VerdictResponse

    /** `DELETE /discovery/verdicts?url=`: Undo, removing a subscription the verdict made. */
    suspend fun undo(userId: Long, url: String)

    /** `GET /discovery/passed`. */
    suspend fun passed(userId: Long): PassedResponse

    /** `POST /discovery/open`. */
    suspend fun open(userId: Long, request: OpenDiscoveredRequest): OpenDiscoveredResponse
}

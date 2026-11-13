package net.tgnra.morningissue.server.service

import java.time.Instant
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.DiscoverFeedResponse
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedHealthResponse
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.RetryResponse

/** A subscription made or found by [FeedService.add]: [created] answers 201, otherwise 200. */
data class AddFeedOutcome(val feed: FeedDto, val created: Boolean)

/** Feeds, subscriptions, OPML and health (4.5). Implemented by W-A. */
interface FeedService {
    /** `GET /feeds`. */
    suspend fun feeds(userId: Long): FeedsResponse

    /** `POST /feeds/discover`; [ApiFailure] `not_a_feed` when the address offers none. */
    suspend fun discover(userId: Long, request: DiscoverFeedRequest): DiscoverFeedResponse

    /** `POST /feeds`: reuses the global feed by URL key; the first fetch is done before returning. */
    suspend fun add(userId: Long, request: AddFeedRequest): AddFeedOutcome

    /** `PATCH /feeds/{id}`. */
    suspend fun patch(userId: Long, feedId: Long, patch: FeedPatch): FeedDto

    /** `DELETE /feeds/{id}`: unsubscribe, with the cleanup in section 2.3. */
    suspend fun unsubscribe(userId: Long, feedId: Long)

    /** `GET /feeds/{id}/articles?before=&limit=`. */
    suspend fun articles(userId: Long, feedId: Long, before: Instant?, limit: Int): ArticlesResponse

    /** `GET /feeds/health`: recomputes the user's flags without network. */
    suspend fun health(userId: Long): FeedHealthResponse

    /** `POST /feeds/{id}/health/dismiss`. */
    suspend fun dismissFlag(userId: Long, feedId: Long)

    /** `POST /feeds/{id}/health/acknowledge`. */
    suspend fun acknowledgeMove(userId: Long, feedId: Long)

    /** `POST /feeds/{id}/health/retry`. */
    suspend fun retry(userId: Long, feedId: Long): RetryResponse

    /** `POST /opml`: starts an OPML job through [JobService]. */
    suspend fun importOpml(userId: Long, opml: ByteArray): JobStartedResponse

    /** `GET /opml`: OPML 2.0. */
    suspend fun exportOpml(userId: Long): String
}

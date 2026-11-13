package net.tgnra.morningissue.api.client

import java.io.File
import java.io.IOException
import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ArticleResponse
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.DeskNoticeResponse
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.DiscoverFeedResponse
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedHealthResponse
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.HealthResponse
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportResponse
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.KoboConfigResponse
import net.tgnra.morningissue.api.KoboHelloResponse
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.api.LiveResponse
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.LoginResponse
import net.tgnra.morningissue.api.ManifestDto
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.OpenDiscoveredResponse
import net.tgnra.morningissue.api.PairKoboResponse
import net.tgnra.morningissue.api.PassedResponse
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.RetryResponse
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchResponse
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.api.TopicsResponse
import net.tgnra.morningissue.api.VerdictResponse

/**
 * A client of the server's `/api/v1` (`docs/server-design.md`, section 4), one function per
 * endpoint. The implementation (OkHttp and kotlinx JSON, pure JVM) is the Android workstream's;
 * the server's end-to-end tests use it too.
 *
 * An implementation is made for one server address and sends the bearer token it holds; [login]
 * gives it the token. Every function throws [ApiException] for an error answer (an
 * [ApiException.EventsPending] for `409 events_pending`, and so on) and
 * [ApiException.Offline] when the server cannot be reached; it throws nothing else but
 * cancellation.
 *
 * The gated functions take `eventsThrough`, sent as `X-Events-Through`: the highest event `seq`
 * the client holds unacknowledged, or 0.
 */
interface MorningIssueApi {
    // 4.1 Auth and account

    /** `GET /health`, unauthenticated: checks a server address. */
    suspend fun health(): HealthResponse

    /** `POST /auth/login`. */
    suspend fun login(request: LoginRequest): LoginResponse

    /** `POST /auth/password`. */
    suspend fun changePassword(request: ChangePasswordRequest)

    /** `POST /auth/logout`: revokes this device's token. */
    suspend fun logout()

    /** `GET /account`. */
    suspend fun account(): AccountResponse

    // 4.2 Events and the manifest

    /** `POST /sync/events`, at most `ApiLimits.MAX_EVENTS_PER_REQUEST` events. */
    suspend fun postEvents(request: EventsRequest): EventsResponse

    /** `GET /sync/manifest?editions=` (gated). */
    suspend fun manifest(eventsThrough: Long, editions: Int = 3): ManifestDto

    // 4.3 Editions, saved shelf, stand, print

    /** `GET /editions?before=&limit=`: released editions, newest first; [before] is an edition number. */
    suspend fun editions(before: Int? = null, limit: Int = 30): EditionsResponse

    /** `GET /editions/{id}/bundle` (gated). */
    suspend fun bundle(editionId: Long, eventsThrough: Long): EditionBundleDto

    /** `GET /saved` (gated). */
    suspend fun saved(eventsThrough: Long): SavedBundleDto

    /** `GET /engagement?since=`. */
    suspend fun engagement(since: Long): EngagementDeltaResponse

    /** `GET /stand`. */
    suspend fun stand(): StandDto

    /** `POST /print`: Print now. */
    suspend fun print(): JobStartedResponse

    /** `POST /editions/extra`. */
    suspend fun printExtra(feedIds: List<Long>): JobStartedResponse

    /** `GET /jobs/{id}`. */
    suspend fun job(jobId: String): JobDto

    /** `POST /editions/{id}/desk-notice`. */
    suspend fun deskNotice(editionId: Long): DeskNoticeResponse

    // 4.4 Settings

    /** `GET /settings`. */
    suspend fun settings(): ServerSettings

    /** `PATCH /settings`. */
    suspend fun patchSettings(patch: SettingsPatch): ServerSettings

    /** `PUT /settings/release`. */
    suspend fun setReleaseTimes(request: ReleaseTimesRequest): ServerSettings

    // 4.5 Feeds, OPML, health

    /** `GET /feeds`. */
    suspend fun feeds(): FeedsResponse

    /** `POST /feeds/discover`; a site that offers no feed is an [ApiException] with [ApiError.NOT_A_FEED]. */
    suspend fun discoverFeeds(address: String): DiscoverFeedResponse

    /** `POST /feeds`. */
    suspend fun addFeed(request: AddFeedRequest): FeedDto

    /** `PATCH /feeds/{id}`. */
    suspend fun patchFeed(feedId: Long, patch: FeedPatch): FeedDto

    /** `DELETE /feeds/{id}`: unsubscribe. */
    suspend fun deleteFeed(feedId: Long)

    /** `GET /feeds/{id}/articles?before=&limit=`; [before] is a `publishedAt` instant in ISO form. */
    suspend fun feedArticles(feedId: Long, before: String? = null, limit: Int = 50): ArticlesResponse

    /** `GET /feeds/health`. */
    suspend fun feedHealth(): FeedHealthResponse

    /** `POST /feeds/{id}/health/dismiss`. */
    suspend fun dismissHealthFlag(feedId: Long)

    /** `POST /feeds/{id}/health/acknowledge`. */
    suspend fun acknowledgeMove(feedId: Long)

    /** `POST /feeds/{id}/health/retry`. */
    suspend fun retryFeed(feedId: Long): RetryResponse

    /** `POST /opml` with the OPML document. */
    suspend fun importOpml(opml: ByteArray): JobStartedResponse

    /** `GET /opml`: the user's subscriptions as OPML 2.0. */
    suspend fun exportOpml(): String

    // 4.6 Articles and search

    /** `GET /articles/{id}`. */
    suspend fun article(articleId: Long): ArticleResponse

    /** `POST /articles/{id}/fulltext`. */
    suspend fun fetchFullText(articleId: Long): ArticleResponse

    /** `GET /articles?shelf=&before=&limit=`; [shelf] is `ApiQuery.SHELF_SAVED` or `SHELF_LIKED`, [before] an ISO instant. */
    suspend fun shelf(shelf: String, before: String? = null, limit: Int = 100): ArticlesResponse

    /** `GET /search`. */
    suspend fun search(query: SearchQuery): SearchResponse

    // 4.7 Discovery

    /** `GET /discovery/deck`. */
    suspend fun deck(): DeckResponse

    /** `GET /discovery/topics`. */
    suspend fun topics(): TopicsResponse

    /** `POST /discovery/topics`. */
    suspend fun chooseTopics(clusterIds: List<Int>): DeckResponse

    /** `GET /discovery/live?url=`. */
    suspend fun live(url: String): LiveResponse

    /** `POST /discovery/verdicts`. */
    suspend fun decide(url: String, verdict: CardVerdict): VerdictResponse

    /** `DELETE /discovery/verdicts?url=`: Undo. */
    suspend fun undoVerdict(url: String)

    /** `GET /discovery/passed`. */
    suspend fun passed(): PassedResponse

    /** `POST /discovery/open`. */
    suspend fun openDiscovered(request: OpenDiscoveredRequest): OpenDiscoveredResponse

    // 4.8 Devices and Kobo pairing

    /** `GET /devices`. */
    suspend fun devices(): DevicesResponse

    /** `POST /devices/kobo` with the raw QR text. */
    suspend fun pairKobo(pairingUri: String): PairKoboResponse

    /** `DELETE /devices/{id}`. */
    suspend fun deleteDevice(deviceId: String)

    /** `GET /kobo/hello`, with a Kobo token. */
    suspend fun koboHello(): KoboHelloResponse

    /** `POST /kobo/images` (gated): the zip's bytes. */
    suspend fun koboImages(request: KoboImagesRequest, eventsThrough: Long): ByteArray

    /** `GET /kobo/config.json`, unauthenticated. */
    suspend fun koboConfig(): KoboConfigResponse

    // 4.9 Legacy import

    /** `POST /import?mode=` with [archive] streamed as the body and [idempotencyKey] as `Idempotency-Key`. */
    suspend fun import(archive: File, mode: ImportMode, idempotencyKey: String): ImportResponse
}

/** An error answer from the server, or no answer. [code] is one of the [ApiError] codes. */
sealed class ApiException(val status: Int, val code: String, message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** `409 events_pending`: upload the events above [acked], then retry. */
    class EventsPending(val acked: Long, message: String = "Events pending") : ApiException(409, ApiError.EVENTS_PENDING, message)

    /** 401: the token is missing, revoked, or (for a Kobo) not paired yet ([ApiError.UNKNOWN]). */
    class Unauthenticated(code: String = ApiError.UNAUTHENTICATED, message: String = "Not logged in") : ApiException(401, code, message)

    /** `403 password_change_required`: only changing the password, logging out and `/account` answer. */
    class PasswordChangeRequired(message: String = "The password must be changed") :
        ApiException(403, ApiError.PASSWORD_CHANGE_REQUIRED, message)

    /** The server could not be reached; [status] is 0. */
    class Offline(cause: Throwable? = null) : ApiException(0, "offline", "The server could not be reached", cause)

    /** Any other error answer. */
    class Http(status: Int, code: String, message: String) : ApiException(status, code, message)
}

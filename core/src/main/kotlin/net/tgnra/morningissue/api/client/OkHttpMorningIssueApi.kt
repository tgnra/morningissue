package net.tgnra.morningissue.api.client

import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiContentTypes
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiErrorBody
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.ArticleResponse
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.DeskNoticeResponse
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.DiscoverFeedResponse
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedHealthResponse
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.HealthResponse
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
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
import net.tgnra.morningissue.api.PairKoboRequest
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
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.TopicsResponse
import net.tgnra.morningissue.api.VerdictRequest
import net.tgnra.morningissue.api.VerdictResponse
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The [MorningIssueApi] over OkHttp and kotlinx JSON ([ApiJson]).
 *
 * @param serverUrl the server's address as the reader typed it once checked: scheme, host,
 *   optional port and optional path prefix (`https://news.example.org`, `http://10.0.2.2:8417/mi`).
 *   [ApiPaths.BASE] is appended.
 * @param client shared OkHttp client; its timeouts apply.
 * @param token the bearer token, if one is held already. [login] replaces it.
 */
class OkHttpMorningIssueApi(
    serverUrl: String,
    private val client: OkHttpClient,
    token: String? = null,
) : MorningIssueApi {
    /** The API's root, `<serverUrl>/api/v1`. */
    val baseUrl: HttpUrl = serverUrl.trimEnd('/').plus(ApiPaths.BASE).toHttpUrl()

    /** Sent as `Authorization: Bearer` on every request but the unauthenticated ones. */
    @Volatile
    var token: String? = token

    private val json = ApiJson.json

    // 4.1 Auth and account

    override suspend fun health(): HealthResponse = get(ApiPaths.HEALTH, auth = false)

    override suspend fun login(request: LoginRequest): LoginResponse =
        post<LoginRequest, LoginResponse>(ApiPaths.LOGIN, request, auth = false).also { token = it.token }

    override suspend fun changePassword(request: ChangePasswordRequest) {
        send(newRequest(url(ApiPaths.PASSWORD)).post(jsonBody(request)))
    }

    override suspend fun logout() {
        send(newRequest(url(ApiPaths.LOGOUT)).post(EMPTY))
        token = null
    }

    override suspend fun account(): AccountResponse = get(ApiPaths.ACCOUNT)

    // 4.2 Events and the manifest

    override suspend fun postEvents(request: EventsRequest): EventsResponse = post(ApiPaths.SYNC_EVENTS, request)

    override suspend fun manifest(eventsThrough: Long, editions: Int): ManifestDto =
        call(newRequest(url(ApiPaths.SYNC_MANIFEST) { addQueryParameter(ApiQuery.EDITIONS, editions.toString()) }, eventsThrough).get())

    // 4.3 Editions, saved shelf, stand, print

    override suspend fun editions(before: Int?, limit: Int): EditionsResponse = get(ApiPaths.EDITIONS) {
        before?.let { addQueryParameter(ApiQuery.BEFORE, it.toString()) }
        addQueryParameter(ApiQuery.LIMIT, limit.toString())
    }

    override suspend fun bundle(editionId: Long, eventsThrough: Long): EditionBundleDto =
        call(newRequest(url(ApiPaths.editionBundle(editionId)), eventsThrough).get())

    override suspend fun saved(eventsThrough: Long): SavedBundleDto = call(newRequest(url(ApiPaths.SAVED), eventsThrough).get())

    override suspend fun engagement(since: Long): EngagementDeltaResponse =
        get(ApiPaths.ENGAGEMENT) { addQueryParameter(ApiQuery.SINCE, since.toString()) }

    override suspend fun stand(): StandDto = get(ApiPaths.STAND)

    override suspend fun print(): JobStartedResponse = call(newRequest(url(ApiPaths.PRINT)).post(EMPTY))

    override suspend fun printExtra(feedIds: List<Long>): JobStartedResponse = post(ApiPaths.EDITIONS_EXTRA, ExtraRequest(feedIds))

    override suspend fun job(jobId: String): JobDto = get(ApiPaths.job(jobId))

    override suspend fun deskNotice(editionId: Long): DeskNoticeResponse =
        call(newRequest(url(ApiPaths.editionDeskNotice(editionId))).post(EMPTY))

    // 4.4 Settings

    override suspend fun settings(): ServerSettings = get(ApiPaths.SETTINGS)

    override suspend fun patchSettings(patch: SettingsPatch): ServerSettings =
        call(newRequest(url(ApiPaths.SETTINGS)).patch(jsonBody(patch)))

    override suspend fun setReleaseTimes(request: ReleaseTimesRequest): ServerSettings =
        call(newRequest(url(ApiPaths.SETTINGS_RELEASE)).put(jsonBody(request)))

    // 4.5 Feeds, OPML, health

    override suspend fun feeds(): FeedsResponse = get(ApiPaths.FEEDS)

    override suspend fun discoverFeeds(address: String): DiscoverFeedResponse = post(ApiPaths.FEEDS_DISCOVER, DiscoverFeedRequest(address))

    override suspend fun addFeed(request: AddFeedRequest): FeedDto = post(ApiPaths.FEEDS, request)

    override suspend fun patchFeed(feedId: Long, patch: FeedPatch): FeedDto =
        call(newRequest(url(ApiPaths.feed(feedId))).patch(jsonBody(patch)))

    override suspend fun deleteFeed(feedId: Long) {
        send(newRequest(url(ApiPaths.feed(feedId))).delete())
    }

    override suspend fun feedArticles(feedId: Long, before: String?, limit: Int): ArticlesResponse = get(ApiPaths.feedArticles(feedId)) {
        before?.let { addQueryParameter(ApiQuery.BEFORE, it) }
        addQueryParameter(ApiQuery.LIMIT, limit.toString())
    }

    override suspend fun feedHealth(): FeedHealthResponse = get(ApiPaths.FEEDS_HEALTH)

    override suspend fun dismissHealthFlag(feedId: Long) {
        send(newRequest(url(ApiPaths.feedHealthDismiss(feedId))).post(EMPTY))
    }

    override suspend fun acknowledgeMove(feedId: Long) {
        send(newRequest(url(ApiPaths.feedHealthAcknowledge(feedId))).post(EMPTY))
    }

    override suspend fun retryFeed(feedId: Long): RetryResponse = call(newRequest(url(ApiPaths.feedHealthRetry(feedId))).post(EMPTY))

    override suspend fun importOpml(opml: ByteArray): JobStartedResponse =
        call(newRequest(url(ApiPaths.OPML)).post(opml.toRequestBody(ApiContentTypes.OPML.toMediaType())))

    override suspend fun exportOpml(): String = send(newRequest(url(ApiPaths.OPML)).get()) { it.body.string() }

    // 4.6 Articles and search

    override suspend fun article(articleId: Long): ArticleResponse = get(ApiPaths.article(articleId))

    override suspend fun fetchFullText(articleId: Long): ArticleResponse =
        call(newRequest(url(ApiPaths.articleFullText(articleId))).post(EMPTY))

    override suspend fun shelf(shelf: String, before: String?, limit: Int): ArticlesResponse = get(ApiPaths.ARTICLES) {
        addQueryParameter(ApiQuery.SHELF, shelf)
        before?.let { addQueryParameter(ApiQuery.BEFORE, it) }
        addQueryParameter(ApiQuery.LIMIT, limit.toString())
    }

    override suspend fun search(query: SearchQuery): SearchResponse = get(ApiPaths.SEARCH) {
        addQueryParameter(ApiQuery.Q, query.q)
        addQueryParameter(ApiQuery.WHEN, query.`when`.name)
        if (query.saved) addQueryParameter(ApiQuery.SAVED, "true")
        if (query.liked) addQueryParameter(ApiQuery.LIKED, "true")
        query.tagId?.let { addQueryParameter(ApiQuery.TAG_ID, it.toString()) }
        query.feedId?.let { addQueryParameter(ApiQuery.FEED_ID, it.toString()) }
        addQueryParameter(ApiQuery.LIMIT, query.limit.toString())
        addQueryParameter(ApiQuery.OFFSET, query.offset.toString())
    }

    // 4.7 Discovery

    override suspend fun deck(): DeckResponse = get(ApiPaths.DISCOVERY_DECK)

    override suspend fun topics(): TopicsResponse = get(ApiPaths.DISCOVERY_TOPICS)

    override suspend fun chooseTopics(clusterIds: List<Int>): DeckResponse = post(ApiPaths.DISCOVERY_TOPICS, TopicsRequest(clusterIds))

    override suspend fun live(url: String): LiveResponse = get(ApiPaths.DISCOVERY_LIVE) { addQueryParameter(ApiQuery.URL, url) }

    override suspend fun decide(url: String, verdict: CardVerdict): VerdictResponse =
        post(ApiPaths.DISCOVERY_VERDICTS, VerdictRequest(url, verdict))

    override suspend fun undoVerdict(url: String) {
        send(newRequest(url(ApiPaths.DISCOVERY_VERDICTS) { addQueryParameter(ApiQuery.URL, url) }).delete())
    }

    override suspend fun passed(): PassedResponse = get(ApiPaths.DISCOVERY_PASSED)

    override suspend fun openDiscovered(request: OpenDiscoveredRequest): OpenDiscoveredResponse = post(ApiPaths.DISCOVERY_OPEN, request)

    // 4.8 Devices and Kobo pairing

    override suspend fun devices(): DevicesResponse = get(ApiPaths.DEVICES)

    override suspend fun pairKobo(pairingUri: String): PairKoboResponse = post(ApiPaths.DEVICES_KOBO, PairKoboRequest(pairingUri))

    override suspend fun deleteDevice(deviceId: String) {
        send(newRequest(url(ApiPaths.device(deviceId))).delete())
    }

    override suspend fun koboHello(): KoboHelloResponse = get(ApiPaths.KOBO_HELLO)

    override suspend fun koboImages(request: KoboImagesRequest, eventsThrough: Long): ByteArray =
        send(newRequest(url(ApiPaths.KOBO_IMAGES), eventsThrough).post(jsonBody(request))) { it.body.bytes() }

    override suspend fun koboConfig(): KoboConfigResponse = get(ApiPaths.KOBO_CONFIG, auth = false)

    // 4.9 Legacy import

    override suspend fun import(archive: File, mode: ImportMode, idempotencyKey: String): ImportResponse {
        val url = url(ApiPaths.IMPORT) { addQueryParameter(ApiQuery.MODE, mode.query) }
        val request = newRequest(url)
            .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .post(archive.asRequestBody(ApiContentTypes.ZIP.toMediaType()))
        return call(request)
    }

    // --- Plumbing --------------------------------------------------------------------------

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path.trimStart('/')).apply(query).build()

    private fun newRequest(url: HttpUrl, eventsThrough: Long? = null, auth: Boolean = true): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (auth) token?.let { builder.header("Authorization", "Bearer $it") }
        if (eventsThrough != null) builder.header(EVENTS_THROUGH_HEADER, eventsThrough.toString())
        return builder
    }

    private inline fun <reified T> jsonBody(value: T): RequestBody = json.encodeToString(serializer<T>(), value).toRequestBody(JSON)

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true, noinline query: HttpUrl.Builder.() -> Unit = {}): T =
        call(newRequest(url(path, query), auth = auth).get())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, auth: Boolean = true): T =
        call(newRequest(url(path), auth = auth).post(jsonBody(body)))

    private suspend inline fun <reified T> call(request: Request.Builder): T = decode(serializer<T>(), request)

    private suspend fun <T> decode(deserializer: KSerializer<T>, request: Request.Builder): T = send(request) { response ->
        val text = response.body.string()
        try {
            json.decodeFromString(deserializer, text)
        } catch (e: SerializationException) {
            throw ApiException.Http(response.code, BAD_RESPONSE, "Unreadable answer: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ApiException.Http(response.code, BAD_RESPONSE, "Unreadable answer: ${e.message}")
        }
    }

    /** Sends [request]; on a 2xx answer runs [read] on it (off the caller's thread), otherwise throws the matching [ApiException]. */
    /** Sends [request] and discards a 2xx answer's body; otherwise throws the matching [ApiException]. */
    private suspend fun send(request: Request.Builder) {
        send(request) { }
    }

    private suspend fun <T> send(request: Request.Builder, read: (Response) -> T): T {
        val call = client.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(ApiException.Offline(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { if (it.isSuccessful) read(it) else throw errorOf(it) } }
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { error ->
                                continuation.resumeWithException(
                                    when (error) {
                                        is ApiException -> error
                                        // The body broke off half way: as good as no answer.
                                        is IOException -> ApiException.Offline(error)
                                        else -> ApiException.Http(response.code, BAD_RESPONSE, error.message ?: error.toString())
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private fun errorOf(response: Response): ApiException {
        val status = response.code
        val text = runCatching { response.body.string() }.getOrDefault("")
        val body = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
        val code = body?.error ?: "http_$status"
        val message = body?.message ?: (text.take(200).ifBlank { response.message.ifBlank { "HTTP $status" } })
        return when {
            code == ApiError.EVENTS_PENDING -> ApiException.EventsPending(body?.acked ?: 0, message)
            status == 401 -> ApiException.Unauthenticated(code, message)
            code == ApiError.PASSWORD_CHANGE_REQUIRED -> ApiException.PasswordChangeRequired(message)
            else -> ApiException.Http(status, code, message)
        }
    }

    companion object {
        /** The code of an [ApiException.Http] for a 2xx answer that could not be read. */
        const val BAD_RESPONSE = "bad_response"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

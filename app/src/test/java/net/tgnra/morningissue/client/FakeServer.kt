package net.tgnra.morningissue.client

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiErrorBody
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.BundleArticleDto
import net.tgnra.morningissue.api.BundleEngagementDto
import net.tgnra.morningissue.api.BundleItemDto
import net.tgnra.morningissue.api.BundlePageDto
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionSummaryDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.HealthResponse
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.ImportResponse
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.LoginResponse
import net.tgnra.morningissue.api.ManifestDto
import net.tgnra.morningissue.api.ManifestEditionDto
import net.tgnra.morningissue.api.ManifestSavedDto
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.api.PairKoboResponse
import net.tgnra.morningissue.api.ReleaseDto
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.api.UserDto
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SliderStep

/**
 * A Morning Issue server in memory, behind a [MockWebServer]: enough of the API for the client's
 * tests, with its state in plain fields a test sets and reads. Every request is kept in
 * [requests]; [failNext] answers the next request to a path with an error instead.
 */
class FakeServer : Dispatcher() {
    val web = MockWebServer()
    val url: String get() = web.url("/").toString()

    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    // --- Account -------------------------------------------------------------------------------

    var username = "tania"
    var password = "generated-1"
    var mustChangePassword = true
    var hasData = false
    var dbId = "db-1"
    var token = "token-1"

    // --- Sync ------------------------------------------------------------------------------------

    /** The device's `acked_seq`. */
    var acked = 0L
    val events: MutableList<SyncEvent> = CopyOnWriteArrayList()

    /**
     * How many more `POST /sync/events` the server answers without taking anything (as if
     * another request from the device were still being merged): it keeps the gate shut.
     */
    var ignoreEvents = 0
    var feeds: List<FeedDto> = listOf(feed(1, "A Blog"))
    var editions: MutableMap<Long, EditionBundleDto> = linkedMapOf()
    var versions: MutableMap<Long, String> = linkedMapOf()
    var saved = SavedBundleDto("saved-0", emptyList(), emptyMap())
    var delta = EngagementDeltaResponse(cursor = 0, engagement = emptyList(), editions = emptyList())

    /** Run when `GET /engagement` arrives, before it is answered: something the reader does mid-sync. */
    @Volatile
    var onEngagement: () -> Unit = {}
    var settings = ServerSettings(
        resetTime = LocalTime.of(6, 0),
        editionLength = EditionLength.STANDARD,
        eveningEnabled = false,
        eveningTime = LocalTime.of(18, 0),
        timeZone = "UTC",
        followDeviceTimeZone = false,
        onboardingDone = true,
    )
    var stand = StandDto(latestEditionId = null, due = null, nextRelease = ReleaseDto(EditionKind.MORNING, T1), hasSources = true)

    // --- Jobs, import, devices --------------------------------------------------------------------

    /** What `GET /jobs/{id}` answers, in turn; the last is repeated. */
    val jobs: MutableMap<String, MutableList<JobDto>> = linkedMapOf()
    var nextJobId = "job-1"

    /** Import jobs started, by `Idempotency-Key`: the same key again answers 200 with the report, starting nothing. */
    val imports: MutableMap<String, String> = linkedMapOf()
    var importReport = ImportReportDto(feeds = 2, posts = 12, editions = 3)
    var devices: MutableList<DeviceDto> = mutableListOf(DeviceDto("phone-1", DeviceKind.PHONE, "Pixel", createdAt = T0, current = true))

    private val failures = mutableMapOf<String, Pair<Int, String>>()

    /** The next request to [path] (without `/api/v1`) is answered [status] with [code]. */
    fun failNext(path: String, status: Int, code: String) {
        failures[path] = status to code
    }

    fun start(): FakeServer {
        web.dispatcher = this
        web.start()
        return this
    }

    fun stop() = web.close()

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == "/api/v1$path" }

    /** Publishes an edition with [articles] (IDs from 100), at [version]. */
    fun publish(id: Long, articles: List<Long>, version: String = "v1-$id", liked: Set<Long> = emptySet()) {
        editions[id] = bundle(id, articles, liked)
        versions[id] = version
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath.removePrefix("/api/v1")
        failures.remove(path)?.let { (status, code) -> return error(status, code) }
        val through = request.headers[EVENTS_THROUGH_HEADER]?.toLongOrNull()
        return when {
            path == "/health" -> ok(HealthResponse(version = "2.0.0"))
            path == "/auth/login" -> login(request)
            path == "/auth/password" -> changePassword(request)
            path == "/auth/logout" -> MockResponse.Builder().code(204).build()
            !authorized(request) -> error(401, ApiError.UNAUTHENTICATED)
            mustChangePassword && path != "/account" -> error(403, ApiError.PASSWORD_CHANGE_REQUIRED)
            path == "/account" -> ok(AccountResponse(username, dbId, mustChangePassword, hasData, settingsCustomized = hasData))
            path == "/sync/events" -> postEvents(request)
            path == "/sync/manifest" -> gated(through) { manifest() }
            path == "/feeds" -> ok(FeedsResponse(feeds, emptyList()))
            path == "/editions" -> ok(EditionsResponse(editions.values.map { it.summary() }))
            path.startsWith("/editions/") && path.endsWith("/bundle") -> gated(through) {
                val id = path.removePrefix("/editions/").removeSuffix("/bundle").toLong()
                editions[id]?.let(::ok) ?: error(404, ApiError.NOT_FOUND)
            }
            path == "/saved" -> gated(through) { ok(saved) }
            path == "/engagement" -> {
                onEngagement()
                ok(delta)
            }
            path == "/settings" -> ok(settings)
            path == "/stand" -> ok(stand)
            path == "/print" || path == "/editions/extra" -> ok(JobStartedResponse(nextJobId))
            path.startsWith("/jobs/") -> {
                val queue = jobs[path.removePrefix("/jobs/")] ?: return error(404, ApiError.NOT_FOUND)
                ok(if (queue.size > 1) queue.removeAt(0) else queue.first())
            }
            path == "/import" -> import(request)
            path == "/devices" && request.method == "GET" -> ok(DevicesResponse(devices))
            path == "/devices/kobo" -> pairKobo(request)
            path.startsWith("/devices/") && request.method == "DELETE" -> {
                devices.removeAll { it.id == path.removePrefix("/devices/") }
                MockResponse.Builder().code(204).build()
            }
            else -> error(404, ApiError.NOT_FOUND)
        }
    }

    private fun authorized(request: RecordedRequest) = request.headers["Authorization"] == "Bearer $token"

    private fun login(request: RecordedRequest): MockResponse {
        val body = request.body!!.utf8()
        if (!body.contains("\"username\":\"$username\"") || !body.contains("\"password\":\"$password\"")) {
            return error(401, ApiError.INVALID_CREDENTIALS)
        }
        return ok(LoginResponse(token, "phone-1", mustChangePassword, UserDto(username, dbId)))
    }

    private fun changePassword(request: RecordedRequest): MockResponse {
        if (!authorized(request)) return error(401, ApiError.UNAUTHENTICATED)
        val body = request.body!!.utf8()
        if (!body.contains("\"current\":\"$password\"")) return error(401, ApiError.INVALID_CREDENTIALS)
        password = Regex("\"new\":\"([^\"]*)\"").find(body)!!.groupValues[1]
        mustChangePassword = false
        return MockResponse.Builder().code(204).build()
    }

    private fun postEvents(request: RecordedRequest): MockResponse {
        val batch = ApiJson.json.decodeFromString<EventsRequest>(request.body!!.utf8()).events
        if (ignoreEvents > 0) {
            ignoreEvents--
            return ok(EventsResponse(acked))
        }
        for (event in batch) if (event.seq > acked) events += event
        acked = maxOf(acked, batch.maxOfOrNull { it.seq } ?: 0)
        return ok(EventsResponse(acked))
    }

    /** The events gate: refused while the device holds events above [acked]. */
    private fun gated(through: Long?, answer: () -> MockResponse): MockResponse = when {
        through == null -> error(400, ApiError.EVENTS_THROUGH_REQUIRED)
        through > acked -> json(409, ApiJson.json.encodeToString(ApiErrorBody(ApiError.EVENTS_PENDING, "Events pending", acked)))
        else -> answer()
    }

    private fun manifest(): MockResponse = ok(
        ManifestDto(
            dbId = dbId,
            serverTime = T0,
            editions = editions.values.sortedByDescending { it.releaseAt }.map {
                ManifestEditionDto(it.id, it.date, it.kind, it.number, it.releaseAt, versions.getValue(it.id))
            },
            saved = ManifestSavedDto(saved.order.size, saved.version),
            acked = mapOf("phone-1" to acked),
        ),
    )

    private fun import(request: RecordedRequest): MockResponse {
        val key = request.headers[IDEMPOTENCY_KEY_HEADER] ?: return error(400, ApiError.BAD_REQUEST)
        if (key in imports) return ok(ImportResponse(report = importReport))
        val jobId = "import-${imports.size + 1}"
        imports[key] = jobId
        jobs[jobId] = mutableListOf(
            job(jobId, JobKind.IMPORT, net.tgnra.morningissue.api.JobState.RUNNING, done = 5, total = 12),
            job(jobId, JobKind.IMPORT, net.tgnra.morningissue.api.JobState.DONE, result = net.tgnra.morningissue.api.JobResult.DONE, report = importReport),
        )
        return json(202, ApiJson.json.encodeToString(ImportResponse(jobId = jobId)))
    }

    private fun pairKobo(request: RecordedRequest): MockResponse {
        val pairing = ApiJson.json.decodeFromString<PairKoboRequest>(request.body!!.utf8()).pairingUri
        if (!pairing.contains("v=2")) return error(400, ApiError.BAD_REQUEST)
        val device = DeviceDto("kobo-1", DeviceKind.KOBO, "Libra 2", width = 1264, createdAt = T0)
        devices.add(device)
        return json(201, ApiJson.json.encodeToString(PairKoboResponse(device)))
    }

    private inline fun <reified T> ok(value: T) = json(200, ApiJson.json.encodeToString(value))

    private fun error(status: Int, code: String) = json(status, ApiJson.json.encodeToString(ApiErrorBody(code, code)))

    private fun json(status: Int, body: String) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        val T0: Instant = Instant.parse("2026-11-11T06:00:00Z")
        val T1: Instant = Instant.parse("2026-11-12T06:00:00Z")

        fun feed(id: Long, title: String, status: FeedStatus = FeedStatus.SUBSCRIBED) = FeedDto(
            id = id,
            url = "https://feed$id.example/rss",
            title = title,
            tier = CadenceTier.DAILY,
            affinity = 0.5,
            sliderStep = SliderStep.AUTO,
            status = status,
            addedAt = T0.minusSeconds(86_400),
        )

        fun article(id: Long, feedId: Long = 1, liked: Boolean = false) = BundleArticleDto(
            feedTitle = "A Blog",
            title = "Story $id",
            author = null,
            url = "https://feed$feedId.example/$id",
            publishedAt = T0.minusSeconds(3_600),
            summary = "Summary $id",
            imageUrl = null,
            wordCount = 300,
            blocks = ApiJson.json.parseToJsonElement(ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText("Body of story $id with quince"))))),
            engagement = BundleEngagementDto(likedAt = if (liked) T0 else null),
            feedId = feedId,
            guid = "guid-$id",
            firstSeenAt = T0.minusSeconds(3_000),
            feedWordCount = 300,
            contentSource = ContentSource.FEED,
        )

        fun bundle(id: Long, articles: List<Long>, liked: Set<Long> = emptySet()) = EditionBundleDto(
            id = id,
            date = LocalDate.of(2026, 11, 11).plusDays(id - 1),
            kind = EditionKind.MORNING,
            number = id.toInt(),
            length = EditionLength.STANDARD,
            releaseAt = T0.plusSeconds(86_400 * (id - 1)),
            finishedAt = null,
            pages = listOf(BundlePageDto(1, articles.mapIndexed { slot, articleId -> BundleItemDto(articleId, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, null, null) })),
            articles = articles.associate { it.toString() to article(it, liked = it in liked) },
            cutoffAt = T0.plusSeconds(86_400 * (id - 1)),
            builtAt = T0.plusSeconds(86_400 * (id - 1)),
        )

        fun job(
            id: String,
            kind: JobKind,
            state: net.tgnra.morningissue.api.JobState,
            stage: net.tgnra.morningissue.api.JobStage? = null,
            done: Int? = null,
            total: Int? = null,
            result: net.tgnra.morningissue.api.JobResult? = null,
            editionId: Long? = null,
            report: ImportReportDto? = null,
        ) = JobDto(
            id = id,
            kind = kind,
            state = state,
            stage = stage,
            done = done,
            total = total,
            outcome = result?.let {
                net.tgnra.morningissue.api.JobOutcomeDto(it, editionId, report = report?.let { r -> ApiJson.json.encodeToJsonElement(ImportReportDto.serializer(), r) })
            },
        )

        private fun EditionBundleDto.summary() = EditionSummaryDto(
            id = id,
            date = date,
            kind = kind,
            number = number,
            length = length,
            releaseAt = releaseAt,
            cutoffAt = cutoffAt ?: releaseAt,
            builtAt = builtAt ?: releaseAt,
            storyCount = articles.size,
        )
    }
}

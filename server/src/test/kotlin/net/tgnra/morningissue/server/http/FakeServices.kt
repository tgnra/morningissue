package net.tgnra.morningissue.server.http

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ArticleFeedStatus
import net.tgnra.morningissue.api.ArticleResponse
import net.tgnra.morningissue.api.ArticleSummaryDto
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.BundleArticleDto
import net.tgnra.morningissue.api.BundleEngagementDto
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.DeskNoticeResponse
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.DiscoverFeedResponse
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.FeedCandidateDto
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedHealthResponse
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.ImportResponse
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.api.KoboConfigResponse
import net.tgnra.morningissue.api.KoboHelloResponse
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.api.LiveResponse
import net.tgnra.morningissue.api.LiveState
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.LoginResponse
import net.tgnra.morningissue.api.ManifestDto
import net.tgnra.morningissue.api.ManifestSavedDto
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.OpenDiscoveredResponse
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.api.PairKoboResponse
import net.tgnra.morningissue.api.PassedResponse
import net.tgnra.morningissue.api.ReleaseDto
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.RetryResponse
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchResponse
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.api.TopicDto
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.TopicsResponse
import net.tgnra.morningissue.api.UserDto
import net.tgnra.morningissue.api.VerdictRequest
import net.tgnra.morningissue.api.VerdictResponse
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.service.AddFeedOutcome
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.ArticleService
import net.tgnra.morningissue.server.service.AuthService
import net.tgnra.morningissue.server.service.DeviceService
import net.tgnra.morningissue.server.service.DiscoveryService
import net.tgnra.morningissue.server.service.EditionService
import net.tgnra.morningissue.server.service.FeedService
import net.tgnra.morningissue.server.service.ImportService
import net.tgnra.morningissue.server.service.JobProgress
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.server.service.JobStart
import net.tgnra.morningissue.server.service.KoboImageService
import net.tgnra.morningissue.server.service.Principal
import net.tgnra.morningissue.server.service.SearchService
import net.tgnra.morningissue.server.service.SettingsService
import net.tgnra.morningissue.server.service.StandService
import net.tgnra.morningissue.server.service.SyncService

/** Bearer tokens the fake [AuthService] knows. */
object TestTokens {
    const val PHONE = "phone-token"
    const val KOBO = "kobo-token"
    const val LOCKED = "locked-token"
    const val DISABLED = "disabled-token"
}

val T0: Instant = Instant.parse("2026-11-11T06:30:00Z")

/**
 * Every service the routes call, faked: each records its calls in [calls] (as text) and answers
 * with fixed data. User 1 has a phone and a Kobo; user 2 must change their password. The events
 * gate passes while `X-Events-Through` is at most [acked].
 */
class FakeServices {
    val calls = ArrayList<String>()
    var acked = 5L
    var importStarted: File? = null
    var importAnswer: ImportResponse = ImportResponse(jobId = "job-import")
    var printRunning = false

    private fun record(text: String) {
        synchronized(calls) { calls += text }
    }

    fun services() = ServerServices(
        auth = auth, devices = devices, feeds = feeds, articles = articles, search = search,
        discovery = discovery, editions = editions, stand = stand, jobs = jobs, sync = sync,
        settings = settings, imports = imports, koboImages = koboImages,
    )

    val feed = FeedDto(
        id = 3, url = "https://example.org/feed", title = "Example", tier = CadenceTier.DAILY, affinity = 0.0,
        sliderStep = SliderStep.AUTO, status = FeedStatus.SUBSCRIBED, addedAt = T0,
    )
    val summary = ArticleSummaryDto(articleId = 7, feedId = 3, feedTitle = "Example", title = "A post", publishedAt = T0, firstSeenAt = T0, readMinutes = 3)
    val bundleArticle = BundleArticleDto(
        feedTitle = "Example", title = "A post", author = null, url = "https://example.org/a", publishedAt = T0,
        summary = null, imageUrl = null, wordCount = 600, blocks = JsonArray(emptyList()), engagement = BundleEngagementDto(),
    )
    val bundle = EditionBundleDto(
        id = 120, date = LocalDate.of(2026, 11, 11), kind = EditionKind.MORNING, number = 12, length = EditionLength.STANDARD,
        releaseAt = T0, finishedAt = null, pages = emptyList(), articles = mapOf("7" to bundleArticle),
    )
    val settingsValue = ServerSettings(
        resetTime = LocalTime.of(6, 30), editionLength = EditionLength.STANDARD, eveningEnabled = false,
        eveningTime = LocalTime.of(18, 0), timeZone = "Europe/London",
    )

    val auth = object : AuthService {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            TestTokens.PHONE -> Principal(1, "phone-1", DeviceKind.PHONE, mustChangePassword = false)
            TestTokens.KOBO -> Principal(1, "kobo-1", DeviceKind.KOBO, mustChangePassword = false)
            TestTokens.LOCKED -> Principal(2, "phone-2", DeviceKind.PHONE, mustChangePassword = true)
            TestTokens.DISABLED -> throw ApiFailure(ApiError.ACCOUNT_DISABLED, "disabled")
            else -> null
        }

        override suspend fun login(request: LoginRequest): LoginResponse {
            record("login(${request.username},${request.device.id})")
            if (request.password != "right") throw ApiFailure(ApiError.INVALID_CREDENTIALS, "wrong")
            return LoginResponse(TestTokens.PHONE, request.device.id, false, UserDto(request.username, "db-1"))
        }

        override suspend fun changePassword(userId: Long, request: ChangePasswordRequest) {
            record("changePassword($userId,${request.current},${request.newPassword})")
        }

        override suspend fun logout(userId: Long, deviceId: String) {
            record("logout($userId,$deviceId)")
        }

        override suspend fun account(userId: Long) = AccountResponse("u$userId", "db-$userId", userId == 2L, false, false)
        override suspend fun resetPassword(username: String) = "new"
        override suspend fun bootstrapUsers(usernames: List<String>) {}
    }

    val devices = object : DeviceService {
        override suspend fun devices(userId: Long, deviceId: String) =
            DevicesResponse(listOf(DeviceDto(deviceId, DeviceKind.PHONE, "Pixel", createdAt = T0, current = true)))

        override suspend fun pairKobo(userId: Long, request: PairKoboRequest): PairKoboResponse {
            record("pairKobo($userId,${request.pairingUri})")
            return PairKoboResponse(DeviceDto("kobo-1", DeviceKind.KOBO, "Kobo", 1264, T0))
        }

        override suspend fun revoke(userId: Long, targetDeviceId: String) {
            record("revoke($userId,$targetDeviceId)")
            if (targetDeviceId == "missing") throw ApiFailure.notFound()
        }

        override suspend fun koboHello(userId: Long, deviceId: String) = KoboHelloResponse("u$userId", "https://news.example.org", "db-$userId")
        override fun koboConfig() = KoboConfigResponse("https://news.example.org")
        override suspend fun markSeen(deviceId: String) {
            record("markSeen($deviceId)")
        }
    }

    val sync = object : SyncService {
        override suspend fun postEvents(userId: Long, deviceId: String, request: EventsRequest): EventsResponse {
            record("postEvents($userId,$deviceId,${request.events.size})")
            acked = request.events.maxOfOrNull { it.seq } ?: acked
            return EventsResponse(acked)
        }

        override suspend fun checkGate(userId: Long, deviceId: String, eventsThrough: Long?) {
            record("checkGate($userId,$deviceId,$eventsThrough)")
            if (eventsThrough == null) throw ApiFailure(ApiError.EVENTS_THROUGH_REQUIRED, "header")
            if (eventsThrough > acked) throw ApiFailure.eventsPending(acked)
        }

        override suspend fun manifest(userId: Long, deviceId: String, editions: Int): ManifestDto {
            record("manifest($userId,$deviceId,$editions)")
            return ManifestDto(dbId = "db-$userId", serverTime = T0, editions = emptyList(), saved = ManifestSavedDto(0, "v0"), acked = mapOf(deviceId to acked))
        }
    }

    val editions = object : EditionService {
        override suspend fun editions(userId: Long, before: Int?, limit: Int): EditionsResponse {
            record("editions($userId,$before,$limit)")
            return EditionsResponse(emptyList())
        }

        override suspend fun bundle(userId: Long, editionId: Long): EditionBundleDto {
            record("bundle($userId,$editionId)")
            if (editionId != 120L) throw ApiFailure.notFound()
            return bundle
        }

        override suspend fun saved(userId: Long) = SavedBundleDto("s1", listOf(7), mapOf("7" to bundleArticle))

        override suspend fun engagement(userId: Long, since: Long): EngagementDeltaResponse {
            record("engagement($userId,$since)")
            return EngagementDeltaResponse(since, emptyList(), emptyList())
        }

        override suspend fun deskNotice(userId: Long, editionId: Long): DeskNoticeResponse {
            record("deskNotice($userId,$editionId)")
            return DeskNoticeResponse(3)
        }
    }

    val stand = object : StandService {
        override suspend fun stand(userId: Long) = StandDto(120, null, ReleaseDto(EditionKind.EVENING, T0), true)
    }

    val jobs = object : JobService {
        override suspend fun print(userId: Long) = JobStart("job-print", alreadyRunning = printRunning)
        override suspend fun extra(userId: Long, request: ExtraRequest): JobStart {
            record("extra($userId,${request.feedIds})")
            return JobStart("job-extra", alreadyRunning = false)
        }

        override suspend fun job(userId: Long, jobId: String): JobDto {
            if (jobId != "job-print") throw ApiFailure.notFound()
            return JobDto(jobId, JobKind.PRINT, JobState.DONE, outcome = JobOutcomeDto(net.tgnra.morningissue.api.JobResult.PRINTED, 121))
        }

        override suspend fun start(userId: Long, kind: JobKind, work: suspend (JobProgress) -> JobOutcomeDto) = JobStart("job-x", false)
    }

    val settings = object : SettingsService {
        override suspend fun settings(userId: Long) = settingsValue
        override suspend fun patch(userId: Long, patch: SettingsPatch): ServerSettings {
            record("patchSettings($userId,${patch.timeZone},${patch.extraOfferDismissedAt})")
            return settingsValue.copy(timeZone = patch.timeZone ?: settingsValue.timeZone)
        }

        override suspend fun setReleaseTimes(userId: Long, request: ReleaseTimesRequest): ServerSettings {
            record("release($userId,${request.resetTime})")
            return settingsValue.copy(resetTime = request.resetTime ?: settingsValue.resetTime)
        }
    }

    val feeds = object : FeedService {
        override suspend fun feeds(userId: Long) = FeedsResponse(listOf(feed), emptyList())
        override suspend fun discover(userId: Long, request: DiscoverFeedRequest): DiscoverFeedResponse {
            if (request.address == "nothing.example") throw ApiFailure(ApiError.NOT_A_FEED, "no feed")
            return DiscoverFeedResponse(listOf(FeedCandidateDto(feed.url)))
        }

        override suspend fun add(userId: Long, request: AddFeedRequest): AddFeedOutcome {
            record("add($userId,${request.url},${request.tags})")
            return AddFeedOutcome(feed, created = request.url != feed.url)
        }

        override suspend fun patch(userId: Long, feedId: Long, patch: FeedPatch): FeedDto {
            record("patchFeed($userId,$feedId,${patch.muteUntil})")
            return feed
        }

        override suspend fun unsubscribe(userId: Long, feedId: Long) {
            record("unsubscribe($userId,$feedId)")
        }

        override suspend fun articles(userId: Long, feedId: Long, before: Instant?, limit: Int): ArticlesResponse {
            record("feedArticles($userId,$feedId,$before,$limit)")
            return ArticlesResponse(listOf(summary))
        }

        override suspend fun health(userId: Long) = FeedHealthResponse(emptyList())
        override suspend fun dismissFlag(userId: Long, feedId: Long) {
            record("dismiss($userId,$feedId)")
        }

        override suspend fun acknowledgeMove(userId: Long, feedId: Long) {
            record("acknowledge($userId,$feedId)")
        }

        override suspend fun retry(userId: Long, feedId: Long) = RetryResponse(RetryOutcome.WORKING)
        override suspend fun importOpml(userId: Long, opml: ByteArray): JobStartedResponse {
            record("importOpml($userId,${opml.size})")
            return JobStartedResponse("job-opml")
        }

        override suspend fun exportOpml(userId: Long) = "<opml version=\"2.0\"/>"
    }

    val articles = object : ArticleService {
        override suspend fun article(userId: Long, articleId: Long): ArticleResponse {
            if (articleId != 7L) throw ApiFailure.notFound()
            return ArticleResponse(bundleArticle, ArticleFeedStatus.SUBSCRIBED)
        }

        override suspend fun fetchFullText(userId: Long, articleId: Long) =
            ArticleResponse(bundleArticle, ArticleFeedStatus.SUBSCRIBED, net.tgnra.morningissue.api.FullTextState.FETCHED)

        override suspend fun shelf(userId: Long, shelf: String, before: Instant?, limit: Int): ArticlesResponse {
            record("shelf($userId,$shelf,$before,$limit)")
            return ArticlesResponse(listOf(summary))
        }
    }

    val search = object : SearchService {
        override suspend fun search(userId: Long, query: SearchQuery): SearchResponse {
            record("search($userId,$query)")
            return SearchResponse(emptyList())
        }
    }

    val discovery = object : DiscoveryService {
        override suspend fun deck(userId: Long) = DeckResponse(emptyList(), needsTopics = true, week = "2026-W46")
        override suspend fun topics(userId: Long) = TopicsResponse(listOf(TopicDto(1, "Science")))
        override suspend fun chooseTopics(userId: Long, request: TopicsRequest): DeckResponse {
            record("chooseTopics($userId,${request.clusterIds})")
            return DeckResponse(emptyList(), needsTopics = false, week = "2026-W46")
        }

        override suspend fun live(userId: Long, url: String): LiveResponse {
            record("live($userId,$url)")
            return LiveResponse(emptyList(), LiveState.FETCHED)
        }

        override suspend fun decide(userId: Long, request: VerdictRequest): VerdictResponse {
            record("decide($userId,${request.url},${request.verdict})")
            return VerdictResponse(if (request.verdict == CardVerdict.SUBSCRIBED) feed else null)
        }

        override suspend fun undo(userId: Long, url: String) {
            record("undo($userId,$url)")
        }

        override suspend fun passed(userId: Long) = PassedResponse(emptyList())
        override suspend fun open(userId: Long, request: OpenDiscoveredRequest) = OpenDiscoveredResponse(7, 3)
    }

    val imports = object : ImportService {
        override suspend fun start(userId: Long, mode: ImportMode, idempotencyKey: String, archive: File): ImportResponse {
            record("import($userId,$mode,$idempotencyKey,${archive.length()})")
            importStarted = archive
            if (mode == ImportMode.REPLACE) throw ApiFailure(ApiError.CONFLICT, "has data")
            return importAnswer
        }
    }

    val koboImages = object : KoboImageService {
        override suspend fun writeZip(userId: Long, request: KoboImagesRequest, output: OutputStream) {
            record("writeZip($userId,${request.width},${request.urls.size})")
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("images.json"))
                zip.write("[]".toByteArray())
                zip.closeEntry()
            }
        }

        override suspend fun prepare(editionId: Long, widths: Set<Int>) {}
    }

    companion object {
        val emptyReport = ImportReportDto()
    }
}

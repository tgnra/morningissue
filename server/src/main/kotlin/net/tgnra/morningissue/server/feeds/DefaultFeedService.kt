package net.tgnra.morningissue.server.feeds

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.encodeToJsonElement
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ArticlesResponse
import net.tgnra.morningissue.api.DiscoverFeedRequest
import net.tgnra.morningissue.api.DiscoverFeedResponse
import net.tgnra.morningissue.api.FeedCandidateDto
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedHealthResponse
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.FlaggedFeedDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.api.JobStartedResponse
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.api.RetryResponse
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.AddFeedOutcome
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.FeedRefresher
import net.tgnra.morningissue.server.service.FeedService
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.sync.DiscoveryResult
import net.tgnra.morningissue.sync.FeedDiscovery
import net.tgnra.morningissue.sync.RepairResult

/**
 * Feeds, subscriptions, OPML and health (4.5) for one user at a time, over the shared feeds.
 * Ported from the app's `RoomFeedRepository`, `DefaultFeedHealthRepository` and
 * `DefaultOpmlTransfer`.
 */
class DefaultFeedService(
    database: ServerDatabase,
    private val subscriptions: Subscriptions,
    private val refresher: FeedRefresher,
    private val health: FeedHealthChecker,
    private val discovery: FeedDiscovery,
    private val opml: OpmlTransfer,
    private val jobs: JobService,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    /** How long `POST /feeds` waits for a new feed's first fetch. */
    private val firstFetchTimeout: Duration = FIRST_FETCH_TIMEOUT,
) : FeedService {
    private val feedDao = database.feedDao()
    private val subscriptionDao = database.subscriptionDao()
    private val healthDao = database.healthDao()
    private val articleDao = database.articleDao()

    override suspend fun feeds(userId: Long): FeedsResponse {
        val now = clock.instant()
        val tags = tagsByFeed(userId)
        return FeedsResponse(
            feeds = subscriptionDao.details(userId).map { it.toDto(tags[it.feed.id].orEmpty(), now) },
            tags = subscriptionDao.tags(userId).map { it.toDto() },
        )
    }

    /** One of the user's feeds; [ApiFailure] `not_found` when they have no subscription to it. */
    suspend fun feed(userId: Long, feedId: Long): FeedDto {
        val row = subscriptionDao.detail(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        return row.toDto(tagsByFeed(userId)[feedId].orEmpty(), clock.instant())
    }

    private suspend fun tagsByFeed(userId: Long): Map<Long, List<String>> =
        subscriptionDao.tagLinks(userId).groupBy({ it.feedId }) { it.name }

    override suspend fun discover(userId: Long, request: DiscoverFeedRequest): DiscoverFeedResponse {
        if (request.address.isBlank()) throw ApiFailure.badRequest("No address given")
        return when (val result = discovery.discover(request.address)) {
            is DiscoveryResult.Found -> DiscoverFeedResponse(
                result.feeds.map { FeedCandidateDto(url = it.url, title = it.title, siteUrl = it.siteUrl, iconUrl = it.iconUrl) },
            )
            DiscoveryResult.NoFeed -> throw ApiFailure(ApiError.NOT_A_FEED, "That address offers no feed")
            is DiscoveryResult.Failed -> throw ApiFailure(ApiError.NOT_A_FEED, result.message)
        }
    }

    override suspend fun add(userId: Long, request: AddFeedRequest): AddFeedOutcome {
        val url = request.url.trim()
        if (url.isEmpty()) throw ApiFailure.badRequest("No feed address given")
        val result = subscriptions.add(userId, url, title = request.title, tags = request.tags)
        if (result.added != Subscriptions.Added.ALREADY) firstFetch(result.feedId)
        return AddFeedOutcome(feed(userId, result.feedId), created = result.added == Subscriptions.Added.CREATED)
    }

    /** Fetches a feed that has never been tried, within [firstFetchTimeout]; a shared feed already fetched is left be. */
    internal suspend fun firstFetch(feedId: Long) {
        val feed = feedDao.getById(feedId) ?: return
        if (feed.lastAttemptAt != null) return
        refresher.refresh(listOf(feedId), Duration.ZERO, deadline = clock.instant().plus(firstFetchTimeout))
    }

    override suspend fun patch(userId: Long, feedId: Long, patch: FeedPatch): FeedDto {
        val row = subscriptionDao.detail(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        patch.title?.trim()?.takeIf { it.isNotEmpty() }?.let { subscriptionDao.updateTitle(userId, feedId, it) }
        patch.sliderStep?.takeIf { it != row.subscription.sliderStep }?.let { step ->
            subscriptionDao.updateSliderStep(userId, feedId, step)
            // The slider is the fix for these flags (the desk's one-tap fixes); the rules do not raise them again.
            if (row.lastFlag in SLIDER_FLAGS) healthDao.setVerdict(userId, feedId, null, null)
        }
        patch.tags?.let { subscriptions.setTags(userId, feedId, it) }
        (patch.muteUntil as? Patch.Set)?.let { subscriptionDao.updateMuteUntil(userId, feedId, it.value) }
        return feed(userId, feedId)
    }

    override suspend fun unsubscribe(userId: Long, feedId: Long) {
        if (!subscriptions.remove(userId, feedId)) throw ApiFailure.notFound("No such feed")
    }

    override suspend fun articles(userId: Long, feedId: Long, before: Instant?, limit: Int): ArticlesResponse {
        subscriptionDao.get(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        val rows = articleDao.forFeed(userId, feedId, before ?: END_OF_TIME, limit.coerceIn(1, MAX_LIMIT))
        return ArticlesResponse(rows.map { it.toSummary() })
    }

    override suspend fun health(userId: Long): FeedHealthResponse {
        health.runForUser(userId)
        val now = clock.instant()
        val tags = tagsByFeed(userId)
        val flagged = subscriptionDao.subscribedDetails(userId).mapNotNull { row ->
            val flag = row.visibleFlag(now) ?: return@mapNotNull null
            FlaggedFeedDto(row.toDto(tags[row.feed.id].orEmpty(), now), flag, row.errorSince, row.lastHttpStatus)
        }.sortedWith(compareBy({ FeedHealthRules.PRIORITY.indexOf(it.flag) }, { it.feed.title.lowercase() }))
        return FeedHealthResponse(flagged)
    }

    /** How many of the user's feeds need attention now (a visible flag other than Gone quiet): the desk notice's count. */
    suspend fun needingAttention(userId: Long): Int {
        val now = clock.instant()
        return subscriptionDao.subscribedDetails(userId).count { row ->
            row.visibleFlag(now)?.let(FeedHealthRules::needsAttention) == true
        }
    }

    override suspend fun dismissFlag(userId: Long, feedId: Long) {
        subscriptionDao.get(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        healthDao.dismiss(userId, feedId, FeedHealthRules.dismissUntil(clock.instant(), config))
    }

    override suspend fun acknowledgeMove(userId: Long, feedId: Long) {
        subscriptionDao.get(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        healthDao.acknowledge(userId, feedId, clock.instant())
    }

    override suspend fun retry(userId: Long, feedId: Long): RetryResponse {
        subscriptionDao.get(userId, feedId) ?: throw ApiFailure.notFound("No such feed")
        val outcome = try {
            when (health.repairNow(feedId)) {
                RepairResult.Failed -> RetryOutcome.STILL_BROKEN
                RepairResult.Recovered -> fetched(feedId, RetryOutcome.WORKING)
                is RepairResult.Relocated -> fetched(feedId, RetryOutcome.MOVED)
            }.also { health.runForUser(userId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RetryOutcome.STILL_BROKEN
        }
        return RetryResponse(outcome)
    }

    private suspend fun fetched(feedId: Long, outcome: RetryOutcome): RetryOutcome =
        if (refresher.refresh(listOf(feedId), Duration.ZERO).answered > 0) outcome else RetryOutcome.STILL_BROKEN

    override suspend fun importOpml(userId: Long, opml: ByteArray): JobStartedResponse {
        val document = try {
            this.opml.read(opml)
        } catch (e: IOException) {
            throw ApiFailure.badRequest(e.message ?: "Not an OPML document")
        }
        val start = jobs.start(userId, JobKind.OPML) { progress ->
            progress.report(JobStage.IMPORTING, 0, document.feeds.size)
            val report = this.opml.import(userId, document) { done, total -> progress.report(JobStage.IMPORTING, done, total) }
            JobOutcomeDto(JobResult.DONE, report = ApiJson.json.encodeToJsonElement(report))
        }
        return JobStartedResponse(start.jobId)
    }

    override suspend fun exportOpml(userId: Long): String = opml.export(userId)

    private companion object {
        val FIRST_FETCH_TIMEOUT: Duration = Duration.ofSeconds(45)

        val SLIDER_FLAGS = setOf(HealthFlag.NOISY, HealthFlag.IGNORED, HealthFlag.LOVED)
    }
}

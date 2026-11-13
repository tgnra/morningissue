package net.tgnra.morningissue.server.editions

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.FeedRefresher
import net.tgnra.morningissue.server.service.JobProgress
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.server.service.JobStart
import org.slf4j.LoggerFactory

/**
 * Jobs in memory (4.3): Print now, Extra, and the imports W-A and W-B start through [start].
 * One job of a kind runs per user at a time; asking again answers the running one. A job is
 * kept for [keepFor] after it ends, then forgotten.
 */
class Jobs(
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** What Print now and Extra run; null leaves them unavailable (a server without editions, or a test). */
    private val actions: JobActions? = null,
    private val keepFor: Duration = Duration.ofHours(1),
) : JobService {
    private class Record(val id: String, val userId: Long, val kind: JobKind) {
        @Volatile var state: JobState = JobState.RUNNING
        @Volatile var stage: JobStage? = null
        @Volatile var done: Int? = null
        @Volatile var total: Int? = null
        @Volatile var outcome: JobOutcomeDto? = null
        @Volatile var endedAt: Instant? = null
        @Volatile var job: Job? = null

        fun dto() = JobDto(id, kind, state, stage, done, total, outcome)
    }

    private val records = ConcurrentHashMap<String, Record>()
    private val lock = Any()

    override suspend fun print(userId: Long): JobStart {
        val actions = checkNotNull(actions) { "Print is not wired" }
        return start(userId, JobKind.PRINT) { progress -> actions.print(userId, progress) }
    }

    override suspend fun extra(userId: Long, request: ExtraRequest): JobStart {
        val actions = checkNotNull(actions) { "Extra is not wired" }
        if (request.feedIds.isEmpty()) throw ApiFailure.badRequest("Name at least one feed")
        return start(userId, JobKind.EXTRA) { progress -> actions.extra(userId, request.feedIds.toSet(), progress) }
    }

    override suspend fun job(userId: Long, jobId: String): JobDto {
        expire()
        val record = records[jobId]?.takeIf { it.userId == userId } ?: throw ApiFailure.notFound("No such job")
        return record.dto()
    }

    override suspend fun start(userId: Long, kind: JobKind, work: suspend (JobProgress) -> JobOutcomeDto): JobStart {
        expire()
        val record = synchronized(lock) {
            running(userId, kind)?.let { return JobStart(it.id, alreadyRunning = true) }
            Record(UUID.randomUUID().toString(), userId, kind).also { records[it.id] = it }
        }
        record.job = scope.launch {
            val progress = JobProgress { stage, done, total ->
                record.stage = stage
                record.done = done
                record.total = total
            }
            val outcome = try {
                work(progress)
            } catch (e: CancellationException) {
                record.state = JobState.FAILED
                record.outcome = JobOutcomeDto(JobResult.FAILED, message = "The server stopped the job")
                record.endedAt = clock.instant()
                throw e
            } catch (e: ApiFailure) {
                JobOutcomeDto(JobResult.FAILED, message = e.message)
            } catch (e: Exception) {
                log.warn("{} job for user {} failed", kind, userId, e)
                JobOutcomeDto(JobResult.FAILED, message = e.message ?: e.javaClass.simpleName)
            }
            record.outcome = outcome
            record.endedAt = clock.instant()
            record.state = if (outcome.result == JobResult.FAILED) JobState.FAILED else JobState.DONE
        }
        return JobStart(record.id, alreadyRunning = false)
    }

    /** The user's running job of [kind], if any (the stand's `jobRunning`). */
    fun runningJobId(userId: Long, kind: JobKind): String? = running(userId, kind)?.id

    /** The coroutine behind [jobId], for tests to wait on. */
    fun coroutineOf(jobId: String): Job? = records[jobId]?.job

    private fun running(userId: Long, kind: JobKind): Record? =
        records.values.firstOrNull { it.userId == userId && it.kind == kind && it.state == JobState.RUNNING }

    private fun expire() {
        val now = clock.instant()
        records.values.removeIf { record -> record.endedAt?.let { Duration.between(it, now) >= keepFor } == true }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Jobs::class.java)
    }
}

/** What Print now and Extra jobs do. */
interface JobActions {
    suspend fun print(userId: Long, progress: JobProgress): JobOutcomeDto

    suspend fun extra(userId: Long, feedIds: Set<Long>, progress: JobProgress): JobOutcomeDto
}

/**
 * Print now and Extra (4.3). Print now refreshes every feed with no leeway, builds the edition
 * that is due (`ensureCurrentEdition`), then fetches its full text and Kobo pictures. Extra
 * refreshes the chosen feeds, prints or reprints today's Extra edition, and when the feeds
 * have nothing printable turns the stand's offer down for the user (as the app did).
 */
class EditionJobActions(
    private val database: ServerDatabase,
    private val runner: BuildRunner,
    private val publisher: EditionPublisher,
    private val refresher: FeedRefresher,
    private val settings: UserSettingsStore,
    private val clock: Clock,
    private val timings: EditionTimings = EditionTimings(),
) : JobActions {
    private val articleDao = database.editionArticleDao()
    private val editionDao = database.editionDao()

    override suspend fun print(userId: Long, progress: JobProgress): JobOutcomeDto {
        val outcome = runner.run(userId, BuildTarget.Current, refreshMaxAge = Duration.ZERO) { stage, done, total ->
            progress.report(stage, done, total)
        }
        outcome.error?.let { log.warn("Print now for user {} failed", userId, it) }
        return JobOutcomeDto(outcome.printResult, outcome.editionId, message = outcome.error?.message)
    }

    override suspend fun extra(userId: Long, feedIds: Set<Long>, progress: JobProgress): JobOutcomeDto {
        val subscribed = articleDao.subscribedFeedIds(userId).toSet()
        val feeds = feedIds.filterTo(HashSet()) { it in subscribed }
        progress.report(JobStage.FETCHING_FEEDS, null, null)
        try {
            refresher.refresh(feeds, Duration.ZERO, clock.instant().plus(timings.refreshTimeout))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Refresh before an Extra edition failed", e)
        }
        progress.report(JobStage.SETTING_PAGES, null, null)
        val today = clock.instant().atZone(settings.get(userId).zone).toLocalDate()
        val reprints = editionDao.getByDate(userId, today, net.tgnra.morningissue.model.EditionKind.EXTRA) != null
        val printed = publisher.publishExtraEdition(userId, feeds)
        if (printed == null) {
            // Nothing recent enough to print; do not keep offering the same sources.
            settings.update(userId, customize = false) { it.copy(extraOfferDismissedAt = clock.instant().toEpochMilli()) }
            return JobOutcomeDto(JobResult.EXTRA_NOTHING)
        }
        return JobOutcomeDto(if (reprints) JobResult.EXTRA_REPRINTED else JobResult.EXTRA_PRINTED, printed)
    }

    private companion object {
        val log = LoggerFactory.getLogger(EditionJobActions::class.java)
    }
}

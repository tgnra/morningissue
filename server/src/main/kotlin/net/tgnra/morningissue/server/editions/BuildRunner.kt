package net.tgnra.morningissue.server.editions

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.edition.DueEditions
import net.tgnra.morningissue.engine.Release
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.EditionServiceImpl.Companion.toSummary
import net.tgnra.morningissue.server.service.FeedRefresher
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.server.service.KoboImageService

/** Timings of the scheduler and of Print now (section 3; `[editions]` and `[refresh]` in the config). */
data class EditionTimings(
    /** Build this long before a release. */
    val buildLead: Duration = Duration.ofMinutes(30),
    /** A pre-print refresh skips feeds tried this recently (the 2-hour leeway). */
    val prePrintMaxAge: Duration = Duration.ofMinutes(120),
    /** The periodic refresh's cadence. */
    val refreshInterval: Duration = Duration.ofMinutes(120),
    /** How long a pre-print refresh may take. */
    val refreshTimeout: Duration = Duration.ofSeconds(240),
    /** How long full text and Kobo pictures may take after a build. */
    val prefetchTimeout: Duration = Duration.ofMinutes(4),
    /** How often the scheduler looks at the clock. */
    val tick: Duration = Duration.ofSeconds(30),
    /** Users built at once. */
    val userParallelism: Int = 2,
    /** Full-text fetches at once per edition. */
    val fullTextConcurrency: Int = 4,
)

/** Which edition a run of [BuildRunner] is for. */
sealed interface BuildTarget {
    /** The scheduler's: the edition of [release], built ahead of it. */
    data class Scheduled(val release: Release) : BuildTarget

    /** Print now, or the scheduler's late build after downtime: the edition due at this moment. */
    data object Current : BuildTarget
}

/** What one build run did; the app's `EarlyBuildOutcome`. */
data class BuildOutcome(
    /** The refresh reached the feeds: it finished in time and at least one answered, or there were none. */
    val synced: Boolean,
    /** The edition the run was for, built now or found already built; null when there is none. */
    val editionId: Long?,
    val fullTextStored: Int = 0,
    val imagesPrepared: Boolean = false,
    /** Set when building or prefetching failed. */
    val error: Throwable? = null,
) {
    /** The job result of a print with this outcome: the app's `EditionPrinter.result`. */
    val printResult: JobResult
        get() = when {
            editionId != null -> if (synced) JobResult.PRINTED else JobResult.PRINTED_OFFLINE
            error != null -> JobResult.FAILED
            synced -> JobResult.NOTHING_NEW
            else -> JobResult.UNREACHABLE
        }
}

/**
 * The stages of a build, shared by the scheduler and Print now: the app's `EarlyBuild`
 * without WorkManager. Refresh (or take the scheduler's refresh), build, record "nothing new",
 * then full text for every printed story and the user's Kobo pictures. Each stage is
 * isolated: a failed or slow refresh does not stop the build, a failed prefetch does not undo
 * it.
 *
 * **Nothing new to print.** When the refresh reached the feeds and the build stored no edition,
 * the release it was for is recorded (`StoredSettings.nothingNewFor`), which is how the stand
 * tells "nothing new today" from a failed build (`DueEditions`).
 */
class BuildRunner(
    private val database: ServerDatabase,
    private val publisher: EditionPublisher,
    private val refresher: FeedRefresher,
    private val fullText: FullTextService,
    private val koboImages: KoboImageService,
    private val settings: UserSettingsStore,
    private val clock: Clock,
    private val timings: EditionTimings = EditionTimings(),
) {
    private val articleDao = database.editionArticleDao()
    private val editionDao = database.editionDao()
    private val userStateDao = database.userStateDao()

    /**
     * Runs every stage for [userId] and returns what happened; only throws on cancellation.
     *
     * @param refreshMaxAge refresh the user's feeds first, skipping those tried this recently
     *   (zero for Print now); null when the caller refreshed already (the scheduler's union
     *   refresh), whose report `FeedRefresher.reachedFeedsOf` then gives
     * @param onStage told each stage as it starts
     */
    suspend fun run(
        userId: Long,
        target: BuildTarget,
        refreshMaxAge: Duration?,
        onStage: (JobStage, Int?, Int?) -> Unit = { _, _, _ -> },
    ): BuildOutcome {
        onStage(JobStage.FETCHING_FEEDS, null, null)
        val synced = if (refreshMaxAge != null) refresh(userId, refreshMaxAge) else refresher.reachedFeedsOf(userId)
        var outcome = BuildOutcome(synced = synced, editionId = null)
        try {
            onStage(JobStage.SETTING_PAGES, null, null)
            val built = build(userId, target)
            outcome = outcome.copy(editionId = built.editionId)
            if (synced) built.nothingNewFor.forEach { settings.recordNothingNew(userId, it) }
            val editionId = built.editionId
            if (editionId != null) {
                outcome = withTimeoutOrNull(timings.prefetchTimeout.toMillis()) { prefetch(userId, editionId, outcome, onStage) } ?: outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = outcome.copy(error = e)
        }
        return outcome
    }

    /** Whether a refresh of the user's subscribed feeds reached them. */
    private suspend fun refresh(userId: Long, maxAge: Duration): Boolean = try {
        val feeds = articleDao.subscribedFeedIds(userId)
        val deadline = clock.instant().plus(timings.refreshTimeout)
        val report = withTimeoutOrNull(timings.refreshTimeout.toMillis()) { refresher.refresh(feeds, maxAge, deadline) }
        report?.reachedFeeds ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private class Built(val editionId: Long?, val nothingNewFor: List<Instant>)

    private suspend fun build(userId: Long, target: BuildTarget): Built = when (target) {
        is BuildTarget.Scheduled -> {
            val id = publisher.buildFor(userId, target.release)
            Built(id, if (id == null) listOf(target.release.at) else emptyList())
        }
        BuildTarget.Current -> {
            // The current edition: the morning one for the latest reset and, when it is on and
            // its time has passed, the evening one. The stored editions say which now exist.
            publisher.ensureCurrentEdition(userId)
            val now = clock.instant()
            val current = settings.get(userId)
            val due = DueEditions.due(now, current.schedule, current.zone)
            val released = editionDao.getReleased(userId, now).map { it.toSummary() }
            Built(
                editionId = due.firstNotNullOfOrNull { DueEditions.editionFor(it, released) }?.id,
                nothingNewFor = DueEditions.unprinted(due, released).map { it.at },
            )
        }
    }

    /** Full text for every printed story (briefs included), then the pictures at the user's Kobo widths. */
    private suspend fun prefetch(userId: Long, editionId: Long, outcome: BuildOutcome, onStage: (JobStage, Int?, Int?) -> Unit): BuildOutcome {
        val stories = editionDao.getItems(userId, editionId).count { it.item.kind != net.tgnra.morningissue.model.ItemKind.INDEX }
        onStage(JobStage.FETCHING_TEXT, 0, stories)
        val stored = fullText.ensureForEdition(editionId, timings.fullTextConcurrency)
        onStage(JobStage.FETCHING_TEXT, stories, stories)
        val withText = outcome.copy(fullTextStored = stored)
        val widths = userStateDao.koboWidths(userId).toSet()
        if (widths.isEmpty()) return withText
        onStage(JobStage.FETCHING_IMAGES, null, null)
        koboImages.prepare(editionId, widths)
        return withText.copy(imagesPrepared = true)
    }
}

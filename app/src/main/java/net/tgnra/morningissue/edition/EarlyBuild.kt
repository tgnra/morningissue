package net.tgnra.morningissue.edition

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.AppContainer
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.relay.RelayWorker
import net.tgnra.morningissue.sync.FeedSyncer
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult

/** Downloads images into the disk cache so an edition reads offline. Never throws except on cancellation. */
fun interface ImagePrefetcher {
    suspend fun prefetch(urls: List<String>)

    /**
     * The pictures of an edition's printed stories: [articleIds] in page order, and
     * [pageImageUrls], the lead images its pages show. A prefetcher that only knows addresses
     * fetches the page images; [StoringImagePrefetcher] keeps every picture of the stories.
     */
    suspend fun prefetchArticles(articleIds: List<Long>, pageImageUrls: List<String>) {
        if (pageImageUrls.isNotEmpty()) prefetch(pageImageUrls)
    }
}

/**
 * Puts the lead image and the body images of each printed story in the image store
 * (`data/images/`), re-encoded for display, so the edition and its articles read offline and
 * the pictures count against the storage budget. This is what the early build uses.
 */
class StoringImagePrefetcher(private val store: ImageStore) : ImagePrefetcher {
    /** Addresses alone cannot be stored: an image is kept for a post. */
    override suspend fun prefetch(urls: List<String>) = Unit

    override suspend fun prefetchArticles(articleIds: List<Long>, pageImageUrls: List<String>) {
        store.storeForArticles(articleIds)
    }
}

/**
 * Fills the shared Coil loader's disk cache; nothing is kept in memory. No longer used by the
 * early build, which keeps images in the store ([StoringImagePrefetcher]).
 */
class CoilImagePrefetcher(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : ImagePrefetcher {
    override suspend fun prefetch(urls: List<String>) = coroutineScope {
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        urls.distinct().map { url ->
            async {
                permits.withPermit {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        // The disk cache keeps the original bytes; decode small to stay light.
                        .size(DECODE_SIZE_PX, DECODE_SIZE_PX)
                        .build()
                    // Returns an ErrorResult rather than throwing; a missing image is not fatal.
                    imageLoader.execute(request)
                }
            }
        }.awaitAll()
        Unit
    }

    private companion object {
        const val PARALLEL_DOWNLOADS = 4
        const val DECODE_SIZE_PX = 64
    }
}

/** Which edition a run of [EarlyBuild] is for. */
enum class BuildTarget {
    /**
     * The background job: the edition of the coming release when that is near, otherwise the
     * current one (see [EarlyBuild]).
     */
    Scheduled,

    /** Print now: the edition that is due at this moment, whatever comes next. */
    Current,
}

/** How far a run of [EarlyBuild] has got; Print now shows these as its progress. */
sealed interface BuildStage {
    /** Fetching every feed. `FeedSyncer.status` has the count. */
    data object Syncing : BuildStage

    /** Choosing and laying out the stories. */
    data object Building : BuildStage

    /** Edition [editionId] is stored; [done] of its [total] stories have their full text. */
    data class FetchingText(val editionId: Long, val done: Int, val total: Int) : BuildStage

    /** Fetching the [count] pictures edition [editionId] shows. */
    data class FetchingImages(val editionId: Long, val count: Int) : BuildStage
}

/** What one early build did; for logs, tests and Print now's result. */
data class EarlyBuildOutcome(
    /**
     * True when the sync reached the feeds: it finished in time and at least one feed
     * answered (or there were none to ask). False when it failed, timed out, or every feed
     * failed, as with no network. The build goes ahead either way, from what is stored.
     */
    val synced: Boolean,
    /**
     * The edition the run was for, built now or found already built. Null when there is
     * none: nothing new to print when [synced], unknown when not.
     */
    val editionId: Long?,
    val fullTextStored: Int = 0,
    val imagesRequested: Int = 0,
    /** Set when building or prefetching failed. */
    val error: Throwable? = null,
)

/**
 * The work of `EarlyBuildWorker`, kept apart from WorkManager so it can be tested directly:
 * final sync, build, prefetch, queue relay sync's Publish, then schedule the next run. Each stage is isolated: a failed or
 * slow sync does not stop the build, a failed prefetch does not undo it, and the next run is
 * scheduled whatever happened (except on cancellation, when WorkManager reruns the job itself).
 *
 * If the job ran so late that the reset it aimed for has passed (or, after a clock change, so
 * early that the next reset is more than `earlyBuildMaxLeadMinutes` away), building "the next
 * edition" now would freeze tomorrow's paper a day ahead. It then makes sure the current
 * edition exists instead.
 *
 * **Print now** runs the same stages in the foreground ([BuildTarget.Current], through
 * `EditionPrinter`): it always builds the edition that is due, and reports each stage as it
 * starts so the stand can show progress.
 *
 * **Nothing new to print.** When the sync reached the feeds and the build still stored no
 * edition, the run writes down the release it was for (`SettingsRepository.recordNothingNew`).
 * That record is how the stand tells "nothing new today" from an edition that failed to
 * build; a run that fails, or reaches no feed and prints nothing, records nothing. The rule is
 * in [DueEditions].
 */
class EarlyBuild(
    private val feedSyncer: FeedSyncer,
    private val publisher: EditionPublisher,
    private val editions: EditionRepository,
    private val fullTextFetcher: FullTextFetcher,
    private val images: ImagePrefetcher,
    /** Durably schedules the next early build; called last. */
    private val scheduleNext: suspend () -> Unit,
    private val clock: Clock,
    /** Read for the release times; written when a run finds nothing new to print. */
    private val settings: SettingsRepository,
    private val config: RankingConfig = RankingConfig.Default,
    // WorkManager stops a worker after 10 minutes; the stages together stay under that.
    private val syncTimeout: Duration = Duration.ofMinutes(4),
    private val prefetchTimeout: Duration = Duration.ofMinutes(4),
    /** The device's current time zone; not taken from [clock], whose zone is fixed at process start. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * The final stage, after the prefetch and before the next run is scheduled: in the app it
     * queues relay sync's Publish (`relay/RelayWorker`). Isolated like the others: whatever
     * it throws is ignored, so a relay problem never fails the build.
     */
    private val afterBuild: suspend (EarlyBuildOutcome) -> Unit = {},
) {
    /**
     * Runs every stage and returns what happened; it only throws on cancellation, or if the
     * next run cannot be scheduled. [onStage] is told each stage as it starts, and each story
     * as its text arrives, possibly from several threads at once.
     */
    suspend fun run(target: BuildTarget = BuildTarget.Scheduled, onStage: (BuildStage) -> Unit = {}): EarlyBuildOutcome {
        onStage(BuildStage.Syncing)
        val synced = sync()
        var outcome = EarlyBuildOutcome(synced = synced, editionId = null)
        try {
            onStage(BuildStage.Building)
            val built = build(target)
            val editionId = built.editionId
            outcome = outcome.copy(editionId = editionId)
            // Only a run that reached the feeds knows there was nothing new.
            if (synced) built.nothingNewFor.forEach { settings.recordNothingNew(it) }
            if (editionId != null) {
                outcome = withTimeoutOrNull(prefetchTimeout.toMillis()) { prefetch(editionId, outcome, onStage) } ?: outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = outcome.copy(error = e)
        }
        try {
            afterBuild(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Relay sync is a passenger: the edition is built whatever happens to it.
        }
        scheduleNext()
        return outcome
    }

    /** Whether the sync reached the feeds; see [EarlyBuildOutcome.synced]. */
    private suspend fun sync(): Boolean = try {
        val report = withTimeoutOrNull(syncTimeout.toMillis()) { feedSyncer.syncAll() }
        report != null && (report.feedsChecked == 0 || report.feedsFailed < report.feedsChecked)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The edition a build was for, and the releases it found nothing to print for. */
    private class Built(val editionId: Long?, val nothingNewFor: List<Instant>)

    private suspend fun build(target: BuildTarget): Built {
        val next = publisher.nextReleaseAt()
        val inWindow = target == BuildTarget.Scheduled && next != null &&
            Duration.between(clock.instant(), next) <= Duration.ofMinutes(config.earlyBuildMaxLeadMinutes)
        if (inWindow) {
            val editionId = publisher.buildNextEdition()
            return Built(editionId, if (editionId == null) listOfNotNull(next) else emptyList())
        }
        // The current edition: the morning one for the latest reset and, when it is switched
        // on and its time has passed, the evening one. What the publisher returns is the newest
        // edition on the stand of any kind, which may be an older paper or an Extra, so the
        // stored editions say which of the due ones now exist.
        publisher.ensureCurrentEdition()
        val current = settings.settings.first()
        val due = DueEditions.due(clock.instant(), current.schedule, zone())
        val released = editions.observeReleasedEditions().first()
        return Built(
            editionId = due.firstNotNullOfOrNull { DueEditions.editionFor(it, released) }?.id,
            nothingNewFor = DueEditions.unprinted(due, released).map { it.at },
        )
    }

    /**
     * Full text for every printed story (briefs included: tapping one opens the reader), and
     * the images the pages show. Index entries are fetched when opened.
     */
    private suspend fun prefetch(editionId: Long, outcome: EarlyBuildOutcome, onStage: (BuildStage) -> Unit): EarlyBuildOutcome {
        val pages = editions.getEdition(editionId)?.pages.orEmpty().filter { it !is EditionPage.Back }
        val articleIds = pages.flatMap { it.stories }.map { it.articleId }.distinct()
        val imageUrls = pages.filter { it !is EditionPage.InBrief }
            .flatMap { it.stories }
            .mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
            .distinct()

        onStage(BuildStage.FetchingText(editionId, 0, articleIds.size))
        val done = AtomicInteger()
        val stored = coroutineScope {
            val permits = Semaphore(PARALLEL_FETCHES)
            articleIds.map { id ->
                async {
                    permits.withPermit { fullTextFetcher.ensureFullText(id) }.also {
                        onStage(BuildStage.FetchingText(editionId, done.incrementAndGet(), articleIds.size))
                    }
                }
            }.awaitAll().count { it is FullTextResult.Stored }
        }
        val withText = outcome.copy(fullTextStored = stored)
        // After the text, so that the pictures in fetched bodies are known. The stories whose
        // pictures the pages show come first; briefs show none until they are opened.
        val pictured = pages.filter { it !is EditionPage.InBrief }.flatMap { it.stories }.map { it.articleId }
        if (imageUrls.isNotEmpty()) onStage(BuildStage.FetchingImages(editionId, imageUrls.size))
        images.prefetchArticles((pictured + articleIds).distinct(), imageUrls)
        return withText.copy(imagesRequested = imageUrls.size)
    }

    companion object {
        private const val PARALLEL_FETCHES = 4

        /** The production wiring, from what the container offers to every worker. */
        fun from(context: Context, container: AppContainer): EarlyBuild {
            val scheduler = container.editionScheduler
            return EarlyBuild(
                feedSyncer = container.feedSyncer,
                publisher = container.editionPublisher,
                editions = container.editions,
                fullTextFetcher = container.fullTextFetcher,
                images = StoringImagePrefetcher(container.images),
                scheduleNext = {
                    // Wait for the schedule to be stored before the worker reports back; any
                    // other scheduler only offers the fire-and-forget call.
                    if (scheduler is WorkManagerEditionScheduler) scheduler.reschedule() else scheduler.scheduleEarlyBuild()
                },
                clock = container.clock,
                settings = container.settings,
                afterBuild = { RelayWorker.enqueuePublish(context) },
            )
        }
    }
}

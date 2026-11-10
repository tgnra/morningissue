package net.tgnra.morningissue.edition

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.AppContainer
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.sync.FeedSyncer
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult

/** Downloads images into the disk cache so an edition reads offline. Never throws except on cancellation. */
fun interface ImagePrefetcher {
    suspend fun prefetch(urls: List<String>)
}

/** Fills the shared Coil loader's disk cache; nothing is kept in memory. */
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

/** What one early build did; for logs and tests. */
data class EarlyBuildOutcome(
    /** False when the final sync failed or timed out; the build went ahead regardless. */
    val synced: Boolean,
    /** The edition built or found, or null when there was nothing to build from. */
    val editionId: Long?,
    val fullTextStored: Int = 0,
    val imagesRequested: Int = 0,
    /** Set when building or prefetching failed. */
    val error: Throwable? = null,
)

/**
 * The work of `EarlyBuildWorker`, kept apart from WorkManager so it can be tested directly:
 * final sync, build, prefetch, then schedule the next run. Each stage is isolated: a failed or
 * slow sync does not stop the build, a failed prefetch does not undo it, and the next run is
 * scheduled whatever happened (except on cancellation, when WorkManager reruns the job itself).
 *
 * If the job ran so late that the reset it aimed for has passed (or, after a clock change, so
 * early that the next reset is more than `earlyBuildMaxLeadMinutes` away), building "the next
 * edition" now would freeze tomorrow's paper a day ahead. It then makes sure the current
 * edition exists instead, exactly as opening the app would.
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
    private val config: RankingConfig = RankingConfig.Default,
    // WorkManager stops a worker after 10 minutes; the stages together stay under that.
    private val syncTimeout: Duration = Duration.ofMinutes(4),
    private val prefetchTimeout: Duration = Duration.ofMinutes(4),
) {
    suspend fun run(): EarlyBuildOutcome {
        val synced = sync()
        var outcome = EarlyBuildOutcome(synced = synced, editionId = null)
        try {
            val editionId = build()
            outcome = outcome.copy(editionId = editionId)
            if (editionId != null) {
                outcome = withTimeoutOrNull(prefetchTimeout.toMillis()) { prefetch(editionId, outcome) } ?: outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = outcome.copy(error = e)
        }
        scheduleNext()
        return outcome
    }

    private suspend fun sync(): Boolean = try {
        withTimeoutOrNull(syncTimeout.toMillis()) { feedSyncer.syncAll() } != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun build(): Long? {
        val untilNext = publisher.nextReleaseAt()?.let { Duration.between(clock.instant(), it) }
        val inWindow = untilNext != null && untilNext <= Duration.ofMinutes(config.earlyBuildMaxLeadMinutes)
        return if (inWindow) publisher.buildNextEdition() else publisher.ensureCurrentEdition()
    }

    /**
     * Full text for every printed story (briefs included: tapping one opens the reader), and
     * the images the pages show. Index entries are fetched when opened.
     */
    private suspend fun prefetch(editionId: Long, outcome: EarlyBuildOutcome): EarlyBuildOutcome {
        val pages = editions.getEdition(editionId)?.pages.orEmpty().filter { it !is EditionPage.Back }
        val articleIds = pages.flatMap { it.stories }.map { it.articleId }.distinct()
        val imageUrls = pages.filter { it !is EditionPage.InBrief }
            .flatMap { it.stories }
            .mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
            .distinct()

        val stored = coroutineScope {
            val permits = Semaphore(PARALLEL_FETCHES)
            articleIds.map { id ->
                async { permits.withPermit { fullTextFetcher.ensureFullText(id) } }
            }.awaitAll().count { it is FullTextResult.Stored }
        }
        val withText = outcome.copy(fullTextStored = stored)
        if (imageUrls.isEmpty()) return withText
        images.prefetch(imageUrls)
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
                images = CoilImagePrefetcher(context.applicationContext, container.imageLoader),
                scheduleNext = {
                    // Wait for the schedule to be stored before the worker reports back; any
                    // other scheduler only offers the fire-and-forget call.
                    if (scheduler is WorkManagerEditionScheduler) scheduler.reschedule() else scheduler.scheduleEarlyBuild()
                },
                clock = container.clock,
            )
        }
    }
}

package net.tgnra.morningissue.edition

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.sync.FeedSyncer
import net.tgnra.morningissue.sync.SyncReport

/** What a print is for. */
sealed interface PrintJob {
    /**
     * The scheduled edition that is due: printed when the stand comes to the foreground, or at
     * the reader's request (Print now, Try again, "Check for new posts"), which [forceSync]s.
     */
    data class Due(val forceSync: Boolean) : PrintJob

    /** An Extra edition from the sources the reader has just added, or a reprint of today's with them. */
    data class Extra(val feedIds: Set<Long>) : PrintJob
}

/** How far a print has got; the stand shows these as its progress. */
sealed interface PrintStage {
    /** A last sync of every feed (or, for an Extra edition, of its new sources). `FeedSyncer.status` has the count. */
    data object Syncing : PrintStage

    /** The on-device model is rating the shortlist: [done] of [total] posts asked about. */
    data class Rating(val done: Int, val total: Int) : PrintStage

    /** Choosing, laying out and storing the stories. */
    data object Building : PrintStage
}

/**
 * The on-device model's part in a print: rate [articleIds] within [budget], telling
 * [onProgress] how many of how many have been asked about (first with 0 when the model is
 * actually asked anything). Returns how many were rated. Never throws but for cancellation;
 * returns 0 without calling [onProgress] when the model is off, missing, or the app is not in
 * the foreground.
 */
fun interface PrintRating {
    suspend fun rate(articleIds: List<Long>, budget: Duration, onProgress: (done: Int, total: Int) -> Unit): Int

    companion object {
        val None = PrintRating { _, _, _ -> 0 }
    }
}

/** The time limits of a print; see [EditionPress]. */
data class PrintTimings(
    /**
     * The scheduled print syncs first only when no feed has answered a sync for this long: the
     * longer interval of the periodic background sync, so it syncs when that has fallen
     * behind (held back by Doze, or the phone was offline), not after every ordinary run.
     */
    val syncIfOlderThan: Duration = Duration.ofHours(2),
    /** The scheduled print's sync; one that runs over carries on in the background. */
    val syncTimeout: Duration = Duration.ofSeconds(20),
    /** An Extra edition's sync of its new sources, which have never been read. */
    val extraSyncTimeout: Duration = Duration.ofSeconds(60),
    /** The model's rating of the shortlist, waiting for a pass already under way included. */
    val ratingBudget: Duration = Duration.ofSeconds(45),
    /** Sync and rating together: whatever happens in them, the build starts by then. */
    val beforeBuild: Duration = Duration.ofSeconds(75),
    /** The build itself; past it the print fails and can be tried again. */
    val buildTimeout: Duration = Duration.ofSeconds(60),
)

/** What one print did. */
data class PrintOutcome(
    val job: PrintJob,
    /**
     * True when the posts the edition was built from are known to be current: this print's
     * sync reached the feeds (it finished in time and at least one feed answered, or there
     * were none to ask), or, when it did not sync, a feed answered a sync within
     * `syncIfOlderThan`. False when offline with an older sync, or when the sync failed.
     */
    val synced: Boolean,
    /**
     * The edition the print was for, built now or found already built. Null when there is
     * none: nothing new to print when [synced], unknown when not.
     */
    val editionId: Long?,
    /** Posts the model rated during this print. */
    val rated: Int = 0,
    /** An Extra edition that replaced today's first printing. */
    val reprinted: Boolean = false,
    /** Set when the build failed or ran out of time. */
    val error: Throwable? = null,
)

/**
 * **Printing an edition in the foreground.** Editions are not built in the background any
 * more: AICore runs Gemini Nano only for an app in the foreground, so a background build could
 * only use ratings left from earlier visits. Instead the edition that is due is printed when
 * the reader opens the app (`EditionPrinter` decides when, from [DueEditions]), and Print now
 * and the Extra edition run the same stages:
 *
 * 1. **Sync** ([PrintStage.Syncing]). For the scheduled edition, only when no feed has
 *    answered a sync for `syncIfOlderThan` (the periodic background sync usually has), or when
 *    the reader asked ([PrintJob.Due.forceSync]), and only online; at most `syncTimeout`.
 *    For an Extra edition, its new sources, at most `extraSyncTimeout`.
 * 2. **Rate** ([PrintStage.Rating]). The model rates the shortlist of the edition about to be
 *    built (`EditionPublisher.shortlist`), at most `ratingBudget`, checking before every call
 *    that the app is still in the foreground: a reader who leaves mid-print stops the rating,
 *    and the build goes on with what is rated. Skipped when the model is off or missing.
 * 3. **Build** ([PrintStage.Building]) and freeze, as the publisher always has, at most
 *    `buildTimeout`. The build reads only stored posts and ratings, never the foreground.
 *
 * Sync and rating together are held to `beforeBuild`, so the build always starts, and each
 * stage is isolated: a failed sync or rating does not stop the build. The full text and
 * pictures are fetched afterwards ([EditionPrefetch]), without the reader waiting.
 *
 * **Which posts an edition takes.** The cutoff is the moment the build runs, as it always has
 * been for an edition built in the foreground: everything first seen (stored by a sync) since
 * the previous cutoff and up to then. Posts the print's own sync stores are first seen before
 * that cutoff, so they are in it. A post first seen after it, including one stored by a sync
 * that outlasted its time limit and finished in the background, is in the next edition.
 *
 * **Nothing new to print.** When the posts are known to be current ([PrintOutcome.synced])
 * and the build still stored no edition, the print writes down the release it was for
 * (`SettingsRepository.recordNothingNew`), as the early build used to; see [DueEditions].
 */
class EditionPress(
    private val feedSyncer: FeedSyncer,
    private val publisher: EditionPublisher,
    private val editions: EditionRepository,
    /** Read for the release times; written when a print finds nothing new, or an Extra nothing to print. */
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val rating: PrintRating = PrintRating.None,
    /** When a feed last answered a sync (`FeedDao.lastSyncAt`). */
    private val lastSyncAt: suspend () -> Instant? = { null },
    /** Whether the phone has a working connection; offline the sync is skipped. */
    private val isOnline: () -> Boolean = { true },
    private val config: RankingConfig = RankingConfig.Default,
    private val timings: PrintTimings = PrintTimings(),
    /** The device's current time zone; not taken from [clock], whose zone is fixed at process start. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Runs every stage and returns what happened; it only throws on cancellation. [onStage] is
     * told each stage as it starts, and each step of the rating.
     */
    suspend fun run(job: PrintJob, onStage: (PrintStage) -> Unit = {}): PrintOutcome {
        var synced = job is PrintJob.Due && isFresh()
        var rated = 0
        withTimeoutOrNull(timings.beforeBuild.toMillis()) {
            synced = sync(job, synced, onStage)
            rated = rate(job, onStage)
        }
        onStage(PrintStage.Building)
        // Read before the build: a reprint replaces today's Extra, and the result says so.
        val reprint = job is PrintJob.Extra && todaysExtraExists()
        var outcome = PrintOutcome(job, synced = synced, editionId = null, rated = rated, reprinted = false)
        try {
            val built = withTimeout(timings.buildTimeout.toMillis()) { build(job) }
            outcome = outcome.copy(editionId = built.editionId, reprinted = reprint && built.editionId != null)
            when (job) {
                // Only a print from current posts knows there was nothing new.
                is PrintJob.Due -> if (synced) built.nothingNewFor.forEach { settings.recordNothingNew(it) }
                // Nothing recent enough to print; do not keep offering the same sources.
                is PrintJob.Extra -> if (built.editionId == null) settings.setExtraOfferDismissedAt(clock.instant())
            }
        } catch (e: TimeoutCancellationException) {
            outcome = outcome.copy(error = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = outcome.copy(error = e)
        }
        return outcome
    }

    /** A feed answered a sync recently enough that the scheduled print need not sync. */
    private suspend fun isFresh(): Boolean {
        val last = try {
            lastSyncAt()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        return Duration.between(last, clock.instant()) < timings.syncIfOlderThan
    }

    /** Whether the posts are current after this stage; see [PrintOutcome.synced]. */
    private suspend fun sync(job: PrintJob, fresh: Boolean, onStage: (PrintStage) -> Unit): Boolean = when (job) {
        is PrintJob.Due -> {
            if ((fresh && !job.forceSync) || !isOnline()) {
                fresh
            } else {
                onStage(PrintStage.Syncing)
                reached(guarded(timings.syncTimeout) { feedSyncer.syncAll() }) || fresh
            }
        }
        is PrintJob.Extra -> {
            onStage(PrintStage.Syncing)
            // An imported list has not been read at all yet. A source that does not answer in
            // time is printed from whatever it has stored.
            val reports = guarded(timings.extraSyncTimeout) {
                coroutineScope { job.feedIds.map { id -> async { feedSyncer.syncFeed(id) } }.awaitAll() }
            }
            reports?.any { reached(it) } == true
        }
    }

    /** Runs [block] within [limit]; null when it ran over or failed. */
    private suspend fun <T> guarded(limit: Duration, block: suspend () -> T): T? = try {
        withTimeoutOrNull(limit.toMillis()) { block() }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun reached(report: SyncReport?): Boolean =
        report != null && (report.feedsChecked == 0 || report.feedsFailed < report.feedsChecked)

    private suspend fun rate(job: PrintJob, onStage: (PrintStage) -> Unit): Int = try {
        val ids = when (job) {
            is PrintJob.Due -> publisher.shortlist(config.interestShortlist)
            is PrintJob.Extra -> publisher.extraShortlist(job.feedIds, config.interestShortlist)
        }
        if (ids.isEmpty()) 0 else rating.rate(ids, timings.ratingBudget) { done, total -> onStage(PrintStage.Rating(done, total)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A failed rating leaves its posts unrated; they score as if there were no model.
        0
    }

    private suspend fun todaysExtraExists(): Boolean {
        val today = clock.instant().atZone(zone()).toLocalDate()
        return editions.observeReleasedEditions().first().any { it.kind == EditionKind.EXTRA && it.date == today }
    }

    /** The edition a build was for, and the releases it found nothing to print for. */
    private class Built(val editionId: Long?, val nothingNewFor: List<Instant> = emptyList())

    private suspend fun build(job: PrintJob): Built = when (job) {
        is PrintJob.Extra -> Built(publisher.publishExtraEdition(job.feedIds))
        is PrintJob.Due -> {
            // The current edition: the morning one for the latest reset and, when it is switched
            // on and its time has passed, the evening one. What the publisher returns is the
            // newest edition on the stand of any kind, which may be an older paper or an Extra,
            // so the stored editions say which of the due ones now exist.
            publisher.ensureCurrentEdition()
            val current = settings.settings.first()
            val due = DueEditions.due(clock.instant(), current.schedule, zone())
            val released = editions.observeReleasedEditions().first()
            Built(
                editionId = due.firstNotNullOfOrNull { DueEditions.editionFor(it, released) }?.id,
                nothingNewFor = DueEditions.unprinted(due, released).map { it.at },
            )
        }
    }
}

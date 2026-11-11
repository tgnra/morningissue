package net.tgnra.morningissue.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.MorningIssueApp
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ContentBlocks

/**
 * Once, after the upgrade that taught the syncer to fetch teaser posts' pages: goes through
 * the posts already stored and fetches the page of each one whose feed body is only a teaser
 * ([DefaultFullTextFetcher.isTeaser]), as a sync now does for new posts. Only subscribed
 * feeds' posts from the last [RankingConfig.maxAgeDays] days, the ones an edition can still
 * print; older ones are fetched when opened, as before.
 */
class TeaserBackfill(
    database: MorningIssueDatabase,
    private val fullText: FullTextFetcher,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val articleDao = database.articleDao()

    /** Returns how many pages were fetched and stored. */
    suspend fun run(): Int {
        val since = clock.instant().minus(Duration.ofDays(config.maxAgeDays.toLong()))
        val permits = Semaphore(PARALLEL)
        var stored = 0
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = articleDao.feedBodiesSince(since, afterId, BATCH)
            if (batch.isEmpty()) return stored
            afterId = batch.last().articleId
            val teasers = withContext(dispatcher) {
                batch.filter { DefaultFullTextFetcher.isTeaser(ContentBlocks.decode(it.blocksJson)) }.map { it.articleId }
            }
            stored += coroutineScope {
                teasers.map { id -> async { permits.withPermit { fullText.ensureFullText(id) } } }.awaitAll()
            }.count { it is FullTextResult.Stored }
        }
    }

    private companion object {
        const val BATCH = 200
        const val PARALLEL = 4
    }
}

/** Runs [TeaserBackfill] once, when there is a connection; see [TeaserBackfillWorker.ensureDone]. */
class TeaserBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? MorningIssueApp)?.container ?: return Result.failure()
        val backfill = TeaserBackfill(container.core.database, container.fullTextFetcher, container.clock)
        backfill.run()
        // A page that could not be had is fetched when the post is opened; the pass is not repeated.
        prefs(applicationContext).edit().putBoolean(KEY_DONE, true).apply()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "teaser-backfill"
        private const val PREFS = "upgrades"
        private const val KEY_DONE = "teaserBackfillDone"

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /**
         * Enqueues the pass unless it has finished before. Called on every process start;
         * KEEP leaves a pass that is waiting for a connection, or running, alone.
         */
        fun ensureDone(context: Context) {
            if (prefs(context).getBoolean(KEY_DONE, false)) return
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request())
        }

        internal fun request(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TeaserBackfillWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
    }
}

package net.tgnra.morningissue.sync

import androidx.room.withTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.Cadence
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.HealthFlag

/**
 * The real [FeedSyncer]. Fetches feeds a few at a time, stores posts it has not seen with
 * first-seen = now, and keeps each feed's validators, health row and cadence up to date.
 */
class DefaultFeedSyncer(
    private val database: MorningIssueDatabase,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    /** A full sync runs here so that it survives the caller that started it. */
    private val scope: CoroutineScope,
    private val config: RankingConfig = RankingConfig.Default,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FeedSyncer {
    private val feedDao = database.feedDao()
    private val articleDao = database.articleDao()
    private val healthDao = database.feedHealthDao()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val lock = Any()
    private var running: Deferred<SyncReport>? = null

    /** One fetch per feed at a time, so a `syncFeed` during a full sync cannot race it. */
    private val feedLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun syncAll(): SyncReport {
        val run = synchronized(lock) {
            running?.takeIf { it.isActive } ?: scope.async { runAll() }.also { running = it }
        }
        return run.await()
    }

    override suspend fun syncFeed(feedId: Long): SyncReport {
        val feed = feedDao.getById(feedId) ?: return SyncReport()
        val ownsStatus = _status.compareAndSet(SyncStatus.Idle, SyncStatus.Running(0, 1))
        try {
            return report(listOf(syncGuarded(feed)))
        } finally {
            if (ownsStatus) _status.compareAndSet(SyncStatus.Running(0, 1), SyncStatus.Idle)
        }
    }

    private suspend fun runAll(): SyncReport {
        try {
            val feeds = feedDao.getAll()
            _status.value = SyncStatus.Running(0, feeds.size)
            val done = AtomicInteger()
            val permits = Semaphore(parallelism)
            val outcomes = coroutineScope {
                feeds.map { feed ->
                    async {
                        permits.withPermit { syncGuarded(feed) }.also {
                            _status.value = SyncStatus.Running(done.incrementAndGet(), feeds.size)
                        }
                    }
                }.awaitAll()
            }
            return report(outcomes)
        } finally {
            _status.value = SyncStatus.Idle
        }
    }

    private fun report(outcomes: List<Outcome>) = SyncReport(
        feedsChecked = outcomes.size,
        feedsFailed = outcomes.count { it.failed },
        newArticles = outcomes.sumOf { it.newArticles },
    )

    private class Outcome(val newArticles: Int = 0, val failed: Boolean = false)

    /** Never throws except on cancellation: any failure is recorded against the feed. */
    private suspend fun syncGuarded(feed: FeedEntity): Outcome =
        feedLocks.getOrPut(feed.id) { Mutex() }.withLock {
            try {
                // Re-read: the row may have changed while this feed waited its turn.
                val current = feedDao.getById(feed.id) ?: return@withLock Outcome()
                syncOne(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { healthDao.recordError(feed.id, clock.instant(), null) }
                Outcome(failed = true)
            }
        }

    private suspend fun syncOne(feed: FeedEntity): Outcome {
        val now = clock.instant()
        return when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
            is FetchResult.NetworkError -> {
                healthDao.recordError(feed.id, now, null)
                Outcome(failed = true)
            }
            is FetchResult.HttpError -> {
                healthDao.recordError(feed.id, now, result.status)
                Outcome(failed = true)
            }
            is FetchResult.NotModified -> {
                val moved = applyPermanentRedirect(feed, result.permanentUrl)
                // updateUrl drops the validators; they still describe the unchanged document.
                feedDao.updateSyncState(feed.id, feed.etag, feed.lastModified, now)
                healthDao.recordSuccess(feed.id, if (moved) 301 else 304, null)
                // No new posts, but the 90-day window has moved on.
                updateCadence(feed.id, now)
                Outcome()
            }
            is FetchResult.Success -> {
                val parsed = try {
                    withContext(parseDispatcher) { FeedParser.parse(result.text(), result.finalUrl, now) }
                } catch (e: FeedParseException) {
                    // The server answered, but not with a feed.
                    healthDao.recordError(feed.id, now, result.status)
                    return Outcome(failed = true)
                }
                val added = store(feed, parsed, now)
                val moved = applyPermanentRedirect(feed, result.permanentUrl)
                feedDao.updateSyncState(feed.id, result.etag, result.lastModified, now)
                feedDao.updateSiteInfo(feed.id, parsed.siteUrl, parsed.iconUrl)
                // A feed added without a title was given its host name; use the real one.
                if (parsed.title != null && feed.title == FeedUrls.hostOf(feed.url)) feedDao.updateTitle(feed.id, parsed.title)
                healthDao.recordSuccess(feed.id, if (moved) 301 else result.status, articleDao.latestPublishedAt(feed.id))
                updateCadence(feed.id, now)
                Outcome(newArticles = added)
            }
        }
    }

    /** Stores the posts the feed has not had before and returns how many there were. */
    private suspend fun store(feed: FeedEntity, parsed: ParsedFeed, now: Instant): Int {
        val fresh = parsed.items.filter { articleDao.getByGuid(feed.id, it.guid) == null }
        if (fresh.isEmpty()) return 0
        // HTML conversion is the slow part; do it outside the transaction.
        val rows = withContext(parseDispatcher) {
            fresh.map { item ->
                val content = item.content
                val article = ArticleEntity(
                    feedId = feed.id,
                    guid = item.guid,
                    url = item.url,
                    title = item.title,
                    summary = content.summary,
                    imageUrl = content.imageUrl,
                    author = item.author,
                    // A post cannot have been published after it was seen; future dates would
                    // also pin a post to the top of every date-ordered list.
                    publishedAt = minOf(item.publishedAt, now),
                    firstSeenAt = now,
                    wordCount = content.wordCount,
                    contentHash = content.contentHash,
                )
                Triple(article, content.blocks.takeIf { it.isNotEmpty() }?.let(ContentBlocks::encode), content.plainText)
            }
        }
        return database.withTransaction {
            rows.count { (article, blocksJson, plainText) ->
                articleDao.insertNew(article, blocksJson, plainText.takeIf { blocksJson != null }) != null
            }
        }
    }

    /**
     * v2's "Moved" rule: on a permanent redirect the feed's address is updated. The Moved flag
     * is raised for the Subscriptions desk only when the address really differs, not for a
     * mere http-to-https or www change. Returns whether the address was changed.
     */
    private suspend fun applyPermanentRedirect(feed: FeedEntity, permanentUrl: String?): Boolean {
        if (permanentUrl == null || permanentUrl == feed.url) return false
        // Two subscriptions can redirect to one address; `feed.url` is unique, so leave this one be.
        if (feedDao.getByUrl(permanentUrl) != null) return false
        feedDao.updateUrl(feed.id, permanentUrl)
        if (FeedUrls.normalize(permanentUrl) != FeedUrls.normalize(feed.url)) healthDao.setFlag(feed.id, HealthFlag.MOVED)
        return true
    }

    private suspend fun updateCadence(feedId: Long, now: Instant) {
        val windowStart = now.minus(Duration.ofDays(config.cadenceWindowDays.toLong()))
        // "The last 30 timestamps or 90 days, whichever covers more."
        val times = if (articleDao.countForFeedSince(feedId, windowStart) > config.cadenceSampleSize) {
            articleDao.publishTimesSince(feedId, windowStart)
        } else {
            articleDao.recentPublishTimes(feedId, config.cadenceSampleSize)
        }
        val stats = Cadence.compute(times, now, config)
        feedDao.updateCadence(feedId, stats.medianIntervalHours, stats.burstiness, stats.tier)
    }

    companion object {
        /** Enough to finish a hundred feeds quickly without hammering a phone's radio. */
        const val DEFAULT_PARALLELISM = 6
    }
}

package net.tgnra.morningissue.server.feeds

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.engine.Cadence
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.FeedDao
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SignalDao
import net.tgnra.morningissue.server.db.StoredPostRow
import net.tgnra.morningissue.server.service.FeedRefresher
import net.tgnra.morningissue.server.service.RefreshReport
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedParseException
import net.tgnra.morningissue.sync.FeedParser
import net.tgnra.morningissue.sync.FetchResult
import net.tgnra.morningissue.sync.FullTextExtractor
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.ParsedFeed
import net.tgnra.morningissue.sync.ParsedItem
import org.slf4j.LoggerFactory

/**
 * The server's [FeedRefresher] (section 3), ported from the app's `DefaultFeedSyncer`: fetches
 * shared feeds into the global tables, stores posts it has not seen with `firstSeenAt = now`
 * (and their categories and traits), fetches the pages of new teaser posts, and keeps each
 * feed's validators, `feed_status` and cadence up to date.
 *
 * - **One fetch per feed at a time, shared.** A fetch in flight is a [Deferred] in a global map;
 *   a second caller asking for the same feed awaits it instead of fetching again, whatever its
 *   `maxAge`. Fetches run in [scope], so a caller that stops waiting (its deadline) does not
 *   cancel a fetch another caller shares.
 * - **`maxAge`** is read from `feed.last_attempt_at` at call time: a feed tried (successfully
 *   or not) within `maxAge` is skipped, so a broken feed is not hammered. `Duration.ZERO`
 *   fetches every feed.
 * - **Only feeds with a SUBSCRIBED subscriber are fetched**; a feed only discovered is never.
 * - At most [parallelism] feeds are fetched at once across all callers.
 * - After a batch with new posts, [afterBatch] runs (signals upkeep: traits, then embeddings).
 */
class DefaultFeedRefresher(
    private val database: ServerDatabase,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    /** Fetches run here, so that they outlive a caller that stops waiting. */
    private val scope: CoroutineScope,
    private val config: RankingConfig = RankingConfig.Default,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Fetches the page of a new post whose feed body is only a teaser ([FullTextExtractor.isTeaser]). */
    private val fullText: FullTextFetcher? = null,
    /** Runs after a refresh that stored new posts; a failure is logged, never thrown. */
    private val afterBatch: suspend () -> Unit = {},
) : FeedRefresher {
    private val feedDao = database.feedDao()
    private val articleDao = database.articleDao()
    private val healthDao = database.healthDao()
    private val signalDao = database.signalDao()
    private val subscriptionDao = database.subscriptionDao()

    private val permits = Semaphore(parallelism)
    private val inFlight = ConcurrentHashMap<Long, Deferred<Outcome>>()

    /** Per user, whether the latest refresh that covered all their subscribed feeds reached them. */
    private val reached = ConcurrentHashMap<Long, Boolean>()

    /** What one feed's fetch did. */
    class Outcome(val newArticles: Int = 0, val answered: Boolean = false)

    override suspend fun refresh(feedIds: Collection<Long>, maxAge: Duration, deadline: Instant?): RefreshReport {
        val requested = feedIds.toSet()
        val now = clock.instant()
        val notTriedSince = if (maxAge.isZero || maxAge.isNegative) null else now.minus(maxAge)
        // A fetch already in flight is joined whatever maxAge says: its posts are about to land.
        val joined = requested.mapNotNull { id -> inFlight[id]?.let { id to it } }.toMap()
        val due = (requested - joined.keys).toList().chunked(FeedDao.MAX_IDS).flatMap { feedDao.fetchable(it, notTriedSince) }
        val fetches = joined.values + due.map(::fetch)

        val remaining = deadline?.let { Duration.between(clock.instant(), it) }
        val finished = if (remaining == null) {
            fetches.awaitAll()
            true
        } else {
            withTimeoutOrNull(remaining.toMillis().coerceAtLeast(1)) { fetches.awaitAll() } != null
        }
        val outcomes = fetches.filter { it.isCompleted && !it.isCancelled }.map { it.await() }
        val report = RefreshReport(
            attempted = fetches.size,
            answered = outcomes.count { it.answered },
            newArticles = outcomes.sumOf { it.newArticles },
            finishedInTime = finished,
        )
        if (report.newArticles > 0) {
            try {
                afterBatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Signals upkeep after a refresh failed", e)
            }
        }
        recordCoverage(requested, report)
        return report
    }

    override fun reachedFeedsOf(userId: Long): Boolean = reached[userId] ?: false

    /** Users all of whose subscribed feeds were in [requested] take this report as their latest. */
    private suspend fun recordCoverage(requested: Set<Long>, report: RefreshReport) {
        val byUser = subscriptionDao.subscribedPairs().groupBy({ it.userId }) { it.feedId }
        for ((userId, feeds) in byUser) {
            if (requested.containsAll(feeds)) reached[userId] = report.reachedFeeds
        }
    }

    /** The shared fetch of [feedId]: the one in flight, or a new one. */
    private fun fetch(feedId: Long): Deferred<Outcome> {
        while (true) {
            inFlight[feedId]?.let { return it }
            val fresh = scope.async(start = CoroutineStart.LAZY) { permits.withPermit { fetchGuarded(feedId) } }
            if (inFlight.putIfAbsent(feedId, fresh) == null) {
                fresh.invokeOnCompletion { inFlight.remove(feedId, fresh) }
                fresh.start()
                return fresh
            }
            fresh.cancel()
        }
    }

    /** Never throws except on cancellation: any failure is recorded against the feed. */
    private suspend fun fetchGuarded(feedId: Long): Outcome {
        val feed = feedDao.getById(feedId) ?: return Outcome()
        return try {
            fetchOne(feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Refreshing feed {} failed", feed.url, e)
            runCatching { failed(feed.id, clock.instant(), null) }
            Outcome()
        }
    }

    private suspend fun failed(feedId: Long, at: Instant, httpStatus: Int?): Outcome {
        healthDao.recordError(feedId, at, httpStatus)
        feedDao.recordAttempt(feedId, at)
        return Outcome()
    }

    private suspend fun fetchOne(feed: FeedEntity): Outcome {
        val now = clock.instant()
        return when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
            is FetchResult.NetworkError -> failed(feed.id, now, null)
            is FetchResult.HttpError -> failed(feed.id, now, result.status)
            is FetchResult.NotModified -> {
                val moved = applyPermanentRedirect(feed, result.permanentUrl, now)
                // updateUrl drops the validators; they still describe the unchanged document.
                feedDao.recordSuccess(feed.id, feed.etag, feed.lastModified, now)
                healthDao.recordSuccess(feed.id, if (moved) 301 else 304, null)
                // No new posts, but the 90-day window has moved on.
                updateCadence(feed.id, now)
                Outcome(answered = true)
            }
            is FetchResult.Success -> {
                val parsed = try {
                    withContext(parseDispatcher) { FeedParser.parse(result.text(), result.finalUrl, now) }
                } catch (e: FeedParseException) {
                    // The server answered, but not with a feed.
                    return failed(feed.id, now, result.status)
                }
                val added = store(feed, parsed, now)
                fetchTeasers(added.teasers)
                val moved = applyPermanentRedirect(feed, result.permanentUrl, now)
                feedDao.recordSuccess(feed.id, result.etag, result.lastModified, now)
                feedDao.updateSiteInfo(feed.id, parsed.siteUrl, parsed.iconUrl)
                // `feed.title` is the document's; each user's own name is their title_override.
                parsed.title?.trim()?.takeIf { it.isNotEmpty() && it != feed.title }?.let { feedDao.updateTitle(feed.id, it) }
                healthDao.recordSuccess(feed.id, if (moved) 301 else result.status, articleDao.latestPublishedAt(feed.id))
                updateCadence(feed.id, now)
                Outcome(newArticles = added.count, answered = true)
            }
        }
    }

    /**
     * Fetches the pages of new teaser posts, newest first and at most [MAX_TEASER_FETCHES] per
     * feed per refresh, [TEASER_PARALLEL] at a time. A failure leaves the teaser.
     */
    private suspend fun fetchTeasers(teasers: List<Teaser>) {
        val fetcher = fullText ?: return
        if (teasers.isEmpty()) return
        val permits = Semaphore(TEASER_PARALLEL)
        coroutineScope {
            for (teaser in teasers.sortedByDescending { it.publishedAt }.take(MAX_TEASER_FETCHES)) {
                async { permits.withPermit { fetcher.ensureFullText(teaser.articleId) } }
            }
        }
    }

    private class Teaser(val articleId: Long, val publishedAt: Instant)

    private class Stored(val count: Int, val teasers: List<Teaser>)

    /**
     * Stores the posts the feed has not had before, with their categories and traits; posts
     * already held are completed where something is missing (categories, traits under older
     * rules, an enclosure only the feed knows).
     */
    private suspend fun store(feed: FeedEntity, parsed: ParsedFeed, now: Instant): Stored {
        val stored = parsed.items.map { it.guid }.chunked(SignalDao.MAX_IDS)
            .flatMap { signalDao.storedPosts(feed.id, it) }.associateBy { it.guid }
        val fresh = parsed.items.filter { it.guid !in stored }.distinctBy { it.guid }
        val incomplete = parsed.items.mapNotNull { item -> stored[item.guid]?.let { completion(item, it) } }
        if (fresh.isEmpty() && incomplete.isEmpty()) return Stored(0, emptyList())
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
                    // A post cannot have been published after it was seen.
                    publishedAt = minOf(item.publishedAt, now),
                    firstSeenAt = now,
                    wordCount = content.wordCount,
                    feedWordCount = content.wordCount,
                    contentHash = content.contentHash,
                )
                NewPost(
                    item, article, content.blocks.takeIf { it.isNotEmpty() }?.let(ContentBlocks::encode),
                    teaser = fullText != null && item.url != null && FullTextExtractor.isTeaser(content.blocks),
                )
            }
        }
        val traits = withContext(parseDispatcher) {
            incomplete.filter { it.traits }.associate { it.articleId to it.item.content.traits }
        }
        val teasers = mutableListOf<Teaser>()
        val count = database.withWriteTransaction {
            for (post in incomplete) {
                if (post.categories) signalDao.addCategories(post.articleId, post.item.categories)
                traits[post.articleId]?.let { signalDao.upsertTraits(SignalDao.traitsRow(post.articleId, it)) }
                if (post.enclosure) signalDao.updateEnclosure(post.articleId, post.item.enclosure)
            }
            rows.count { post ->
                val content = post.item.content
                val id = articleDao.insertNew(post.article, post.blocksJson, content.plainText.takeIf { post.blocksJson != null })
                    ?: return@count false
                signalDao.addCategories(id, post.item.categories)
                signalDao.upsertTraits(SignalDao.traitsRow(id, content.traits))
                if (post.teaser) teasers += Teaser(id, post.article.publishedAt)
                true
            }
        }
        return Stored(count, teasers)
    }

    private class NewPost(val item: ParsedItem, val article: ArticleEntity, val blocksJson: String?, val teaser: Boolean)

    private class Completion(
        val item: ParsedItem,
        val articleId: Long,
        val categories: Boolean,
        val traits: Boolean,
        val enclosure: Boolean,
    )

    private fun completion(item: ParsedItem, stored: StoredPostRow): Completion? {
        val categories = !stored.hasCategories && item.categories.isNotEmpty()
        val traits = stored.traitsVersion != ArticleTraitRules.VERSION
        val enclosure = !traits && stored.enclosure == EnclosureKind.NONE && item.enclosure != EnclosureKind.NONE
        return if (categories || traits || enclosure) Completion(item, stored.articleId, categories, traits, enclosure) else null
    }

    /**
     * On a permanent redirect the shared feed's address is updated, unless another feed row has
     * it. When it really differs (not a mere http-to-https or www change) the move is recorded
     * in `feed_status`, and every subscriber sees Moved until they acknowledge it. Returns
     * whether the address was changed.
     */
    private suspend fun applyPermanentRedirect(feed: FeedEntity, permanentUrl: String?, now: Instant): Boolean {
        if (permanentUrl == null || permanentUrl == feed.url) return false
        return relocate(database, feed, permanentUrl, now)
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
        private val log = LoggerFactory.getLogger(DefaultFeedRefresher::class.java)

        /** `refresh.concurrency`'s default. */
        const val DEFAULT_PARALLELISM = 6

        /** Teaser pages fetched per feed per refresh, and at once within a feed. */
        const val MAX_TEASER_FETCHES = 10
        const val TEASER_PARALLEL = 2

        /**
         * Moves the shared [feed] to [url] (a permanent redirect or a repair), unless another
         * feed row already has that address or its URL key. A real change of address (more than
         * scheme or "www.") is recorded as a move. Returns whether the address changed.
         */
        internal suspend fun relocate(database: ServerDatabase, feed: FeedEntity, url: String, now: Instant): Boolean {
            val feedDao = database.feedDao()
            val key = FeedUrls.normalize(url)
            val taken = feedDao.getByUrl(url)?.takeIf { it.id != feed.id } ?: feedDao.getByUrlKey(key)?.takeIf { it.id != feed.id }
            if (taken != null) return false
            database.withWriteTransaction {
                feedDao.updateUrl(feed.id, url, key)
                if (key != FeedUrls.normalize(feed.url)) database.healthDao().recordMove(feed.id, feed.url, now)
            }
            return true
        }
    }
}

package net.tgnra.morningissue.server.editions

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.ArticleEvidence
import net.tgnra.morningissue.engine.LearnedAffinities
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.server.db.ServerDatabase

/**
 * Recomputes one user's `subscription.affinity` and `tag.affinity` with the pure [Affinity]
 * rules: the app's `AffinityLearner` with `userId` threaded through.
 *
 * Nothing is kept as a running sum: every pass derives the values from the user's
 * `edition_item` runs and `engagement` rows of the last `affinityWindowDays`. It runs when an
 * edition is built, after merged events change opened, read, saved or Liked state (debounced,
 * `sync/Relearner`), and after an import.
 *
 * None of the functions opens a transaction; callers run them inside their own.
 */
class AffinityLearner(
    database: ServerDatabase,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val learningDao = database.learningDao()
    private val articleDao = database.editionArticleDao()
    private val editionDao = database.editionDao()
    private val affinity = Affinity(config)

    /** As the app's: learns as of [at], [pendingEditionIds] not yet superseded. */
    suspend fun learn(userId: Long, at: Instant, pendingEditionIds: Set<Long>): LearnedAffinities =
        learn(userId, evidence(userId, at, pendingEditionIds), at)

    /** The first instant whose runs and engagement are read when learning as of [at]. */
    fun windowStart(at: Instant): Instant = at.minus(Duration.ofDays(config.affinityWindowDays))

    /**
     * What the user did with each post over the learning window, by article ID: the one record
     * both feed affinity and within-feed ranking learn from. See the app's `AffinityLearner`
     * for the rules (pending editions' untouched stories and unseen runs are left out).
     */
    suspend fun evidence(userId: Long, at: Instant, pendingEditionIds: Set<Long>): Map<Long, ArticleEvidence> {
        val since = windowStart(at)
        val evidence = HashMap<Long, ArticleEvidence>()
        for (run in learningDao.runsSince(userId, since, config.pageSeenMinMillis)) {
            val touched = run.openedAt != null || run.readAt != null || run.savedAt != null || run.likedAt != null
            if (!touched && (run.editionId in pendingEditionIds || !run.seen)) continue
            val known = evidence[run.articleId]
                ?: ArticleEvidence(run.feedId, emptyList(), run.openedAt, run.readAt, run.savedAt, run.likedAt)
            evidence[run.articleId] = if (run.seen) known.copy(ranAt = known.ranAt + run.ranAt) else known
        }
        for (row in learningDao.engagedSince(userId, since)) {
            if (row.articleId !in evidence) {
                evidence[row.articleId] = ArticleEvidence(row.feedId, emptyList(), row.openedAt, row.readAt, row.savedAt, row.likedAt)
            }
        }
        return evidence
    }

    /** Learns as of [at] from [evidence] without storing anything. */
    suspend fun learn(userId: Long, evidence: Map<Long, ArticleEvidence>, at: Instant): LearnedAffinities {
        val feedTags = HashMap<Long, MutableList<Long>>()
        val intervals = HashMap<Long, Double?>()
        for (feed in articleDao.subscribedFeeds(userId)) {
            feedTags[feed.feedId] = ArrayList()
            intervals[feed.feedId] = feed.medianIntervalHrs
        }
        learningDao.tagLinks(userId).forEach { feedTags[it.feedId]?.add(it.tagId) }
        return affinity.learn(evidence.values, feedTags, at, tagIds = learningDao.tags(userId).map { it.id }, feedIntervalHours = intervals)
    }

    /** Writes the values that changed. */
    suspend fun store(userId: Long, learned: LearnedAffinities) {
        for (feed in articleDao.subscribedFeeds(userId)) {
            val value = learned.feeds[feed.feedId] ?: continue
            if (abs(value - feed.affinity) > EPSILON) learningDao.updateFeedAffinity(userId, feed.feedId, value)
        }
        for (tag in learningDao.tags(userId)) {
            val value = learned.tags[tag.id] ?: continue
            if (abs(value - tag.affinity) > EPSILON) learningDao.updateTagAffinity(userId, tag.id, value)
        }
    }

    /**
     * Learns and stores as of [at], between builds: the newest scheduled edition and any Extra
     * printed since have not been superseded, so their untouched stories do not count yet.
     */
    suspend fun refresh(userId: Long, at: Instant) =
        store(userId, learn(userId, at, editionDao.getPending(userId).mapTo(HashSet()) { it.id }))

    private companion object {
        const val EPSILON = 1e-9
    }
}

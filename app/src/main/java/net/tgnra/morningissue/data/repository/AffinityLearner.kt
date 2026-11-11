package net.tgnra.morningissue.data.repository

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.ArticleEvidence
import net.tgnra.morningissue.engine.LearnedAffinities
import net.tgnra.morningissue.engine.RankingConfig

/**
 * Recomputes `feed.affinity` and `tag.affinity` with the pure [Affinity] rules.
 *
 * Nothing is kept as a running sum: every pass derives the values from the `edition_item` runs
 * and `engagement` rows of the last `affinityWindowDays`, so undoing a Like or deleting an
 * edition cannot leave the stored numbers out of step. A pass is a handful of indexed reads
 * (a few thousand short rows at most), cheap enough to run:
 *
 * - when an edition is built (`RoomEditionPublisher`), so the scorer ranks with current values;
 * - when a story's opened, read, saved or Liked state changes (`RoomArticleRepository`);
 * - when tags or subscriptions change (`RoomFeedRepository`), since a feed borrows from its tags.
 *
 * None of the functions opens a transaction; callers run them inside their own.
 */
class AffinityLearner(
    database: MorningIssueDatabase,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()
    private val editionDao = database.editionDao()
    private val engagementDao = database.engagementDao()
    private val affinity = Affinity(config)

    /**
     * Learns as of [at] without storing anything.
     *
     * @param pendingEditionId the edition that has not been superseded yet: the stories in it
     *   that the reader has not touched do not count as ignored. Null when every stored
     *   edition counts, which is the case while the next one is being built.
     */
    suspend fun learn(at: Instant, pendingEditionId: Long?): LearnedAffinities = learn(at, setOfNotNull(pendingEditionId))

    /** As above, with every edition that has not been superseded yet (see `EditionDao.getPending`). */
    suspend fun learn(at: Instant, pendingEditionIds: Set<Long>): LearnedAffinities = learn(evidence(at, pendingEditionIds), at)

    /** The first instant whose runs and engagement are read when learning as of [at]. */
    fun windowStart(at: Instant): Instant = at.minus(Duration.ofDays(config.affinityWindowDays))

    /**
     * What the reader did with each post over the learning window, by article ID: the one
     * record both feed affinity and within-feed ranking (`WithinFeedLearner`) learn from, so
     * the two cannot disagree about what counts.
     *
     * - A post is in it when it was printed (not merely indexed) in an edition released since
     *   [windowStart], or the reader opened, read, saved or Liked it since then (from a source
     *   page, search or an edition older than the window).
     * - An untouched story of an edition in [pendingEditionIds] is left out: that edition has
     *   not been superseded, so the story does not count as ignored yet.
     */
    suspend fun evidence(at: Instant, pendingEditionIds: Set<Long>): Map<Long, ArticleEvidence> {
        val since = windowStart(at)
        val evidence = HashMap<Long, ArticleEvidence>()
        for (run in editionDao.getRunsSince(since)) {
            val touched = run.openedAt != null || run.readAt != null || run.savedAt != null || run.likedAt != null
            if (run.editionId in pendingEditionIds && !touched) continue
            val known = evidence[run.articleId]
                ?: ArticleEvidence(run.feedId, emptyList(), run.openedAt, run.readAt, run.savedAt, run.likedAt)
            evidence[run.articleId] = known.copy(ranAt = known.ranAt + run.ranAt)
        }
        for (row in engagementDao.engagedSince(since)) {
            if (row.articleId !in evidence) {
                evidence[row.articleId] =
                    ArticleEvidence(row.feedId, emptyList(), row.openedAt, row.readAt, row.savedAt, row.likedAt)
            }
        }
        return evidence
    }

    /** Learns as of [at] from [evidence] (see the function above) without storing anything. */
    suspend fun learn(evidence: Map<Long, ArticleEvidence>, at: Instant): LearnedAffinities {
        val feedTags = HashMap<Long, MutableList<Long>>()
        feedDao.getAll().forEach { feedTags[it.id] = ArrayList() }
        tagDao.getAllLinks().forEach { feedTags[it.feedId]?.add(it.tagId) }
        return affinity.learn(evidence.values, feedTags, at, tagIds = tagDao.getAll().map { it.id })
    }

    /** Writes the values that changed. */
    suspend fun store(learned: LearnedAffinities) {
        for (feed in feedDao.getAll()) {
            val value = learned.feeds[feed.id] ?: continue
            if (abs(value - feed.affinity) > EPSILON) feedDao.updateAffinity(feed.id, value)
        }
        for (tag in tagDao.getAll()) {
            val value = learned.tags[tag.id] ?: continue
            if (abs(value - tag.affinity) > EPSILON) tagDao.updateAffinity(tag.id, value)
        }
    }

    /**
     * Learns and stores as of [at], between builds: the newest scheduled edition and any Extra
     * edition printed since have not been superseded, so their untouched stories do not count yet.
     */
    suspend fun refresh(at: Instant) = store(learn(at, editionDao.getPending().mapTo(HashSet()) { it.id }))

    private companion object {
        const val EPSILON = 1e-9
    }
}

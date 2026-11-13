package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Query
import java.time.Instant

/**
 * The reads affinity and within-feed learning need, per user, and the writes of what they
 * learn (`subscription.affinity`, `tag.affinity`). Owned by W-B; the tables are W-A's.
 */
@Dao
interface LearningDao {
    /**
     * Printed (non-index) stories of subscribed feeds in the user's editions released since
     * [since], with what the user did and whether the run was seen (its page on screen for at
     * least [seenMinMillis] in all, or an edition from before page views).
     */
    @Query(
        "SELECT a.feedId AS feedId, i.articleId AS articleId, i.editionId AS editionId, e.releaseAt AS ranAt, " +
            "g.openedAt AS openedAt, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
            "CASE WHEN ${EditionDao.SEEN} THEN 1 ELSE 0 END AS seen " +
            "FROM edition_item i JOIN edition e ON e.id = i.editionId JOIN article a ON a.id = i.articleId " +
            "JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "LEFT JOIN engagement g ON g.articleId = i.articleId AND g.user_id = :userId " +
            "WHERE e.user_id = :userId AND i.kind != 'INDEX' AND e.releaseAt >= :since AND s.status = 'SUBSCRIBED'",
    )
    suspend fun runsSince(userId: Long, since: Instant, seenMinMillis: Long): List<UserRunRow>

    /** Subscribed feeds' articles the user opened, read, saved or Liked at or after [since]. */
    @Query(
        "SELECT a.feedId AS feedId, g.articleId AS articleId, g.openedAt AS openedAt, g.readAt AS readAt, " +
            "g.savedAt AS savedAt, g.likedAt AS likedAt FROM engagement g JOIN article a ON a.id = g.articleId " +
            "JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "WHERE g.user_id = :userId AND s.status = 'SUBSCRIBED' " +
            "AND (g.openedAt >= :since OR g.readAt >= :since OR g.savedAt >= :since OR g.likedAt >= :since)",
    )
    suspend fun engagedSince(userId: Long, since: Instant): List<UserEngagedRow>

    @Query(
        "SELECT st.feed_id AS feedId, t.id AS tagId, t.name AS name, t.affinity AS affinity " +
            "FROM subscription_tag st JOIN tag t ON t.id = st.tag_id WHERE st.user_id = :userId AND t.user_id = :userId",
    )
    suspend fun tagLinks(userId: Long): List<UserTagLinkRow>

    @Query("SELECT id, name, affinity FROM tag WHERE user_id = :userId ORDER BY name COLLATE NOCASE")
    suspend fun tags(userId: Long): List<UserTagRow>

    @Query("UPDATE subscription SET affinity = :affinity WHERE user_id = :userId AND feed_id = :feedId")
    suspend fun updateFeedAffinity(userId: Long, feedId: Long, affinity: Double)

    @Query("UPDATE tag SET affinity = :affinity WHERE user_id = :userId AND id = :tagId")
    suspend fun updateTagAffinity(userId: Long, tagId: Long, affinity: Double)

    // --- Signals for within-feed ranking (global tables) -----------------------------------

    /** Signals of [articleIds]; vectors of [model], or none when it is null. At most 500 IDs. */
    @Query("$SIGNAL_SELECT WHERE a.id IN (:articleIds)")
    suspend fun signalRows(articleIds: List<Long>, model: Int?): List<LearningSignalRow>

    @Query("SELECT * FROM article_category WHERE articleId IN (:articleIds) ORDER BY articleId, `key`")
    suspend fun categoriesOf(articleIds: List<Long>): List<ArticleCategoryEntity>

    /** Signals of every post of a subscribed feed with evidence for the user since [since]. */
    @Query(
        "$SIGNAL_SELECT WHERE a.id IN ($EVIDENCE_IDS) " +
            "AND a.feedId IN (SELECT feed_id FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED')",
    )
    suspend fun signalRowsWithEvidenceSince(userId: Long, since: Instant, model: Int?): List<LearningSignalRow>

    @Query("SELECT * FROM article_category WHERE articleId IN ($EVIDENCE_IDS) ORDER BY articleId, `key`")
    suspend fun categoriesWithEvidenceSince(userId: Long, since: Instant): List<ArticleCategoryEntity>

    companion object {
        /** Shared projection for [LearningSignalRow]; append a WHERE clause. */
        const val SIGNAL_SELECT =
            "SELECT a.id AS articleId, a.feedId AS feedId, a.feedWordCount AS feedWordCount, " +
                "t.articleId AS t_articleId, t.traitsVersion AS t_traitsVersion, t.linkCount AS t_linkCount, " +
                "t.imageCount AS t_imageCount, t.hasCode AS t_hasCode, t.enclosure AS t_enclosure, " +
                "t.isLinkPost AS t_isLinkPost, t.titlePrefix AS t_titlePrefix, v.vector AS vector " +
                "FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
                "LEFT JOIN article_embedding v ON v.articleId = a.id AND v.model = :model"

        /** The posts with evidence for `:userId` since `:since`: the rule of [runsSince] plus [engagedSince]. */
        const val EVIDENCE_IDS =
            "SELECT i.articleId FROM edition_item i JOIN edition d ON d.id = i.editionId " +
                "WHERE d.user_id = :userId AND i.kind != 'INDEX' AND d.releaseAt >= :since " +
                "UNION SELECT g.articleId FROM engagement g WHERE g.user_id = :userId " +
                "AND (g.openedAt >= :since OR g.readAt >= :since OR g.savedAt >= :since OR g.likedAt >= :since)"
    }
}

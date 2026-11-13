package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Query
import java.time.Instant

/**
 * The publisher's per-user reads of the global feed and article tables (W-A's), through the
 * user's `subscription`: candidates, held-over posts, feed profiles, the saved shelf and stored
 * bodies. Owned by W-B. Every query names the user.
 *
 * **Effective first-seen time** (`docs/server-design.md`, 2.3): a user sees a post as first
 * seen at `MAX(article.firstSeenAt, subscription.added_at)`, so a feed the server already held
 * offers its stored posts as new from the moment the user subscribed. [firstSeenBetween] reads
 * it as a union of two indexed ranges.
 */
@Dao
interface EditionArticleDao {
    /**
     * Subscribed feeds' posts the user first saw in `(after, upTo]`: the new posts of one
     * edition, oldest first. A post opened or saved from discovery belongs to a DISCOVERED
     * subscription and never enters the pool.
     */
    @Query(
        "$USER_ROW_SELECT WHERE s.status = 'SUBSCRIBED' AND a.firstSeenAt > :after AND a.firstSeenAt <= :upTo " +
            "AND a.firstSeenAt >= s.added_at " +
            "UNION ALL " +
            "$USER_ROW_SELECT WHERE s.status = 'SUBSCRIBED' AND s.added_at > :after AND s.added_at <= :upTo " +
            "AND a.firstSeenAt < s.added_at " +
            "ORDER BY seenAt, id",
    )
    suspend fun firstSeenBetween(userId: Long, after: Instant, upTo: Instant): List<UserArticleRow>

    /**
     * The held-over pool of one loved slow feed: its posts the user first saw at or before
     * [upTo] and has not touched (never opened, read, saved or Liked), newest first.
     */
    @Query(
        "$USER_ROW_SELECT WHERE a.feedId = :feedId AND s.status = 'SUBSCRIBED' " +
            "AND MAX(a.firstSeenAt, s.added_at) <= :upTo AND g.openedAt IS NULL " +
            "AND g.readAt IS NULL AND g.savedAt IS NULL AND g.likedAt IS NULL " +
            "ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit",
    )
    suspend fun heldOverForFeed(userId: Long, feedId: Long, upTo: Instant, limit: Int): List<UserArticleRow>

    /**
     * When [feedId] last posted, going by the posts the user had seen by [seenUpTo]; null when
     * they knew of none. Welcome back measures a feed's silence from here.
     */
    @Query(
        "SELECT a.publishedAt FROM article a JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "WHERE a.feedId = :feedId AND MAX(a.firstSeenAt, s.added_at) <= :seenUpTo ORDER BY a.publishedAt DESC LIMIT 1",
    )
    suspend fun lastKnownPostAt(userId: Long, feedId: Long, seenUpTo: Instant): Instant?

    /** Subscribed feeds with a post the user Liked or saved at or after [since]. */
    @Query(
        "SELECT DISTINCT a.feedId FROM engagement g JOIN article a ON a.id = g.articleId " +
            "JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "WHERE g.user_id = :userId AND s.status = 'SUBSCRIBED' AND (g.likedAt >= :since OR g.savedAt >= :since)",
    )
    suspend fun feedsLikedOrSavedSince(userId: Long, since: Instant): List<Long>

    /**
     * From your reading list: the post saved longest ago, from a feed the user does not
     * subscribe to, that they have not read and that has never run in that slot for them.
     */
    @Query(
        "$USER_ROW_SELECT WHERE s.status = 'DISCOVERED' AND g.savedAt IS NOT NULL AND g.readAt IS NULL " +
            "AND a.id NOT IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.kind = 'READING_LIST') " +
            "ORDER BY g.savedAt, a.id LIMIT 1",
    )
    suspend fun oldestUnreadSavedFromUnsubscribed(userId: Long): UserArticleRow?

    /** Every saved post, newest saved first, as the Saved shelf orders them. */
    @Query("$USER_ROW_SELECT WHERE g.savedAt IS NOT NULL ORDER BY g.savedAt DESC, a.id DESC")
    suspend fun saved(userId: Long): List<UserArticleRow>

    /** One article as the user sees it, whether or not they subscribe to its feed. */
    @Query("$USER_ROW_SELECT WHERE a.id = :articleId")
    suspend fun row(userId: Long, articleId: Long): UserArticleRow?

    /** The user's subscribed feeds, as the publisher profiles them. */
    @Query("$FEED_SELECT WHERE s.user_id = :userId AND s.status = 'SUBSCRIBED' ORDER BY f.title COLLATE NOCASE")
    suspend fun subscribedFeeds(userId: Long): List<UserFeedRow>

    /** One feed the user has a subscription to, of either status. */
    @Query("$FEED_SELECT WHERE s.user_id = :userId AND s.feed_id = :feedId")
    suspend fun feed(userId: Long, feedId: Long): UserFeedRow?

    /** The tags on the user's subscriptions. */
    @Query(
        "SELECT st.feed_id AS feedId, t.id AS tagId, t.name AS name, t.affinity AS affinity " +
            "FROM subscription_tag st JOIN tag t ON t.id = st.tag_id WHERE st.user_id = :userId AND t.user_id = :userId",
    )
    suspend fun tagLinks(userId: Long): List<UserTagLinkRow>

    /** The feeds the user subscribes to (status SUBSCRIBED). */
    @Query("SELECT feed_id FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED' ORDER BY feed_id")
    suspend fun subscribedFeedIds(userId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED'")
    suspend fun countSubscribed(userId: Long): Int

    /** Subscribed feeds added after [since]: the Extra offer's new sources. */
    @Query("SELECT feed_id FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED' AND added_at > :since ORDER BY feed_id")
    suspend fun subscribedAddedAfter(userId: Long, since: Instant): List<Long>

    /** The health flags of the user's subscribed feeds, for the desk notice. */
    @Query(
        "SELECT ff.last_flag AS flag, ff.flag_dismissed_until AS dismissedUntil FROM feed_flag ff " +
            "JOIN subscription s ON s.feed_id = ff.feed_id AND s.user_id = :userId " +
            "WHERE ff.user_id = :userId AND s.status = 'SUBSCRIBED'",
    )
    suspend fun flags(userId: Long): List<UserFlagRow>

    /** Stored bodies of [articleIds] (global rows); at most [MAX_IDS] per call. */
    @Query("SELECT articleId, blocksJson, source FROM article_content WHERE articleId IN (:articleIds)")
    suspend fun contents(articleIds: List<Long>): List<StoredBodyRow>

    /** The newest fetch attempt of any feed: where the scheduler's periodic refresh counts from after a restart. */
    @Query("SELECT MAX(last_attempt_at) FROM feed")
    suspend fun lastAttemptAt(): Instant?

    companion object {
        /** IDs per `IN (...)` list, well under SQLite's bound-value limit. */
        const val MAX_IDS = 500

        /**
         * Shared projection for [UserArticleRow]; append a WHERE clause. Names `:userId`, which
         * every query using it takes.
         */
        const val USER_ROW_SELECT =
            "SELECT a.*, COALESCE(s.title_override, f.title) AS feedTitle, f.iconUrl AS feedIconUrl, s.status AS feedStatus, " +
                "MAX(a.firstSeenAt, COALESCE(s.added_at, a.firstSeenAt)) AS seenAt, " +
                "g.openedAt AS openedAt, g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, " +
                "g.likedAt AS likedAt, g.editionsRunUnopened AS editionsRunUnopened, g.leadCount AS leadCount " +
                "FROM article a JOIN feed f ON f.id = a.feedId " +
                "LEFT JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
                "LEFT JOIN engagement g ON g.articleId = a.id AND g.user_id = :userId"

        /** Shared projection for [UserFeedRow]; append a WHERE clause on `s`. */
        const val FEED_SELECT =
            "SELECT f.id AS feedId, COALESCE(s.title_override, f.title) AS title, f.tier AS tier, " +
                "f.medianIntervalHrs AS medianIntervalHrs, s.affinity AS affinity, s.slider_step AS sliderStep, " +
                "s.mute_until AS muteUntil, s.added_at AS addedAt FROM subscription s JOIN feed f ON f.id = s.feed_id"
    }
}

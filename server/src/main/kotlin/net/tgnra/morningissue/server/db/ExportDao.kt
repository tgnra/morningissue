package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import java.time.Instant
import net.tgnra.morningissue.model.EnclosureKind

/**
 * Reads for the archive export (`ArchiveExporter`): one user's library in the shape the app's
 * archive holds it. The mirror of [ImportDao]. Every query over a per-user table names
 * `user_id`; the global reads (bodies, enclosures) are keyed by article IDs the caller took
 * from the user's own posts.
 *
 * The user's posts are those [ArticleDao.VISIBLE] calls theirs: every post of a feed they
 * subscribe to (in either status), and any other post they engaged with or one of their
 * editions placed.
 */
@Dao
abstract class ExportDao {
    /** The user's subscriptions with the shared feed, by feed ID. */
    @Query(
        "SELECT s.*, f.url AS f_url, f.title AS f_title, f.siteUrl AS f_siteUrl, f.iconUrl AS f_iconUrl " +
            "FROM subscription s JOIN feed f ON f.id = s.feed_id WHERE s.user_id = :userId ORDER BY s.feed_id",
    )
    abstract suspend fun subscriptions(userId: Long): List<ExportSubscriptionRow>

    /** The user's tags on their subscriptions, by name. */
    @Query(
        "SELECT st.feed_id AS feedId, t.name AS name FROM subscription_tag st JOIN tag t ON t.id = st.tag_id " +
            "WHERE st.user_id = :userId ORDER BY st.feed_id, t.name COLLATE NOCASE",
    )
    abstract suspend fun tagLinks(userId: Long): List<TagLinkRow>

    /**
     * Feeds the user does not subscribe to but holds posts of: posts they engaged with or that
     * one of their editions placed. Unsubscribing clears both, so this is normally empty.
     */
    @Query(
        "SELECT * FROM feed f WHERE NOT EXISTS (SELECT 1 FROM subscription s WHERE s.user_id = :userId AND s.feed_id = f.id) " +
            "AND f.id IN (SELECT a.feedId FROM article a JOIN engagement g ON g.articleId = a.id WHERE g.user_id = :userId " +
            "UNION SELECT a.feedId FROM article a JOIN edition_item i ON i.articleId = a.id JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId) ORDER BY f.id",
    )
    abstract suspend fun otherFeeds(userId: Long): List<FeedEntity>

    @Query("SELECT COUNT(*) FROM article a WHERE $USER_POST")
    abstract suspend fun countPosts(userId: Long): Int

    /**
     * The user's posts after [afterId], in ID order, with when the user first saw each:
     * `MAX(firstSeenAt, subscription.added_at)`, as editions count it.
     */
    @Query(
        "SELECT a.*, MAX(a.firstSeenAt, COALESCE(s.added_at, a.firstSeenAt)) AS seenAt FROM article a " +
            "LEFT JOIN subscription s ON s.user_id = :userId AND s.feed_id = a.feedId " +
            "WHERE a.id > :afterId AND $USER_POST ORDER BY a.id LIMIT :limit",
    )
    abstract suspend fun postsAfter(userId: Long, afterId: Long, limit: Int): List<ExportPostRow>

    @Query("SELECT * FROM article_content WHERE articleId IN (:articleIds)")
    abstract suspend fun contents(articleIds: List<Long>): List<ArticleContentEntity>

    @Query("SELECT * FROM engagement WHERE user_id = :userId AND articleId IN (:articleIds)")
    abstract suspend fun engagements(userId: Long, articleIds: List<Long>): List<EngagementEntity>

    @Query("SELECT articleId, enclosure FROM article_traits WHERE articleId IN (:articleIds) AND enclosure != 'NONE'")
    abstract suspend fun enclosures(articleIds: List<Long>): List<ExportEnclosureRow>

    /** The user's released editions, oldest first. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND releaseAt <= :now ORDER BY releaseAt, id")
    abstract suspend fun releasedEditions(userId: Long, now: Instant): List<EditionEntity>

    /** The items of one of the user's editions (the caller took [editionId] from [releasedEditions]). */
    @Query("SELECT * FROM edition_item WHERE editionId = :editionId ORDER BY page, slot")
    abstract suspend fun items(editionId: Long): List<EditionItemEntity>

    @Query("SELECT * FROM edition_page_view WHERE editionId = :editionId ORDER BY page")
    abstract suspend fun pageViews(editionId: Long): List<EditionPageViewEntity>

    companion object {
        /** [ArticleDao.VISIBLE]: `a` is one of `:userId`'s posts. */
        const val USER_POST =
            "(a.feedId IN (SELECT vs.feed_id FROM subscription vs WHERE vs.user_id = :userId) " +
                "OR a.id IN (SELECT vg.articleId FROM engagement vg WHERE vg.user_id = :userId) " +
                "OR a.id IN (SELECT vi.articleId FROM edition_item vi JOIN edition ve ON ve.id = vi.editionId WHERE ve.user_id = :userId))"
    }
}

/** A subscription with what the archive needs of its shared feed. */
data class ExportSubscriptionRow(
    @Embedded val subscription: SubscriptionEntity,
    @Embedded(prefix = "f_") val feed: ExportFeedColumns,
)

data class ExportFeedColumns(
    val url: String,
    val title: String,
    val siteUrl: String?,
    val iconUrl: String?,
)

/** A post with when the user first saw it. */
data class ExportPostRow(
    @Embedded val article: ArticleEntity,
    val seenAt: Instant,
)

data class ExportEnclosureRow(
    val articleId: Long,
    val enclosure: EnclosureKind,
)

package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant
import net.tgnra.morningissue.model.DiscoveryVerdict

/**
 * Discovery, per user: verdicts, the reads that build the user's taste profile, and the two
 * `user_state` records discovery keeps (the week's deck, and how to undo a Subscribe). Owned by
 * W-A (`docs/server-design.md`, section 8). The catalogue itself is not in Room: see
 * `feeds/discovery/CatalogueStore`.
 */
@Dao
abstract class DiscoveryDao {
    // --- Verdicts ------------------------------------------------------------------------------

    /** Records a decision, replacing any earlier one of the user's for the same feed. */
    @Upsert
    abstract suspend fun upsertVerdict(verdict: DiscoveryVerdictEntity)

    @Query("SELECT * FROM discovery_verdict WHERE user_id = :userId AND urlKey = :urlKey")
    abstract suspend fun verdict(userId: Long, urlKey: String): DiscoveryVerdictEntity?

    /** Every decision of the user's, newest first. */
    @Query("SELECT * FROM discovery_verdict WHERE user_id = :userId ORDER BY decidedAt DESC")
    abstract suspend fun verdicts(userId: Long): List<DiscoveryVerdictEntity>

    @Query("SELECT urlKey FROM discovery_verdict WHERE user_id = :userId")
    abstract suspend fun verdictKeys(userId: Long): List<String>

    /** One kind of decision, newest first: the Passed list. */
    @Query("SELECT * FROM discovery_verdict WHERE user_id = :userId AND verdict = :verdict ORDER BY decidedAt DESC")
    abstract suspend fun verdictsOf(userId: Long, verdict: DiscoveryVerdict): List<DiscoveryVerdictEntity>

    /** Forgets a decision: Undo, or Undo in the Passed list. */
    @Query("DELETE FROM discovery_verdict WHERE user_id = :userId AND urlKey = :urlKey")
    abstract suspend fun deleteVerdict(userId: Long, urlKey: String)

    // --- The user's profile --------------------------------------------------------------------

    /** The newest [limit] vectors [model] wrote for one feed's posts: its centroid's input. Global. */
    @Query(
        "SELECT v.vector FROM article_embedding v JOIN article a ON a.id = v.articleId " +
            "WHERE a.feedId = :feedId AND v.model = :model ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit",
    )
    abstract suspend fun feedVectors(feedId: Long, model: Int, limit: Int): List<ByteArray>

    /** Vectors of posts the user Liked or read at or after [since], newest first, whatever their feed. */
    @Query(
        "SELECT v.vector AS vector, g.likedAt AS likedAt, g.readAt AS readAt FROM engagement g " +
            "JOIN article a ON a.id = g.articleId JOIN article_embedding v ON v.articleId = a.id AND v.model = :model " +
            "WHERE g.user_id = :userId AND (g.likedAt >= :since OR g.readAt >= :since) " +
            "ORDER BY COALESCE(g.likedAt, g.readAt) DESC LIMIT :limit",
    )
    abstract suspend fun engagedVectors(userId: Long, since: Instant, model: Int, limit: Int): List<EngagedVectorRow>

    /** The feed categories of posts the user read since [since], most read first. */
    @Query(
        "SELECT c.`key` FROM article_category c JOIN engagement g ON g.articleId = c.articleId " +
            "WHERE g.user_id = :userId AND (g.readAt >= :since OR g.likedAt >= :since) " +
            "GROUP BY c.`key` ORDER BY COUNT(*) DESC LIMIT :limit",
    )
    abstract suspend fun readCategories(userId: Long, since: Instant, limit: Int): List<String>

    // --- Discovery's user_state records --------------------------------------------------------

    @Query("SELECT json FROM user_state WHERE user_id = :userId AND `key` = :key")
    abstract suspend fun state(userId: Long, key: String): String?

    @Query("INSERT OR REPLACE INTO user_state (user_id, `key`, json) VALUES (:userId, :key, :json)")
    abstract suspend fun putState(userId: Long, key: String, json: String)

    @Query("DELETE FROM user_state WHERE user_id = :userId AND `key` = :key")
    abstract suspend fun deleteState(userId: Long, key: String)
}

/** See [DiscoveryDao.engagedVectors]. */
class EngagedVectorRow(
    val vector: ByteArray,
    val likedAt: Instant?,
    val readAt: Instant?,
)

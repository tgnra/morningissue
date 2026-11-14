package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.DiscoveryVerdict

/**
 * Feed discovery's own tables (`discovery_verdict`, `discovery_candidate`) and the reads that
 * build the reader's taste profile from `article_embedding`. The catalogue itself is not in
 * Room: see `data/discovery/CatalogueStore`.
 */
@Dao
abstract class DiscoveryDao {
    // --- Verdicts ---------------------------------------------------------------------------

    /** Records a decision, replacing any earlier one for the same feed. */
    @Upsert
    abstract suspend fun upsertVerdict(verdict: DiscoveryVerdictEntity)

    @Query("SELECT * FROM discovery_verdict WHERE urlKey = :urlKey")
    abstract suspend fun verdict(urlKey: String): DiscoveryVerdictEntity?

    /** Every decision, newest first. */
    @Query("SELECT * FROM discovery_verdict ORDER BY decidedAt DESC")
    abstract suspend fun verdicts(): List<DiscoveryVerdictEntity>

    @Query("SELECT urlKey FROM discovery_verdict")
    abstract suspend fun verdictKeys(): List<String>

    /** One kind of decision, newest first: the Passed list. */
    @Query("SELECT * FROM discovery_verdict WHERE verdict = :verdict ORDER BY decidedAt DESC")
    abstract fun observeVerdicts(verdict: DiscoveryVerdict): Flow<List<DiscoveryVerdictEntity>>

    /** Forgets a decision: Undo, or Undo in the Passed list. */
    @Query("DELETE FROM discovery_verdict WHERE urlKey = :urlKey")
    abstract suspend fun deleteVerdict(urlKey: String)

    // --- Candidates found on the device (filled by the blogroll crawler, not built yet) ------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCandidate(candidate: DiscoveryCandidateEntity): Long

    @Upsert
    abstract suspend fun upsertCandidate(candidate: DiscoveryCandidateEntity)

    @Query("SELECT * FROM discovery_candidate ORDER BY mentions DESC, foundAt")
    abstract suspend fun candidates(): List<DiscoveryCandidateEntity>

    @Query("SELECT COUNT(*) FROM discovery_candidate")
    abstract suspend fun candidateCount(): Int

    @Query("DELETE FROM discovery_candidate WHERE urlKey = :urlKey")
    abstract suspend fun deleteCandidate(urlKey: String)

    // --- The reader's profile ---------------------------------------------------------------

    /** The newest [limit] vectors [model] wrote for one feed's posts: its centroid's input. */
    @Query(
        "SELECT v.vector FROM article_embedding v JOIN article a ON a.id = v.articleId " +
            "WHERE a.feedId = :feedId AND v.model = :model ORDER BY a.publishedAt DESC, a.id DESC LIMIT :limit",
    )
    abstract suspend fun feedVectors(feedId: Long, model: Int, limit: Int): List<ByteArray>

    /**
     * Vectors of posts the reader Liked or read at or after [since], newest first, whatever
     * their feed: a Liked post saved from discovery says as much about taste as any other.
     */
    @Query(
        "SELECT v.vector AS vector, g.likedAt AS likedAt, g.readAt AS readAt FROM engagement g " +
            "JOIN article a ON a.id = g.articleId JOIN article_embedding v ON v.articleId = a.id AND v.model = :model " +
            "WHERE g.likedAt >= :since OR g.readAt >= :since " +
            "ORDER BY COALESCE(g.likedAt, g.readAt) DESC LIMIT :limit",
    )
    abstract suspend fun engagedVectors(since: Instant, model: Int, limit: Int): List<EngagedVectorRow>

    /** The feed categories of posts read since [since], most read first. */
    @Query(
        "SELECT c.`key` FROM article_category c JOIN engagement g ON g.articleId = c.articleId " +
            "WHERE g.readAt >= :since OR g.likedAt >= :since GROUP BY c.`key` ORDER BY COUNT(*) DESC LIMIT :limit",
    )
    abstract suspend fun readCategories(since: Instant, limit: Int): List<String>
}

/** See `DiscoveryDao.engagedVectors`. */
class EngagedVectorRow(
    val vector: ByteArray,
    val likedAt: Instant?,
    val readAt: Instant?,
)

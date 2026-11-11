package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.engine.RankingConfig

@Dao
abstract class EngagementDao {
    @Query("SELECT * FROM engagement WHERE articleId = :articleId")
    abstract suspend fun get(articleId: Long): EngagementEntity?

    @Query("SELECT * FROM engagement WHERE articleId = :articleId")
    abstract fun observe(articleId: Long): Flow<EngagementEntity?>

    /**
     * Subscribed feeds' articles the reader opened, read, saved or Liked at or after [since];
     * input to affinity for posts engaged with outside an edition. Posts saved from discovery
     * are dormant evidence: they count once their feed is subscribed.
     */
    @Query(
        "SELECT a.feedId AS feedId, g.articleId AS articleId, g.openedAt AS openedAt, g.readAt AS readAt, " +
            "g.savedAt AS savedAt, g.likedAt AS likedAt FROM engagement g JOIN article a ON a.id = g.articleId " +
            "JOIN feed f ON f.id = a.feedId WHERE f.status = 'SUBSCRIBED' " +
            "AND (g.openedAt >= :since OR g.readAt >= :since OR g.savedAt >= :since OR g.likedAt >= :since)",
    )
    abstract suspend fun engagedSince(since: Instant): List<EngagedRow>

    /** Creates an empty row if the article has none. */
    @Query("INSERT OR IGNORE INTO engagement (articleId) VALUES (:articleId)")
    abstract suspend fun ensure(articleId: Long)

    @Query("UPDATE engagement SET openedAt = COALESCE(openedAt, :at) WHERE articleId = :articleId")
    protected abstract suspend fun setOpenedAt(articleId: Long, at: Instant)

    @Query("UPDATE engagement SET readFraction = MAX(readFraction, :fraction) WHERE articleId = :articleId")
    protected abstract suspend fun raiseReadFraction(articleId: Long, fraction: Float)

    @Query("UPDATE engagement SET readAt = COALESCE(readAt, :at) WHERE articleId = :articleId")
    protected abstract suspend fun setReadAt(articleId: Long, at: Instant)

    @Query("UPDATE engagement SET savedAt = :at WHERE articleId = :articleId")
    protected abstract suspend fun setSavedAt(articleId: Long, at: Instant?)

    @Query("UPDATE engagement SET likedAt = :at WHERE articleId = :articleId")
    protected abstract suspend fun setLikedAt(articleId: Long, at: Instant?)

    @Query("UPDATE engagement SET leadCount = leadCount + 1 WHERE articleId = :articleId")
    protected abstract suspend fun bumpLeadCount(articleId: Long)

    @Query("INSERT OR IGNORE INTO engagement (articleId) SELECT articleId FROM edition_item WHERE editionId = :editionId AND kind != 'INDEX'")
    protected abstract suspend fun ensureForEdition(editionId: Long)

    @Query(
        "UPDATE engagement SET editionsRunUnopened = editionsRunUnopened + 1 WHERE openedAt IS NULL " +
            "AND readAt IS NULL AND savedAt IS NULL AND likedAt IS NULL " +
            "AND articleId IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE i.editionId = :editionId AND i.kind != 'INDEX' AND ${EditionDao.SEEN})",
    )
    protected abstract suspend fun bumpUnopenedForEdition(editionId: Long, seenMinMillis: Long)

    /** Records the first open; later calls keep the original time. */
    @Transaction
    open suspend fun markOpened(articleId: Long, at: Instant) {
        ensure(articleId)
        setOpenedAt(articleId, at)
    }

    /** Raises the furthest-read fraction (never lowers it). */
    @Transaction
    open suspend fun recordReadFraction(articleId: Long, fraction: Float) {
        ensure(articleId)
        raiseReadFraction(articleId, fraction.coerceIn(0f, 1f))
    }

    /** Marks the article read; later calls keep the original time. */
    @Transaction
    open suspend fun markRead(articleId: Long, at: Instant) {
        ensure(articleId)
        setReadAt(articleId, at)
    }

    /** [at] null removes the save. */
    @Transaction
    open suspend fun setSaved(articleId: Long, at: Instant?) {
        ensure(articleId)
        setSavedAt(articleId, at)
    }

    /** [at] null removes the Like. */
    @Transaction
    open suspend fun setLiked(articleId: Long, at: Instant?) {
        ensure(articleId)
        setLikedAt(articleId, at)
    }

    @Transaction
    open suspend fun incrementLeadCount(articleId: Long) {
        ensure(articleId)
        bumpLeadCount(articleId)
    }

    /**
     * Adds one to `editionsRunUnopened` for every printed (non-index) story of [editionId] that
     * the reader saw and has not touched: never opened, and not marked read, saved or Liked
     * from the long-press menu either. "Saw" is the rule of `EditionDao.SEEN`: the story's page
     * was on screen for at least [seenMinMillis] in all, or the edition predates page views. A
     * story on a page the reader never looked at has not been passed over, so its seen penalty
     * does not grow. Call once per edition, when it is superseded by the next build.
     */
    @Transaction
    open suspend fun countUnopenedRun(editionId: Long, seenMinMillis: Long = RankingConfig.Default.pageSeenMinMillis) {
        ensureForEdition(editionId)
        bumpUnopenedForEdition(editionId, seenMinMillis)
    }
}

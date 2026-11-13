package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier

/**
 * Global feeds: refresh bookkeeping, repair, cadence and garbage collection. Owned by W-A
 * (`docs/server-design.md`, section 8). What a user chose about a feed is in [SubscriptionDao].
 */
@Dao
abstract class FeedDao {
    /** Returns the new row ID, or -1 when a feed with the same URL or URL key exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(feed: FeedEntity): Long

    @Query("SELECT * FROM feed WHERE id = :feedId")
    abstract suspend fun getById(feedId: Long): FeedEntity?

    @Query("SELECT * FROM feed WHERE url = :url")
    abstract suspend fun getByUrl(url: String): FeedEntity?

    @Query("SELECT * FROM feed WHERE url_key = :urlKey")
    abstract suspend fun getByUrlKey(urlKey: String): FeedEntity?

    /** Every feed's address: a repair never takes one of them. */
    @Query("SELECT id, url FROM feed")
    abstract suspend fun getUrls(): List<FeedUrlRow>

    @Query("DELETE FROM feed WHERE id = :feedId")
    abstract suspend fun delete(feedId: Long)

    /**
     * Which of [feedIds] a refresh fetches: those with at least one SUBSCRIBED subscriber (a
     * feed only discovered is never fetched) not tried since [notTriedSince]; null fetches every
     * one of them. At most [MAX_IDS] IDs per call.
     */
    @Query(
        "SELECT id FROM feed WHERE id IN (:feedIds) " +
            "AND EXISTS (SELECT 1 FROM subscription s WHERE s.feed_id = feed.id AND s.status = 'SUBSCRIBED') " +
            "AND (:notTriedSince IS NULL OR last_attempt_at IS NULL OR last_attempt_at < :notTriedSince)",
    )
    abstract suspend fun fetchable(feedIds: List<Long>, notTriedSince: Instant?): List<Long>

    /** Feeds anybody subscribes to: what the periodic refresh fetches. */
    @Query("SELECT DISTINCT s.feed_id FROM subscription s WHERE s.status = 'SUBSCRIBED' ORDER BY s.feed_id")
    abstract suspend fun subscribedFeedIds(): List<Long>

    /** The latest fetch of any feed; the scheduler's `lastPeriodicAt` on start. */
    @Query("SELECT MAX(last_attempt_at) FROM feed")
    abstract suspend fun latestAttempt(): Instant?

    /** A fetch failed at [at]. */
    @Query("UPDATE feed SET last_attempt_at = :at WHERE id = :feedId")
    abstract suspend fun recordAttempt(feedId: Long, at: Instant)

    /** A fetch worked at [at] (200 or 304); [etag] and [lastModified] are kept for the next request. */
    @Query(
        "UPDATE feed SET etag = :etag, lastModified = :lastModified, last_attempt_at = :at, last_success_at = :at " +
            "WHERE id = :feedId",
    )
    abstract suspend fun recordSuccess(feedId: Long, etag: String?, lastModified: String?, at: Instant)

    @Query("UPDATE feed SET medianIntervalHrs = :medianIntervalHrs, burstiness = :burstiness, tier = :tier WHERE id = :feedId")
    abstract suspend fun updateCadence(feedId: Long, medianIntervalHrs: Double?, burstiness: Double?, tier: CadenceTier)

    /** The feed document's own title; each user's name for it is `subscription.title_override`. */
    @Query("UPDATE feed SET title = :title WHERE id = :feedId")
    abstract suspend fun updateTitle(feedId: Long, title: String)

    /** Fills in site link and icon learned from the feed document; nulls leave the stored value. */
    @Query("UPDATE feed SET siteUrl = COALESCE(:siteUrl, siteUrl), iconUrl = COALESCE(:iconUrl, iconUrl) WHERE id = :feedId")
    abstract suspend fun updateSiteInfo(feedId: Long, siteUrl: String?, iconUrl: String?)

    /** Points the feed at a new address (permanent redirect or repair) and drops stale validators. */
    @Query("UPDATE feed SET url = :url, url_key = :urlKey, etag = NULL, lastModified = NULL WHERE id = :feedId")
    abstract suspend fun updateUrl(feedId: Long, url: String, urlKey: String)

    /** Feeds nobody subscribes to, in either status, that hold no posts any more: garbage collection deletes them. */
    @Query(
        "SELECT id FROM feed WHERE NOT EXISTS (SELECT 1 FROM subscription s WHERE s.feed_id = feed.id) " +
            "AND NOT EXISTS (SELECT 1 FROM article a WHERE a.feedId = feed.id)",
    )
    abstract suspend fun orphanIds(): List<Long>

    companion object {
        /** IDs per `IN (...)` list, safely under SQLite's limit of bound values. */
        const val MAX_IDS = 500
    }
}

data class FeedUrlRow(
    val id: Long,
    val url: String,
)

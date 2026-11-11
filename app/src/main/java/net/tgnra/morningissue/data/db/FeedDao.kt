package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep

@Dao
interface FeedDao {
    /** Returns the new row ID, or -1 when a feed with the same URL exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: FeedEntity): Long

    @Update
    suspend fun update(feed: FeedEntity)

    @Query("DELETE FROM feed WHERE id = :feedId")
    suspend fun delete(feedId: Long)

    @Query("SELECT * FROM feed WHERE id = :feedId")
    suspend fun getById(feedId: Long): FeedEntity?

    @Query("SELECT * FROM feed WHERE url = :url")
    suspend fun getByUrl(url: String): FeedEntity?

    /**
     * Subscribed feeds: what sync, the health check, affinity learning, editions, Sources and
     * OPML export work on. A feed that only holds posts from discovery (`FeedStatus.DISCOVERED`)
     * is left out of every list here; `SubscribedOnlyQueryTest` fails the build when a new
     * query over `feed` or `article` forgets to say which feeds it means.
     */
    @Query("SELECT * FROM feed WHERE status = 'SUBSCRIBED' ORDER BY title COLLATE NOCASE")
    suspend fun getSubscribed(): List<FeedEntity>

    /** Every feed's address, subscribed or not: adding a feed matches against all of them, so a discovered feed is reused. */
    @Query("SELECT id, url FROM feed")
    suspend fun getUrls(): List<FeedUrlRow>

    @Query("SELECT COUNT(*) FROM feed WHERE status = 'SUBSCRIBED'")
    suspend fun countSubscribed(): Int

    @Transaction
    @Query("SELECT * FROM feed WHERE status = 'SUBSCRIBED' ORDER BY title COLLATE NOCASE")
    suspend fun getSubscribedWithDetails(): List<FeedWithDetails>

    /** Every feed, discovered ones included, for the archive. */
    @Transaction
    @Query("SELECT * FROM feed ORDER BY title COLLATE NOCASE")
    suspend fun getAllWithDetailsIncludingDiscovered(): List<FeedWithDetails>

    @Transaction
    @Query("SELECT * FROM feed WHERE id = :feedId")
    suspend fun getWithDetails(feedId: Long): FeedWithDetails?

    @Transaction
    @Query("SELECT * FROM feed WHERE status = 'SUBSCRIBED' ORDER BY title COLLATE NOCASE")
    fun observeSubscribedWithDetails(): Flow<List<FeedWithDetails>>

    @Transaction
    @Query("SELECT * FROM feed WHERE id = :feedId")
    fun observeWithDetails(feedId: Long): Flow<FeedWithDetails?>

    /** Feeds that only hold posts from discovery and have none left; the clean-up deletes them. */
    @Query("SELECT id FROM feed WHERE status = 'DISCOVERED' AND id NOT IN (SELECT feedId FROM article)")
    suspend fun emptyDiscoveredIds(): List<Long>

    /**
     * Makes a discovered feed a subscription, as of [at]. Returns 1 when it was discovered,
     * 0 when it was already subscribed or does not exist.
     */
    @Query("UPDATE feed SET status = 'SUBSCRIBED', addedAt = :at, discoveredAt = NULL WHERE id = :feedId AND status = 'DISCOVERED'")
    suspend fun subscribeDiscovered(feedId: Long, at: Instant): Int

    /** Undoes [subscribeDiscovered]: the feed holds discovery posts again, as it did before. */
    @Query("UPDATE feed SET status = 'DISCOVERED', addedAt = :addedAt, discoveredAt = :discoveredAt WHERE id = :feedId")
    suspend fun revertToDiscovered(feedId: Long, addedAt: Instant, discoveredAt: Instant?)

    @Query("UPDATE feed SET etag = :etag, lastModified = :lastModified, lastSyncAt = :lastSyncAt WHERE id = :feedId")
    suspend fun updateSyncState(feedId: Long, etag: String?, lastModified: String?, lastSyncAt: Instant)

    @Query("UPDATE feed SET medianIntervalHrs = :medianIntervalHrs, burstiness = :burstiness, tier = :tier WHERE id = :feedId")
    suspend fun updateCadence(feedId: Long, medianIntervalHrs: Double?, burstiness: Double?, tier: CadenceTier)

    @Query("UPDATE feed SET affinity = :affinity WHERE id = :feedId")
    suspend fun updateAffinity(feedId: Long, affinity: Double)

    @Query("UPDATE feed SET sliderStep = :step WHERE id = :feedId")
    suspend fun updateSliderStep(feedId: Long, step: SliderStep)

    @Query("UPDATE feed SET muteUntil = :until WHERE id = :feedId")
    suspend fun updateMuteUntil(feedId: Long, until: Instant?)

    @Query("UPDATE feed SET title = :title WHERE id = :feedId")
    suspend fun updateTitle(feedId: Long, title: String)

    /** Fills in site link and icon learned from the feed document; nulls leave the stored value. */
    @Query("UPDATE feed SET siteUrl = COALESCE(:siteUrl, siteUrl), iconUrl = COALESCE(:iconUrl, iconUrl) WHERE id = :feedId")
    suspend fun updateSiteInfo(feedId: Long, siteUrl: String?, iconUrl: String?)

    /** Points the feed at a new address (permanent redirect or repair) and drops stale validators. */
    @Query("UPDATE feed SET url = :url, etag = NULL, lastModified = NULL WHERE id = :feedId")
    suspend fun updateUrl(feedId: Long, url: String)

    @Query("UPDATE feed SET notify = :notify WHERE id = :feedId")
    suspend fun updateNotify(feedId: Long, notify: Boolean)
}

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

    @Query("SELECT * FROM feed ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<FeedEntity>

    @Query("SELECT id, url FROM feed")
    suspend fun getUrls(): List<FeedUrlRow>

    @Query("SELECT COUNT(*) FROM feed")
    suspend fun count(): Int

    @Transaction
    @Query("SELECT * FROM feed ORDER BY title COLLATE NOCASE")
    suspend fun getAllWithDetails(): List<FeedWithDetails>

    @Transaction
    @Query("SELECT * FROM feed WHERE id = :feedId")
    suspend fun getWithDetails(feedId: Long): FeedWithDetails?

    @Transaction
    @Query("SELECT * FROM feed ORDER BY title COLLATE NOCASE")
    fun observeAllWithDetails(): Flow<List<FeedWithDetails>>

    @Transaction
    @Query("SELECT * FROM feed WHERE id = :feedId")
    fun observeWithDetails(feedId: Long): Flow<FeedWithDetails?>

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

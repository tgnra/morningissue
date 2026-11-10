package net.tgnra.morningissue.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun link(link: FeedTagEntity)

    @Query("DELETE FROM feed_tag WHERE feedId = :feedId")
    protected abstract suspend fun unlinkAll(feedId: Long)

    @Query("SELECT * FROM tag WHERE name = :name COLLATE NOCASE")
    abstract suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tag ORDER BY name COLLATE NOCASE")
    abstract suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tag ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT t.* FROM tag t JOIN feed_tag ft ON ft.tagId = t.id WHERE ft.feedId = :feedId ORDER BY t.name COLLATE NOCASE")
    abstract suspend fun getForFeed(feedId: Long): List<TagEntity>

    @Query("SELECT * FROM feed_tag")
    abstract suspend fun getAllLinks(): List<FeedTagEntity>

    @Query("UPDATE tag SET affinity = :affinity WHERE id = :tagId")
    abstract suspend fun updateAffinity(tagId: Long, affinity: Double)

    /** Removes tags no feed carries any more. */
    @Query("DELETE FROM tag WHERE id NOT IN (SELECT tagId FROM feed_tag)")
    abstract suspend fun deleteUnused()

    /** Returns the tag named [name] (matched without regard to case), creating it if needed. */
    @Transaction
    open suspend fun getOrCreate(name: String): TagEntity {
        val clean = name.trim()
        getByName(clean)?.let { return it }
        val id = insertIgnore(TagEntity(name = clean))
        return TagEntity(id = id, name = clean)
    }

    /** Replaces the feed's tags with [names]; blank names are ignored and unused tags removed. */
    @Transaction
    open suspend fun setFeedTags(feedId: Long, names: List<String>) {
        unlinkAll(feedId)
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
            link(FeedTagEntity(feedId = feedId, tagId = getOrCreate(name).id))
        }
        deleteUnused()
    }
}

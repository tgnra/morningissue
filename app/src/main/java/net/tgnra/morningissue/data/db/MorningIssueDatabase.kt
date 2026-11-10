package net.tgnra.morningissue.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FeedEntity::class,
        TagEntity::class,
        FeedTagEntity::class,
        ArticleEntity::class,
        ArticleContentEntity::class,
        EngagementEntity::class,
        EditionEntity::class,
        EditionItemEntity::class,
        FeedHealthEntity::class,
        ArticleFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MorningIssueDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
    abstract fun engagementDao(): EngagementDao
    abstract fun editionDao(): EditionDao
    abstract fun feedHealthDao(): FeedHealthDao

    companion object {
        const val NAME = "morningissue.db"

        fun create(context: Context): MorningIssueDatabase =
            Room.databaseBuilder(context, MorningIssueDatabase::class.java, NAME).build()

        /** For tests. */
        fun inMemory(context: Context): MorningIssueDatabase =
            Room.inMemoryDatabaseBuilder(context, MorningIssueDatabase::class.java).build()
    }
}

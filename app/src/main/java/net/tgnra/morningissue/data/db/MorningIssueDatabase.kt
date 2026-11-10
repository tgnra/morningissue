package net.tgnra.morningissue.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.room3.ColumnTypeConverters

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
@ColumnTypeConverters(Converters::class)
abstract class MorningIssueDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
    abstract fun engagementDao(): EngagementDao
    abstract fun editionDao(): EditionDao
    abstract fun feedHealthDao(): FeedHealthDao

    /**
     * Room 3 needs an explicit SQLite driver. This is the platform's own SQLite, the engine
     * the database has always run on, so FTS4 `MATCH` and `snippet` behave as before and
     * Robolectric tests need no extra native library.
     */
    companion object {
        const val NAME = "morningissue.db"

        fun create(context: Context): MorningIssueDatabase =
            Room.databaseBuilder<MorningIssueDatabase>(context, NAME).setDriver(AndroidSQLiteDriver()).build()

        /** For tests. */
        fun inMemory(context: Context): MorningIssueDatabase =
            Room.inMemoryDatabaseBuilder<MorningIssueDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
    }
}

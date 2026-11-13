package net.tgnra.morningissue.server.db

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * The server's multi-user schema (`docs/server-design.md`, section 2.2), on Room 3 for the JVM
 * with the bundled SQLite (which brings its own native library, built with FTS3/4/5).
 *
 * Shared file: workstreams change it only by appending to the entity list and the DAO
 * accessors. The schema stays at version 1, with no migrations, until the first server release;
 * `server/schemas/` is regenerated at integration.
 */
@Database(
    entities = [
        // AccountEntities.kt
        UserEntity::class,
        DeviceEntity::class,
        UserSettingsEntity::class,
        UserStateEntity::class,
        ImportLogEntity::class,
        // FeedEntities.kt
        FeedEntity::class,
        FeedStatusEntity::class,
        ArticleEntity::class,
        ArticleContentEntity::class,
        ArticleFtsEntity::class,
        ArticleCategoryEntity::class,
        ArticleTraitsEntity::class,
        ArticleEmbeddingEntity::class,
        // UserFeedEntities.kt
        SubscriptionEntity::class,
        TagEntity::class,
        SubscriptionTagEntity::class,
        FeedFlagEntity::class,
        DiscoveryVerdictEntity::class,
        // EditionEntities.kt
        EngagementEntity::class,
        EditionEntity::class,
        EditionItemEntity::class,
        EditionPageViewEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class ServerDatabase : RoomDatabase() {
    // W-C
    abstract fun accountDao(): AccountDao
    abstract fun deviceDao(): DeviceDao

    // W-A
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun signalDao(): SignalDao
    abstract fun healthDao(): HealthDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun discoveryDao(): DiscoveryDao

    // W-B
    abstract fun editionDao(): EditionDao
    abstract fun engagementDao(): EngagementDao
    abstract fun editionArticleDao(): EditionArticleDao
    abstract fun learningDao(): LearningDao
    abstract fun importDao(): ImportDao
    abstract fun userStateDao(): UserStateDao

    companion object {
        const val FILE_NAME = "morningissue.db"

        /** Queries run on at most this many threads; WAL gives one writer and several readers. */
        private const val QUERY_PARALLELISM = 8

        /**
         * Opens (creating if needed) the database file [file] in WAL mode. The bundled SQLite
         * extracts its native library into `java.io.tmpdir` on first use, which the launcher
         * sets to `<dataDir>/tmp`.
         */
        fun open(file: File): ServerDatabase =
            Room.databaseBuilder<ServerDatabase>(file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(QUERY_PARALLELISM))
                .build()

        /** For tests. */
        fun inMemory(): ServerDatabase =
            Room.inMemoryDatabaseBuilder<ServerDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(QUERY_PARALLELISM))
                .build()
    }
}

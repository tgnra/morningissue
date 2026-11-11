package net.tgnra.morningissue.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * One step per schema version. A step is never edited once a release has shipped it: an
 * installed database has already run it. The table definitions here are the ones Room exported
 * to `app/schemas/<version>.json`, and `MigrationTest` checks each step against that file.
 */
object Migrations {
    /**
     * Version 2 adds what the within-feed ranking reads: `article_category`, `article_traits`
     * and `article_embedding`. All three start empty.
     *
     * - Traits are computed from what is already stored, by `TraitsBackfill` after the upgrade.
     * - Embeddings are computed by the embedder from the stored text.
     * - Categories were never stored and exist only in the feeds. Clearing every feed's HTTP
     *   validators makes the next sync fetch each feed in full instead of getting "not
     *   modified", and the syncer then files the posts it already holds.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `article_category` (`articleId` INTEGER NOT NULL, `key` TEXT NOT NULL, " +
                    "`label` TEXT NOT NULL, PRIMARY KEY(`articleId`, `key`), " +
                    "FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_article_category_key` ON `article_category` (`key`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `article_traits` (`articleId` INTEGER NOT NULL, `traitsVersion` INTEGER NOT NULL, " +
                    "`linkCount` INTEGER NOT NULL, `imageCount` INTEGER NOT NULL, `hasCode` INTEGER NOT NULL, " +
                    "`enclosure` TEXT NOT NULL, `isLinkPost` INTEGER NOT NULL, `titlePrefix` TEXT, PRIMARY KEY(`articleId`), " +
                    "FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `article_embedding` (`articleId` INTEGER NOT NULL, `model` INTEGER NOT NULL, " +
                    "`vector` BLOB NOT NULL, PRIMARY KEY(`articleId`), " +
                    "FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("UPDATE `feed` SET `etag` = NULL, `lastModified` = NULL")
        }
    }

    /** Every step, for `RoomDatabase.Builder.addMigrations`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}

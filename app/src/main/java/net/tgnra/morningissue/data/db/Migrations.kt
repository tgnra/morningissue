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
     * and `article_embedding`, the column `article.feedWordCount`, and `stored_image`, the
     * image store's bookkeeping. The four tables start empty.
     *
     * - Traits are computed from what is already stored, by `TraitsBackfill` after the upgrade.
     * - Embeddings are computed by the embedder from the stored text.
     * - Categories were never stored and exist only in the feeds. Clearing every feed's HTTP
     *   validators makes the next sync fetch each feed in full instead of getting "not
     *   modified", and the syncer then files the posts it already holds.
     * - `feedWordCount` is the length of the feed's own text, which version 1 kept only until
     *   full text was fetched over it. It is filled as `FeedWordCount.estimate` does: a post
     *   whose stored body is still the feed's (or that has none) takes its `wordCount`, which
     *   is exact; a post whose body was fetched from its page takes the length of its summary,
     *   which is the feed's text cut to 400 characters, so a feed text longer than that is
     *   undercounted. Copying the page's count instead would keep the fault the column is
     *   there to remove: opened posts would look long and unopened ones brief.
     * - `stored_image` has nothing to be filled from: version 1 kept no images, only whatever
     *   was in Coil's cache at the time. Back issues' pictures are shown from the network as
     *   before, and an article's are stored when it is next opened.
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
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `stored_image` (`url` TEXT NOT NULL, `articleId` INTEGER NOT NULL, " +
                    "`fileName` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `originalBytes` INTEGER NOT NULL, " +
                    "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `storedAt` INTEGER NOT NULL, PRIMARY KEY(`url`), " +
                    "FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_stored_image_articleId` ON `stored_image` (`articleId`)")
            connection.execSQL("UPDATE `feed` SET `etag` = NULL, `lastModified` = NULL")
            connection.execSQL("ALTER TABLE `article` ADD COLUMN `feedWordCount` INTEGER NOT NULL DEFAULT 0")
            // Summaries are stored with single spaces, so spaces + 1 is their word count.
            connection.execSQL(
                "UPDATE `article` SET `feedWordCount` = CASE " +
                    "WHEN `id` IN (SELECT `articleId` FROM `article_content` WHERE `source` = 'FETCHED') THEN MIN(`wordCount`, " +
                    "CASE WHEN `summary` IS NULL OR TRIM(`summary`) = '' THEN 0 " +
                    "ELSE LENGTH(TRIM(`summary`)) - LENGTH(REPLACE(TRIM(`summary`), ' ', '')) + 1 END) " +
                    "ELSE `wordCount` END",
            )
        }
    }

    /**
     * Version 3 adds feed discovery: `feed.status` and `feed.discoveredAt`, and the tables
     * `discovery_verdict` and `discovery_candidate`.
     *
     * - `status` is filled by its default: every feed of a version 2 database is a
     *   subscription. `discoveredAt` stays null, as it does for every subscription.
     * - `discovery_verdict` records decisions about suggested feeds. Version 2 had no
     *   suggestions, so there is nothing to fill it from; a feed removed before the upgrade is
     *   not remembered as dropped.
     * - `discovery_candidate` is the cache of feeds found on the device (blogrolls, links in
     *   read posts). Nothing fills it yet; it is created now so that the crawler needs no
     *   further schema change.
     * - Feed centroids are deliberately not a table: they are derived from `article_embedding`
     *   whenever discovery opens, so nothing needs filling and nothing can go stale.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `feed` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'SUBSCRIBED'")
            connection.execSQL("ALTER TABLE `feed` ADD COLUMN `discoveredAt` INTEGER")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `discovery_verdict` (`urlKey` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                    "`verdict` TEXT NOT NULL, `decidedAt` INTEGER NOT NULL, `title` TEXT, `clusterId` INTEGER, " +
                    "`centroid` BLOB, PRIMARY KEY(`urlKey`))",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_discovery_verdict_decidedAt` ON `discovery_verdict` (`decidedAt`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `discovery_candidate` (`urlKey` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `foundVia` TEXT, `foundViaTitle` TEXT, `title` TEXT, `siteUrl` TEXT, " +
                    "`host` TEXT, `description` TEXT, `mentions` INTEGER NOT NULL DEFAULT 1, `foundAt` INTEGER NOT NULL, " +
                    "`fetchedAt` INTEGER, `tier` TEXT, `medianIntervalHrs` REAL, `fullTextShare` REAL, `lastPostAt` INTEGER, " +
                    "`centroid` BLOB, PRIMARY KEY(`urlKey`))",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_discovery_candidate_fetchedAt` ON `discovery_candidate` (`fetchedAt`)")
        }
    }

    /** Every step, for `RoomDatabase.Builder.addMigrations`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

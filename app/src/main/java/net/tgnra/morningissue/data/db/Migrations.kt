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

    /**
     * Version 4 adds page views: the table `edition_page_view` (how long each page of an
     * edition was on screen) and the column `edition.pageViewsTracked`. A printed story then
     * counts as ignored only when its page was on screen for at least `pageSeenMinMillis`.
     *
     * - `edition_page_view` starts empty. Version 3 recorded nothing about pages, so there is
     *   nothing to fill it from.
     * - `pageViewsTracked` is the cut-over. Every edition of a version 3 database gets 0 (its
     *   default): with no view data its printed stories go on counting as seen, as they always
     *   did, so the affinity, within-feed ranking, seen penalty and feed-health flags learned
     *   before the upgrade do not change on upgrade. The publisher sets it to 1 on every
     *   edition it builds from now on. A flag per edition rather than one migration timestamp,
     *   so that the rule does not depend on the clock: an edition built before the upgrade and
     *   read after it is judged entirely by the old rule, and one built after it entirely by
     *   the new.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `edition` ADD COLUMN `pageViewsTracked` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `edition_page_view` (`editionId` INTEGER NOT NULL, `page` INTEGER NOT NULL, " +
                    "`msVisible` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, PRIMARY KEY(`editionId`, `page`), " +
                    "FOREIGN KEY(`editionId`) REFERENCES `edition`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /**
     * Version 5 adds `sync_peer`, the Kobos paired for local-network sync (a feature since
     * removed, with the Kobo reader; version 8 drops the table).
     *
     * It starts empty: version 4 had no sync, so there was nothing to fill it from.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_peer` (`deviceId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`tokenHash` TEXT NOT NULL, `ackedSeq` INTEGER NOT NULL DEFAULT 0, `pairedAt` INTEGER NOT NULL, " +
                    "`lastSyncAt` INTEGER, PRIMARY KEY(`deviceId`))",
            )
        }
    }

    /**
     * Version 6 lets a Kobo pair for relay sync (since removed) in the same `sync_peer`
     * row as its local-network pairing, since both send the one event log and share its
     * `ackedSeq`.
     *
     * - `tokenHash` may now be null, for a device paired only through a relay. SQLite cannot
     *   drop a NOT NULL constraint in place, so the table is rebuilt and every row copied.
     * - Four new columns hold the relay pairing: `relayWidth`, `relayAuthKey` and `relayEncKey`
     *   (wrapped with a Keystore key) and `relayPairedAt`. They start null: version 5 had no
     *   relay sync, so every existing device stays paired over the local network only.
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `_new_sync_peer` (`deviceId` TEXT NOT NULL, `name` TEXT NOT NULL, `tokenHash` TEXT, " +
                    "`ackedSeq` INTEGER NOT NULL DEFAULT 0, `pairedAt` INTEGER NOT NULL, `lastSyncAt` INTEGER, `relayWidth` INTEGER, " +
                    "`relayAuthKey` TEXT, `relayEncKey` TEXT, `relayPairedAt` INTEGER, PRIMARY KEY(`deviceId`))",
            )
            connection.execSQL(
                "INSERT INTO `_new_sync_peer` (`deviceId`, `name`, `tokenHash`, `ackedSeq`, `pairedAt`, `lastSyncAt`) " +
                    "SELECT `deviceId`, `name`, `tokenHash`, `ackedSeq`, `pairedAt`, `lastSyncAt` FROM `sync_peer`",
            )
            connection.execSQL("DROP TABLE `sync_peer`")
            connection.execSQL("ALTER TABLE `_new_sync_peer` RENAME TO `sync_peer`")
        }
    }

    /**
     * Version 8 is the local app again, without the Kobo: `sync_peer`, the paired Kobos, is
     * dropped with everything else of the Kobo sync. Nothing else changes, so every other row
     * stays as it was.
     */
    val MIGRATION_6_8: Migration = object : Migration(6, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS `sync_peer`")
        }
    }

    /**
     * Version 7 was the database of the 0.5 releases, the client of a self-hosted server
     * (since removed): version 6 without `sync_peer`, and with `outbox_event`, the events
     * waiting to be sent to the server. Every other table is the same as version 6's, so this
     * step only drops `outbox_event`; what the rows hold is then judged at the first start by
     * `ServerClientUpgrade`, which can tell from the client's own records whether this is still
     * the reader's library or the server's cache (which it clears, for an archive restore).
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS `outbox_event`")
            connection.execSQL("DROP TABLE IF EXISTS `sync_peer`")
        }
    }

    /**
     * Version 9 adds `article_interest`, the on-device model's 1-to-5 rating of a post, with
     * when it was given and a hash of the taste profile the model was shown. It starts empty:
     * nothing was rated before, and every post is scored as if unrated (N = 1) until the model
     * has rated it, so the first edition after the upgrade is built as before.
     */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `article_interest` (`articleId` INTEGER NOT NULL, `rating` INTEGER NOT NULL, " +
                    "`ratedAt` INTEGER NOT NULL, `profileHash` TEXT NOT NULL, PRIMARY KEY(`articleId`), " +
                    "FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** Every step, for `RoomDatabase.Builder.addMigrations`. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_8, MIGRATION_7_8, MIGRATION_8_9,
    )
}

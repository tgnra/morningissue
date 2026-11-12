package net.tgnra.morningissue.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.signals.EmbeddingBackfill
import net.tgnra.morningissue.data.signals.SignalsUpkeep
import net.tgnra.morningissue.data.signals.StoredEmbedding
import net.tgnra.morningissue.data.signals.TraitsBackfill
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades a database built from the exported schema of an older version (`app/schemas`, which
 * the debug build carries as assets) and checks the result against the schema of the new one.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = MorningIssueDatabase::class,
    )

    @After
    fun tearDown() {
        file.delete()
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { statement ->
        check(statement.step())
        if (statement.isNull(0)) null else statement.getText(0)
    }

    private val body = ContentBlocks.encode(
        listOf(ContentBlock.Paragraph(RichText("Read this piece on marmalade.", listOf(InlineSpan(0, 4, InlineStyle.LINK, "https://elsewhere.example/piece"))))),
    )

    /** A small library as version 1 held it: two feeds with validators, three posts, a body, engagement and an edition. */
    private fun seedVersion1(db: SQLiteConnection) {
        db.execSQL(
            "INSERT INTO feed (id, url, siteUrl, title, tier, affinity, sliderStep, notify, lastSyncAt, etag, lastModified, addedAt) VALUES " +
                "(1, 'https://blog.example/feed.xml', 'https://blog.example/', 'A blog', 'WEEKLY', 0.8, 'FAVOURITE', 0, 1000, '\"v7\"', 'Mon, 09 Nov 2026 10:00:00 GMT', 500), " +
                "(2, 'https://news.example/rss', NULL, 'News', 'DAILY', 0.5, 'AUTO', 0, 1000, NULL, 'Tue, 10 Nov 2026 10:00:00 GMT', 600)",
        )
        db.execSQL("INSERT INTO tag (id, name, affinity) VALUES (1, 'Tech', 0.6)")
        db.execSQL("INSERT INTO feed_tag (feedId, tagId) VALUES (1, 1)")
        db.execSQL(
            "INSERT INTO article (id, feedId, guid, url, title, summary, publishedAt, firstSeenAt, wordCount, contentHash) VALUES " +
                "(1, 1, 'a', 'https://blog.example/links/1', 'Links: something good', 'Read this piece.', 900, 1000, 5, 'h1'), " +
                "(2, 1, 'b', 'https://blog.example/b', 'An essay', NULL, 900, 1000, 0, NULL), " +
                "(3, 2, 'c', 'https://news.example/c', 'A story', 'Summary', 950, 1000, 1, 'h3')",
        )
        db.prepare("INSERT INTO article_content (articleId, blocksJson, source, storedAt) VALUES (1, ?, 'FEED', 1000)").use {
            it.bindText(1, body)
            it.step()
        }
        db.execSQL(
            "INSERT INTO article_fts (rowid, title, summary, author, body) VALUES " +
                "(1, 'Links: something good', 'Read this piece.', '', 'Read this piece on marmalade.'), " +
                "(2, 'An essay', '', '', ''), (3, 'A story', 'Summary', '', '')",
        )
        db.execSQL("INSERT INTO engagement (articleId, openedAt, readFraction, likedAt, editionsRunUnopened, leadCount) VALUES (1, 1100, 0.9, 1200, 0, 1)")
        db.execSQL(
            "INSERT INTO edition (id, date, kind, cutoffAt, releaseAt, builtAt, length, number) VALUES (1, 20767, 'MORNING', 1000, 1000, 990, 'STANDARD', 1)",
        )
        db.execSQL("INSERT INTO edition_item (editionId, articleId, page, slot, kind, score, reason) VALUES (1, 1, 1, 0, 'LEAD', 1.5, 'Because')")
        db.execSQL("INSERT INTO feed_health (feedId, errorStreak, lastHttpStatus) VALUES (1, 0, 200)")
    }

    @Test
    fun version1To2KeepsEveryRowAddsTheEmptyTablesAndClearsTheValidators() = runBlocking<Unit> {
        helper.createDatabase(1).use { v1 ->
            seedVersion1(v1)
            // Two posts the reader opened, so that their pages were fetched over the feed's excerpts.
            v1.execSQL(
                "INSERT INTO article (id, feedId, guid, url, title, summary, publishedAt, firstSeenAt, wordCount, contentHash) VALUES " +
                    "(4, 2, 'd', 'https://news.example/d', 'Opened', 'An excerpt of just seven words here.', 950, 1000, 1200, 'h4'), " +
                    "(5, 2, 'e', 'https://news.example/e', 'Opened, no summary', NULL, 950, 1000, 800, 'h5')",
            )
            v1.execSQL("INSERT INTO article_content (articleId, blocksJson, source, storedAt) VALUES (4, '[]', 'FETCHED', 1100), (5, '[]', 'FETCHED', 1100)")
            v1.execSQL("INSERT INTO article_fts (rowid, title, summary, author, body) VALUES (4, 'Opened', '', '', ''), (5, 'Opened, no summary', '', '', '')")
        }

        // Runs the step and compares the resulting tables with the exported version 2 schema.
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { db ->
            for ((table, rows) in listOf(
                "feed" to 2L, "tag" to 1L, "feed_tag" to 1L, "article" to 5L, "article_content" to 3L, "article_fts" to 5L,
                "engagement" to 1L, "edition" to 1L, "edition_item" to 1L, "feed_health" to 1L,
            )) {
                assertEquals(table, rows, db.long("SELECT COUNT(*) FROM $table"))
            }
            for (table in listOf("article_category", "article_traits", "article_embedding", "stored_image")) {
                assertEquals(table, 0L, db.long("SELECT COUNT(*) FROM $table"))
            }
            assertEquals(1L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_article_category_key'"))
            assertEquals(1L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_stored_image_articleId'"))
            // The image store's table takes rows for the posts that were there, and they go with their post.
            db.execSQL(
                "INSERT INTO stored_image (url, articleId, fileName, byteSize, originalBytes, width, height, storedAt) " +
                    "VALUES ('https://blog.example/a.jpg', 1, 'abc', 1000, 4000, 1280, 720, 2000)",
            )
            assertEquals(1000L, db.long("SELECT SUM(byteSize) FROM stored_image"))

            // Every feed is fetched in full next time; nothing else about it changed.
            assertEquals(0L, db.long("SELECT COUNT(*) FROM feed WHERE etag IS NOT NULL OR lastModified IS NOT NULL"))
            assertEquals("A blog", db.text("SELECT title FROM feed WHERE id = 1"))
            assertEquals("FAVOURITE", db.text("SELECT sliderStep FROM feed WHERE id = 1"))
            assertEquals(1000L, db.long("SELECT lastSyncAt FROM feed WHERE id = 1"))
            assertEquals(800L, db.long("SELECT CAST(affinity * 1000 AS INTEGER) FROM feed WHERE id = 1"))

            // The feed's own word count: the stored count where the body is still the feed's or
            // there is none, the summary's length where a fetched page has replaced it.
            assertEquals(5L, db.long("SELECT feedWordCount FROM article WHERE id = 1"))
            assertEquals(0L, db.long("SELECT feedWordCount FROM article WHERE id = 2"))
            assertEquals(1L, db.long("SELECT feedWordCount FROM article WHERE id = 3"))
            assertEquals(7L, db.long("SELECT feedWordCount FROM article WHERE id = 4"))
            assertEquals(0L, db.long("SELECT feedWordCount FROM article WHERE id = 5"))
            // The same rule in Kotlin, for archives written before the field existed.
            assertEquals(7, net.tgnra.morningissue.model.FeedWordCount.estimate(1200, "An excerpt of just seven words here.", ContentSource.FETCHED))
            assertEquals(5, net.tgnra.morningissue.model.FeedWordCount.estimate(5, "Read this piece.", ContentSource.FEED))
            assertEquals(1200L, db.long("SELECT wordCount FROM article WHERE id = 4"))

            assertEquals("Links: something good", db.text("SELECT title FROM article WHERE id = 1"))
            assertEquals(body, db.text("SELECT blocksJson FROM article_content WHERE articleId = 1"))
            assertEquals(1200L, db.long("SELECT likedAt FROM engagement WHERE articleId = 1"))
            assertEquals("Because", db.text("SELECT reason FROM edition_item WHERE editionId = 1"))
            assertEquals(1L, db.long("SELECT rowid FROM article_fts WHERE article_fts MATCH 'marmalade'"))
        }
    }

    @Test
    fun theAppOpensAVersion1DatabaseAndTheBackfillFillsInTheTraits() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)

        // As `MorningIssueDatabase.create` builds it, on the file version 1 left behind.
        val db = Room.databaseBuilder<MorningIssueDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*Migrations.ALL)
            .build()
        helper.closeWhenFinished(db)

        assertEquals(3, db.articleDao().count())
        val feed = db.feedDao().getById(1)!!
        assertNull(feed.etag)
        assertNull(feed.lastModified)
        assertEquals("https://blog.example/", feed.siteUrl)
        assertEquals(ContentSource.FEED, db.articleDao().getContent(1)!!.source)
        assertNotNull(db.engagementDao().get(1)!!.likedAt)
        assertEquals(1, db.editionDao().count())

        val dao = db.articleSignalDao()
        assertEquals(0, dao.traitsCount())
        assertEquals(3, dao.countWithoutEmbedding(1))
        assertTrue(dao.signalsFor(listOf(1L, 2L, 3L), null).all { it.traits == null && it.categories.isEmpty() && it.embedding == null })

        assertEquals(3, TraitsBackfill(db).run())
        assertEquals(ArticleTraits(1, 0, false, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "links"), dao.getTraits(1)!!.toTraits())
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = false, titlePrefix = null), dao.getTraits(2)!!.toTraits())
        assertEquals(0, TraitsBackfill(db).run())

        // The new tables go with their post.
        dao.addCategories(1, listOf(net.tgnra.morningissue.model.ArticleCategories.of("Tech")!!))
        dao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(1, 1, byteArrayOf(1))))
        db.storedImageDao().upsert(StoredImageEntity("https://blog.example/a.jpg", 1, "abc", 1000, 4000, 1280, 720, java.time.Instant.EPOCH))
        assertEquals(1000L, db.storedImageDao().totalBytes())
        db.feedDao().delete(1)
        assertEquals(0, db.storedImageDao().count())
        assertEquals(0, dao.categoryCount())
        assertEquals(0, dao.embeddingCount())
        assertEquals(1, dao.traitsCount())
    }

    @Test
    fun anUpgradedLibraryGetsItsTraitsAndVectorsFromOnePassOfUpkeep() = runBlocking<Unit> {
        helper.createDatabase(1).use { v1 ->
            seedVersion1(v1)
            // Two more posts as version 1 held them: one in Russian, one with no words at all.
            v1.execSQL(
                "INSERT INTO article (id, feedId, guid, url, title, summary, publishedAt, firstSeenAt, wordCount, contentHash) VALUES " +
                    "(4, 2, 'd', 'https://news.example/d', 'Учёные обнаружили новую экзопланету', 'В зоне обитаемости.', 950, 1000, 3, 'h4'), " +
                    "(5, 2, 'e', 'https://news.example/e', '🚀🚀🚀', NULL, 950, 1000, 0, NULL)",
            )
            v1.execSQL(
                "INSERT INTO article_fts (rowid, title, summary, author, body) VALUES " +
                    "(4, 'Учёные обнаружили новую экзопланету', 'В зоне обитаемости.', '', ''), (5, '🚀🚀🚀', '', '', '')",
            )
        }
        val db = Room.databaseBuilder<MorningIssueDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*Migrations.ALL)
            .build()
        helper.closeWhenFinished(db)
        val dao = db.articleSignalDao()
        assertEquals(0, dao.traitsCount())
        assertEquals(0, dao.embeddingCount())
        val upkeep = SignalsUpkeep(TraitsBackfill(db), EmbeddingBackfill(db, embedder = { EmbeddingFixtures.embedder }))

        // What runs after the first sync, or a few seconds after the app starts.
        assertEquals(SignalsUpkeep.Result(traits = 5, embedded = 3, marked = 2), upkeep.run())

        val all = listOf(1L, 2L, 3L, 4L, 5L)
        val signals = dao.signalsFor(all, StaticEmbedder.MODEL_ID).associateBy { it.articleId }
        assertEquals(all.toSet(), signals.keys)
        assertTrue(signals.values.all { it.traits?.traitsVersion == net.tgnra.morningissue.model.ArticleTraitRules.VERSION })
        // Every embeddable post has a vector; it is the one the embedder gives its stored text.
        for (id in listOf(1L, 2L, 3L)) assertEquals(StaticEmbedder.DIMENSIONS, signals.getValue(id).embedding!!.size)
        assertEquals(
            StaticEmbedder.toBytes(EmbeddingFixtures.embedder.embed("Links: something good\nRead this piece on marmalade.")!!).toList(),
            signals.getValue(1).embedding!!.toList(),
        )
        // The two that cannot be embedded are marked, so that they are not tried again.
        assertEquals(StoredEmbedding.NON_LATIN, StoredEmbedding.markOf(signals.getValue(4).embedding!!))
        assertEquals(StoredEmbedding.NO_KNOWN_WORDS, StoredEmbedding.markOf(signals.getValue(5).embedding!!))
        assertEquals(0, dao.countWithoutEmbedding(StaticEmbedder.MODEL_ID))
        assertEquals(0, dao.articlesNeedingTraits(net.tgnra.morningissue.model.ArticleTraitRules.VERSION, 0, 10).size)

        // A second pass finds nothing to do and changes nothing.
        assertEquals(SignalsUpkeep.Result(), upkeep.run())
        assertEquals(5, dao.traitsCount())
        assertEquals(5, dao.embeddingCount())
        assertEquals(signals.getValue(1).embedding!!.toList(), dao.getEmbedding(1)!!.vector.toList())
    }

    /** What version 3 must hold after an upgrade: every feed subscribed, the discovery tables empty and usable. */
    private fun assertVersion3(db: SQLiteConnection) {
        for ((table, rows) in listOf("feed" to 2L, "article" to 3L, "engagement" to 1L, "edition_item" to 1L, "feed_health" to 1L)) {
            assertEquals(table, rows, db.long("SELECT COUNT(*) FROM $table"))
        }
        assertEquals(2L, db.long("SELECT COUNT(*) FROM feed WHERE status = 'SUBSCRIBED' AND discoveredAt IS NULL"))
        assertEquals("A blog", db.text("SELECT title FROM feed WHERE id = 1"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM discovery_verdict"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM discovery_candidate"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_discovery_verdict_decidedAt'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_discovery_candidate_fetchedAt'"))
        // A feed added after the upgrade without a status is a subscription, as the column's default says.
        db.execSQL("INSERT INTO feed (id, url, title, tier, affinity, sliderStep, notify, addedAt) VALUES (3, 'https://new.example/feed', 'New', 'WEEKLY', 0.5, 'AUTO', 0, 2000)")
        assertEquals("SUBSCRIBED", db.text("SELECT status FROM feed WHERE id = 3"))
        db.execSQL("INSERT INTO discovery_verdict (urlKey, url, verdict, decidedAt) VALUES ('k', 'https://k.example/feed', 'PASSED', 3000)")
        db.execSQL("INSERT INTO discovery_candidate (urlKey, url, source, foundAt) VALUES ('c', 'https://c.example/feed', 'BLOGROLL', 3000)")
        assertEquals(1L, db.long("SELECT mentions FROM discovery_candidate WHERE urlKey = 'c'"))
    }

    @Test
    fun version2To3MarksEveryFeedSubscribedAndAddsTheDiscoveryTables() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).close()
        // Validated against 3.json.
        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3)).use(::assertVersion3)
    }

    @Test
    fun version1To3RunsBothSteps() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)).use { db ->
            assertVersion3(db)
            assertEquals(0L, db.long("SELECT COUNT(*) FROM feed WHERE etag IS NOT NULL"))
        }
    }

    /** What version 4 must hold after an upgrade: every edition from before it untracked, the page-view table empty and usable. */
    private fun assertVersion4(db: SQLiteConnection) {
        assertVersion3(db)
        assertEquals(1L, db.long("SELECT COUNT(*) FROM edition WHERE pageViewsTracked = 0"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM edition_page_view"))
        // An edition stored after the upgrade without the flag is untracked, as the column's default says.
        db.execSQL("INSERT INTO edition (id, date, kind, cutoffAt, releaseAt, builtAt, length, number) VALUES (2, 20768, 'MORNING', 2000, 2000, 1990, 'STANDARD', 2)")
        assertEquals(0L, db.long("SELECT pageViewsTracked FROM edition WHERE id = 2"))
        db.execSQL("INSERT INTO edition_page_view (editionId, page, msVisible, firstSeenAt) VALUES (1, 1, 6000, 2100)")
        assertEquals(6000L, db.long("SELECT msVisible FROM edition_page_view WHERE editionId = 1 AND page = 1"))
        // Page views go with their edition.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM edition WHERE id = 1")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM edition_page_view"))
    }

    @Test
    fun version3To4AddsPageViewsAndLeavesEveryOldEditionUntracked() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)).close()
        // Validated against 4.json.
        helper.runMigrationsAndValidate(4, listOf(Migrations.MIGRATION_3_4)).use(::assertVersion4)
    }

    @Test
    fun version1To4RunsEveryStep() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(4, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)).use { db ->
            assertVersion4(db)
            assertEquals(0L, db.long("SELECT COUNT(*) FROM feed WHERE etag IS NOT NULL"))
        }
    }

    @Test
    fun runsFromBeforeTheUpgradeStillCountAsSeenAndNewOnesNeedTheirPageOnScreen() = runBlocking<Unit> {
        helper.createDatabase(1).use { v1 ->
            seedVersion1(v1)
            // An untouched story on the old edition's second page, which the reader may never have turned to.
            v1.execSQL("INSERT INTO edition_item (editionId, articleId, page, slot, kind, score, reason) VALUES (1, 2, 2, 0, 'TOP', 1.0, NULL)")
        }
        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)).close()
        val db = Room.databaseBuilder<MorningIssueDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*Migrations.ALL)
            .build()
        helper.closeWhenFinished(db)
        val editions = db.editionDao()

        // Before the upgrade nothing recorded pages: every run counts, as it always did.
        val before = editions.getRunsSince(Instant.EPOCH)
        assertEquals(2, before.size)
        assertTrue(before.all { it.seen })

        // An edition built after it: the front page was up for six seconds in all, page 2 for two.
        val at = Instant.ofEpochMilli(5_000)
        val id = editions.insertEditionWithItems(
            EditionEntity(
                date = LocalDate.ofEpochDay(20768), kind = EditionKind.MORNING, cutoffAt = at, releaseAt = at, builtAt = at,
                length = EditionLength.STANDARD, number = 2, pageViewsTracked = true,
            ),
            listOf(
                EditionItemEntity(0, 3, page = 1, slot = 0, kind = ItemKind.LEAD, score = 1.0),
                EditionItemEntity(0, 2, page = 2, slot = 0, kind = ItemKind.TOP, score = 1.0),
            ),
        )
        editions.recordPageView(id, 1, 4_000, at)
        editions.recordPageView(id, 1, 2_000, at.plusSeconds(60))
        editions.recordPageView(id, 2, 2_000, at)
        assertEquals(listOf(6_000L, 2_000L), editions.getPageViews(id).map { it.msVisible })
        assertEquals(at, editions.getPageViews(id).first().firstSeenAt)
        val runs = editions.getRunsSince(Instant.EPOCH).filter { it.editionId == id }.associate { it.articleId to it.seen }
        assertEquals(mapOf(3L to true, 2L to false), runs)
        assertEquals(listOf(3L), editions.seenRuns(listOf(id), 5_000))

        // Only the story the reader saw is passed over.
        db.engagementDao().countUnopenedRun(id)
        assertEquals(1, db.engagementDao().get(3)!!.editionsRunUnopened)
        assertEquals(0, db.engagementDao().get(2)!!.editionsRunUnopened)
    }

    /** What version 5 must hold after an upgrade: everything version 4 held, and no paired Kobo. */
    private fun assertVersion5(db: SQLiteConnection) {
        assertEquals(0L, db.long("SELECT COUNT(*) FROM sync_peer"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM edition WHERE pageViewsTracked = 0"))
        assertEquals(1200L, db.long("SELECT likedAt FROM engagement WHERE articleId = 1"))
        // A peer paired after the upgrade starts at ackedSeq 0, as the column's default says, and has not synced.
        db.execSQL("INSERT INTO sync_peer (deviceId, name, tokenHash, pairedAt) VALUES ('b7e2', 'Kobo Libra 2', 'ab', 3000)")
        assertEquals(0L, db.long("SELECT ackedSeq FROM sync_peer WHERE deviceId = 'b7e2'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sync_peer WHERE lastSyncAt IS NULL"))
    }

    @Test
    fun version4To5AddsAnEmptyPeerTable() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(4, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)).close()
        // Validated against 5.json.
        helper.runMigrationsAndValidate(5, listOf(Migrations.MIGRATION_4_5)).use(::assertVersion5)
    }

    @Test
    fun version1To5RunsEveryStep() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(5, Migrations.ALL.toList()).use { db ->
            assertVersion5(db)
            assertEquals(2L, db.long("SELECT COUNT(*) FROM feed WHERE status = 'SUBSCRIBED'"))
        }
    }

    @Test
    fun anUpgradedDatabaseCanPairAKobo() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(4, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)).close()
        val db = Room.databaseBuilder<MorningIssueDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*Migrations.ALL)
            .build()
        helper.closeWhenFinished(db)
        val peers = db.syncPeerDao()
        assertTrue(peers.all().isEmpty())
        peers.pair("b7e2", "Kobo Libra 2", "ab", Instant.ofEpochMilli(3_000))
        peers.setAcked("b7e2", 41, Instant.ofEpochMilli(4_000))
        // Pairing again replaces the token and keeps the acknowledged events.
        peers.pair("b7e2", "Kobo Libra 2", "cd", Instant.ofEpochMilli(5_000))
        val peer = peers.get("b7e2")!!
        assertEquals("cd", peer.tokenHash)
        assertEquals(41L, peer.ackedSeq)
        assertEquals(Instant.ofEpochMilli(5_000), peer.pairedAt)
        assertTrue(peers.articleExists(1))
    }

    /** What version 6 must hold after an upgrade: every paired Kobo kept as it was, and none paired for relay sync. */
    private fun assertVersion6(db: SQLiteConnection) {
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sync_peer"))
        assertEquals("ab", db.text("SELECT tokenHash FROM sync_peer WHERE deviceId = 'b7e2'"))
        assertEquals(41L, db.long("SELECT ackedSeq FROM sync_peer WHERE deviceId = 'b7e2'"))
        assertEquals(3000L, db.long("SELECT pairedAt FROM sync_peer WHERE deviceId = 'b7e2'"))
        assertEquals(4000L, db.long("SELECT lastSyncAt FROM sync_peer WHERE deviceId = 'b7e2'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM sync_peer WHERE relayAuthKey IS NULL AND relayEncKey IS NULL AND relayWidth IS NULL AND relayPairedAt IS NULL"))
        // A device paired only through a relay has no token.
        db.execSQL("INSERT INTO sync_peer (deviceId, name, pairedAt, relayWidth, relayAuthKey, relayEncKey, relayPairedAt) VALUES ('c9f1', 'Kobo Clara', 5000, 1072, 'a', 'e', 5000)")
        assertNull(db.text("SELECT tokenHash FROM sync_peer WHERE deviceId = 'c9f1'"))
        assertEquals(0L, db.long("SELECT ackedSeq FROM sync_peer WHERE deviceId = 'c9f1'"))
    }

    private fun pairAtVersion5(db: SQLiteConnection) {
        db.execSQL("INSERT INTO sync_peer (deviceId, name, tokenHash, ackedSeq, pairedAt, lastSyncAt) VALUES ('b7e2', 'Kobo Libra 2', 'ab', 41, 3000, 4000)")
    }

    @Test
    fun version5To6KeepsEveryPeerAndAddsTheRelayColumns() = runBlocking<Unit> {
        helper.createDatabase(5).use(::pairAtVersion5)
        // Validated against 6.json.
        helper.runMigrationsAndValidate(6, listOf(Migrations.MIGRATION_5_6)).use(::assertVersion6)
    }

    @Test
    fun version1To6RunsEveryStep() = runBlocking<Unit> {
        helper.createDatabase(1).use(::seedVersion1)
        helper.runMigrationsAndValidate(5, listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5))
            .use(::pairAtVersion5)
        helper.runMigrationsAndValidate(6, listOf(Migrations.MIGRATION_5_6)).use(::assertVersion6)
        helper.runMigrationsAndValidate(6, emptyList()).use { db ->
            assertEquals(2L, db.long("SELECT COUNT(*) FROM feed WHERE status = 'SUBSCRIBED'"))
            assertEquals(1200L, db.long("SELECT likedAt FROM engagement WHERE articleId = 1"))
        }
    }

    @Test
    fun anUpgradedKoboPairsForRelaySyncAndKeepsItsAcknowledgedEvents() = runBlocking<Unit> {
        helper.createDatabase(5).use(::pairAtVersion5)
        val db = Room.databaseBuilder<MorningIssueDatabase>(context, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*Migrations.ALL)
            .build()
        helper.closeWhenFinished(db)
        val peers = db.syncPeerDao()
        peers.pairRelay("b7e2", "Kobo Libra 2", 1264, "auth", "enc", Instant.ofEpochMilli(6_000))
        var peer = peers.get("b7e2")!!
        assertTrue(peer.isLanPaired && peer.isRelayPaired)
        assertEquals(41L, peer.ackedSeq)
        // Forgetting one pairing keeps the other, and the row with its ackedSeq.
        peers.forget("b7e2")
        peer = peers.get("b7e2")!!
        assertNull(peer.tokenHash)
        assertTrue(peer.isRelayPaired)
        assertEquals(41L, peer.ackedSeq)
        assertEquals(listOf("b7e2"), peers.relayPeers().map { it.deviceId })
        // Scanning again replaces the keys and keeps ackedSeq.
        peers.pairRelay("b7e2", "Kobo Libra 2 (new name)", 1072, "auth2", "enc2", Instant.ofEpochMilli(7_000))
        peer = peers.get("b7e2")!!
        assertEquals("auth2", peer.relayAuthKey)
        assertEquals(1072, peer.relayWidth)
        assertEquals(41L, peer.ackedSeq)
        // Unpaired both ways, the row goes.
        peers.forgetRelay("b7e2")
        assertNull(peers.get("b7e2"))
    }

    @Test
    fun aFreshInstallIsCreatedAtTheCurrentVersion() = runBlocking<Unit> {
        // Version 6 built directly must be what the migrations arrive at; validation compares both with 6.json.
        helper.createDatabase(6).use { db ->
            assertEquals(0L, db.long("SELECT COUNT(*) FROM edition_page_view"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM sync_peer"))
        }
        helper.runMigrationsAndValidate(6, emptyList()).close()
    }
}

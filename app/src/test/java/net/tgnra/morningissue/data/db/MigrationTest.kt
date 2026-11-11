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
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.signals.TraitsBackfill
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EnclosureKind
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
        helper.createDatabase(1).use(::seedVersion1)

        // Runs the step and compares the resulting tables with the exported version 2 schema.
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { db ->
            for ((table, rows) in listOf(
                "feed" to 2L, "tag" to 1L, "feed_tag" to 1L, "article" to 3L, "article_content" to 1L, "article_fts" to 3L,
                "engagement" to 1L, "edition" to 1L, "edition_item" to 1L, "feed_health" to 1L,
            )) {
                assertEquals(table, rows, db.long("SELECT COUNT(*) FROM $table"))
            }
            for (table in listOf("article_category", "article_traits", "article_embedding")) {
                assertEquals(table, 0L, db.long("SELECT COUNT(*) FROM $table"))
            }
            assertEquals(1L, db.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_article_category_key'"))

            // Every feed is fetched in full next time; nothing else about it changed.
            assertEquals(0L, db.long("SELECT COUNT(*) FROM feed WHERE etag IS NOT NULL OR lastModified IS NOT NULL"))
            assertEquals("A blog", db.text("SELECT title FROM feed WHERE id = 1"))
            assertEquals("FAVOURITE", db.text("SELECT sliderStep FROM feed WHERE id = 1"))
            assertEquals(1000L, db.long("SELECT lastSyncAt FROM feed WHERE id = 1"))
            assertEquals(800L, db.long("SELECT CAST(affinity * 1000 AS INTEGER) FROM feed WHERE id = 1"))

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
        db.feedDao().delete(1)
        assertEquals(0, dao.categoryCount())
        assertEquals(0, dao.embeddingCount())
        assertEquals(1, dao.traitsCount())
    }

    @Test
    fun aFreshInstallIsCreatedAtTheCurrentVersion() = runBlocking<Unit> {
        // Version 2 built directly must be what the migration arrives at; validation compares both with 2.json.
        helper.createDatabase(2).use { db ->
            assertEquals(0L, db.long("SELECT COUNT(*) FROM article_traits"))
        }
        helper.runMigrationsAndValidate(2, emptyList()).close()
    }
}

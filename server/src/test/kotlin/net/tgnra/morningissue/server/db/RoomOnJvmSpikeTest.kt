package net.tgnra.morningissue.server.db

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase-1 spike (`docs/server-design.md`, section 2.1): Room 3 on the JVM with the bundled
 * SQLite runs the server's schema, FTS4 `MATCH` and `snippet()` included; a file database opens
 * in WAL mode; and the native library is extracted under the configured `java.io.tmpdir`
 * (`server/build.gradle.kts` points the test JVM at `build/tmp/test-jvm`).
 */
class RoomOnJvmSpikeTest {
    private val opened = mutableListOf<ServerDatabase>()
    private val dirs = mutableListOf<File>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private suspend fun ServerDatabase.exec(vararg sql: String) = useWriterConnection { connection ->
        sql.forEach { connection.executeSQL(it) }
    }

    private suspend fun ServerDatabase.strings(sql: String): List<String> = useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    }

    private suspend fun ServerDatabase.long(sql: String): Long = useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            check(statement.step())
            statement.getLong(0)
        }
    }

    private suspend fun ServerDatabase.seedFeedAndArticles() = exec(
        "INSERT INTO feed (id, url, url_key, title, tier, addedAt) VALUES (1, 'https://a.example/feed', 'a.example/feed', 'A', 'DAILY', 0)",
        "INSERT INTO article (id, feedId, guid, title, publishedAt, firstSeenAt) VALUES (10, 1, 'g1', 'Ferries of the north', 0, 0)",
        "INSERT INTO article (id, feedId, guid, title, publishedAt, firstSeenAt) VALUES (11, 1, 'g2', 'Lighthouses', 0, 0)",
        "INSERT INTO article_fts (rowid, title, summary, author, body) VALUES " +
            "(10, 'Ferries of the north', '', '', 'The morning ferry leaves the harbour at six and crosses to the island.')",
        "INSERT INTO article_fts (rowid, title, summary, author, body) VALUES " +
            "(11, 'Lighthouses', '', '', 'A keeper climbs the stair every evening.')",
    )

    @Test
    fun inMemoryDatabaseRunsFts4MatchAndSnippet() = runBlocking {
        val db = ServerDatabase.inMemory().also(opened::add)
        db.seedFeedAndArticles()

        val hits = db.strings("SELECT CAST(rowid AS TEXT) FROM article_fts WHERE article_fts MATCH 'harb*'")
        assertEquals(listOf("10"), hits)
        val snippet = db.strings(
            "SELECT snippet(article_fts, '[', ']', '…', -1, 8) FROM article_fts WHERE article_fts MATCH 'harbour'",
        ).single()
        assertTrue(snippet, "[harbour]" in snippet)
        // The FTS rows join back to the ordinary table.
        val titles = db.strings(
            "SELECT a.title FROM article a JOIN article_fts f ON f.rowid = a.id WHERE article_fts MATCH 'keeper'",
        )
        assertEquals(listOf("Lighthouses"), titles)
    }

    @Test
    fun foreignKeysCascadeFromAUser() = runBlocking {
        val db = ServerDatabase.inMemory().also(opened::add)
        db.seedFeedAndArticles()
        db.exec(
            "INSERT INTO users (id, username, password_hash, db_id, created_at) VALUES (1, 'tania', 'x', 'db', 0)",
            "INSERT INTO subscription (user_id, feed_id, added_at) VALUES (1, 1, 0)",
            "INSERT INTO engagement (user_id, articleId, openedAt) VALUES (1, 10, 5)",
        )
        db.exec("DELETE FROM users WHERE id = 1")
        assertEquals(0L, db.long("SELECT COUNT(*) FROM subscription"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM engagement"))
        // Global rows stay.
        assertEquals(2L, db.long("SELECT COUNT(*) FROM article"))
    }

    @Test
    fun fileDatabaseOpensInWalMode() = runBlocking {
        val dir = Files.createTempDirectory("spike-db").toFile().also(dirs::add)
        val file = File(dir, ServerDatabase.FILE_NAME)
        val db = ServerDatabase.open(file).also(opened::add)
        db.seedFeedAndArticles()

        assertEquals("wal", db.strings("PRAGMA journal_mode").single().lowercase())
        assertTrue(file.exists())
        assertTrue("the -wal file", File(dir, "${ServerDatabase.FILE_NAME}-wal").exists())
        db.close()
        opened.remove(db)

        // Reopened, the data is there.
        val again = ServerDatabase.open(file).also(opened::add)
        assertEquals(2L, again.long("SELECT COUNT(*) FROM article_fts WHERE article_fts MATCH 'the'"))
    }

    @Test
    fun theNativeLibraryIsExtractedUnderTheConfiguredTmpdir() = runBlocking {
        val tmp = File(System.getProperty("java.io.tmpdir")).canonicalFile
        assertTrue("the build sets java.io.tmpdir: $tmp", tmp.path.endsWith("tmp/test-jvm"))
        ServerDatabase.inMemory().also(opened::add).exec("SELECT 1")

        val extracted = tmp.listFiles().orEmpty().filter { "sqliteJni" in it.name }
        assertTrue("libsqliteJni under $tmp: ${tmp.list()?.toList()}", extracted.isNotEmpty())
    }
}

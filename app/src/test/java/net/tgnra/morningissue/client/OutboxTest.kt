package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.kobo.SyncEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The outbox's `seq` is the event's number on the server, which skips any at or below what it
 * acknowledged: so it must only ever go up, across restarts and cache resets.
 */
@RunWith(AndroidJUnit4::class)
class OutboxTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var dbFile: File
    private val kits = mutableListOf<ClientKit>()

    @Before
    fun setUp() {
        server = FakeServer().start()
        dbFile = File(folder.root, "cache.db")
    }

    @After
    fun tearDown() {
        kits.forEach { it.close() }
        server.stop()
    }

    /** The app started afresh on the same database file: a new database instance (and new stores). */
    private fun start(): ClientKit = ClientKit(server, folder.newFolder(), databaseFile = dbFile).also { kits += it }

    private fun event(type: String = "opened") = SyncEvent(seq = 0, type = type, articleId = 100, at = "2026-11-11T07:00:00Z")

    @Test
    fun numbersKeepClimbingAcrossARestart() = runBlocking<Unit> {
        val first = start()
        assertEquals(listOf(1L, 2L, 3L), List(3) { first.outbox.append(event()) })
        // Acknowledged and deleted: the table is empty, but AUTOINCREMENT remembers.
        first.outbox.deleteThrough(3)
        assertEquals(0L, first.outbox.through())
        first.close()
        kits.remove(first)

        val second = start()
        assertEquals(4L, second.outbox.append(event()))
        assertEquals(4L, second.outbox.through())
        assertEquals(listOf(4L), second.outbox.pending().map { it.seq })
    }

    @Test
    fun aCacheResetStartsAboveWhatTheServerAcknowledged() = runBlocking<Unit> {
        val kit = start()
        repeat(5) { kit.outbox.append(event()) }
        kit.auth.acked(5)
        // Emptying every table resets the table's own counter...
        kit.database.clearAllTables()
        // ...but the next event is still numbered above what the server has.
        assertEquals(6L, kit.outbox.append(event()))
        assertEquals(7L, kit.outbox.append(event()))
    }

    @Test
    fun anEventKeepsItsFieldsAndGetsItsNumber() = runBlocking<Unit> {
        val kit = start()
        kit.outbox.append(event("liked").copy(value = kotlinx.serialization.json.JsonPrimitive(true)))
        val stored = kit.outbox.pending().single()
        assertEquals(1L, stored.seq)
        assertEquals("liked", stored.type)
        assertEquals(100L, stored.articleId)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), stored.value)
    }

    @Test
    fun theUploadGoesInBatchesAndDropsWhatWasAcknowledged() = runBlocking<Unit> {
        val kit = start()
        kit.loggedIn()
        repeat(1_200) { kit.outbox.append(event()) }

        val acked = kit.connection.call { kit.uploader.flush(it) }

        assertEquals(1_200L, acked)
        assertEquals(listOf(500, 500, 200), server.requestsTo("/sync/events").map { Regex("\"seq\"").findAll(it.body!!.utf8()).count() })
        assertEquals(0, kit.outbox.count())
        assertEquals(1_200L, kit.auth.current().ackedSeq)
    }
}

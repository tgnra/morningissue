package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EngagementDto
import net.tgnra.morningissue.api.client.ApiException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** [ServerSync] against a [FakeServer] (`docs/server-design.md`, 5.2). */
@RunWith(AndroidJUnit4::class)
class ServerSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var kit: ClientKit

    @Before
    fun setUp() = runBlocking {
        server = FakeServer().start()
        kit = ClientKit(server, folder.root)
        kit.loggedIn()
    }

    @After
    fun tearDown() {
        kit.close()
        server.stop()
    }

    private val db get() = kit.database

    @Test
    fun aSyncFillsTheCacheWithTheFeedsTheEditionsAndTheirStories() = runBlocking<Unit> {
        server.publish(1, listOf(100, 101), liked = setOf(101))

        val outcome = kit.sync.sync()

        assertTrue("$outcome", outcome is SyncOutcome.Synced)
        assertEquals(listOf(1L), db.cacheDao().feedIds())
        assertEquals(1, db.editionDao().count())
        assertEquals(listOf(100L, 101L), db.editionDao().getItems(1).map { it.row.article.id })
        assertNotNull(db.articleDao().getContent(100))
        assertNotNull(db.engagementDao().get(101)!!.likedAt)
        assertEquals(mapOf(1L to "v1-1"), kit.auth.current().editionVersions)
        assertNotNull(kit.auth.current().lastSyncAt)
        // Every gated request said how far the outbox goes.
        assertEquals("0", server.requestsTo("/sync/manifest").single().headers[EVENTS_THROUGH_HEADER])
    }

    @Test
    fun aBundleIsFetchedAgainOnlyWhenItsVersionChanges() = runBlocking<Unit> {
        server.publish(1, listOf(100))
        server.publish(2, listOf(101))
        kit.sync.sync()
        assertEquals(1, server.requestsTo("/editions/1/bundle").size)
        assertEquals(1, server.requestsTo("/editions/2/bundle").size)

        kit.sync.sync()
        assertEquals("unchanged: not fetched", 1, server.requestsTo("/editions/1/bundle").size)

        // Edition 2 changed on the server (a Kobo finished it, a story's text arrived).
        server.publish(2, listOf(101, 102), version = "v2-2")
        kit.sync.sync()
        assertEquals(1, server.requestsTo("/editions/1/bundle").size)
        assertEquals(2, server.requestsTo("/editions/2/bundle").size)
        assertEquals(listOf(101L, 102L), db.editionDao().getItems(2).map { it.row.article.id })
        assertEquals("v2-2", kit.auth.current().editionVersions[2])
    }

    @Test
    fun theSavedShelfIsFetchedWhenItsVersionChanges() = runBlocking<Unit> {
        kit.sync.sync()
        assertEquals(1, server.requestsTo("/saved").size)
        kit.sync.sync()
        assertEquals(1, server.requestsTo("/saved").size)
        server.saved = server.saved.copy(version = "saved-1", order = listOf(200), articles = mapOf("200" to FakeServer.article(200)))
        kit.sync.sync()
        assertEquals(2, server.requestsTo("/saved").size)
        assertNotNull(db.articleDao().getById(200))
    }

    @Test
    fun theOutboxGoesUpFirstAndItsEventsAreDeletedOnceAcknowledged() = runBlocking<Unit> {
        server.publish(1, listOf(100))
        kit.sync.sync()
        kit.engagement.setLiked(100, true)
        kit.engagement.markEditionOpened(1)
        assertEquals(2, kit.outbox.count())

        kit.sync.sync()

        assertEquals(listOf("liked", "editionOpened"), server.events.map { it.type })
        assertEquals(listOf(1L, 2L), server.events.map { it.seq })
        assertEquals(0, kit.outbox.count())
        assertEquals(2L, kit.auth.current().ackedSeq)
        assertEquals("2", server.requestsTo("/sync/manifest").last().headers[EVENTS_THROUGH_HEADER])
    }

    @Test
    fun eventsPendingUploadsAgainAndAsksAgain() = runBlocking<Unit> {
        server.publish(1, listOf(100))
        kit.sync.sync()
        kit.engagement.setLiked(100, true)
        // The first upload is not taken (the server is still merging another request).
        server.ignoreEvents = 1

        val outcome = kit.sync.sync()

        assertTrue("$outcome", outcome is SyncOutcome.Synced)
        val manifests = server.requestsTo("/sync/manifest").drop(1)
        assertEquals("refused once, then answered", 2, manifests.size)
        assertEquals(listOf("liked"), server.events.map { it.type })
        assertEquals(0, kit.outbox.count())
    }

    @Test
    fun theGateIsTriedAtMostThreeTimes() = runBlocking<Unit> {
        server.publish(1, listOf(100))
        kit.sync.sync()
        kit.engagement.setLiked(100, true)
        server.ignoreEvents = 100

        val outcome = kit.sync.sync()

        assertTrue("$outcome", outcome is SyncOutcome.Failed && outcome.error is ApiException.EventsPending)
        assertEquals(3, server.requestsTo("/sync/manifest").drop(1).size)
        // The event is kept for the next try.
        assertEquals(1, kit.outbox.count())
    }

    @Test
    fun aNewServerDatabaseWipesTheCacheAndStartsAgain() = runBlocking<Unit> {
        server.publish(1, listOf(100))
        kit.sync.sync()
        assertEquals("db-1", kit.auth.current().dbId)

        // The server's data was reset: other editions, other IDs.
        server.dbId = "db-2"
        server.editions.clear()
        server.versions.clear()
        server.publish(5, listOf(500))

        val outcome = kit.sync.sync()

        assertTrue("$outcome", outcome is SyncOutcome.Synced)
        assertEquals(1, kit.wipes)
        assertEquals("db-2", kit.auth.current().dbId)
        assertNull(db.editionDao().getById(1))
        assertNull(db.articleDao().getById(100))
        assertEquals(listOf(500L), db.editionDao().getItems(5).map { it.row.article.id })
        assertEquals(mapOf(5L to "v1-5"), kit.auth.current().editionVersions)
    }

    @Test
    fun whatTheReaderDidDuringASyncStaysVisibleOverTheServersEngagement() = runBlocking<Unit> {
        server.publish(1, listOf(100, 101))
        kit.sync.sync()
        // The server's engagement delta: a Kobo read 101; 100 is untouched there.
        server.delta = EngagementDeltaResponse(
            cursor = 7,
            engagement = listOf(EngagementDto(100), EngagementDto(101, readAt = FakeServer.T0, readFraction = 1f)),
            editions = emptyList(),
        )
        // The reader Likes 100 while the sync is running, after the outbox went up.
        server.onEngagement = { runBlocking { kit.engagement.setLiked(100, true) } }

        kit.sync.sync()

        // The server's state is applied, and the Like the server has not seen yet is put back over it.
        assertNotNull(db.engagementDao().get(101)!!.readAt)
        assertNotNull(db.engagementDao().get(100)!!.likedAt)
        assertEquals(1, kit.outbox.count())
        assertEquals(7L, kit.auth.current().engagementCursor)

        // The next sync sends it.
        server.onEngagement = {}
        kit.sync.sync()
        assertEquals(listOf("liked"), server.events.map { it.type })
        assertEquals("7", server.requestsTo("/engagement").last().url.queryParameter("since"))
    }

    @Test
    fun offlineIsAnOutcomeNotAnError() = runBlocking<Unit> {
        server.stop()
        assertEquals(SyncOutcome.Offline, kit.sync.sync())
    }

    @Test
    fun notLoggedInHasNothingToSync() = runBlocking<Unit> {
        kit.auth.signedOut()
        assertEquals(SyncOutcome.NotReady, kit.sync.sync())
        assertTrue(server.requests.isEmpty())
    }
}

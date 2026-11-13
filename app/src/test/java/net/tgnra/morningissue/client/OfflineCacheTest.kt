package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.model.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Once synced, the phone reads from its cache with the server gone, and what the reader does waits in the outbox. */
@RunWith(AndroidJUnit4::class)
class OfflineCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var kit: ClientKit

    @Before
    fun setUp() = runBlocking {
        server = FakeServer().start()
        kit = ClientKit(server, folder.root)
        kit.loggedIn()
        server.publish(1, listOf(100, 101))
        assertTrue(kit.sync.sync() is SyncOutcome.Synced)
        // The connection goes.
        server.stop()
    }

    @After
    fun tearDown() {
        kit.close()
    }

    @Test
    fun theEditionAndItsStoriesOpenFromTheCache() = runBlocking<Unit> {
        val stand = withTimeout(5_000) { kit.editions.observeReleasedEditions().first() }
        assertEquals(listOf(1L), stand.map { it.id })
        val edition = withTimeout(5_000) { kit.editions.observeEdition(1).first { it != null } }!!
        assertTrue(edition.pages.isNotEmpty())

        val article = withTimeout(5_000) { kit.articles.observeReaderArticle(100).first { it != null } }!!
        assertEquals("Story 100", article.title)
        // The body is the cached one; asking the server for the page fails quietly.
        assertTrue(kit.articles.ensureFullText(100))
    }

    @Test
    fun searchFallsBackToTheCachesIndex() = runBlocking<Unit> {
        val found = kit.articles.search(SearchQuery("quince"))
        assertEquals(setOf(100L, 101L), found.map { it.articleId }.toSet())
    }

    @Test
    fun readingOfflineIsKeptForTheNextSync() = runBlocking<Unit> {
        kit.articles.markOpened(100)
        kit.articles.setLiked(100, true)
        kit.editions.markEditionOpened(1)

        assertNotNull(kit.database.engagementDao().get(100)!!.likedAt)
        assertNotNull(kit.database.editionDao().getById(1)!!.openedAt)
        assertEquals(SyncOutcome.Offline, kit.sync.sync())
        assertEquals(listOf("opened", "liked", "editionOpened"), kit.outbox.pending().map { it.type })
    }
}

package net.tgnra.morningissue.server.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.SERVER_PROTOCOL
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.editions.TestWorld
import net.tgnra.morningissue.server.service.ApiFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/** The events gate and the manifest (4.2): 400 without the header, 409 while events are pending, then through. */
class SyncGateTest {
    private val world = TestWorld(NOW)
    private val db = world.db
    private val ids: Seeded = runBlocking { SyncTestData.seed(world) }
    private val user = ids.user
    private val device: String = runBlocking { world.addDevice(user, "b7e2", width = 1264) }
    private val sync = SyncServiceImpl(db, EditionBundles(db, world.clock), world.clock, relearn = {})

    @After
    fun tearDown() = world.close()

    private fun refused(block: suspend () -> Unit): ApiFailure = runBlocking {
        try {
            block()
        } catch (e: ApiFailure) {
            return@runBlocking e
        }
        fail("not refused")
        error("unreachable")
    }

    @Test
    fun withoutTheHeaderTheGateAnswers400() {
        val failure = refused { sync.checkGate(user, device, null) }
        assertEquals(ApiError.EVENTS_THROUGH_REQUIRED, failure.code)
        assertEquals(400, failure.status)
    }

    @Test
    fun eventsAboveTheAckedSeqAnswer409WithTheAckedSeq() = runBlocking {
        sync.postEvents(user, device, EventsRequest(listOf(SyncEvent(1, "opened", articleId = ids.lead, at = RELEASE.toString()))))
        val failure = refused { sync.checkGate(user, device, 3) }
        assertEquals(ApiError.EVENTS_PENDING, failure.code)
        assertEquals(409, failure.status)
        assertEquals(1L, failure.acked)
    }

    @Test
    fun afterTheMergeTheGateLetsTheRequestThrough() = runBlocking {
        refused { sync.checkGate(user, device, 2) }
        val response = sync.postEvents(
            user,
            device,
            EventsRequest(
                listOf(
                    SyncEvent(1, "opened", articleId = ids.lead, at = RELEASE.toString()),
                    SyncEvent(2, "liked", articleId = ids.lead, value = JsonPrimitive(true), at = RELEASE.toString()),
                ),
            ),
        )
        assertEquals(2L, response.acked)
        // Through: no failure, at the acked seq or below it.
        sync.checkGate(user, device, 2)
        sync.checkGate(user, device, 0)
        val manifest = sync.manifest(user, device, 3)
        assertEquals(mapOf(device to 2L), manifest.acked)
    }

    @Test
    fun aDeviceOfAnotherUserIsNotLetThrough() = runBlocking {
        val other = world.addUser()
        val theirs = world.addDevice(other, "theirs")
        assertEquals(ApiError.UNKNOWN, refused { sync.checkGate(user, theirs, 0) }.code)
        assertEquals(ApiError.UNKNOWN, refused { sync.manifest(user, theirs, 3) }.code)
        assertEquals(ApiError.UNKNOWN, refused { sync.postEvents(user, theirs, EventsRequest(emptyList())) }.code)
    }

    @Test
    fun theManifestOffersTheNewestReleasedEditionsOfTheUserOnly() = runBlocking {
        val other = world.addUser(dbId = "other-db")
        SyncTestData.seed(world, userId = other)
        val manifest = sync.manifest(user, device, 3)
        assertEquals("3f0c6f2e-6c55-4f7b-9d1e-0a6f2b9e5c11", manifest.dbId)
        assertEquals(SERVER_PROTOCOL, manifest.protocol)
        assertEquals(NOW, manifest.serverTime)
        // Tomorrow's edition is built but not released: never offered.
        assertEquals(listOf(ids.edition, ids.older), manifest.editions.map { it.id })
        assertEquals(64, manifest.editions.first().version.length)
        assertEquals(0, manifest.saved.count)
        assertEquals(listOf(ids.edition), sync.manifest(user, device, 1).editions.map { it.id })
    }

    @Test
    fun aMergedEventChangesTheVersionOfTheEditionItTouches() = runBlocking {
        val before = sync.manifest(user, device, 3)
        sync.postEvents(user, device, EventsRequest(listOf(SyncEvent(1, "saved", articleId = ids.lead, value = JsonPrimitive(true), at = RELEASE.toString()))))
        val after = sync.manifest(user, device, 3)
        assertNotEquals(before.editions[0].version, after.editions[0].version)
        // The older edition does not carry the lead: unchanged.
        assertEquals(before.editions[1].version, after.editions[1].version)
        assertEquals(1, after.saved.count)
        assertNotEquals(before.saved.version, after.saved.version)
    }
}

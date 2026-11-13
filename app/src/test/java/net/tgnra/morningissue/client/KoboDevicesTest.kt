package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.PairKoboRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Pairing a Kobo through the server: the scanned (or pasted) code goes up as it is. */
@RunWith(AndroidJUnit4::class)
class KoboDevicesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var kit: ClientKit

    private val code = "morningissue:pair?v=2&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=b7e2-41aa&n=Kobo%20Libra%202&w=1264"

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

    @Test
    fun aScannedCodeIsPostedAsItsPairingUri() = runBlocking<Unit> {
        val result = kit.koboDevices.pair("  $code\n")

        assertTrue("$result", result is PairResult.Paired)
        assertEquals("Libra 2", (result as PairResult.Paired).device.name)
        val sent = ApiJson.json.decodeFromString<PairKoboRequest>(server.requestsTo("/devices/kobo").single().body!!.utf8())
        assertEquals(code, sent.pairingUri)
    }

    @Test
    fun textThatIsNotACodeNeverReachesTheServer() = runBlocking<Unit> {
        assertEquals(PairResult.NotACode, kit.koboDevices.pair("https://example.org"))
        assertEquals(PairResult.NotACode, kit.koboDevices.pair(KoboDevices.PAIRING_PREFIX))
        assertTrue(server.requestsTo("/devices/kobo").isEmpty())
    }

    @Test
    fun aCodeFromAnOlderKoboIsRefused() = runBlocking<Unit> {
        assertEquals(PairResult.Refused, kit.koboDevices.pair(code.replace("v=2", "v=1")))
    }

    @Test
    fun offlinePairingIsUnreachable() = runBlocking<Unit> {
        server.stop()
        assertEquals(PairResult.Unreachable, kit.koboDevices.pair(code))
        assertNull(kit.koboDevices.list())
    }

    @Test
    fun theDeviceListPutsKobosFirstAndForgetRemovesOne() = runBlocking<Unit> {
        kit.koboDevices.pair(code)

        val devices = kit.koboDevices.list()!!
        assertEquals(listOf(DeviceKind.KOBO, DeviceKind.PHONE), devices.map { it.kind })

        assertTrue(kit.koboDevices.forget("kobo-1"))
        assertEquals(listOf("phone-1"), kit.koboDevices.list()!!.map { it.id })
        assertEquals("DELETE", server.requestsTo("/devices/kobo-1").single().method)
    }
}

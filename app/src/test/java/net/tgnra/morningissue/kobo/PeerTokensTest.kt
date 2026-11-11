package net.tgnra.morningissue.kobo

import java.net.Inet4Address
import java.net.InetAddress
import java.time.Instant
import java.util.Base64
import net.tgnra.morningissue.data.db.SyncPeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PeerTokensTest {
    private fun peer(id: String, token: String) = SyncPeerEntity(id, "Kobo $id", PeerTokens.hash(token), pairedAt = Instant.EPOCH)

    @Test
    fun aTokenIs32RandomBytesInBase64url() {
        val token = PeerTokens.newToken()
        assertEquals(43, token.length)
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        assertEquals(token, token.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(token, PeerTokens.newToken())
    }

    @Test
    fun theHashIsSha256InHex() {
        // SHA-256("abc"), a published test vector.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PeerTokens.hash("abc"))
    }

    @Test
    fun theTokenMatchesItsPeerOnly() {
        val a = peer("a", "token-a")
        val b = peer("b", "token-b")
        assertSame(b, PeerTokens.match("token-b", listOf(a, b)))
        assertSame(a, PeerTokens.match("token-a", listOf(a, b)))
        assertNull(PeerTokens.match("token-c", listOf(a, b)))
        assertNull(PeerTokens.match("token-a".dropLast(1), listOf(a, b)))
        assertNull(PeerTokens.match(null, listOf(a, b)))
        assertNull(PeerTokens.match("token-a", emptyList()))
        // A stored hash that is not one never matches, even the text of itself.
        assertNull(PeerTokens.match("zz", listOf(a.copy(tokenHash = "zz"))))
    }

    @Test
    fun onlyABearerHeaderCarriesAToken() {
        assertEquals("abc", PeerTokens.bearer("Bearer abc"))
        assertEquals("abc", PeerTokens.bearer("bearer  abc "))
        assertNull(PeerTokens.bearer("Basic abc"))
        assertNull(PeerTokens.bearer("Bearer"))
        assertNull(PeerTokens.bearer("Bearer "))
        assertNull(PeerTokens.bearer(null))
    }

    private fun lan(name: String, ip: String, broadcast: String? = null) =
        LanAddress(name, InetAddress.getByName(ip) as Inet4Address, broadcast?.let { InetAddress.getByName(it) as Inet4Address })

    @Test
    fun theAddressShownIsWifiFirstAndNeverMobileData() {
        val wifi = lan("wlan0", "192.168.1.20", "192.168.1.255")
        val hotspot = lan("ap0", "192.168.43.1")
        assertEquals(wifi, LanAddress.choose(listOf(lan("rmnet_data0", "10.12.0.4"), hotspot, wifi, lan("tun0", "10.8.0.2"))))
        assertEquals(hotspot, LanAddress.choose(listOf(lan("rmnet_data0", "10.12.0.4"), hotspot)))
        assertNull(LanAddress.choose(listOf(lan("rmnet_data0", "10.12.0.4"), lan("wlan0", "100.64.3.2"))))
        assertEquals("192.168.1.20", wifi.host)
    }
}

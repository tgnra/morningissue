package net.tgnra.morningissue.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing code a Kobo shows, and the relay address the reader types. */
class RelayPairingUriTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val s = RelayCrypto.b64(secret)

    private fun ok(text: String) = (RelayPairingUri.parse(text) as PairingParse.Ok).pairing
    private fun refused(text: String) = (RelayPairingUri.parse(text) as PairingParse.Refused).problem

    @Test
    fun readsEveryField() {
        val pairing = ok("morningissue:pair?v=1&s=$s&d=b7e2-41aa&n=Kobo%20Libra%202&w=1264")
        assertArrayEquals(secret, pairing.secret)
        assertEquals("b7e2-41aa", pairing.deviceId)
        assertEquals("Kobo Libra 2", pairing.name)
        assertEquals(1264, pairing.width)
    }

    @Test
    fun toleratesOrderSpacesPlusAndCase() {
        val pairing = ok("  MorningIssue:pair?w=1072&n=Kobo+Clara%C2%A0BW&d=c9f1&s=$s&v=1&extra=ignored \n")
        assertEquals("Kobo Clara BW", pairing.name)
        assertEquals(1072, pairing.width)
    }

    @Test
    fun holdsTheWidthToTheImageRange() {
        assertEquals(2048, ok("morningissue:pair?v=1&s=$s&d=a&n=K&w=4000").width)
        assertEquals(160, ok("morningissue:pair?v=1&s=$s&d=a&n=K&w=20").width)
    }

    @Test
    fun refusesAnUnknownVersion() {
        assertEquals(PairingProblem.UNKNOWN_VERSION, refused("morningissue:pair?v=2&s=$s&d=a&n=K&w=1264"))
    }

    @Test
    fun refusesWhatIsNotAPairingCode() {
        assertEquals(PairingProblem.NOT_A_CODE, refused("https://example.com/?v=1"))
        assertEquals(PairingProblem.NOT_A_CODE, refused("morningissue:other?v=1"))
        assertEquals(PairingProblem.NOT_A_CODE, refused("hello"))
        assertEquals(PairingProblem.NOT_A_CODE, refused(""))
    }

    @Test
    fun refusesMissingOrBadFields() {
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?s=$s&d=a&n=K&w=1264"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=AAAA&d=a&n=K&w=1264"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&n=K&w=1264"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&d=a&n=%20&w=1264"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&d=a&n=K&w=wide"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&d=a&n=K"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&d=${"x".repeat(65)}&n=K&w=1264"))
        assertEquals(PairingProblem.BAD_FIELDS, refused("morningissue:pair?v=1&s=$s&d=a&n=%ZZ&w=1264"))
    }

    @Test
    fun relayAddressMustBeHttps() {
        assertEquals(RelayAddress.Parse.Ok("https://relay.example"), RelayAddress.parse(" https://relay.example/ ", allowLocalHttp = false))
        assertEquals(RelayAddress.Parse.Ok("https://relay.example:8443/mi"), RelayAddress.parse("https://relay.example:8443/mi", allowLocalHttp = false))
        assertEquals(RelayAddress.Parse.Ok("https://relay.example"), RelayAddress.parse("relay.example", allowLocalHttp = false))
        assertEquals(RelayAddress.Parse.NotHttps, RelayAddress.parse("http://relay.example", allowLocalHttp = true))
        assertEquals(RelayAddress.Parse.NotHttps, RelayAddress.parse("http://localhost:8080", allowLocalHttp = false))
        assertEquals(RelayAddress.Parse.Ok("http://localhost:8080"), RelayAddress.parse("http://localhost:8080", allowLocalHttp = true))
        assertEquals(RelayAddress.Parse.Ok("http://10.0.2.2:8080"), RelayAddress.parse("http://10.0.2.2:8080", allowLocalHttp = true))
        assertEquals(RelayAddress.Parse.NotHttps, RelayAddress.parse("ftp://relay.example", allowLocalHttp = true))
        assertEquals(RelayAddress.Parse.Empty, RelayAddress.parse("  ", allowLocalHttp = true))
        assertTrue(RelayAddress.parse("https://relay.example/?x=1", allowLocalHttp = false) is RelayAddress.Parse.Invalid)
    }
}

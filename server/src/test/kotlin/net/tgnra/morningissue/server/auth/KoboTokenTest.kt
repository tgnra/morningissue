package net.tgnra.morningissue.server.auth

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kobo token derivation against the vector shared with the Kobo client, and the v2 pairing code. */
class KoboTokenTest {
    private val vector = Json.parseToJsonElement(File("../docs/server-fixtures/kobo-token.json").readText()).jsonObject
    private fun field(name: String) = vector.getValue(name).jsonPrimitive.content

    @Test
    fun derivesTheSharedVector() {
        val secret = Tokens.unb64url(field("secret"))!!
        assertArrayEquals(ByteArray(32) { it.toByte() }, secret)
        assertEquals(field("secretHex"), Tokens.hex(secret))
        assertEquals(Tokens.KOBO_TOKEN_INFO, field("info"))
        val token = Tokens.koboToken(secret)
        assertEquals(field("token"), token)
        assertEquals(field("tokenSha256Hex"), Tokens.hash(token))
    }

    @Test
    fun thePairingCodeInTheVectorGivesTheSameToken() {
        val pairing = (KoboPairingUri.parse(field("pairingUri")) as KoboPairingUri.Parse.Ok).pairing
        assertEquals(field("token"), pairing.token)
        assertEquals("b7e2-41aa", pairing.deviceId)
        assertEquals("Kobo Libra 2", pairing.name)
        assertEquals(1264, pairing.width)
    }

    @Test
    fun hkdfMatchesRfc5869CaseThreeWithAnEmptySalt() {
        // RFC 5869 A.3: IKM 22 x 0x0b, empty salt, empty info, L = 42.
        val okm = Tokens.hkdf(ByteArray(22) { 0x0b }, "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", Tokens.hex(okm))
    }

    @Test
    fun phoneTokensAreRandomAndUnpadded() {
        val a = Tokens.newToken()
        val b = Tokens.newToken()
        assertTrue(a != b)
        assertEquals(43, a.length)
        assertTrue(a.none { it == '=' || it == '+' || it == '/' })
        assertEquals(64, Tokens.hash(a).length)
    }

    private val s = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private fun ok(text: String) = (KoboPairingUri.parse(text) as KoboPairingUri.Parse.Ok).pairing
    private fun refused(text: String) = KoboPairingUri.parse(text) as KoboPairingUri.Parse.Refused

    @Test
    fun toleratesOrderSpacesPlusAndCase() {
        val pairing = ok("  MorningIssue:pair?w=1072&n=Kobo+Clara%C2%A0BW&d=c9f1&s=$s&v=2&extra=ignored \n")
        assertEquals("Kobo Clara\u00A0BW", pairing.name)
        assertEquals(1072, pairing.width)
        assertEquals("c9f1", pairing.deviceId)
    }

    @Test
    fun holdsTheWidthToTheImageRange() {
        assertEquals(2048, ok("morningissue:pair?v=2&s=$s&d=a&n=K&w=4000").width)
        assertEquals(160, ok("morningissue:pair?v=2&s=$s&d=a&n=K&w=20").width)
    }

    @Test
    fun refusesTheRelayVersionAndUnknownOnes() {
        assertTrue(refused("morningissue:pair?v=1&s=$s&d=a&n=K&w=1264").unknownVersion)
        assertTrue(refused("morningissue:pair?v=3&s=$s&d=a&n=K&w=1264").unknownVersion)
    }

    @Test
    fun refusesWhatIsNotACodeOrIsIncomplete() {
        for (text in listOf(
            "", "hello", "https://example.com/?v=2", "morningissue:other?v=2",
            "morningissue:pair?s=$s&d=a&n=K&w=1264",
            "morningissue:pair?v=2&s=AAAA&d=a&n=K&w=1264",
            "morningissue:pair?v=2&s=$s&n=K&w=1264",
            "morningissue:pair?v=2&s=$s&d=a&n=%20&w=1264",
            "morningissue:pair?v=2&s=$s&d=a&n=K&w=wide",
            "morningissue:pair?v=2&s=$s&d=a&n=K",
            "morningissue:pair?v=2&s=$s&d=${"x".repeat(65)}&n=K&w=1264",
            "morningissue:pair?v=2&s=$s&d=a&n=%ZZ&w=1264",
        )) {
            val r = refused(text)
            assertTrue(text, !r.unknownVersion)
        }
    }
}

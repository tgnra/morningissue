package net.tgnra.morningissue.server.auth

import java.net.URLDecoder
import net.tgnra.morningissue.kobo.KoboImagePlan
import net.tgnra.morningissue.kobo.KoboProtocol

/** What a Kobo's pairing code carries: `morningissue:pair?v=2&s=…&d=…&n=…&w=…` (section 6). */
class KoboPairing(
    /** The device secret `S`, 32 bytes. Only the token derived from it is kept, as a hash. */
    val secret: ByteArray,
    val deviceId: String,
    val name: String,
    /** The short side of its screen, held to 160..2048. */
    val width: Int,
) {
    /** The bearer token the Kobo sends. */
    val token: String get() = Tokens.koboToken(secret)
}

/**
 * Reads the text of a Kobo's QR code. It is the relay-era code with `v=2`, parsed the same way:
 * keys in any order, `+` as a space, the first of a repeated key wins, unknown keys ignored.
 */
object KoboPairingUri {
    const val VERSION = "2"

    sealed interface Parse {
        data class Ok(val pairing: KoboPairing) : Parse

        /** [message] is for the person pairing; [unknownVersion] tells an old or new Kobo from a garbled code. */
        data class Refused(val message: String, val unknownVersion: Boolean = false) : Parse
    }

    fun parse(text: String): Parse {
        val trimmed = text.trim()
        val colon = trimmed.indexOf(':')
        if (colon < 0 || !trimmed.substring(0, colon).equals("morningissue", ignoreCase = true)) return notACode()
        val rest = trimmed.substring(colon + 1).removePrefix("//")
        if (rest.substringBefore('?') != "pair" || !rest.contains('?')) return notACode()
        val params = HashMap<String, String>()
        for (part in rest.substringAfter('?').substringBefore('#').split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')) ?: return bad()
            val value = decode(part.substringAfter('=', "")) ?: return bad()
            params.putIfAbsent(key, value)
        }
        val v = params["v"] ?: return bad()
        if (v != VERSION) {
            return Parse.Refused("This pairing code is version $v; this server reads version $VERSION. Update the Kobo's Morning Issue.", unknownVersion = true)
        }
        val secret = params["s"]?.let(Tokens::unb64url)?.takeIf { it.size == Tokens.SECRET_BYTES } ?: return bad()
        val deviceId = params["d"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= KoboProtocol.MAX_DEVICE_ID_CHARS } ?: return bad()
        val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }?.take(KoboProtocol.MAX_NAME_CHARS) ?: return bad()
        val width = params["w"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return bad()
        return Parse.Ok(KoboPairing(secret, deviceId, name, KoboImagePlan.width(width.toString())))
    }

    private fun notACode() = Parse.Refused("That is not a Morning Issue pairing code")

    private fun bad() = Parse.Refused("The pairing code is incomplete or damaged; scan it again")

    private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
}

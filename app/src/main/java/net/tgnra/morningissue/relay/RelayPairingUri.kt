package net.tgnra.morningissue.relay

import java.net.URLDecoder
import net.tgnra.morningissue.kobo.KoboImagePlan
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import net.tgnra.morningissue.kobo.KoboProtocol

/** What a device's pairing code carries: `morningissue:pair?v=1&s=…&d=…&n=…&w=…`. */
class RelayPairing(
    /** The device secret `S`, 32 bytes. */
    val secret: ByteArray,
    val deviceId: String,
    val name: String,
    /** The short side of its screen, held to the range `/v1/images` allows. */
    val width: Int,
)

/** Why a pairing code was refused, for the screen to say. */
enum class PairingProblem { NOT_A_CODE, UNKNOWN_VERSION, BAD_FIELDS }

sealed interface PairingParse {
    data class Ok(val pairing: RelayPairing) : PairingParse
    data class Refused(val problem: PairingProblem) : PairingParse
}

/** Reads the QR code a Kobo shows, or the same text pasted. Pure. */
object RelayPairingUri {
    const val VERSION = "1"

    fun parse(text: String): PairingParse {
        val trimmed = text.trim()
        val colon = trimmed.indexOf(':')
        if (colon < 0 || !trimmed.substring(0, colon).equals("morningissue", ignoreCase = true)) return refused(PairingProblem.NOT_A_CODE)
        val rest = trimmed.substring(colon + 1).removePrefix("//")
        val path = rest.substringBefore('?')
        if (path != "pair" || !rest.contains('?')) return refused(PairingProblem.NOT_A_CODE)
        val query = rest.substringAfter('?').substringBefore('#')
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')) ?: return refused(PairingProblem.BAD_FIELDS)
            val value = decode(part.substringAfter('=', "")) ?: return refused(PairingProblem.BAD_FIELDS)
            // The first of a repeated key wins, so a code cannot be read two ways.
            params.putIfAbsent(key, value)
        }
        if (params["v"] != VERSION) return refused(if (params["v"] == null) PairingProblem.BAD_FIELDS else PairingProblem.UNKNOWN_VERSION)
        val secret = params["s"]?.let(RelayCrypto::unb64)?.takeIf { it.size == RelayCrypto.KEY_BYTES } ?: return refused(PairingProblem.BAD_FIELDS)
        val deviceId = params["d"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= KoboProtocol.MAX_DEVICE_ID_CHARS }
            ?: return refused(PairingProblem.BAD_FIELDS)
        val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }?.take(KoboProtocol.MAX_NAME_CHARS) ?: return refused(PairingProblem.BAD_FIELDS)
        val width = params["w"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return refused(PairingProblem.BAD_FIELDS)
        return PairingParse.Ok(RelayPairing(secret, deviceId, name, KoboImagePlan.width(width.toString())))
    }

    private fun refused(problem: PairingProblem) = PairingParse.Refused(problem)

    /** Percent-decoding; `+` reads as a space, as form encoding writes it. */
    private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
}

/** A relay's address as the reader typed it, checked. */
object RelayAddress {
    sealed interface Parse {
        /** [url] has no trailing slash, so paths are appended to it. */
        data class Ok(val url: String) : Parse
        data object Empty : Parse
        data object NotHttps : Parse
        data object Invalid : Parse
    }

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

    /**
     * `https://` only (a bare host name gets it), because the phone sends keys to the relay. [allowLocalHttp] (debug builds)
     * also lets `http://` through for a relay on this machine or the emulator's host.
     */
    fun parse(text: String, allowLocalHttp: Boolean): Parse {
        val typed = text.trim().trimEnd('/')
        if (typed.isEmpty()) return Parse.Empty
        // A bare host name means https.
        val trimmed = if (typed.contains("://")) typed else "https://$typed"
        val url = trimmed.toHttpUrlOrNull() ?: return if (trimmed.startsWith("http", ignoreCase = true)) Parse.Invalid else Parse.NotHttps
        if (url.query != null || url.fragment != null || url.username.isNotEmpty()) return Parse.Invalid
        return when {
            url.scheme == "https" -> Parse.Ok(trimmed)
            allowLocalHttp && url.host in LOCAL_HOSTS -> Parse.Ok(trimmed)
            else -> Parse.NotHttps
        }
    }
}

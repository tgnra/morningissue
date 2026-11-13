package net.tgnra.morningissue.client

import kotlin.coroutines.cancellation.CancellationException
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.client.ApiException

sealed interface PairResult {
    data class Paired(val device: DeviceDto) : PairResult

    /** Not a Morning Issue pairing code. */
    data object NotACode : PairResult

    /** The server refused the code (an unknown version, or malformed). */
    data object Refused : PairResult

    data object Unreachable : PairResult
}

/**
 * The account's devices (`GET /devices`) and Kobo pairing (`POST /devices/kobo`). The Kobo
 * shows its pairing code as a QR; the phone hands its text to the server as it is, and the
 * server derives the Kobo's token from it. Scanning is the consent.
 */
class KoboDevices(private val connection: ServerConnection) {
    /** The account's devices, Kobos first; null when the server cannot be reached. */
    suspend fun list(): List<DeviceDto>? = online {
        connection.call { it.devices() }.devices.sortedWith(compareBy({ it.kind != DeviceKind.KOBO }, { it.name.lowercase() }))
    }

    suspend fun pair(code: String): PairResult {
        val text = code.trim()
        if (!isPairingCode(text)) return PairResult.NotACode
        return try {
            PairResult.Paired(connection.call { it.pairKobo(text) }.device)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException.Offline) {
            PairResult.Unreachable
        } catch (e: ApiException.Http) {
            if (e.status in 400..499) PairResult.Refused else PairResult.Unreachable
        } catch (_: ApiException) {
            PairResult.Refused
        }
    }

    /** Forget: the device's token stops working. Returns false when the server could not be told. */
    suspend fun forget(deviceId: String): Boolean = online { connection.call { it.deleteDevice(deviceId) } } != null

    private suspend fun <T> online(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        null
    }

    companion object {
        const val PAIRING_PREFIX = "morningissue:pair?"

        fun isPairingCode(text: String): Boolean = text.startsWith(PAIRING_PREFIX) && text.length > PAIRING_PREFIX.length
    }
}

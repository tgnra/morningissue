package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.KoboConfigResponse
import net.tgnra.morningissue.api.KoboHelloResponse
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.api.PairKoboResponse

/** Devices and Kobo pairing (4.8). Implemented by W-C. */
interface DeviceService {
    /** `GET /devices`; [deviceId] is the caller's, marked `current`. */
    suspend fun devices(userId: Long, deviceId: String): DevicesResponse

    /** `POST /devices/kobo`: parses the v2 pairing URI and stores the derived token's hash. */
    suspend fun pairKobo(userId: Long, request: PairKoboRequest): PairKoboResponse

    /** `DELETE /devices/{id}`: revokes [targetDeviceId] if it is the user's. */
    suspend fun revoke(userId: Long, targetDeviceId: String)

    /** `GET /kobo/hello`. */
    suspend fun koboHello(userId: Long, deviceId: String): KoboHelloResponse

    /** `GET /kobo/config.json`. */
    fun koboConfig(): KoboConfigResponse

    /** Updates `last_seen_at`, at most once a minute per device. */
    suspend fun markSeen(deviceId: String)
}

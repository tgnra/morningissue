package net.tgnra.morningissue.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.KoboConfigResponse
import net.tgnra.morningissue.api.KoboHelloResponse
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.api.PairKoboResponse
import net.tgnra.morningissue.server.db.DeviceEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.DeviceService

/** [DeviceService] over the `device` table (section 4.8). */
class DbDeviceService(
    private val db: ServerDatabase,
    /** The server's public URL, for `/kobo/hello` and `/kobo/config.json`. */
    private val publicUrl: String?,
    private val clock: Clock = Clock.systemUTC(),
) : DeviceService {
    private val devices = db.deviceDao()
    private val lastSeen = ConcurrentHashMap<String, Instant>()

    override suspend fun devices(userId: Long, deviceId: String): DevicesResponse =
        DevicesResponse(devices.activeDevices(userId).map { it.toDto(current = it.id == deviceId) })

    override suspend fun pairKobo(userId: Long, request: PairKoboRequest): PairKoboResponse {
        val pairing = when (val parse = KoboPairingUri.parse(request.pairingUri)) {
            is KoboPairingUri.Parse.Ok -> parse.pairing
            is KoboPairingUri.Parse.Refused -> throw ApiFailure.badRequest(parse.message)
        }
        // Pairing is not the Kobo being seen: it calls by itself later.
        val row = storeDevice(userId, pairing.deviceId, DeviceKind.KOBO, pairing.name, pairing.width, Tokens.hash(pairing.token), db, clock, seenNow = false)
        return PairKoboResponse(row.toDto(current = false))
    }

    override suspend fun revoke(userId: Long, targetDeviceId: String) {
        if (devices.revoke(userId, targetDeviceId, clock.instant()) == 0) throw ApiFailure.notFound("No such device")
        lastSeen.remove(targetDeviceId)
    }

    override suspend fun koboHello(userId: Long, deviceId: String): KoboHelloResponse {
        val user = db.accountDao().user(userId) ?: throw ApiFailure(ApiError.UNKNOWN, "No such account")
        return KoboHelloResponse(username = user.username, server = publicUrl, dbId = user.dbId)
    }

    override fun koboConfig(): KoboConfigResponse = KoboConfigResponse(server = publicUrl)

    override suspend fun markSeen(deviceId: String) {
        val now = clock.instant()
        val previous = lastSeen[deviceId]
        if (previous != null && Duration.between(previous, now) < SEEN_INTERVAL) return
        lastSeen[deviceId] = now
        devices.touch(deviceId, now)
    }

    private fun DeviceEntity.toDto(current: Boolean) = DeviceDto(
        id = id,
        kind = kind,
        name = name,
        width = width,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        current = current,
    )

    private companion object {
        val SEEN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

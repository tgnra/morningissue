package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.DeviceKind

/**
 * Who is calling: the device a bearer token belongs to and its user. The HTTP layer resolves it
 * ([AuthService.authenticate]) and hands [userId] and [deviceId] to the services.
 */
data class Principal(
    val userId: Long,
    val deviceId: String,
    val kind: DeviceKind,
    /** While true, only `/auth/password`, `/auth/logout` and `/account` answer. */
    val mustChangePassword: Boolean,
)

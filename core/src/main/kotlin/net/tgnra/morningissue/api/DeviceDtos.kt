@file:UseSerializers(IsoInstantSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// 4.8 Devices and Kobo pairing

/** One of the user's devices. */
@Serializable
data class DeviceDto(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    /** A Kobo's picture width (its screen's short side, [ApiLimits.MIN_KOBO_WIDTH]..[ApiLimits.MAX_KOBO_WIDTH]); null for a phone. */
    val width: Int? = null,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null,
    /** True for the device making the request. */
    val current: Boolean = false,
)

/** `GET /devices`. `DELETE /devices/{id}` revokes one. */
@Serializable
data class DevicesResponse(
    val devices: List<DeviceDto>,
)

/**
 * `POST /devices/kobo`: pair a Kobo from the raw text of its QR code
 * (`morningissue:pair?v=2&s=…&d=…&n=…&w=…`). The server derives the Kobo's token from `s`,
 * keeps only its hash and drops `s`. 400 for an unknown `v`. Pairing the same `d` again
 * replaces the token and keeps its `acked_seq`.
 */
@Serializable
data class PairKoboRequest(
    val pairingUri: String,
)

/** `POST /devices/kobo`'s answer (201). */
@Serializable
data class PairKoboResponse(
    val device: DeviceDto,
)

/** `GET /kobo/hello` with a Kobo token: 401 `unknown` until it is paired. */
@Serializable
data class KoboHelloResponse(
    val username: String,
    /** The server's public URL, if configured. */
    val server: String? = null,
)

/**
 * `POST /kobo/images` (gated): pictures prepared for e-ink at [width]. The answer is a zip
 * ([ApiContentTypes.ZIP]) of stored entries `images/<sha256(url)>.<jpg|png>` plus
 * `images.json`, a list of [KoboImageEntry]. A URL that cannot be got or decoded is left out.
 */
@Serializable
data class KoboImagesRequest(
    val width: Int,
    /** At most [ApiLimits.MAX_IMAGE_URLS]. */
    val urls: List<String>,
)

/** One line of `images.json` in the `POST /kobo/images` zip. */
@Serializable
data class KoboImageEntry(
    val url: String,
    /** The entry's name in the zip. */
    val file: String,
    /** `image/jpeg` or `image/png`. */
    val type: String,
    val width: Int,
    val height: Int,
)

/** `GET /kobo/config.json`, unauthenticated: for copying to a Kobo over USB. */
@Serializable
data class KoboConfigResponse(
    val server: String?,
)

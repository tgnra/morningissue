@file:UseSerializers(IsoInstantSerializer::class, LocalTimeSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import java.time.LocalTime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import net.tgnra.morningissue.model.EditionLength

// 4.4 Settings. Device-local settings (theme, display mode, greyscale, storage budget, text
// size) stay on the phone and are not here.

/**
 * `GET /settings`, and the answer to both writes. `nothingNewFor`, `resetChange` and
 * `eveningChange` are kept on the server and not exposed.
 */
@Serializable
data class ServerSettings(
    val resetTime: LocalTime,
    val editionLength: EditionLength,
    val eveningEnabled: Boolean,
    val eveningTime: LocalTime,
    /** An IANA zone ID, such as `Europe/London`; editions are scheduled in it. */
    val timeZone: String,
    /** When true the phone sends a PATCH whenever the device's zone changes. */
    val followDeviceTimeZone: Boolean = true,
    val onboardingDone: Boolean = false,
    val extraOfferDismissedAt: Instant? = null,
    /** Computed, not stored: the next release under these settings. */
    val nextRelease: ReleaseDto? = null,
)

/**
 * `PATCH /settings`: only what is given changes. [timeZone] must be a valid IANA ID (400
 * otherwise). [extraOfferDismissedAt] may be set to null to clear it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SettingsPatch(
    val editionLength: EditionLength? = null,
    val timeZone: String? = null,
    val followDeviceTimeZone: Boolean? = null,
    val onboardingDone: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val extraOfferDismissedAt: Patch<Instant?> = Patch.Absent,
)

/**
 * `PUT /settings/release`: change the release times with the app's `ReleaseTimes` rules: never
 * retroactive, an edition already built keeps its time. Only what is given changes.
 */
@Serializable
data class ReleaseTimesRequest(
    val resetTime: LocalTime? = null,
    val eveningEnabled: Boolean? = null,
    val eveningTime: LocalTime? = null,
)

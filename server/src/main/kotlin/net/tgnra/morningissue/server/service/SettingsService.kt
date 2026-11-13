package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.SettingsPatch

/** Per-user settings (4.4), stored in `user_settings`. Implemented by W-B. */
interface SettingsService {
    /** `GET /settings`. */
    suspend fun settings(userId: Long): ServerSettings

    /** `PATCH /settings`; marks the settings customized. */
    suspend fun patch(userId: Long, patch: SettingsPatch): ServerSettings

    /** `PUT /settings/release`, with `ReleaseTimes` semantics. */
    suspend fun setReleaseTimes(userId: Long, request: ReleaseTimesRequest): ServerSettings
}

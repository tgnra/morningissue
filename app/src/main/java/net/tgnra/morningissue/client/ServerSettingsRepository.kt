package net.tgnra.morningissue.client

import android.util.Log
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.tgnra.morningissue.api.DueState
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.data.settings.DataStoreSettingsRepository
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ReleaseChange
import net.tgnra.morningissue.model.ThemeMode

/**
 * The settings: the edition settings live on the server (`GET/PATCH /settings`,
 * `PUT /settings/release`) and are copied into the local store for display offline; the
 * device's own (theme, display mode, greyscale, storage budget) stay local.
 *
 * A change to a server setting shows at once, and is put back if the server cannot be reached
 * or refuses it. Nothing here throws but cancellation.
 */
class ServerSettingsRepository(
    private val local: DataStoreSettingsRepository,
    private val connection: ServerConnection,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SettingsRepository {
    override val settings: Flow<AppSettings> get() = local.settings

    /** The server's last answer, for the time-zone check. */
    @Volatile
    private var lastServer: ServerSettings? = null

    override suspend fun setResetTime(time: LocalTime) = release(ReleaseTimesRequest(resetTime = time.withSecond(0).withNano(0))) {
        local.setResetTime(time)
    }

    /** The server applies a release-time change itself ("never retroactive"), so [change] is not needed. */
    override suspend fun changeResetTime(time: LocalTime, change: ReleaseChange) = setResetTime(time)

    override suspend fun changeEveningEdition(enabled: Boolean, time: LocalTime, change: ReleaseChange?) =
        release(ReleaseTimesRequest(eveningEnabled = enabled, eveningTime = time.withSecond(0).withNano(0))) {
            local.setEveningEditionEnabled(enabled)
            local.setEveningEditionTime(time)
        }

    override suspend fun setEveningEditionEnabled(enabled: Boolean) = release(ReleaseTimesRequest(eveningEnabled = enabled)) {
        local.setEveningEditionEnabled(enabled)
    }

    override suspend fun setEveningEditionTime(time: LocalTime) = release(ReleaseTimesRequest(eveningTime = time.withSecond(0).withNano(0))) {
        local.setEveningEditionTime(time)
    }

    override suspend fun setEditionLength(length: EditionLength) = patch(SettingsPatch(editionLength = length)) {
        local.setEditionLength(length)
    }

    /** Kept on the phone even when the server cannot be told: the reader is not sent through onboarding again. */
    override suspend fun setOnboardingDone(done: Boolean) {
        local.setOnboardingDone(done)
        attempt { applyServer(connection.call { it.patchSettings(SettingsPatch(onboardingDone = done)) }) }
    }

    override suspend fun setExtraOfferDismissedAt(at: Instant) = patch(SettingsPatch(extraOfferDismissedAt = Patch.Set(at))) {
        local.setExtraOfferDismissedAt(at)
    }

    // Device-local.

    override suspend fun setThemeMode(mode: ThemeMode) = local.setThemeMode(mode)

    override suspend fun setDisplayMode(mode: DisplayMode) = local.setDisplayMode(mode)

    override suspend fun setGreyscaleImages(enabled: Boolean) = local.setGreyscaleImages(enabled)

    override suspend fun setStorageBudgetMb(megabytes: Int) = local.setStorageBudgetMb(megabytes)

    override suspend fun recordNothingNew(releaseAt: Instant) = local.recordNothingNew(releaseAt)

    // From the server.

    /** Writes the server's settings over the local copy. */
    suspend fun applyServer(server: ServerSettings) {
        lastServer = server
        local.writeServerFields(
            resetTime = server.resetTime,
            editionLength = server.editionLength,
            eveningEnabled = server.eveningEnabled,
            eveningTime = server.eveningTime,
            onboardingDone = server.onboardingDone,
            extraOfferDismissedAt = server.extraOfferDismissedAt,
        )
    }

    /**
     * Records the stand's verdict on the edition that is due: when the server found nothing new
     * for it, the newsstand says so instead of offering to print it (`DueEditions`).
     */
    suspend fun applyStand(stand: StandDto) {
        val due = stand.due ?: return
        if (due.state == DueState.NOTHING_NEW) local.recordNothingNew(due.releaseAt)
    }

    /** When the server follows this phone's time zone and it has changed, tells the server. */
    suspend fun followDeviceTimeZone(api: MorningIssueApi) {
        val server = lastServer ?: return
        val here = zone().id
        if (server.followDeviceTimeZone && server.timeZone != here) applyServer(api.patchSettings(SettingsPatch(timeZone = here)))
    }

    /** The same, for the time-zone broadcast: asks the server for its settings first. */
    suspend fun followDeviceTimeZone() = attempt {
        connection.call { api ->
            applyServer(api.settings())
            followDeviceTimeZone(api)
        }
    }

    private suspend fun patch(patch: SettingsPatch, optimistic: suspend () -> Unit) =
        change(optimistic) { api -> api.patchSettings(patch) }

    private suspend fun release(request: ReleaseTimesRequest, optimistic: suspend () -> Unit) =
        change(optimistic) { api -> api.setReleaseTimes(request) }

    private suspend fun change(optimistic: suspend () -> Unit, request: suspend (MorningIssueApi) -> ServerSettings) {
        val before = local.settings.first()
        optimistic()
        val ok = attempt { applyServer(connection.call(request)) }
        if (!ok) {
            local.writeServerFields(
                before.resetTime, before.editionLength, before.eveningEditionEnabled, before.eveningEditionTime,
                before.onboardingDone, before.extraOfferDismissedAt,
            )
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        Log.w(TAG, "A settings change did not reach the server", e)
        false
    }

    private companion object {
        const val TAG = "ServerSettings"
    }
}

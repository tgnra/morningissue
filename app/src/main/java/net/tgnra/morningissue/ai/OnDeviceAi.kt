package net.tgnra.morningissue.ai

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import net.tgnra.morningissue.data.settings.SettingsRepository

/**
 * The on-device model's part in the app: whether it is there, getting it onto the phone, and
 * when it rates. Everything is gated on the model's own status and on the setting
 * (`AppSettings.onDeviceAiRanking`); on a phone without the model it never gets past [refresh].
 *
 * - [refresh] asks ML Kit where the model stands. When the phone supports it but does not have
 *   it yet (`DOWNLOADABLE`) and the setting is on, it asks for the download, which waits for
 *   an unmetered network and a charger ([scheduleDownload], `NanoDownloadWorker`).
 * - [rateSoon] runs one [InterestRater] pass on [scope]: after a sync that finished while the
 *   app was in the foreground, and when the newsstand comes to the foreground. One pass at a
 *   time; a call while one runs does nothing.
 *
 * Nothing here is needed for an edition: the build reads whatever ratings are stored, and with
 * none every post scores as if there were no model.
 */
class OnDeviceAi(
    private val model: NanoModel,
    private val settings: SettingsRepository,
    private val rater: InterestRater,
    private val scope: CoroutineScope,
    private val scheduleDownload: () -> Unit,
) {
    private val _status = MutableStateFlow<NanoStatus?>(null)

    /** The model's status as last checked; null before the first check. */
    val status: StateFlow<NanoStatus?> = _status.asStateFlow()

    private val passing = Mutex()

    /** Checks where the model stands, and asks for the download when it is wanted. Never throws. */
    suspend fun refresh(): NanoStatus {
        val status = try {
            model.status()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NanoStatus.UNAVAILABLE
        }
        _status.value = status
        if (status == NanoStatus.DOWNLOADABLE && settings.settings.first().onDeviceAiRanking) {
            try {
                scheduleDownload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Asked for again at the next check.
            }
        }
        return status
    }

    /** Starts a rating pass on [scope] if the setting is on and none is running. */
    fun rateSoon(): Job = scope.launch { rateNow() }

    /**
     * One rating pass now, if the setting is on, the model is available and no pass is
     * running; null when none was made. Never throws but for cancellation.
     */
    suspend fun rateNow(): RatingReport? {
        if (!settings.settings.first().onDeviceAiRanking) return null
        if (!passing.tryLock()) return null
        return try {
            if (refresh() != NanoStatus.AVAILABLE) null else rater.rate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed pass leaves its posts unrated: they score as if there were no model.
            null
        } finally {
            passing.unlock()
        }
    }

    /**
     * Fetches the model if it is still wanted (the setting is on) and not there yet. True when
     * there is nothing left to do: it is there, it never will be, or it is no longer wanted.
     */
    suspend fun download(): Boolean {
        if (!settings.settings.first().onDeviceAiRanking) return true
        return when (refresh()) {
            NanoStatus.DOWNLOADABLE, NanoStatus.DOWNLOADING -> model.download().also { refresh() }
            NanoStatus.AVAILABLE, NanoStatus.UNAVAILABLE -> true
        }
    }
}

package net.tgnra.morningissue.data.settings

import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ThemeMode

/** User settings. Implemented by [DataStoreSettingsRepository]; read by every layer. */
interface SettingsRepository {
    /** Current settings, then every change. Emits defaults before anything has been saved. */
    val settings: Flow<AppSettings>

    /** Sets the local time at which the morning edition is released (seconds are dropped). */
    suspend fun setResetTime(time: LocalTime)

    /** Applies to editions built from now on; stored editions keep their length. */
    suspend fun setEditionLength(length: EditionLength)

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDisplayMode(mode: DisplayMode)

    suspend fun setEveningEditionEnabled(enabled: Boolean)

    /**
     * Sets the local time at which the Evening edition is released (seconds are dropped).
     * Has a default body so that test doubles written before it existed still compile.
     */
    suspend fun setEveningEditionTime(time: LocalTime) = Unit

    /** Clamped to `AppSettings.MIN_STORAGE_BUDGET_MB..MAX_STORAGE_BUDGET_MB`. */
    suspend fun setStorageBudgetMb(megabytes: Int)

    /** True once onboarding has finished; the app then starts on the newsstand. */
    suspend fun setOnboardingDone(done: Boolean)

    /** Records that the offer of an Extra edition was turned down, so it is not made again for the same sources. */
    suspend fun setExtraOfferDismissedAt(at: Instant) = Unit
}

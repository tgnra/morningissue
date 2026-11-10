package net.tgnra.morningissue.data.settings

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

    /** Clamped to `AppSettings.MIN_STORAGE_BUDGET_MB..MAX_STORAGE_BUDGET_MB`. */
    suspend fun setStorageBudgetMb(megabytes: Int)

    /** True once onboarding has finished; the app then starts on the newsstand. */
    suspend fun setOnboardingDone(done: Boolean)
}

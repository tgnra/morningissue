package net.tgnra.morningissue.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ReleaseChange
import net.tgnra.morningissue.model.ThemeMode

/** The app's single settings store. Keys of settings that are gone are removed on first read ([RetiredSettings]). */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(RetiredSettings) },
)

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::toSettings)
        .distinctUntilChanged()

    override suspend fun setResetTime(time: LocalTime) {
        dataStore.edit {
            it[RESET_MINUTE_OF_DAY] = time.hour * 60 + time.minute
            it.remove(RESET_CHANGE)
        }
    }

    override suspend fun changeResetTime(time: LocalTime, change: ReleaseChange) {
        dataStore.edit {
            it[RESET_MINUTE_OF_DAY] = time.hour * 60 + time.minute
            it[RESET_CHANGE] = encode(change)
        }
    }

    override suspend fun changeEveningEdition(enabled: Boolean, time: LocalTime, change: ReleaseChange?) {
        dataStore.edit {
            it[EVENING_EDITION] = enabled
            it[EVENING_MINUTE_OF_DAY] = time.hour * 60 + time.minute
            if (change != null) it[EVENING_CHANGE] = encode(change) else it.remove(EVENING_CHANGE)
        }
    }

    /** "settled;kept;from" in epoch milliseconds, the first two empty when there is none. */
    private fun encode(change: ReleaseChange): String =
        listOf(change.settled, change.kept, change.from).joinToString(";") { it?.toEpochMilli()?.toString().orEmpty() }

    private fun decode(stored: String?): ReleaseChange? {
        val parts = stored?.split(';')?.takeIf { it.size == 3 } ?: return null
        val from = parts[2].toLongOrNull() ?: return null
        return ReleaseChange(
            settled = parts[0].toLongOrNull()?.let(Instant::ofEpochMilli),
            kept = parts[1].toLongOrNull()?.let(Instant::ofEpochMilli),
            from = Instant.ofEpochMilli(from),
        )
    }

    override suspend fun setEditionLength(length: EditionLength) {
        dataStore.edit { it[EDITION_LENGTH] = length.name }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setDisplayMode(mode: DisplayMode) {
        dataStore.edit { it[DISPLAY_MODE] = mode.name }
    }

    override suspend fun setGreyscaleImages(enabled: Boolean) {
        dataStore.edit { it[GREYSCALE_IMAGES] = enabled }
    }

    override suspend fun setEveningEditionEnabled(enabled: Boolean) {
        dataStore.edit {
            it[EVENING_EDITION] = enabled
            it.remove(EVENING_CHANGE)
        }
    }

    override suspend fun setEveningEditionTime(time: LocalTime) {
        dataStore.edit {
            it[EVENING_MINUTE_OF_DAY] = time.hour * 60 + time.minute
            it.remove(EVENING_CHANGE)
        }
    }

    override suspend fun setStorageBudgetMb(megabytes: Int) {
        val clamped = megabytes.coerceIn(AppSettings.MIN_STORAGE_BUDGET_MB, AppSettings.MAX_STORAGE_BUDGET_MB)
        dataStore.edit { it[STORAGE_BUDGET_MB] = clamped }
    }

    override suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    override suspend fun setExtraOfferDismissedAt(at: Instant) {
        dataStore.edit { it[EXTRA_OFFER_DISMISSED_AT] = at.toEpochMilli() }
    }

    override suspend fun recordNothingNew(releaseAt: Instant) {
        dataStore.edit { prefs ->
            val kept = (listOf(releaseAt) + releaseTimes(prefs[NOTHING_NEW_FOR]))
                .distinct()
                .sortedDescending()
                .take(AppSettings.NOTHING_NEW_KEPT)
            prefs[NOTHING_NEW_FOR] = kept.joinToString(",") { it.toEpochMilli().toString() }
        }
    }

    /** Epoch milliseconds separated by commas; anything unreadable is dropped. */
    private fun releaseTimes(stored: String?): List<Instant> =
        stored.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.map(Instant::ofEpochMilli)

    private fun toSettings(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            resetTime = timeOr(prefs[RESET_MINUTE_OF_DAY], defaults.resetTime),
            editionLength = enumOr(prefs[EDITION_LENGTH], defaults.editionLength),
            themeMode = enumOr(prefs[THEME_MODE], defaults.themeMode),
            displayMode = enumOr(prefs[DISPLAY_MODE], defaults.displayMode),
            greyscaleImages = prefs[GREYSCALE_IMAGES] ?: defaults.greyscaleImages,
            eveningEditionEnabled = prefs[EVENING_EDITION] ?: defaults.eveningEditionEnabled,
            eveningEditionTime = timeOr(prefs[EVENING_MINUTE_OF_DAY], defaults.eveningEditionTime),
            storageBudgetMb = prefs[STORAGE_BUDGET_MB] ?: defaults.storageBudgetMb,
            onboardingDone = prefs[ONBOARDING_DONE] ?: defaults.onboardingDone,
            extraOfferDismissedAt = prefs[EXTRA_OFFER_DISMISSED_AT]?.let(Instant::ofEpochMilli),
            nothingNewFor = releaseTimes(prefs[NOTHING_NEW_FOR]),
            resetChange = decode(prefs[RESET_CHANGE]),
            eveningChange = decode(prefs[EVENING_CHANGE]),
        )
    }

    private fun timeOr(minuteOfDay: Int?, default: LocalTime): LocalTime =
        minuteOfDay?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) } ?: default

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        val RESET_MINUTE_OF_DAY = intPreferencesKey("reset_minute_of_day")
        val EDITION_LENGTH = stringPreferencesKey("edition_length")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DISPLAY_MODE = stringPreferencesKey("display_mode")
        val GREYSCALE_IMAGES = booleanPreferencesKey("greyscale_images")
        val EVENING_EDITION = booleanPreferencesKey("evening_edition")
        val EVENING_MINUTE_OF_DAY = intPreferencesKey("evening_minute_of_day")
        val STORAGE_BUDGET_MB = intPreferencesKey("storage_budget_mb")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val EXTRA_OFFER_DISMISSED_AT = longPreferencesKey("extra_offer_dismissed_at")
        val NOTHING_NEW_FOR = stringPreferencesKey("nothing_new_for")
        val RESET_CHANGE = stringPreferencesKey("reset_change")
        val EVENING_CHANGE = stringPreferencesKey("evening_change")
    }
}

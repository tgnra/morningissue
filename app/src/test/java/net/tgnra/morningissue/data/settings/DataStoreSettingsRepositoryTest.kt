package net.tgnra.morningissue.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM test: DataStore over a temporary file, no Android needed. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher())

    private fun repository(): DataStoreSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) {
            File(tmp.root, "settings.preferences_pb")
        }
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun defaults() = scope.runTest {
        val settings = repository().settings.first()

        assertEquals(LocalTime.of(6, 30), settings.resetTime)
        assertEquals(EditionLength.STANDARD, settings.editionLength)
        assertEquals(ThemeMode.System, settings.themeMode)
        assertEquals(DisplayMode.Auto, settings.displayMode)
        assertFalse(settings.eveningEditionEnabled)
        assertEquals(500, settings.storageBudgetMb)
        assertFalse(settings.onboardingDone)
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun settersRoundTrip() = scope.runTest {
        val repository = repository()

        repository.setResetTime(LocalTime.of(3, 5, 59))
        repository.setEditionLength(EditionLength.LONG)
        repository.setThemeMode(ThemeMode.Dark)
        repository.setDisplayMode(DisplayMode.Eink)
        repository.setEveningEditionEnabled(true)
        repository.setStorageBudgetMb(1_000_000)
        repository.setOnboardingDone(true)

        assertEquals(
            AppSettings(
                resetTime = LocalTime.of(3, 5),
                editionLength = EditionLength.LONG,
                themeMode = ThemeMode.Dark,
                displayMode = DisplayMode.Eink,
                eveningEditionEnabled = true,
                storageBudgetMb = AppSettings.MAX_STORAGE_BUDGET_MB,
                onboardingDone = true,
            ),
            repository.settings.first(),
        )
    }
}

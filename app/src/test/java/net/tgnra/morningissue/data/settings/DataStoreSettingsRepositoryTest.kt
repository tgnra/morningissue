package net.tgnra.morningissue.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ReleaseChange
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
        assertFalse(settings.greyscaleImages)
        assertFalse(settings.eveningEditionEnabled)
        assertEquals(LocalTime.of(18, 0), settings.eveningEditionTime)
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
        repository.setGreyscaleImages(true)
        repository.setEveningEditionEnabled(true)
        repository.setEveningEditionTime(LocalTime.of(17, 45, 30))
        repository.setStorageBudgetMb(1_000_000)
        repository.setOnboardingDone(true)

        assertEquals(
            AppSettings(
                resetTime = LocalTime.of(3, 5),
                editionLength = EditionLength.LONG,
                themeMode = ThemeMode.Dark,
                displayMode = DisplayMode.Eink,
                greyscaleImages = true,
                eveningEditionEnabled = true,
                eveningEditionTime = LocalTime.of(17, 45),
                storageBudgetMb = AppSettings.MAX_STORAGE_BUDGET_MB,
                onboardingDone = true,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun nothingNewReleasesAreKeptNewestFirstAndOnlyTheLastFew() = scope.runTest {
        val repository = repository()
        assertEquals(emptyList<Instant>(), repository.settings.first().nothingNewFor)
        val day = Instant.parse("2026-11-11T06:30:00Z")
        fun release(daysLater: Long) = day.plusSeconds(daysLater * 86_400)

        repository.recordNothingNew(release(1))
        repository.recordNothingNew(release(0))
        repository.recordNothingNew(release(1))
        assertEquals("newest first, each once", listOf(release(1), release(0)), repository.settings.first().nothingNewFor)

        (2L..6L).forEach { repository.recordNothingNew(release(it)) }
        assertEquals(
            listOf(release(6), release(5), release(4), release(3)),
            repository.settings.first().nothingNewFor,
        )
        assertEquals(AppSettings.NOTHING_NEW_KEPT, repository.settings.first().nothingNewFor.size)
    }

    @Test
    fun aReleaseTimeChangeIsStoredWithItsRecordAndAPlainSetDropsIt() = scope.runTest {
        val repository = repository()
        val settled = Instant.parse("2026-11-10T06:30:00Z")
        val change = ReleaseChange(settled = settled, kept = null, from = Instant.parse("2026-11-11T08:00:00Z"))

        repository.changeResetTime(LocalTime.of(8, 0), change)
        assertEquals(LocalTime.of(8, 0), repository.settings.first().resetTime)
        assertEquals(change, repository.settings.first().resetChange)

        val evening = ReleaseChange(settled = null, kept = settled.plusSeconds(3600), from = settled.plusSeconds(90_000))
        repository.changeEveningEdition(enabled = true, time = LocalTime.of(20, 0), change = evening)
        val stored = repository.settings.first()
        assertEquals(true, stored.eveningEditionEnabled)
        assertEquals(LocalTime.of(20, 0), stored.eveningEditionTime)
        assertEquals(evening, stored.eveningChange)

        repository.changeEveningEdition(enabled = false, time = LocalTime.of(20, 0), change = null)
        assertEquals(null, repository.settings.first().eveningChange)
        repository.setResetTime(LocalTime.of(7, 0))
        assertEquals(null, repository.settings.first().resetChange)
    }
}

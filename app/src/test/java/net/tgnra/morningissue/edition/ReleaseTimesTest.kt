package net.tgnra.morningissue.edition

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.ReleaseChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' way of changing the release times: what `ReleaseTimes` stores. UTC; reset 06:30, evening 18:00. */
class ReleaseTimesTest {
    private val zone = ZoneOffset.UTC
    private val clock = MutableClock(Instant.parse("2026-11-10T09:00:00Z"))
    private val settings = FakeSettings()
    private val built = mutableSetOf<Pair<LocalDate, EditionKind>>()
    private val times = ReleaseTimes(settings, isBuilt = { date, kind -> (date to kind) in built }, clock = clock, zone = { zone })

    private fun at(text: String): Instant = Instant.parse(text)
    private val schedule get() = settings.current.schedule

    @Test
    fun changingTheResetTimeAfterTodaysEditionAppliesFromTomorrow() = runTest {
        times.setResetTime(LocalTime.of(8, 0))

        assertEquals(LocalTime.of(8, 0), settings.current.resetTime)
        assertEquals(
            ReleaseChange(settled = at("2026-11-10T06:30:00Z"), kept = null, from = at("2026-11-11T08:00:00Z")),
            settings.current.resetChange,
        )
        assertEquals(at("2026-11-10T06:30:00Z"), schedule.latestReset(clock.now, zone))
    }

    @Test
    fun settingTheSameTimeChangesNothing() = runTest {
        times.setResetTime(LocalTime.of(6, 30, 45))

        assertEquals(AppSettings(), settings.current)
    }

    @Test
    fun anEditionAlreadyBuiltForTheComingResetKeepsItsTime() = runTest {
        clock.now = at("2026-11-10T05:30:00Z")
        built += LocalDate.of(2026, 11, 10) to EditionKind.MORNING

        times.setResetTime(LocalTime.of(7, 0))

        assertEquals(
            ReleaseChange(settled = at("2026-11-09T06:30:00Z"), kept = at("2026-11-10T06:30:00Z"), from = at("2026-11-11T07:00:00Z")),
            settings.current.resetChange,
        )
    }

    @Test
    fun withNothingBuiltTheComingResetMoves() = runTest {
        clock.now = at("2026-11-10T05:30:00Z")
        // Yesterday's edition exists; that is not the coming one.
        built += LocalDate.of(2026, 11, 9) to EditionKind.MORNING

        times.setResetTime(LocalTime.of(7, 0))

        assertEquals(at("2026-11-10T07:00:00Z"), schedule.nextReset(clock.now, zone))
        assertNull(settings.current.resetChange!!.kept)
    }

    @Test
    fun aSecondChangeBuildsOnTheFirst() = runTest {
        times.setResetTime(LocalTime.of(10, 0))
        clock.now = at("2026-11-10T11:00:00Z")
        times.setResetTime(LocalTime.of(8, 0))

        assertEquals(at("2026-11-10T06:30:00Z"), schedule.latestReset(clock.now, zone))
        assertEquals(at("2026-11-11T08:00:00Z"), schedule.nextReset(clock.now, zone))
    }

    @Test
    fun switchingTheEveningEditionOnAfterItsTimeStartsItTomorrow() = runTest {
        clock.now = at("2026-11-10T19:00:00Z")

        times.setEveningEditionEnabled(true)

        assertTrue(settings.current.eveningEditionEnabled)
        assertNull(schedule.currentEveningRelease(clock.now, zone))
        assertEquals(at("2026-11-11T18:00:00Z"), schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun switchingTheEveningEditionOffIsImmediateAndDropsTheRecord() = runTest {
        clock.now = at("2026-11-10T19:00:00Z")
        times.setEveningEditionEnabled(true)

        times.setEveningEditionEnabled(false)

        assertFalse(settings.current.eveningEditionEnabled)
        assertNull(settings.current.eveningChange)
        assertNull(schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun changingTheEveningTimeAfterTonightsReleaseAppliesFromTomorrow() = runTest {
        settings.setEveningEditionEnabled(true)
        clock.now = at("2026-11-10T19:00:00Z")

        times.setEveningEditionTime(LocalTime.of(17, 0))

        assertEquals(LocalTime.of(17, 0), settings.current.eveningEditionTime)
        assertEquals(at("2026-11-10T18:00:00Z"), schedule.currentEveningRelease(clock.now, zone))
        assertEquals(at("2026-11-11T17:00:00Z"), schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun anEveningEditionAlreadyBuiltKeepsItsTime() = runTest {
        settings.setEveningEditionEnabled(true)
        clock.now = at("2026-11-10T17:00:00Z")
        built += LocalDate.of(2026, 11, 10) to EditionKind.EVENING

        times.setEveningEditionTime(LocalTime.of(20, 0))

        assertEquals(at("2026-11-10T18:00:00Z"), schedule.nextEvening(clock.now, zone))
        assertEquals(at("2026-11-11T20:00:00Z"), schedule.nextEvening(at("2026-11-10T18:00:00Z"), zone))
    }

    @Test
    fun theEveningTimeSetWhileSwitchedOffIsJustStored() = runTest {
        times.setEveningEditionTime(LocalTime.of(20, 0))

        assertEquals(LocalTime.of(20, 0), settings.current.eveningEditionTime)
        assertNull(settings.current.eveningChange)
    }
}

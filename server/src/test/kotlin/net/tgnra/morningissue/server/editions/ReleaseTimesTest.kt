package net.tgnra.morningissue.server.editions

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.model.ReleaseChange
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.server.db.EditionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PUT /settings/release`: what `ReleaseTimes` stores, per user. UTC; reset 06:30, evening
 * 18:00. Ported from the app; "already built" is an edition row of the user's.
 */
class ReleaseTimesTest {
    private val zone = ZoneOffset.UTC
    private val world = TestWorld(Instant.parse("2026-11-10T09:00:00Z"))
    private val clock = world.clock
    private val service = SettingsServiceImpl(world.db, world.settings, clock)
    private val user = runBlocking { world.addUser() }

    @After
    fun tearDown() = world.close()

    private fun at(text: String): Instant = Instant.parse(text)
    private val current get() = runBlocking { world.settings.get(user) }
    private val schedule get() = current.schedule

    private suspend fun setResetTime(time: LocalTime, userId: Long = user) = service.setReleaseTimes(userId, ReleaseTimesRequest(resetTime = time))
    private suspend fun setEveningEnabled(enabled: Boolean) = service.setReleaseTimes(user, ReleaseTimesRequest(eveningEnabled = enabled))
    private suspend fun setEveningTime(time: LocalTime) = service.setReleaseTimes(user, ReleaseTimesRequest(eveningTime = time))

    private suspend fun built(date: LocalDate, kind: EditionKind, userId: Long = user) {
        val release = at("${date}T06:30:00Z")
        world.db.editionDao().insertEditionWithItems(
            EditionEntity(userId = userId, date = date, kind = kind, cutoffAt = release, releaseAt = release, builtAt = release, length = EditionLength.STANDARD, number = 1),
            emptyList(),
        )
    }

    @Test
    fun changingTheResetTimeAfterTodaysEditionAppliesFromTomorrow() = runBlocking {
        setResetTime(LocalTime.of(8, 0))

        assertEquals(LocalTime.of(8, 0), current.reset)
        assertEquals(
            ReleaseChange(settled = at("2026-11-10T06:30:00Z"), kept = null, from = at("2026-11-11T08:00:00Z")),
            current.resetChange!!.toReleaseChange(),
        )
        assertEquals(at("2026-11-10T06:30:00Z"), schedule.latestReset(clock.now, zone))
        assertTrue(world.settings.isCustomized(user))
    }

    @Test
    fun settingTheSameTimeChangesNothing() = runBlocking {
        setResetTime(LocalTime.of(6, 30, 45))

        assertEquals(world.settings.defaults(), current)
        assertFalse(world.settings.isCustomized(user))
    }

    @Test
    fun anEditionAlreadyBuiltForTheComingResetKeepsItsTime() = runBlocking {
        clock.now = at("2026-11-10T05:30:00Z")
        built(LocalDate.of(2026, 11, 10), EditionKind.MORNING)

        setResetTime(LocalTime.of(7, 0))

        assertEquals(
            ReleaseChange(settled = at("2026-11-09T06:30:00Z"), kept = at("2026-11-10T06:30:00Z"), from = at("2026-11-11T07:00:00Z")),
            current.resetChange!!.toReleaseChange(),
        )
    }

    @Test
    fun withNothingBuiltTheComingResetMoves() = runBlocking {
        clock.now = at("2026-11-10T05:30:00Z")
        // Yesterday's edition exists; that is not the coming one.
        built(LocalDate.of(2026, 11, 9), EditionKind.MORNING)

        setResetTime(LocalTime.of(7, 0))

        assertEquals(at("2026-11-10T07:00:00Z"), schedule.nextReset(clock.now, zone))
        assertNull(current.resetChange!!.kept)
    }

    @Test
    fun anotherUsersEditionDoesNotCountAsBuilt() = runBlocking {
        clock.now = at("2026-11-10T05:30:00Z")
        val other = world.addUser()
        built(LocalDate.of(2026, 11, 10), EditionKind.MORNING, userId = other)

        setResetTime(LocalTime.of(7, 0))

        assertEquals(at("2026-11-10T07:00:00Z"), schedule.nextReset(clock.now, zone))
        assertEquals(world.settings.defaults(), world.settings.get(other))
    }

    @Test
    fun aSecondChangeBuildsOnTheFirst() = runBlocking {
        setResetTime(LocalTime.of(10, 0))
        clock.now = at("2026-11-10T11:00:00Z")
        setResetTime(LocalTime.of(8, 0))

        assertEquals(at("2026-11-10T06:30:00Z"), schedule.latestReset(clock.now, zone))
        assertEquals(at("2026-11-11T08:00:00Z"), schedule.nextReset(clock.now, zone))
    }

    @Test
    fun switchingTheEveningEditionOnAfterItsTimeStartsItTomorrow() = runBlocking {
        clock.now = at("2026-11-10T19:00:00Z")

        setEveningEnabled(true)

        assertTrue(current.eveningEnabled)
        assertNull(schedule.currentEveningRelease(clock.now, zone))
        assertEquals(at("2026-11-11T18:00:00Z"), schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun switchingTheEveningEditionOffIsImmediateAndDropsTheRecord() = runBlocking {
        clock.now = at("2026-11-10T19:00:00Z")
        setEveningEnabled(true)

        setEveningEnabled(false)

        assertFalse(current.eveningEnabled)
        assertNull(current.eveningChange)
        assertNull(schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun changingTheEveningTimeAfterTonightsReleaseAppliesFromTomorrow() = runBlocking {
        // The app switched it on through settings directly, with no change record.
        world.settings.update(user, customize = true) { it.copy(eveningEnabled = true) }
        clock.now = at("2026-11-10T19:00:00Z")

        setEveningTime(LocalTime.of(17, 0))

        assertEquals(LocalTime.of(17, 0), current.evening)
        assertEquals(at("2026-11-10T18:00:00Z"), schedule.currentEveningRelease(clock.now, zone))
        assertEquals(at("2026-11-11T17:00:00Z"), schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun anEveningEditionAlreadyBuiltKeepsItsTime() = runBlocking {
        world.settings.update(user, customize = true) { it.copy(eveningEnabled = true) }
        clock.now = at("2026-11-10T17:00:00Z")
        built(LocalDate.of(2026, 11, 10), EditionKind.EVENING)

        setEveningTime(LocalTime.of(20, 0))

        assertEquals(at("2026-11-10T18:00:00Z"), schedule.nextEvening(clock.now, zone))
        assertEquals(at("2026-11-11T20:00:00Z"), schedule.nextEvening(at("2026-11-10T18:00:00Z"), zone))
    }

    @Test
    fun theEveningTimeSetWhileSwitchedOffIsJustStored() = runBlocking {
        setEveningTime(LocalTime.of(20, 0))

        assertEquals(LocalTime.of(20, 0), current.evening)
        assertNull(current.eveningChange)
    }

    @Test
    fun switchingOnWithATimeInOneRequestUsesThatTime() = runBlocking {
        clock.now = at("2026-11-10T12:00:00Z")

        service.setReleaseTimes(user, ReleaseTimesRequest(eveningEnabled = true, eveningTime = LocalTime.of(20, 0)))

        // Switched on with 20:00, not switched on at 18:00 and then moved.
        assertEquals(at("2026-11-10T20:00:00Z"), schedule.nextEvening(clock.now, zone))
    }

    @Test
    fun theScheduleIsInTheUsersZone() = runBlocking {
        world.settings.update(user, customize = true) { it.copy(timeZone = "America/New_York") }

        val settings = setResetTime(LocalTime.of(8, 0))

        // 09:00 UTC is 04:00 in New York: today's release has not happened, so 08:00 applies today.
        assertEquals(at("2026-11-10T13:00:00Z"), settings.nextRelease!!.at)
    }
}

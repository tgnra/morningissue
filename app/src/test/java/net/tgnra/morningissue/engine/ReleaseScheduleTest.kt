package net.tgnra.morningissue.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import net.tgnra.morningissue.edition.DueEditions
import net.tgnra.morningissue.edition.DueRelease
import net.tgnra.morningissue.edition.DueStatus
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.model.ReleaseChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A changed release time applies from the next release, never retroactively. "Today" is
 * 10 November 2026 in Amsterdam; the reset was 06:30 and the Evening edition's time 18:00.
 */
class ReleaseScheduleTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val oldReset = LocalTime.of(6, 30)
    private val oldEvening = LocalTime.of(18, 0)

    private fun at(date: String, time: String): Instant = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()
    private fun time(text: String): LocalTime = LocalTime.parse(text)

    private val yesterday = "2026-11-09"
    private val today = "2026-11-10"
    private val tomorrow = "2026-11-11"

    private val before = ReleaseSchedule(oldReset, null)

    /** The schedule after the reset time is changed to [newTime] at [changedAt], starting from [from]. */
    private fun resetChanged(newTime: String, changedAt: Instant, built: Boolean = false, from: ReleaseSchedule = before): ReleaseSchedule {
        val change = ReleaseSchedule.changeTo(
            newTime = time(newTime),
            now = changedAt,
            zone = zone,
            latest = from.latestReset(changedAt, zone),
            next = from.nextReset(changedAt, zone),
            nextIsBuilt = built,
        )
        return ReleaseSchedule(time(newTime), null, resetChange = change)
    }

    private val eveningOn = ReleaseSchedule(oldReset, oldEvening)

    private fun eveningChanged(newTime: String, changedAt: Instant, built: Boolean = false): ReleaseSchedule {
        val change = ReleaseSchedule.changeTo(
            newTime = time(newTime),
            now = changedAt,
            zone = zone,
            latest = eveningOn.latestEvening(changedAt, zone),
            next = eveningOn.nextEvening(changedAt, zone)!!,
            nextIsBuilt = built,
        )
        return ReleaseSchedule(oldReset, time(newTime), eveningChange = change)
    }

    private fun edition(date: String, kind: EditionKind = EditionKind.MORNING) = EditionSummary(
        id = 1, date = LocalDate.parse(date), kind = kind, number = 1, length = EditionLength.STANDARD,
        releaseAt = at(date, "06:30"), cutoffAt = at(date, "05:00"), leadHeadline = null, leadImageUrl = null,
        leadSource = null, storyCount = 9, isRead = false,
    )

    private fun status(schedule: ReleaseSchedule, now: Instant, released: List<EditionSummary>, nothingNewFor: List<Instant> = emptyList()) =
        DueEditions.status(now, schedule, zone, released, nothingNewFor, hasSources = true)

    private val todaysMorning = DueRelease(EditionKind.MORNING, at(today, "06:30"), LocalDate.parse(today))

    // --- Unchanged ----------------------------------------------------------------------------

    @Test
    fun withoutAChangeItIsThePlainClock() {
        val now = at(today, "09:00")
        assertEquals(EditionClock.latestReset(now, oldReset, zone), before.latestReset(now, zone))
        assertEquals(EditionClock.nextReset(now, oldReset, zone), before.nextReset(now, zone))
        assertEquals(EditionClock.nextRelease(now, oldReset, oldEvening, zone), eveningOn.nextRelease(now, zone))
        assertEquals(
            EditionClock.currentEveningRelease(at(today, "19:00"), oldReset, oldEvening, zone),
            eveningOn.currentEveningRelease(at(today, "19:00"), zone),
        )
        assertNull(before.currentEveningRelease(at(today, "19:00"), zone))
    }

    // --- Changed after today's edition came out ---------------------------------------------------

    @Test
    fun anEarlierTimeThatHasPassedTodayDoesNotMoveTodaysRelease() {
        val changedAt = at(today, "09:00")
        val schedule = resetChanged("08:00", changedAt)

        assertEquals("today's release is still the 06:30 one", at(today, "06:30"), schedule.latestReset(changedAt, zone))
        assertEquals("the new time starts tomorrow", at(tomorrow, "08:00"), schedule.nextReset(changedAt, zone))
        // All day, and overnight.
        assertEquals(at(today, "06:30"), schedule.latestReset(at(today, "23:00"), zone))
        assertEquals(at(today, "06:30"), schedule.latestReset(at(tomorrow, "07:59"), zone))
        assertEquals(at(tomorrow, "08:00"), schedule.latestReset(at(tomorrow, "08:00"), zone))
        assertEquals("then daily at the new time", at("2026-11-12", "08:00"), schedule.nextReset(at(tomorrow, "08:00"), zone))
    }

    @Test
    fun whatWasSettledForTodayStaysSettledAfterAChangeToAnEarlierTime() {
        val changedAt = at(today, "09:00")
        val schedule = resetChanged("08:00", changedAt)
        val later = at(today, "09:05")

        assertEquals("printed", DueStatus.Printed, status(schedule, later, listOf(edition(today))))
        assertEquals(
            "nothing new: the record made for 06:30 still answers",
            DueStatus.NothingNew(todaysMorning),
            status(schedule, later, listOf(edition(yesterday)), nothingNewFor = listOf(at(today, "06:30"))),
        )
        assertEquals(
            "failed: still the same edition that is asked for, not a new one",
            DueStatus.NotPrinted(todaysMorning),
            status(schedule, later, listOf(edition(yesterday))),
        )
        // Without the record of the change, the bare new time would call a new edition due at 08:00.
        assertEquals(
            DueStatus.NotPrinted(DueRelease(EditionKind.MORNING, at(today, "08:00"), LocalDate.parse(today))),
            status(ReleaseSchedule(time("08:00"), null), later, listOf(edition(yesterday)), nothingNewFor = listOf(at(today, "06:30"))),
        )
    }

    @Test
    fun aLaterTimeTodayDoesNotBringASecondReleaseToday() {
        val changedAt = at(today, "09:00")
        val schedule = resetChanged("10:00", changedAt)

        assertEquals(at(tomorrow, "10:00"), schedule.nextReset(changedAt, zone))
        // 10:00 today passes and nothing becomes due.
        assertEquals(at(today, "06:30"), schedule.latestReset(at(today, "10:30"), zone))
        assertEquals(
            DueStatus.NothingNew(todaysMorning),
            status(schedule, at(today, "10:30"), listOf(edition(yesterday)), nothingNewFor = listOf(at(today, "06:30"))),
        )
        assertEquals(DueStatus.Printed, status(schedule, at(today, "10:30"), listOf(edition(today))))
        // Tomorrow the old 06:30 does not happen; the edition comes at 10:00.
        assertEquals(at(today, "06:30"), schedule.latestReset(at(tomorrow, "07:00"), zone))
        assertEquals(DueStatus.Printed, status(schedule, at(tomorrow, "07:00"), listOf(edition(today))))
        assertEquals(at(tomorrow, "10:00"), schedule.latestReset(at(tomorrow, "10:00"), zone))
    }

    // --- Changed before today's edition came out --------------------------------------------------

    @Test
    fun beforeTheOldTimeALaterTimeMovesTodaysRelease() {
        val changedAt = at(today, "05:00")
        val schedule = resetChanged("07:00", changedAt)

        assertEquals(at(today, "07:00"), schedule.nextReset(changedAt, zone))
        assertEquals("yesterday's is the current one until then", at(yesterday, "06:30"), schedule.latestReset(at(today, "06:45"), zone))
        assertEquals(at(today, "07:00"), schedule.latestReset(at(today, "07:00"), zone))
        assertEquals(at(tomorrow, "07:00"), schedule.nextReset(at(today, "07:00"), zone))
    }

    @Test
    fun beforeTheOldTimeAnEarlierTimeStillAheadMovesTodaysRelease() {
        val changedAt = at(today, "05:00")
        val schedule = resetChanged("05:30", changedAt)

        assertEquals(at(today, "05:30"), schedule.nextReset(changedAt, zone))
        assertEquals(at(today, "05:30"), schedule.latestReset(at(today, "06:00"), zone))
        assertEquals("the old 06:30 is gone", at(tomorrow, "05:30"), schedule.nextReset(at(today, "06:00"), zone))
    }

    @Test
    fun beforeTheOldTimeATimeAlreadyPassedTodayLeavesTodayAtTheOldTime() {
        val changedAt = at(today, "05:00")
        val schedule = resetChanged("04:00", changedAt)

        assertEquals("not due at once", at(yesterday, "06:30"), schedule.latestReset(changedAt, zone))
        assertEquals("and today is not skipped", at(today, "06:30"), schedule.nextReset(changedAt, zone))
        assertEquals(at(today, "06:30"), schedule.latestReset(at(today, "07:00"), zone))
        assertEquals(at(tomorrow, "04:00"), schedule.nextReset(at(today, "07:00"), zone))
        assertEquals(at(tomorrow, "04:00"), schedule.latestReset(at(tomorrow, "04:30"), zone))
        // Yesterday's paper on the stand: nothing is reported unprinted at the moment of the change.
        assertEquals(DueStatus.Printed, status(schedule, changedAt, listOf(edition(yesterday))))
    }

    @Test
    fun anEditionAlreadyBuiltForTheOldTimeKeepsItsReleaseTime() {
        val changedAt = at(today, "05:30")
        val later = resetChanged("07:00", changedAt, built = true)
        assertEquals("released when it was built for", at(today, "06:30"), later.nextReset(changedAt, zone))
        assertEquals(at(today, "06:30"), later.latestReset(at(today, "06:30"), zone))
        assertEquals("the new time starts with the edition after it", at(tomorrow, "07:00"), later.nextReset(at(today, "06:30"), zone))
        assertEquals("07:00 today is not a second release", at(today, "06:30"), later.latestReset(at(today, "07:30"), zone))

        val earlier = resetChanged("05:45", changedAt, built = true)
        assertEquals(at(today, "06:30"), earlier.nextReset(changedAt, zone))
        assertEquals("not due at 05:45 while its edition is still hidden", at(yesterday, "06:30"), earlier.latestReset(at(today, "06:00"), zone))
        assertEquals(at(tomorrow, "05:45"), earlier.nextReset(at(today, "06:30"), zone))
    }

    @Test
    fun aBuiltEditionForTomorrowIsLeftAloneToo() {
        // The reset is half past midnight; tomorrow's edition is built at 23:00.
        val late = ReleaseSchedule(time("00:30"), null)
        val changedAt = at(today, "23:15")
        val schedule = resetChanged("01:00", changedAt, built = true, from = late)

        assertEquals(at(tomorrow, "00:30"), schedule.nextReset(changedAt, zone))
        assertEquals(at("2026-11-12", "01:00"), schedule.nextReset(at(tomorrow, "00:30"), zone))
    }

    // --- Changing it again ---------------------------------------------------------------------

    @Test
    fun aSecondChangeBeforeTheFirstHasTakenEffectStartsFromTheSameSettledRelease() {
        val first = resetChanged("10:00", at(today, "09:00"))
        val second = resetChanged("08:00", at(today, "11:00"), from = first)

        assertEquals(at(today, "06:30"), second.latestReset(at(today, "11:00"), zone))
        assertEquals(at(tomorrow, "08:00"), second.nextReset(at(today, "11:00"), zone))
        assertEquals(at(today, "06:30"), second.latestReset(at(tomorrow, "07:59"), zone))
    }

    @Test
    fun aSecondChangeCanStillMoveAReleaseTheFirstLeftInPlace() {
        // 05:00: to 04:00, so today stays at 06:30. 05:10: to 05:45, which is still ahead.
        val first = resetChanged("04:00", at(today, "05:00"))
        val second = resetChanged("05:45", at(today, "05:10"), from = first)

        assertEquals(at(yesterday, "06:30"), second.latestReset(at(today, "05:10"), zone))
        assertEquals(at(today, "05:45"), second.nextReset(at(today, "05:10"), zone))
        assertEquals(at(tomorrow, "05:45"), second.nextReset(at(today, "06:00"), zone))
    }

    @Test
    fun changingBackOnTheSameDayStillLeavesTodayAlone() {
        val first = resetChanged("10:00", at(today, "09:00"))
        val back = resetChanged("06:30", at(today, "09:30"), from = first)

        assertEquals(at(today, "06:30"), back.latestReset(at(today, "12:00"), zone))
        assertEquals(at(tomorrow, "06:30"), back.nextReset(at(today, "12:00"), zone))
    }

    @Test
    fun thereIsAlwaysOneResetPerDateAcrossAChange() {
        fun case(newTime: String, changedAt: Instant, built: Boolean = false) = changedAt to resetChanged(newTime, changedAt, built)
        val cases = listOf(
            case("08:00", at(today, "09:00")),
            case("10:00", at(today, "09:00")),
            case("04:00", at(today, "05:00")),
            case("07:00", at(today, "05:00")),
            case("07:00", at(today, "05:30"), built = true),
            case("23:30", at(today, "23:00")),
            case("00:10", at(today, "23:00")),
        )
        for ((changedAt, schedule) in cases) {
            // The latest reset at the change, then every reset after it for a week.
            var reset = schedule.latestReset(changedAt, zone)
            val dates = arrayListOf(EditionClock.editionDate(reset, zone))
            repeat(7) {
                reset = schedule.nextReset(reset, zone)
                dates += EditionClock.editionDate(reset, zone)
            }
            assertEquals("no date skipped or doubled", (0L..7L).map { dates.first().plusDays(it) }, dates)
        }
    }

    @Test
    fun aClockSetBackBeforeTheSettledReleaseFallsBackToThePlainTime() {
        val schedule = resetChanged("08:00", at(today, "09:00"))
        val setBack = at("2026-11-05", "12:00")
        assertEquals(EditionClock.latestReset(setBack, time("08:00"), zone), schedule.latestReset(setBack, zone))
    }

    // --- The Evening edition's time ---------------------------------------------------------------

    @Test
    fun anEveningTimeChangedAfterTonightsReleaseAppliesFromTomorrow() {
        val changedAt = at(today, "19:00")
        val earlier = eveningChanged("17:00", changedAt)
        assertEquals("tonight's is still the 18:00 one", at(today, "18:00"), earlier.currentEveningRelease(changedAt, zone))
        assertEquals(at(tomorrow, "06:30"), earlier.nextRelease(changedAt, zone).at)
        assertEquals(Release(at(tomorrow, "17:00"), EditionKind.EVENING), earlier.nextRelease(at(tomorrow, "07:00"), zone))

        val later = eveningChanged("20:00", changedAt)
        assertEquals("no second evening release at 20:00", at(today, "18:00"), later.currentEveningRelease(at(today, "20:30"), zone))
        assertEquals(Release(at(tomorrow, "06:30"), EditionKind.MORNING), later.nextRelease(at(today, "19:30"), zone))
        assertEquals(at(tomorrow, "20:00"), later.nextEvening(at(today, "20:30"), zone))
        // An evening with nothing new stays that: its record is for 18:00.
        val tonight = DueRelease(EditionKind.EVENING, at(today, "18:00"), LocalDate.parse(today))
        assertEquals(listOf(tonight, todaysMorning), DueEditions.due(at(today, "20:30"), later, zone))
        assertEquals(
            DueStatus.Printed,
            status(later, at(today, "20:30"), listOf(edition(today)), nothingNewFor = listOf(at(today, "18:00"))),
        )
    }

    @Test
    fun anEveningTimeChangedBeforeTonightsReleaseMovesItUnlessItHasPassedOrIsBuilt() {
        val changedAt = at(today, "16:00")
        assertEquals(at(today, "20:00"), eveningChanged("20:00", changedAt).nextEvening(changedAt, zone))
        assertEquals(at(today, "16:30"), eveningChanged("16:30", changedAt).nextEvening(changedAt, zone))

        val passed = eveningChanged("15:00", changedAt)
        assertNull("not due at once", passed.currentEveningRelease(changedAt, zone))
        assertEquals(at(today, "18:00"), passed.nextEvening(changedAt, zone))
        assertEquals(at(tomorrow, "15:00"), passed.nextEvening(at(today, "18:00"), zone))

        val built = eveningChanged("20:00", at(today, "17:00"), built = true)
        assertEquals(at(today, "18:00"), built.nextEvening(at(today, "17:00"), zone))
        assertEquals(at(tomorrow, "20:00"), built.nextEvening(at(today, "18:00"), zone))
    }

    @Test
    fun switchingTheEveningEditionOnAfterItsTimeDoesNotMakeTonightsDue() {
        val switchedOnAt = at(today, "19:00")
        val schedule = ReleaseSchedule(oldReset, oldEvening, eveningChange = ReleaseSchedule.switchedOn(oldEvening, switchedOnAt, zone))

        assertNull(schedule.currentEveningRelease(switchedOnAt, zone))
        assertEquals(listOf(todaysMorning), DueEditions.due(at(today, "22:00"), schedule, zone))
        assertEquals(at(tomorrow, "18:00"), schedule.nextEvening(switchedOnAt, zone))
        assertEquals(at(tomorrow, "18:00"), schedule.currentEveningRelease(at(tomorrow, "19:00"), zone))
    }

    @Test
    fun switchingTheEveningEditionOnBeforeItsTimeBringsTonights() {
        val switchedOnAt = at(today, "10:00")
        val schedule = ReleaseSchedule(oldReset, oldEvening, eveningChange = ReleaseSchedule.switchedOn(oldEvening, switchedOnAt, zone))

        assertNull(schedule.currentEveningRelease(at(today, "17:00"), zone))
        assertEquals(Release(at(today, "18:00"), EditionKind.EVENING), schedule.nextRelease(switchedOnAt, zone))
        assertEquals(at(today, "18:00"), schedule.currentEveningRelease(at(today, "19:00"), zone))
    }

    @Test
    fun aMorningChangeDoesNotMakeLastNightsEveningEditionCurrentAgain() {
        // 09:00, the morning reset moved to 10:00: the latest reset is still today's 06:30,
        // so yesterday's evening release stays behind it.
        val change = ReleaseSchedule.changeTo(time("10:00"), at(today, "09:00"), zone, at(today, "06:30"), at(tomorrow, "06:30"), nextIsBuilt = false)
        val schedule = ReleaseSchedule(time("10:00"), oldEvening, resetChange = change)

        assertNull(schedule.currentEveningRelease(at(today, "09:30"), zone))
        assertEquals(ReleaseChange(settled = at(today, "06:30"), kept = null, from = at(tomorrow, "10:00")), change)
    }
}

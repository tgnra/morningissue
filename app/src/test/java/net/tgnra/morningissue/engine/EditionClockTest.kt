package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class EditionClockTest {
    private val reset = LocalTime.of(6, 30)
    private val london = ZoneId.of("Europe/London")

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun beforeTheResetTimeTheLatestResetIsYesterdays() {
        val now = at("2026-11-10T05:00:00Z")
        assertEquals(at("2026-11-09T06:30:00Z"), EditionClock.latestReset(now, reset, london))
        assertEquals(at("2026-11-10T06:30:00Z"), EditionClock.nextReset(now, reset, london))
    }

    @Test
    fun afterTheResetTimeTheLatestResetIsTodays() {
        val now = at("2026-11-10T07:00:00Z")
        assertEquals(at("2026-11-10T06:30:00Z"), EditionClock.latestReset(now, reset, london))
        assertEquals(at("2026-11-11T06:30:00Z"), EditionClock.nextReset(now, reset, london))
    }

    @Test
    fun atTheResetInstantTheNewEditionIsCurrent() {
        val now = at("2026-11-10T06:30:00Z")
        assertEquals(now, EditionClock.latestReset(now, reset, london))
        assertEquals(at("2026-11-11T06:30:00Z"), EditionClock.nextReset(now, reset, london))
        assertEquals(at("2026-11-09T06:30:00Z"), EditionClock.latestReset(now.minusMillis(1), reset, london))
    }

    @Test
    fun editionDateIsTheLocalDateOfTheReset() {
        assertEquals(LocalDate.of(2026, 11, 10), EditionClock.editionDate(at("2026-11-10T06:30:00Z"), london))
        // 03:00 local on the 11th in Tokyo is still the 10th in UTC.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val early = EditionClock.latestReset(at("2026-11-10T19:00:00Z"), LocalTime.of(3, 0), tokyo)
        assertEquals(at("2026-11-10T18:00:00Z"), early)
        assertEquals(LocalDate.of(2026, 11, 11), EditionClock.editionDate(early, tokyo))
    }

    @Test
    fun springForwardShortensTheDayBetweenResets() {
        // Europe/London goes from GMT to BST at 01:00 UTC on 29 March 2026.
        val saturday = at("2026-03-28T06:30:00Z")
        val sunday = at("2026-03-29T05:30:00Z")
        val monday = at("2026-03-30T05:30:00Z")

        assertEquals(sunday, EditionClock.nextReset(saturday, reset, london))
        assertEquals(Duration.ofHours(23), Duration.between(saturday, sunday))
        assertEquals(saturday, EditionClock.latestReset(sunday.minusSeconds(1), reset, london))
        assertEquals(sunday, EditionClock.latestReset(sunday, reset, london))
        assertEquals(monday, EditionClock.nextReset(sunday, reset, london))
        assertEquals(LocalDate.of(2026, 3, 29), EditionClock.editionDate(sunday, london))
    }

    @Test
    fun resetTimeInsideTheSkippedHourMovesToTheNextValidMoment() {
        // 01:30 does not exist on 29 March 2026 in London; the reset happens at 02:30 BST.
        val skipped = LocalTime.of(1, 30)
        val next = EditionClock.nextReset(at("2026-03-28T12:00:00Z"), skipped, london)

        assertEquals(at("2026-03-29T01:30:00Z"), next)
        assertEquals(LocalDate.of(2026, 3, 29), EditionClock.editionDate(next, london))
        assertEquals(at("2026-03-30T00:30:00Z"), EditionClock.nextReset(next, skipped, london))
    }

    @Test
    fun fallBackLengthensTheDayAndARepeatedResetTimeHappensOnce() {
        // Europe/London goes back to GMT at 01:00 UTC on 25 October 2026.
        assertEquals(
            Duration.ofHours(25),
            Duration.between(
                EditionClock.latestReset(at("2026-10-24T12:00:00Z"), reset, london),
                EditionClock.nextReset(at("2026-10-24T12:00:00Z"), reset, london),
            ),
        )

        // 01:30 happens twice that night; the first one (BST) is the reset.
        val repeated = LocalTime.of(1, 30)
        val first = at("2026-10-25T00:30:00Z")
        val betweenTheTwo = at("2026-10-25T01:00:00Z")
        val afterBoth = at("2026-10-25T02:00:00Z")
        assertEquals(first, EditionClock.nextReset(at("2026-10-24T12:00:00Z"), repeated, london))
        assertEquals(first, EditionClock.latestReset(betweenTheTwo, repeated, london))
        assertEquals(first, EditionClock.latestReset(afterBoth, repeated, london))
        assertEquals(at("2026-10-26T01:30:00Z"), EditionClock.nextReset(betweenTheTwo, repeated, london))
    }

    @Test
    fun aTimeZoneChangeMovesTheResetAtOnce() {
        val now = at("2026-11-10T20:00:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val losAngeles = ZoneId.of("America/Los_Angeles")

        // Tokyo: 05:00 on the 11th, so the latest reset is the 10th at 06:30 JST.
        assertEquals(at("2026-11-09T21:30:00Z"), EditionClock.latestReset(now, reset, tokyo))
        assertEquals(at("2026-11-10T21:30:00Z"), EditionClock.nextReset(now, reset, tokyo))
        // Los Angeles: noon on the 10th, so the latest reset is the 10th at 06:30 PST.
        assertEquals(at("2026-11-10T14:30:00Z"), EditionClock.latestReset(now, reset, losAngeles))
        assertEquals(at("2026-11-11T14:30:00Z"), EditionClock.nextReset(now, reset, losAngeles))
    }

    @Test
    fun latestAndNextAreAlwaysConsecutiveResets() {
        val zones = listOf(london, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Pago_Pago"), ZoneOffset.UTC)
        val times = listOf(LocalTime.MIDNIGHT, LocalTime.of(3, 0), reset, LocalTime.of(23, 59))
        var now = at("2026-03-27T00:00:00Z")
        val end = at("2026-03-31T00:00:00Z")
        while (now.isBefore(end)) {
            for (zone in zones) for (time in times) {
                val latest = EditionClock.latestReset(now, time, zone)
                val next = EditionClock.nextReset(now, time, zone)
                assertEquals(true, !latest.isAfter(now) && next.isAfter(now))
                assertEquals(next, EditionClock.nextReset(latest, time, zone))
                assertEquals(
                    EditionClock.editionDate(latest, zone).plusDays(1),
                    EditionClock.editionDate(next, zone),
                )
            }
            now = now.plus(Duration.ofMinutes(37))
        }
    }
}

package net.tgnra.morningissue.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When editions are released. The reset happens once per local calendar day at the user's
 * reset time, in whatever zone is passed in; nothing is cached, so a time-zone change takes
 * effect on the next call.
 *
 * Daylight saving: on the day clocks go forward, a reset time inside the skipped hour moves to
 * the first valid moment after it (02:30 becomes 03:30); on the day they go back, a reset time
 * that happens twice uses the first occurrence. Either way there is exactly one reset per date.
 */
object EditionClock {
    /** The reset instant of [date] in [zone]. */
    fun resetOn(date: LocalDate, resetTime: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, resetTime, zone).toInstant()

    /** The most recent reset at or before [now]. */
    fun latestReset(now: Instant, resetTime: LocalTime, zone: ZoneId): Instant {
        var date = now.atZone(zone).toLocalDate().plusDays(1)
        while (true) {
            val reset = resetOn(date, resetTime, zone)
            if (!reset.isAfter(now)) return reset
            date = date.minusDays(1)
        }
    }

    /** The first reset strictly after [now]. */
    fun nextReset(now: Instant, resetTime: LocalTime, zone: ZoneId): Instant {
        var date = now.atZone(zone).toLocalDate().minusDays(1)
        while (true) {
            val reset = resetOn(date, resetTime, zone)
            if (reset.isAfter(now)) return reset
            date = date.plusDays(1)
        }
    }

    /** The calendar date an edition released at [reset] is published for. */
    fun editionDate(reset: Instant, zone: ZoneId): LocalDate = reset.atZone(zone).toLocalDate()
}

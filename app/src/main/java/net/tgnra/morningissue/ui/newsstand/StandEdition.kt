package net.tgnra.morningissue.ui.newsstand

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import net.tgnra.morningissue.engine.EditionClock

/** What the stand has to say about the paper facing out. */
enum class StandEdition {
    /** Nothing has been printed yet: the empty stand. */
    None,

    /** The newest edition is the one for the most recent reset. */
    Current,

    /**
     * A reset has passed without a new edition and the newest one is an earlier day's. Whether
     * that is because the feeds had nothing new (the engine never prints an empty or
     * reprint-only paper) or because the edition failed to build is `DueEditions`' call; the
     * stand only says "No new edition today" for the first.
     */
    NoNewEdition,
}

/** The stand's reading of the clock, kept pure so it can be tested. */
object StandEditions {
    /** The date of the edition that the most recent reset at or before [now] released. */
    fun expectedDate(now: Instant, resetTime: LocalTime, zone: ZoneId): LocalDate =
        EditionClock.editionDate(EditionClock.latestReset(now, resetTime, zone), zone)

    /**
     * Where the stand stands, given the date of the newest released edition ([newestDate], null
     * when there is none). It reads dates only; see `edition.DueEditions` for whether the
     * edition for the most recent reset is missing for want of news or failed to build.
     */
    fun of(newestDate: LocalDate?, now: Instant, resetTime: LocalTime, zone: ZoneId): StandEdition =
        of(newestDate, expectedDate(now, resetTime, zone))

    /**
     * The same, given the date of the edition that is due ([expected]). The stand passes the
     * date of the due release from `DueEditions`, which follows the release schedule and so
     * is not moved by a reset time changed earlier today.
     */
    fun of(newestDate: LocalDate?, expected: LocalDate): StandEdition = when {
        newestDate == null -> StandEdition.None
        newestDate.isBefore(expected) -> StandEdition.NoNewEdition
        else -> StandEdition.Current
    }

    /**
     * The weekday to call the last paper by ("Monday's paper") when it came out within the
     * week before [expected]; null when it is older and only its date is unambiguous.
     */
    fun sinceWeekday(newestDate: LocalDate, expected: LocalDate): DayOfWeek? =
        if (ChronoUnit.DAYS.between(newestDate, expected) in 1..6) newestDate.dayOfWeek else null
}

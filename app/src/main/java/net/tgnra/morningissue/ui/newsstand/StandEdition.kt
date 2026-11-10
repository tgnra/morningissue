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
     * A reset has passed without a new edition, because the feeds had nothing new: the engine
     * never prints an empty or reprint-only paper. The newest edition is an earlier day's.
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
     * when there is none). Only meaningful once `ensureCurrentEdition` has had its chance to
     * print the edition for the most recent reset.
     */
    fun of(newestDate: LocalDate?, now: Instant, resetTime: LocalTime, zone: ZoneId): StandEdition = when {
        newestDate == null -> StandEdition.None
        newestDate.isBefore(expectedDate(now, resetTime, zone)) -> StandEdition.NoNewEdition
        else -> StandEdition.Current
    }

    /**
     * The weekday to call the last paper by ("Monday's paper") when it came out within the
     * week before [expected]; null when it is older and only its date is unambiguous.
     */
    fun sinceWeekday(newestDate: LocalDate, expected: LocalDate): DayOfWeek? =
        if (ChronoUnit.DAYS.between(newestDate, expected) in 1..6) newestDate.dayOfWeek else null
}

package net.tgnra.morningissue.ui.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Dates, times and counts as the paper prints them. Pure, so the wording can be tested. */
object PaperFormat {
    /** "Tuesday 10 November 2026": the masthead and cover dateline. */
    fun longDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale).format(date)

    /** "Tue 10 Nov": under a racked cover. */
    fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)

    /** "10 Nov 2026": an article's date in bylines and lists. */
    fun articleDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(instant.atZone(zone))

    /** "November 2026": the back-issues calendar. */
    fun monthTitle(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)

    /** "6:30 am", or "06:30" on a 24-hour clock. */
    fun time(time: LocalTime, use24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        if (use24Hour) {
            DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
        } else {
            DateTimeFormatter.ofPattern("h:mm a", locale).format(time).lowercase(locale)
        }

    /** The next time the clock reads [resetTime] after [now]: when the next edition is due. */
    fun nextReset(now: Instant, resetTime: LocalTime, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate().atTime(resetTime).atZone(zone).toInstant()
        return if (today.isAfter(now)) today else now.atZone(zone).toLocalDate().plusDays(1).atTime(resetTime).atZone(zone).toInstant()
    }

    /**
     * The weeks of [month] as rows of seven cells starting on [firstDayOfWeek]; cells outside
     * the month are null.
     */
    fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate?>> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val cells = List<LocalDate?>(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        return cells.chunked(7).map { week -> week + List<LocalDate?>(7 - week.size) { null } }
    }

    /** The seven days of a week in order, starting on [firstDayOfWeek]. */
    fun weekDays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> = (0L..6L).map { firstDayOfWeek.plus(it) }
}

/** How often a feed posts, in the unit that reads best. */
sealed interface PostRate {
    data class PerWeek(val count: Int) : PostRate
    data class PerMonth(val count: Int) : PostRate
    data object LessThanMonthly : PostRate

    companion object {
        private const val WEEKS_PER_MONTH = 30.0 / 7.0

        /** Null when the cadence is not known yet. */
        fun of(postsPerWeek: Double?): PostRate? = when {
            postsPerWeek == null || postsPerWeek <= 0.0 -> null
            postsPerWeek >= 1.0 -> PerWeek(postsPerWeek.roundToInt())
            postsPerWeek * WEEKS_PER_MONTH >= 1.0 -> PerMonth((postsPerWeek * WEEKS_PER_MONTH).roundToInt())
            else -> LessThanMonthly
        }
    }
}

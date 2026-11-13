package net.tgnra.morningissue.engine

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.ReleaseChange

/**
 * When editions are released, given the release times in the settings **and how the last
 * change to each takes effect**. Everything that asks "which release is the latest" or "when
 * is the next one" (the publisher, the early build's scheduler, the newsstand, the back
 * page) asks this, never [EditionClock] with a bare time, so they all agree.
 *
 * **The rule: a changed release time applies from the next release, never to one that has
 * already come.** The latest release at the moment of the change stays the latest release,
 * whatever the new time would say about today, so what was settled for it (printed, nothing
 * new, or failed to build) stays settled, and nothing new becomes due. The same goes for the
 * Evening edition's time, and for switching the Evening edition on, which is a change from
 * "no release" to a time.
 *
 * What "the next release" is, for a change made at instant C ([changeTo]):
 *
 * - Normally the coming release moves to the new time **on its own date**. With the reset at
 *   06:30 and the time changed at 09:00, tomorrow's edition comes at the new time, whether
 *   that is 08:00 or 10:00; today gets no second release at 10:00. Changed at 05:00 to 07:00,
 *   today's edition comes at 07:00.
 * - The coming release **stays at the old time**, and the new time starts the day after, in
 *   two cases: when the new time on that date has already passed (changed at 05:00 from 06:30
 *   to 04:00: today's edition still comes at 06:30, tomorrow's at 04:00; making it due at once
 *   would be retroactive, and skipping it would lose a day), and when its edition has
 *   **already been built** ahead of time (it was frozen with the old release time and is
 *   released then, as promised; it is not re-timed, rebuilt or discarded).
 *
 * So there is always exactly one release per kind and date, before, across and after a change.
 *
 * The mechanism is a [ReleaseChange] stored beside each time: the release instants on either
 * side of the change. Until its `from` instant the schedule is read from those instants alone;
 * from then on it is the plain time and the record no longer matters. Storing instants rather
 * than the old time is what keeps the settled release exactly where it was, so that it still
 * matches the edition and the "nothing new" record made for it, and it makes a second change
 * before the first has taken effect come out right.
 *
 * @param eveningTime the Evening edition's time, or null when it is switched off
 */
class ReleaseSchedule(
    private val resetTime: LocalTime,
    private val eveningTime: LocalTime?,
    private val resetChange: ReleaseChange? = null,
    private val eveningChange: ReleaseChange? = null,
) {
    /** The most recent morning reset at or before [now]. */
    fun latestReset(now: Instant, zone: ZoneId): Instant =
        resetChange?.takeIf { it.governs(now) }?.latest(now) ?: EditionClock.latestReset(now, resetTime, zone)

    /** The first morning reset strictly after [now]. */
    fun nextReset(now: Instant, zone: ZoneId): Instant =
        resetChange?.takeIf { it.governs(now) }?.next(now) ?: EditionClock.nextReset(now, resetTime, zone)

    /** The most recent evening release at or before [now]; null when there has been none or it is switched off. */
    fun latestEvening(now: Instant, zone: ZoneId): Instant? {
        val time = eveningTime ?: return null
        val change = eveningChange?.takeIf { it.governs(now) } ?: return EditionClock.latestReset(now, time, zone)
        return change.latest(now)
    }

    /** The first evening release strictly after [now]; null when it is switched off. */
    fun nextEvening(now: Instant, zone: ZoneId): Instant? {
        val time = eveningTime ?: return null
        return eveningChange?.takeIf { it.governs(now) }?.next(now) ?: EditionClock.nextReset(now, time, zone)
    }

    /**
     * The evening release whose edition is current at [now]: the latest one, provided it came
     * after the latest morning reset. Null in the morning, and when it is switched off.
     */
    fun currentEveningRelease(now: Instant, zone: ZoneId): Instant? =
        latestEvening(now, zone)?.takeIf { it.isAfter(latestReset(now, zone)) }

    /** The first scheduled release strictly after [now], of either kind. At the same instant the morning wins. */
    fun nextRelease(now: Instant, zone: ZoneId): Release {
        val morning = Release(nextReset(now, zone), EditionKind.MORNING)
        val evening = nextEvening(now, zone)?.let { Release(it, EditionKind.EVENING) }
        return if (evening != null && evening.at.isBefore(morning.at)) evening else morning
    }

    /** The record still decides at [now]: its new time has not started, and the clock has not been set back before it. */
    private fun ReleaseChange.governs(now: Instant): Boolean =
        now.isBefore(from) && (settled == null || !settled.isAfter(now))

    private fun ReleaseChange.latest(now: Instant): Instant? =
        if (kept != null && !kept.isAfter(now)) kept else settled

    private fun ReleaseChange.next(now: Instant): Instant =
        if (kept != null && kept.isAfter(now)) kept else from

    companion object {
        /**
         * How a change of a release time to [newTime], made at [now], takes effect; see the
         * class comment for the rule.
         *
         * @param latest the latest release of this kind under the schedule so far, or null if there has been none
         * @param next the coming release of this kind under the schedule so far
         * @param nextIsBuilt whether the edition for [next] has already been built ahead of time
         */
        fun changeTo(
            newTime: LocalTime,
            now: Instant,
            zone: ZoneId,
            latest: Instant?,
            next: Instant,
            nextIsBuilt: Boolean,
        ): ReleaseChange {
            val date = EditionClock.editionDate(next, zone)
            val moved = EditionClock.resetOn(date, newTime, zone)
            return if (!nextIsBuilt && moved.isAfter(now)) {
                ReleaseChange(settled = latest, kept = null, from = moved)
            } else {
                ReleaseChange(settled = latest, kept = next, from = EditionClock.resetOn(date.plusDays(1), newTime, zone))
            }
        }

        /**
         * Switching a release on at [now] (the Evening edition): nothing before now counts,
         * so today's time, if it has already passed, does not become due.
         */
        fun switchedOn(time: LocalTime, now: Instant, zone: ZoneId): ReleaseChange =
            ReleaseChange(settled = null, kept = null, from = EditionClock.nextReset(now, time, zone))
    }
}

/** The release schedule these settings describe. */
val AppSettings.schedule: ReleaseSchedule
    get() = ReleaseSchedule(
        resetTime = resetTime,
        eveningTime = eveningEditionTime.takeIf { eveningEditionEnabled },
        resetChange = resetChange,
        eveningChange = eveningChange,
    )

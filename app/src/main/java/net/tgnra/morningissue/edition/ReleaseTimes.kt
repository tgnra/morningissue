package net.tgnra.morningissue.edition

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.ReleaseSchedule
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.EditionKind

/**
 * Changes the release times (the morning reset, the Evening edition's time and its switch) so
 * that a change **applies from the next release and never retroactively**. Settings goes
 * through this; nothing in the UI sets these three on `SettingsRepository` directly.
 *
 * Each change is stored together with a `ReleaseChange`: the release instants on either side
 * of it, worked out here from the schedule as it stood and from whether the coming edition
 * has already been built. `ReleaseSchedule` has the rule and reads the record back.
 *
 * An edition already built ahead of time for the old time is left exactly as it is: it is
 * released at the time it was built for, and the new time starts with the edition after it.
 *
 * Switching the Evening edition off takes effect at once (its paper is simply no longer due);
 * switching it on counts as a change, so an evening time that has already passed today does
 * not make an edition due today.
 *
 * @param isBuilt whether an edition of that kind is stored for that date, released or not
 * @param zone the device's current time zone
 */
class ReleaseTimes(
    private val settings: SettingsRepository,
    private val isBuilt: suspend (date: LocalDate, kind: EditionKind) -> Boolean,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()

    /** Sets the morning reset time (seconds are dropped). Setting the time it already has does nothing. */
    suspend fun setResetTime(time: LocalTime): Unit = mutex.withLock {
        val newTime = time.truncatedTo(ChronoUnit.MINUTES)
        val current = settings.settings.first()
        if (newTime == current.resetTime) return@withLock
        val now = clock.instant()
        val zone = zone()
        val schedule = current.schedule
        val next = schedule.nextReset(now, zone)
        val change = ReleaseSchedule.changeTo(
            newTime = newTime,
            now = now,
            zone = zone,
            latest = schedule.latestReset(now, zone),
            next = next,
            nextIsBuilt = isBuilt(EditionClock.editionDate(next, zone), EditionKind.MORNING),
        )
        settings.changeResetTime(newTime, change)
    }

    /** Sets the Evening edition's release time (seconds are dropped). */
    suspend fun setEveningEditionTime(time: LocalTime): Unit = mutex.withLock {
        val newTime = time.truncatedTo(ChronoUnit.MINUTES)
        val current = settings.settings.first()
        if (newTime == current.eveningEditionTime) return@withLock
        if (!current.eveningEditionEnabled) {
            // No evening release is scheduled, so there is nothing for the change to disturb.
            settings.changeEveningEdition(enabled = false, time = newTime, change = null)
            return@withLock
        }
        val now = clock.instant()
        val zone = zone()
        val schedule = current.schedule
        val next = schedule.nextEvening(now, zone) ?: return@withLock
        val change = ReleaseSchedule.changeTo(
            newTime = newTime,
            now = now,
            zone = zone,
            latest = schedule.latestEvening(now, zone),
            next = next,
            nextIsBuilt = isBuilt(EditionClock.editionDate(next, zone), EditionKind.EVENING),
        )
        settings.changeEveningEdition(enabled = true, time = newTime, change = change)
    }

    /** Switches the Evening edition on or off. */
    suspend fun setEveningEditionEnabled(enabled: Boolean): Unit = mutex.withLock {
        val current = settings.settings.first()
        if (enabled == current.eveningEditionEnabled) return@withLock
        val change = if (enabled) ReleaseSchedule.switchedOn(current.eveningEditionTime, clock.instant(), zone()) else null
        settings.changeEveningEdition(enabled = enabled, time = current.eveningEditionTime, change = change)
    }
}

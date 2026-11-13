package net.tgnra.morningissue.server.editions

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.data.archive.ArchiveReleaseChange
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.ReleaseSchedule
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.SettingsService

/**
 * Changes a user's release times so that a change **applies from the next release and never
 * retroactively**: the app's `ReleaseTimes`, server-side (`PUT /settings/release`). Each change
 * is stored with a `ReleaseChange` worked out from the schedule as it stood and from whether
 * the coming edition has already been built; `ReleaseSchedule` reads it back. The scheduler
 * picks the new schedule up on its next tick.
 */
class ReleaseTimes(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
) {
    private val editionDao = database.editionDao()
    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    private suspend fun isBuilt(userId: Long, next: java.time.Instant, zone: ZoneId, kind: EditionKind) =
        editionDao.getByDate(userId, EditionClock.editionDate(next, zone), kind) != null

    /**
     * Applies [request] in an order that keeps each step meaningful: switching the Evening
     * edition on takes the requested evening time, and a time set while it is off disturbs
     * nothing. Returns the settings after.
     */
    suspend fun apply(userId: Long, request: ReleaseTimesRequest): StoredSettings = mutexes.computeIfAbsent(userId) { Mutex() }.withLock {
        database.withWriteTransaction {
            request.resetTime?.let { setResetTime(userId, it) }
            val current = settings.get(userId)
            val enable = request.eveningEnabled
            if (enable == true && !current.eveningEnabled) {
                request.eveningTime?.let { setEveningTime(userId, it) }
                setEveningEnabled(userId, true)
            } else {
                enable?.let { setEveningEnabled(userId, it) }
                request.eveningTime?.let { setEveningTime(userId, it) }
            }
            settings.get(userId)
        }
    }

    /** Sets the morning reset time (seconds dropped). Setting the time it already has does nothing. */
    private suspend fun setResetTime(userId: Long, time: LocalTime) {
        val newTime = time.truncatedTo(ChronoUnit.MINUTES)
        val current = settings.get(userId)
        if (newTime == current.reset) return
        val now = clock.instant()
        val zone = current.zone
        val schedule = current.schedule
        val next = schedule.nextReset(now, zone)
        val change = ReleaseSchedule.changeTo(
            newTime = newTime,
            now = now,
            zone = zone,
            latest = schedule.latestReset(now, zone),
            next = next,
            nextIsBuilt = isBuilt(userId, next, zone, EditionKind.MORNING),
        )
        settings.update(userId, customize = true) {
            it.copy(resetTime = StoredSettings.time(newTime), resetChange = ArchiveReleaseChange.of(change))
        }
    }

    /** Sets the Evening edition's release time (seconds dropped). */
    private suspend fun setEveningTime(userId: Long, time: LocalTime) {
        val newTime = time.truncatedTo(ChronoUnit.MINUTES)
        val current = settings.get(userId)
        if (newTime == current.evening) return
        if (!current.eveningEnabled) {
            // No evening release is scheduled, so there is nothing for the change to disturb.
            settings.update(userId, customize = true) { it.copy(eveningTime = StoredSettings.time(newTime), eveningChange = null) }
            return
        }
        val now = clock.instant()
        val zone = current.zone
        val schedule = current.schedule
        val next = schedule.nextEvening(now, zone) ?: return
        val change = ReleaseSchedule.changeTo(
            newTime = newTime,
            now = now,
            zone = zone,
            latest = schedule.latestEvening(now, zone),
            next = next,
            nextIsBuilt = isBuilt(userId, next, zone, EditionKind.EVENING),
        )
        settings.update(userId, customize = true) {
            it.copy(eveningTime = StoredSettings.time(newTime), eveningChange = ArchiveReleaseChange.of(change))
        }
    }

    /** Switches the Evening edition on (a change from "no release": today's passed time is not due) or off (at once). */
    private suspend fun setEveningEnabled(userId: Long, enabled: Boolean) {
        val current = settings.get(userId)
        if (enabled == current.eveningEnabled) return
        val change = if (enabled) ReleaseSchedule.switchedOn(current.evening, clock.instant(), current.zone) else null
        settings.update(userId, customize = true) {
            it.copy(eveningEnabled = enabled, eveningChange = change?.let(ArchiveReleaseChange::of))
        }
    }
}

/**
 * `GET`/`PATCH /settings` and `PUT /settings/release` (4.4).
 *
 * A PATCH marks the settings customized, so an import no longer applies the phone's, unless it
 * only sets the time zone (`timeZone`, `followDeviceTimeZone`): the phone sends that on its own
 * at login, before a migration upload, and it must not stop an empty account from taking the
 * phone's schedule.
 */
class SettingsServiceImpl(
    database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
) : SettingsService {
    private val releaseTimes = ReleaseTimes(database, settings, clock)

    override suspend fun settings(userId: Long): ServerSettings = settings.get(userId).toApi(clock.instant())

    override suspend fun patch(userId: Long, patch: SettingsPatch): ServerSettings {
        val zone = patch.timeZone?.let { id ->
            try {
                ZoneId.of(id).id
            } catch (_: DateTimeException) {
                throw ApiFailure.badRequest("Unknown time zone: $id")
            }
        }
        val customizes = patch.editionLength != null || patch.onboardingDone != null || patch.extraOfferDismissedAt is Patch.Set
        val updated = settings.update(userId, customize = customizes) { current ->
            current.copy(
                editionLength = patch.editionLength ?: current.editionLength,
                timeZone = zone ?: current.timeZone,
                followDeviceTimeZone = patch.followDeviceTimeZone ?: current.followDeviceTimeZone,
                onboardingDone = patch.onboardingDone ?: current.onboardingDone,
                extraOfferDismissedAt = when (val dismissed = patch.extraOfferDismissedAt) {
                    is Patch.Set -> dismissed.value?.toEpochMilli()
                    Patch.Absent -> current.extraOfferDismissedAt
                },
            )
        }
        return updated.toApi(clock.instant())
    }

    override suspend fun setReleaseTimes(userId: Long, request: ReleaseTimesRequest): ServerSettings =
        releaseTimes.apply(userId, request).toApi(clock.instant())
}

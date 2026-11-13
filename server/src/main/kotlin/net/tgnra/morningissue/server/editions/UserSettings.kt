package net.tgnra.morningissue.server.editions

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.api.ReleaseDto
import net.tgnra.morningissue.api.ServerSettings
import net.tgnra.morningissue.data.archive.ArchiveReleaseChange
import net.tgnra.morningissue.engine.ReleaseSchedule
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.UserSettingsEntity

/**
 * A user's settings as stored in `user_settings.json`: what `GET /settings` shows plus the
 * server-only fields (`nothingNewFor`, `resetChange`, `eveningChange`). Times of day are
 * `HH:mm`, instants epoch milliseconds, as in the archive's `settings.json`.
 */
@Serializable
data class StoredSettings(
    val resetTime: String = AppSettings.DEFAULT_RESET_TIME.format(TIME_OF_DAY),
    val editionLength: EditionLength = EditionLength.STANDARD,
    val eveningEnabled: Boolean = false,
    val eveningTime: String = AppSettings.DEFAULT_EVENING_TIME.format(TIME_OF_DAY),
    /** An IANA zone ID. */
    val timeZone: String = "UTC",
    val followDeviceTimeZone: Boolean = true,
    val onboardingDone: Boolean = false,
    val extraOfferDismissedAt: Long? = null,
    /** Releases a run reached the feeds for and found nothing new to print, newest first (`DueEditions`). */
    val nothingNewFor: List<Long> = emptyList(),
    val resetChange: ArchiveReleaseChange? = null,
    val eveningChange: ArchiveReleaseChange? = null,
) {
    val reset: LocalTime get() = LocalTime.parse(resetTime, TIME_OF_DAY)
    val evening: LocalTime get() = LocalTime.parse(eveningTime, TIME_OF_DAY)

    /** The zone editions are scheduled in; UTC if the stored ID has become unknown. */
    val zone: ZoneId get() = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("UTC"))

    /** The release schedule these settings describe, with how the last changes take effect. */
    val schedule: ReleaseSchedule
        get() = ReleaseSchedule(
            resetTime = reset,
            eveningTime = evening.takeIf { eveningEnabled },
            resetChange = resetChange?.toReleaseChange(),
            eveningChange = eveningChange?.toReleaseChange(),
        )

    val nothingNew: List<Instant> get() = nothingNewFor.map(Instant::ofEpochMilli)

    /** What the API shows, with the next release worked out at [now]. */
    fun toApi(now: Instant): ServerSettings {
        val next = schedule.nextRelease(now, zone)
        return ServerSettings(
            resetTime = reset,
            editionLength = editionLength,
            eveningEnabled = eveningEnabled,
            eveningTime = evening,
            timeZone = timeZone,
            followDeviceTimeZone = followDeviceTimeZone,
            onboardingDone = onboardingDone,
            extraOfferDismissedAt = extraOfferDismissedAt?.let(Instant::ofEpochMilli),
            nextRelease = ReleaseDto(next.kind, next.at),
        )
    }

    /** With [releaseAt] recorded as a release that had nothing new, keeping [AppSettings.NOTHING_NEW_KEPT]. */
    fun withNothingNew(releaseAt: Instant): StoredSettings =
        copy(nothingNewFor = (listOf(releaseAt.toEpochMilli()) + nothingNewFor).distinct().take(AppSettings.NOTHING_NEW_KEPT))

    companion object {
        val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun time(value: LocalTime): String = value.format(TIME_OF_DAY)
    }
}

/**
 * Reads and writes [StoredSettings] in `user_settings`. A user with no row has the defaults,
 * in [defaultZone], and is not `customized`.
 */
class UserSettingsStore(
    private val database: ServerDatabase,
    private val clock: Clock,
    private val defaultZone: ZoneId = ZoneId.systemDefault(),
) {
    private val dao = database.userStateDao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The defaults a user without a row has. */
    fun defaults(): StoredSettings = StoredSettings(timeZone = defaultZone.id)

    suspend fun get(userId: Long): StoredSettings = dao.settings(userId)?.let(::decode) ?: defaults()

    /** Whether the user has set anything, or an import applied the phone's settings (4.9). */
    suspend fun isCustomized(userId: Long): Boolean = dao.settings(userId)?.customized ?: false

    /**
     * Replaces the settings with [transform] of the current ones. [customize] true marks them
     * customized; false keeps the flag as it was. Callers that need it atomic with other writes
     * run this inside their transaction.
     */
    suspend fun update(userId: Long, customize: Boolean, transform: (StoredSettings) -> StoredSettings): StoredSettings {
        val row = dao.settings(userId)
        val current = row?.let(::decode) ?: defaults()
        val next = transform(current)
        dao.putSettings(
            UserSettingsEntity(
                userId = userId,
                json = json.encodeToString(StoredSettings.serializer(), next),
                customized = customize || (row?.customized ?: false),
                updatedAt = clock.instant(),
            ),
        )
        return next
    }

    /** Records that a run reached the feeds for [releaseAt] and found nothing new to print. */
    suspend fun recordNothingNew(userId: Long, releaseAt: Instant) {
        update(userId, customize = false) { it.withNothingNew(releaseAt) }
    }

    private fun decode(row: UserSettingsEntity): StoredSettings =
        runCatching { json.decodeFromString(StoredSettings.serializer(), row.json) }.getOrElse { defaults() }
}

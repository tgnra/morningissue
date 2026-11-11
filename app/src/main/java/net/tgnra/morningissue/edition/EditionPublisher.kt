package net.tgnra.morningissue.edition

import java.time.Clock
import java.time.Instant
import net.tgnra.morningissue.data.db.EditionDao

/**
 * Builds and releases editions. **Owned by the engine/edition agent**; the stub wired in
 * milestone 0 is [StoredEditionPublisher]. Called through `EditionRepository` by the UI and
 * directly by `EarlyBuildWorker`.
 *
 * Implementations run the pure `engine.EditionBuilder` over candidates from the DAOs and store
 * the result with `EditionDao.insertEditionWithItems`. A stored edition is frozen: it is never
 * rebuilt or reordered.
 */
interface EditionPublisher {
    /**
     * Returns the ID of the edition for the most recent reset time, building it first if it is
     * missing: from stored posts only, with `cutoffAt = now` and `releaseAt` = that reset time
     * (target under 300 ms). Never touches the network. Returns null when
     * no edition exists and none can be built (no feeds or no posts). Safe to call concurrently
     * and repeatedly; at most one edition is created per date and kind.
     *
     * This used to run every time the newsstand opened (the on-open fallback). It no longer
     * does: it is the build stage of Print now and of a background run that started after its
     * reset (both through `EarlyBuild`), and onboarding's first edition.
     */
    suspend fun ensureCurrentEdition(): Long?

    /**
     * Builds the edition for the *next* release ahead of time (`cutoffAt = now`, `releaseAt` =
     * the next reset, or the evening edition's time when that is switched on and comes first)
     * and stores it unreleased. Does not sync or prefetch; the caller does that
     * around this call. Returns the edition's ID, the existing one if it was already built, or
     * null when there is nothing to build from.
     */
    suspend fun buildNextEdition(): Long?

    /**
     * When the next edition will be released, from the reset time (and, when it is switched
     * on, the evening edition's time) and the current time zone; shown on the back page. Null
     * if it cannot be determined.
     */
    suspend fun nextReleaseAt(): Instant?

    /**
     * Prints an Extra edition at once from the stored posts of [feedIds] (sync them first):
     * their posts first seen in the last few days that no edition has printed yet, each feed's
     * newest five whatever their age. Released immediately and returned by ID.
     *
     * It does not disturb the schedule: no cutoff moves, today's edition is untouched, and
     * nothing is superseded. Stories it prints are not new in the next morning edition; posts
     * it only lists in its index still are.
     *
     * There is one Extra edition per calendar date. If today already has one, it is
     * **reprinted** to include [feedIds]: set again from its own feeds and these, and stored
     * in the first printing's place under the same ID and number, as the newest paper on the
     * stand. This is the one case in which a stored edition changes.
     *
     * Returns null, storing and changing nothing, when those feeds have nothing printable.
     */
    suspend fun publishExtraEdition(feedIds: Set<Long>): Long? = null
}

/**
 * Keeps the early build scheduled. **Owned by the engine/edition agent**; the stub is
 * [NoOpEditionScheduler]. Called from `MorningIssueApp` on process start and whenever the reset
 * time setting changes, and from `TimeChangeReceiver` when the clock or time zone changes.
 */
interface EditionScheduler {
    /**
     * (Re)schedules the unique one-off `EarlyBuildWorker` for `nextReset − earlyBuildLead`,
     * replacing any earlier schedule. Idempotent and cheap; must not block.
     */
    fun scheduleEarlyBuild()
}

/** Shows whatever editions are already stored; never builds one. */
class StoredEditionPublisher(
    private val editionDao: EditionDao,
    private val clock: Clock,
) : EditionPublisher {
    override suspend fun ensureCurrentEdition(): Long? = editionDao.getLatestReleased(clock.instant())?.id
    override suspend fun buildNextEdition(): Long? = null
    override suspend fun nextReleaseAt(): Instant? = null
}

class NoOpEditionScheduler : EditionScheduler {
    override fun scheduleEarlyBuild() = Unit
}

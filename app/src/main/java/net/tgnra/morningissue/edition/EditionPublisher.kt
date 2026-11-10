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
     * (the on-open fallback; target under 300 ms). Never touches the network. Returns null when
     * no edition exists and none can be built (no feeds or no posts). Safe to call concurrently
     * and repeatedly; at most one edition is created per date and kind.
     */
    suspend fun ensureCurrentEdition(): Long?

    /**
     * Builds the edition for the *next* reset ahead of time (`cutoffAt = now`, `releaseAt` =
     * next reset) and stores it unreleased. Does not sync or prefetch; the caller does that
     * around this call. Returns the edition's ID, the existing one if it was already built, or
     * null when there is nothing to build from.
     */
    suspend fun buildNextEdition(): Long?

    /**
     * When the next edition will be released, from the reset time setting and the current time
     * zone; shown on the back page. Null if it cannot be determined.
     */
    suspend fun nextReleaseAt(): Instant?
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

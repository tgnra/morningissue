package net.tgnra.morningissue.edition

import java.time.Clock
import java.time.Instant
import net.tgnra.morningissue.data.db.EditionDao

/**
 * Builds and releases editions. **Owned by the engine/edition agent**; the stub wired in
 * milestone 0 is [StoredEditionPublisher]. Called through `EditionRepository` by the UI and
 * directly by the print (`EditionPress`), which runs in the foreground when the reader opens
 * the app.
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
     * This is the build stage of the print on open, of Print now (both through `EditionPress`)
     * and of onboarding's first edition. Nothing builds an edition ahead of its release any
     * more.
     */
    suspend fun ensureCurrentEdition(): Long?

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

    /**
     * The article IDs of the best [limit] candidates of the next build, best first, by their
     * score without the model's rating: what the on-device model should rate before that build
     * (`ai/InterestRater`). The next build is the due edition while it is still to be printed
     * (what [ensureCurrentEdition] would build now), otherwise the edition of the coming
     * release. Reads only. Empty when there is nothing new for it.
     */
    suspend fun shortlist(limit: Int): List<Long> = emptyList()

    /**
     * The same for the Extra edition [publishExtraEdition] would print from [feedIds] now
     * (or reprint, when today already has one). Reads only.
     */
    suspend fun extraShortlist(feedIds: Set<Long>, limit: Int): List<Long> = emptyList()
}

/** Shows whatever editions are already stored; never builds one. */
class StoredEditionPublisher(
    private val editionDao: EditionDao,
    private val clock: Clock,
) : EditionPublisher {
    override suspend fun ensureCurrentEdition(): Long? = editionDao.getLatestReleased(clock.instant())?.id
    override suspend fun nextReleaseAt(): Instant? = null
}

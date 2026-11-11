package net.tgnra.morningissue.data.repository

import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionSummary

/**
 * Reading stored editions. Called by the UI (newsstand, paper, back issues). Implemented by
 * [RoomEditionRepository], which reads Room and delegates building to `EditionPublisher`.
 */
interface EditionRepository {
    /**
     * Released editions (release time at or before now), newest first. Emits again when an
     * edition is stored or changed, and when a stored edition's release time passes while
     * this is being collected. Unreleased editions are never included.
     */
    fun observeReleasedEditions(): Flow<List<EditionSummary>>

    /**
     * One edition laid out as pages. Emits again when a story's opened, read, liked or saved
     * state changes; the stories and their order never change. Emits null if the edition does
     * not exist. The edition is returned whether or not it has been released.
     */
    fun observeEdition(editionId: Long): Flow<Edition?>

    /** A snapshot of [observeEdition]. */
    suspend fun getEdition(editionId: Long): Edition?

    /**
     * Makes sure the edition for the most recent reset time exists, building it from stored
     * posts if the early build did not run, and returns its ID. Never syncs. Returns null when
     * there is nothing to show (for example no feeds yet). Call when the app comes to the
     * foreground; cheap when the edition already exists.
     */
    suspend fun ensureCurrentEdition(): Long?

    /**
     * Prints an Extra edition from the stored posts of [feedIds] and returns its ID; see
     * `EditionPublisher.publishExtraEdition`. Sync the feeds first. Null when nothing was
     * printed (no posts, or today already has an Extra edition).
     */
    suspend fun publishExtraEdition(feedIds: Set<Long>): Long? = null

    /** Records that the reader opened the edition. Only the first call counts. */
    suspend fun markEditionOpened(editionId: Long)

    /** Records that the reader reached the back page; the cover then shows as read. */
    suspend fun markEditionFinished(editionId: Long)
}

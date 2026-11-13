package net.tgnra.morningissue.data.repository

import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionSummary

/**
 * Reading editions. Called by the UI (newsstand, paper, back issues). Implemented by
 * `client.CachedEditionRepository`, which reads the cache; editions are printed on the server.
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
     * Syncs with the server and returns the ID of the newest edition on the stand, or null when
     * there is none. Printing is the server's (`client.PrintClient`).
     */
    suspend fun ensureCurrentEdition(): Long?

    /** Records that the reader opened the edition. Only the first call counts. */
    suspend fun markEditionOpened(editionId: Long)

    /** Records that the reader reached the back page; the cover then shows as read. */
    suspend fun markEditionFinished(editionId: Long)

    /**
     * Adds [millis] to the time page [page] (1-based) of the edition has been on screen. A
     * printed story only counts as passed over once its page has been on screen for
     * `RankingConfig.pageSeenMinMillis` in all; see `EditionDao.SEEN`.
     */
    suspend fun recordPageView(editionId: Long, page: Int, millis: Long) {}
}

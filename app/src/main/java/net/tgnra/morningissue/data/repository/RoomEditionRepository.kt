package net.tgnra.morningissue.data.repository

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import net.tgnra.morningissue.data.db.EditionCoverRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.edition.EditionPublisher
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionSummary

class RoomEditionRepository(
    database: MorningIssueDatabase,
    private val publisher: EditionPublisher,
    private val clock: Clock,
) : EditionRepository {
    private val editionDao = database.editionDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeReleasedEditions(): Flow<List<EditionSummary>> =
        editionDao.observeCovers().transformLatest { covers ->
            emit(released(covers))
            // Editions built early become visible when their release time passes.
            var pending = covers.filter { it.edition.releaseAt > clock.instant() }
            while (pending.isNotEmpty()) {
                val next = pending.minOf { it.edition.releaseAt }
                delay(Duration.between(clock.instant(), next).toMillis().coerceAtLeast(0) + 1)
                emit(released(covers))
                pending = covers.filter { it.edition.releaseAt > clock.instant() }
            }
        }.distinctUntilChanged()

    override fun observeEdition(editionId: Long): Flow<Edition?> =
        combine(editionDao.observeById(editionId), editionDao.observeItems(editionId)) { edition, items ->
            edition?.let { EditionPages.toEdition(it, items, publisher.nextReleaseAt()) }
        }

    override suspend fun getEdition(editionId: Long): Edition? = observeEdition(editionId).first()

    override suspend fun ensureCurrentEdition(): Long? = publisher.ensureCurrentEdition()

    override suspend fun publishExtraEdition(feedIds: Set<Long>): Long? = publisher.publishExtraEdition(feedIds)

    override suspend fun markEditionOpened(editionId: Long) = editionDao.markOpened(editionId, clock.instant())

    override suspend fun markEditionFinished(editionId: Long) = editionDao.markFinished(editionId, clock.instant())

    private fun released(covers: List<EditionCoverRow>): List<EditionSummary> {
        val now = clock.instant()
        return covers.filter { it.edition.releaseAt <= now }.map { it.toSummary() }
    }
}

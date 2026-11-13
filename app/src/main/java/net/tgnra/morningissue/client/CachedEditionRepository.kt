package net.tgnra.morningissue.client

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.data.db.EditionCoverRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.EditionPages
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.repository.toSummary
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionSummary

/**
 * Editions from the cache. An edition whose stories the cache does not hold (an older back
 * issue, or one the storage trim reached) is fetched from the server when it is opened.
 */
class CachedEditionRepository(
    private val database: MorningIssueDatabase,
    private val engagement: LocalEngagement,
    private val sync: ServerSync,
    private val editions: EditionFetcher,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : EditionRepository {
    private val editionDao = database.editionDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeReleasedEditions(): Flow<List<EditionSummary>> =
        editionDao.observeCovers().transformLatest { covers ->
            emit(released(covers))
            // An edition the server built early becomes visible when its release time passes.
            var pending = covers.filter { it.edition.releaseAt > clock.instant() }
            while (pending.isNotEmpty()) {
                val next = pending.minOf { it.edition.releaseAt }
                delay(Duration.between(clock.instant(), next).toMillis().coerceAtLeast(0) + 1)
                emit(released(covers))
                pending = covers.filter { it.edition.releaseAt > clock.instant() }
            }
        }.distinctUntilChanged()

    override fun observeEdition(editionId: Long): Flow<Edition?> = channelFlow {
        launch { if (database.cacheDao().itemCount(editionId) == 0) editions.fetch(editionId) }
        combine(editionDao.observeById(editionId), editionDao.observeItems(editionId)) { edition, items ->
            edition?.let { EditionPages.toEdition(it, items, nextReleaseAt()) }
        }.collect { send(it) }
    }

    override suspend fun getEdition(editionId: Long): Edition? {
        if (database.cacheDao().itemCount(editionId) == 0) editions.fetch(editionId)
        val edition = editionDao.getById(editionId) ?: return null
        return EditionPages.toEdition(edition, editionDao.getItems(editionId), nextReleaseAt())
    }

    override suspend fun ensureCurrentEdition(): Long? {
        sync.sync("current edition")
        return editionDao.getLatestReleased(clock.instant())?.id
    }

    override suspend fun markEditionOpened(editionId: Long) = engagement.markEditionOpened(editionId)

    override suspend fun markEditionFinished(editionId: Long) = engagement.markEditionFinished(editionId)

    override suspend fun recordPageView(editionId: Long, page: Int, millis: Long) = engagement.recordPageView(editionId, page, millis)

    private suspend fun nextReleaseAt(): Instant? =
        runCatching { settings.settings.first().schedule.nextRelease(clock.instant(), zone()).at }.getOrNull()

    private fun released(covers: List<EditionCoverRow>): List<EditionSummary> {
        val now = clock.instant()
        return covers.filter { it.edition.releaseAt <= now }.map { it.toSummary() }
    }
}

/** Fetches one edition's bundle into the cache (`GET /editions/{id}/bundle`, through the events gate). */
class EditionFetcher(
    private val connection: ServerConnection,
    private val uploader: OutboxUploader,
    private val writer: CacheWriter,
) {
    /** True when the edition is now in the cache; false offline or when the server does not have it. */
    suspend fun fetch(editionId: Long): Boolean = try {
        connection.call { api -> writer.storeBundle(uploader.gated(api) { through -> api.bundle(editionId, through) }) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        false
    }
}

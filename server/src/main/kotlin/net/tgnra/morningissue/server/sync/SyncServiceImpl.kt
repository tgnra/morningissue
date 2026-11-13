package net.tgnra.morningissue.server.sync

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.ManifestDto
import net.tgnra.morningissue.api.ManifestEditionDto
import net.tgnra.morningissue.api.ManifestSavedDto
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.server.service.SyncService
import org.slf4j.LoggerFactory

/**
 * Event upload, the events gate and the manifest (4.2, 4.3), the same for phones and Kobos.
 *
 * After a merge that changed opened, read, saved or Liked state, the user's affinity is
 * relearned through [relearn] (debounced); newly saved articles get their text fetched through
 * [fullText] in [scope], so `GET /saved` finds it ready.
 */
class SyncServiceImpl(
    private val database: ServerDatabase,
    private val bundles: EditionBundles,
    private val clock: Clock,
    private val relearn: (userId: Long) -> Unit,
    private val fullText: FullTextService? = null,
    private val scope: CoroutineScope? = null,
) : SyncService {
    private val merger = EventMerger(database)
    private val userStateDao = database.userStateDao()
    private val editionDao = database.editionDao()

    override suspend fun postEvents(userId: Long, deviceId: String, request: EventsRequest): EventsResponse {
        if (request.events.size > ApiLimits.MAX_EVENTS_PER_REQUEST) {
            throw ApiFailure.badRequest("At most ${ApiLimits.MAX_EVENTS_PER_REQUEST} events per request")
        }
        val result = merger.merge(userId, deviceId, request.events) ?: throw unknownDevice()
        if (result.engaged) relearn(userId)
        if (result.saved.isNotEmpty() && fullText != null && scope != null) {
            scope.launch {
                for (id in result.saved) {
                    try {
                        fullText.ensure(id)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        log.debug("Full text for saved article {} failed", id, e)
                    }
                }
            }
        }
        return EventsResponse(result.acked)
    }

    override suspend fun checkGate(userId: Long, deviceId: String, eventsThrough: Long?) {
        if (eventsThrough == null) {
            throw ApiFailure(ApiError.EVENTS_THROUGH_REQUIRED, "Send the ${net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER} header")
        }
        val acked = userStateDao.ackedSeq(userId, deviceId) ?: throw unknownDevice()
        if (eventsThrough > acked) throw ApiFailure.eventsPending(acked)
    }

    override suspend fun manifest(userId: Long, deviceId: String, editions: Int): ManifestDto {
        val now = clock.instant()
        val count = editions.coerceIn(1, ApiLimits.MAX_MANIFEST_EDITIONS)
        val dbId = userStateDao.dbId(userId) ?: throw ApiFailure.notFound("No such user")
        val acked = userStateDao.ackedSeq(userId, deviceId) ?: throw unknownDevice()
        val offered = editionDao.getRecentReleased(userId, now, count)
        val saved = bundles.saved(userId)
        return ManifestDto(
            dbId = dbId,
            serverTime = now,
            editions = offered.map { edition ->
                ManifestEditionDto(edition.id, edition.date, edition.kind, edition.number, edition.releaseAt, bundles.version(userId, edition))
            },
            saved = ManifestSavedDto(count = saved.order.size, version = saved.version),
            acked = mapOf(deviceId to acked),
        )
    }

    private fun unknownDevice() = ApiFailure(ApiError.UNKNOWN, "This device is not paired with the account")

    private companion object {
        val log = LoggerFactory.getLogger(SyncServiceImpl::class.java)
    }
}

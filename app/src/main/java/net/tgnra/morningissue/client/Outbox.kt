package net.tgnra.morningissue.client

import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.OutboxEventEntity
import net.tgnra.morningissue.kobo.SyncEvent

/**
 * The reading events waiting for the server (`outbox_event`), in the `docs/kobo-sync.md`
 * "Events" format. Written by [LocalEngagement] in the same transaction as the cache row it
 * changes, so the two never disagree.
 *
 * @param ackedFloor the highest `seq` the server has acknowledged from this device
 *   (`AuthState.ackedSeq`). After the cache has been reset the table starts empty and its
 *   AUTOINCREMENT from 1, but the server ignores any `seq` at or below what it acknowledged,
 *   so a new event is numbered above that.
 */
class Outbox(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    private val ackedFloor: suspend () -> Long,
) {
    private val dao = database.outboxDao()

    /** Appends [event] (its `seq` is ignored) and returns the `seq` it got. Call inside the caller's write transaction. */
    suspend fun append(event: SyncEvent): Long {
        val floor = ackedFloor()
        // Only when the table holds nothing above the floor (it was emptied by a cache reset);
        // otherwise AUTOINCREMENT, which never reuses a number, even across restarts.
        val explicit = if (floor > (dao.maxSeq() ?: 0)) floor + 1 else 0
        return dao.insert(OutboxEventEntity(seq = explicit, json = json.encodeToString(SyncEvent.serializer(), event.copy(seq = 0)), createdAt = clock.instant()))
    }

    /** The events still held, oldest first, with their `seq`. */
    suspend fun pending(limit: Int = Int.MAX_VALUE): List<SyncEvent> =
        (if (limit == Int.MAX_VALUE) dao.all() else dao.oldest(limit)).mapNotNull(::decode)

    /**
     * `X-Events-Through`: the highest `seq` this device has written, as far as it knows: the
     * highest still held, or (with the table empty) the highest the server acknowledged.
     */
    suspend fun through(): Long = maxOf(dao.maxSeq() ?: 0, ackedFloor())

    suspend fun count(): Int = dao.count()

    /** Drops the events the server has acknowledged. */
    suspend fun deleteThrough(acked: Long) = dao.deleteThrough(acked)

    private fun decode(row: OutboxEventEntity): SyncEvent? =
        runCatching { json.decodeFromString(SyncEvent.serializer(), row.json).copy(seq = row.seq) }.getOrNull()

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/**
 * Uploads the [Outbox]: `POST /sync/events` in batches of 500 ([ApiLimits.MAX_EVENTS_PER_REQUEST]),
 * deleting what each answer acknowledges. One flush at a time.
 */
class OutboxUploader(
    private val outbox: Outbox,
    private val auth: AuthStore,
) {
    private val lock = Mutex()

    /**
     * Sends everything held and returns the server's `acked`. Stops when the outbox is empty, or
     * when an answer acknowledges nothing new (the server is behind; the gate will say so).
     */
    suspend fun flush(api: MorningIssueApi): Long = lock.withLock {
        var acked = auth.current().ackedSeq
        while (true) {
            val batch = outbox.pending(ApiLimits.MAX_EVENTS_PER_REQUEST)
            if (batch.isEmpty()) break
            val answer = api.postEvents(EventsRequest(batch))
            acked(answer.acked)
            val progressed = answer.acked > acked || answer.acked >= batch.last().seq
            acked = maxOf(acked, answer.acked)
            if (!progressed) break
        }
        acked
    }

    /**
     * A gated request (`docs/server-design.md`, 4.2): [request] gets the outbox's
     * `X-Events-Through`. On `409 events_pending` the events above the server's `acked` are
     * uploaded and the request is sent again, at most [ApiLimits.MAX_GATE_ROUNDS] times in all.
     */
    suspend fun <T> gated(api: MorningIssueApi, request: suspend (through: Long) -> T): T {
        var round = 0
        while (true) {
            try {
                return request(outbox.through())
            } catch (e: ApiException.EventsPending) {
                if (++round >= ApiLimits.MAX_GATE_ROUNDS) throw e
                acked(e.acked)
                flush(api)
            }
        }
    }

    /** Records an `acked` the server gave (an answer to events, a manifest, a refusal). */
    suspend fun acked(seq: Long) {
        auth.acked(seq)
        outbox.deleteThrough(seq)
    }
}

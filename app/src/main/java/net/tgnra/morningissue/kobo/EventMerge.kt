package net.tgnra.morningissue.kobo

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import net.tgnra.morningissue.data.db.MorningIssueDatabase

/** What one Kobo event asks the phone to change; see the merge table in `docs/kobo-sync.md`. */
sealed interface KoboChange {
    data class Opened(val articleId: Long, val at: Instant) : KoboChange
    data class ReadFraction(val articleId: Long, val fraction: Float) : KoboChange
    data class Read(val articleId: Long, val at: Instant) : KoboChange

    /** [at] null clears the Like. */
    data class Liked(val articleId: Long, val at: Instant?) : KoboChange

    /** [at] null clears the save. */
    data class Saved(val articleId: Long, val at: Instant?) : KoboChange
    data class PageView(val editionId: Long, val page: Int, val millis: Long, val at: Instant) : KoboChange
    data class EditionOpened(val editionId: Long, val at: Instant) : KoboChange
    data class EditionFinished(val editionId: Long, val at: Instant) : KoboChange
}

object KoboEvents {
    /** An instant from the wire: ISO-8601, `Z` or an offset, fractions of a second allowed. Null if unreadable. */
    fun parseTime(text: String?): Instant? = text?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }

    /**
     * What [event] asks for, or null when it is to be skipped: a type this version does not
     * know, or a field its type needs that is missing or unreadable. A skipped event still
     * counts as merged, so that the Kobo can delete it; sending it again would not make it
     * readable.
     */
    fun changeOf(event: SyncEvent): KoboChange? {
        val at = parseTime(event.at)
        val article = event.articleId
        val edition = event.editionId
        return when (event.type) {
            "opened" -> if (article != null && at != null) KoboChange.Opened(article, at) else null
            "readFraction" -> {
                val value = event.value?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                if (article != null && value != null) KoboChange.ReadFraction(article, value.toFloat().coerceIn(0f, 1f)) else null
            }
            "read" -> if (article != null && at != null) KoboChange.Read(article, at) else null
            "liked", "saved" -> {
                val value = event.value?.takeIf { !it.isString }?.booleanOrNull
                when {
                    article == null || value == null -> null
                    value && at == null -> null
                    event.type == "liked" -> KoboChange.Liked(article, at.takeIf { value })
                    else -> KoboChange.Saved(article, at.takeIf { value })
                }
            }
            "pageView" -> {
                val page = event.page
                val ms = event.ms
                if (edition != null && page != null && page >= 1 && ms != null && ms > 0 && at != null) {
                    KoboChange.PageView(edition, page, ms, at)
                } else {
                    null
                }
            }
            "editionOpened" -> if (edition != null && at != null) KoboChange.EditionOpened(edition, at) else null
            "editionFinished" -> if (edition != null && at != null) KoboChange.EditionFinished(edition, at) else null
            else -> null
        }
    }
}

/**
 * Applies a Kobo's events to the database. Everything one request carries is merged in one
 * write transaction together with the device's new `ackedSeq`, so a request either lands
 * whole, acknowledged, or not at all; and an event at or below `ackedSeq` is skipped, so a
 * retry after a lost response changes nothing.
 */
class KoboEventMerger(private val database: MorningIssueDatabase, private val clock: Clock) {
    /** [ackedSeq] is the device's new one; [merged] counts the events above the old one, skipped ones included. */
    data class Result(val ackedSeq: Long, val merged: Int)

    /** Merges [events] from [deviceId], or returns null for a device that is not paired. */
    suspend fun merge(deviceId: String, events: List<SyncEvent>): Result? = database.withWriteTransaction {
        val peers = database.syncPeerDao()
        val peer = peers.get(deviceId) ?: return@withWriteTransaction null
        var acked = peer.ackedSeq
        var merged = 0
        // In seq order, so that a Like then an un-Like ends un-Liked whatever order they came in.
        for (event in events.sortedBy { it.seq }) {
            if (event.seq <= acked) continue
            KoboEvents.changeOf(event)?.let { apply(it) }
            acked = event.seq
            merged++
        }
        peers.setAcked(deviceId, acked, clock.instant())
        Result(acked, merged)
    }

    private suspend fun apply(change: KoboChange) {
        val engagement = database.engagementDao()
        val editions = database.editionDao()
        val articleExists: suspend (Long) -> Boolean = { database.syncPeerDao().articleExists(it) }
        when (change) {
            is KoboChange.Opened -> if (articleExists(change.articleId)) engagement.mergeOpened(change.articleId, change.at)
            is KoboChange.ReadFraction -> if (articleExists(change.articleId)) engagement.recordReadFraction(change.articleId, change.fraction)
            is KoboChange.Read -> if (articleExists(change.articleId)) engagement.mergeRead(change.articleId, change.at)
            is KoboChange.Liked -> if (articleExists(change.articleId)) engagement.setLiked(change.articleId, change.at)
            is KoboChange.Saved -> if (articleExists(change.articleId)) engagement.setSaved(change.articleId, change.at)
            // Skips an edition that is gone on its own.
            is KoboChange.PageView -> editions.recordPageView(change.editionId, change.page, change.millis, change.at)
            // An UPDATE of a missing row changes nothing.
            is KoboChange.EditionOpened -> editions.mergeOpened(change.editionId, change.at)
            is KoboChange.EditionFinished -> editions.mergeFinished(change.editionId, change.at)
        }
    }
}

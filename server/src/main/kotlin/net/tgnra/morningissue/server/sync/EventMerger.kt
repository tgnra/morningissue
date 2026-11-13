package net.tgnra.morningissue.server.sync

import androidx.room3.withWriteTransaction
import net.tgnra.morningissue.kobo.KoboChange
import net.tgnra.morningissue.kobo.KoboEvents
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.server.db.ServerDatabase

/**
 * Applies a device's events to its user's state: the app's `KoboEventMerger`, generalised to
 * phones and scoped to the user (4.2). The merge table is `docs/kobo-sync.md`'s.
 *
 * - Events with `seq` above the device's `acked_seq` are applied in `seq` order, in one write
 *   transaction with the new `acked_seq`, so a request lands whole or not at all, and a retry
 *   after a lost response changes nothing.
 * - An event naming an article or edition the user does not have, or one this version cannot
 *   read, is skipped but counts as merged.
 * - Every engagement or edition row the request changes is stamped with one new
 *   `users.change_seq`, for the engagement delta and the manifest's bundle cache.
 */
class EventMerger(private val database: ServerDatabase) {
    /**
     * [acked] is the device's new `acked_seq`; [merged] counts the events above the old one,
     * skipped ones included. [engaged] is true when opened, read, saved or Liked state changed
     * (affinity is then relearned); [saved] lists articles newly saved (their text is fetched).
     */
    data class Result(val acked: Long, val merged: Int, val engaged: Boolean, val saved: List<Long>)

    /** Merges [events] from [deviceId], or returns null when it is not one of the user's devices. */
    suspend fun merge(userId: Long, deviceId: String, events: List<SyncEvent>): Result? = database.withWriteTransaction {
        val state = database.userStateDao()
        val before = state.ackedSeq(userId, deviceId) ?: return@withWriteTransaction null
        var acked = before
        var merged = 0
        var engaged = false
        val saved = ArrayList<Long>()
        var seq: Long? = null
        suspend fun stamp(): Long = seq ?: state.nextChangeSeq(userId).also { seq = it }

        for (event in events.sortedBy { it.seq }) {
            if (event.seq <= acked) continue
            when (val change = KoboEvents.changeOf(event)) {
                is KoboChange.ToArticle -> {
                    val outcome = applyToArticle(userId, change, ::stamp)
                    if (outcome.engaged) engaged = true
                    if (outcome.newlySaved) saved += change.articleId
                }
                is KoboChange.ToEdition -> applyToEdition(userId, change, ::stamp)
                null -> Unit
            }
            acked = event.seq
            merged++
        }
        if (acked != before) state.setAckedSeq(userId, deviceId, acked)
        Result(acked, merged, engaged, saved)
    }

    private class ArticleOutcome(val engaged: Boolean, val newlySaved: Boolean)

    /**
     * Applies [change] unless the user does not have its article. Engaged when it altered what
     * affinity learns from: the opened, read, saved or Liked state, not the read fraction alone.
     */
    private suspend fun applyToArticle(userId: Long, change: KoboChange.ToArticle, stamp: suspend () -> Long): ArticleOutcome {
        val id = change.articleId
        val engagement = database.engagementDao()
        if (!engagement.hasArticle(userId, id)) return ArticleOutcome(false, false)
        // The stamp is allocated before the write; a write that changes nothing leaves it unused,
        // which only leaves a gap in the counter.
        val seq = stamp()
        val wasSaved = engagement.get(userId, id)?.savedAt != null
        val changed = when (change) {
            is KoboChange.Opened -> engagement.mergeOpened(userId, id, change.at, seq)
            is KoboChange.ReadFraction -> engagement.recordReadFraction(userId, id, change.fraction, seq)
            is KoboChange.Read -> engagement.mergeRead(userId, id, change.at, seq)
            is KoboChange.Liked -> engagement.setLiked(userId, id, change.at, seq)
            is KoboChange.Saved -> engagement.setSaved(userId, id, change.at, seq)
        }
        val engaged = changed && change !is KoboChange.ReadFraction
        return ArticleOutcome(engaged, change is KoboChange.Saved && change.at != null && !wasSaved)
    }

    private suspend fun applyToEdition(userId: Long, change: KoboChange.ToEdition, stamp: suspend () -> Long) {
        val editions = database.editionDao()
        if (editions.getById(userId, change.editionId) == null) return
        val seq = stamp()
        when (change) {
            // Page views are in the phone's bundle, so they move the edition's stamp too.
            is KoboChange.PageView -> if (editions.recordPageView(userId, change.editionId, change.page, change.millis, change.at)) {
                editions.setChangeSeq(userId, change.editionId, seq)
            }
            is KoboChange.EditionOpened -> editions.mergeOpened(userId, change.editionId, change.at, seq)
            is KoboChange.EditionFinished -> editions.mergeFinished(userId, change.editionId, change.at, seq)
        }
    }
}

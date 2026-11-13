package net.tgnra.morningissue.client

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.kobo.KoboChange
import net.tgnra.morningissue.kobo.KoboEvents
import net.tgnra.morningissue.kobo.SyncEvent

/**
 * What the reader does on the phone. Each change is written to the cache at once, so the UI
 * answers immediately, and appended to the [Outbox] in the same transaction; the server learns
 * of it at the next sync (`POST /sync/events`), the only way engagement reaches it.
 *
 * A scroll position is sent only when it has grown by [READ_FRACTION_STEP] since the last one
 * sent, or when the reader leaves the article. A page view carries the time added, in ms.
 */
class LocalEngagement(
    private val database: MorningIssueDatabase,
    private val outbox: Outbox,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    /** Called after each change, to schedule an upload; never blocks. */
    private val onChange: () -> Unit = {},
) {
    private val engagementDao = database.engagementDao()
    private val editionDao = database.editionDao()

    /** The last scroll position sent per article, so that a scroll does not send an event per step. */
    private val sentFraction = ConcurrentHashMap<Long, Float>()

    suspend fun markOpened(articleId: Long) = change {
        val before = engagementDao.get(articleId)?.openedAt
        val now = clock.instant()
        engagementDao.markOpened(articleId, now)
        if (before == null) outbox.append(event("opened", articleId = articleId, at = now))
    }

    /** [leaving] sends the position whatever it is: the reader closed the article. */
    suspend fun setReadFraction(articleId: Long, fraction: Float, leaving: Boolean = false) = change {
        val value = fraction.coerceIn(0f, 1f)
        val stored = engagementDao.get(articleId)?.readFraction ?: 0f
        engagementDao.recordReadFraction(articleId, value)
        val sent = sentFraction[articleId] ?: stored
        if (value > sent && (leaving || value - sent >= READ_FRACTION_STEP - EPSILON || value >= 1f)) {
            sentFraction[articleId] = value
            outbox.append(event("readFraction", articleId = articleId, value = JsonPrimitive(value)))
        }
        if (value >= config.readScrollFraction) markReadInTransaction(articleId)
    }

    suspend fun markRead(articleId: Long) = change { markReadInTransaction(articleId) }

    private suspend fun markReadInTransaction(articleId: Long) {
        if (engagementDao.get(articleId)?.readAt != null) return
        val now = clock.instant()
        engagementDao.markRead(articleId, now)
        outbox.append(event("read", articleId = articleId, at = now))
    }

    suspend fun setLiked(articleId: Long, liked: Boolean) = change {
        val now = clock.instant()
        engagementDao.setLiked(articleId, now.takeIf { liked })
        outbox.append(event("liked", articleId = articleId, value = JsonPrimitive(liked), at = now))
    }

    suspend fun setSaved(articleId: Long, saved: Boolean) = change {
        val now = clock.instant()
        engagementDao.setSaved(articleId, now.takeIf { saved })
        outbox.append(event("saved", articleId = articleId, value = JsonPrimitive(saved), at = now))
    }

    suspend fun markEditionOpened(editionId: Long) = change {
        val now = clock.instant()
        if (editionDao.getById(editionId)?.openedAt == null) {
            editionDao.markOpened(editionId, now)
            outbox.append(event("editionOpened", editionId = editionId, at = now))
        }
    }

    suspend fun markEditionFinished(editionId: Long) = change {
        val now = clock.instant()
        if (editionDao.getById(editionId)?.finishedAt == null) {
            editionDao.markFinished(editionId, now)
            outbox.append(event("editionFinished", editionId = editionId, at = now))
        }
    }

    suspend fun recordPageView(editionId: Long, page: Int, millis: Long) {
        if (page < 1 || millis <= 0) return
        change {
            val now = clock.instant()
            editionDao.recordPageView(editionId, page, millis, now)
            outbox.append(event("pageView", editionId = editionId, page = page, ms = millis, at = now))
        }
    }

    private suspend fun change(block: suspend () -> Unit) {
        database.withWriteTransaction { block() }
        onChange()
    }

    /**
     * Writes [events] (still pending, so the server has not seen them) over the cache again,
     * after the server's state has been applied: the server's copy wins, but what the reader
     * did since stays visible until the server has it. Call inside a write transaction.
     */
    suspend fun reapply(events: List<SyncEvent>) {
        val cache = database.cacheDao()
        for (event in events) {
            val change = KoboEvents.changeOf(event) ?: continue
            // An article or edition the cache no longer holds has nothing to show it on.
            val present = when (change) {
                is KoboChange.ToArticle -> cache.articleExists(change.articleId)
                is KoboChange.ToEdition -> cache.editionExists(change.editionId)
            }
            if (!present) continue
            when (change) {
                is KoboChange.Opened -> engagementDao.mergeOpened(change.articleId, change.at)
                is KoboChange.Read -> engagementDao.mergeRead(change.articleId, change.at)
                is KoboChange.ReadFraction -> engagementDao.recordReadFraction(change.articleId, change.fraction)
                is KoboChange.Liked -> engagementDao.setLiked(change.articleId, change.at)
                is KoboChange.Saved -> engagementDao.setSaved(change.articleId, change.at)
                is KoboChange.EditionOpened -> editionDao.mergeOpened(change.editionId, change.at)
                is KoboChange.EditionFinished -> editionDao.mergeFinished(change.editionId, change.at)
                // The server's page views already count what it has; pending ones are not shown anywhere.
                is KoboChange.PageView -> Unit
            }
        }
    }

    private fun event(
        type: String,
        articleId: Long? = null,
        editionId: Long? = null,
        value: JsonPrimitive? = null,
        page: Int? = null,
        ms: Long? = null,
        at: Instant? = null,
    ) = SyncEvent(seq = 0, type = type, articleId = articleId, editionId = editionId, value = value, page = page, ms = ms, at = at?.toString())

    companion object {
        /** A scroll position is sent each time it has grown this much. */
        const val READ_FRACTION_STEP = 0.1f
        private const val EPSILON = 1e-4f
    }
}

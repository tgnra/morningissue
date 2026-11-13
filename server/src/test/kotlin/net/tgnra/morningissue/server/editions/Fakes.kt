package net.tgnra.morningissue.server.editions

import java.io.OutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import net.tgnra.morningissue.api.FullTextState
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.server.service.FeedRefresher
import net.tgnra.morningissue.server.service.FullTextService
import net.tgnra.morningissue.server.service.KoboImageService
import net.tgnra.morningissue.server.service.RefreshReport

/** Records the order of everything a run does. */
class Journal {
    private val entries = mutableListOf<String>()

    @Synchronized
    fun add(entry: String) {
        entries += entry
    }

    @Synchronized
    fun all(): List<String> = entries.toList()
}

/**
 * A [FeedRefresher] that fetches nothing but keeps the bookkeeping the real one does: a feed
 * tried within `maxAge` is skipped, and every fetch it counts as an attempt now. [fetches]
 * counts the fetches per feed, [calls] each call's feed set.
 */
class FakeRefresher(
    private val clock: Clock,
    private val journal: Journal? = null,
    /** What a call reports, given the feeds it fetched. */
    var behaviour: suspend (fetched: Set<Long>) -> RefreshReport = { RefreshReport(it.size, it.size, 0, finishedInTime = true) },
    /** Whether a user's feeds were reached by the latest refresh. */
    var reached: Boolean = true,
) : FeedRefresher {
    val lastAttempt = ConcurrentHashMap<Long, Instant>()
    val fetches = ConcurrentHashMap<Long, Int>()
    val calls = mutableListOf<Set<Long>>()

    override suspend fun refresh(feedIds: Collection<Long>, maxAge: Duration, deadline: Instant?): RefreshReport {
        val now = clock.instant()
        synchronized(calls) { calls += feedIds.toSet() }
        journal?.add("refresh:${feedIds.sorted().joinToString(",")}")
        val due = feedIds.filterTo(LinkedHashSet()) { id ->
            val last = lastAttempt[id]
            last == null || last.isBefore(now.minus(maxAge)) || maxAge.isZero
        }
        due.forEach {
            lastAttempt[it] = now
            fetches.merge(it, 1, Int::plus)
        }
        return behaviour(due)
    }

    override fun reachedFeedsOf(userId: Long): Boolean = reached
}

class FakeFullText(
    private val journal: Journal? = null,
    /** What a call for an edition does: how many bodies it stored, or a throw. */
    var behaviour: suspend (editionId: Long) -> Int = { 0 },
) : FullTextService {
    val editions = mutableListOf<Long>()

    override suspend fun ensure(articleId: Long): FullTextState {
        journal?.add("text:$articleId")
        return FullTextState.FETCHED
    }

    override suspend fun ensureForEdition(editionId: Long, concurrency: Int): Int {
        synchronized(editions) { editions += editionId }
        journal?.add("fulltext:$editionId")
        return behaviour(editionId)
    }
}

class FakeKoboImages(private val journal: Journal? = null) : KoboImageService {
    val prepared = mutableListOf<Pair<Long, Set<Int>>>()

    override suspend fun writeZip(userId: Long, request: KoboImagesRequest, output: OutputStream) = Unit

    override suspend fun prepare(editionId: Long, widths: Set<Int>) {
        synchronized(prepared) { prepared += editionId to widths }
        journal?.add("images:$editionId")
    }
}

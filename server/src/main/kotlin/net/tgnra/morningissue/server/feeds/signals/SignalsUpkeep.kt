package net.tgnra.morningissue.server.feeds.signals

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps the tables the within-feed ranking reads filled in: [TraitsBackfill], then
 * [EmbeddingBackfill]. On the server it runs after every refresh that stored posts and after an archive import,
 * so a library upgraded from database version 1, or brought in by an archive import, gets its
 * traits and vectors without the reader doing anything.
 *
 * Hold one instance per process. Only one pass runs at a time: a call that arrives while a
 * pass is under way does not start a second one and does not wait; it leaves word, and the
 * running pass goes round once more when it finishes, so posts stored meanwhile are not left
 * until the next sync.
 *
 * It runs on the caller's coroutine, which must not be the main thread's: the database work
 * is on Room's own threads and the computing on `Dispatchers.Default`, but a first pass over
 * a large library takes a while. Cancelling the caller stops it between batches.
 */
class SignalsUpkeep(
    private val traits: TraitsBackfill,
    private val embeddings: EmbeddingBackfill,
) {
    private val running = Mutex()
    private val requested = AtomicBoolean(false)

    /** What the passes of one call did, added up; all zero when another call was already at work. */
    data class Result(val traits: Int = 0, val embedded: Int = 0, val marked: Int = 0)

    suspend fun run(): Result {
        requested.set(true)
        var result = Result()
        while (true) {
            if (!running.tryLock()) return result
            try {
                while (requested.getAndSet(false)) {
                    val withTraits = traits.run()
                    val vectors = embeddings.run()
                    result = Result(result.traits + withTraits, result.embedded + vectors.embedded, result.marked + vectors.marked)
                }
            } finally {
                running.unlock()
            }
            // A request made after the last check and before the unlock found the lock taken.
            if (!requested.get()) return result
        }
    }
}

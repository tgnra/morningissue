package net.tgnra.morningissue.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Failed logins per user name: after [maxFailures] within [window], further attempts for that
 * name are refused (`429 too_many_attempts`) until the oldest failure leaves the window. A
 * successful login clears the name's record. Names are counted whether or not they exist.
 * In memory: a restart forgets, which is fine for a handful of accounts.
 */
class LoginRateLimiter(
    private val clock: Clock,
    private val maxFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
) {
    private val failures = HashMap<String, ArrayDeque<Instant>>()

    /** Whether [username] may try now. */
    @Synchronized
    fun allowed(username: String): Boolean {
        val now = clock.instant()
        val q = failures[key(username)] ?: return true
        prune(q, now)
        return q.size < maxFailures
    }

    /** When [username] may try again, if it is locked out now. */
    @Synchronized
    fun retryAfter(username: String): Duration? {
        val now = clock.instant()
        val q = failures[key(username)] ?: return null
        prune(q, now)
        if (q.size < maxFailures) return null
        return Duration.between(now, q.peekFirst().plus(window))
    }

    @Synchronized
    fun failed(username: String) {
        val now = clock.instant()
        val q = failures.getOrPut(key(username)) { ArrayDeque() }
        prune(q, now)
        q.addLast(now)
        if (failures.size > MAX_TRACKED) failures.entries.removeIf { (_, v) -> prune(v, now); v.isEmpty() }
    }

    @Synchronized
    fun succeeded(username: String) {
        failures.remove(key(username))
    }

    private fun prune(q: ArrayDeque<Instant>, now: Instant) {
        val cutoff = now.minus(window)
        while (q.isNotEmpty() && !q.peekFirst().isAfter(cutoff)) q.removeFirst()
    }

    private fun key(username: String) = username.lowercase()

    private companion object {
        /** Past this many names, empty records are dropped, so guessing names cannot grow memory without end. */
        const val MAX_TRACKED = 10_000
    }
}

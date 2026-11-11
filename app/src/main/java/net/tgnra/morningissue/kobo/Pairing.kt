package net.tgnra.morningissue.kobo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one pairing request that may wait for the reader at a time. `POST /v1/pair` calls [ask]
 * and is held open while the Sync screen shows "Allow … to sync?"; the screen's buttons call
 * [answer].
 */
class Pairing {
    class Pending(val deviceId: String, val name: String) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    enum class Outcome { ALLOWED, DENIED, TIMED_OUT, BUSY }

    private val state = MutableStateFlow<Pending?>(null)

    /** The request waiting for Allow or Deny, if any. */
    val pending: StateFlow<Pending?> = state.asStateFlow()

    /** Waits up to [timeoutMillis] for the reader. A second request while one waits is [Outcome.BUSY]. */
    suspend fun ask(deviceId: String, name: String, timeoutMillis: Long = KoboProtocol.PAIR_WAIT_MILLIS): Outcome {
        val request = Pending(deviceId, name)
        if (!state.compareAndSet(null, request)) return Outcome.BUSY
        return try {
            when (withTimeoutOrNull(timeoutMillis) { request.answer.await() }) {
                true -> Outcome.ALLOWED
                false -> Outcome.DENIED
                null -> Outcome.TIMED_OUT
            }
        } finally {
            state.compareAndSet(request, null)
        }
    }

    /** Allows or denies the waiting request; does nothing when there is none. */
    fun answer(allow: Boolean) {
        state.value?.answer?.complete(allow)
    }
}

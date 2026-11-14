package net.tgnra.morningissue.edition

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a print ended. */
enum class PrintResult {
    /** The edition is on the stand, printed from current posts. */
    Printed,

    /** The edition is on the stand, but the feeds could not be reached: it holds what was already stored. */
    PrintedFromStored,

    /** The posts were current and there was nothing new to print. Not a failure. */
    NothingNew,

    /** The feeds could not be reached and nothing stored was waiting to be printed. Worth trying again. */
    Unreachable,

    /** The build itself went wrong or ran out of time. Worth trying again. */
    Failed,

    /** An Extra edition is on the stand. */
    ExtraPrinted,

    /** Today's Extra edition was reprinted with the new sources. */
    ExtraReprinted,

    /** The new sources had nothing recent to print. */
    ExtraNothingNew,

    /** The Extra edition could not be printed. */
    ExtraFailed,
}

/** What the printer is doing. */
sealed interface PrintState {
    /** Not asked for since the process started, or the last result was closed. */
    data object Idle : PrintState

    /** Printing for [job], at [stage]. */
    data class Printing(val job: PrintJob, val stage: PrintStage) : PrintState

    /**
     * Ended [at] with [result]. Kept so the stand can say what happened, until the next print
     * starts or the reader closes the notice ([EditionPrinter.dismissResult]).
     */
    data class Finished(val result: PrintResult, val at: Instant) : PrintState
}

/**
 * Prints editions in the foreground: the edition that is due when the stand comes to the
 * foreground ([printIfDue]), Print now ([printNow]) and the Extra edition ([printExtra]), all
 * through the same stages ([EditionPress]): sync if needed, rate the shortlist with the
 * on-device model, build and freeze. Afterwards it fetches the edition's full text and
 * pictures ([EditionPrefetch]) without holding up the result.
 *
 * One instance per process, held by the app container, and the work runs in the app's own
 * scope: it carries on through a rotation, a visit to another screen or the app going to the
 * background (the rating stops there, the build does not), and whichever screen comes back
 * reads where it has got to from [state].
 *
 * **One print at a time.** A request while one is under way starts nothing: a second open of
 * the stand joins the print in progress. Printing is idempotent besides: the publisher builds
 * at most one edition per date and kind in one transaction, so a print that a killed process
 * left half done is simply run again at the next open, and finds the edition there if it was
 * stored.
 *
 * **Never hangs.** Each stage has its own limit, and the whole print is held to [cap]; past it
 * the print ends as [PrintResult.Failed] and the stand offers Try again.
 *
 * @param scope the process-wide scope
 * @param press resolved for each print, so creating the container builds nothing
 * @param prefetch resolved after each print that stored an edition; null fetches nothing
 * @param dueStatus where the stand is with the scheduled edition that is due ([DueEditions.status]); null when unknown
 */
class EditionPrinter(
    private val scope: CoroutineScope,
    private val press: () -> EditionPress,
    private val clock: Clock,
    private val dueStatus: suspend () -> DueStatus? = { null },
    private val prefetch: () -> EditionPrefetch? = { null },
    private val cap: Duration = Duration.ofMinutes(3),
) {
    private val _state = MutableStateFlow<PrintState>(PrintState.Idle)
    val state: StateFlow<PrintState> = _state.asStateFlow()

    /** The releases this process has already printed for on its own; a failure is not retried until asked. */
    private val tried = HashSet<Instant>()

    /**
     * The stand has come to the foreground (or a release time passed while it was on screen):
     * prints the scheduled edition if it is due and not printed ([DueStatus.NotPrinted]).
     * Returns true when a print is now under way, started here or joined; false when nothing
     * is due. A release this process already printed for on its own is not tried again here
     * (the stand shows Try again).
     */
    suspend fun printIfDue(): Boolean {
        if (_state.value is PrintState.Printing) return true
        val status = try {
            dueStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not tell whether an edition is due", e)
            null
        }
        val release = (status as? DueStatus.NotPrinted)?.release ?: return false
        synchronized(tried) {
            if (!tried.add(release.at)) return false
        }
        // Started here, or one started meanwhile by another caller.
        return start(PrintJob.Due(forceSync = false)) || _state.value is PrintState.Printing
    }

    /** Print now, Try again and "Check for new posts": prints the due edition, syncing first. False when a print is already under way. */
    fun printNow(): Boolean = start(PrintJob.Due(forceSync = true))

    /** Prints an Extra edition from [feedIds], or reprints today's with them. False when a print is already under way. */
    fun printExtra(feedIds: Set<Long>): Boolean = feedIds.isNotEmpty() && start(PrintJob.Extra(feedIds))

    /**
     * The reader closed the notice of the last result: it is forgotten, and nothing is said
     * again until a later print produces a new one. Held here, not in the screen, so the notice
     * stays closed through a rotation or a visit to another screen. Does nothing while a print
     * is under way.
     */
    fun dismissResult() {
        _state.update { if (it is PrintState.Finished) PrintState.Idle else it }
    }

    private fun start(job: PrintJob): Boolean {
        val started = PrintState.Printing(job, initialStage(job))
        while (true) {
            val current = _state.value
            if (current is PrintState.Printing) return false
            if (_state.compareAndSet(current, started)) break
        }
        scope.launch {
            val outcome = try {
                withTimeoutOrNull(cap.toMillis()) { press().run(job) { stage -> onStage(job, stage) } }
                    ?: PrintOutcome(job, synced = false, editionId = null, error = IllegalStateException("The print ran over ${cap.seconds} s"))
            } catch (e: CancellationException) {
                // The process is going away; leave nothing that looks like a print in flight.
                _state.value = PrintState.Idle
                throw e
            } catch (e: Exception) {
                PrintOutcome(job, synced = false, editionId = null, error = e)
            }
            _state.value = PrintState.Finished(result(outcome), clock.instant())
            outcome.editionId?.let { id -> scope.launch { prefetch()?.prefetch(id) } }
        }
        return true
    }

    private fun onStage(job: PrintJob, stage: PrintStage) {
        _state.update { current ->
            // A count that arrives late must not move the progress back.
            val shown = (current as? PrintState.Printing)?.stage
            val stale = stage is PrintStage.Rating && (shown is PrintStage.Building || shown is PrintStage.Rating && shown.done > stage.done)
            if (current is PrintState.Printing && !stale) PrintState.Printing(job, stage) else current
        }
    }

    private companion object {
        const val TAG = "EditionPrinter"

        /** What the stand shows before the first stage is reported: the print is getting ready. */
        fun initialStage(job: PrintJob): PrintStage = if (job is PrintJob.Extra) PrintStage.Syncing else PrintStage.Building

        fun result(outcome: PrintOutcome): PrintResult {
            outcome.error?.let { Log.w(TAG, "Printing failed", it) }
            return when (outcome.job) {
                is PrintJob.Extra -> when {
                    outcome.editionId != null -> if (outcome.reprinted) PrintResult.ExtraReprinted else PrintResult.ExtraPrinted
                    outcome.error != null -> PrintResult.ExtraFailed
                    else -> PrintResult.ExtraNothingNew
                }
                is PrintJob.Due -> when {
                    outcome.editionId != null -> if (outcome.synced) PrintResult.Printed else PrintResult.PrintedFromStored
                    outcome.error != null -> PrintResult.Failed
                    outcome.synced -> PrintResult.NothingNew
                    else -> PrintResult.Unreachable
                }
            }
        }
    }
}

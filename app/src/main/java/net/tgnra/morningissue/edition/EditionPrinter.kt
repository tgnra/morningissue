package net.tgnra.morningissue.edition

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a Print now run ended. */
enum class PrintResult {
    /** The edition is on the stand, printed from a fresh sync. */
    Printed,

    /** The edition is on the stand, but the feeds could not be reached: it holds what was already stored. */
    PrintedFromStored,

    /** The feeds were reached and there was nothing new to print. Not a failure. */
    NothingNew,

    /** The feeds could not be reached and nothing stored was waiting to be printed. Worth trying again. */
    Unreachable,

    /** The build itself went wrong. Worth trying again. */
    Failed,
}

/** What Print now is doing. */
sealed interface PrintState {
    /** Not asked for since the process started. */
    data object Idle : PrintState

    /** Under way, at [stage]. */
    data class Printing(val stage: BuildStage) : PrintState {
        /**
         * The edition being printed, once it is stored. The stand keeps the placeholder up in
         * its place until the run has finished, so the cover appears with its stories fetched.
         */
        val editionId: Long?
            get() = when (stage) {
                is BuildStage.FetchingText -> stage.editionId
                is BuildStage.FetchingImages -> stage.editionId
                else -> null
            }
    }

    /** Ended [at] with [result]. Kept until the next run so the stand can say what happened. */
    data class Finished(val result: PrintResult, val at: Instant) : PrintState
}

/**
 * Print now: runs the early build's stages in the foreground when the reader asks for an
 * edition that was not printed in the background. Sync every feed, build the due edition,
 * fetch its full text and pictures; the stages themselves are [EarlyBuild]'s
 * ([BuildTarget.Current]), not copies of them.
 *
 * One instance per process, held by the app container, and the work runs in the app's own
 * scope: it carries on through a rotation, a visit to another screen or the app going to the
 * background, and whichever screen comes back reads where it has got to from [state].
 *
 * Only one run at a time: [printNow] does nothing while one is under way.
 *
 * With no network the sync fails quickly, the edition is printed from the posts already
 * stored, and the result says so ([PrintResult.PrintedFromStored]). With nothing stored to
 * print either, the run ends as [PrintResult.Unreachable] and can be started again.
 *
 * @param scope the process-wide scope
 * @param earlyBuild resolved for each run, so creating the container builds nothing
 */
class EditionPrinter(
    private val scope: CoroutineScope,
    private val earlyBuild: () -> EarlyBuild,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<PrintState>(PrintState.Idle)
    val state: StateFlow<PrintState> = _state.asStateFlow()

    /** Starts a run and returns true; returns false, starting nothing, when one is already under way. */
    fun printNow(): Boolean {
        val started = PrintState.Printing(BuildStage.Syncing)
        while (true) {
            val current = _state.value
            if (current is PrintState.Printing) return false
            if (_state.compareAndSet(current, started)) break
        }
        scope.launch {
            val result = try {
                result(earlyBuild().run(BuildTarget.Current, ::onStage))
            } catch (e: CancellationException) {
                // The process is going away; leave nothing that looks like a run in flight.
                _state.value = PrintState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Print now failed", e)
                PrintResult.Failed
            }
            _state.value = PrintState.Finished(result, clock.instant())
        }
        return true
    }

    /** Stories finish on several threads, so a count that arrives late must not move the progress back. */
    private fun onStage(stage: BuildStage) {
        _state.update { current ->
            val shown = (current as? PrintState.Printing)?.stage
            val stale = stage is BuildStage.FetchingText &&
                (shown is BuildStage.FetchingImages || shown is BuildStage.FetchingText && shown.done > stage.done)
            if (current is PrintState.Printing && !stale) PrintState.Printing(stage) else current
        }
    }

    private companion object {
        const val TAG = "EditionPrinter"

        fun result(outcome: EarlyBuildOutcome): PrintResult = when {
            // A failed prefetch does not undo the edition: its stories are fetched when opened.
            outcome.editionId != null -> if (outcome.synced) PrintResult.Printed else PrintResult.PrintedFromStored
            outcome.error != null -> PrintResult.Failed.also { Log.w(TAG, "Print now failed", outcome.error) }
            outcome.synced -> PrintResult.NothingNew
            else -> PrintResult.Unreachable
        }
    }
}

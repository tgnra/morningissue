package net.tgnra.morningissue.client

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi

/** How a Print now run ended. */
enum class PrintResult {
    /** The edition is on the stand, printed from a fresh fetch of the feeds. */
    Printed,

    /** The edition is on the stand, but the server could not reach the feeds: it holds what was already stored. */
    PrintedFromStored,

    /** The feeds were reached and there was nothing new to print. Not a failure. */
    NothingNew,

    /** Neither the feeds nor (when the phone is offline) the server could be reached. Worth trying again. */
    Unreachable,

    /** The build itself went wrong. Worth trying again. */
    Failed,
}

/** Where a print job on the server has got to (`JobDto.stage`). */
sealed interface BuildStage {
    /** Fetching the feeds; [done] of [total] when the server says. */
    data class Syncing(val done: Int = 0, val total: Int = 0) : BuildStage

    data object Building : BuildStage

    data class FetchingText(val done: Int, val total: Int) : BuildStage

    data class FetchingImages(val count: Int) : BuildStage
}

/** What Print now is doing. */
sealed interface PrintState {
    /** Not asked for since the process started. */
    data object Idle : PrintState

    /** Under way, at [stage]. */
    data class Printing(val stage: BuildStage) : PrintState {
        /** The edition being printed, while the stand keeps a placeholder in its place; the server's appears only once synced. */
        val editionId: Long? get() = null
    }

    /** Ended [at] with [result]; kept until the next run or [PrintClient.dismissResult]. */
    data class Finished(val result: PrintResult, val at: Instant) : PrintState
}

/** How an Extra edition run ended. */
enum class ExtraResult { Printed, Reprinted, NothingToPrint, Failed }

/**
 * Print now and the Extra edition, on the server: `POST /print` (or `/editions/extra`), then
 * `GET /jobs/{id}` every [pollMillis] until the job is done, then a [ServerSync] so that the
 * edition is in the cache when the result is shown. Same state machine as the local printer it
 * replaces; one run at a time; the work runs in the app's scope.
 */
class PrintClient(
    private val scope: CoroutineScope,
    private val connection: ServerConnection,
    private val sync: ServerSync,
    private val clock: Clock,
    private val pollMillis: Long = 1_000,
    /** Polls that may fail in a row (the phone lost its connection) before the run gives up. */
    private val pollFailures: Int = 30,
) {
    private val _state = MutableStateFlow<PrintState>(PrintState.Idle)
    val state: StateFlow<PrintState> = _state.asStateFlow()

    /** Starts a run and returns true; returns false, starting nothing, when one is already under way. */
    fun printNow(): Boolean {
        val started = PrintState.Printing(BuildStage.Syncing())
        while (true) {
            val current = _state.value
            if (current is PrintState.Printing) return false
            if (_state.compareAndSet(current, started)) break
        }
        scope.launch {
            val result = try {
                resultOf(runJob(start = { it.print() }, onProgress = ::onProgress))
            } catch (e: CancellationException) {
                _state.value = PrintState.Idle
                throw e
            } catch (_: ApiException.Offline) {
                PrintResult.Unreachable
            } catch (e: Exception) {
                Log.w(TAG, "Print now failed", e)
                PrintResult.Failed
            }
            _state.value = PrintState.Finished(result, clock.instant())
        }
        return true
    }

    /** The reader closed the notice of the last result. Does nothing while a run is under way. */
    fun dismissResult() {
        _state.update { if (it is PrintState.Finished) PrintState.Idle else it }
    }

    /** Prints (or reprints) today's Extra edition from [feedIds]. Never throws but cancellation. */
    suspend fun printExtra(feedIds: Set<Long>): ExtraResult = try {
        val job = runJob(start = { it.printExtra(feedIds.toList()) }, onProgress = {})
        when (job.outcome?.result) {
            JobResult.EXTRA_PRINTED -> ExtraResult.Printed
            JobResult.EXTRA_REPRINTED -> ExtraResult.Reprinted
            JobResult.EXTRA_NOTHING, JobResult.NOTHING_NEW -> ExtraResult.NothingToPrint
            else -> if (job.state == JobState.DONE && job.outcome?.editionId != null) ExtraResult.Printed else ExtraResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Extra edition failed", e)
        ExtraResult.Failed
    }

    /** Starts a job, follows it to the end, then syncs; returns the finished job. */
    private suspend fun runJob(start: suspend (MorningIssueApi) -> net.tgnra.morningissue.api.JobStartedResponse, onProgress: (JobDto) -> Unit): JobDto {
        val jobId = connection.call { start(it) }.jobId
        var failures = 0
        var job: JobDto
        while (true) {
            job = try {
                connection.call { it.job(jobId) }.also { failures = 0 }
            } catch (e: ApiException.Offline) {
                if (++failures >= pollFailures) throw e
                delay(pollMillis)
                continue
            }
            onProgress(job)
            if (job.state != JobState.RUNNING) break
            delay(pollMillis)
        }
        sync.sync("print")
        return job
    }

    private fun onProgress(job: JobDto) {
        val stage = when (job.stage) {
            JobStage.FETCHING_FEEDS, JobStage.IMPORTING, null -> BuildStage.Syncing(job.done ?: 0, job.total ?: 0)
            JobStage.SETTING_PAGES -> BuildStage.Building
            JobStage.FETCHING_TEXT -> BuildStage.FetchingText(job.done ?: 0, job.total ?: 0)
            JobStage.FETCHING_IMAGES -> BuildStage.FetchingImages(job.total ?: job.done ?: 0)
        }
        _state.update { if (it is PrintState.Printing) PrintState.Printing(stage) else it }
    }

    private fun resultOf(job: JobDto): PrintResult {
        if (job.state == JobState.FAILED) return PrintResult.Failed
        return when (job.outcome?.result) {
            JobResult.PRINTED, JobResult.EXTRA_PRINTED, JobResult.EXTRA_REPRINTED -> PrintResult.Printed
            JobResult.PRINTED_OFFLINE -> PrintResult.PrintedFromStored
            JobResult.NOTHING_NEW, JobResult.EXTRA_NOTHING -> PrintResult.NothingNew
            JobResult.UNREACHABLE -> PrintResult.Unreachable
            JobResult.FAILED, JobResult.DONE, null -> if (job.outcome?.editionId != null) PrintResult.Printed else PrintResult.Failed
        }
    }

    private companion object {
        const val TAG = "PrintClient"
    }
}

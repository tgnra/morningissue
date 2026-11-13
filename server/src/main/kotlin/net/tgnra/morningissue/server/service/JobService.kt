package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobStage

/** A job started, or the one of the same kind already running for the user ([alreadyRunning]: 200, not 202). */
data class JobStart(val jobId: String, val alreadyRunning: Boolean)

/** What a running job reports as it goes; `GET /jobs/{id}` shows the latest. */
fun interface JobProgress {
    fun report(stage: JobStage, done: Int?, total: Int?)
}

/**
 * Print now, Extra and every other long request (4.3): jobs run in the background and are kept in
 * memory for an hour after they end. Implemented by W-B; W-A's OPML import and W-B's archive
 * import run through [start].
 */
interface JobService {
    /** `POST /print`: refresh everything, `ensureCurrentEdition`, full text, Kobo images. One per user at a time. */
    suspend fun print(userId: Long): JobStart

    /** `POST /editions/extra`. */
    suspend fun extra(userId: Long, request: ExtraRequest): JobStart

    /** `GET /jobs/{id}`; [ApiFailure] `not_found` for another user's or an expired job. */
    suspend fun job(userId: Long, jobId: String): JobDto

    /** Runs [work] as a job of [kind] for the user; a failure it throws ends the job FAILED. */
    suspend fun start(userId: Long, kind: JobKind, work: suspend (JobProgress) -> JobOutcomeDto): JobStart
}

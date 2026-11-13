package net.tgnra.morningissue.server.importer

import androidx.room3.withWriteTransaction
import java.io.File
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.ImportResponse
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.server.db.ImportLogEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.ImportService
import net.tgnra.morningissue.server.service.JobService

/**
 * `POST /import` (4.9): runs [ArchiveImporter] as an IMPORT job, once per `Idempotency-Key`.
 *
 * - A key already in `import_log` answers its report and does nothing.
 * - The same key while its import runs answers that job.
 * - `mode=replace` is refused (`conflict`) on an account with data; on an empty account it is
 *   a merge.
 * - Another import running for the account: `busy`.
 *
 * When the import is done its report goes into `import_log`, the uploaded file is deleted, and
 * [afterImport] runs (integration wires W-A's signals upkeep for the new posts).
 */
class ImportServiceImpl(
    private val database: ServerDatabase,
    private val importer: ArchiveImporter,
    private val jobs: JobService,
    private val clock: Clock,
    private val afterImport: suspend (userId: Long) -> Unit = {},
) : ImportService {
    private val importDao = database.importDao()

    /** Imports running, by user and key, to their job. */
    private val running = ConcurrentHashMap<Pair<Long, String>, String>()

    override suspend fun start(userId: Long, mode: ImportMode, idempotencyKey: String, archive: File): ImportResponse {
        if (idempotencyKey.isBlank()) throw ApiFailure.badRequest("An Idempotency-Key is required")
        importDao.importLog(userId, idempotencyKey)?.let { return ImportResponse(report = decode(it.reportJson)) }
        val key = userId to idempotencyKey
        running[key]?.takeIf { it != PENDING }?.let { return ImportResponse(jobId = it) }
        if (mode == ImportMode.REPLACE && importDao.hasData(userId)) {
            throw ApiFailure(ApiError.CONFLICT, "The account already has data; import with mode=merge")
        }
        if (running.keys.any { it.first == userId } || running.putIfAbsent(key, PENDING) != null) {
            throw ApiFailure(ApiError.BUSY, "An import is already running for this account")
        }
        val started = try {
            startJob(userId, idempotencyKey, archive, key)
        } catch (e: Exception) {
            running.remove(key)
            throw e
        }
        if (started.alreadyRunning) {
            running.remove(key, PENDING)
            throw ApiFailure(ApiError.BUSY, "An import is already running for this account")
        }
        running.replace(key, PENDING, started.jobId)
        return ImportResponse(jobId = started.jobId)
    }

    private suspend fun startJob(userId: Long, idempotencyKey: String, archive: File, key: Pair<Long, String>) =
        jobs.start(userId, JobKind.IMPORT) { progress ->
            try {
                progress.report(JobStage.IMPORTING, 0, null)
                val report = try {
                    importer.import(userId, { archive.inputStream() }) { done, total -> progress.report(JobStage.IMPORTING, done, total) }
                } catch (e: ArchiveFormatException) {
                    throw ApiFailure.badRequest(
                        if (e.reason == ArchiveFormatException.Reason.NEWER_VERSION) "The archive is from a newer version" else "Not a Morning Issue archive",
                    )
                }
                database.withWriteTransaction {
                    importDao.insertImportLog(ImportLogEntity(userId, idempotencyKey, encode(report), clock.instant()))
                }
                archive.delete()
                afterImport(userId)
                JobOutcomeDto(JobResult.DONE, report = ApiJson.json.encodeToJsonElement(ImportReportDto.serializer(), report))
            } finally {
                running.remove(key)
            }
        }

    private fun encode(report: ImportReportDto) = ApiJson.json.encodeToString(ImportReportDto.serializer(), report)

    private fun decode(json: String): ImportReportDto = ApiJson.json.decodeFromString(ImportReportDto.serializer(), json)

    private companion object {
        /** Held for a key between the checks and the job's start. */
        const val PENDING = ""
    }
}

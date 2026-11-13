package net.tgnra.morningissue.server.importer

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.server.editions.Jobs
import net.tgnra.morningissue.server.editions.TestWorld
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.JobProgress
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.server.service.JobStart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `POST /import` as a job (4.9): once per Idempotency-Key, replace only into an empty account, one at a time. */
class ImportServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val world = TestWorld(T0)
    private val db = world.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Jobs(scope, world.clock)
    private val afterImport = mutableListOf<Long>()

    private val importer = ArchiveImporter(db, world.settings, world.clock)
    private val service = ImportServiceImpl(db, importer, jobs, world.clock, afterImport = { synchronized(afterImport) { afterImport += it } })
    private val user = runBlocking { world.addUser() }

    @After
    fun tearDown() {
        scope.cancel()
        world.close()
    }

    private fun upload(bytes: ByteArray = Archive.library().bytes()): File = folder.newFile().apply { writeBytes(bytes) }

    private suspend fun finish(jobId: String) {
        jobs.coroutineOf(jobId)!!.join()
    }

    private fun refused(block: suspend () -> Unit): ApiFailure = runBlocking {
        try {
            block()
        } catch (e: ApiFailure) {
            return@runBlocking e
        }
        fail("not refused")
        error("unreachable")
    }

    @Test
    fun anImportRunsAsAJobAndEndsWithItsReport() = runBlocking<Unit> {
        val file = upload()
        val started = service.start(user, ImportMode.MERGE, "key-1", file)
        assertNull(started.report)
        val jobId = started.jobId!!
        finish(jobId)

        val job = jobs.job(user, jobId)
        assertEquals(JobKind.IMPORT, job.kind)
        assertEquals(JobState.DONE, job.state)
        assertEquals(JobResult.DONE, job.outcome!!.result)
        val report = ApiJson.json.decodeFromJsonElement(ImportReportDto.serializer(), job.outcome!!.report!!)
        assertEquals(12, report.posts)
        assertFalse("the upload is deleted", file.exists())
        assertEquals(listOf(user), afterImport)
        assertNotNull(db.importDao().importLog(user, "key-1"))
    }

    @Test
    fun theSameKeyAgainAnswersTheReportAndImportsNothing() = runBlocking<Unit> {
        val first = service.start(user, ImportMode.MERGE, "key-1", upload())
        finish(first.jobId!!)
        val firstReport = ApiJson.json.decodeFromJsonElement(ImportReportDto.serializer(), jobs.job(user, first.jobId!!).outcome!!.report!!)
        val seq = db.userStateDao().changeSeq(user)

        // A retry after a lost response, with the file sent again.
        val retryFile = upload()
        val retry = service.start(user, ImportMode.MERGE, "key-1", retryFile)
        assertNull(retry.jobId)
        assertEquals(firstReport, retry.report)
        assertEquals(seq, db.userStateDao().changeSeq(user))
        assertEquals(listOf(user), afterImport)
        // Even mode=replace, which the account's data would now refuse: the key wins.
        assertEquals(firstReport, service.start(user, ImportMode.REPLACE, "key-1", upload()).report)

        // A new key is a new import: everything is skipped this time.
        val second = service.start(user, ImportMode.MERGE, "key-2", upload())
        finish(second.jobId!!)
        val report = ApiJson.json.decodeFromJsonElement(ImportReportDto.serializer(), jobs.job(user, second.jobId!!).outcome!!.report!!)
        assertEquals(0 to 12, report.posts to report.skippedPosts)
    }

    @Test
    fun keysAreTheUsers() = runBlocking<Unit> {
        val other = world.addUser()
        val first = service.start(user, ImportMode.MERGE, "shared-key", upload())
        finish(first.jobId!!)
        // Another user's same key is their own import.
        val theirs = service.start(other, ImportMode.MERGE, "shared-key", upload())
        assertNotNull(theirs.jobId)
        finish(theirs.jobId!!)
        assertEquals(2, db.editionArticleDao().countSubscribed(other))
    }

    @Test
    fun theSameKeyWhileItsImportRunsAnswersThatJobAndAnotherKeyIsBusy() = runBlocking<Unit> {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Held at its first progress report, before anything is logged.
        val holding = object : JobService by jobs {
            override suspend fun start(userId: Long, kind: JobKind, work: suspend (JobProgress) -> JobOutcomeDto): JobStart =
                jobs.start(userId, kind) { progress ->
                    work(
                        JobProgress { stage, done, total ->
                            progress.report(stage, done, total)
                            if (userId == user && done == 0 && total == null) {
                                held.countDown()
                                release.await(5, TimeUnit.SECONDS)
                            }
                        },
                    )
                }
        }
        val slow = ImportServiceImpl(db, importer, holding, world.clock)
        val first = slow.start(user, ImportMode.MERGE, "key-1", upload())
        assertTrue(held.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(first.jobId, slow.start(user, ImportMode.MERGE, "key-1", upload()).jobId)
            val busy = refused { slow.start(user, ImportMode.MERGE, "key-2", upload()) }
            assertEquals(ApiError.BUSY, busy.code)
            // Another user is not held up.
            val other = world.addUser()
            assertNotNull(slow.start(other, ImportMode.MERGE, "key-2", upload(Archive(emptyList(), emptyList(), emptyList()).bytes())).jobId)
        } finally {
            release.countDown()
        }
        finish(first.jobId!!)
    }

    @Test
    fun replaceIsRefusedOnAnAccountWithDataAndIsAMergeOnAnEmptyOne() = runBlocking<Unit> {
        val empty = service.start(user, ImportMode.REPLACE, "key-1", upload())
        finish(empty.jobId!!)
        assertEquals(JobState.DONE, jobs.job(user, empty.jobId!!).state)

        val failure = refused { service.start(user, ImportMode.REPLACE, "key-2", upload()) }
        assertEquals(ApiError.CONFLICT, failure.code)
        assertEquals(409, failure.status)
        assertNull(db.importDao().importLog(user, "key-2"))
    }

    @Test
    fun aBadFileEndsTheJobFailedAndLogsNothing() = runBlocking<Unit> {
        val started = service.start(user, ImportMode.MERGE, "key-1", upload("<opml/>".toByteArray()))
        finish(started.jobId!!)
        assertEquals(JobState.FAILED, jobs.job(user, started.jobId!!).state)
        assertNull(db.importDao().importLog(user, "key-1"))
        assertTrue(afterImport.isEmpty())
        // The key is free to use again with a good file.
        val again = service.start(user, ImportMode.MERGE, "key-1", upload())
        finish(again.jobId!!)
        assertEquals(JobState.DONE, jobs.job(user, again.jobId!!).state)
    }

    @Test
    fun aKeyIsRequired() {
        assertEquals(400, refused { service.start(user, ImportMode.MERGE, " ", upload()) }.status)
    }
}

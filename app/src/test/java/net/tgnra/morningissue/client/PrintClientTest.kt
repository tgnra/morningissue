package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobStage
import net.tgnra.morningissue.api.JobState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Print now on the server: the job followed to its end, its stages shown, then a sync. */
@RunWith(AndroidJUnit4::class)
class PrintClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var kit: ClientKit
    private lateinit var printer: PrintClient

    @Before
    fun setUp() = runBlocking {
        server = FakeServer().start()
        kit = ClientKit(server, folder.root)
        kit.loggedIn()
        printer = PrintClient(kit.scope, kit.connection, kit.sync, kit.clock, pollMillis = 1, pollFailures = 3)
    }

    @After
    fun tearDown() {
        kit.close()
        server.stop()
    }

    private fun running(stage: JobStage, done: Int? = null, total: Int? = null) =
        FakeServer.job("job-1", JobKind.PRINT, JobState.RUNNING, stage, done, total)

    private fun finished(result: JobResult, editionId: Long? = null, state: JobState = JobState.DONE) =
        FakeServer.job("job-1", JobKind.PRINT, state, result = result, editionId = editionId)

    private suspend fun printAndAwait(): PrintResult {
        assertTrue(printer.printNow())
        return withTimeout(10_000) { printer.state.first { it is PrintState.Finished } as PrintState.Finished }.result
    }

    @Test
    fun aPrintGoesThroughItsStagesThenSyncsTheEditionIn() = runBlocking<Unit> {
        server.jobs["job-1"] = mutableListOf(
            running(JobStage.FETCHING_FEEDS, 3, 10),
            running(JobStage.SETTING_PAGES),
            running(JobStage.FETCHING_TEXT, 4, 8),
            running(JobStage.FETCHING_IMAGES, total = 6),
            finished(JobResult.PRINTED, editionId = 1),
        )
        // The edition the job prints, for the sync that follows it.
        server.publish(1, listOf(100))
        val seen = CopyOnWriteArrayList<PrintState>()
        val watcher = kit.scope.launch { printer.state.collect { seen += it } }

        assertEquals(PrintResult.Printed, printAndAwait())
        watcher.cancel()

        val stages = seen.filterIsInstance<PrintState.Printing>().map { it.stage }.distinct()
        assertEquals(BuildStage.Syncing(), stages.first())
        assertTrue("$stages", BuildStage.Syncing(3, 10) in stages || BuildStage.Building in stages)
        assertEquals(1, server.requestsTo("/print").size)
        // Synced once the job was done: the edition is in the cache when the result shows.
        assertEquals(1, kit.database.editionDao().count())
        printer.dismissResult()
        assertEquals(PrintState.Idle, printer.state.value)
    }

    @Test
    fun oneRunAtATime() = runBlocking<Unit> {
        server.jobs["job-1"] = MutableList(50) { running(JobStage.FETCHING_FEEDS, it, 50) }.apply { add(finished(JobResult.PRINTED, 1)) }
        assertTrue(printer.printNow())
        assertFalse(printer.printNow())
        withTimeout(10_000) { printer.state.first { it is PrintState.Finished } }
        assertEquals(1, server.requestsTo("/print").size)
    }

    @Test
    fun eachOutcomeIsSaidAsTheLocalPrinterSaidIt() = runBlocking<Unit> {
        val cases = listOf(
            finished(JobResult.PRINTED_OFFLINE, 1) to PrintResult.PrintedFromStored,
            finished(JobResult.NOTHING_NEW) to PrintResult.NothingNew,
            finished(JobResult.UNREACHABLE) to PrintResult.Unreachable,
            finished(JobResult.FAILED, state = JobState.FAILED) to PrintResult.Failed,
        )
        for ((job, expected) in cases) {
            server.jobs["job-1"] = mutableListOf(job)
            assertEquals("$job", expected, printAndAwait())
        }
    }

    @Test
    fun aPhoneThatCannotReachTheServerIsUnreachable() = runBlocking<Unit> {
        server.stop()
        assertEquals(PrintResult.Unreachable, printAndAwait())
    }

    @Test
    fun theExtraEditionSaysWhetherItPrintedReprintedOrHadNothing() = runBlocking<Unit> {
        val cases = listOf(
            JobResult.EXTRA_PRINTED to ExtraResult.Printed,
            JobResult.EXTRA_REPRINTED to ExtraResult.Reprinted,
            JobResult.EXTRA_NOTHING to ExtraResult.NothingToPrint,
        )
        server.nextJobId = "job-1"
        for ((result, expected) in cases) {
            server.jobs["job-1"] = mutableListOf(FakeServer.job("job-1", JobKind.EXTRA, JobState.DONE, result = result, editionId = 9))
            assertEquals(expected, printer.printExtra(setOf(1L, 2L)))
        }
        assertTrue(server.requestsTo("/editions/extra").last().body!!.utf8().contains("1"))
    }
}

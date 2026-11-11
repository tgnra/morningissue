package net.tgnra.morningissue.kobo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import okhttp3.OkHttpClient
import okhttp3.Request
import net.tgnra.morningissue.sync.NoOpFullTextFetcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [KoboSync.run] on the real port: it listens while it runs, and only then. */
@RunWith(AndroidJUnit4::class)
class KoboSyncTest {
    private lateinit var db: MorningIssueDatabase
    private val dir: File = createTempDirectory("kobo-sync").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
    private val installId = InstallId(settings)

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun sync() = KoboSync(
        db, installId, { KoboImages({ File(dir, "none") }, { OkHttpClient() }, File(dir, "cache")) }, NoOpFullTextFetcher(),
        KoboImageWidths(settings), NOW_CLOCK, deviceName = { "Pixel 9" },
    )

    private fun portIsFree(): Boolean = runCatching { ServerSocket(KoboProtocol.HTTP_PORT).close() }.isSuccess

    @Test
    fun theDbIdIsMadeOnceAndKept() = runBlocking {
        val id = installId.get()
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, installId.get())
    }

    @Test
    fun itListensWhileRunningAndStopsWhenCancelled() = runBlocking {
        assumeTrue("port ${KoboProtocol.HTTP_PORT} is in use on this machine", portIsFree())
        val sync = sync()
        val job = launch(Dispatchers.IO) { sync.run() }
        withTimeout(10_000) { sync.status.first { it.state == KoboSyncStatus.State.LISTENING } }
        val code = kotlinx.coroutines.withContext(Dispatchers.IO) {
            OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${KoboProtocol.HTTP_PORT}/v1/manifest").build()).execute().use { it.code }
        }
        assertEquals(401, code)
        job.cancelAndJoin()
        assertEquals(KoboSyncStatus.State.STOPPED, sync.status.value.state)
        assertEquals(true, portIsFree())
    }

    @Test
    fun aPortAnotherAppHoldsIsReported() = runBlocking {
        assumeTrue("port ${KoboProtocol.HTTP_PORT} is in use on this machine", portIsFree())
        ServerSocket(KoboProtocol.HTTP_PORT).use {
            val sync = sync()
            withTimeout(10_000) { sync.run() }
            assertEquals(KoboSyncStatus.State.PORT_IN_USE, sync.status.value.state)
        }
    }
}

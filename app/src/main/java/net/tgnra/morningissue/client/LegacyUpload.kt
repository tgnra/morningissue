package net.tgnra.morningissue.client

import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.data.archive.ArchiveTransfer
import net.tgnra.morningissue.data.db.MorningIssueDatabase

/** What the migration screen shows. */
sealed interface MigrationState {
    /** Looking at the account. */
    data object Checking : MigrationState

    /** The account already holds a library: merge this phone's into it, or use the account's. */
    data object Choice : MigrationState

    /** Writing this phone's library to a file, [done] of [total] posts. */
    data class Exporting(val done: Int, val total: Int) : MigrationState

    data object Uploading : MigrationState

    /** The server is importing, [done] of [total] when it says. */
    data class Importing(val done: Int, val total: Int) : MigrationState

    data class Done(val report: ImportReportDto?) : MigrationState

    /** Nothing was changed on the phone: Retry, or save a copy. [offline] when the server could not be reached. */
    data class Failed(val offline: Boolean, val message: String?) : MigrationState
}

/**
 * The first start after updating from the app that kept everything on the phone
 * (`docs/server-design.md`, 5.3): the local library is uploaded to the account with the
 * archive export and `POST /import?mode=merge`, under an `Idempotency-Key` that is kept until
 * the upload is done, so a retry (or a restart halfway) never imports twice. The phone's
 * database is left alone until the server has the library; then it becomes the cache.
 *
 * The work runs in [scope], so it survives the screen.
 */
class LegacyUpload(
    private val scope: CoroutineScope,
    private val database: MorningIssueDatabase,
    private val auth: AuthStore,
    private val connection: ServerConnection,
    private val exporter: ArchiveTransfer,
    private val sync: ServerSync,
    private val uploadFile: File,
    private val resetLocalCache: suspend () -> Unit,
    private val pollMillis: Long = 1_000,
) {
    private val _state = MutableStateFlow<MigrationState>(MigrationState.Checking)
    val state: StateFlow<MigrationState> = _state.asStateFlow()

    @Volatile
    private var running = false

    /**
     * Whether the migration screen is needed: the upload is not settled and the phone holds a
     * library (a feed). With nothing to upload, it is settled here as done.
     */
    suspend fun needed(): Boolean {
        val migration = auth.current().legacyMigration
        if (migration == LegacyMigration.DONE || migration == LegacyMigration.SKIPPED) return false
        if (database.cacheDao().feedIds().isNotEmpty()) return true
        auth.setLegacyMigration(LegacyMigration.DONE)
        return false
    }

    /**
     * Looks at the account: an empty one gets the upload at once; one with a library of its own
     * asks first. An upload that was under way (PENDING) carries on.
     */
    fun start() {
        if (running) return
        _state.value = MigrationState.Checking
        launch {
            if (auth.current().legacyMigration == LegacyMigration.PENDING) {
                upload()
                return@launch
            }
            val account = connection.call { it.account() }
            if (account.hasData) _state.value = MigrationState.Choice else upload()
        }
    }

    /** Merge this phone's library into the account (also Retry after a failure). */
    fun merge() {
        if (running) return
        // Set before the work starts, so the screen leaves the choice (or the failure) at once.
        _state.value = MigrationState.Exporting(0, 0)
        launch { upload() }
    }

    /** Use the account's library: this phone's is removed. */
    fun skip() {
        if (running) return
        _state.value = MigrationState.Checking
        launch {
            resetLocalCache()
            auth.setLegacyMigration(LegacyMigration.SKIPPED)
            uploadFile.delete()
            sync.sync("migration")
            _state.value = MigrationState.Done(null)
        }
    }

    private suspend fun upload() {
        val key = auth.importKey()
        auth.setLegacyMigration(LegacyMigration.PENDING, key)
        _state.value = MigrationState.Exporting(0, 0)
        withContext(Dispatchers.IO) {
            uploadFile.parentFile?.mkdirs()
            uploadFile.outputStream().use { out ->
                exporter.export(out) { _state.value = MigrationState.Exporting(it.postsDone, it.postsTotal) }
                out.flush()
            }
        }
        _state.value = MigrationState.Uploading
        val answer = connection.call { it.import(uploadFile, ImportMode.MERGE, key) }
        val report = answer.report ?: answer.jobId?.let { follow(it) }
        auth.setLegacyMigration(LegacyMigration.DONE)
        uploadFile.delete()
        resetLocalCache()
        sync.sync("migration")
        _state.value = MigrationState.Done(report)
    }

    private suspend fun follow(jobId: String): ImportReportDto? {
        while (true) {
            val job = connection.call { it.job(jobId) }
            when (job.state) {
                JobState.FAILED -> throw IOException(job.outcome?.message ?: "Import failed")
                JobState.DONE -> return job.outcome?.report?.let { runCatching { ApiJson.json.decodeFromJsonElement<ImportReportDto>(it) }.getOrNull() }
                JobState.RUNNING -> _state.value = MigrationState.Importing(job.done ?: 0, job.total ?: 0)
            }
            delay(pollMillis)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        running = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Offline) {
                _state.value = MigrationState.Failed(offline = true, message = e.message)
            } catch (e: Exception) {
                Log.w(TAG, "Migration failed", e)
                _state.value = MigrationState.Failed(offline = false, message = e.message)
            } finally {
                running = false
            }
        }
    }

    private companion object {
        const val TAG = "LegacyUpload"
    }
}

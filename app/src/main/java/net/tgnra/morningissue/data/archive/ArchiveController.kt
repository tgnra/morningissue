package net.tgnra.morningissue.data.archive

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.tgnra.morningissue.data.storage.StorageRepository
import net.tgnra.morningissue.data.storage.TrimReport

sealed interface ArchiveStatus {
    data object Idle : ArchiveStatus

    data class Running(val importing: Boolean, val progress: ArchiveProgress?) : ArchiveStatus

    /** [freed] is set when the export was followed by "free up space". */
    data class Exported(val report: ExportReport, val freed: TrimReport?) : ArchiveStatus

    data class Imported(val report: ImportReport) : ArchiveStatus

    data class Failed(val importing: Boolean, val reason: ArchiveFormatException.Reason?) : ArchiveStatus
}

/**
 * Runs archive exports and imports against files the reader picked with the system file
 * picker (Storage Access Framework). The work runs in the app's scope, so it survives leaving
 * the Settings screen; [status] is what the screen shows. One job at a time.
 */
class ArchiveController(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    private val transfer: ArchiveTransfer,
    private val storage: StorageRepository,
) {
    private val _status = MutableStateFlow<ArchiveStatus>(ArchiveStatus.Idle)
    val status: StateFlow<ArchiveStatus> = _status.asStateFlow()

    /**
     * Writes the archive to [uri]. With [freeSpace], the stored text of everything that may be
     * trimmed is dropped afterwards, and only once the file has been written and closed.
     */
    fun export(uri: Uri, freeSpace: Boolean) = start(importing = false) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No stream for $uri")
        val report = stream.use { out ->
            transfer.export(out) { _status.value = ArchiveStatus.Running(importing = false, progress = it) }.also { out.flush() }
        }
        ArchiveStatus.Exported(report, if (freeSpace) storage.trimAll() else null)
    }

    fun import(uri: Uri) = start(importing = true) {
        val report = transfer.import(
            open = { resolver.openInputStream(uri) ?: throw IOException("No stream for $uri") },
            onProgress = { _status.value = ArchiveStatus.Running(importing = true, progress = it) },
        )
        ArchiveStatus.Imported(report)
    }

    /** Forgets a finished job's result. */
    fun clear() {
        if (_status.value !is ArchiveStatus.Running) _status.value = ArchiveStatus.Idle
    }

    private fun start(importing: Boolean, job: suspend () -> ArchiveStatus) {
        val current = _status.value
        if (current is ArchiveStatus.Running || !_status.compareAndSet(current, ArchiveStatus.Running(importing, null))) return
        scope.launch {
            _status.value = try {
                job()
            } catch (e: CancellationException) {
                _status.value = ArchiveStatus.Idle
                throw e
            } catch (e: ArchiveFormatException) {
                ArchiveStatus.Failed(importing, e.reason)
            } catch (_: Exception) {
                ArchiveStatus.Failed(importing, null)
            }
        }
    }
}

package net.tgnra.morningissue.server.service

import java.io.OutputStream

/** What an archive export wrote. */
data class ExportReport(val feeds: Int = 0, val posts: Int = 0, val editions: Int = 0)

/**
 * The legacy archive export: the reverse of [ImportService], for moving a reader back to the
 * phone app. Implemented by `importer/ArchiveExporter`.
 */
interface ExportService {
    /**
     * Writes [userId]'s library to [output] as an archive (`ArchiveFormat`) the phone app's
     * "Restore from archive" reads, a line at a time. Does not close [output].
     */
    suspend fun export(userId: Long, output: OutputStream): ExportReport
}

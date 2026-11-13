package net.tgnra.morningissue.client

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.api.OpmlReportDto

/** "Added 38, skipped 4". */
data class OpmlImportReport(val added: Int = 0, val skipped: Int = 0)

/**
 * OPML import and export of the subscriptions, on the server: `POST /opml` runs as a job,
 * followed until done, then the feeds are synced; `GET /opml` is the export. The caller owns
 * and closes the streams. Throws `IOException` (an `ApiException` among them) on failure.
 */
class ServerOpml(
    private val connection: ServerConnection,
    private val sync: ServerSync,
    private val pollMillis: Long = 1_000,
) {
    suspend fun importFrom(input: InputStream): OpmlImportReport {
        val bytes = withContext(Dispatchers.IO) { input.readBytes() }
        if (bytes.size > ApiLimits.MAX_OPML_BYTES) throw IOException("OPML document too large")
        val jobId = connection.call { it.importOpml(bytes) }.jobId
        while (true) {
            val job = connection.call { it.job(jobId) }
            if (job.state == JobState.FAILED) throw IOException(job.outcome?.message ?: "OPML import failed")
            if (job.state == JobState.DONE) {
                sync.sync("opml")
                val report = job.outcome?.report?.let { runCatching { ApiJson.json.decodeFromJsonElement<OpmlReportDto>(it) }.getOrNull() }
                return OpmlImportReport(report?.added ?: 0, report?.skipped ?: 0)
            }
            delay(pollMillis)
        }
    }

    /** Writes the server's OPML to [output] and returns how many feeds it lists. */
    suspend fun exportTo(output: OutputStream): Int {
        val text = connection.call { it.exportOpml() }
        withContext(Dispatchers.IO) { output.write(text.toByteArray(Charsets.UTF_8)) }
        return OUTLINE.findAll(text).count()
    }

    private companion object {
        /** An outline with a feed address, which is what a feed is in OPML. */
        val OUTLINE = Regex("<outline\\b[^>]*\\bxmlUrl\\s*=", RegexOption.IGNORE_CASE)
    }
}

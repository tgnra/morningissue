package net.tgnra.morningissue.server.http

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.api.ApiContentTypes
import net.tgnra.morningissue.api.ApiPaths

/**
 * `GET /export`: the caller's library as an archive the phone app's "Restore from archive"
 * reads. Written first to `<dataDir>/imports/export-<random>.zip.part` (so a failure answers an
 * error rather than a cut-off zip, and no read transaction waits on a slow download), then unlinked and
 * sent as an attachment from the still-open file.
 */
internal fun Route.exportRoutes(s: ServerServices) {
    get(ApiPaths.EXPORT, Access.PHONE) { p ->
        val options = call.application.attributes[OPTIONS_KEY]
        val dir = options.importsDir
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val file = File(dir, "export-${UUID.randomUUID()}.zip.part")
        val input = try {
            withContext(Dispatchers.IO) {
                file.outputStream().use { out -> s.exports.export(p.userId, out) }
                // Opened before the response and unlinked at once: Ktor runs the body writer after
                // this handler returns, so deleting the file in a `finally` here would race it.
                // The open stream keeps the data readable; nothing is left behind on disk.
                file.inputStream().also { file.delete() }
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.IO) { file.delete() }
            throw t
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, EXPORT_FILE_NAME).toString(),
        )
        call.respondOutputStream(ContentType.parse(ApiContentTypes.ZIP)) { input.use { it.copyTo(this) } }
    }
}

/** The name the archive is offered under. */
const val EXPORT_FILE_NAME = "morning-issue-archive.zip"

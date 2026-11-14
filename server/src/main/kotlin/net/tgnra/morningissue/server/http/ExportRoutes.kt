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
 * error rather than a cut-off zip, and no read transaction waits on a slow download), then sent
 * as an attachment and deleted.
 */
internal fun Route.exportRoutes(s: ServerServices) {
    get(ApiPaths.EXPORT, Access.PHONE) { p ->
        val options = call.application.attributes[OPTIONS_KEY]
        val dir = options.importsDir
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val file = File(dir, "export-${UUID.randomUUID()}.zip.part")
        try {
            withContext(Dispatchers.IO) {
                file.outputStream().use { out -> s.exports.export(p.userId, out) }
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, EXPORT_FILE_NAME).toString(),
            )
            call.respondOutputStream(ContentType.parse(ApiContentTypes.ZIP)) { file.inputStream().use { it.copyTo(this) } }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }
}

/** The name the archive is offered under. */
const val EXPORT_FILE_NAME = "morning-issue-archive.zip"

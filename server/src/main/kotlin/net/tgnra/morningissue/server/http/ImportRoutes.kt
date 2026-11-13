package net.tgnra.morningissue.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.ApiQuery
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.server.service.ApiFailure

/** An `Idempotency-Key` is a UUID; anything plain up to 64 characters is taken, since it names a file. */
private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9-]{1,64}$")

/**
 * 4.9: `POST /import?mode=merge|replace`. The archive is streamed to
 * `<dataDir>/imports/<userId>-<key>.zip` (at most `import.maxMb`), then handed to the import
 * service: `202 {"jobId"}` for a new import, `200 {"report"}` for a key already used.
 */
internal fun Route.importRoutes(s: ServerServices, options: HttpOptions) {
    post(ApiPaths.IMPORT, Access.PHONE) { p ->
        val key = call.request.headers[IDEMPOTENCY_KEY_HEADER]?.trim()
            ?: throw ApiFailure.badRequest("The $IDEMPOTENCY_KEY_HEADER header is required")
        if (!IDEMPOTENCY_KEY.matches(key)) throw ApiFailure.badRequest("$IDEMPOTENCY_KEY_HEADER must be a UUID")
        val mode = when (call.queryString(ApiQuery.MODE)?.lowercase()) {
            null, ApiQuery.MODE_MERGE -> ImportMode.MERGE
            ApiQuery.MODE_REPLACE -> ImportMode.REPLACE
            else -> throw ApiFailure.badRequest("\"mode\" must be ${ApiQuery.MODE_MERGE} or ${ApiQuery.MODE_REPLACE}")
        }
        checkDeclaredLength(call, options.importMaxBytes)

        val dir = options.importsDir
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val target = File(dir, "${p.userId}-$key.zip")
        val part = File(dir, "${p.userId}-$key.zip.part")
        try {
            val size = withContext(Dispatchers.IO) {
                part.outputStream().buffered().use { out -> copyBody(call.receiveChannel(), out, options.importMaxBytes) }
            }
            if (size == 0L) throw ApiFailure.badRequest("The archive is empty")
            withContext(Dispatchers.IO) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            part.delete()
        }

        val result = try {
            s.imports.start(p.userId, mode, key, target)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        if (result.jobId != null) {
            call.respond(HttpStatusCode.Accepted, result)
        } else {
            // A key already used: nothing to import.
            target.delete()
            call.respond(HttpStatusCode.OK, result)
        }
    }
}

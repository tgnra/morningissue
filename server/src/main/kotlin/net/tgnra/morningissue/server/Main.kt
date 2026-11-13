package net.tgnra.morningissue.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.HealthResponse
import org.slf4j.LoggerFactory

/**
 * The server's command line: `serve | reset-password <user> | import <user> <file.zip>`.
 *
 * Phase 1 skeleton: `serve` answers `/api/v1/health` only, on the port and address from the
 * environment (`MORNINGISSUE_PORT`, default 8417; `MORNINGISSUE_LISTEN`, default 0.0.0.0), and
 * with `MORNINGISSUE_DATA_DIR` set puts temporary files under it. The TOML config, users, the
 * database and the other routes come with the HTTP workstream (W-C).
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "serve", null -> serve()
        else -> {
            System.err.println("Usage: morningissue-server serve")
            exitProcess(2)
        }
    }
}

/** The server's version, from the jar manifest; `dev` when run from classes. */
val SERVER_VERSION: String = VersionAnchor::class.java.`package`?.implementationVersion ?: "dev"

private object VersionAnchor

private fun serve() {
    System.getenv("MORNINGISSUE_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { useDataDirForTemp(File(it)) }
    val port = System.getenv("MORNINGISSUE_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val listen = System.getenv("MORNINGISSUE_LISTEN")?.takeIf { it.isNotBlank() } ?: DEFAULT_LISTEN
    LoggerFactory.getLogger("net.tgnra.morningissue.server").info("Morning Issue server {} listening on {}:{}", SERVER_VERSION, listen, port)
    embeddedServer(CIO, port = port, host = listen, module = Application::morningIssue).start(wait = true)
}

/**
 * Points `java.io.tmpdir` at `<dataDir>/tmp`, where the bundled SQLite extracts its native
 * library, unless the JVM was started with its own `-Djava.io.tmpdir`. Must run before anything
 * makes a temporary file (the JDK reads the property once). Works under systemd `DynamicUser`
 * and read-only roots, where `/tmp` may not be usable.
 */
fun useDataDirForTemp(dataDir: File) {
    val explicit = ManagementFactory.getRuntimeMXBean().inputArguments.any { it.startsWith("-Djava.io.tmpdir=") }
    if (explicit) return
    val tmp = File(dataDir, "tmp")
    if (tmp.isDirectory || tmp.mkdirs()) System.setProperty("java.io.tmpdir", tmp.absolutePath)
}

/** The Ktor application: JSON and the routes under `/api/v1`. */
fun Application.morningIssue() {
    install(ContentNegotiation) {
        json(ApiJson.json)
    }
    routing {
        route(ApiPaths.BASE) {
            get(ApiPaths.HEALTH) {
                call.respond(HealthResponse(version = SERVER_VERSION))
            }
        }
    }
}

const val DEFAULT_PORT = 8417
const val DEFAULT_LISTEN = "0.0.0.0"

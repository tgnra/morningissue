package net.tgnra.morningissue.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.time.Clock
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.server.auth.DbAuthService
import net.tgnra.morningissue.server.auth.DbDeviceService
import net.tgnra.morningissue.server.config.ConfigException
import net.tgnra.morningissue.server.config.ConfigLoader
import net.tgnra.morningissue.server.config.ServerConfig
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.http.HttpOptions
import net.tgnra.morningissue.server.http.ServerServices
import net.tgnra.morningissue.server.http.installApi
import net.tgnra.morningissue.server.service.ApiFailure
import org.slf4j.LoggerFactory

/**
 * The server's command line:
 *
 * ```
 * morningissue-server [--config <file>] serve
 * morningissue-server [--config <file>] reset-password <user>
 * morningissue-server [--config <file>] import [--merge] <user> <file.zip>
 * ```
 *
 * The real services are built by a [Container]; integration replaces [AccountsContainer] with
 * the full `ServerContainer` here.
 */
fun main(args: Array<String>) {
    exitProcess(Cli(::AccountsContainer).run(args))
}

/** The server's version, from the jar manifest; `dev` when run from classes. */
val SERVER_VERSION: String = VersionAnchor::class.java.`package`?.implementationVersion ?: "dev"

private object VersionAnchor

/**
 * Everything a command needs, built from the config: the database, the services the routes
 * call, and any background work. Hand-written dependency injection, like the app's
 * `AppContainer`.
 */
interface Container : AutoCloseable {
    val config: ServerConfig
    val database: ServerDatabase
    val services: ServerServices

    /** Starts background work (the scheduler, refreshes) for `serve`, after the users are bootstrapped. Not called by the other commands. */
    fun start() {}

    override fun close() {
        database.close()
    }
}

/**
 * The container until the feed, edition and sync services are wired in: real accounts and
 * devices, `501 not_implemented` for the rest.
 */
class AccountsContainer(override val config: ServerConfig) : Container {
    private val clock: Clock = Clock.systemUTC()
    override val database: ServerDatabase = ServerDatabase.open(config.databaseFile)
    override val services: ServerServices = ServerServices(
        auth = DbAuthService(database, config.initialPasswordsDir, clock),
        devices = DbDeviceService(database, config.publicUrl, clock),
    )
}

/** Parses the arguments and runs a command; returns the exit status. */
class Cli(
    private val containerFactory: (ServerConfig) -> Container,
    private val env: Map<String, String> = System.getenv(),
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    private val log = LoggerFactory.getLogger("net.tgnra.morningissue.server")

    fun run(args: Array<String>): Int {
        var configPath: String? = null
        val rest = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--config" || a == "-c" -> {
                    configPath = args.getOrNull(i + 1) ?: return usage("--config needs a file")
                    i++
                }
                a.startsWith("--config=") -> configPath = a.substringAfter('=')
                a == "--help" || a == "-h" -> return usage(null, status = 0)
                a == "--version" -> {
                    out.println(SERVER_VERSION)
                    return 0
                }
                else -> rest += a
            }
            i++
        }
        val command = rest.firstOrNull() ?: "serve"
        val config = try {
            ConfigLoader.load(configPath, env) { log.warn(it) }
        } catch (e: ConfigException) {
            err.println(e.message)
            return 2
        }
        return when (command) {
            "serve" -> if (rest.size == 1 || rest.isEmpty()) serve(config) else usage("serve takes no arguments")
            "reset-password" -> rest.getOrNull(1)?.takeIf { rest.size == 2 }?.let { resetPassword(config, it) } ?: usage("reset-password needs a user name")
            "import" -> {
                val merge = "--merge" in rest
                val positional = rest.drop(1).filter { it != "--merge" }
                if (positional.size != 2) usage("import needs a user name and an archive") else import(config, positional[0], File(positional[1]), merge)
            }
            else -> usage("Unknown command \"$command\"")
        }
    }

    private fun usage(problem: String?, status: Int = 2): Int {
        val stream = if (status == 0) out else err
        problem?.let { stream.println(it) }
        stream.println(
            """
            Usage: morningissue-server [--config <file>] <command>
              serve                               run the server (the default)
              reset-password <user>               give a user a new generated password and sign out their phones
              import [--merge] <user> <file.zip>  import an archive exported by the app (into an empty account unless --merge)
            The config file is --config, else ${'$'}${ConfigLoader.ENV_CONFIG}, else ${ConfigLoader.DEFAULT_PATH} (optional).
            """.trimIndent(),
        )
        return status
    }

    /** Creates the data directory and points temporary files into it; before the database opens. */
    private fun prepareDataDir(config: ServerConfig) {
        config.dataDir.mkdirs()
        useDataDirForTemp(config.dataDir)
    }

    private fun serve(config: ServerConfig): Int {
        prepareDataDir(config)
        val container = containerFactory(config)
        runBlocking { container.services.auth.bootstrapUsers(config.users) }
        if (config.users.isEmpty()) {
            log.warn("No users are configured: list them in [users] names in the config file or in ${ConfigLoader.ENV_USERS}")
        }
        container.start()
        log.info(
            "Morning Issue server {} listening on {}:{}, data in {}{}",
            SERVER_VERSION, config.listen, config.port, config.dataDir.absolutePath,
            config.publicUrl?.let { ", public URL $it" } ?: "",
        )
        val options = httpOptions(config)
        val server = embeddedServer(CIO, port = config.port, host = config.listen) { morningIssue(container.services, options) }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop(1000, 5000)
                container.close()
            },
        )
        server.start(wait = true)
        return 0
    }

    private fun resetPassword(config: ServerConfig, username: String): Int {
        prepareDataDir(config)
        return containerFactory(config).use { container ->
            try {
                val password = runBlocking { container.services.auth.resetPassword(username) }
                out.println("New password for \"$username\": $password")
                out.println("Also written to ${File(config.initialPasswordsDir, "$username.txt").path}. It must be changed at the next login.")
                0
            } catch (e: ApiFailure) {
                err.println(e.message)
                1
            }
        }
    }

    private fun import(config: ServerConfig, username: String, archive: File, merge: Boolean): Int {
        if (!archive.isFile) {
            err.println("No file ${archive.path}")
            return 1
        }
        prepareDataDir(config)
        return containerFactory(config).use { container ->
            runBlocking {
                val user = container.database.accountDao().userByName(username)
                if (user == null) {
                    err.println("No user \"$username\"; add them to the config and start the server once")
                    return@runBlocking 1
                }
                val key = UUID.randomUUID().toString()
                config.importsDir.mkdirs()
                val copy = File(config.importsDir, "${user.id}-$key.zip")
                archive.copyTo(copy, overwrite = true)
                try {
                    val mode = if (merge) ImportMode.MERGE else ImportMode.REPLACE
                    val start = container.services.imports.start(user.id, mode, key, copy)
                    start.report?.let {
                        out.println(ApiJson.json.encodeToString(it))
                        return@runBlocking 0
                    }
                    val jobId = start.jobId ?: return@runBlocking 1
                    while (true) {
                        val job = container.services.jobs.job(user.id, jobId)
                        if (job.state != JobState.RUNNING) {
                            out.println(ApiJson.json.encodeToString(job))
                            return@runBlocking if (job.state == JobState.DONE) 0 else 1
                        }
                        delay(1000)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    1
                } catch (e: ApiFailure) {
                    copy.delete()
                    err.println(e.message)
                    1
                } catch (e: NotImplementedError) {
                    copy.delete()
                    err.println("Import is not available in this build: ${e.message}")
                    1
                }
            }
        }
    }
}

/** The HTTP layer's options from the config. */
fun httpOptions(config: ServerConfig) = HttpOptions(importsDir = config.importsDir, importMaxBytes = config.import.maxBytes)

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

/** The Ktor application: the API under `/api/v1` over [services]. Tests install it with fakes. */
fun Application.morningIssue(services: ServerServices = ServerServices(), options: HttpOptions = HttpOptions()) {
    installApi(services, options)
}

const val DEFAULT_PORT = ServerConfig.DEFAULT_PORT
const val DEFAULT_LISTEN = ServerConfig.DEFAULT_LISTEN

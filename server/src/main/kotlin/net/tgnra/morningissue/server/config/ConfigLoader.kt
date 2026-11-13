package net.tgnra.morningissue.server.config

import java.io.File
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/** A config file or environment value that cannot be used; the message says which and why. */
class ConfigException(message: String) : RuntimeException(message)

/**
 * Reads the TOML config (section 7.1), then applies the environment overrides, so Docker can run
 * with no file at all: `MORNINGISSUE_USERS` (comma-separated), `MORNINGISSUE_PORT`,
 * `MORNINGISSUE_LISTEN`, `MORNINGISSUE_PUBLIC_URL`, `MORNINGISSUE_DATA_DIR`.
 */
object ConfigLoader {
    const val ENV_CONFIG = "MORNINGISSUE_CONFIG"
    const val ENV_USERS = "MORNINGISSUE_USERS"
    const val ENV_PORT = "MORNINGISSUE_PORT"
    const val ENV_LISTEN = "MORNINGISSUE_LISTEN"
    const val ENV_PUBLIC_URL = "MORNINGISSUE_PUBLIC_URL"
    const val ENV_DATA_DIR = "MORNINGISSUE_DATA_DIR"
    const val DEFAULT_PATH = "/etc/morningissue/config.toml"

    /** A user name is a file name too (`initial-passwords/<name>.txt`), so it is kept plain. */
    private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    private val KNOWN = mapOf(
        "server" to setOf("listen", "port", "publicUrl", "dataDir"),
        "users" to setOf("names"),
        "refresh" to setOf("intervalMinutes", "prePrintMaxAgeMinutes", "concurrency", "timeoutSeconds"),
        "editions" to setOf("buildLeadMinutes"),
        "kobo" to setOf("imageCacheMb"),
        "import" to setOf("maxMb"),
    )

    /**
     * Which file to read: [explicit] (`--config`), else `$MORNINGISSUE_CONFIG`, else
     * [DEFAULT_PATH]. Returns the file and whether it was named, because only a named file
     * must exist.
     */
    fun locate(explicit: String?, env: Map<String, String>): Pair<File, Boolean> {
        explicit?.takeIf { it.isNotBlank() }?.let { return File(it) to true }
        env[ENV_CONFIG]?.takeIf { it.isNotBlank() }?.let { return File(it) to true }
        return File(DEFAULT_PATH) to false
    }

    /**
     * Loads the config: the file from [locate] if there is one, then [env] on top. [warn] hears
     * about keys this version does not know, which are ignored.
     */
    fun load(explicit: String?, env: Map<String, String> = System.getenv(), warn: (String) -> Unit = {}): ServerConfig {
        val (file, named) = locate(explicit, env)
        val fromFile = when {
            file.isFile -> parse(file.readText(), file.path, warn)
            named -> throw ConfigException("Config file ${file.path} does not exist")
            else -> ServerConfig()
        }
        return applyEnv(fromFile, env)
    }

    /** Parses TOML text; [source] names it in errors. */
    fun parse(text: String, source: String = "config", warn: (String) -> Unit = {}): ServerConfig {
        val toml = Toml.parse(text)
        if (toml.hasErrors()) {
            throw ConfigException("$source: " + toml.errors().joinToString("; ") { it.toString() })
        }
        for (key in toml.keySet()) {
            val known = KNOWN[key]
            val table = toml.get(key) as? TomlTable
            if (known == null || table == null) {
                warn("$source: unknown key \"$key\" ignored")
                continue
            }
            for (sub in table.keySet()) if (sub !in known) warn("$source: unknown key \"$key.$sub\" ignored")
        }
        val r = Reader(toml, source)
        val defaults = ServerConfig()
        return ServerConfig(
            listen = r.string("server.listen") ?: defaults.listen,
            port = r.long("server.port")?.let { port(it, "$source: server.port") } ?: defaults.port,
            publicUrl = r.string("server.publicUrl")?.let(::publicUrl),
            dataDir = r.string("server.dataDir")?.let(::File) ?: defaults.dataDir,
            users = r.strings("users.names")?.let { users(it, "$source: users.names") } ?: defaults.users,
            refresh = RefreshConfig(
                intervalMinutes = r.positive("refresh.intervalMinutes") ?: defaults.refresh.intervalMinutes,
                prePrintMaxAgeMinutes = r.nonNegative("refresh.prePrintMaxAgeMinutes") ?: defaults.refresh.prePrintMaxAgeMinutes,
                concurrency = r.positive("refresh.concurrency")?.toInt() ?: defaults.refresh.concurrency,
                timeoutSeconds = r.positive("refresh.timeoutSeconds") ?: defaults.refresh.timeoutSeconds,
            ),
            editions = EditionsConfig(
                buildLeadMinutes = r.nonNegative("editions.buildLeadMinutes") ?: defaults.editions.buildLeadMinutes,
            ),
            kobo = KoboConfig(imageCacheMb = r.nonNegative("kobo.imageCacheMb") ?: defaults.kobo.imageCacheMb),
            import = ImportConfig(maxMb = r.positive("import.maxMb") ?: defaults.import.maxMb),
        )
    }

    /** The environment overrides, applied after the file. Blank values are ignored. */
    fun applyEnv(config: ServerConfig, env: Map<String, String>): ServerConfig {
        fun get(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
        var c = config
        get(ENV_USERS)?.let { raw -> c = c.copy(users = users(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }, ENV_USERS)) }
        get(ENV_PORT)?.let { raw ->
            val port = raw.toLongOrNull() ?: throw ConfigException("$ENV_PORT must be a number, not \"$raw\"")
            c = c.copy(port = port(port, ENV_PORT))
        }
        get(ENV_LISTEN)?.let { c = c.copy(listen = it) }
        get(ENV_PUBLIC_URL)?.let { c = c.copy(publicUrl = publicUrl(it)) }
        get(ENV_DATA_DIR)?.let { c = c.copy(dataDir = File(it)) }
        return c
    }

    private fun port(value: Long, where: String): Int {
        if (value !in 1..65535) throw ConfigException("$where must be 1..65535, not $value")
        return value.toInt()
    }

    private fun publicUrl(raw: String): String? = raw.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    private fun users(names: List<String>, where: String): List<String> {
        for (name in names) {
            if (!USERNAME.matches(name)) {
                throw ConfigException("$where: \"$name\" is not a usable user name (letters, digits, '.', '_' and '-', at most 64)")
            }
        }
        val dupes = names.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw ConfigException("$where: ${dupes.joinToString()} listed more than once")
        return names
    }

    private class Reader(private val toml: TomlTable, private val source: String) {
        fun string(key: String): String? {
            val v = toml.get(key) ?: return null
            return v as? String ?: throw wrongType(key, "a string")
        }

        fun long(key: String): Long? {
            val v = toml.get(key) ?: return null
            return v as? Long ?: throw wrongType(key, "an integer")
        }

        fun positive(key: String): Long? = long(key)?.also { if (it <= 0) throw ConfigException("$source: $key must be above 0") }

        fun nonNegative(key: String): Long? = long(key)?.also { if (it < 0) throw ConfigException("$source: $key must not be negative") }

        fun strings(key: String): List<String>? {
            val v = toml.get(key) ?: return null
            val array = v as? TomlArray ?: throw wrongType(key, "a list of strings")
            return (0 until array.size()).map { i -> array.get(i) as? String ?: throw wrongType(key, "a list of strings") }
        }

        private fun wrongType(key: String, what: String) = ConfigException("$source: $key must be $what")
    }
}

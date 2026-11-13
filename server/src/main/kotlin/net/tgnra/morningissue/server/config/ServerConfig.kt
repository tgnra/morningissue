package net.tgnra.morningissue.server.config

import java.io.File
import java.time.Duration

/**
 * Everything the config file and the environment set (`docs/server-design.md`, section 7.1).
 * Every field has the default the design names, so a Docker container can run with no file and
 * only `MORNINGISSUE_USERS`.
 */
data class ServerConfig(
    val listen: String = DEFAULT_LISTEN,
    val port: Int = DEFAULT_PORT,
    /** Shown to Kobos (`/kobo/config.json`, `/kobo/hello`) and in logs; no trailing slash. */
    val publicUrl: String? = null,
    val dataDir: File = File(DEFAULT_DATA_DIR),
    /** The accounts. A name removed from the list is disabled; its data is kept. */
    val users: List<String> = emptyList(),
    val refresh: RefreshConfig = RefreshConfig(),
    val editions: EditionsConfig = EditionsConfig(),
    val kobo: KoboConfig = KoboConfig(),
    val import: ImportConfig = ImportConfig(),
) {
    val databaseFile: File get() = File(dataDir, "morningissue.db")
    val initialPasswordsDir: File get() = File(dataDir, "initial-passwords")
    val cacheDir: File get() = File(dataDir, "cache")
    val koboImagesDir: File get() = File(dataDir, "kobo-images")
    val importsDir: File get() = File(dataDir, "imports")
    val tmpDir: File get() = File(dataDir, "tmp")

    companion object {
        const val DEFAULT_LISTEN = "0.0.0.0"
        const val DEFAULT_PORT = 8417
        const val DEFAULT_DATA_DIR = "/var/lib/morningissue"
    }
}

/** `[refresh]`: the periodic refresh and the pre-print refresh (section 3). */
data class RefreshConfig(
    val intervalMinutes: Long = 120,
    val prePrintMaxAgeMinutes: Long = 120,
    val concurrency: Int = 6,
    val timeoutSeconds: Long = 240,
) {
    val interval: Duration get() = Duration.ofMinutes(intervalMinutes)
    val prePrintMaxAge: Duration get() = Duration.ofMinutes(prePrintMaxAgeMinutes)
    val timeout: Duration get() = Duration.ofSeconds(timeoutSeconds)
}

/** `[editions]`. */
data class EditionsConfig(
    val buildLeadMinutes: Long = 30,
) {
    val buildLead: Duration get() = Duration.ofMinutes(buildLeadMinutes)
}

/** `[kobo]`: the grey picture cache's size. */
data class KoboConfig(
    val imageCacheMb: Long = 1024,
) {
    val imageCacheBytes: Long get() = imageCacheMb * MB
}

/** `[import]`: the largest legacy archive accepted. */
data class ImportConfig(
    val maxMb: Long = 4096,
) {
    val maxBytes: Long get() = maxMb * MB
}

private const val MB = 1024L * 1024

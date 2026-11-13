package net.tgnra.morningissue.server.feeds.discovery

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.CatalogueCluster
import net.tgnra.morningissue.engine.CatalogueFeed
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.model.CadenceTier

/** Where the bundled catalogue comes from: the server's classpath, a file in tests. */
fun interface CatalogueSource {
    /** A fresh stream of the catalogue file, or null when there is none. */
    fun open(): InputStream?

    companion object {
        const val RESOURCE_PATH = "discovery/catalogue.db"

        /** The catalogue on the classpath (`assets/discovery/catalogue.db`), or null when the build has none. */
        fun classpath(): CatalogueSource = CatalogueSource {
            CatalogueSource::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
        }

        /** Changes whenever the classpath catalogue could have: its size and time in the jar. */
        fun classpathStamp(): String = runCatching {
            val url = CatalogueSource::class.java.classLoader.getResource(RESOURCE_PATH) ?: return "none"
            val connection = url.openConnection()
            "${connection.contentLengthLong}/${connection.lastModified}"
        }.getOrDefault("unknown")
    }
}

/** What [CatalogueStore.load] found. */
sealed interface CatalogueStatus {
    data class Ready(val catalogue: Catalogue) : CatalogueStatus

    /** This build carries no catalogue: discovery has no suggestions. */
    data object Missing : CatalogueStatus

    /** The file is there but the server cannot use it; [reason] is for logs and tests. */
    data class Refused(val reason: String) : CatalogueStatus
}

/**
 * The bundled feed catalogue, built offline by `tools/discovery/` (section 4.7), ported from the
 * app's `CatalogueStore` to the JVM: read through `androidx.sqlite`'s [BundledSQLiteDriver]
 * connection API instead of Android's SQLite.
 *
 * **Install.** SQLite cannot open a file inside the jar, so the resource is copied to
 * `<dataDir>/cache/catalogue.db` on first use. It is copied again only when the resource could
 * have changed ([versionStamp]) and its `built_at` or `format_version` then differ from the
 * installed copy's.
 *
 * **Opening.** Read-only. A file whose format or embedding model the server does not know is
 * refused ([CatalogueStatus.Refused]). The format is the one the app's store documents.
 */
class CatalogueStore(
    private val source: CatalogueSource,
    private val directory: File,
    /** Changes whenever the resource could have. */
    private val versionStamp: () -> String,
    private val modelId: Int = StaticEmbedder.MODEL_ID,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val driver = BundledSQLiteDriver()

    @Volatile
    private var loaded: CatalogueStatus? = null

    /** The catalogue, installing it first if need be. Remembered for the life of the process once it opens. */
    suspend fun load(): CatalogueStatus = loaded ?: mutex.withLock {
        loaded ?: withContext(dispatcher) { install() }.also { if (it is CatalogueStatus.Ready) loaded = it }
    }

    private fun install(): CatalogueStatus {
        directory.mkdirs()
        val installed = File(directory, FILE_NAME)
        val stampFile = File(directory, STAMP_NAME)
        val stamp = versionStamp()
        if (!installed.exists() || runCatching { stampFile.readText() }.getOrNull() != stamp) {
            val stream = runCatching { source.open() }.getOrNull()
                ?: return if (installed.exists()) open(installed) else CatalogueStatus.Missing
            val temp = File(directory, "$FILE_NAME.tmp")
            try {
                stream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                temp.delete()
                return if (installed.exists()) open(installed) else CatalogueStatus.Refused("copy failed: ${e.message}")
            }
            val fresh = stampOf(temp)
            val current = if (installed.exists()) stampOf(installed) else null
            if (current == null || fresh != current) {
                if (!temp.renameTo(installed)) {
                    installed.delete()
                    temp.renameTo(installed)
                }
            } else {
                temp.delete()
            }
            stampFile.writeText(stamp)
        }
        return open(installed)
    }

    private fun openReadOnly(file: File): SQLiteConnection = driver.open(file.path, SQLITE_OPEN_READONLY)

    /** `format_version` and `built_at` of a catalogue file, or null when it cannot be read. */
    private fun stampOf(file: File): String? = try {
        openReadOnly(file).use { db ->
            val meta = readTextMeta(db)
            "${meta["format_version"]}/${meta["built_at"]}"
        }
    } catch (_: Exception) {
        null
    }

    private fun open(file: File): CatalogueStatus {
        val db = try {
            openReadOnly(file)
        } catch (e: Exception) {
            return CatalogueStatus.Refused("cannot open: ${e.message}")
        }
        return try {
            val version = db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
            if (version != USER_VERSION) return refuse(db, "user_version $version")
            val meta = readTextMeta(db)
            if (meta["format_version"] != FORMAT_VERSION) return refuse(db, "format_version ${meta["format_version"]}")
            if (meta["model_id"] != modelId.toString()) return refuse(db, "model_id ${meta["model_id"]}, the server's embedder is $modelId")
            val dims = meta["dims"]?.toIntOrNull()
            if (dims != StaticEmbedder.DIMENSIONS) return refuse(db, "dims $dims")
            val mean = db.prepare("SELECT value FROM meta WHERE key = 'mean'").use { if (it.step() && !it.isNull(0)) it.getBlob(0) else null }
            if (mean == null || mean.size != dims * 4) return refuse(db, "no mean vector")
            val meanVector = FloatArray(dims)
            ByteBuffer.wrap(mean).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(meanVector)
            CatalogueStatus.Ready(Catalogue(db, meta["built_at"]?.toLongOrNull()?.let(Instant::ofEpochMilli), meanVector))
        } catch (e: Exception) {
            refuse(db, "unreadable: ${e.message}")
        }
    }

    private fun refuse(db: SQLiteConnection, reason: String): CatalogueStatus {
        db.close()
        return CatalogueStatus.Refused(reason)
    }

    private fun readTextMeta(db: SQLiteConnection): Map<String, String?> =
        db.prepare("SELECT key, CAST(value AS TEXT) FROM meta WHERE key != 'mean'").use { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), if (statement.isNull(1)) null else statement.getText(1)) }
        }

    companion object {
        const val FILE_NAME = "catalogue.db"
        private const val STAMP_NAME = "catalogue.stamp"
        const val USER_VERSION = 1
        const val FORMAT_VERSION = "1"

        /** The server's store: the classpath catalogue, installed under `<dataDir>/cache/`. */
        fun of(dataDir: File): CatalogueStore =
            CatalogueStore(CatalogueSource.classpath(), File(dataDir, "cache"), versionStamp = CatalogueSource::classpathStamp)
    }
}

/**
 * An open, read-only catalogue. Feeds and clusters are read once and kept; headlines are read
 * per card. The connection is used by one thread at a time.
 */
class Catalogue internal constructor(
    private val db: SQLiteConnection,
    /** When the pipeline built it; part of the deck's identity. */
    val builtAt: Instant?,
    /** The mean of every feed centroid, subtracted before similarities are taken. */
    val mean: FloatArray,
) {
    /** The clusters, each with the unit-length mean of its members' feed centroids; a cluster with no member feeds is left out. */
    val clusters: List<CatalogueCluster> by lazy {
        val sums = HashMap<Int, FloatArray>()
        for (feed in feeds) {
            val id = feed.clusterId ?: continue
            val centroid = feed.centroid ?: continue
            val sum = sums.getOrPut(id) { FloatArray(centroid.size) }
            if (sum.size != centroid.size) continue
            for (i in sum.indices) sum[i] += centroid[i]
        }
        synchronized(db) {
            db.prepare("SELECT id, label FROM cluster ORDER BY id").use { c ->
                buildList {
                    while (c.step()) {
                        val id = c.getLong(0).toInt()
                        val sum = sums[id] ?: continue
                        val norm = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
                        if (norm <= 0f) continue
                        add(CatalogueCluster(id, c.text(1) ?: "", FloatArray(sum.size) { sum[it] / norm }))
                    }
                }
            }
        }
    }

    val feeds: List<CatalogueFeed> by lazy {
        synchronized(db) {
            db.prepare(
                "SELECT id, url, url_key, site_url, host, title, description, icon_url, language, tier, " +
                    "median_interval_hours, posts_per_week, full_text_share, last_post_at, cluster_id, categories, " +
                    "source, quality, centroid, scale FROM feed ORDER BY id",
            ).use { c ->
                buildList {
                    while (c.step()) {
                        val url = c.text(1) ?: continue
                        add(
                            CatalogueFeed(
                                id = c.getLong(0),
                                url = url,
                                urlKey = c.text(2) ?: Hosts.key(url),
                                siteUrl = c.text(3),
                                host = c.text(4)?.lowercase() ?: Hosts.registrable(c.text(3) ?: url),
                                title = c.text(5)?.takeIf { it.isNotBlank() } ?: Hosts.registrable(url) ?: url,
                                description = c.text(6),
                                iconUrl = c.text(7),
                                language = c.text(8),
                                tier = c.text(9)?.let { name -> CadenceTier.entries.firstOrNull { it.name == name } },
                                medianIntervalHours = c.real(10),
                                postsPerWeek = c.real(11),
                                fullTextShare = c.real(12),
                                lastPostAt = if (c.isNull(13)) null else Instant.ofEpochMilli(c.getLong(13)),
                                clusterId = if (c.isNull(14)) null else c.getLong(14).toInt(),
                                categories = c.text(15)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                                source = c.text(16),
                                quality = c.real(17) ?: 0.5,
                                centroid = if (c.isNull(18)) null else vectorOf(c.getBlob(18), c.real(19) ?: 1.0),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Catalogue feeds by URL key. */
    val byKey: Map<String, CatalogueFeed> by lazy { feeds.associateBy { it.urlKey } }

    /** The catalogue's newest headlines of one feed, newest first. */
    fun headlines(feedId: Long, limit: Int = 3): List<CatalogueHeadline> = synchronized(db) {
        db.prepare("SELECT title, url, published_at, word_count FROM headline WHERE feed_id = ? ORDER BY position LIMIT ?").use { c ->
            c.bindLong(1, feedId)
            c.bindLong(2, limit.toLong())
            buildList {
                while (c.step()) {
                    add(
                        CatalogueHeadline(
                            title = c.text(0) ?: continue,
                            url = c.text(1),
                            publishedAt = if (c.isNull(2)) null else Instant.ofEpochMilli(c.getLong(2)),
                            wordCount = if (c.isNull(3)) null else c.getLong(3).toInt(),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        fun SQLiteStatement.text(i: Int): String? = if (isNull(i)) null else getText(i)
        fun SQLiteStatement.real(i: Int): Double? = if (isNull(i)) null else getDouble(i)

        /** `int8 × scale`, normalised; null when the blob is not a 256-byte vector or is all zeros. */
        fun vectorOf(blob: ByteArray?, scale: Double): FloatArray? {
            if (blob == null || blob.size != StaticEmbedder.DIMENSIONS) return null
            val factor = scale.toFloat().takeIf { it != 0f && it.isFinite() } ?: 1f
            val vector = FloatArray(blob.size) { blob[it] * factor }
            return if (Discovery.normalise(vector)) vector else null
        }
    }
}

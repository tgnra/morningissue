package net.tgnra.morningissue.data.discovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
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

/** Where the bundled catalogue comes from: the APK's assets on a device, a file in tests. */
fun interface CatalogueSource {
    /** A fresh stream of the catalogue file, or null when there is none. */
    fun open(): InputStream?

    companion object {
        const val ASSET_PATH = "discovery/catalogue.db"

        /** The catalogue in the APK, or null when this build was made without one. */
        fun assets(context: Context): CatalogueSource {
            val assets = context.applicationContext.assets
            return CatalogueSource {
                try {
                    assets.open(ASSET_PATH)
                } catch (_: FileNotFoundException) {
                    null
                } catch (_: IOException) {
                    null
                }
            }
        }
    }
}

/** What [CatalogueStore.load] found. */
sealed interface CatalogueStatus {
    data class Ready(val catalogue: Catalogue) : CatalogueStatus

    /** This build carries no catalogue. Discovery shows "No suggestions available". */
    data object Missing : CatalogueStatus

    /** The file is there but this app cannot use it; [reason] is for logs and tests. */
    data class Refused(val reason: String) : CatalogueStatus
}

/**
 * The bundled feed catalogue, built offline by `tools/discovery/` and shipped in the APK as
 * `assets/discovery/catalogue.db`. Nothing about it is downloaded or built on the device.
 *
 * **Install.** SQLite cannot open a file inside the APK, so the asset is copied into the app's
 * no-backup files on first use. It is copied again only when the asset could have changed (a
 * new app version, by [versionStamp]) and its `built_at` or `format_version` then differ from
 * the installed copy's.
 *
 * **Opening.** Read-only, with the platform's SQLite API rather than Room: the catalogue is not
 * part of the app's schema, so a new catalogue never needs a migration. A file whose format or
 * embedding model this app does not know is refused ([CatalogueStatus.Refused]); vectors from
 * another model cannot be compared with the reader's.
 *
 * The format (`PRAGMA user_version = 1`):
 *
 * ```
 * meta(key, value)        format_version '1', built_at (epoch ms), model_id, dims '256',
 *                         mean (256 little-endian float32)
 * cluster(id, label, centroid, scale)        centroid: 256 int8, value = int8 × scale
 * feed(id, url, url_key, site_url, host, title, description, icon_url, language, tier,
 *      median_interval_hours, posts_per_week, full_text_share, last_post_at, cluster_id,
 *      categories ('\n'-separated), source, quality, centroid, scale)
 * headline(feed_id, position (0 newest), title, url, published_at, word_count)
 * ```
 */
class CatalogueStore(
    private val source: CatalogueSource,
    private val directory: File,
    /** Changes whenever the asset could have: the app's version and install time. */
    private val versionStamp: () -> String,
    private val modelId: Int = StaticEmbedder.MODEL_ID,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

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
            val stream = source.open() ?: return if (installed.exists()) open(installed) else CatalogueStatus.Missing
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

    /** `format_version` and `built_at` of a catalogue file, or null when it cannot be read. */
    private fun stampOf(file: File): String? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val meta = readTextMeta(db)
            "${meta["format_version"]}/${meta["built_at"]}"
        }
    } catch (_: SQLiteException) {
        null
    }

    private fun open(file: File): CatalogueStatus {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            return CatalogueStatus.Refused("cannot open: ${e.message}")
        }
        return try {
            val version = db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (version != USER_VERSION) return refuse(db, "user_version $version")
            val meta = readTextMeta(db)
            if (meta["format_version"] != FORMAT_VERSION) return refuse(db, "format_version ${meta["format_version"]}")
            if (meta["model_id"] != modelId.toString()) return refuse(db, "model_id ${meta["model_id"]}, the app's embedder is $modelId")
            val dims = meta["dims"]?.toIntOrNull()
            if (dims != StaticEmbedder.DIMENSIONS) return refuse(db, "dims $dims")
            val mean = db.rawQuery("SELECT value FROM meta WHERE key = 'mean'", null).use { if (it.moveToFirst()) it.getBlob(0) else null }
            if (mean == null || mean.size != dims * 4) return refuse(db, "no mean vector")
            val meanVector = FloatArray(dims)
            ByteBuffer.wrap(mean).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(meanVector)
            CatalogueStatus.Ready(
                Catalogue(
                    db = db,
                    builtAt = meta["built_at"]?.toLongOrNull()?.let(Instant::ofEpochMilli),
                    mean = meanVector,
                ),
            )
        } catch (e: SQLiteException) {
            refuse(db, "unreadable: ${e.message}")
        }
    }

    private fun refuse(db: SQLiteDatabase, reason: String): CatalogueStatus {
        db.close()
        return CatalogueStatus.Refused(reason)
    }

    private fun readTextMeta(db: SQLiteDatabase): Map<String, String?> =
        db.rawQuery("SELECT key, CAST(value AS TEXT) FROM meta WHERE key != 'mean'", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1)) }
        }

    companion object {
        const val FILE_NAME = "catalogue.db"
        private const val STAMP_NAME = "catalogue.stamp"
        const val USER_VERSION = 1
        const val FORMAT_VERSION = "1"

        /** The store for this app: the bundled asset, installed under no-backup files. */
        fun of(context: Context): CatalogueStore {
            val app = context.applicationContext
            return CatalogueStore(
                source = CatalogueSource.assets(app),
                directory = File(app.noBackupFilesDir, "discovery"),
                versionStamp = {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val info = app.packageManager.getPackageInfo(app.packageName, 0)
                        @Suppress("DEPRECATION")
                        "${info.versionCode}/${info.lastUpdateTime}"
                    }.getOrDefault("unknown")
                },
            )
        }
    }
}

/**
 * An open, read-only catalogue. Feeds and clusters are read once and kept (a few thousand
 * rows, about 1.5 MB); headlines are read per card.
 */
class Catalogue internal constructor(
    private val db: SQLiteDatabase,
    /** When the pipeline built it; part of the deck's identity. */
    val builtAt: Instant?,
    /** The mean of every feed centroid, subtracted before similarities are taken. */
    val mean: FloatArray,
) {
    val clusters: List<CatalogueCluster> by lazy {
        synchronized(db) {
            db.rawQuery("SELECT id, label, centroid, scale FROM cluster ORDER BY id", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val vector = vectorOf(c.getBlob(2), c.getDouble(3)) ?: continue
                        add(CatalogueCluster(c.getInt(0), c.getString(1) ?: "", vector))
                    }
                }
            }
        }
    }

    val feeds: List<CatalogueFeed> by lazy {
        synchronized(db) {
            db.rawQuery(
                "SELECT id, url, url_key, site_url, host, title, description, icon_url, language, tier, " +
                    "median_interval_hours, posts_per_week, full_text_share, last_post_at, cluster_id, categories, " +
                    "source, quality, centroid, scale FROM feed ORDER BY id",
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        fun text(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
                        fun real(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
                        val url = text(1) ?: continue
                        add(
                            CatalogueFeed(
                                id = c.getLong(0),
                                url = url,
                                urlKey = text(2) ?: Hosts.key(url),
                                siteUrl = text(3),
                                host = text(4)?.lowercase() ?: Hosts.registrable(text(3) ?: url),
                                title = text(5)?.takeIf { it.isNotBlank() } ?: Hosts.registrable(url) ?: url,
                                description = text(6),
                                iconUrl = text(7),
                                language = text(8),
                                tier = text(9)?.let { name -> CadenceTier.entries.firstOrNull { it.name == name } },
                                medianIntervalHours = real(10),
                                postsPerWeek = real(11),
                                fullTextShare = real(12),
                                lastPostAt = if (c.isNull(13)) null else Instant.ofEpochMilli(c.getLong(13)),
                                clusterId = if (c.isNull(14)) null else c.getInt(14),
                                categories = text(15)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                                source = text(16),
                                quality = real(17) ?: 0.5,
                                centroid = if (c.isNull(18)) null else vectorOf(c.getBlob(18), real(19) ?: 1.0),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** The catalogue's newest headlines of one feed, newest first. */
    fun headlines(feedId: Long, limit: Int = 3): List<CatalogueHeadline> = synchronized(db) {
        db.rawQuery(
            "SELECT title, url, published_at, word_count FROM headline WHERE feed_id = ? ORDER BY position LIMIT ?",
            arrayOf(feedId.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CatalogueHeadline(
                            title = c.getString(0) ?: continue,
                            url = if (c.isNull(1)) null else c.getString(1),
                            publishedAt = if (c.isNull(2)) null else Instant.ofEpochMilli(c.getLong(2)),
                            wordCount = if (c.isNull(3)) null else c.getInt(3),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        /** `int8 × scale`, normalised; null when the blob is not a 256-byte vector or is all zeros. */
        fun vectorOf(blob: ByteArray?, scale: Double): FloatArray? {
            if (blob == null || blob.size != StaticEmbedder.DIMENSIONS) return null
            val factor = scale.toFloat().takeIf { it != 0f && it.isFinite() } ?: 1f
            val vector = FloatArray(blob.size) { blob[it] * factor }
            return if (Discovery.normalise(vector)) vector else null
        }
    }
}

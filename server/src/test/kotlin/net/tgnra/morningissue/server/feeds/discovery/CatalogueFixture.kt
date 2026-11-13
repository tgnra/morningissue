package net.tgnra.morningissue.server.feeds.discovery

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.model.FeedUrls

/**
 * Builds small catalogue files in the bundled format, for tests; the app's `CatalogueFixture`
 * written through the bundled SQLite driver instead of Android's `SQLiteDatabase`.
 */
object CatalogueFixture {
    class Cluster(val id: Int, val label: String, val centroid: FloatArray)

    class Headline(val title: String, val url: String?, val publishedAt: Long?, val wordCount: Int?)

    class Feed(
        val id: Long,
        val url: String,
        val title: String,
        val centroid: FloatArray?,
        val clusterId: Int? = null,
        val host: String? = null,
        val siteUrl: String? = null,
        val language: String? = "en",
        val tier: String? = "WEEKLY",
        val categories: List<String> = emptyList(),
        val source: String? = "kagi",
        val quality: Double = 0.8,
        val description: String? = null,
        val postsPerWeek: Double? = 2.0,
        val lastPostAt: Long? = null,
        val headlines: List<Headline> = emptyList(),
    )

    /** A unit vector pointing mostly along [axis], with a little of [axis] + 1. */
    fun axis(axis: Int, tilt: Float = 0.1f): FloatArray = FloatArray(StaticEmbedder.DIMENSIONS).also {
        it[axis % it.size] = 1f
        it[(axis + 1) % it.size] = tilt
        Discovery.normalise(it)
    }

    fun write(
        file: File,
        feeds: List<Feed>,
        clusters: List<Cluster> = emptyList(),
        builtAt: Long = 1_762_000_000_000L,
        modelId: String = StaticEmbedder.MODEL_ID.toString(),
        formatVersion: String = "1",
        userVersion: Int = 1,
        dims: String = StaticEmbedder.DIMENSIONS.toString(),
        mean: FloatArray? = FloatArray(StaticEmbedder.DIMENSIONS),
    ): File {
        file.parentFile?.mkdirs()
        file.delete()
        val db = BundledSQLiteDriver().open(file.path)
        try {
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value)")
            db.execSQL("CREATE TABLE cluster (id INTEGER PRIMARY KEY, label TEXT, centroid BLOB, scale REAL)")
            db.execSQL(
                "CREATE TABLE feed (id INTEGER PRIMARY KEY, url TEXT, url_key TEXT, site_url TEXT, host TEXT, title TEXT, " +
                    "description TEXT, icon_url TEXT, language TEXT, tier TEXT, median_interval_hours REAL, posts_per_week REAL, " +
                    "full_text_share REAL, last_post_at INTEGER, cluster_id INTEGER, categories TEXT, source TEXT, quality REAL, " +
                    "centroid BLOB, scale REAL)",
            )
            db.execSQL("CREATE TABLE headline (feed_id INTEGER, position INTEGER, title TEXT, url TEXT, published_at INTEGER, word_count INTEGER)")
            db.execSQL("BEGIN")
            fun meta(key: String, value: Any?) = db.insert("meta", mapOf("key" to key, "value" to value))
            meta("format_version", formatVersion)
            meta("built_at", builtAt.toString())
            meta("model_id", modelId)
            meta("dims", dims)
            if (mean != null) {
                val bytes = ByteBuffer.allocate(mean.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                mean.forEach { bytes.putFloat(it) }
                meta("mean", bytes.array())
            }
            for (cluster in clusters) {
                val (blob, scale) = quantise(cluster.centroid)
                db.insert("cluster", mapOf("id" to cluster.id, "label" to cluster.label, "centroid" to blob, "scale" to scale))
            }
            for (feed in feeds) {
                val quantised = feed.centroid?.let(::quantise)
                db.insert(
                    "feed",
                    mapOf(
                        "id" to feed.id,
                        "url" to feed.url,
                        "url_key" to FeedUrls.normalize(feed.url),
                        "site_url" to feed.siteUrl,
                        "host" to feed.host,
                        "title" to feed.title,
                        "description" to feed.description,
                        "language" to feed.language,
                        "tier" to feed.tier,
                        "posts_per_week" to feed.postsPerWeek,
                        "full_text_share" to 1.0,
                        "last_post_at" to feed.lastPostAt,
                        "cluster_id" to feed.clusterId,
                        "categories" to feed.categories.joinToString("\n"),
                        "source" to feed.source,
                        "quality" to feed.quality,
                        "centroid" to quantised?.first,
                        "scale" to quantised?.second,
                    ),
                )
                feed.headlines.forEachIndexed { position, headline ->
                    db.insert(
                        "headline",
                        mapOf(
                            "feed_id" to feed.id,
                            "position" to position,
                            "title" to headline.title,
                            "url" to headline.url,
                            "published_at" to headline.publishedAt,
                            "word_count" to headline.wordCount,
                        ),
                    )
                }
            }
            db.execSQL("COMMIT")
            db.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            db.close()
        }
        return file
    }

    private fun SQLiteConnection.insert(table: String, values: Map<String, Any?>) {
        val columns = values.keys.joinToString(", ")
        val marks = values.keys.joinToString(", ") { "?" }
        prepare("INSERT INTO $table ($columns) VALUES ($marks)").use { statement ->
            values.values.forEachIndexed { index, value ->
                val at = index + 1
                when (value) {
                    null -> statement.bindNull(at)
                    is ByteArray -> statement.bindBlob(at, value)
                    is String -> statement.bindText(at, value)
                    is Int -> statement.bindLong(at, value.toLong())
                    is Long -> statement.bindLong(at, value)
                    is Double -> statement.bindDouble(at, value)
                    else -> error("cannot bind ${value::class}")
                }
            }
            statement.step()
        }
    }

    /** int8 with one scale per vector, as the pipeline stores them. */
    private fun quantise(vector: FloatArray): Pair<ByteArray, Double> {
        val max = vector.maxOf { abs(it) }.takeIf { it > 0f } ?: 1f
        val scale = max / 127.0
        return ByteArray(vector.size) { (vector[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
    }
}

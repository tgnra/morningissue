package net.tgnra.morningissue.data.discovery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import net.tgnra.morningissue.embedding.StaticEmbedder

/**
 * Builds small catalogue files in the bundled format, for tests. The real catalogue and its
 * fixture come from the pipeline in `tools/discovery/`; this writes the same tables by hand.
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
        net.tgnra.morningissue.engine.Discovery.normalise(it)
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
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.use {
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value)")
            db.execSQL("CREATE TABLE cluster (id INTEGER PRIMARY KEY, label TEXT, centroid BLOB, scale REAL)")
            db.execSQL(
                "CREATE TABLE feed (id INTEGER PRIMARY KEY, url TEXT, url_key TEXT, site_url TEXT, host TEXT, title TEXT, " +
                    "description TEXT, icon_url TEXT, language TEXT, tier TEXT, median_interval_hours REAL, posts_per_week REAL, " +
                    "full_text_share REAL, last_post_at INTEGER, cluster_id INTEGER, categories TEXT, source TEXT, quality REAL, " +
                    "centroid BLOB, scale REAL)",
            )
            db.execSQL("CREATE TABLE headline (feed_id INTEGER, position INTEGER, title TEXT, url TEXT, published_at INTEGER, word_count INTEGER)")
            fun meta(key: String, value: Any?) = db.insert(
                "meta",
                null,
                ContentValues().apply {
                    put("key", key)
                    when (value) {
                        is ByteArray -> put("value", value)
                        is String -> put("value", value)
                        null -> putNull("value")
                    }
                },
            )
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
                db.insert(
                    "cluster",
                    null,
                    ContentValues().apply {
                        put("id", cluster.id)
                        put("label", cluster.label)
                        put("centroid", blob)
                        put("scale", scale)
                    },
                )
            }
            for (feed in feeds) {
                val values = ContentValues().apply {
                    put("id", feed.id)
                    put("url", feed.url)
                    put("url_key", net.tgnra.morningissue.model.FeedUrls.normalize(feed.url))
                    put("site_url", feed.siteUrl)
                    put("host", feed.host)
                    put("title", feed.title)
                    put("description", feed.description)
                    put("language", feed.language)
                    put("tier", feed.tier)
                    put("posts_per_week", feed.postsPerWeek)
                    put("full_text_share", 1.0)
                    feed.lastPostAt?.let { put("last_post_at", it) }
                    feed.clusterId?.let { put("cluster_id", it) }
                    put("categories", feed.categories.joinToString("\n"))
                    put("source", feed.source)
                    put("quality", feed.quality)
                    feed.centroid?.let {
                        val (blob, scale) = quantise(it)
                        put("centroid", blob)
                        put("scale", scale)
                    }
                }
                db.insert("feed", null, values)
                feed.headlines.forEachIndexed { position, headline ->
                    db.insert(
                        "headline",
                        null,
                        ContentValues().apply {
                            put("feed_id", feed.id)
                            put("position", position)
                            put("title", headline.title)
                            put("url", headline.url)
                            headline.publishedAt?.let { put("published_at", it) }
                            headline.wordCount?.let { put("word_count", it) }
                        },
                    )
                }
            }
            db.execSQL("PRAGMA user_version = $userVersion")
        }
        return file
    }

    /** int8 with one scale per vector, as the pipeline stores them. */
    private fun quantise(vector: FloatArray): Pair<ByteArray, Double> {
        val max = vector.maxOf { abs(it) }.takeIf { it > 0f } ?: 1f
        val scale = max / 127.0
        return ByteArray(vector.size) { (vector[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
    }
}

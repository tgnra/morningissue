package net.tgnra.morningissue.data.discovery

import net.tgnra.morningissue.data.db.DiscoveryDao
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.engine.DiscoveryConfig
import net.tgnra.morningissue.model.FeedUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A subscribed feed's direction in the embedder's space, from its stored post vectors. */
internal object FeedCentroids {
    /**
     * The normalised mean of the feed's newest `feedVectors` post vectors, or null when fewer
     * than `minFeedVectors` have been embedded. Derived every time, never stored.
     */
    suspend fun of(dao: DiscoveryDao, feedId: Long, config: DiscoveryConfig = DiscoveryConfig.Default): FloatArray? {
        val vectors = dao.feedVectors(feedId, StaticEmbedder.MODEL_ID, config.feedVectors)
        if (vectors.size < config.minFeedVectors) return null
        return Discovery.centroid(vectors.mapNotNull { bytes -> bytes.takeIf { it.size == StaticEmbedder.DIMENSIONS }?.let(StaticEmbedder::fromBytes) })
    }
}

/** The registrable part of an address's host, as the catalogue's `host` column has it. */
internal object Hosts {
    /**
     * "blog.example.co.uk" gives "example.co.uk"; a host on a shared platform keeps its own
     * name ("someone.bearblog.dev"), because the public suffix list names the platform.
     * Null when [url] has no host. The full host when the public suffix list cannot be loaded.
     */
    fun registrable(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val parsed = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
        // The suffix list is a resource OkHttp loads on first use; without it the full host is
        // used, which hides less rather than more.
        val domain = runCatching { parsed.topPrivateDomain() }.getOrNull()
        return (domain ?: parsed.host).lowercase().removePrefix("www.")
    }

    /** The URL key used for verdicts and matching, the same as `FeedUrls.normalize`. */
    fun key(url: String): String = FeedUrls.normalize(url)
}

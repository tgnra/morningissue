package net.tgnra.morningissue.sync

import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.embedding.EmbeddableText
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.Discovery

/** A suggested feed as fetched live: fresh headlines, and its centroid recomputed on the device. */
class LiveCandidate(
    val url: String,
    val parsed: ParsedFeed,
    val fetchedAt: Instant,
    /** The normalised mean of its newest posts' vectors; null when none could be embedded. */
    val centroid: FloatArray?,
)

sealed interface CandidateResult {
    data class Fetched(val candidate: LiveCandidate) : CandidateResult

    /** On a metered connection nothing is fetched unless the reader asked ([CandidateFetcher.fetch] with `force`). */
    data object SkippedMetered : CandidateResult

    /** The site answered with an error or with something that is not a feed: the card is dropped. */
    data class Dead(val message: String) : CandidateResult

    /** No answer (offline, timeout): the card keeps the catalogue's headlines. */
    data class Unreachable(val message: String) : CandidateResult
}

/**
 * Fetches suggested feeds for discovery's cards: only the card on screen and the next two, never
 * the whole deck. Uses the ordinary [FeedFetcher] with a 1 MB cap (subscriptions get 8 MB), keeps
 * what it fetched in `cacheDir/discovery/` for 24 hours, and on a metered connection fetches
 * nothing unless the reader taps "Load latest". Fetching a feed tells that one site the reader's
 * address, exactly as visiting it would; nothing else leaves the device.
 *
 * A fetched feed's centroid is recomputed with the app's own embedder from its newest posts and
 * replaces the catalogue's, so the card's reason and the verdict it gets reflect what the feed
 * publishes now.
 */
class CandidateFetcher(
    private val fetcher: FeedFetcher,
    private val cacheDir: File,
    private val clock: Clock,
    private val isMetered: () -> Boolean,
    private val embedder: suspend () -> StaticEmbedder?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val memory = HashMap<String, LiveCandidate>()

    /** Whether prefetching is allowed now (an unmetered connection). */
    fun mayPrefetch(): Boolean = !isMetered()

    /** What was fetched for [url] in this session, if anything. */
    @Synchronized
    fun cached(url: String): LiveCandidate? = memory[url]

    /**
     * The feed at [url], from the 24-hour cache when it is there. [force] fetches on a metered
     * connection too: the reader asked for it.
     */
    suspend fun fetch(url: String, force: Boolean = false): CandidateResult {
        cached(url)?.let { return CandidateResult.Fetched(it) }
        val file = cacheFile(url)
        val now = clock.instant()
        val fresh = file.exists() && Duration.between(Instant.ofEpochMilli(file.lastModified()), now) < CACHE_TTL
        val body: ByteArray
        val finalUrl: String
        if (fresh) {
            body = withContext(dispatcher) { file.readBytes() }
            finalUrl = url
        } else {
            if (!force && isMetered()) return CandidateResult.SkippedMetered
            when (val result = fetcher.fetch(url, maxBytes = MAX_BYTES)) {
                is FetchResult.Success -> {
                    body = result.body
                    finalUrl = result.finalUrl
                    withContext(dispatcher) {
                        runCatching {
                            file.parentFile?.mkdirs()
                            file.writeBytes(body)
                        }
                    }
                }
                is FetchResult.HttpError -> return CandidateResult.Dead("HTTP ${result.status}")
                is FetchResult.NetworkError -> return CandidateResult.Unreachable(result.message)
                is FetchResult.NotModified -> return CandidateResult.Unreachable("not modified")
            }
        }
        val parsed = try {
            withContext(Dispatchers.Default) { FeedParser.parse(XmlText.decode(body, null), finalUrl, now) }
        } catch (e: FeedParseException) {
            file.delete()
            return CandidateResult.Dead(e.message ?: "not a feed")
        }
        val candidate = LiveCandidate(url, parsed, now, centroidOf(parsed))
        synchronized(this) { memory[url] = candidate }
        return CandidateResult.Fetched(candidate)
    }

    /** The centroid of the newest [CENTROID_POSTS] posts, as the pipeline computes it. */
    private suspend fun centroidOf(parsed: ParsedFeed): FloatArray? {
        val model = runCatching { embedder() }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            val vectors = parsed.items.sortedByDescending { it.publishedAt }.take(CENTROID_POSTS).mapNotNull { item ->
                val content = item.content
                val text = EmbeddableText.of(item.title, content.summary, content.plainText)
                if (EmbeddableText.isMostlyNonLatin(text)) null else model.embed(text)
            }
            Discovery.centroid(vectors)
        }
    }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "discovery"), "$digest.feed")
    }

    companion object {
        /** Discovery fetches feeds it may never show again; a megabyte is plenty for a feed. */
        const val MAX_BYTES = 1L * 1024 * 1024
        val CACHE_TTL: Duration = Duration.ofHours(24)
        const val CENTROID_POSTS = 20

        /** The card on screen and the next two. */
        const val PREFETCH_AHEAD = 2
    }
}

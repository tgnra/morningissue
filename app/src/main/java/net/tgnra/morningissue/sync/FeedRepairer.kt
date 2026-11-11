package net.tgnra.morningissue.sync

import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.model.FeedUrls
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface RepairResult {
    /** The feed's own address answers with a feed again. */
    data object Recovered : RepairResult

    /** The feed was found at [url]. */
    data class Relocated(val url: String) : RepairResult

    /** Nothing that was tried is a feed. */
    data object Failed : RepairResult
}

/**
 * Looks for a failing feed before the reader is asked to. In order: the address itself once
 * more; the same address over the other scheme and with or without `www.`; the feed the site
 * itself declares (through [FeedDiscovery]); then the usual feed paths on the feed's host.
 * Every address offered has been fetched and parsed as a feed. Stores nothing.
 */
class FeedRepairer(
    private val fetcher: FeedFetcher,
    private val discovery: FeedDiscovery,
    private val clock: Clock,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * @param taken normalised addresses (`FeedUrls.normalize`) of the reader's other feeds; a
     *   feed is never repaired onto an address another subscription already has.
     */
    suspend fun repair(url: String, siteUrl: String?, taken: Set<String> = emptySet()): RepairResult {
        if (workingAddress(url) != null) return RepairResult.Recovered
        val parsed = url.trim().toHttpUrlOrNull()
        fun free(candidate: String) = FeedUrls.normalize(candidate) !in taken

        // The same document over the other scheme, with or without "www.".
        if (parsed != null) {
            firstWorking(originVariants(parsed).drop(1).map { it.toString() }.filter(::free))?.takeIf(::free)?.let { return RepairResult.Relocated(it) }
        }

        // The feed the site declares for itself.
        val site = siteUrl?.takeIf { it.toHttpUrlOrNull() != null } ?: parsed?.resolve("/")?.toString()
        if (site != null) {
            val found = discovery.discover(site) as? DiscoveryResult.Found
            found?.feeds?.firstOrNull { free(it.url) }?.let { feed ->
                return if (feed.url == url) RepairResult.Recovered else RepairResult.Relocated(feed.url)
            }
        }

        // The usual places, on the feed's own host.
        if (parsed != null) {
            val candidates = originVariants(parsed).flatMap { origin -> COMMON_PATHS.mapNotNull { origin.resolve(it)?.toString() } }
                .filter { it != url && free(it) }
            firstWorking(candidates)?.takeIf(::free)?.let { return RepairResult.Relocated(it) }
        }
        return RepairResult.Failed
    }

    /** Tries [candidates] together and returns the first, in their order, that is a feed. */
    private suspend fun firstWorking(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val results = coroutineScope { candidates.map { async { workingAddress(it) } }.awaitAll() }
        return results.firstOrNull { it != null }
    }

    /** The address to use for [url] if it answers with a feed (its permanent redirect target, if any). */
    private suspend fun workingAddress(url: String): String? {
        val result = fetcher.fetch(url) as? FetchResult.Success ?: return null
        val isFeed = withContext(parseDispatcher) {
            val text = result.text()
            FeedParser.sniff(text) != null && try {
                FeedParser.parse(text, result.finalUrl, clock.instant())
                true
            } catch (e: FeedParseException) {
                false
            }
        }
        return if (isFeed) result.permanentUrl ?: url else null
    }

    companion object {
        private val COMMON_PATHS = listOf("/feed", "/rss", "/atom.xml", "/index.xml")

        /** [url] and the same address over the other scheme, with and without `www.`. */
        internal fun variants(url: String): List<String> =
            url.toHttpUrlOrNull()?.let { originVariants(it).map(HttpUrl::toString) }.orEmpty()

        /** [url] first, then its scheme and `www.` variants. */
        private fun originVariants(url: HttpUrl): List<HttpUrl> {
            val otherScheme = if (url.isHttps) "http" else "https"
            fun HttpUrl.withScheme(scheme: String): HttpUrl? = runCatching {
                // A default port follows the scheme; an explicit one is kept.
                newBuilder().scheme(scheme).apply { if (url.port != HttpUrl.defaultPort(url.scheme)) port(url.port) }.build()
            }.getOrNull()
            val host = url.host
            val otherHost = when {
                host.startsWith("www.") -> host.removePrefix("www.")
                // An address or a single-label name has no "www." form.
                host.none { it.isLetter() } || '.' !in host || ':' in host -> null
                else -> "www.$host"
            }
            val withOtherHost = otherHost?.let { runCatching { url.newBuilder().host(it).build() }.getOrNull() }
            return listOfNotNull(url, url.withScheme(otherScheme), withOtherHost, withOtherHost?.withScheme(otherScheme)).distinct()
        }
    }
}

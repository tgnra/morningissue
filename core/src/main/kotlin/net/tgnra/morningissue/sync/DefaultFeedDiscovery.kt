package net.tgnra.morningissue.sync

import java.io.ByteArrayInputStream
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.model.FeedUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * The real [FeedDiscovery]. In order: the address itself as a feed; the feeds a page declares
 * with `<link rel="alternate">`; then the usual feed paths on the site. Every feed returned
 * has been fetched and parsed, so its title and site link come from the feed itself.
 */
class DefaultFeedDiscovery(
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FeedDiscovery {

    override suspend fun discover(input: String): DiscoveryResult {
        val candidates = candidateUrls(input)
        if (candidates.isEmpty()) return DiscoveryResult.Failed("That is not a web address")

        var failure = "Could not connect"
        for (url in candidates) {
            when (val result = fetcher.fetch(url, accept = ACCEPT_ANY, maxBytes = FeedFetcher.MAX_FEED_BYTES)) {
                is FetchResult.NetworkError -> failure = result.message
                is FetchResult.HttpError -> {
                    // The page is missing or forbidden, but the site may still have a feed.
                    val probed = probeCommonPaths(url, null)
                    return if (probed.isNotEmpty()) DiscoveryResult.Found(probed) else DiscoveryResult.Failed("The site answered with an error (HTTP ${result.status})")
                }
                is FetchResult.NotModified -> return DiscoveryResult.NoFeed
                is FetchResult.Success -> {
                    val feeds = fromDocument(url, result)
                    return if (feeds.isNotEmpty()) DiscoveryResult.Found(feeds) else DiscoveryResult.NoFeed
                }
            }
        }
        return DiscoveryResult.Failed(failure)
    }

    /** What the user typed as fetchable addresses: https first when no scheme was given. */
    private fun candidateUrls(input: String): List<String> {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return emptyList()
        // "feed:" is an old way of marking a feed link.
        if (text.startsWith("feed://", ignoreCase = true)) text = "http://" + text.substring(7)
        if (text.startsWith("feed:", ignoreCase = true)) text = text.substring(5)
        val candidates = if (SCHEME.containsMatchIn(text)) listOf(text) else listOf("https://$text", "http://$text")
        return candidates.filter { it.toHttpUrlOrNull() != null }
    }

    private suspend fun fromDocument(requestedUrl: String, page: FetchResult.Success): List<DiscoveredFeed> {
        asFeed(requestedUrl, page, null)?.let { return listOf(it) }

        val document = withContext(parseDispatcher) {
            runCatching { Jsoup.parse(ByteArrayInputStream(page.body), page.charset?.name(), page.finalUrl) }.getOrNull()
        } ?: return emptyList()
        val hints = PageHints(
            title = document.title().trim().ifEmpty { null },
            siteUrl = page.finalUrl,
            iconUrl = pageIcon(document),
        )

        val declared = declaredFeeds(document)
        val found = coroutineScope {
            declared.map { link -> async { fetchFeed(link.url, hints.copy(title = link.title ?: hints.title)) } }.awaitAll()
        }.filterNotNull().distinctBy { FeedUrls.normalize(it.url) }
        if (found.isNotEmpty()) {
            // Comment feeds are rarely what was meant; keep them, but last.
            return found.sortedBy { feed -> COMMENTS.containsMatchIn(feed.url) || feed.title?.let(COMMENTS::containsMatchIn) == true }
        }
        return probeCommonPaths(page.finalUrl, hints)
    }

    private class FeedLink(val url: String, val title: String?)

    private data class PageHints(val title: String?, val siteUrl: String?, val iconUrl: String?)

    private fun declaredFeeds(document: Document): List<FeedLink> = document.select("link[href]")
        .filter { link ->
            val rel = link.attr("rel").lowercase().split(' ')
            val type = link.attr("type").trim().lowercase()
            ("alternate" in rel && type in FEED_TYPES) || "feed" in rel
        }
        .mapNotNull { link ->
            link.absUrl("href").takeIf { it.toHttpUrlOrNull() != null }?.let { FeedLink(it, link.attr("title").trim().ifEmpty { null }) }
        }
        .distinctBy { it.url }
        .take(MAX_DECLARED)

    private fun pageIcon(document: Document): String? {
        val icons = document.select("link[href]").filter { "icon" in it.attr("rel").lowercase() }
        // Touch icons are the large ones.
        val best = icons.firstOrNull { "apple-touch-icon" in it.attr("rel").lowercase() } ?: icons.firstOrNull()
        return best?.absUrl("href")?.takeIf { it.toHttpUrlOrNull() != null }
    }

    /** `/feed`, `/rss` and friends on the site's root; the first that answers with a feed. */
    private suspend fun probeCommonPaths(pageUrl: String, hints: PageHints?): List<DiscoveredFeed> {
        val origin = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val found = coroutineScope {
            COMMON_PATHS.map { path -> async { origin.resolve(path)?.let { fetchFeed(it.toString(), hints) } } }.awaitAll()
        }
        return listOfNotNull(found.firstOrNull { it != null })
    }

    private suspend fun fetchFeed(url: String, hints: PageHints?): DiscoveredFeed? {
        val result = fetcher.fetch(url) as? FetchResult.Success ?: return null
        return asFeed(url, result, hints)
    }

    /** [result] as a subscribable feed, or null when it is not one. */
    private suspend fun asFeed(requestedUrl: String, result: FetchResult.Success, hints: PageHints?): DiscoveredFeed? {
        val feed = withContext(parseDispatcher) {
            val text = result.text()
            if (FeedParser.sniff(text) == null) return@withContext null
            try {
                FeedParser.parse(text, result.finalUrl, clock.instant())
            } catch (e: FeedParseException) {
                null
            }
        } ?: return null
        return DiscoveredFeed(
            // Subscribe to the address given unless the server says it has moved for good.
            url = result.permanentUrl ?: requestedUrl,
            title = feed.title ?: hints?.title,
            siteUrl = feed.siteUrl ?: hints?.siteUrl,
            iconUrl = hints?.iconUrl ?: feed.iconUrl,
        )
    }

    private companion object {
        const val ACCEPT_ANY = "text/html, application/rss+xml, application/atom+xml, application/feed+json, " +
            "application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"
        const val MAX_DECLARED = 8
        val COMMON_PATHS = listOf("/feed", "/rss", "/atom.xml", "/index.xml", "/feed.xml", "/rss.xml")
        val FEED_TYPES = setOf(
            "application/rss+xml", "application/atom+xml", "application/feed+json", "application/json",
            "application/rdf+xml", "application/xml", "text/xml",
        )
        val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
        val COMMENTS = Regex("comment", RegexOption.IGNORE_CASE)
    }
}

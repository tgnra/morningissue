package net.tgnra.morningissue.model

import java.net.URI

/** URL helpers shared by add-feed, OPML import and sync. */
object FeedUrls {
    /**
     * A comparison key for "is this the same feed?": scheme and a leading `www.` are ignored,
     * the host is lower-cased, default ports, fragments and a trailing slash are dropped.
     * The result is only for matching, never for fetching.
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed") }.getOrNull()
        val host = uri?.host ?: return trimmed.lowercase().trimEnd('/')
        val port = uri.port.takeIf { it != -1 && it != 80 && it != 443 }?.let { ":$it" }.orEmpty()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return host.lowercase().removePrefix("www.") + port + path + query
    }

    /** The host of [url] without `www.`, or [url] itself when it cannot be parsed. */
    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: url
}

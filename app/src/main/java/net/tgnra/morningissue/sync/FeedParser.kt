package net.tgnra.morningissue.sync

import java.time.Instant

/** A post as read from the document, before identity, dates and links are settled. */
internal class RawItem {
    var guid: String? = null
    var link: String? = null
    var title: String? = null
    var summaryHtml: String? = null
    var contentHtml: String? = null
    var author: String? = null
    var published: String? = null
    var updated: String? = null

    /** Lead image candidates in falling priority. */
    var mediaImage: String? = null
    var thumbnail: String? = null
    var enclosureImage: String? = null

    /** Overrides the feed's base for this post's relative links (Atom `xml:base`). */
    var base: String? = null
}

internal class RawFeed(val format: FeedFormat) {
    var title: String? = null
    var link: String? = null
    var icon: String? = null
    var author: String? = null
    var base: String? = null
    val items = ArrayList<RawItem>()
}

/**
 * Reads RSS 2.0 (and 0.9x / 1.0), Atom and JSON Feed into [ParsedFeed]. The format is decided
 * by looking at the document, never by its Content-Type. Pure: no I/O, no Android.
 */
object FeedParser {
    /** The feed format [text] appears to be in, or null when it is something else (an HTML page, say). */
    fun sniff(text: String): FeedFormat? {
        val head = text.take(SNIFF_CHARS).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (head.startsWith("{")) {
            return FeedFormat.JSON.takeIf { "jsonfeed.org" in head || "\"items\"" in text }
        }
        // The first real element, past the XML declaration, comments, doctype and stylesheet PIs.
        val root = ROOT_ELEMENT.find(head)?.groupValues?.get(1)?.substringAfter(':')?.lowercase()
        return when (root) {
            "rss", "rdf" -> FeedFormat.RSS
            "feed" -> FeedFormat.ATOM
            else -> null
        }
    }

    /**
     * [feedUrl] is where the document was fetched from, used to resolve relative links.
     * [fetchedAt] stands in for missing or unreadable post dates.
     *
     * @throws FeedParseException when [text] is not a feed or is broken before its first post.
     */
    fun parse(text: String, feedUrl: String, fetchedAt: Instant): ParsedFeed {
        val raw = when (sniff(text)) {
            FeedFormat.JSON -> JsonFeedParser.parse(text)
            FeedFormat.RSS, FeedFormat.ATOM -> XmlFeedParser.parse(text)
            null -> throw FeedParseException("Not a feed")
        }
        return finish(raw, feedUrl, fetchedAt)
    }

    private fun finish(raw: RawFeed, feedUrl: String, fetchedAt: Instant): ParsedFeed {
        val feedBase = Texts.resolve(feedUrl, raw.base) ?: feedUrl
        val siteUrl = Texts.resolve(feedBase, raw.link)
        // RSS has no rule for relative addresses; readers resolve them against the channel's
        // site link, which also works when the feed itself is served from another host.
        val linkBase = siteUrl?.takeIf { raw.format == FeedFormat.RSS } ?: feedBase
        val seen = HashSet<String>()
        val items = raw.items.mapNotNull { item -> finishItem(item, raw, linkBase, siteUrl, fetchedAt) }
            .filter { seen.add(it.guid) }
        return ParsedFeed(
            format = raw.format,
            title = raw.title?.let(Texts::squash)?.takeIf { it.isNotEmpty() },
            siteUrl = siteUrl,
            iconUrl = Texts.resolve(linkBase, raw.icon),
            items = items,
        )
    }

    private fun finishItem(item: RawItem, feed: RawFeed, feedBase: String, siteUrl: String?, fetchedAt: Instant): ParsedItem? {
        val base = Texts.resolve(feedBase, item.base) ?: feedBase
        val url = Texts.resolve(base, item.link)
        val summaryHtml = item.summaryHtml?.takeIf { it.isNotBlank() }
        val bodyHtml = item.contentHtml?.takeIf { it.isNotBlank() } ?: summaryHtml

        var title = item.title?.let(Texts::squash).orEmpty()
        if (title.isEmpty()) {
            // Microblog posts have no title: use the opening words.
            val text = (summaryHtml ?: bodyHtml)?.let(HtmlBlocks::plainText).orEmpty()
            title = Texts.excerpt(text, TITLE_FALLBACK_CHARS) ?: if (url != null) UNTITLED else return null
        }

        val dateText = item.published?.takeIf { it.isNotBlank() } ?: item.updated?.takeIf { it.isNotBlank() }
        val date = FeedDates.parse(item.published) ?: FeedDates.parse(item.updated)

        // The fallback hash uses the date as written, never the fetch time, so it stays the
        // same from one fetch to the next.
        val guid = item.guid?.trim()?.takeIf { it.isNotEmpty() }
            ?: url
            ?: ("hash:" + Texts.hash(title + "\n" + dateText.orEmpty().trim()))

        return ParsedItem(
            guid = guid,
            url = url,
            title = title,
            author = (item.author ?: feed.author)?.let(Texts::squash)?.takeIf { it.isNotEmpty() },
            publishedAt = date ?: fetchedAt,
            hasDate = date != null,
            mediaImageUrl = sequenceOf(item.mediaImage, item.thumbnail, item.enclosureImage)
                .firstNotNullOfOrNull { Texts.resolve(base, it) },
            summaryHtml = summaryHtml,
            bodyHtml = bodyHtml,
            // Relative links inside the body are relative to the post's page when it has one.
            baseUrl = url ?: siteUrl ?: base,
        )
    }

    private const val SNIFF_CHARS = 4096
    private const val TITLE_FALLBACK_CHARS = 80
    private const val UNTITLED = "Untitled"
    private val ROOT_ELEMENT = Regex("""<([A-Za-z][A-Za-z0-9:_.-]*)""")
}

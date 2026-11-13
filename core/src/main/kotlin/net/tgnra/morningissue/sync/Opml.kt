package net.tgnra.morningissue.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.tgnra.morningissue.model.FeedUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** One subscription in an OPML document. */
data class OpmlFeed(
    /** The feed document URL (`xmlUrl`). */
    val url: String,
    val title: String? = null,
    /** The site's address (`htmlUrl`). */
    val siteUrl: String? = null,
    /** The folders the feed sits in; on export, one folder is written per tag. */
    val tags: List<String> = emptyList(),
)

data class OpmlDocument(
    val title: String? = null,
    /** Each feed once, in document order, however many folders listed it. */
    val feeds: List<OpmlFeed> = emptyList(),
)

/**
 * A pure OPML reader and writer; no Android, no database.
 *
 * The reader takes what feed readers really export: outlines nested to any depth, feeds
 * without `type="rss"`, attribute names in any case (`xmlurl`, `XMLURL`), a byte-order mark,
 * HTML entities and bare ampersands, `feed://` addresses and addresses without a scheme.
 * Every enclosing folder name becomes a tag, as does each entry of an OPML 2.0 `category`
 * attribute. A feed listed in several folders comes out once with all their tags.
 *
 * The writer produces OPML 2.0: untagged feeds at the top level, then one folder per tag in
 * alphabetical order. A feed with several tags appears in each of its folders.
 */
object Opml {
    /** @throws IOException when [input] is not OPML. */
    fun read(input: InputStream): OpmlDocument = read(XmlText.decode(input.readBytes()))

    /** @throws IOException when [text] is not OPML. */
    fun read(text: String): OpmlDocument {
        val found = LinkedHashMap<String, OpmlFeed>()
        var title: String? = null
        var sawOpml = false
        try {
            val parser = XmlText.newParser(XmlText.sanitize(text), namespaceAware = false)
            val folders = ArrayList<String?>()
            // For each open <outline>: whether it pushed a folder.
            val outlines = ArrayList<Boolean>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.lowercase()
                    when {
                        !sawOpml -> {
                            if (name != "opml") throw IOException("Not an OPML file")
                            sawOpml = true
                        }
                        name == "title" && outlines.isEmpty() && title == null -> {
                            title = parser.readElementText().ifEmpty { null }
                        }
                        name == "outline" -> {
                            val feed = feedOf(parser, folders.filterNotNull())
                            if (feed != null) {
                                found.merge(FeedUrls.normalize(feed.url), feed) { old, new ->
                                    old.copy(
                                        title = old.title ?: new.title,
                                        siteUrl = old.siteUrl ?: new.siteUrl,
                                        tags = mergeTags(old.tags + new.tags),
                                    )
                                }
                                // A feed outline is not a folder for anything nested in it.
                                folders += null
                            } else {
                                folders += (parser.attr("text", true) ?: parser.attr("title", true))?.let(Texts::squash)?.takeIf { it.isNotEmpty() }
                            }
                            outlines += true
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.lowercase() == "outline" && outlines.isNotEmpty()) {
                    outlines.removeAt(outlines.lastIndex)
                    folders.removeAt(folders.lastIndex)
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // A file damaged part-way still gives up the feeds before the damage.
            if (found.isEmpty()) throw IOException("The OPML file is damaged", e)
        } catch (e: RuntimeException) {
            if (found.isEmpty()) throw IOException("The OPML file is damaged", e)
        }
        if (!sawOpml) throw IOException("Not an OPML file")
        return OpmlDocument(title, found.values.toList())
    }

    private fun feedOf(parser: XmlPullParser, folders: List<String>): OpmlFeed? {
        val type = parser.attr("type", true)?.lowercase()
        // Some old exporters put the feed address in `url`.
        val address = parser.attr("xmlUrl", true)
            ?: parser.attr("url", true)?.takeIf { type == "rss" || type == "atom" || type == "feed" }
            ?: return null
        val url = cleanUrl(address) ?: return null
        val title = (parser.attr("title", true) ?: parser.attr("text", true))?.let(Texts::squash)
            ?.takeIf { it.isNotEmpty() && it != address && it != url }
        val categories = parser.attr("category", true).orEmpty().split(',')
            .map { Texts.squash(it.trim().trim('/').substringAfterLast('/')) }
        return OpmlFeed(
            url = url,
            title = title,
            siteUrl = parser.attr("htmlUrl", true)?.let(::cleanUrl),
            tags = mergeTags(folders + categories),
        )
    }

    private fun mergeTags(tags: List<String>): List<String> =
        tags.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    /** A fetchable http(s) address, or null when [raw] cannot be made into one. */
    private fun cleanUrl(raw: String): String? {
        var url = raw.trim()
        if (url.startsWith("feed://", ignoreCase = true)) url = "http://" + url.substring(7)
        if (url.startsWith("feed:", ignoreCase = true)) url = url.substring(5)
        if (!SCHEME.containsMatchIn(url)) {
            if ("://" in url || '.' !in url) return null
            url = "https://$url"
        }
        return url.takeIf { it.toHttpUrlOrNull() != null }
    }

    /** Writes [feeds] as OPML 2.0 in UTF-8. Does not close [output]. */
    fun write(feeds: List<OpmlFeed>, output: OutputStream, title: String = DEFAULT_TITLE, created: Instant? = null) {
        output.write(write(feeds, title, created).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun write(feeds: List<OpmlFeed>, title: String = DEFAULT_TITLE, created: Instant? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head>\n")
        append("    <title>").append(XmlText.escape(title)).append("</title>\n")
        if (created != null) {
            append("    <dateCreated>").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(created.atOffset(ZoneOffset.UTC))).append("</dateCreated>\n")
        }
        append("  </head>\n")
        append("  <body>\n")
        val byTitle = Comparator<OpmlFeed> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title ?: a.url, b.title ?: b.url) }
        for (feed in feeds.filter { mergeTags(it.tags).isEmpty() }.sortedWith(byTitle)) outline(feed, "    ")
        // Tags that differ only in case are one folder, named as first seen.
        val folders = LinkedHashMap<String, Pair<String, MutableList<OpmlFeed>>>()
        for (feed in feeds) {
            for (tag in mergeTags(feed.tags.map(Texts::squash))) {
                folders.getOrPut(tag.lowercase()) { tag to ArrayList() }.second += feed
            }
        }
        for ((name, members) in folders.values.sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.first, b.first) }) {
            val label = XmlText.escape(name)
            append("    <outline text=\"").append(label).append("\" title=\"").append(label).append("\">\n")
            for (feed in members.sortedWith(byTitle)) outline(feed, "      ")
            append("    </outline>\n")
        }
        append("  </body>\n")
        append("</opml>\n")
    }

    private fun StringBuilder.outline(feed: OpmlFeed, indent: String) {
        val label = XmlText.escape(feed.title?.takeIf { it.isNotBlank() } ?: FeedUrls.hostOf(feed.url))
        append(indent).append("<outline type=\"rss\" text=\"").append(label).append("\" title=\"").append(label)
            .append("\" xmlUrl=\"").append(XmlText.escape(feed.url)).append('"')
        if (!feed.siteUrl.isNullOrBlank()) append(" htmlUrl=\"").append(XmlText.escape(feed.siteUrl)).append('"')
        append("/>\n")
    }

    const val DEFAULT_TITLE = "Morning Issue subscriptions"
    private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
}

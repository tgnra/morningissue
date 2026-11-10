package net.tgnra.morningissue.sync

import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** RSS (2.0, 0.9x, 1.0/RDF) and Atom (1.0, 0.3) on a namespace-aware pull parser. */
internal object XmlFeedParser {
    fun parse(text: String): RawFeed {
        var feed: RawFeed? = null
        try {
            val parser = XmlText.newParser(XmlText.sanitize(text), namespaceAware = true)
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
            if (event != XmlPullParser.START_TAG) throw FeedParseException("Empty document")
            when (parser.name.lowercase()) {
                "rss", "rdf" -> {
                    feed = RawFeed(FeedFormat.RSS)
                    rssContainer(parser, feed)
                }
                "feed" -> {
                    feed = RawFeed(FeedFormat.ATOM)
                    atomFeed(parser, feed)
                }
                else -> throw FeedParseException("Not a feed: <${parser.name}>")
            }
            // kxml ends a cut-off document quietly; treat it like any other damage.
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("Unexpected end of document")
        } catch (e: XmlPullParserException) {
            // A feed cut off or broken part-way still yields the posts before the damage.
            if (feed?.items.isNullOrEmpty()) throw FeedParseException("Malformed feed XML", e)
        } catch (e: IOException) {
            if (feed?.items.isNullOrEmpty()) throw FeedParseException("Malformed feed XML", e)
        } catch (e: RuntimeException) {
            // kxml reports some syntax errors as unchecked exceptions.
            if (feed?.items.isNullOrEmpty()) throw FeedParseException("Malformed feed XML", e)
        }
        return feed!!
    }

    // --- RSS ------------------------------------------------------------------------------------

    /** Children of `<rss>`, `<rdf:RDF>` or `<channel>`; RSS 1.0 keeps items beside the channel. */
    private fun rssContainer(p: XmlPullParser, feed: RawFeed) {
        p.forEachChild {
            val ns = p.namespace.orEmpty()
            val name = p.name.lowercase()
            when {
                isItunes(ns) && name == "image" -> {
                    if (feed.icon == null) feed.icon = p.attr("href")
                    p.skipElement()
                }
                !isCore(ns) -> p.skipElement()
                name == "channel" -> rssContainer(p, feed)
                name == "item" -> feed.items += rssItem(p)
                name == "title" -> feed.title = HtmlBlocks.plainText(p.readElementText())
                name == "link" -> p.readElementText().takeIf { it.isNotEmpty() }?.let { feed.link = it }
                name == "image" -> p.forEachChild {
                    if (p.name.lowercase() == "url") feed.icon = p.readElementText().ifEmpty { null } else p.skipElement()
                }
                else -> p.skipElement()
            }
        }
    }

    private fun rssItem(p: XmlPullParser): RawItem {
        val item = RawItem()
        var permalinkGuid: String? = null
        p.forEachChild {
            val ns = p.namespace.orEmpty()
            val name = p.name.lowercase()
            when {
                isMedia(ns) -> media(p, item)
                ns == NS_CONTENT && name == "encoded" -> item.contentHtml = p.readElementText()
                ns == NS_DC && name == "creator" -> item.author = p.readElementText()
                ns == NS_DC && name == "date" -> item.published = item.published ?: p.readElementText()
                isAtom(ns) && name == "link" -> {
                    val rel = p.attr("rel") ?: "alternate"
                    if (rel == "alternate" && item.link == null) item.link = p.attr("href")
                    p.skipElement()
                }
                isAtom(ns) && (name == "published" || name == "updated") -> item.updated = item.updated ?: p.readElementText()
                isItunes(ns) && name == "image" -> {
                    item.enclosureImage = item.enclosureImage ?: p.attr("href")
                    p.skipElement()
                }
                !isCore(ns) -> p.skipElement()
                name == "title" -> item.title = HtmlBlocks.plainText(p.readElementText())
                name == "link" -> {
                    val href = p.attr("href")
                    (p.readElementText().ifEmpty { href })?.let { item.link = it }
                }
                name == "description" -> item.summaryHtml = p.readElementText()
                name == "guid" -> {
                    val permalink = p.attr("isPermaLink")
                    val value = p.readElementText()
                    item.guid = value
                    if (!permalink.equals("false", ignoreCase = true) && HTTP.containsMatchIn(value)) permalinkGuid = value
                }
                name == "pubdate" || name == "date" -> item.published = p.readElementText()
                name == "author" -> item.author = item.author ?: authorName(p.readElementText())
                name == "enclosure" -> {
                    val url = p.attr("url")
                    if (url != null && isImage(p.attr("type"), null, url)) item.enclosureImage = item.enclosureImage ?: url
                    p.skipElement()
                }
                else -> p.skipElement()
            }
        }
        if (item.link.isNullOrBlank()) item.link = permalinkGuid
        return item
    }

    /** `media:content`, `media:thumbnail` and `media:group`, which may nest. */
    private fun media(p: XmlPullParser, item: RawItem) {
        when (p.name.lowercase()) {
            "content" -> {
                val url = p.attr("url")
                if (url != null && item.mediaImage == null && isImage(p.attr("type"), p.attr("medium"), url)) item.mediaImage = url
                p.forEachChild { media(p, item) }
            }
            "thumbnail" -> {
                item.thumbnail = item.thumbnail ?: p.attr("url")
                p.skipElement()
            }
            "group" -> p.forEachChild { if (isMedia(p.namespace.orEmpty())) media(p, item) else p.skipElement() }
            "description" -> {
                // YouTube keeps the only text of an entry here.
                val text = p.readElementText()
                if (item.summaryHtml == null && text.isNotEmpty()) item.summaryHtml = HtmlBlocks.textToHtml(text)
            }
            else -> p.skipElement()
        }
    }

    /** RSS `<author>` is "email (Name)"; keep the name when there is one. */
    private fun authorName(text: String): String? {
        val name = AUTHOR_NAME.find(text)?.groupValues?.get(1) ?: text
        return name.trim().takeIf { it.isNotEmpty() }
    }

    private fun isImage(type: String?, medium: String?, url: String): Boolean = when {
        medium != null -> medium.equals("image", ignoreCase = true)
        type != null -> type.startsWith("image/", ignoreCase = true)
        else -> IMAGE_EXTENSION.containsMatchIn(url)
    }

    // --- Atom -----------------------------------------------------------------------------------

    private fun atomFeed(p: XmlPullParser, feed: RawFeed) {
        feed.base = xmlBase(p)
        var logo: String? = null
        p.forEachChild {
            val ns = p.namespace.orEmpty()
            when {
                isMedia(ns) -> p.skipElement()
                else -> when (p.name.lowercase()) {
                    "title" -> feed.title = atomPlainText(p)
                    "link" -> {
                        val rel = p.attr("rel") ?: "alternate"
                        val type = p.attr("type")
                        if (rel == "alternate" && (feed.link == null || type == "text/html")) feed.link = p.attr("href")
                        p.skipElement()
                    }
                    "icon" -> feed.icon = p.readElementText().ifEmpty { null }
                    "logo" -> logo = p.readElementText().ifEmpty { null }
                    "author" -> feed.author = atomPerson(p)
                    "entry" -> feed.items += atomEntry(p)
                    else -> p.skipElement()
                }
            }
        }
        if (feed.icon == null) feed.icon = logo
    }

    private fun atomEntry(p: XmlPullParser): RawItem {
        val item = RawItem()
        item.base = xmlBase(p)
        var linkIsHtml = false
        p.forEachChild {
            val ns = p.namespace.orEmpty()
            when {
                isMedia(ns) -> media(p, item)
                else -> when (p.name.lowercase()) {
                    "title" -> item.title = atomPlainText(p)
                    "id" -> item.guid = p.readElementText()
                    "link" -> {
                        val rel = p.attr("rel") ?: "alternate"
                        val type = p.attr("type")
                        val href = p.attr("href")
                        if (rel == "alternate" && href != null && !linkIsHtml) {
                            item.link = href
                            linkIsHtml = type == "text/html"
                        } else if (rel == "enclosure" && href != null && isImage(type, null, href)) {
                            item.enclosureImage = item.enclosureImage ?: href
                        }
                        p.skipElement()
                    }
                    "published", "issued", "created" -> item.published = item.published ?: p.readElementText()
                    "updated", "modified" -> item.updated = p.readElementText()
                    "summary" -> item.summaryHtml = atomHtml(p)
                    "content" -> item.contentHtml = atomHtml(p)
                    "author" -> item.author = item.author ?: atomPerson(p)
                    else -> p.skipElement()
                }
            }
        }
        return item
    }

    private fun xmlBase(p: XmlPullParser): String? = p.getAttributeValue(NS_XML, "base")?.trim()?.takeIf { it.isNotEmpty() }

    private fun atomPerson(p: XmlPullParser): String? {
        var name: String? = null
        p.forEachChild { if (p.name.lowercase() == "name") name = p.readElementText().ifEmpty { null } else p.skipElement() }
        return name
    }

    /** An Atom text construct as plain text. */
    private fun atomPlainText(p: XmlPullParser): String = when (atomTextType(p)) {
        TextType.XHTML -> HtmlBlocks.plainText(p.readInnerXml())
        TextType.HTML -> HtmlBlocks.plainText(p.readElementText())
        else -> p.readElementText()
    }

    /** An Atom text construct as HTML; null when empty, out of line (`src`) or not text. */
    private fun atomHtml(p: XmlPullParser): String? = when (atomTextType(p)) {
        TextType.XHTML -> p.readInnerXml()
        TextType.HTML -> p.readElementText()
        TextType.TEXT -> HtmlBlocks.textToHtml(p.readElementText())
        TextType.OTHER -> {
            p.skipElement()
            ""
        }
    }.takeIf { it.isNotBlank() }

    private enum class TextType { TEXT, HTML, XHTML, OTHER }

    private fun atomTextType(p: XmlPullParser): TextType {
        val type = p.attr("type")?.lowercase()
        // Atom 0.3 marks entity-escaped HTML with mode="escaped".
        val escaped = p.attr("mode").equals("escaped", ignoreCase = true)
        return when {
            type == "xhtml" || type == "application/xhtml+xml" -> if (escaped) TextType.HTML else TextType.XHTML
            type == "html" || type == "text/html" -> TextType.HTML
            type == null || type == "text" || type == "text/plain" -> TextType.TEXT
            else -> TextType.OTHER
        }
    }

    // --- Namespaces -----------------------------------------------------------------------------

    private const val NS_XML = "http://www.w3.org/XML/1998/namespace"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"

    private fun isMedia(ns: String) = ns.startsWith("http://search.yahoo.com/mrss")
    private fun isAtom(ns: String) = ns == "http://www.w3.org/2005/Atom"
    private fun isItunes(ns: String) = ns.startsWith("http://www.itunes.com/dtds/podcast")

    /** RSS's own elements: no namespace, or one of the default namespaces RSS dialects declare. */
    private fun isCore(ns: String) = ns.isEmpty() || ns == "http://purl.org/rss/1.0/" ||
        ns == "http://my.netscape.com/rdf/simple/0.9/" || ns.startsWith("http://backend.userland.com/")

    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val AUTHOR_NAME = Regex("""\(([^)]+)\)""")
    private val IMAGE_EXTENSION = Regex("""\.(jpe?g|png|gif|webp|avif)(\?|$)""", RegexOption.IGNORE_CASE)
}

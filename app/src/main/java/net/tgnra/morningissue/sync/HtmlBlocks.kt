package net.tgnra.morningissue.sync

import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import org.jsoup.Jsoup
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Turns article HTML (from a feed entry or from Readability) into the block model the reader
 * renders. Unknown markup degrades to paragraphs; scripts, forms and embeds are dropped.
 */
object HtmlBlocks {
    /** [baseUrl] resolves relative links and image addresses. */
    fun convert(html: String, baseUrl: String? = null): List<ContentBlock> {
        if (html.isBlank()) return emptyList()
        return convert(Jsoup.parseBodyFragment(html, baseUrl.orEmpty()).body())
    }

    fun convert(root: Element): List<ContentBlock> = BlockConverter().convertChildren(root)

    /** Plain text as HTML: blank lines separate paragraphs, single line breaks are kept. */
    fun textToHtml(text: String): String = text.trim().split(BLANK_LINE).filter { it.isNotBlank() }
        .joinToString("") { "<p>" + XmlText.escape(it.trim()).replace("&#10;", "<br>").replace("&#13;", "") + "</p>" }

    /** The text of an HTML fragment with whitespace collapsed. */
    fun plainText(html: String): String =
        if ('<' in html || '&' in html) Jsoup.parseBodyFragment(html).text().trim() else html.trim().replace(SPACES, " ")

    private val BLANK_LINE = Regex("""\r?\n[ \t]*\r?\n""")
    private val SPACES = Regex("""\s+""")
}

private class BlockConverter {
    private val blocks = ArrayList<ContentBlock>()
    private val text = StringBuilder()
    private val spans = ArrayList<InlineSpan>()

    /** An inline style whose element is still being walked. */
    private class Open(val style: InlineStyle, val href: String?, var start: Int)

    private val open = ArrayList<Open>()

    fun convertChildren(element: Element, except: Element? = null): List<ContentBlock> {
        for (child in element.childNodes()) if (child !== except) walk(child)
        flush()
        return blocks
    }

    private fun walkChildren(element: Element) {
        for (child in element.childNodes()) walk(child)
    }

    private fun walk(node: Node) {
        when (node) {
            is TextNode -> appendText(node.wholeText)
            is Element -> element(node)
            else -> Unit
        }
    }

    private fun element(el: Element) {
        when (val tag = el.normalName()) {
            in SKIPPED -> Unit
            "br" -> lineBreak()
            "hr" -> flush()
            "img" -> image(el, null)
            "picture" -> el.selectFirst("img")?.let { image(it, null) }
            "figure" -> figure(el)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                merge(textsOf(sub(el)), " ")?.let { blocks += ContentBlock.Heading(tag[1] - '0', it) }
            }
            "blockquote" -> quote(el)
            "ul", "ol" -> list(el)
            "pre" -> pre(el)
            "a" -> {
                val href = el.absUrl("href").ifEmpty { el.attr("href") }.trim()
                if (LINK_SCHEME.containsMatchIn(href)) styled(el, InlineStyle.LINK, href) else walkChildren(el)
            }
            "b", "strong" -> styled(el, InlineStyle.BOLD)
            "i", "em", "cite", "dfn", "var" -> styled(el, InlineStyle.ITALIC)
            "code", "kbd", "samp", "tt" -> styled(el, InlineStyle.CODE)
            "s", "strike", "del" -> styled(el, InlineStyle.STRIKETHROUGH)
            "sup" -> styled(el, InlineStyle.SUPERSCRIPT)
            "sub" -> styled(el, InlineStyle.SUBSCRIPT)
            else -> if (el.isBlock) {
                flush()
                walkChildren(el)
                flush()
            } else {
                walkChildren(el)
            }
        }
    }

    // --- Inline text --------------------------------------------------------------------------

    private fun appendText(raw: String) {
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
            } else {
                text.append(ch)
            }
        }
    }

    /** One `<br>` is a line break; two in a row end the paragraph. */
    private fun lineBreak() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
        when {
            text.isEmpty() -> Unit
            text.last() == '\n' -> flush()
            else -> text.append('\n')
        }
    }

    private fun styled(el: Element, style: InlineStyle, href: String? = null) {
        val mark = Open(style, href, text.length)
        open += mark
        walkChildren(el)
        open.remove(mark)
        if (text.length > mark.start) spans += InlineSpan(mark.start, text.length, style, href)
    }

    /** Takes the pending inline text, closing and reopening any styles that span the break. */
    private fun takeText(): RichText? {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        val result = if (end == 0) {
            null
        } else {
            val all = spans + open.map { InlineSpan(it.start, end, it.style, it.href) }
            RichText(
                text.substring(0, end),
                all.map { it.copy(end = minOf(it.end, end)) }.filter { it.start < it.end }.sortedBy { it.start },
            )
        }
        text.setLength(0)
        spans.clear()
        open.forEach { it.start = 0 }
        return result
    }

    private fun flush() {
        takeText()?.let { blocks += ContentBlock.Paragraph(it) }
    }

    // --- Blocks -------------------------------------------------------------------------------

    private fun sub(el: Element, except: Element? = null): List<ContentBlock> = BlockConverter().convertChildren(el, except)

    private fun image(img: Element, caption: RichText?) {
        val alt = img.attr("alt").trim()
        val classes = img.className().lowercase()
        if ("emoji" in classes || "smiley" in classes) {
            appendText(alt)
            return
        }
        val url = imageUrl(img) ?: return
        val width = img.attr("width").trim().toIntOrNull()
        val height = img.attr("height").trim().toIntOrNull()
        // Tracking pixels and spacers.
        if ((width != null && width <= 2) || (height != null && height <= 2)) return
        flush()
        blocks += ContentBlock.Image(url, alt.ifEmpty { null }, caption, width, height)
    }

    private fun imageUrl(img: Element): String? {
        for (attribute in IMAGE_ATTRIBUTES) {
            val value = img.absUrl(attribute).ifEmpty { img.attr(attribute) }.trim()
            if (WEB_SCHEME.containsMatchIn(value)) return value
        }
        // srcset: "url 480w, url 800w" - take the widest.
        val best = (img.attr("srcset").ifBlank { img.attr("data-srcset") }).split(',')
            .mapNotNull { entry ->
                val parts = entry.trim().split(SPACES)
                parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { it to (parts.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 0.0) }
            }
            .maxByOrNull { it.second }?.first ?: return null
        return StringUtil.resolve(img.baseUri(), best).takeIf { WEB_SCHEME.containsMatchIn(it) }
    }

    private fun figure(el: Element) {
        val images = el.select("img")
        if (images.isEmpty()) {
            flush()
            walkChildren(el)
            flush()
            return
        }
        val caption = el.selectFirst("figcaption")?.let { merge(textsOf(sub(it)), " ") }
        images.forEachIndexed { index, img -> image(img, caption.takeIf { index == images.lastIndex }) }
    }

    private fun quote(el: Element) {
        flush()
        val citationElement = el.children().lastOrNull { it.normalName() == "footer" || it.normalName() == "cite" }
        val citation = citationElement?.let { merge(textsOf(sub(it)), " ") }
        val inner = sub(el, except = citationElement)
        val paragraphs = textsOf(inner)
        if (paragraphs.isNotEmpty() || citation != null) blocks += ContentBlock.Quote(paragraphs, citation)
        blocks += inner.filterIsInstance<ContentBlock.Image>()
    }

    private fun list(el: Element) {
        flush()
        val items = ArrayList<RichText>()
        val images = ArrayList<ContentBlock.Image>()
        collectItems(el, items, images)
        if (items.isNotEmpty()) blocks += ContentBlock.ItemList(el.normalName() == "ol", items)
        blocks += images
    }

    /** Flattens nested lists: a nested list's items follow their parent item. */
    private fun collectItems(listElement: Element, items: MutableList<RichText>, images: MutableList<ContentBlock.Image>) {
        for (child in listElement.children()) {
            when (child.normalName()) {
                "ul", "ol" -> collectItems(child, items, images)
                "li" -> {
                    val inner = sub(child)
                    merge(textsOf(inner.filter { it !is ContentBlock.ItemList }), "\n")?.let { items += it }
                    inner.filterIsInstance<ContentBlock.ItemList>().forEach { items += it.items }
                    images += inner.filterIsInstance<ContentBlock.Image>()
                }
            }
        }
    }

    private fun pre(el: Element) {
        flush()
        val code = el.wholeText().trim('\n', '\r').trimEnd()
        if (code.isBlank()) return
        val language = el.selectFirst("code[class]")?.classNames().orEmpty().plus(el.classNames())
            .firstNotNullOfOrNull { name -> LANGUAGE_PREFIXES.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } }
        blocks += ContentBlock.Preformatted(code, language?.takeIf { it.isNotEmpty() })
    }

    private fun textsOf(blocks: List<ContentBlock>): List<RichText> = blocks.flatMap { block ->
        when (block) {
            is ContentBlock.Paragraph -> listOf(block.text)
            is ContentBlock.Heading -> listOf(block.text)
            is ContentBlock.Quote -> block.paragraphs + listOfNotNull(block.citation)
            is ContentBlock.ItemList -> block.items
            is ContentBlock.Preformatted -> listOf(RichText(block.text))
            is ContentBlock.Image -> emptyList()
        }
    }

    private fun merge(parts: List<RichText>, separator: String): RichText? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        val merged = StringBuilder()
        val mergedSpans = ArrayList<InlineSpan>()
        for (part in parts) {
            if (merged.isNotEmpty()) merged.append(separator)
            val offset = merged.length
            merged.append(part.text)
            part.spans.mapTo(mergedSpans) { it.copy(start = it.start + offset, end = it.end + offset) }
        }
        return RichText(merged.toString(), mergedSpans)
    }

    private companion object {
        val SKIPPED = setOf(
            "script", "style", "noscript", "iframe", "object", "embed", "form", "input", "button", "select",
            "textarea", "svg", "canvas", "template", "head", "title", "meta", "link", "video", "audio",
            "source", "map", "area",
        )
        val IMAGE_ATTRIBUTES = listOf("src", "data-src", "data-lazy-src", "data-original")
        val LANGUAGE_PREFIXES = listOf("language-", "lang-")
        val WEB_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
        val LINK_SCHEME = Regex("^(https?://|mailto:)", RegexOption.IGNORE_CASE)
        val SPACES = Regex("""\s+""")
    }
}

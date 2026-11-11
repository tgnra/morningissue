package net.tgnra.morningissue.sync

import java.nio.charset.Charset
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Text decoding and XML clean-up shared by the feed parser and the OPML reader. */
internal object XmlText {
    /**
     * Bytes to text. A byte-order mark wins, then the document's own XML declaration, then
     * [declared] (the HTTP header), then UTF-8. The declaration is trusted over the header
     * because wrong server defaults are far more common than transcoding proxies.
     */
    fun decode(bytes: ByteArray, declared: Charset? = null): String {
        val charset = bomCharset(bytes) ?: xmlDeclarationCharset(bytes) ?: declared ?: Charsets.UTF_8
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun bomCharset(b: ByteArray): Charset? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        else -> null
    }

    private fun xmlDeclarationCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        if (!head.trimStart().startsWith("<?xml")) return null
        val name = XML_ENCODING.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    /**
     * Makes sloppy real-world XML acceptable to a strict pull parser: HTML entities such as
     * `&nbsp;` become their characters, bare ampersands are escaped, and anything before the
     * first tag is dropped. CDATA sections and comments are left alone.
     */
    fun sanitize(xml: String): String {
        // Scanned by hand. The earlier Regex.replace with a lambda took five seconds on a phone
        // for a 270 kB feed of escaped HTML (about 0.7 ms per entity); this takes under 100 ms.
        val out = StringBuilder(xml.length + 64)
        var i = xml.indexOf('<').coerceAtLeast(0)
        val n = xml.length
        while (i < n) {
            val ch = xml[i]
            if (ch == '<') {
                val close = when {
                    xml.startsWith("<![CDATA[", i) -> "]]>"
                    xml.startsWith("<!--", i) -> "-->"
                    else -> null
                }
                val end = close?.let { xml.indexOf(it, i + 4) } ?: -1
                if (close != null && end >= 0) {
                    out.append(xml, i, end + close.length)
                    i = end + close.length
                } else {
                    out.append(ch)
                    i++
                }
            } else if (ch == '&') {
                val end = referenceEnd(xml, i)
                if (end < 0) {
                    out.append("&amp;")
                    i++
                } else {
                    val value = xml.substring(i, end)
                    if (xml[i + 1] == '#') {
                        if (isValidCharRef(value)) out.append(value)
                    } else {
                        val name = value.substring(1, value.length - 1)
                        if (name in XML_ENTITIES) {
                            out.append(value)
                        } else {
                            val decoded = Parser.unescapeEntities(value, false)
                            if (decoded == value) out.append("&amp;").append(name).append(';') else out.append(escape(decoded))
                        }
                    }
                    i = end
                }
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Where the entity or character reference starting at the `&` at [start] ends (just past
     * its `;`), or -1 when the ampersand does not begin one.
     */
    private fun referenceEnd(xml: String, start: Int): Int {
        val n = xml.length
        var i = start + 1
        if (i >= n) return -1
        if (xml[i] == '#') {
            i++
            val hex = i < n && (xml[i] == 'x' || xml[i] == 'X')
            if (hex) i++
            val digits = i
            while (i < n && (xml[i] in '0'..'9' || (hex && (xml[i] in 'a'..'f' || xml[i] in 'A'..'F')))) i++
            return if (i > digits && i < n && xml[i] == ';') i + 1 else -1
        }
        if (!isAsciiLetter(xml[i])) return -1
        i++
        while (i < n && (isAsciiLetter(xml[i]) || xml[i] in '0'..'9')) i++
        return if (i < n && xml[i] == ';') i + 1 else -1
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun isValidCharRef(ref: String): Boolean {
        val digits = ref.substring(2, ref.length - 1)
        val code = if (digits.startsWith("x", ignoreCase = true)) {
            digits.substring(1).toIntOrNull(16)
        } else {
            digits.toIntOrNull()
        } ?: return false
        return code == 0x9 || code == 0xA || code == 0xD || (code in 0x20..0xD7FF) ||
            (code in 0xE000..0xFFFD) || (code in 0x10000..0x10FFFF)
    }

    /** Escapes text for use as XML character data or a double-quoted attribute value. */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> if (ch >= ' ' && ch != '￾' && ch != '￿') append(ch)
            }
        }
    }

    /** A pull parser over [text], which should have been through [sanitize]. */
    fun newParser(text: String, namespaceAware: Boolean): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = namespaceAware
        return factory.newPullParser().apply { setInput(text.reader()) }
    }

    private val XML_ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
}

/** Moves from a start tag to its matching end tag, ignoring everything inside. */
internal fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (nextToken()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/**
 * All text inside the current element, nested elements included; ends on its end tag.
 *
 * Reads token by token and joins the pieces here. Asking Android's parser for whole text
 * runs with `next()` was far slower than linear in the number of entities: a 1.2 MB feed of
 * escaped HTML (40,000 `&lt;`-style entities) took 97 seconds to read on a phone.
 */
internal fun XmlPullParser.readElementText(): String {
    val out = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (nextToken()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF,
            XmlPullParser.IGNORABLE_WHITESPACE -> text?.let(out::append)
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return out.toString().trim()
}

/** The current element's content re-serialised as markup (for Atom `type="xhtml"`). */
internal fun XmlPullParser.readInnerXml(): String {
    val out = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (nextToken()) {
            XmlPullParser.START_TAG -> {
                depth++
                out.append('<').append(name)
                for (i in 0 until attributeCount) {
                    out.append(' ').append(getAttributeName(i)).append("=\"")
                        .append(XmlText.escape(getAttributeValue(i))).append('"')
                }
                out.append('>')
            }
            XmlPullParser.END_TAG -> {
                depth--
                if (depth > 0) out.append("</").append(name).append('>')
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF,
            XmlPullParser.IGNORABLE_WHITESPACE -> text?.let { out.append(XmlText.escape(it)) }
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return out.toString().trim()
}

/** An attribute by local name in any namespace; blank values count as missing. */
internal fun XmlPullParser.attr(name: String, ignoreCase: Boolean = false): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).equals(name, ignoreCase)) {
            return getAttributeValue(i)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}

/**
 * Calls [onChild] at each child start tag of the current element. [onChild] must leave the
 * parser on that child's end tag (read it or skip it).
 */
internal inline fun XmlPullParser.forEachChild(onChild: () -> Unit) {
    val parentDepth = depth
    while (true) {
        when (nextToken()) {
            XmlPullParser.START_TAG -> onChild()
            XmlPullParser.END_TAG -> if (depth <= parentDepth) return
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

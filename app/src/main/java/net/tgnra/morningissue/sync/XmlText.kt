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
        val start = xml.indexOf('<').coerceAtLeast(0)
        return SANITIZE.replace(xml.substring(start)) { match ->
            val value = match.value
            val name = match.groups[1]?.value
            when {
                value.startsWith("<") -> value
                value == "&" -> "&amp;"
                name == null -> if (isValidCharRef(value)) value else ""
                name in XML_ENTITIES -> value
                else -> {
                    val decoded = Parser.unescapeEntities(value, false)
                    if (decoded == value) "&amp;$name;" else escape(decoded)
                }
            }
        }
    }

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
    private val SANITIZE = Regex(
        """<!\[CDATA\[.*?]]>|<!--.*?-->|&(?:#(?:[0-9]+|[xX][0-9a-fA-F]+);|([A-Za-z][A-Za-z0-9]*);)?""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
}

/** Moves from a start tag to its matching end tag, ignoring everything inside. */
internal fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** All text inside the current element, nested elements included; ends on its end tag. */
internal fun XmlPullParser.readElementText(): String {
    val out = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.TEXT -> out.append(text)
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
        when (next()) {
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
            XmlPullParser.TEXT -> out.append(XmlText.escape(text))
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
        when (next()) {
            XmlPullParser.START_TAG -> onChild()
            XmlPullParser.END_TAG -> if (depth <= parentDepth) return
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

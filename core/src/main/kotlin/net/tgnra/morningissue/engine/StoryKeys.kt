package net.tgnra.morningissue.engine

import java.text.Normalizer

/**
 * The keys cross-feed deduplication compares: a canonical URL and a normalised title. Pure
 * string work; the rule for when two keys make one story is in `EditionBuilder.dedupe`.
 */
object StoryKeys {
    /**
     * [url] reduced to what identifies the page: host and path, plus any query parameters that
     * are not tracking. Ignored: the scheme (`http` and `https` are the same page), host case,
     * a leading `www.`, a default port, the fragment, a trailing slash, and the tracking
     * parameters in [isTracking]. The path keeps its case. Null when there is no host to
     * compare, so a blank or relative link never matches anything.
     */
    fun canonicalUrl(url: String?): String? {
        var rest = url?.trim().orEmpty()
        if (rest.isEmpty()) return null
        rest = rest.substringBefore('#')
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            val scheme = rest.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null
            rest = rest.substring(schemeEnd + 3)
        } else if (rest.startsWith("//")) {
            rest = rest.substring(2)
        } else {
            return null
        }
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')
        var host = rest.substringBefore('/').substringAfterLast('@').lowercase()
        val path = rest.substring(rest.indexOf('/').let { if (it < 0) rest.length else it }).trimEnd('/')
        host = host.removeSuffix(":80").removeSuffix(":443").removePrefix("www.").trimEnd('.')
        if (host.isEmpty()) return null
        val kept = query.split('&').filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        return buildString {
            append(host).append(path)
            if (kept.isNotEmpty()) append('?').append(kept.joinToString("&"))
        }
    }

    /** Query parameters that say where a click came from, not which page it is. */
    fun isTracking(parameter: String): Boolean {
        val name = parameter.lowercase()
        return name.startsWith("utm_") || name in TRACKING_PARAMETERS
    }

    /**
     * [title] for comparison: lower case, accents and punctuation dropped, whitespace
     * collapsed. A leading or trailing segment naming the feed itself ("… | The Verge",
     * "The Verge: …") is removed first, since syndicated copies differ in exactly that.
     * Every word and digit is kept, so "Security updates for Tuesday" and "Security updates
     * for Wednesday", or two release numbers, never share a key.
     */
    fun titleKey(title: String?, feedTitle: String? = null): String? {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val own = feedTitle?.let(::words)?.takeIf { it.isNotEmpty() }
        var segments = raw.split(SEPARATOR).map(::words).filter { it.isNotEmpty() }
        if (own != null && segments.size > 1) {
            if (segments.last() == own) segments = segments.dropLast(1)
            if (segments.size > 1 && segments.first() == own) segments = segments.drop(1)
        }
        return segments.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun words(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .lowercase()
            .replace(NOT_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    private val TRACKING_PARAMETERS = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "yclid", "igshid", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok",
    )

    /** Separators between a headline and a site name: a spaced dash or bar, or a colon. */
    private val SEPARATOR = Regex("""\s+[|\-–—·»]\s+|:\s+""")
    private val MARKS = Regex("""\p{M}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

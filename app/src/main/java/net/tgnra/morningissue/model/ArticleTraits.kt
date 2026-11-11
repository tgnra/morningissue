package net.tgnra.morningissue.model

import java.net.URI

/**
 * A category a feed files a post under (RSS `<category>`, Atom `<category term>`, JSON Feed
 * `tags`). [key] is what posts are matched by; [label] is the feed's own spelling, for display.
 */
data class ArticleCategory(val key: String, val label: String)

/** The rules for turning a feed's category names into [ArticleCategory] values. */
object ArticleCategories {
    /** A longer name is a sentence, not a category; it is cut here. */
    const val MAX_KEY_CHARS = 64

    /** Some feeds list dozens of keywords per post; only the first few say what it is about. */
    const val MAX_PER_ARTICLE = 12

    private val SPACES = Regex("""\s+""")

    /** [name] with runs of whitespace squashed to single spaces, lowercased, at most [MAX_KEY_CHARS] long. */
    fun key(name: String): String = squash(name).lowercase().take(MAX_KEY_CHARS).trimEnd()

    /**
     * One category, or null when [name] is blank. [name] is what identifies the category (an
     * Atom `term`); [label] is its human spelling when the feed gives one separately.
     */
    fun of(name: String, label: String? = null): ArticleCategory? {
        val key = key(name)
        if (key.isEmpty()) return null
        val shown = label?.let(::squash)?.takeIf { it.isNotEmpty() } ?: squash(name)
        return ArticleCategory(key, shown.take(MAX_KEY_CHARS).trimEnd())
    }

    /** [categories] in the feed's order, one per key, at most [MAX_PER_ARTICLE]. */
    fun clean(categories: List<ArticleCategory>): List<ArticleCategory> =
        categories.distinctBy { it.key }.take(MAX_PER_ARTICLE)

    private fun squash(text: String) = text.trim().replace(SPACES, " ")
}

/**
 * Structural features of a post: what it is made of, as opposed to what it says. The same feed
 * can post essays, link round-ups and podcast episodes, and a reader may want only some of them.
 * Word count is not here because it is already on the article.
 */
data class ArticleTraits(
    /** Links in the body. */
    val linkCount: Int,
    /** Images in the body. */
    val imageCount: Int,
    /** The body has a code block or inline code. */
    val hasCode: Boolean,
    val enclosure: EnclosureKind,
    /** The post mainly points somewhere else; see [ArticleTraitRules.isLinkPost]. */
    val isLinkPost: Boolean,
    /** The marker of a recurring series ("links", "podcast", "show hn"); see [ArticleTraitRules.titlePrefix]. */
    val titlePrefix: String?,
)

/**
 * Computes [ArticleTraits]. Pure, so the same rules serve sync (from the feed entry) and the
 * backfill (from what is stored).
 */
object ArticleTraitRules {
    /**
     * Raise this when a rule below changes. Rows in `article_traits` carry the version they
     * were computed with, and the backfill recomputes those that are behind.
     */
    const val VERSION = 1

    /**
     * The version of a row that only records what cannot be recomputed (the enclosure), written
     * by archive import. Always behind [VERSION], so the backfill completes the row.
     */
    const val VERSION_PENDING = 0

    /** A body longer than this has something of its own to say, however many links it has. */
    const val LINK_POST_MAX_WORDS = 120

    /** A series marker is a word or a short phrase; anything longer is the start of a headline. */
    const val TITLE_PREFIX_MAX_WORDS = 3
    const val TITLE_PREFIX_MAX_CHARS = 40

    /** What separates a series marker from the rest of the title. */
    private val TITLE_SEPARATORS = listOf(": ", " | ", " – ", " — ", " - ")

    private val SPACES = Regex("""\s+""")

    /**
     * @param url the post's own link.
     * @param siteUrl the address of the site the feed belongs to (`feed.siteUrl`).
     * @param blocks the body; empty when the feed carries none or it has been trimmed, in
     *   which case the counts are zero and [isLinkPost] goes by the link alone.
     */
    fun compute(
        title: String,
        url: String?,
        siteUrl: String?,
        blocks: List<ContentBlock>,
        enclosure: EnclosureKind = EnclosureKind.NONE,
    ): ArticleTraits {
        val spans = blocks.flatMap(::spansOf)
        val links = spans.filter { it.style == InlineStyle.LINK }.mapNotNull { it.href }
        return ArticleTraits(
            linkCount = links.size,
            imageCount = blocks.count { it is ContentBlock.Image },
            hasCode = blocks.any { it is ContentBlock.Preformatted } || spans.any { it.style == InlineStyle.CODE },
            enclosure = enclosure,
            isLinkPost = isLinkPost(url, siteUrl, links, ContentBlocks.wordCount(ContentBlocks.plainText(blocks))),
            titlePrefix = titlePrefix(title),
        )
    }

    /**
     * Whether a post mainly points somewhere else, as a link blog's entries do. Either:
     *
     * - its own link leaves the feed's site (the headline opens someone else's page), or
     * - its body is short ([LINK_POST_MAX_WORDS] words at most) and links to exactly one
     *   address off the site: a pointer with a remark, not an article with references.
     *
     * Two hosts are the same site when they are equal without `www.` or one is a subdomain of
     * the other, so `blog.example.com` posts in a feed of `example.com` are not link posts.
     * Without a site address or a link there is nothing to compare, and the answer is no.
     *
     * @param links the addresses the body links to.
     * @param wordCount the length of the body.
     */
    fun isLinkPost(url: String?, siteUrl: String?, links: List<String>, wordCount: Int): Boolean {
        val site = siteUrl?.let(::hostOf)
        val own = url?.let(::hostOf)
        if (site != null && own != null && !sameSite(site, own)) return true
        val home = listOfNotNull(site, own)
        if (home.isEmpty() || wordCount > LINK_POST_MAX_WORDS) return false
        val external = HashSet<String>()
        for (link in links) {
            val host = hostOf(link) ?: continue
            if (home.none { sameSite(it, host) }) external += link.substringBefore('#').trimEnd('/')
        }
        return external.size == 1
    }

    /**
     * The marker of a recurring series: the text before the first `": "`, `" | "` or spaced
     * dash in [title], lowercased, when it is at most [TITLE_PREFIX_MAX_WORDS] words. "Links:
     * week 12" gives `links`; "Show HN: A thing" gives `show hn`. Null when the title has no
     * separator, nothing follows it, or what precedes it is too long to be a marker.
     */
    fun titlePrefix(title: String): String? {
        val clean = title.trim().replace(SPACES, " ")
        val (at, separator) = TITLE_SEPARATORS.map { clean.indexOf(it) to it }.filter { it.first > 0 }.minByOrNull { it.first }
            ?: return null
        if (clean.length <= at + separator.length) return null
        val prefix = clean.substring(0, at).trim().lowercase()
        if (prefix.isEmpty() || prefix.length > TITLE_PREFIX_MAX_CHARS) return null
        return prefix.takeIf { it.split(' ').size <= TITLE_PREFIX_MAX_WORDS }
    }

    private fun spansOf(block: ContentBlock): List<InlineSpan> = when (block) {
        is ContentBlock.Paragraph -> block.text.spans
        is ContentBlock.Heading -> block.text.spans
        is ContentBlock.Image -> block.caption?.spans.orEmpty()
        is ContentBlock.Quote -> block.paragraphs.flatMap { it.spans } + block.citation?.spans.orEmpty()
        is ContentBlock.ItemList -> block.items.flatMap { it.spans }
        is ContentBlock.Preformatted -> emptyList()
    }

    /** The lowercased host of an http(s) address without `www.`; null for anything else. */
    private fun hostOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) return null
        return uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    private fun sameSite(a: String, b: String): Boolean = a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

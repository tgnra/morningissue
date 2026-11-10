package net.tgnra.morningissue.sync

import java.security.MessageDigest
import java.time.Instant
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class FeedFormat { RSS, ATOM, JSON }

/** The document could not be read as a feed. */
class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A feed document reduced to what the app stores. All URLs are absolute. */
data class ParsedFeed(
    val format: FeedFormat,
    val title: String?,
    /** The site the feed belongs to. */
    val siteUrl: String?,
    val iconUrl: String?,
    val items: List<ParsedItem>,
)

/**
 * One post. Identity, link and dates are settled by the parser; the costlier parts derived
 * from the body HTML are in [content], computed on first use so that sync only pays for posts
 * it has not stored yet.
 */
class ParsedItem internal constructor(
    /** The feed's ID for the post, else its link, else a hash of title and date. */
    val guid: String,
    val url: String?,
    val title: String,
    val author: String?,
    /** The feed's date; the fetch time when missing or unreadable (see [hasDate]). */
    val publishedAt: Instant,
    val hasDate: Boolean,
    private val mediaImageUrl: String?,
    private val summaryHtml: String?,
    private val bodyHtml: String?,
    private val baseUrl: String,
) {
    val content: ItemContent by lazy {
        val blocks = bodyHtml?.let { HtmlBlocks.convert(it, baseUrl) }.orEmpty()
        val plainText = ContentBlocks.plainText(blocks)
        val summary = Texts.excerpt(summaryHtml?.let(HtmlBlocks::plainText).orEmpty().ifEmpty { plainText })
        val image = mediaImageUrl
            ?: blocks.asSequence().filterIsInstance<ContentBlock.Image>()
                .firstOrNull { (it.width ?: MIN_LEAD_IMAGE_PX) >= MIN_LEAD_IMAGE_PX && (it.height ?: MIN_LEAD_IMAGE_PX) >= MIN_LEAD_IMAGE_PX }?.url
            ?: summaryHtml?.takeIf { it !== bodyHtml }?.let { html ->
                HtmlBlocks.convert(html, baseUrl).filterIsInstance<ContentBlock.Image>().firstOrNull()?.url
            }
        ItemContent(
            summary = summary,
            blocks = blocks,
            plainText = plainText,
            wordCount = ContentBlocks.wordCount(plainText.ifEmpty { summary.orEmpty() }),
            imageUrl = image,
            contentHash = Texts.hash(Texts.squash(title).lowercase() + "\n" + Texts.squash(plainText.ifEmpty { summary.orEmpty() })),
        )
    }

    override fun toString(): String = "ParsedItem(guid=$guid, title=$title, publishedAt=$publishedAt)"

    private companion object {
        /** Images stated to be smaller than this are icons and badges, not lead images. */
        const val MIN_LEAD_IMAGE_PX = 50
    }
}

/** What sync stores for a post beyond its headline fields. */
data class ItemContent(
    /** Plain-text standfirst, at most a few sentences; null when the feed gives no text. */
    val summary: String?,
    /** The feed-provided body; empty when the feed carries none. May be an excerpt. */
    val blocks: List<ContentBlock>,
    val plainText: String,
    val wordCount: Int,
    /** Lead image: media tags, then an image enclosure, then the first image in the body. */
    val imageUrl: String?,
    /** Hash of the normalised title and text, for spotting the same story across feeds. */
    val contentHash: String,
)

internal object Texts {
    private val SPACES = Regex("""\s+""")
    private const val EXCERPT_CHARS = 400

    fun squash(text: String): String = text.trim().replace(SPACES, " ")

    /** At most [EXCERPT_CHARS] characters, cut at a word boundary; null when blank. */
    fun excerpt(text: String, limit: Int = EXCERPT_CHARS): String? {
        val clean = squash(text)
        if (clean.isEmpty()) return null
        if (clean.length <= limit) return clean
        val cut = clean.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
        return clean.substring(0, cut).trimEnd(',', ';', ':', ' ', '.') + "…"
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /** [url] resolved against [base]; null unless the result is an http(s) address. */
    fun resolve(base: String?, url: String?): String? {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return null
        clean.toHttpUrlOrNull()?.let { return it.toString() }
        return base?.toHttpUrlOrNull()?.resolve(clean)?.toString()
    }
}

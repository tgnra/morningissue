package net.tgnra.morningissue.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Inline formatting applied to a range of a [RichText]. */
@Serializable
enum class InlineStyle {
    @SerialName("b") BOLD,
    @SerialName("i") ITALIC,
    @SerialName("code") CODE,
    @SerialName("a") LINK,
    @SerialName("s") STRIKETHROUGH,
    @SerialName("sup") SUPERSCRIPT,
    @SerialName("sub") SUBSCRIPT,
}

/** A style over `[start, end)` of the owning text. [href] is set for [InlineStyle.LINK] only. */
@Serializable
data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val href: String? = null,
)

/** Plain text plus inline spans. Spans may overlap and nest. */
@Serializable
data class RichText(
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
)

/**
 * The block model for article text. Sync and the full-text fetcher turn HTML into these;
 * the reader renders them in Compose. Stored as JSON through [ContentBlocks].
 */
@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("p")
    data class Paragraph(val text: RichText) : ContentBlock

    /** [level] is 1..6 as in HTML. */
    @Serializable
    @SerialName("h")
    data class Heading(val level: Int, val text: RichText) : ContentBlock

    /** [width] and [height] are in pixels when the source states them. */
    @Serializable
    @SerialName("img")
    data class Image(
        val url: String,
        val alt: String? = null,
        val caption: RichText? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("quote")
    data class Quote(val paragraphs: List<RichText>, val citation: RichText? = null) : ContentBlock

    /** A flat list; nested lists are flattened by the converter. */
    @Serializable
    @SerialName("list")
    data class ItemList(val ordered: Boolean, val items: List<RichText>) : ContentBlock

    /** Code or other preformatted text; whitespace is significant. */
    @Serializable
    @SerialName("pre")
    data class Preformatted(val text: String, val language: String? = null) : ContentBlock
}

/** JSON codec for the block model as stored in `article_content.blocksJson`. */
object ContentBlocks {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(blocks: List<ContentBlock>): String = json.encodeToString(blocks)

    /** Returns an empty list when [stored] is blank or cannot be read. */
    fun decode(stored: String): List<ContentBlock> {
        if (stored.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ContentBlock>>(stored) }.getOrDefault(emptyList())
    }

    /** The text of [blocks] without formatting, one block per line; used for search and word counts. */
    fun plainText(blocks: List<ContentBlock>): String = buildString {
        fun line(text: String) {
            if (text.isNotBlank()) appendLine(text.trim())
        }
        for (block in blocks) {
            when (block) {
                is ContentBlock.Paragraph -> line(block.text.text)
                is ContentBlock.Heading -> line(block.text.text)
                is ContentBlock.Image -> block.caption?.let { line(it.text) }
                is ContentBlock.Quote -> {
                    block.paragraphs.forEach { line(it.text) }
                    block.citation?.let { line(it.text) }
                }
                is ContentBlock.ItemList -> block.items.forEach { line(it.text) }
                is ContentBlock.Preformatted -> line(block.text)
            }
        }
    }.trimEnd()

    fun wordCount(plainText: String): Int = plainText.split(WHITESPACE).count { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}

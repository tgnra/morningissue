package net.tgnra.morningissue.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** JSON Feed 1.0 and 1.1 (https://www.jsonfeed.org/version/1.1/). */
internal object JsonFeedParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parse(text: String): RawFeed {
        val document = try {
            json.decodeFromString<Document>(text.trimStart('\uFEFF'))
        } catch (e: SerializationException) {
            throw FeedParseException("Malformed JSON feed", e)
        } catch (e: IllegalArgumentException) {
            throw FeedParseException("Malformed JSON feed", e)
        }
        val feed = RawFeed(FeedFormat.JSON)
        feed.title = document.title
        feed.link = document.homePageUrl
        feed.icon = document.icon ?: document.favicon
        feed.author = (document.authors?.firstOrNull() ?: document.author)?.name
        for (entry in document.items) {
            val item = RawItem()
            // IDs are strings by the spec, but numbers turn up.
            item.guid = (entry.id as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            item.link = entry.url ?: entry.externalUrl
            item.title = entry.title
            item.summaryHtml = entry.summary?.let(HtmlBlocks::textToHtml)
            item.contentHtml = entry.contentHtml?.takeIf { it.isNotBlank() } ?: entry.contentText?.let(HtmlBlocks::textToHtml)
            item.author = (entry.authors?.firstOrNull() ?: entry.author)?.name
            item.published = entry.datePublished
            item.updated = entry.dateModified
            item.mediaImage = entry.image ?: entry.bannerImage
            item.enclosureImage = entry.attachments?.firstOrNull { it.mimeType?.startsWith("image/") == true }?.url
            feed.items += item
        }
        return feed
    }

    @Serializable
    private class Document(
        val title: String? = null,
        @SerialName("home_page_url") val homePageUrl: String? = null,
        val icon: String? = null,
        val favicon: String? = null,
        val author: Author? = null,
        val authors: List<Author>? = null,
        val items: List<Entry> = emptyList(),
    )

    @Serializable
    private class Author(val name: String? = null)

    @Serializable
    private class Attachment(val url: String? = null, @SerialName("mime_type") val mimeType: String? = null)

    @Serializable
    private class Entry(
        val id: JsonElement? = null,
        val url: String? = null,
        @SerialName("external_url") val externalUrl: String? = null,
        val title: String? = null,
        @SerialName("content_html") val contentHtml: String? = null,
        @SerialName("content_text") val contentText: String? = null,
        val summary: String? = null,
        val image: String? = null,
        @SerialName("banner_image") val bannerImage: String? = null,
        @SerialName("date_published") val datePublished: String? = null,
        @SerialName("date_modified") val dateModified: String? = null,
        val author: Author? = null,
        val authors: List<Author>? = null,
        val attachments: List<Attachment>? = null,
    )
}

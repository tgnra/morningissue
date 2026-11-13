package net.tgnra.morningissue.server.simulation

import java.time.Clock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The network as the server's `FeedFetcher` sees it, with no network at all: an OkHttp
 * interceptor that answers every request itself, from [schedule] as of [clock]. A feed's
 * document holds its newest posts published by now, written as RSS 2.0 from their templates,
 * with an ETag so that an unchanged feed is answered 304 as a real server would. A request
 * for anything else fails loudly rather than reaching out.
 */
class SimulatedInternet(private val schedule: PostSchedule, private val feeds: List<FeedSpec>, private val clock: Clock) {
    private val byUrl = feeds.associateBy { it.url }

    var requests = 0
        private set

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> answer(chain) })
        .build()

    private fun answer(chain: Interceptor.Chain): Response {
        requests++
        val request = chain.request()
        val feed = byUrl[request.url.toString()] ?: error("The simulation has no page at ${request.url}")
        val posts = schedule.documentAt(feed, clock.instant())
        val etag = "\"${feed.key}-${posts.firstOrNull()?.seq ?: -1}\""
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).header("ETag", etag)
        if (request.header("If-None-Match") == etag) {
            return builder.code(304).message("Not Modified").body(ByteArray(0).toResponseBody(null)).build()
        }
        return builder.code(200).message("OK")
            .body(document(feed, posts).toResponseBody("application/rss+xml; charset=utf-8".toMediaType()))
            .build()
    }

    companion object {
        /** Restores a post's length (see [Template.originalWords]) by repeating its trimmed paragraphs, up to this many words. */
        const val MAX_BODY_WORDS = 2500

        fun document(feed: FeedSpec, posts: List<SimPost>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\"><channel>")
            append("<title>").append(escape(feed.title)).append("</title>")
            append("<link>").append(escape(feed.siteUrl)).append("</link>")
            for (post in posts) {
                append("<item><title>").append(escape(post.title)).append("</title>")
                append("<link>").append(escape(post.link)).append("</link>")
                append("<guid isPermaLink=\"false\">").append(post.guid).append("</guid>")
                append("<pubDate>").append(PostSchedule.RFC_1123.format(post.publishedAt)).append("</pubDate>")
                for (category in post.categories) append("<category>").append(escape(category)).append("</category>")
                post.template.enclosure?.let {
                    append("<enclosure url=\"").append(escape(it.url)).append("\" type=\"").append(escape(it.type))
                        .append("\" length=\"").append(escape(it.length)).append("\"/>")
                }
                append("<description><![CDATA[").append(body(post.template).replace("]]>", "]]]]><![CDATA[>")).append("]]></description>")
                append("</item>")
            }
            append("</channel></rss>")
        }

        private val bodies = java.util.concurrent.ConcurrentHashMap<Template, String>()

        /** The trimmed body, its text repeated until the post is about as long as the real one was. */
        fun body(template: Template): String = bodies.getOrPut(template) {
            val trimmed = template.bodyHtml
            val trimmedWords = trimmed.replace(Regex("<[^>]+>"), " ").split(Regex("\\s+")).count { it.isNotBlank() }
            val target = minOf(template.originalWords, MAX_BODY_WORDS)
            if (trimmedWords == 0 || target <= trimmedWords) return@getOrPut trimmed
            val text = trimmed.replace(Regex("<pre[\\s\\S]*?</pre>"), " ").replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").trim().removeSuffix("…").trim()
            if (text.isEmpty()) return@getOrPut trimmed
            val filler = StringBuilder(trimmed)
            var words = trimmedWords
            val per = text.split(' ').size
            while (words < target) {
                filler.append("<p>").append(escape(text)).append("</p>")
                words += per
            }
            filler.toString()
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}

package net.tgnra.morningissue.sync

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal object Fixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sync/$name")) { "Missing fixture sync/$name" }.use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}

internal val NOW: Instant = Instant.parse("2026-11-10T06:30:00Z")

/** A clock the test moves by hand. */
internal class MutableClock(var now: Instant = NOW) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/** A small valid RSS document with one item per GUID given. */
internal fun rssOf(vararg guids: String, title: String = "Test feed", pubDate: (Int) -> String? = { null }): String = buildString {
    append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>$title</title><link>https://site.example/</link>")
    guids.forEachIndexed { index, guid ->
        append("<item><title>Post $guid</title><guid isPermaLink=\"false\">$guid</guid><link>https://site.example/$guid</link>")
        pubDate(index)?.let { append("<pubDate>$it</pubDate>") }
        append("<description>Summary of $guid</description></item>")
    }
    append("</channel></rss>")
}

internal fun response(body: String = "", code: Int = 200, vararg headers: Pair<String, String>): mockwebserver3.MockResponse =
    mockwebserver3.MockResponse.Builder().code(code).body(body)
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

internal fun response(body: ByteArray, vararg headers: Pair<String, String>): mockwebserver3.MockResponse =
    mockwebserver3.MockResponse.Builder().code(200).body(okio.Buffer().write(body))
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

/** Serves [routes] by path; anything else is a 404. Handlers may change between requests. */
internal class Routes(private val routes: MutableMap<String, () -> mockwebserver3.MockResponse> = java.util.concurrent.ConcurrentHashMap()) :
    mockwebserver3.Dispatcher() {
    val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    operator fun set(path: String, handler: () -> mockwebserver3.MockResponse) {
        routes[path] = handler
    }

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    fun clear() {
        routes.clear()
        hits.clear()
    }

    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
        val path = request.url.encodedPath
        hits.getOrPut(path) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        return routes[path]?.invoke() ?: response("Not found", 404)
    }
}

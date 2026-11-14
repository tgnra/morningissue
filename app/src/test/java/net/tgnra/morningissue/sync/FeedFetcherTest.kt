package net.tgnra.morningissue.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedFetcherTest {
    private val server = MockWebServer()
    private val fetcher = FeedFetcher(OkHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun successCarriesBodyValidatorsAndType() = runBlocking<Unit> {
        server.enqueue(
            response(
                rssOf("a"), 200,
                "ETag" to "\"v1\"", "Last-Modified" to "Mon, 09 Nov 2026 10:00:00 GMT", "Content-Type" to "Application/RSS+XML; charset=utf-8",
            ),
        )
        val result = fetcher.fetch(url("/feed")) as FetchResult.Success

        assertEquals(200, result.status)
        assertEquals(rssOf("a"), result.text())
        assertEquals("\"v1\"", result.etag)
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", result.lastModified)
        assertEquals("application/rss+xml", result.contentType)
        assertEquals(Charsets.UTF_8, result.charset)
        assertEquals(url("/feed"), result.finalUrl)
        assertNull(result.permanentUrl)

        val request = server.takeRequest()
        assertNull(request.headers["If-None-Match"])
        assertNull(request.headers["If-Modified-Since"])
        assertTrue(request.headers["Accept"]!!.contains("application/rss+xml"))
        assertTrue(request.headers["User-Agent"]!!.startsWith("MorningIssue"))
    }

    @Test
    fun conditionalRequestAnswered304() = runBlocking<Unit> {
        server.enqueue(response(code = 304))
        val result = fetcher.fetch(url("/feed"), etag = "\"v1\"", lastModified = "Mon, 09 Nov 2026 10:00:00 GMT")

        assertEquals(FetchResult.NotModified(null), result)
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.headers["If-None-Match"])
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", request.headers["If-Modified-Since"])
    }

    @Test
    fun httpErrorsAreValues() = runBlocking<Unit> {
        for (code in listOf(404, 410, 429, 500, 503)) {
            server.enqueue(response("gone", code))
            assertEquals(FetchResult.HttpError(code), fetcher.fetch(url("/feed")))
        }
    }

    @Test
    fun networkFailuresAreValues() = runBlocking<Unit> {
        val deadUrl = url("/feed")
        server.close()
        assertTrue(fetcher.fetch(deadUrl) is FetchResult.NetworkError)
        assertTrue(fetcher.fetch("http://no-such-host.invalid/feed") is FetchResult.NetworkError)
        assertTrue(fetcher.fetch("not a url") is FetchResult.NetworkError)
        assertTrue(fetcher.fetch("ftp://example.com/feed") is FetchResult.NetworkError)
    }

    @Test
    fun permanentRedirectIsReportedAndTemporaryIsNot() = runBlocking<Unit> {
        server.enqueue(response(code = 301, headers = arrayOf("Location" to "/moved")))
        server.enqueue(response(rssOf("a")))
        val permanent = fetcher.fetch(url("/old")) as FetchResult.Success
        assertEquals(url("/moved"), permanent.permanentUrl)
        assertEquals(url("/moved"), permanent.finalUrl)

        server.enqueue(response(code = 302, headers = arrayOf("Location" to "/elsewhere")))
        server.enqueue(response(rssOf("a")))
        val temporary = fetcher.fetch(url("/old")) as FetchResult.Success
        assertNull(temporary.permanentUrl)
        assertEquals(url("/elsewhere"), temporary.finalUrl)

        // 308 then 302: permanent only as far as the first temporary hop.
        server.enqueue(response(code = 308, headers = arrayOf("Location" to "/new-home")))
        server.enqueue(response(code = 302, headers = arrayOf("Location" to "/cdn-today")))
        server.enqueue(response(rssOf("a")))
        val mixed = fetcher.fetch(url("/old")) as FetchResult.Success
        assertEquals(url("/new-home"), mixed.permanentUrl)
        assertEquals(url("/cdn-today"), mixed.finalUrl)
    }

    @Test
    fun bodyOverTheCapIsRefused() = runBlocking<Unit> {
        server.enqueue(response("x".repeat(5000)))
        val result = fetcher.fetch(url("/feed"), maxBytes = 1000)
        assertEquals(FetchResult.NetworkError("The document is too large"), result)

        server.enqueue(response("x".repeat(1000)))
        assertTrue(fetcher.fetch(url("/feed"), maxBytes = 1000) is FetchResult.Success)
    }

    @Test
    fun charsetComesFromBomThenXmlDeclarationThenHeader() = runBlocking<Unit> {
        val declared = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss><channel><title>Café</title></channel></rss>"
        // The header wrongly says UTF-8; the document knows better.
        server.enqueue(response(declared.toByteArray(Charsets.ISO_8859_1), "Content-Type" to "text/xml; charset=utf-8"))
        assertEquals(declared, (fetcher.fetch(url("/a")) as FetchResult.Success).text())

        val undeclared = "<rss><channel><title>Café</title></channel></rss>"
        server.enqueue(response(undeclared.toByteArray(Charsets.ISO_8859_1), "Content-Type" to "text/xml; charset=iso-8859-1"))
        assertEquals(undeclared, (fetcher.fetch(url("/b")) as FetchResult.Success).text())

        server.enqueue(response(undeclared.toByteArray(Charsets.UTF_8)))
        assertEquals(undeclared, (fetcher.fetch(url("/c")) as FetchResult.Success).text())

        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        server.enqueue(response(bom + undeclared.toByteArray(Charsets.UTF_8), "Content-Type" to "text/xml; charset=iso-8859-1"))
        assertEquals(undeclared, (fetcher.fetch(url("/d")) as FetchResult.Success).text())
    }
}

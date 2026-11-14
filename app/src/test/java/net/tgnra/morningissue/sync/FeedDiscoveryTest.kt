package net.tgnra.morningissue.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedDiscoveryTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val discovery = DefaultFeedDiscovery(FeedFetcher(OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()), MutableClock())

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()

    private fun page(head: String) = "<!DOCTYPE html><html><head><title>Example Site</title>$head</head><body><p>Hello</p></body></html>"

    private suspend fun found(input: String): List<DiscoveredFeed> = (discovery.discover(input) as DiscoveryResult.Found).feeds

    @Test
    fun aFeedAddressIsReturnedAsIs() = runBlocking<Unit> {
        // Served with the wrong Content-Type: detection goes by content.
        routes["/feed.xml"] = { response(rssOf("a", title = "Direct Feed"), 200, "Content-Type" to "text/html") }
        assertEquals(
            listOf(DiscoveredFeed(url("/feed.xml"), "Direct Feed", "https://site.example/", null)),
            found(url("/feed.xml")),
        )

        routes["/feed.json"] = { response(Fixtures.text("feed.json"), 200, "Content-Type" to "application/json") }
        val json = found(url("/feed.json")).single()
        assertEquals("JSON Example", json.title)
        assertEquals("https://json.example.net/icon.png", json.iconUrl)
    }

    @Test
    fun feedsDeclaredByAnHtmlPage() = runBlocking<Unit> {
        routes["/blog/"] = {
            response(
                page(
                    """
                    <link rel="stylesheet" href="/style.css">
                    <link rel="icon" href="/favicon.png">
                    <link rel="apple-touch-icon" href="/touch.png">
                    <link rel="alternate" type="application/rss+xml" title="Comments Feed" href="/comments/feed">
                    <link rel="ALTERNATE" type="application/atom+xml" title="Posts" href="atom.xml">
                    <link rel="alternate" type="application/rss+xml" title="Dead" href="/dead.xml">
                    <link rel="alternate" type="text/html" hreflang="de" href="/de/">
                    <link rel="alternate" type="application/feed+json" href="/feed.json">
                    """,
                ),
                200, "Content-Type" to "text/html; charset=utf-8",
            )
        }
        routes["/comments/feed"] = { response(rssOf("c", title = "Comments on Example")) }
        routes["/blog/atom.xml"] = { response(Fixtures.text("atom.xml")) }
        routes["/feed.json"] = { response(rssOf("j", title = "")) }

        val feeds = found(url("/blog/"))
        // The dead link and the translated page are left out; the comments feed comes last.
        assertEquals(listOf(url("/blog/atom.xml"), url("/feed.json"), url("/comments/feed")), feeds.map { it.url })
        val posts = feeds[0]
        assertEquals("Field & Stream Notes", posts.title)
        assertEquals("https://blog.example.org/", posts.siteUrl)
        assertEquals(url("/touch.png"), posts.iconUrl)
        // A feed without a title takes the page's.
        assertEquals("Example Site", feeds[1].title)
    }

    @Test
    fun bareDomainWithoutScheme() = runBlocking<Unit> {
        routes["/"] = { response(page("""<link rel="alternate" type="application/rss+xml" href="/rss.xml">""")) }
        routes["/rss.xml"] = { response(rssOf("a", title = "Bare Domain Feed")) }

        // No scheme: https is tried first (nothing answers TLS here), then http.
        val feeds = found("${server.hostName}:${server.port}")
        assertEquals(listOf(DiscoveredFeed(url("/rss.xml"), "Bare Domain Feed", "https://site.example/", null)), feeds)
        assertEquals(feeds, found("  ${server.hostName}:${server.port}/  "))
    }

    @Test
    fun commonPathsAreProbedWhenThePageDeclaresNothing() = runBlocking<Unit> {
        routes["/about/team"] = { response(page("")) }
        routes["/index.xml"] = { response(rssOf("a", title = "Hugo Site")) }
        routes["/rss.xml"] = { response(rssOf("a", title = "Also here")) }
        routes["/feed"] = { response(page("")) }

        val feeds = found(url("/about/team"))
        // The first common path that really is a feed; /feed answered with a page.
        assertEquals(listOf(url("/index.xml")), feeds.map { it.url })
        assertEquals("Hugo Site", feeds.single().title)
        for (path in listOf("/feed", "/rss", "/atom.xml", "/index.xml", "/feed.xml", "/rss.xml")) {
            assertEquals(path, 1, routes.hits(path))
        }
    }

    @Test
    fun commonPathsAreProbedWhenThePageIsMissing() = runBlocking<Unit> {
        routes["/atom.xml"] = { response(Fixtures.text("atom.xml")) }
        assertEquals(listOf(url("/atom.xml")), found(url("/no/such/page")).map { it.url })
    }

    @Test
    fun permanentlyMovedFeedIsReturnedAtItsNewAddress() = runBlocking<Unit> {
        routes["/old.xml"] = { response(code = 301, headers = arrayOf("Location" to "/new.xml")) }
        routes["/new.xml"] = { response(rssOf("a")) }
        assertEquals(url("/new.xml"), found(url("/old.xml")).single().url)
    }

    @Test
    fun pageWithoutAnyFeed() = runBlocking<Unit> {
        routes["/"] = { response(page("")) }
        assertEquals(DiscoveryResult.NoFeed, discovery.discover(url("/")))
    }

    @Test
    fun failuresHaveAReason() = runBlocking<Unit> {
        assertTrue(discovery.discover("") is DiscoveryResult.Failed)
        assertTrue(discovery.discover("two words") is DiscoveryResult.Failed)
        assertTrue(discovery.discover("no-such-host.invalid") is DiscoveryResult.Failed)
        // Nothing at the address and nothing at the common paths.
        val missing = discovery.discover(url("/gone")) as DiscoveryResult.Failed
        assertTrue(missing.message, "404" in missing.message)
    }
}

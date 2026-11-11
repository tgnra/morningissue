package net.tgnra.morningissue.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.model.FeedUrls
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Repair of a failing feed against a local web server. */
@RunWith(RobolectricTestRunner::class)
class FeedRepairerTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val clock = MutableClock()
    private lateinit var repairer: FeedRepairer

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        val fetcher = FeedFetcher(OkHttpClient())
        repairer = FeedRepairer(fetcher, DefaultFeedDiscovery(fetcher, clock), clock)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun page(vararg feedLinks: String) = response(
        "<html><head><title>Site</title>" +
            feedLinks.joinToString("") { "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"$it\">" } +
            "</head><body>Hello</body></html>",
        200,
        "Content-Type" to "text/html",
    )

    @Test
    fun aFeedThatAnswersAgainHasRecovered() = runBlocking {
        routes["/old.xml"] = { response(rssOf("a")) }
        assertEquals(RepairResult.Recovered, repairer.repair(url("/old.xml"), null))
        assertEquals("nothing else was tried", 1, routes.hits.size)
    }

    @Test
    fun theSitesOwnFeedLinkIsPreferred() = runBlocking {
        routes["/"] = { page("/blog/new-feed.xml") }
        routes["/blog/new-feed.xml"] = { response(rssOf("a")) }
        // A feed at a common path as well: the site's own word wins.
        routes["/feed"] = { response(rssOf("b")) }

        assertEquals(RepairResult.Relocated(url("/blog/new-feed.xml")), repairer.repair(url("/old.xml"), url("/")))
        assertEquals(1, routes.hits("/old.xml"))
    }

    @Test
    fun commonAddressesAreTriedInOrder() = runBlocking {
        for (path in listOf("/feed", "/rss", "/atom.xml", "/index.xml")) {
            routes.clear()
            routes[path] = { response(rssOf("a")) }
            assertEquals(path, RepairResult.Relocated(url(path)), repairer.repair(url("/gone/feed.xml"), null))
        }
        // Several answer: the first of the list is taken.
        routes.clear()
        routes["/index.xml"] = { response(rssOf("a")) }
        routes["/rss"] = { response(rssOf("b")) }
        assertEquals(RepairResult.Relocated(url("/rss")), repairer.repair(url("/gone/feed.xml"), null))
    }

    @Test
    fun aPageThatIsNotAFeedDoesNotCount() = runBlocking {
        // Every address answers 200, with a web page.
        for (path in listOf("/old.xml", "/", "/feed", "/rss", "/atom.xml", "/index.xml", "/feed.xml", "/rss.xml")) {
            routes[path] = { page() }
        }
        assertEquals(RepairResult.Failed, repairer.repair(url("/old.xml"), url("/")))
    }

    @Test
    fun nothingAnsweringIsAFailureAfterEveryAlternativeWasTried() = runBlocking {
        assertEquals(RepairResult.Failed, repairer.repair(url("/old.xml"), null))
        for (path in listOf("/old.xml", "/", "/feed", "/rss", "/atom.xml", "/index.xml")) {
            assertTrue("$path was tried", routes.hits(path) >= 1)
        }
    }

    @Test
    fun aFeedIsNotRepairedOntoAnotherSubscriptionsAddress() = runBlocking {
        routes["/feed"] = { response(rssOf("a")) }
        val taken = setOf(FeedUrls.normalize(url("/feed")))
        assertEquals(RepairResult.Failed, repairer.repair(url("/old.xml"), null, taken))
    }

    @Test
    fun theOtherSchemeAndWwwAreTriedForARealHost() {
        // No network here: just the list of addresses, through the https and www variants.
        val variants = FeedRepairer.variants("http://example.com/blog/feed.xml")
        assertEquals(
            listOf(
                "http://example.com/blog/feed.xml",
                "https://example.com/blog/feed.xml",
                "http://www.example.com/blog/feed.xml",
                "https://www.example.com/blog/feed.xml",
            ),
            variants,
        )
        assertEquals(
            listOf("https://www.example.com/feed", "http://www.example.com/feed", "https://example.com/feed", "http://example.com/feed"),
            FeedRepairer.variants("https://www.example.com/feed"),
        )
        assertEquals(
            "an address has no www form, and keeps its port",
            listOf("http://127.0.0.1:8080/feed", "https://127.0.0.1:8080/feed"),
            FeedRepairer.variants("http://127.0.0.1:8080/feed"),
        )
    }
}

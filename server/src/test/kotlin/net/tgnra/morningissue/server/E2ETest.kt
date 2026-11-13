package net.tgnra.morningissue.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobResult
import net.tgnra.morningissue.api.JobState
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.api.LoginDevice
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.api.client.OkHttpMorningIssueApi
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.server.config.RefreshConfig
import net.tgnra.morningissue.server.config.ServerConfig
import net.tgnra.morningissue.server.importer.Archive
import net.tgnra.morningissue.sync.MutableClock
import net.tgnra.morningissue.sync.Routes
import net.tgnra.morningissue.sync.response
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The whole server end to end (`docs/server-design.md`, section 8, Phase 3 step 4): the real
 * [ServerContainer] behind Ktor on a random port, a temporary data dir, a MockWebServer as the
 * internet, and core's OkHttp client of the API as the phone and the Kobo.
 */
class E2ETest {
    private val internet = MockWebServer()
    private val routes = Routes()
    private lateinit var dataDir: File
    private val clock = MutableClock(Instant.parse("2026-11-12T08:00:00Z"))
    private val http = OkHttpClient()
    private var container: ServerContainer? = null
    private var server: EmbeddedServer<*, *>? = null

    @Before
    fun setUp() {
        dataDir = Files.createTempDirectory("e2e").toFile()
        internet.dispatcher = routes
        internet.start()
    }

    @After
    fun tearDown() {
        server?.stop(100, 1000)
        container?.close()
        internet.close()
        dataDir.deleteRecursively()
    }

    private fun config(users: List<String>, refresh: RefreshConfig = RefreshConfig()) = ServerConfig(
        listen = "127.0.0.1",
        port = 0,
        publicUrl = "https://news.example.org",
        dataDir = dataDir,
        users = users,
        refresh = refresh,
    )

    /** Starts the container and Ktor as `serve` does (without the scheduler's own ticking); returns the base URL. */
    private fun serve(config: ServerConfig): String {
        val c = ServerContainer(config, clock).also { container = it }
        runBlocking { c.services.auth.bootstrapUsers(config.users) }
        val s = embeddedServer(CIO, port = 0, host = "127.0.0.1") { morningIssue(c.services, httpOptions(config)) }
        server = s
        s.start(wait = false)
        val port = runBlocking { s.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port"
    }

    // -- The internet ---------------------------------------------------------------------

    private val rfc1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    /** Serves a feed at [path] with a post per GUID, each with a page and a picture, published an hour apart before now. */
    private fun serveFeed(path: String, title: String, guids: List<String>) {
        val base = internet.url("/").toString().trimEnd('/')
        val publishedAt = clock.now
        val rss = buildString {
            append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>$title</title><link>$base/</link>")
            guids.forEachIndexed { i, guid ->
                append("<item><title>$title story $guid about lighthouses</title><guid isPermaLink=\"false\">$guid</guid>")
                append("<link>$base/posts/$guid.html</link>")
                append("<pubDate>${rfc1123.format(publishedAt.minus(Duration.ofHours(i + 1L)))}</pubDate>")
                append("<description>${"A paragraph about lighthouses and the keepers who kept them, story $guid. ".repeat(12)}</description>")
                append("<enclosure url=\"$base/img/$guid.png\" type=\"image/png\" length=\"1000\"/></item>")
            }
            append("</channel></rss>")
        }
        routes[path] = { response(rss, 200, "Content-Type" to "application/rss+xml") }
        for (guid in guids) {
            routes["/posts/$guid.html"] = {
                response(
                    "<html><head><title>$guid</title></head><body><article><h1>Story $guid</h1>" +
                        "<p>${"The full text of the story, which is longer than the feed's summary. ".repeat(40)}</p></article></body></html>",
                    200,
                    "Content-Type" to "text/html",
                )
            }
            routes["/img/$guid.png"] = { response(PNG, "Content-Type" to "image/png") }
        }
    }

    private suspend fun MorningIssueApi.awaitJob(jobId: String): JobDto = withTimeout(60_000) {
        while (true) {
            val job = job(jobId)
            if (job.state != JobState.RUNNING) return@withTimeout job
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun at(time: String) = clock.now.toString().substringBefore('T').let { Instant.parse("${it}T$time:00Z") }

    // -- The phone and the Kobo -----------------------------------------------------------

    @Test
    fun aPhoneSetsUpAnAccountPrintsSyncsAndPairsAKobo() = runBlocking {
        val url = serve(config(listOf("alice")))

        // The password file the bootstrap wrote, then the first login and the lockout.
        val passwordFile = File(dataDir, "initial-passwords/alice.txt")
        assertTrue(passwordFile.isFile)
        val generated = passwordFile.readText().trim()
        val phone = OkHttpMorningIssueApi(url, http)
        assertTrue(phone.health().ok)
        val login = phone.login(LoginRequest("alice", generated, LoginDevice(UUID.randomUUID().toString(), "Pixel")))
        assertTrue(login.mustChangePassword)
        try {
            phone.settings()
            fail("settings answered before the password was changed")
        } catch (_: ApiException.PasswordChangeRequired) {
        }
        phone.changePassword(ChangePasswordRequest(generated, "correct horse battery"))
        assertEquals("UTC", phone.patchSettings(SettingsPatch(timeZone = "UTC")).timeZone)

        // Import the app's archive (feeds pointed at the test internet), and again with the same key.
        val library = Archive.library()
        val archive = library.copy(feeds = library.feeds.map { it.copy(url = internet.url("/archive/${it.n}.xml").toString()) })
        val file = File(dataDir, "library.zip").apply { writeBytes(archive.bytes()) }
        val key = UUID.randomUUID().toString()
        val started = phone.import(file, ImportMode.REPLACE, key)
        val imported = phone.awaitJob(checkNotNull(started.jobId))
        assertEquals(JobState.DONE, imported.state)
        assertEquals(2, phone.feeds().feeds.size)
        assertNotNull("the same key answers the earlier report", phone.import(file, ImportMode.REPLACE, key).report)
        assertEquals(3, phone.editions().editions.size)

        // Add a feed: its first fetch happens on the way.
        serveFeed("/feed.xml", "The Lighthouse", listOf("a", "b", "c"))
        val feed = phone.addFeed(AddFeedRequest(internet.url("/feed.xml").toString()))
        assertEquals("The Lighthouse", feed.title)
        assertEquals(1, routes.hits("/feed.xml"))
        assertEquals(3, phone.feeds().feeds.size)

        // Print now.
        val print = phone.awaitJob(phone.print().jobId)
        assertEquals(JobState.DONE, print.state)
        val printedId = checkNotNull(print.outcome?.editionId)
        assertEquals(JobResult.PRINTED, print.outcome!!.result)
        assertEquals(printedId, phone.editions().editions.first().id)

        // The gate: events the server has not seen hold the manifest back.
        try {
            phone.manifest(eventsThrough = 2)
            fail("the manifest answered with events pending")
        } catch (e: ApiException.EventsPending) {
            assertEquals(0, e.acked)
        }
        val bundleBefore = phone.bundle(printedId, eventsThrough = 0)
        val story = bundleBefore.pages.flatMap { it.items }.first { bundleBefore.articles.getValue(it.articleId.toString()).feedTitle == "The Lighthouse" }.articleId
        val now = clock.now.toString()
        val acked = phone.postEvents(
            EventsRequest(
                listOf(
                    SyncEvent(1, "opened", articleId = story, at = now),
                    SyncEvent(2, "liked", articleId = story, value = JsonPrimitive(true), at = now),
                ),
            ),
        )
        assertEquals(2, acked.acked)
        val manifest = phone.manifest(eventsThrough = 2)
        assertEquals(login.user.dbId, manifest.dbId)
        assertEquals(printedId, manifest.editions.first().id)
        val engagement = phone.bundle(printedId, eventsThrough = 2).articles.getValue(story.toString()).engagement
        assertNotNull(engagement.likedAt)

        // Pair a Kobo with a version 2 code; it then syncs with its own token.
        val secret = ByteArray(32) { it.toByte() }
        val kobo = OkHttpMorningIssueApi(url, http, token = KOBO_TOKEN)
        try {
            kobo.koboHello()
            fail("an unpaired Kobo was answered")
        } catch (e: ApiException.Unauthenticated) {
            assertEquals("unknown", e.code)
        }
        val paired = phone.pairKobo("morningissue:pair?v=2&s=${b64url(secret)}&d=kobo-e2e&n=Kobo%20Libra%202&w=1264")
        assertEquals(DeviceKind.KOBO, paired.device.kind)
        val hello = kobo.koboHello()
        assertEquals("alice", hello.username)
        assertEquals(login.user.dbId, hello.dbId)
        try {
            kobo.feeds()
            fail("a Kobo token was answered on a phone endpoint")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
        val koboManifest = kobo.manifest(eventsThrough = 0)
        assertEquals(manifest.editions.map { it.id to it.version }, koboManifest.editions.map { it.id to it.version })
        val bundle = kobo.bundle(printedId, eventsThrough = 0)
        val pictures = bundle.articles.values.mapNotNull { it.imageUrl }.filter { it.startsWith(internet.url("/img/").toString()) }
        assertTrue("the printed stories carry their pictures", pictures.isNotEmpty())
        val zip = kobo.koboImages(KoboImagesRequest(width = 1264, urls = pictures), eventsThrough = 0)
        val entries = HashMap<String, Int>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes().size
            }
        }
        assertTrue("images.json in ${entries.keys}", "images.json" in entries)
        assertEquals(pictures.size, entries.keys.count { it.startsWith("images/") })
        assertTrue(entries.filterKeys { it.startsWith("images/") }.values.all { it > 0 })
    }

    // -- Two users, one feed --------------------------------------------------------------

    @Test
    fun twoUsersSharingAFeedHaveItFetchedOnceBeforeBothPrintsWithTheTwoHourLeeway() = runBlocking {
        // The periodic refresh is kept out of the way, so every fetch below is a pre-print one.
        clock.now = Instant.parse("2026-11-12T04:30:00Z")
        serve(config(listOf("alice", "bob"), RefreshConfig(intervalMinutes = 100_000)))
        val c = container!!
        val users = listOf("alice", "bob").map { c.database.accountDao().userByName(it)!!.id }
        val feedUrl = internet.url("/shared.xml").toString()
        serveFeed("/shared.xml", "Shared", listOf("s1", "s2", "s3"))

        // 04:30: Alice adds the feed (its first fetch), then Bob (already fetched: no request).
        for (user in users) {
            c.services.settings.patch(user, SettingsPatch(timeZone = "UTC"))
            c.services.feeds.add(user, AddFeedRequest(feedUrl))
        }
        assertEquals(1, routes.hits("/shared.xml"))
        c.scheduler.restore()

        // 06:00, half an hour before the 06:30 release: both are built, and the feed, fetched an
        // hour and a half ago, is within the leeway, so it is not fetched again.
        clock.now = at("06:00")
        c.scheduler.tick().joinAll()
        assertEquals(1, routes.hits("/shared.xml"))
        for (user in users) {
            val edition = c.database.editionDao().getRecent(user, 1).single()
            assertEquals(Instant.parse("2026-11-12T06:30:00Z"), edition.releaseAt)
            val items = c.database.editionDao().getItems(user, edition.id)
            assertTrue("user $user's edition prints the shared feed", items.any { it.row.feedTitle == "Shared" })
        }

        // The next morning the feed was last fetched a day ago: one fetch serves both builds.
        clock.now = Instant.parse("2026-11-13T05:00:00Z")
        serveFeed("/shared.xml", "Shared", listOf("s4", "s5", "s1", "s2", "s3"))
        clock.now = Instant.parse("2026-11-13T06:00:00Z")
        c.scheduler.tick().joinAll()
        assertEquals(2, routes.hits("/shared.xml"))
        for (user in users) {
            val edition = c.database.editionDao().getRecent(user, 1).single()
            assertEquals(Instant.parse("2026-11-13T06:30:00Z"), edition.releaseAt)
            val guids = c.database.editionDao().getItems(user, edition.id).map { it.row.article.guid }
            assertTrue("user $user's edition has the new posts: $guids", guids.any { it == "s4" || it == "s5" })
        }
    }

    private companion object {
        /** `docs/server-fixtures/kobo-token.json`: the token of the secret 0x00..0x1f. */
        const val KOBO_TOKEN = "gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc"

        fun b64url(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        val PNG: ByteArray by lazy {
            val image = BufferedImage(640, 420, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            for (x in 0 until 640 step 20) {
                g.color = Color(x % 256, (x * 3) % 256, 200)
                g.fillRect(x, 0, 20, 420)
            }
            g.dispose()
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }
}

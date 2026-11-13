package net.tgnra.morningissue.api.client

import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.LoginDevice
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.api.SearchQuery
import net.tgnra.morningissue.api.SearchWhen
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.model.EditionLength
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OkHttpMorningIssueApiTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var api: OkHttpMorningIssueApi

    @Before
    fun setUp() {
        server.start()
        // A path prefix, as behind a reverse proxy.
        api = OkHttpMorningIssueApi(server.url("/mi/").toString(), client)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private inline fun <reified E : ApiException> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: ApiException) {
            if (e is E) return e
            fail("Expected ${E::class.simpleName}, got $e")
        }
        fail("Expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun loginTakesTheTokenAndSendsItAfterwards() = runBlocking {
        server.enqueue(json("""{"token":"t0k","deviceId":"d1","mustChangePassword":true,"user":{"username":"tania","dbId":"db"}}"""))
        server.enqueue(json("""{"username":"tania","dbId":"db","mustChangePassword":true,"hasData":false,"settingsCustomized":false}"""))

        val login = api.login(LoginRequest("tania", "secret", LoginDevice("d1", "Pixel")))
        assertTrue(login.mustChangePassword)
        assertEquals("t0k", api.token)
        val account = api.account()
        assertFalse(account.hasData)

        val first = server.takeRequest()
        assertEquals("/mi/api/v1/auth/login", first.url.encodedPath)
        assertEquals("POST", first.method)
        assertNull(first.headers["Authorization"])
        assertTrue(first.body!!.utf8().contains("\"kind\":\"PHONE\""))
        val second = server.takeRequest()
        assertEquals("/mi/api/v1/account", second.url.encodedPath)
        assertEquals("Bearer t0k", second.headers["Authorization"])
    }

    @Test
    fun gatedCallsCarryTheEventsHeaderAndARefusalSaysWhatWasAcked() = runBlocking {
        server.enqueue(json("""{"error":"events_pending","message":"upload first","acked":41}""", 409))
        val pending = assertThrows<ApiException.EventsPending> { runBlocking { api.manifest(eventsThrough = 44, editions = 10) } }
        assertEquals(41L, pending.acked)
        val request = server.takeRequest()
        assertEquals("44", request.headers[EVENTS_THROUGH_HEADER])
        assertEquals("10", request.url.queryParameter("editions"))

        server.enqueue(
            json(
                """{"v":1,"protocol":"server/1","dbId":"db","serverTime":"2026-11-11T06:30:00Z","editions":[
                   {"id":7,"date":"2026-11-11","kind":"MORNING","number":3,"releaseAt":"2026-11-11T06:30:00Z","version":"abc","extra":1}],
                   "saved":{"count":0,"version":"s0"},"acked":{"d1":44}}""",
            ),
        )
        val manifest = api.manifest(eventsThrough = 44, editions = 10)
        assertEquals(LocalDate.of(2026, 11, 11), manifest.editions.single().date)
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), manifest.serverTime)
    }

    @Test
    fun errorsAreTyped() = runBlocking {
        server.enqueue(json("""{"error":"unauthenticated","message":"no"}""", 401))
        assertThrows<ApiException.Unauthenticated> { runBlocking { api.feeds() } }
        server.enqueue(json("""{"error":"password_change_required","message":"change it"}""", 403))
        assertThrows<ApiException.PasswordChangeRequired> { runBlocking { api.feeds() } }
        server.enqueue(json("""{"error":"not_a_feed","message":"nothing there"}""", 422))
        val notAFeed = assertThrows<ApiException.Http> { runBlocking { api.discoverFeeds("example.org") } }
        assertEquals(ApiError.NOT_A_FEED, notAFeed.code)
        assertEquals(422, notAFeed.status)
        // A proxy's own error page.
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad gateway</html>").build())
        val proxy = assertThrows<ApiException.Http> { runBlocking { api.stand() } }
        assertEquals(502, proxy.status)
        // A 200 that is not the answer asked for.
        server.enqueue(json("""{"nonsense":true}"""))
        val unreadable = assertThrows<ApiException.Http> { runBlocking { api.stand() } }
        assertEquals(OkHttpMorningIssueApi.BAD_RESPONSE, unreadable.code)
    }

    @Test
    fun noServerIsOffline() = runBlocking {
        val port = server.port
        server.close()
        val gone = OkHttpMorningIssueApi("http://127.0.0.1:$port", client)
        val offline = assertThrows<ApiException.Offline> { runBlocking { gone.health() } }
        assertEquals(0, offline.status)
    }

    @Test
    fun importStreamsTheArchiveWithItsKey() = runBlocking {
        val file = File.createTempFile("legacy", ".zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4, 1, 2, 3)) }
        try {
            server.enqueue(json("""{"jobId":"j1"}""", 202))
            assertEquals("j1", api.import(file, ImportMode.MERGE, "key-1").jobId)
            server.enqueue(json("""{"report":{"feeds":2,"posts":10,"skippedPosts":0,"editions":1,"skippedEditions":0,"settingsApplied":true}}"""))
            assertEquals(10, api.import(file, ImportMode.MERGE, "key-1").report!!.posts)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("merge", request.url.queryParameter("mode"))
                assertEquals("key-1", request.headers[IDEMPOTENCY_KEY_HEADER])
                assertEquals("application/zip", request.headers["Content-Type"])
                assertEquals(7L, request.bodySize)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun aPatchSendsOnlyWhatIsSet() = runBlocking {
        val settings = """{"resetTime":"06:30","editionLength":"LONG","eveningEnabled":false,"eveningTime":"18:00","timeZone":"Europe/London"}"""
        server.enqueue(json(settings))
        server.enqueue(json(settings))
        api.patchSettings(SettingsPatch(editionLength = EditionLength.LONG))
        api.patchSettings(SettingsPatch(extraOfferDismissedAt = Patch.Set(null)))
        val first = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertFalse(first.body!!.utf8().contains("extraOfferDismissedAt"))
        assertTrue(server.takeRequest().body!!.utf8().contains("\"extraOfferDismissedAt\":null"))
    }

    @Test
    fun searchAndPairingSendTheirFields() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))
        api.search(SearchQuery("marmalade jam", SearchWhen.WEEK, saved = true, tagId = 3))
        val search = server.takeRequest().url
        assertEquals("marmalade jam", search.queryParameter("q"))
        assertEquals("WEEK", search.queryParameter("when"))
        assertEquals("true", search.queryParameter("saved"))
        assertEquals("3", search.queryParameter("tagId"))
        assertNull(search.queryParameter("liked"))

        server.enqueue(json("""{"device":{"id":"b7e2","kind":"KOBO","name":"Kobo Libra 2","width":1264,"createdAt":"2026-11-11T06:30:00Z"}}""", 201))
        val paired = api.pairKobo("morningissue:pair?v=2&s=abc&d=b7e2&n=Kobo&w=1264")
        assertEquals(1264, paired.device.width)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"pairingUri\":\"morningissue:pair?v=2"))
    }

    @Test
    fun logoutForgetsTheToken() = runBlocking {
        api.token = "t0k"
        server.enqueue(MockResponse.Builder().code(204).build())
        api.logout()
        assertNull(api.token)
        assertEquals("Bearer t0k", server.takeRequest().headers["Authorization"])
    }
}

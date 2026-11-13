package net.tgnra.morningissue.server.http

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeFully
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiErrorBody
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.EVENTS_THROUGH_HEADER
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.ImportResponse
import net.tgnra.morningissue.server.morningIssue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Every route of section 4 against fake services: statuses, error bodies, auth scopes, the lockout, the gate and body limits. */
class RoutesTest {
    private val fakes = FakeServices()
    private lateinit var importsDir: File

    @Before
    fun setUp() {
        importsDir = Files.createTempDirectory("imports-test").toFile()
    }

    @After
    fun tearDown() {
        importsDir.deleteRecursively()
    }

    private fun api(
        options: HttpOptions = HttpOptions(importsDir = importsDir, importMaxBytes = 64 * 1024),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { morningIssue(fakes.services(), options) }
        block()
    }

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        token: String? = TestTokens.PHONE,
        body: Any? = null,
        through: Long? = null,
        headers: Map<String, String> = emptyMap(),
        type: ContentType = ContentType.Application.Json,
    ): HttpResponse = request(ApiPaths.BASE + path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        through?.let { header(EVENTS_THROUGH_HEADER, it.toString()) }
        headers.forEach { (k, v) -> header(k, v) }
        when (body) {
            null -> {}
            is String -> {
                contentType(type)
                setBody(body)
            }
            is ByteArray -> {
                contentType(type)
                setBody(body)
            }
            else -> error("body")
        }
    }

    private suspend fun HttpResponse.error(): ApiErrorBody = ApiJson.json.decodeFromString(bodyAsText())

    private suspend fun assertError(response: HttpResponse, status: Int, code: String) {
        assertEquals("${response.call.request.url}: ${response.bodyAsText()}", status, response.status.value)
        assertEquals(code, response.error().error)
    }

    /** One endpoint: who may call it, a valid body, and what a phone gets. */
    private data class Endpoint(
        val method: HttpMethod,
        val path: String,
        val access: Access?,
        val body: String? = null,
        val gated: Boolean = false,
        val ok: HttpStatusCode = HttpStatusCode.OK,
    )

    private val pairUri = "morningissue:pair?v=2&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=kobo-1&n=Kobo&w=1264"

    private val endpoints = listOf(
        Endpoint(HttpMethod.Get, ApiPaths.HEALTH, null),
        Endpoint(HttpMethod.Post, ApiPaths.LOGIN, null, """{"username":"tania","password":"right","device":{"id":"p1","name":"Pixel 8","kind":"PHONE"}}"""),
        Endpoint(HttpMethod.Get, ApiPaths.KOBO_CONFIG, null),
        Endpoint(HttpMethod.Post, ApiPaths.PASSWORD, Access.ACCOUNT, """{"current":"a","new":"bbbbbbbbbbbb"}""", ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Post, ApiPaths.LOGOUT, Access.ACCOUNT, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Get, ApiPaths.ACCOUNT, Access.ACCOUNT),
        Endpoint(HttpMethod.Post, ApiPaths.SYNC_EVENTS, Access.KOBO, """{"events":[]}"""),
        Endpoint(HttpMethod.Get, ApiPaths.SYNC_MANIFEST, Access.KOBO, gated = true),
        Endpoint(HttpMethod.Get, ApiPaths.EDITIONS, Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.editionBundle(120), Access.KOBO, gated = true),
        Endpoint(HttpMethod.Get, ApiPaths.SAVED, Access.KOBO, gated = true),
        Endpoint(HttpMethod.Get, ApiPaths.ENGAGEMENT, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.editionDeskNotice(120), Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.STAND, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.PRINT, Access.PHONE, ok = HttpStatusCode.Accepted),
        Endpoint(HttpMethod.Post, ApiPaths.EDITIONS_EXTRA, Access.PHONE, """{"feedIds":[3]}""", ok = HttpStatusCode.Accepted),
        Endpoint(HttpMethod.Get, ApiPaths.job("job-print"), Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.SETTINGS, Access.PHONE),
        Endpoint(HttpMethod.Patch, ApiPaths.SETTINGS, Access.PHONE, """{"timeZone":"Europe/Paris"}"""),
        Endpoint(HttpMethod.Put, ApiPaths.SETTINGS_RELEASE, Access.PHONE, """{"resetTime":"07:00"}"""),
        Endpoint(HttpMethod.Get, ApiPaths.FEEDS, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.FEEDS_DISCOVER, Access.PHONE, """{"address":"example.org"}"""),
        Endpoint(HttpMethod.Post, ApiPaths.FEEDS, Access.PHONE, """{"url":"https://new.example/feed","tags":["a"]}""", ok = HttpStatusCode.Created),
        Endpoint(HttpMethod.Patch, ApiPaths.feed(3), Access.PHONE, """{"muteUntil":null}"""),
        Endpoint(HttpMethod.Delete, ApiPaths.feed(3), Access.PHONE, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Get, ApiPaths.feedArticles(3), Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.FEEDS_HEALTH, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.feedHealthDismiss(3), Access.PHONE, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Post, ApiPaths.feedHealthAcknowledge(3), Access.PHONE, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Post, ApiPaths.feedHealthRetry(3), Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.OPML, Access.PHONE, "<opml/>", ok = HttpStatusCode.Accepted),
        Endpoint(HttpMethod.Get, ApiPaths.OPML, Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.article(7), Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.articleFullText(7), Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.ARTICLES + "?shelf=saved", Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.SEARCH + "?q=kobo", Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.DISCOVERY_DECK, Access.PHONE),
        Endpoint(HttpMethod.Get, ApiPaths.DISCOVERY_TOPICS, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.DISCOVERY_TOPICS, Access.PHONE, """{"clusterIds":[1,2]}"""),
        Endpoint(HttpMethod.Get, ApiPaths.DISCOVERY_LIVE + "?url=https%3A%2F%2Fa.example%2Ffeed", Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.DISCOVERY_VERDICTS, Access.PHONE, """{"url":"https://a.example/feed","verdict":"PASSED"}"""),
        Endpoint(HttpMethod.Delete, ApiPaths.DISCOVERY_VERDICTS + "?url=https%3A%2F%2Fa.example%2Ffeed", Access.PHONE, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Get, ApiPaths.DISCOVERY_PASSED, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.DISCOVERY_OPEN, Access.PHONE, """{"feedUrl":"https://a.example/feed","articleUrl":"https://a.example/1"}"""),
        Endpoint(HttpMethod.Get, ApiPaths.DEVICES, Access.PHONE),
        Endpoint(HttpMethod.Post, ApiPaths.DEVICES_KOBO, Access.PHONE, """{"pairingUri":"$pairUri"}""", ok = HttpStatusCode.Created),
        Endpoint(HttpMethod.Delete, ApiPaths.device("kobo-1"), Access.PHONE, ok = HttpStatusCode.NoContent),
        Endpoint(HttpMethod.Get, ApiPaths.KOBO_HELLO, Access.KOBO),
        Endpoint(HttpMethod.Post, ApiPaths.KOBO_IMAGES, Access.KOBO, """{"width":1264,"urls":["https://a.example/1.jpg"]}""", gated = true),
    )

    @Test
    fun everyEndpointAnswersAPhoneWithItsStatus() = api {
        for (e in endpoints) {
            val r = client.call(e.method, e.path, body = e.body, through = if (e.gated) 0 else null, type = typeOf(e))
            assertEquals("${e.method.value} ${e.path}: ${r.bodyAsText()}", e.ok, r.status)
        }
    }

    private fun typeOf(e: Endpoint) = if (e.path == ApiPaths.OPML) ContentType.parse("text/x-opml") else ContentType.Application.Json

    @Test
    fun everyProtectedEndpointNeedsAToken() = api {
        for (e in endpoints.filter { it.access != null }) {
            assertError(client.call(e.method, e.path, token = null, body = e.body, through = 0, type = typeOf(e)), 401, ApiError.UNAUTHENTICATED)
            val wrong = client.call(e.method, e.path, token = "nonsense", body = e.body, through = 0, type = typeOf(e))
            // A Kobo polling while its code is shown hears "unknown"; elsewhere a stale token is "unauthenticated".
            assertError(wrong, 401, if (e.access == Access.KOBO) ApiError.UNKNOWN else ApiError.UNAUTHENTICATED)
        }
    }

    @Test
    fun aKoboTokenReachesOnlyTheKoboEndpoints() = api {
        for (e in endpoints.filter { it.access != null }) {
            val r = client.call(e.method, e.path, token = TestTokens.KOBO, body = e.body, through = if (e.gated) 0 else null, type = typeOf(e))
            if (e.access == Access.KOBO) {
                assertEquals("${e.method.value} ${e.path}: ${r.bodyAsText()}", e.ok, r.status)
            } else {
                assertError(r, 403, ApiError.FORBIDDEN_FOR_DEVICE)
            }
        }
    }

    @Test
    fun aGeneratedPasswordLocksEverythingButPasswordLogoutAndAccount() = api {
        for (e in endpoints.filter { it.access != null }) {
            val r = client.call(e.method, e.path, token = TestTokens.LOCKED, body = e.body, through = if (e.gated) 0 else null, type = typeOf(e))
            if (e.access == Access.ACCOUNT) {
                assertEquals("${e.method.value} ${e.path}", e.ok, r.status)
            } else {
                assertError(r, 403, ApiError.PASSWORD_CHANGE_REQUIRED)
            }
        }
        val account = Json.parseToJsonElement(client.call(HttpMethod.Get, ApiPaths.ACCOUNT, TestTokens.LOCKED).bodyAsText()).jsonObject
        assertEquals("true", account.getValue("mustChangePassword").jsonPrimitive.content)
    }

    @Test
    fun aDisabledAccountIsForbidden() = api {
        assertError(client.call(HttpMethod.Get, ApiPaths.FEEDS, TestTokens.DISABLED), 403, ApiError.ACCOUNT_DISABLED)
    }

    @Test
    fun eachAuthenticatedCallMarksTheDeviceSeen() = api {
        client.call(HttpMethod.Get, ApiPaths.FEEDS)
        client.call(HttpMethod.Get, ApiPaths.KOBO_HELLO, TestTokens.KOBO)
        client.call(HttpMethod.Get, ApiPaths.HEALTH, null)
        assertEquals(listOf("markSeen(phone-1)", "markSeen(kobo-1)"), fakes.calls.filter { it.startsWith("markSeen") })
    }

    @Test
    fun publicEndpointsAnswerWithoutAToken() = api {
        val health = Json.parseToJsonElement(client.call(HttpMethod.Get, ApiPaths.HEALTH, null).bodyAsText()).jsonObject
        assertEquals("true", health.getValue("ok").jsonPrimitive.content)
        assertEquals(1L, health.getValue("api").jsonPrimitive.long)
        assertEquals("""{"server":"https://news.example.org"}""", client.call(HttpMethod.Get, ApiPaths.KOBO_CONFIG, null).bodyAsText())
    }

    @Test
    fun loginPassesTheDeviceAndMapsWrongPasswords() = api {
        val ok = client.call(HttpMethod.Post, ApiPaths.LOGIN, null, """{"username":"tania","password":"right","device":{"id":"p1","name":"Pixel 8"}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"token\":\"${TestTokens.PHONE}\""))
        assertEquals("login(tania,p1)", fakes.calls.first())
        assertError(client.call(HttpMethod.Post, ApiPaths.LOGIN, null, """{"username":"tania","password":"no","device":{"id":"p1","name":"P"}}"""), 401, ApiError.INVALID_CREDENTIALS)
    }

    @Test
    fun malformedBodiesAreBadRequests() = api {
        assertError(client.call(HttpMethod.Post, ApiPaths.LOGIN, null, "{not json"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.LOGIN, null, """{"username":"tania"}"""), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.LOGIN, null), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.FEEDS, body = """{"url":" "}"""), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Patch, ApiPaths.SETTINGS, body = """{"timeZone":"Mars/Olympus"}"""), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.PASSWORD, TestTokens.LOCKED, """{"current":"a"}"""), 400, ApiError.BAD_REQUEST)
    }

    @Test
    fun pathAndQueryParametersAreCheckedAndPassedOn() = api {
        assertError(client.call(HttpMethod.Get, "/articles/seven"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.ARTICLES), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.ARTICLES + "?shelf=read"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.ARTICLES + "?shelf=liked&before=yesterday"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.SEARCH), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.SEARCH + "?q=a&when=DECADE"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.EDITIONS + "?limit=0"), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Get, ApiPaths.DISCOVERY_LIVE), 400, ApiError.BAD_REQUEST)

        client.call(HttpMethod.Get, ApiPaths.ARTICLES + "?shelf=liked&before=2026-11-11T06:30:00Z&limit=20")
        client.call(HttpMethod.Get, ApiPaths.SEARCH + "?q=kobo+sync&when=week&saved=true&tagId=4&feedId=3&limit=10&offset=20")
        client.call(HttpMethod.Get, ApiPaths.EDITIONS + "?before=12&limit=5")
        client.call(HttpMethod.Get, ApiPaths.ENGAGEMENT + "?since=99")
        client.call(HttpMethod.Get, ApiPaths.feedArticles(3) + "?before=2026-11-11T06:30:00Z")
        client.call(HttpMethod.Get, ApiPaths.SYNC_MANIFEST + "?editions=10", TestTokens.KOBO, through = 5)
        assertTrue(fakes.calls.contains("shelf(1,liked,2026-11-11T06:30:00Z,20)"))
        assertTrue(fakes.calls.contains("search(1,SearchQuery(q=kobo sync, when=WEEK, saved=true, liked=false, tagId=4, feedId=3, limit=10, offset=20))"))
        assertTrue(fakes.calls.contains("editions(1,12,5)"))
        assertTrue(fakes.calls.contains("engagement(1,99)"))
        assertTrue(fakes.calls.contains("feedArticles(1,3,2026-11-11T06:30:00Z,50)"))
        assertTrue(fakes.calls.contains("manifest(1,kobo-1,10)"))
    }

    @Test
    fun serviceFailuresKeepTheirCodeAndStatus() = api {
        assertError(client.call(HttpMethod.Get, ApiPaths.article(8)), 404, ApiError.NOT_FOUND)
        assertError(client.call(HttpMethod.Get, ApiPaths.job("other")), 404, ApiError.NOT_FOUND)
        assertError(client.call(HttpMethod.Delete, ApiPaths.device("missing")), 404, ApiError.NOT_FOUND)
        assertError(client.call(HttpMethod.Post, ApiPaths.FEEDS_DISCOVER, body = """{"address":"nothing.example"}"""), 422, ApiError.NOT_A_FEED)
        assertError(client.call(HttpMethod.Get, ApiPaths.editionBundle(121), TestTokens.KOBO, through = 0), 404, ApiError.NOT_FOUND)
        assertError(client.call(HttpMethod.Get, "/no/such/thing"), 404, ApiError.NOT_FOUND)
    }

    @Test
    fun theGateWantsTheHeaderAndAnswersPendingWithAcked() = api {
        for (path in listOf(ApiPaths.SYNC_MANIFEST, ApiPaths.editionBundle(120), ApiPaths.SAVED)) {
            assertError(client.call(HttpMethod.Get, path, TestTokens.KOBO), 400, ApiError.EVENTS_THROUGH_REQUIRED)
            assertError(client.call(HttpMethod.Get, path, TestTokens.KOBO, headers = mapOf(EVENTS_THROUGH_HEADER to "lots")), 400, ApiError.EVENTS_THROUGH_REQUIRED)
            val pending = client.call(HttpMethod.Get, path, TestTokens.KOBO, through = 9)
            assertError(pending, 409, ApiError.EVENTS_PENDING)
            assertEquals(5L, pending.error().acked)
        }
        val images = """{"width":1264,"urls":[]}"""
        assertError(client.call(HttpMethod.Post, ApiPaths.KOBO_IMAGES, TestTokens.KOBO, images), 400, ApiError.EVENTS_THROUGH_REQUIRED)
        assertError(client.call(HttpMethod.Post, ApiPaths.KOBO_IMAGES, TestTokens.KOBO, images, through = 9), 409, ApiError.EVENTS_PENDING)

        // Upload the events, then the manifest answers.
        val events = (6..9).joinToString(",") { """{"seq":$it,"type":"opened","articleId":7,"at":"2026-11-11T07:00:00Z"}""" }
        val acked = client.call(HttpMethod.Post, ApiPaths.SYNC_EVENTS, TestTokens.KOBO, """{"events":[$events]}""")
        assertEquals("""{"acked":9}""", acked.bodyAsText())
        val manifest = client.call(HttpMethod.Get, ApiPaths.SYNC_MANIFEST, TestTokens.KOBO, through = 9)
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertTrue(manifest.bodyAsText().contains("\"protocol\":\"server/1\""))
        assertTrue(fakes.calls.contains("postEvents(1,kobo-1,4)"))
    }

    @Test
    fun atMostFiveHundredEventsAtOnce() = api {
        val events = (1..501).joinToString(",") { """{"seq":$it,"type":"opened","articleId":7}""" }
        assertError(client.call(HttpMethod.Post, ApiPaths.SYNC_EVENTS, TestTokens.KOBO, """{"events":[$events]}"""), 413, ApiError.TOO_LARGE)
    }

    @Test
    fun bundlesAndTheSavedShelfCarryAnETag() = api {
        val first = client.call(HttpMethod.Get, ApiPaths.editionBundle(120), TestTokens.KOBO, through = 0)
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)
        assertEquals(fakes.bundle, ApiJson.json.decodeFromString<net.tgnra.morningissue.api.EditionBundleDto>(first.bodyAsText()))
        val again = client.call(HttpMethod.Get, ApiPaths.editionBundle(120), TestTokens.KOBO, through = 0, headers = mapOf(HttpHeaders.IfNoneMatch to etag!!))
        assertEquals(HttpStatusCode.NotModified, again.status)
        val saved = client.call(HttpMethod.Get, ApiPaths.SAVED, TestTokens.KOBO, through = 0)
        assertNotNull(saved.headers[HttpHeaders.ETag])
        assertTrue(saved.bodyAsText().contains("\"order\":[7]"))
    }

    @Test
    fun jobsAnswer202WhenStartedAnd200WhenAlreadyRunning() = api {
        assertEquals(HttpStatusCode.Accepted, client.call(HttpMethod.Post, ApiPaths.PRINT).status)
        fakes.printRunning = true
        val running = client.call(HttpMethod.Post, ApiPaths.PRINT)
        assertEquals(HttpStatusCode.OK, running.status)
        assertEquals("""{"jobId":"job-print"}""", running.bodyAsText())
    }

    @Test
    fun addingAFeedAlreadySubscribedIs200() = api {
        val r = client.call(HttpMethod.Post, ApiPaths.FEEDS, body = """{"url":"https://example.org/feed"}""")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun patchKeepsAnExplicitNullApartFromAnAbsentKey() = api {
        client.call(HttpMethod.Patch, ApiPaths.feed(3), body = """{"muteUntil":null}""")
        client.call(HttpMethod.Patch, ApiPaths.feed(3), body = """{"title":"x"}""")
        client.call(HttpMethod.Patch, ApiPaths.SETTINGS, body = """{"extraOfferDismissedAt":"2026-11-11T06:30:00Z"}""")
        assertEquals(listOf("patchFeed(1,3,Set(value=null))", "patchFeed(1,3,Absent)"), fakes.calls.filter { it.startsWith("patchFeed") })
        assertTrue(fakes.calls.contains("patchSettings(1,null,Set(value=2026-11-11T06:30:00Z))"))
    }

    @Test
    fun opmlGoesInAsBytesAndComesOutAsOpml() = api {
        val opml = "<opml version=\"2.0\"><body/></opml>"
        assertEquals(HttpStatusCode.Accepted, client.call(HttpMethod.Post, ApiPaths.OPML, body = opml, type = ContentType.parse("text/x-opml")).status)
        assertTrue(fakes.calls.contains("importOpml(1,${opml.length})"))
        val out = client.call(HttpMethod.Get, ApiPaths.OPML)
        assertEquals("text/x-opml", out.headers[HttpHeaders.ContentType]!!.substringBefore(';'))
        assertTrue(out.headers[HttpHeaders.ContentDisposition]!!.contains("attachment"))
        assertEquals("<opml version=\"2.0\"/>", out.bodyAsText())
    }

    @Test
    fun koboImagesStreamAZipAndAreHeldToTheLimits() = api {
        val ok = client.call(HttpMethod.Post, ApiPaths.KOBO_IMAGES, TestTokens.KOBO, """{"width":1264,"urls":["https://a.example/1.jpg"]}""", through = 0)
        assertEquals("application/zip", ok.headers[HttpHeaders.ContentType])
        ZipInputStream(ByteArrayInputStream(ok.bodyAsBytes())).use { assertEquals("images.json", it.nextEntry!!.name) }
        val many = (1..101).joinToString(",") { "\"https://a.example/$it.jpg\"" }
        assertError(client.call(HttpMethod.Post, ApiPaths.KOBO_IMAGES, TestTokens.KOBO, """{"width":1264,"urls":[$many]}""", through = 0), 413, ApiError.TOO_LARGE)
        assertError(client.call(HttpMethod.Post, ApiPaths.KOBO_IMAGES, TestTokens.KOBO, """{"width":90,"urls":[]}""", through = 0), 400, ApiError.BAD_REQUEST)
        assertTrue(fakes.calls.contains("writeZip(1,1264,1)"))
    }

    @Test
    fun koboHelloCarriesTheDbId() = api {
        assertEquals(
            """{"username":"u1","server":"https://news.example.org","dbId":"db-1"}""",
            client.call(HttpMethod.Get, ApiPaths.KOBO_HELLO, TestTokens.KOBO).bodyAsText(),
        )
    }

    @Test
    fun bodiesOverOneMegabyteAreRefused() = api {
        val big = "{\"address\":\"" + "x".repeat(1024 * 1024) + "\"}"
        assertError(client.call(HttpMethod.Post, ApiPaths.FEEDS_DISCOVER, body = big), 413, ApiError.TOO_LARGE)
        // OPML may be up to 5 MB.
        val opml = "<opml>" + " ".repeat(2 * 1024 * 1024) + "</opml>"
        assertEquals(HttpStatusCode.Accepted, client.call(HttpMethod.Post, ApiPaths.OPML, body = opml, type = ContentType.parse("text/x-opml")).status)
        val tooBig = "<opml>" + " ".repeat(5 * 1024 * 1024) + "</opml>"
        assertError(client.call(HttpMethod.Post, ApiPaths.OPML, body = tooBig, type = ContentType.parse("text/x-opml")), 413, ApiError.TOO_LARGE)
    }

    @Test
    fun aChunkedBodyIsCountedAsItArrives() = api {
        val big = ByteArray(1024 * 1024 + 10) { 'x'.code.toByte() }
        val r = client.request(ApiPaths.BASE + ApiPaths.FEEDS_DISCOVER) {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer ${TestTokens.PHONE}")
            contentType(ContentType.Application.Json)
            setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                    channel.writeFully(big)
                }
            })
        }
        assertError(r, 413, ApiError.TOO_LARGE)
    }

    private val key = "6f1c2b9e-4a7d-4e1b-9c3f-2d8e5a7b1c40"

    @Test
    fun importStreamsTheArchiveToTheDataDirAndStartsAJob() = api {
        val zip = ByteArray(5000) { it.toByte() }
        val r = client.call(HttpMethod.Post, ApiPaths.IMPORT + "?mode=merge", body = zip, headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = ContentType.parse("application/zip"))
        assertEquals(HttpStatusCode.Accepted, r.status)
        assertEquals(ImportResponse(jobId = "job-import"), ApiJson.json.decodeFromString<ImportResponse>(r.bodyAsText()))
        assertTrue(fakes.calls.contains("import(1,MERGE,$key,5000)"))
        assertEquals(File(importsDir, "1-$key.zip"), fakes.importStarted)
        assertTrue(fakes.importStarted!!.readBytes().contentEquals(zip))
        assertFalse(File(importsDir, "1-$key.zip.part").exists())
    }

    @Test
    fun aKnownImportKeyAnswersItsReport() = api {
        fakes.importAnswer = ImportResponse(report = ImportReportDto(feeds = 2))
        val r = client.call(HttpMethod.Post, ApiPaths.IMPORT, body = ByteArray(10), headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = ContentType.parse("application/zip"))
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(2, ApiJson.json.decodeFromString<ImportResponse>(r.bodyAsText()).report!!.feeds)
        assertFalse(File(importsDir, "1-$key.zip").exists())
    }

    @Test
    fun importChecksItsHeaderModeAndSize() = api {
        val zip = ContentType.parse("application/zip")
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT, body = ByteArray(10), type = zip), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT, body = ByteArray(10), headers = mapOf(IDEMPOTENCY_KEY_HEADER to "../../etc"), type = zip), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT + "?mode=wipe", body = ByteArray(10), headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = zip), 400, ApiError.BAD_REQUEST)
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT, body = ByteArray(0), headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = zip), 400, ApiError.BAD_REQUEST)
        // The test limit is 64 KB.
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT, body = ByteArray(65 * 1024), headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = zip), 413, ApiError.TOO_LARGE)
        // REPLACE on an account with data: the service says conflict and the upload is removed.
        assertError(client.call(HttpMethod.Post, ApiPaths.IMPORT + "?mode=replace", body = ByteArray(10), headers = mapOf(IDEMPOTENCY_KEY_HEADER to key), type = zip), 409, ApiError.CONFLICT)
        assertNull(importsDir.listFiles()!!.firstOrNull())
    }

    @Test
    fun unwiredServicesAnswer501() = testApplication {
        application { morningIssue(ServerServices(auth = fakes.auth, devices = fakes.devices), HttpOptions(importsDir = importsDir)) }
        val r = client.call(HttpMethod.Get, ApiPaths.FEEDS)
        assertEquals(HttpStatusCode.NotImplemented, r.status)
        assertEquals(NOT_IMPLEMENTED, r.error().error)
        // Auth and devices are real here.
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, ApiPaths.DEVICES).status)
    }

    @Test
    fun theDefaultApplicationStillAnswersHealth() = testApplication {
        application { morningIssue() }
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, ApiPaths.HEALTH, null).status)
        assertEquals(HttpStatusCode.NotImplemented, client.call(HttpMethod.Get, ApiPaths.FEEDS).status)
    }
}

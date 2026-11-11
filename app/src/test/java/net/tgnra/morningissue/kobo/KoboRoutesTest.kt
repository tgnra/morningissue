package net.tgnra.morningissue.kobo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The routes of protocol v1: pairing, the token check, the caps, and each endpoint's answers. */
@RunWith(AndroidJUnit4::class)
class KoboRoutesTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var ids: Seeded
    private lateinit var routes: KoboRoutes
    private val pairing = Pairing()
    private val activity = mutableListOf<SyncActivity>()
    private val images: File = createTempDirectory("kobo-images").toFile()

    @Before
    fun setUp() = runTest {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        ids = KoboTestData.seed(db)
        routes = KoboRoutes(db, pairing, { "3f0c" }, { url -> File(images, url.hashCode().toString()) }, NOW_CLOCK, activity::add, pairWaitMillis = 5_000)
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
    }

    private fun get(path: String, token: String? = null, query: Map<String, String> = emptyMap()) =
        HttpRequest("GET", path, query, headers = listOfNotNull(token?.let { "authorization" to "Bearer $it" }).toMap())

    private fun post(path: String, body: String, token: String? = null) =
        HttpRequest("POST", path, headers = listOfNotNull(token?.let { "authorization" to "Bearer $it" }).toMap(), body = body.toByteArray())

    private fun HttpResponse.text() = body.decodeToString()

    private suspend fun pair(allow: Boolean, deviceId: String = "b7e2") = kotlinx.coroutines.coroutineScope {
        val reply = async { routes.handle(post("/v1/pair", """{"deviceId":"$deviceId","name":"Kobo Libra 2"}""")) }
        val pending = pairing.pending.first { it != null }!!
        assertEquals("Kobo Libra 2", pending.name)
        pairing.answer(allow)
        reply.await()
    }

    @Test
    fun allowGivesATokenThatOpensTheOtherRoutes() = runTest {
        val response = pair(allow = true)
        assertEquals(200, response.status)
        val body = Json.parseToJsonElement(response.text()).jsonObject
        assertEquals("3f0c", body.getValue("dbId").jsonPrimitive.content)
        val token = body.getValue("token").jsonPrimitive.content
        // Only the hash is kept.
        assertEquals(PeerTokens.hash(token), db.syncPeerDao().get("b7e2")!!.tokenHash)
        assertNull(pairing.pending.value)

        val manifest = routes.handle(get("/v1/manifest", token))
        assertEquals(200, manifest.status)
        assertEquals(0L, Json.parseToJsonElement(manifest.text()).jsonObject.getValue("ackedSeq").jsonPrimitive.long)
        assertEquals(NOW, db.syncPeerDao().get("b7e2")!!.lastSyncAt)
        assertEquals(200, routes.handle(get("/v1/editions/${ids.edition}", token)).status)

        // Pairing again replaces the old token.
        val again = Json.parseToJsonElement(pair(allow = true).text()).jsonObject.getValue("token").jsonPrimitive.content
        assertEquals(401, routes.handle(get("/v1/manifest", token)).status)
        assertEquals(200, routes.handle(get("/v1/manifest", again)).status)
    }

    @Test
    fun denyIs403AndNoAnswerIs408() = runTest {
        assertEquals(403, pair(allow = false).status)
        assertTrue(db.syncPeerDao().all().isEmpty())
        val quick = KoboRoutes(db, pairing, { "3f0c" }, { File(images, "x") }, NOW_CLOCK, pairWaitMillis = 50)
        assertEquals(408, quick.handle(post("/v1/pair", """{"deviceId":"b7e2","name":"Kobo"}""")).status)
        assertNull(pairing.pending.value)
    }

    @Test
    fun aSecondPairingWhileOneWaitsIs409() = runTest {
        val first = async { routes.handle(post("/v1/pair", """{"deviceId":"one","name":"Kobo One"}""")) }
        pairing.pending.first { it != null }
        assertEquals(409, routes.handle(post("/v1/pair", """{"deviceId":"two","name":"Kobo Two"}""")).status)
        pairing.answer(true)
        assertEquals(200, first.await().status)
    }

    @Test
    fun aBadPairingRequestIs400() = runTest {
        assertEquals(400, routes.handle(post("/v1/pair", "{")).status)
        assertEquals(400, routes.handle(post("/v1/pair", """{"deviceId":"","name":"Kobo"}""")).status)
        assertEquals(400, routes.handle(post("/v1/pair", """{"deviceId":"b7e2","name":"${"K".repeat(65)}"}""")).status)
        assertNull(pairing.pending.value)
    }

    @Test
    fun everyOtherRouteNeedsAPairedToken() = runTest {
        db.syncPeerDao().pair("b7e2", "Kobo", PeerTokens.hash("good"), RELEASE)
        for (request in listOf(
            get("/v1/manifest"), get("/v1/manifest", "bad"), get("/v1/editions/${ids.edition}", "bad"),
            get("/v1/images", "bad", mapOf("url" to "https://x/y.jpg")), post("/v1/events", """{"events":[]}""", "bad"),
        )) {
            assertEquals(request.path, 401, routes.handle(request).status)
        }
        assertEquals(200, routes.handle(get("/v1/manifest", "good")).status)
        assertEquals(404, routes.handle(get("/v1/elsewhere", "good")).status)
        assertEquals(405, routes.handle(post("/v1/manifest", "", "good")).status)
        assertEquals(405, routes.handle(get("/v1/pair")).status)
    }

    @Test
    fun eventsAreMergedAndAcknowledged() = runTest {
        db.syncPeerDao().pair("b7e2", "Kobo Libra 2", PeerTokens.hash("good"), RELEASE)
        val body = """{"events":[
            {"seq":42,"type":"opened","articleId":${ids.lead},"at":"2026-11-11T06:40:00Z"},
            {"seq":43,"type":"liked","articleId":${ids.lead},"value":true,"at":"2026-11-11T06:41:00Z","extra":"ignored"},
            {"seq":44,"type":"bookmarked","articleId":${ids.lead},"at":"2026-11-11T06:42:00Z"}
        ]}"""
        val response = routes.handle(post("/v1/events", body, "good"))
        assertEquals(200, response.status)
        assertEquals("""{"ackedSeq":44}""", response.text())
        assertEquals(RELEASE.plusSeconds(600), db.engagementDao().get(ids.lead)!!.openedAt)
        assertEquals(SyncActivity("b7e2", "Kobo Libra 2", NOW, eventsMerged = 3), activity.last())
        // The manifest then reports it.
        val manifest = Json.parseToJsonElement(routes.handle(get("/v1/manifest", "good")).text()).jsonObject
        assertEquals(44L, manifest.getValue("ackedSeq").jsonPrimitive.long)
    }

    @Test
    fun tooManyEventsIs413AndMergesNothing() = runTest {
        db.syncPeerDao().pair("b7e2", "Kobo", PeerTokens.hash("good"), RELEASE)
        val events = (1..KoboProtocol.MAX_EVENTS_PER_REQUEST + 1).joinToString(",") { """{"seq":$it,"type":"opened","articleId":${ids.lead},"at":"2026-11-11T06:40:00Z"}""" }
        assertEquals(413, routes.handle(post("/v1/events", """{"events":[$events]}""", "good")).status)
        assertEquals(0L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertEquals(400, routes.handle(post("/v1/events", """{"events":[{"type":"opened"}]}""", "good")).status)
    }

    @Test
    fun editionsAreServedOnlyOnceReleased() = runTest {
        db.syncPeerDao().pair("b7e2", "Kobo", PeerTokens.hash("good"), RELEASE)
        assertEquals(404, routes.handle(get("/v1/editions/${ids.unreleased}", "good")).status)
        assertEquals(404, routes.handle(get("/v1/editions/999999", "good")).status)
        assertEquals(404, routes.handle(get("/v1/editions/abc", "good")).status)
        val bundle = routes.handle(get("/v1/editions/${ids.older}", "good"))
        assertEquals("application/json; charset=utf-8", bundle.contentType)
    }

    @Test
    fun imagesComeFromTheStoreWithTheTypeOfTheirBytes() = runTest {
        db.syncPeerDao().pair("b7e2", "Kobo", PeerTokens.hash("good"), RELEASE)
        val url = "https://garden.example/img/frost.jpg"
        assertEquals(404, routes.handle(get("/v1/images", "good", mapOf("url" to url))).status)
        assertEquals(400, routes.handle(get("/v1/images", "good")).status)
        // A WebP header: the store re-encodes, whatever the address says.
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(32)
        File(images, url.hashCode().toString()).writeBytes(webp)
        val response = routes.handle(get("/v1/images", "good", mapOf("url" to url)))
        assertEquals(200, response.status)
        assertEquals("image/webp", response.contentType)
        assertEquals(webp.toList(), response.file!!.readBytes().toList())
    }
}

/** [LanHttpServer] and [HttpWire] on a real socket, through OkHttp. */
class LanHttpServerTest {
    private fun serve(port: Int, test: (OkHttpClient) -> Unit) = kotlinx.coroutines.runBlocking {
        val server = LanHttpServer(port, { request ->
            when (request.path) {
                "/echo" -> HttpResponse.json(200, """{"method":"${request.method}","q":"${request.query["url"]}","n":${request.body.size}}""")
                else -> HttpResponse.error(404, "not found")
            }
        }, maxBodyBytes = 1024)
        val listening = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { server.run { listening.complete(Unit) } }
        listening.await()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { test(OkHttpClient()) }
        } finally {
            job.cancel()
            job.join()
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun servesGetAndPostAndClosesTheConnection() {
        val port = freePort()
        serve(port) { client ->
            val url = "http://127.0.0.1:$port"
            client.newCall(Request.Builder().url("$url/echo?url=https%3A%2F%2Fa.example%2Fx.jpg%3Fw%3D2").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("close", it.header("Connection"))
                assertEquals("""{"method":"GET","q":"https://a.example/x.jpg?w=2","n":0}""", it.body.string())
            }
            client.newCall(Request.Builder().url("$url/echo").post("x".repeat(100).toRequestBody()).build()).execute().use {
                assertEquals("""{"method":"POST","q":"null","n":100}""", it.body.string())
            }
            client.newCall(Request.Builder().url("$url/nowhere").build()).execute().use { assertEquals(404, it.code) }
            // Past the body cap, refused before it is read.
            client.newCall(Request.Builder().url("$url/echo").post("x".repeat(2048).toRequestBody()).build()).execute().use { assertEquals(413, it.code) }
        }
        // Cancelling closed the port.
        java.net.ServerSocket(port).close()
    }

    @Test
    fun aChunkedBodyIsRefused() {
        val port = freePort()
        serve(port) {
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".toByteArray())
                val reply = socket.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 411 Length Required", reply)
            }
        }
    }

    @Test
    fun theWireReaderParsesOneRequest() {
        val raw = "POST /v1/events?x=1&y=a+b HTTP/1.1\r\nHost: phone\r\nContent-Length: 5\r\nAuthorization: Bearer t\r\n\r\nhello"
        val request = HttpWire.readRequest(raw.byteInputStream(), maxBodyBytes = 10)
        assertEquals("POST", request.method)
        assertEquals("/v1/events", request.path)
        assertEquals(mapOf("x" to "1", "y" to "a b"), request.query)
        assertEquals("Bearer t", request.headers["authorization"])
        assertEquals("hello", request.body.decodeToString())
        val tooBig = runCatching { HttpWire.readRequest(raw.byteInputStream(), maxBodyBytes = 4) }.exceptionOrNull() as HttpWire.Refused
        assertEquals(413, tooBig.status)
        val garbage = runCatching { HttpWire.readRequest("hello\r\n\r\n".byteInputStream(), 10) }.exceptionOrNull() as HttpWire.Refused
        assertEquals(400, garbage.status)
        val endless = runCatching { HttpWire.readRequest(("GET / HTTP/1.1\r\nX: " + "a".repeat(20_000)).byteInputStream(), 10) }.exceptionOrNull() as HttpWire.Refused
        assertEquals(431, endless.status)
    }
}

package net.tgnra.morningissue.server.http

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiErrorBody
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiPaths
import net.tgnra.morningissue.api.DevicesResponse
import net.tgnra.morningissue.api.KoboHelloResponse
import net.tgnra.morningissue.api.LoginResponse
import net.tgnra.morningissue.server.ServerContainer
import net.tgnra.morningissue.server.Cli
import net.tgnra.morningissue.server.auth.DbAuthService
import net.tgnra.morningissue.server.auth.DbDeviceService
import net.tgnra.morningissue.server.auth.Passwords
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.morningIssue
import net.tgnra.morningissue.sync.MutableClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real account and device services behind the routes: the first login, the lockout, Kobo pairing. */
class AuthFlowTest {
    private lateinit var db: ServerDatabase
    private lateinit var dataDir: File
    private val clock = MutableClock()
    private lateinit var services: ServerServices

    @Before
    fun setUp() {
        db = ServerDatabase.inMemory()
        dataDir = Files.createTempDirectory("auth-flow").toFile()
        val auth = DbAuthService(db, File(dataDir, "initial-passwords"), clock, Passwords(iterations = 1_000))
        services = FakeServices().services().copy(auth = auth, devices = DbDeviceService(db, "https://news.example.org", clock))
        runBlocking { auth.bootstrapUsers(listOf("alice")) }
    }

    @After
    fun tearDown() {
        db.close()
        dataDir.deleteRecursively()
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { morningIssue(services, HttpOptions(importsDir = File(dataDir, "imports"))) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, token: String? = null, body: String? = null): HttpResponse =
        client.request(ApiPaths.BASE + path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    private suspend fun HttpResponse.code() = ApiJson.json.decodeFromString<ApiErrorBody>(bodyAsText()).error

    private val passwordFile get() = File(dataDir, "initial-passwords/alice.txt")

    private fun loginBody(password: String, device: String = "phone-1") =
        """{"username":"alice","password":"$password","device":{"id":"$device","name":"Pixel 8"}}"""

    @Test
    fun firstLoginThenPasswordChangeThenEverythingOpens() = api {
        val initial = passwordFile.readText().trim()
        val login = send(HttpMethod.Post, ApiPaths.LOGIN, body = loginBody(initial))
        assertEquals(HttpStatusCode.OK, login.status)
        val session = ApiJson.json.decodeFromString<LoginResponse>(login.bodyAsText())
        assertTrue(session.mustChangePassword)

        val locked = send(HttpMethod.Get, ApiPaths.SETTINGS, session.token)
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals(ApiError.PASSWORD_CHANGE_REQUIRED, locked.code())
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, ApiPaths.ACCOUNT, session.token).status)

        val weak = send(HttpMethod.Post, ApiPaths.PASSWORD, session.token, """{"current":"$initial","new":"short"}""")
        assertEquals(ApiError.WEAK_PASSWORD, weak.code())
        val wrong = send(HttpMethod.Post, ApiPaths.PASSWORD, session.token, """{"current":"nope","new":"long enough now"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val changed = send(HttpMethod.Post, ApiPaths.PASSWORD, session.token, """{"current":"$initial","new":"long enough now"}""")
        assertEquals(HttpStatusCode.NoContent, changed.status)
        assertFalse(passwordFile.exists())
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, ApiPaths.SETTINGS, session.token).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, ApiPaths.DEVICES, session.token).status)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Post, ApiPaths.LOGOUT, session.token).status)
        assertEquals(ApiError.UNAUTHENTICATED, send(HttpMethod.Get, ApiPaths.SETTINGS, session.token).code())
    }

    @Test
    fun theSixthBadPasswordIsTooManyAttempts() = api {
        repeat(5) {
            val r = send(HttpMethod.Post, ApiPaths.LOGIN, body = loginBody("wrong"))
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            assertEquals(ApiError.INVALID_CREDENTIALS, r.code())
        }
        val limited = send(HttpMethod.Post, ApiPaths.LOGIN, body = loginBody(passwordFile.readText().trim()))
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(ApiError.TOO_MANY_ATTEMPTS, limited.code())
    }

    @Test
    fun aPairedKoboSaysHelloWithItsDerivedTokenUntilForgotten() = api {
        val initial = passwordFile.readText().trim()
        val token = ApiJson.json.decodeFromString<LoginResponse>(send(HttpMethod.Post, ApiPaths.LOGIN, body = loginBody(initial)).bodyAsText()).token
        send(HttpMethod.Post, ApiPaths.PASSWORD, token, """{"current":"$initial","new":"long enough now"}""")

        val koboToken = "gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc"
        // While the code is shown, the Kobo hears "unknown".
        assertEquals(ApiError.UNKNOWN, send(HttpMethod.Get, ApiPaths.KOBO_HELLO, koboToken).code())

        val uri = "morningissue:pair?v=2&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=kobo-1&n=Kobo%20Libra%202&w=1264"
        clock.now = clock.now.plusSeconds(60)
        val paired = send(HttpMethod.Post, ApiPaths.DEVICES_KOBO, token, """{"pairingUri":"$uri"}""")
        assertEquals(HttpStatusCode.Created, paired.status)
        assertEquals(ApiError.BAD_REQUEST, send(HttpMethod.Post, ApiPaths.DEVICES_KOBO, token, """{"pairingUri":"${uri.replace("v=2", "v=1")}"}""").code())

        val hello = send(HttpMethod.Get, ApiPaths.KOBO_HELLO, koboToken)
        assertEquals(HttpStatusCode.OK, hello.status)
        val body = ApiJson.json.decodeFromString<KoboHelloResponse>(hello.bodyAsText())
        assertEquals("alice", body.username)
        assertEquals("https://news.example.org", body.server)
        assertEquals(db.accountDao().userByName("alice")!!.dbId, body.dbId)

        // The Kobo may not manage the account.
        assertEquals(ApiError.FORBIDDEN_FOR_DEVICE, send(HttpMethod.Get, ApiPaths.DEVICES, koboToken).code())

        val list = ApiJson.json.decodeFromString<DevicesResponse>(send(HttpMethod.Get, ApiPaths.DEVICES, token).bodyAsText())
        assertEquals(listOf("phone-1", "kobo-1"), list.devices.map { it.id })
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, ApiPaths.device("kobo-1"), token).status)
        assertEquals(ApiError.UNKNOWN, send(HttpMethod.Get, ApiPaths.KOBO_HELLO, koboToken).code())
    }

    @Test
    fun theCliResetsAPasswordInTheDataDir() {
        val out = ByteArrayOutputStream()
        val env = mapOf("MORNINGISSUE_DATA_DIR" to dataDir.path, "MORNINGISSUE_USERS" to "bob")
        // Bootstrap bob through a container on a file database, as `serve` would.
        ServerContainer(net.tgnra.morningissue.server.config.ConfigLoader.load(null, env)).use { c ->
            runBlocking { c.services.auth.bootstrapUsers(listOf("bob")) }
        }
        val first = File(dataDir, "initial-passwords/bob.txt").readText().trim()
        val status = Cli(::ServerContainer, env, PrintStream(out), PrintStream(ByteArrayOutputStream())).run(arrayOf("reset-password", "bob"))
        assertEquals(0, status)
        val fresh = File(dataDir, "initial-passwords/bob.txt").readText().trim()
        assertTrue(fresh != first)
        assertTrue(out.toString().contains(fresh))
        assertEquals(1, Cli(::ServerContainer, env, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())).run(arrayOf("reset-password", "carol")))
        assertEquals(2, Cli(::ServerContainer, env, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())).run(arrayOf("frobnicate")))
    }
}

package net.tgnra.morningissue.server.auth

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.LoginDevice
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.PairKoboRequest
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.sync.MutableClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory

/** The user bootstrap, passwords, logins and devices, over an in-memory database. */
class AccountsTest {
    private lateinit var db: ServerDatabase
    private lateinit var dataDir: File
    private val clock = MutableClock()
    private val passwords = Passwords(iterations = 1_000)
    private lateinit var auth: DbAuthService
    private lateinit var devices: DbDeviceService
    private val logged = ListAppender<ILoggingEvent>()
    private val bootstrapLogger = LoggerFactory.getLogger(UserBootstrap::class.java) as Logger

    private val passwordsDir get() = File(dataDir, "initial-passwords")

    @Before
    fun setUp() {
        db = ServerDatabase.inMemory()
        dataDir = Files.createTempDirectory("accounts-test").toFile()
        auth = DbAuthService(db, passwordsDir, clock, passwords)
        devices = DbDeviceService(db, "https://news.example.org", clock)
        logged.start()
        bootstrapLogger.addAppender(logged)
    }

    @After
    fun tearDown() {
        bootstrapLogger.detachAppender(logged)
        db.close()
        dataDir.deleteRecursively()
    }

    private fun initialPassword(user: String) = File(passwordsDir, "$user.txt").readText().trim()

    private fun login(user: String, password: String, device: String = "phone-1") =
        runBlocking { auth.login(LoginRequest(user, password, LoginDevice(device, "Pixel 8"))) }

    private fun failure(block: suspend () -> Unit): ApiFailure {
        try {
            runBlocking { block() }
        } catch (e: ApiFailure) {
            return e
        }
        fail("expected an ApiFailure")
        throw AssertionError()
    }

    @Test
    fun firstStartCreatesUsersWithAPasswordFileAndALogLine(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania", "sam"))
        val users = db.accountDao().allUsers()
        assertEquals(listOf("tania", "sam"), users.map { it.username })
        assertTrue(users.all { it.mustChangePassword && !it.disabled })
        assertTrue(users[0].dbId != users[1].dbId)

        val file = File(passwordsDir, "tania.txt")
        val password = initialPassword("tania")
        assertEquals(20, password.length)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(passwordsDir.toPath())))
        assertTrue(passwords.verify(password, users[0].passwordHash))

        val line = logged.list.single { it.formattedMessage.contains("\"tania\"") }
        assertEquals(Level.WARN, line.level)
        assertEquals("Created user \"tania\" with password \"$password\". Log in from the app and change it.", line.formattedMessage)

        // A second start changes nothing.
        val before = db.accountDao().allUsers()
        auth.bootstrapUsers(listOf("tania", "sam"))
        assertEquals(before, db.accountDao().allUsers())
        assertEquals(password, initialPassword("tania"))
    }

    @Test
    fun aNameLeftOutIsDisabledAndComesBackWithItsData(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania", "sam"))
        val sam = db.accountDao().userByName("sam")!!
        val token = login("sam", initialPassword("sam")).token
        auth.bootstrapUsers(listOf("tania"))
        assertTrue(db.accountDao().userByName("sam")!!.disabled)
        assertEquals(ApiError.ACCOUNT_DISABLED, failure { auth.authenticate(token) }.code)
        assertEquals(ApiError.ACCOUNT_DISABLED, failure { auth.login(LoginRequest("sam", initialPassword("sam"), LoginDevice("p", "P"))) }.code)
        assertEquals(listOf("tania"), db.accountDao().enabledUsers().map { it.username })

        auth.bootstrapUsers(listOf("tania", "sam"))
        val back = db.accountDao().userByName("sam")!!
        assertFalse(back.disabled)
        assertEquals(sam.dbId, back.dbId)
        assertEquals(sam.passwordHash, back.passwordHash)
        assertNotNull(auth.authenticate(token))
    }

    @Test
    fun loginGivesATokenThatAuthenticatesAndOnlyItsHashIsStored(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val response = login("tania", initialPassword("tania"))
        assertTrue(response.mustChangePassword)
        assertEquals("tania", response.user.username)
        assertEquals(db.accountDao().userByName("tania")!!.dbId, response.user.dbId)
        val principal = auth.authenticate(response.token)!!
        assertEquals("phone-1", principal.deviceId)
        assertEquals(DeviceKind.PHONE, principal.kind)
        assertTrue(principal.mustChangePassword)
        val row = db.deviceDao().byId("phone-1")!!
        assertEquals(Tokens.hash(response.token), row.tokenHash)
        assertNull(auth.authenticate("not-a-token"))
    }

    @Test
    fun loggingInAgainReplacesTheTokenAndKeepsAckedSeq(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val first = login("tania", initialPassword("tania"))
        val row = db.deviceDao().byId("phone-1")!!
        db.deviceDao().upsert(row.copy(ackedSeq = 42))
        val second = login("tania", initialPassword("tania"))
        assertNull(auth.authenticate(first.token))
        assertNotNull(auth.authenticate(second.token))
        val after = db.deviceDao().byId("phone-1")!!
        assertEquals(42, after.ackedSeq)
        assertEquals(row.createdAt, after.createdAt)
    }

    @Test
    fun theSameDeviceUnderAnotherAccountStartsOver(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania", "sam"))
        login("tania", initialPassword("tania"))
        db.deviceDao().upsert(db.deviceDao().byId("phone-1")!!.copy(ackedSeq = 7))
        login("sam", initialPassword("sam"))
        val row = db.deviceDao().byId("phone-1")!!
        assertEquals(db.accountDao().userByName("sam")!!.id, row.userId)
        assertEquals(0, row.ackedSeq)
    }

    @Test
    fun wrongPasswordsAndUnknownNamesAreRefusedThenRateLimited(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val right = initialPassword("tania")
        assertEquals(ApiError.INVALID_CREDENTIALS, failure { auth.login(LoginRequest("nobody", right, LoginDevice("p", "P"))) }.code)
        repeat(5) {
            assertEquals(ApiError.INVALID_CREDENTIALS, failure { auth.login(LoginRequest("tania", "wrong", LoginDevice("p", "P"))) }.code)
        }
        // Even the right password waits out the window.
        assertEquals(ApiError.TOO_MANY_ATTEMPTS, failure { auth.login(LoginRequest("tania", right, LoginDevice("p", "P"))) }.code)
        clock.now = clock.now.plus(Duration.ofMinutes(15))
        login("tania", right)
    }

    @Test
    fun aKoboCannotLogInWithAPassword(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val e = failure { auth.login(LoginRequest("tania", initialPassword("tania"), LoginDevice("k", "Kobo", DeviceKind.KOBO))) }
        assertEquals(ApiError.BAD_REQUEST, e.code)
    }

    @Test
    fun changingThePasswordClearsTheFlagAndDeletesTheFile(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val initial = initialPassword("tania")
        val token = login("tania", initial).token
        val userId = auth.authenticate(token)!!.userId

        assertEquals(ApiError.INVALID_CREDENTIALS, failure { auth.changePassword(userId, ChangePasswordRequest("wrong", "a much better one")) }.code)
        assertEquals(ApiError.WEAK_PASSWORD, failure { auth.changePassword(userId, ChangePasswordRequest(initial, "short")) }.code)
        assertEquals(ApiError.WEAK_PASSWORD, failure { auth.changePassword(userId, ChangePasswordRequest(initial, initial)) }.code)
        assertTrue(File(passwordsDir, "tania.txt").exists())

        auth.changePassword(userId, ChangePasswordRequest(initial, "a much better one"))
        assertFalse(File(passwordsDir, "tania.txt").exists())
        assertFalse(auth.authenticate(token)!!.mustChangePassword)
        assertFalse(auth.account(userId).mustChangePassword)
        assertFalse(login("tania", "a much better one", "phone-2").mustChangePassword)
        assertEquals(ApiError.INVALID_CREDENTIALS, failure { auth.login(LoginRequest("tania", initial, LoginDevice("p", "P"))) }.code)
    }

    @Test
    fun resetPasswordWritesANewFileAndSignsOutPhonesButNotKobos(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val token = login("tania", initialPassword("tania")).token
        val userId = auth.authenticate(token)!!.userId
        auth.changePassword(userId, ChangePasswordRequest(initialPassword("tania"), "a much better one"))
        devices.pairKobo(userId, PairKoboRequest(koboUri()))
        val koboToken = Tokens.koboToken(ByteArray(32) { it.toByte() })

        val fresh = auth.resetPassword("tania")
        assertEquals(fresh, initialPassword("tania"))
        assertNull(auth.authenticate(token))
        assertTrue(auth.authenticate(koboToken)!!.mustChangePassword)
        assertTrue(login("tania", fresh, "phone-2").mustChangePassword)
        assertTrue(logged.list.any { it.level == Level.WARN && it.formattedMessage.contains(fresh) })
        assertEquals(ApiError.NOT_FOUND, failure { auth.resetPassword("nobody") }.code)
    }

    @Test
    fun logoutRevokesThisDeviceOnly(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val a = login("tania", initialPassword("tania"), "a").token
        val b = login("tania", initialPassword("tania"), "b").token
        val p = auth.authenticate(a)!!
        auth.logout(p.userId, p.deviceId)
        assertNull(auth.authenticate(a))
        assertNotNull(auth.authenticate(b))
    }

    @Test
    fun theAccountSaysWhetherItHasDataAndCustomizedSettings(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val userId = db.accountDao().userByName("tania")!!.id
        val account = auth.account(userId)
        assertEquals("tania", account.username)
        assertFalse(account.hasData)
        assertFalse(account.settingsCustomized)
        assertTrue(account.mustChangePassword)

        db.useWriterConnection { c ->
            c.executeSQL(
                "INSERT INTO edition(user_id, date, kind, cutoffAt, releaseAt, builtAt, length, number) " +
                    "VALUES ($userId, 20400, 'MORNING', 0, 0, 0, 'STANDARD', 1)",
            )
            c.executeSQL("INSERT INTO user_settings(user_id, json, customized, updated_at) VALUES ($userId, '{}', 1, 0)")
        }
        val after = auth.account(userId)
        assertTrue(after.hasData)
        assertTrue(after.settingsCustomized)
    }

    private fun koboUri(d: String = "kobo-1", w: Int = 1264) =
        "morningissue:pair?v=2&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=$d&n=Kobo%20Libra%202&w=$w"

    @Test
    fun pairingAKoboStoresTheDerivedTokensHashAndRepairingKeepsAckedSeq(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val phone = auth.authenticate(login("tania", initialPassword("tania")).token)!!
        val paired = devices.pairKobo(phone.userId, PairKoboRequest(koboUri()))
        assertEquals("kobo-1", paired.device.id)
        assertEquals(DeviceKind.KOBO, paired.device.kind)
        assertEquals("Kobo Libra 2", paired.device.name)
        assertEquals(1264, paired.device.width)
        assertNull(paired.device.lastSeenAt)
        val row = db.deviceDao().byId("kobo-1")!!
        assertEquals("fc27ecea41821dbf6c80ee0f797b450c9eb03525dcd0c4bdc67c8d9dc441a89b", row.tokenHash)

        val token = "gLYEzapDfvjcIu5h42jFUFPx46QBYg0sKsGaedJRfzc"
        val kobo = auth.authenticate(token)!!
        assertEquals(DeviceKind.KOBO, kobo.kind)
        assertEquals("kobo-1", kobo.deviceId)

        db.deviceDao().upsert(row.copy(ackedSeq = 9))
        devices.revoke(phone.userId, "kobo-1")
        assertNull(auth.authenticate(token))
        devices.pairKobo(phone.userId, PairKoboRequest(koboUri(w = 1072)))
        assertNotNull(auth.authenticate(token))
        assertEquals(9, db.deviceDao().byId("kobo-1")!!.ackedSeq)
        assertEquals(1072, db.deviceDao().byId("kobo-1")!!.width)
    }

    @Test
    fun aBadPairingCodeIsABadRequest(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val userId = db.accountDao().userByName("tania")!!.id
        assertEquals(ApiError.BAD_REQUEST, failure { devices.pairKobo(userId, PairKoboRequest(koboUri().replace("v=2", "v=1"))) }.code)
        assertEquals(ApiError.BAD_REQUEST, failure { devices.pairKobo(userId, PairKoboRequest("hello")) }.code)
    }

    @Test
    fun theDeviceListMarksTheCallerAndLeavesOutRevokedOnes(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania", "sam"))
        val phone = auth.authenticate(login("tania", initialPassword("tania")).token)!!
        login("sam", initialPassword("sam"), "sams-phone")
        clock.now = clock.now.plusSeconds(60)
        devices.pairKobo(phone.userId, PairKoboRequest(koboUri()))
        val list = devices.devices(phone.userId, phone.deviceId).devices
        assertEquals(listOf("phone-1" to true, "kobo-1" to false), list.map { it.id to it.current })

        devices.revoke(phone.userId, "kobo-1")
        assertEquals(listOf("phone-1"), devices.devices(phone.userId, phone.deviceId).devices.map { it.id })
        assertEquals(ApiError.NOT_FOUND, failure { devices.revoke(phone.userId, "kobo-1") }.code)
        // Another user's device is not this user's to forget.
        assertEquals(ApiError.NOT_FOUND, failure { devices.revoke(phone.userId, "sams-phone") }.code)
    }

    @Test
    fun koboHelloNamesTheUserServerAndDbId(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        val user = db.accountDao().userByName("tania")!!
        val hello = devices.koboHello(user.id, "kobo-1")
        assertEquals("tania", hello.username)
        assertEquals("https://news.example.org", hello.server)
        assertEquals(user.dbId, hello.dbId)
        assertEquals("https://news.example.org", devices.koboConfig().server)
    }

    @Test
    fun lastSeenIsWrittenAtMostOnceAMinute(): Unit = runBlocking {
        auth.bootstrapUsers(listOf("tania"))
        login("tania", initialPassword("tania"))
        val start = clock.now
        clock.now = start.plusSeconds(10)
        devices.markSeen("phone-1")
        assertEquals(start.plusSeconds(10), db.deviceDao().byId("phone-1")!!.lastSeenAt)
        clock.now = start.plusSeconds(50)
        devices.markSeen("phone-1")
        assertEquals(start.plusSeconds(10), db.deviceDao().byId("phone-1")!!.lastSeenAt)
        clock.now = start.plusSeconds(71)
        devices.markSeen("phone-1")
        assertEquals(start.plusSeconds(71), db.deviceDao().byId("phone-1")!!.lastSeenAt)
    }
}

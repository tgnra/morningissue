package net.tgnra.morningissue.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.api.IDEMPOTENCY_KEY_HEADER
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.db.feed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The first start after updating (`docs/server-design.md`, 5.3): the server's address, login,
 * the generated password changed, then the library this phone kept uploaded to the account
 * (or set aside), with a retry that never imports twice.
 */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FakeServer
    private lateinit var kit: ClientKit

    @Before
    fun setUp() {
        server = FakeServer().start()
        kit = ClientKit(server, folder.root)
    }

    @After
    fun tearDown() {
        kit.close()
        server.stop()
    }

    /** The library an earlier version kept: one feed and two posts. */
    private suspend fun seedLegacyLibrary() {
        val feedId = kit.database.feedDao().insert(feed("https://legacy.example/feed.xml", "Legacy"))
        kit.database.articleDao().insertNew(article(feedId, "old-1"))
        kit.database.articleDao().insertNew(article(feedId, "old-2"))
    }

    private suspend fun loginAndChangePassword() {
        val check = kit.account.check(server.url)
        assertTrue(check is ServerCheck.Reachable)
        // The fake server is on http://, which the reader has to confirm.
        assertFalse((check as ServerCheck.Reachable).address.encrypted)
        assertEquals(LoginResult.InvalidCredentials, kit.account.login(check.address.url, "tania", "wrong"))
        assertEquals(LoginResult.LoggedIn(mustChangePassword = true), kit.account.login(check.address.url, "tania", "generated-1"))
        assertTrue(kit.auth.current().isLoggedIn)
        assertTrue(kit.auth.current().mustChangePassword)
        // Nothing syncs until the password is the reader's own.
        assertEquals(SyncOutcome.NotReady, kit.sync.sync())

        assertEquals(PasswordChangeResult.TooShort, kit.account.changePassword("generated-1", "short"))
        assertEquals(PasswordChangeResult.WrongPassword, kit.account.changePassword("nope", "a much longer one"))
        assertTrue(kit.auth.current().isLoggedIn)
        assertEquals(PasswordChangeResult.Changed, kit.account.changePassword("generated-1", "a much longer one"))
        assertFalse(kit.auth.current().mustChangePassword)
        assertEquals("a much longer one", server.password)
    }

    private suspend fun awaitMigration(): MigrationState =
        withTimeout(10_000) { kit.migration.state.first { it is MigrationState.Done || it is MigrationState.Failed || it is MigrationState.Choice } }

    @Test
    fun anEmptyAccountTakesThePhonesLibraryAtOnce() = runBlocking<Unit> {
        seedLegacyLibrary()
        server.hasData = false
        loginAndChangePassword()
        // Logging in to a new account did not throw the library away: it is still to be uploaded.
        assertEquals(1, kit.database.cacheDao().feedIds().size)

        assertTrue(kit.migration.needed())
        kit.migration.start()
        val done = awaitMigration()

        assertTrue("$done", done is MigrationState.Done)
        assertEquals(12, (done as MigrationState.Done).report?.posts)
        val upload = server.requestsTo("/import").single()
        assertEquals("merge", upload.url.queryParameter("mode"))
        assertNotNull(upload.headers[IDEMPOTENCY_KEY_HEADER])
        assertTrue("the archive was sent", upload.bodySize > 0)
        val state = kit.auth.current()
        assertEquals(LegacyMigration.DONE, state.legacyMigration)
        assertNull(state.importKey)
        assertFalse(kit.uploadFile.exists())
        // The phone's database is now the cache of the account: the server's feed, not the old one.
        assertEquals(listOf(1L), kit.database.cacheDao().feedIds())
        assertFalse(kit.migration.needed())
    }

    @Test
    fun anAccountWithALibraryAsksAndMergesWhenTold() = runBlocking<Unit> {
        seedLegacyLibrary()
        server.hasData = true
        loginAndChangePassword()
        kit.migration.start()
        assertEquals(MigrationState.Choice, awaitMigration())
        assertTrue(server.requestsTo("/import").isEmpty())

        kit.migration.merge()
        assertTrue(awaitMigration() is MigrationState.Done)
        assertEquals("merge", server.requestsTo("/import").single().url.queryParameter("mode"))
        assertEquals(LegacyMigration.DONE, kit.auth.current().legacyMigration)
    }

    @Test
    fun usingTheAccountsLibraryRemovesThePhonesAndUploadsNothing() = runBlocking<Unit> {
        seedLegacyLibrary()
        server.hasData = true
        loginAndChangePassword()
        kit.migration.start()
        assertEquals(MigrationState.Choice, awaitMigration())

        kit.migration.skip()
        withTimeout(10_000) { kit.migration.state.first { it is MigrationState.Done } }
        assertTrue(server.requestsTo("/import").isEmpty())
        assertEquals(LegacyMigration.SKIPPED, kit.auth.current().legacyMigration)
        assertEquals(0, kit.database.articleDao().count())
        // Then the account's library came down.
        assertEquals(listOf(1L), kit.database.cacheDao().feedIds())
    }

    @Test
    fun aRetryAfterAFailureSendsTheSameKeyAndImportsOnce() = runBlocking<Unit> {
        seedLegacyLibrary()
        server.hasData = false
        loginAndChangePassword()
        // The server takes the upload, then the phone loses it while following the job.
        server.failNext("/jobs/import-1", 503, "unavailable")
        kit.migration.start()
        val failed = awaitMigration()
        assertTrue("$failed", failed is MigrationState.Failed)
        // Nothing on the phone was changed, and the key is kept for the retry.
        assertEquals(LegacyMigration.PENDING, kit.auth.current().legacyMigration)
        assertEquals(2, kit.database.articleDao().count())
        val key = kit.auth.current().importKey
        assertNotNull(key)

        kit.migration.merge()
        val done = awaitMigration()
        assertTrue("$done", done is MigrationState.Done)
        val uploads = server.requestsTo("/import")
        assertEquals(2, uploads.size)
        assertEquals(listOf(key, key), uploads.map { it.headers[IDEMPOTENCY_KEY_HEADER] })
        // The server started one import; the second request was answered with its report.
        assertEquals(1, server.imports.size)
        assertEquals(12, (done as MigrationState.Done).report?.posts)
    }

    @Test
    fun aPhoneWithNoLibraryHasNothingToUpload() = runBlocking<Unit> {
        loginAndChangePassword()
        assertFalse(kit.migration.needed())
        assertEquals(LegacyMigration.DONE, kit.auth.current().legacyMigration)
        assertTrue(kit.sync.sync() is SyncOutcome.Synced)
    }

    @Test
    fun loggingOutWithUnsentChangesAsksFirst() = runBlocking<Unit> {
        kit.loggedIn()
        server.publish(1, listOf(100))
        assertTrue(kit.sync.sync() is SyncOutcome.Synced)
        kit.engagement.setLiked(100, true)
        // The server cannot take the event now.
        server.failNext("/sync/events", 503, "unavailable")

        assertEquals(LogoutResult.UnsentEvents(1), kit.account.logout())
        assertTrue(kit.auth.current().isLoggedIn)

        server.failNext("/sync/events", 503, "unavailable")
        assertEquals(LogoutResult.LoggedOut, kit.account.logout(force = true))
        assertFalse(kit.auth.current().isLoggedIn)
        assertEquals(0, kit.database.articleDao().count())
        assertEquals(0, kit.outbox.count())
        assertEquals(1, server.requestsTo("/auth/logout").size)
    }

    @Test
    fun loggingOutUploadsTheOutboxFirst() = runBlocking<Unit> {
        kit.loggedIn()
        server.publish(1, listOf(100))
        kit.sync.sync()
        kit.engagement.setSaved(100, true)

        assertEquals(LogoutResult.LoggedOut, kit.account.logout())
        assertEquals(listOf("saved"), server.events.map { it.type })
    }

    @Test
    fun aRevokedTokenSignsTheAppOut() = runBlocking<Unit> {
        kit.loggedIn()
        server.token = "revoked"
        assertEquals(SyncOutcome.SignedOut, kit.sync.sync())
        assertFalse(kit.auth.current().isLoggedIn)
        // The server address and the device stay, for logging in again.
        assertEquals(server.url, kit.auth.current().serverUrl)
        assertNotNull(kit.auth.current().deviceId ?: kit.auth.deviceId())
    }

    @Test
    fun aServerAddressIsMadeIntoAUrl() {
        assertEquals(ServerAddress("https://news.example.org", true), ServerAddress.parse("news.example.org"))
        assertEquals(ServerAddress("https://news.example.org/mi", true), ServerAddress.parse(" https://news.example.org/mi/ "))
        assertEquals(ServerAddress("https://news.example.org", true), ServerAddress.parse("https://news.example.org/api/v1"))
        assertEquals(ServerAddress("http://192.168.1.4:8080", false), ServerAddress.parse("http://192.168.1.4:8080"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("ftp://news.example.org"))
        assertNull(ServerAddress.parse("news example"))
    }
}

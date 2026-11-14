package net.tgnra.morningissue.data.upgrade

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.edition.FakeSettings
import net.tgnra.morningissue.model.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerClientUpgradeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: MorningIssueDatabase
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        database = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "server_auth.preferences_pb") }
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    /** A feed with a post: the library, or the cache, the version 7 database held. */
    private suspend fun seedLibrary() {
        val feed = database.feedDao().insert(FeedEntity(url = "https://blog.example/feed.xml", title = "A Blog", addedAt = T0))
        database.articleDao().insertNew(article(feed, "post-1"))
    }

    private suspend fun client(legacyMigration: String?) = store.edit {
        it[stringPreferencesKey("server_url")] = "https://news.example.org"
        it[stringPreferencesKey("token")] = "secret"
        it[longPreferencesKey("acked_seq")] = 41
        if (legacyMigration != null) it[stringPreferencesKey("legacy_migration")] = legacyMigration
    }

    private suspend fun feeds() = database.feedDao().getUrls().size

    @Test
    fun withoutTheServerClientNothingHappens() = runBlocking<Unit> {
        seedLibrary()
        val settings = FakeSettings(AppSettings(onboardingDone = true))
        assertEquals(ServerClientUpgrade.Outcome.NOTHING, ServerClientUpgrade(store, database, settings).run())
        assertEquals(1, feeds())
        assertEquals(AppSettings(onboardingDone = true), settings.current)
    }

    @Test
    fun theServerCacheIsClearedAndOnboardingOffersTheRestore() = runBlocking<Unit> {
        for (state in listOf("DONE", "SKIPPED")) {
            seedLibrary()
            client(state)
            val settings = FakeSettings(AppSettings(onboardingDone = true))
            assertEquals(ServerClientUpgrade.Outcome.CLEARED_CACHE, ServerClientUpgrade(store, database, settings).run())
            assertEquals(0, feeds())
            assertEquals(0, database.articleDao().count())
            assertFalse(settings.current.onboardingDone)
            assertTrue(settings.current.restoreNotice)
            // The client's records, its token among them, are gone.
            assertTrue(store.data.first().asMap().isEmpty())
        }
    }

    @Test
    fun aLibraryTheClientNeverTookOverIsKept() = runBlocking<Unit> {
        // Nothing uploaded yet, or an upload that never finished: still the reader's own library.
        for (state in listOf(null, "NONE", "PENDING")) {
            database.clearAllTables()
            seedLibrary()
            client(state)
            val settings = FakeSettings(AppSettings(onboardingDone = true))
            assertEquals(ServerClientUpgrade.Outcome.KEPT_LIBRARY, ServerClientUpgrade(store, database, settings).run())
            assertEquals(1, feeds())
            assertEquals(AppSettings(onboardingDone = true), settings.current)
            assertTrue(store.data.first().asMap().isEmpty())
        }
    }
}

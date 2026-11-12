package net.tgnra.morningissue.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.BuildConfig
import net.tgnra.morningissue.data.settings.RetiredSettings
import net.tgnra.morningissue.kobo.InstallId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelaySettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun store(name: String = "settings.preferences_pb") =
        PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(RetiredSettings)) { File(folder.root, name) }

    @Test
    fun theBuildsRelayIsTheOneUsed() = runBlocking {
        val s = RelaySettings(store(), "https://relay.example.org", "built-in-token")
        assertEquals("https://relay.example.org", s.current().address)
        assertEquals("built-in-token", s.current().registrationToken)
        assertTrue(s.current().isConfigured)
    }

    @Test
    fun byDefaultTheRelayIsBuiltIn() = runBlocking {
        val s = RelaySettings(store())
        assertEquals(BuildConfig.RELAY_ADDRESS, s.current().address)
        assertEquals(BuildConfig.RELAY_TOKEN, s.current().registrationToken)
        // Whatever the build was given, it is an address requests can go to.
        assertTrue(RelayAddress.parse(s.current().address, allowLocalHttp = false) is RelayAddress.Parse.Ok)
    }

    @Test
    fun aBuildGivenNoAddressUsesTheProjectsRelay() {
        // `app/build.gradle.kts` says whether this build was given one (-P or the environment).
        assumeFalse(System.getProperty("morningissue.relayAddressGiven").toBoolean())
        assertEquals("https://mirelay.tgnra.net", BuildConfig.RELAY_ADDRESS)
    }

    @Test
    fun aRelayTypedIntoAnEarlierVersionIsIgnoredAndRemoved() = runBlocking {
        val oldAddress = stringPreferencesKey("relay_address")
        val oldToken = stringPreferencesKey("relay_registration_token")
        val oldWidths = stringPreferencesKey("kobo_image_widths")
        val dbId = stringPreferencesKey("db_id")
        // What the earlier version left, written without the migration.
        val id = UUID.randomUUID().toString()
        val earlier = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = PreferenceDataStoreFactory.create(scope = earlier) { File(folder.root, "old.preferences_pb") }
        before.edit {
            it[oldAddress] = "https://typed.example.org"
            it[oldToken] = "typed"
            it[oldWidths] = "b7e2=1264"
            it[dbId] = id
        }
        // Closed, as by an app update, before the new version opens it.
        earlier.coroutineContext.job.cancelAndJoin()

        val after = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(RetiredSettings)) { File(folder.root, "old.preferences_pb") }
        val s = RelaySettings(after, "https://relay.example.org", "built-in-token")
        assertEquals("https://relay.example.org", s.current().address)
        assertEquals("built-in-token", s.current().registrationToken)
        val prefs = after.data.first()
        assertNull(prefs[oldAddress])
        assertNull(prefs[oldToken])
        assertNull(prefs[oldWidths])
        // Everything else stays: the install's ID most of all, or a Kobo would wipe itself.
        assertEquals(id, InstallId(after).get())
    }

    @Test
    fun theInstallIdIsMadeOnceAndKept() = runBlocking {
        val installId = InstallId(store())
        val id = installId.get()
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, installId.get())
    }

    @Test
    fun aBuildWithoutARelayHasNone() = runBlocking {
        val s = RelaySettings(store(), "", "")
        assertFalse(s.current().isConfigured)
        assertEquals("", s.current().registrationToken)
    }
}

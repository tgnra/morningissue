package net.tgnra.morningissue.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelaySettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun settings(address: String, token: String) =
        RelaySettings(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }, address, token)

    @Test
    fun theBuiltInRelayIsUsedUntilTheReaderSetsOne() = runBlocking {
        val s = settings("https://relay.example.org", "built-in-token")
        assertEquals("https://relay.example.org", s.current().address)
        assertEquals("built-in-token", s.current().registrationToken)

        s.setAddress("https://other.example.org")
        s.setRegistrationToken("mine")
        assertEquals("https://other.example.org", s.current().address)
        assertEquals("mine", s.current().registrationToken)

        // Cleared by the reader: off, not back to the built-in one.
        s.setAddress("")
        s.setRegistrationToken("")
        assertFalse(s.current().isConfigured)
        assertEquals("", s.current().registrationToken)
    }

    @Test
    fun aBuildWithoutARelayHasNone() = runBlocking {
        val s = settings("", "")
        assertFalse(s.current().isConfigured)
        assertEquals("", s.current().registrationToken)
    }
}

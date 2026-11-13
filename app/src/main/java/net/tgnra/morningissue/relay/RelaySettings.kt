package net.tgnra.morningissue.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.BuildConfig

/** The relay the app syncs through. */
data class RelayConfig(
    /** The relay's address as built into the app; empty when the build has none. */
    val address: String = "",
    /** Sent when creating the group, for a relay that asks for one; empty for none. */
    val registrationToken: String = "",
) {
    val isConfigured: Boolean get() = address.isNotBlank()
}

/**
 * Relay sync's part of the settings store: the owner secret `O`, made once per install, and
 * whether the group an earlier version left on the relay has been deleted.
 *
 * The address and the registration token are the build's (`BuildConfig.RELAY_ADDRESS` and
 * `RELAY_TOKEN`, from the build's properties; see `app/build.gradle.kts`) and cannot be changed
 * in the app. An address or token typed into an earlier version's Relay sync screen is not
 * read, and `RetiredSettings` removes it from the store.
 */
class RelaySettings(
    private val dataStore: DataStore<Preferences>,
    private val address: String = BuildConfig.RELAY_ADDRESS,
    private val registrationToken: String = BuildConfig.RELAY_TOKEN,
) {
    val config: Flow<RelayConfig> = dataStore.data.map { RelayConfig(address = address, registrationToken = registrationToken) }

    suspend fun current(): RelayConfig = config.first()

    /** The owner secret `O`: 32 random bytes made the first time it is asked for, then kept. */
    suspend fun ownerSecret(): ByteArray {
        dataStore.data.first()[OWNER]?.let(RelayCrypto::unb64)?.takeIf { it.size == RelayCrypto.KEY_BYTES }?.let { return it }
        var secret = ByteArray(0)
        dataStore.edit { prefs ->
            val stored = prefs[OWNER]?.let(RelayCrypto::unb64)?.takeIf { it.size == RelayCrypto.KEY_BYTES }
            secret = stored ?: RelayCrypto.randomBytes(RelayCrypto.KEY_BYTES).also { prefs[OWNER] = RelayCrypto.b64(it) }
        }
        return secret
    }

    /** Whether the owner secret has been made: without one, this install never had a group. */
    suspend fun hasOwnerSecret(): Boolean = dataStore.data.first()[OWNER] != null

    /**
     * Whether the group an earlier version kept on the relay between syncs has been deleted.
     * Since sessions, the relay holds the group only while one is open.
     */
    suspend fun leftoverGroupCleared(): Boolean = dataStore.data.first()[LEFTOVER_CLEARED] == true

    suspend fun setLeftoverGroupCleared() {
        dataStore.edit { it[LEFTOVER_CLEARED] = true }
    }

    private companion object {
        val OWNER = stringPreferencesKey("relay_owner_secret")
        val LEFTOVER_CLEARED = booleanPreferencesKey("relay_leftover_group_cleared")
    }
}

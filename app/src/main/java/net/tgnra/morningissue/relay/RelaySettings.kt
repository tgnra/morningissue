package net.tgnra.morningissue.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.BuildConfig

/** What the reader set for relay sync, and what the phone remembers about it. */
data class RelayConfig(
    /** As typed; empty when no relay is set. */
    val address: String = "",
    /** Sent when creating the group, for a relay that asks for one; empty for none. */
    val registrationToken: String = "",
    /** When the last package was published, and when Done last ran to the end. */
    val lastPublishedAt: Instant? = null,
    val lastDoneAt: Instant? = null,
) {
    val isConfigured: Boolean get() = address.isNotBlank()
}

/**
 * Relay sync's part of the settings store: the address and registration token, the owner
 * secret `O`, made once per install, and which address the group was last registered at.
 *
 * The address and token start as the ones built into the app (`BuildConfig.RELAY_ADDRESS`
 * and `RELAY_TOKEN`, from the build's properties; empty in a build without them) until the
 * reader sets their own; setting a field empty turns it off.
 */
class RelaySettings(
    private val dataStore: DataStore<Preferences>,
    private val builtInAddress: String = BuildConfig.RELAY_ADDRESS,
    private val builtInToken: String = BuildConfig.RELAY_TOKEN,
) {
    val config: Flow<RelayConfig> = dataStore.data.map { prefs ->
        RelayConfig(
            address = prefs[ADDRESS] ?: builtInAddress,
            registrationToken = prefs[TOKEN] ?: builtInToken,
            lastPublishedAt = prefs[LAST_PUBLISHED]?.let(Instant::ofEpochMilli),
            lastDoneAt = prefs[LAST_DONE]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun current(): RelayConfig = config.first()

    suspend fun setAddress(address: String) {
        dataStore.edit { it[ADDRESS] = address.trim() }
    }

    suspend fun setRegistrationToken(token: String) {
        dataStore.edit { it[TOKEN] = token.trim() }
    }

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

    /** The relay address the group is known to exist at, so `PUT /v1/group` is sent only the first time. */
    suspend fun registeredAt(): String? = dataStore.data.first()[REGISTERED]

    suspend fun setRegisteredAt(address: String?) {
        dataStore.edit { if (address == null) it.remove(REGISTERED) else it[REGISTERED] = address }
    }

    suspend fun setLastPublished(at: Instant) {
        dataStore.edit { it[LAST_PUBLISHED] = at.toEpochMilli() }
    }

    suspend fun setLastDone(at: Instant) {
        dataStore.edit { it[LAST_DONE] = at.toEpochMilli() }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("relay_address")
        val TOKEN = stringPreferencesKey("relay_registration_token")
        val OWNER = stringPreferencesKey("relay_owner_secret")
        val REGISTERED = stringPreferencesKey("relay_registered_at")
        val LAST_PUBLISHED = longPreferencesKey("relay_last_published")
        val LAST_DONE = longPreferencesKey("relay_last_done")
    }
}

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

/** The relay the app syncs through, and what the phone remembers about it. */
data class RelayConfig(
    /** The relay's address as built into the app; empty when the build has none. */
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
 * Relay sync's part of the settings store: the owner secret `O`, made once per install, which
 * address the group was last registered at, and when Publish and Done last finished.
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
    val config: Flow<RelayConfig> = dataStore.data.map { prefs ->
        RelayConfig(
            address = address,
            registrationToken = registrationToken,
            lastPublishedAt = prefs[LAST_PUBLISHED]?.let(Instant::ofEpochMilli),
            lastDoneAt = prefs[LAST_DONE]?.let(Instant::ofEpochMilli),
        )
    }

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
        val OWNER = stringPreferencesKey("relay_owner_secret")
        val REGISTERED = stringPreferencesKey("relay_registered_at")
        val LAST_PUBLISHED = longPreferencesKey("relay_last_published")
        val LAST_DONE = longPreferencesKey("relay_last_done")
    }
}

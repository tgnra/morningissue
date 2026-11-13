package net.tgnra.morningissue.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where the upload of the library an earlier version kept on this phone has got to. */
enum class LegacyMigration {
    /** Not looked at yet. */
    NONE,

    /** The library is being (or was being) uploaded; [AuthState.importKey] makes a retry safe. */
    PENDING,

    /** Uploaded, or there was nothing to upload. */
    DONE,

    /** The reader chose the account's library; this phone's was removed. */
    SKIPPED,
}

/** The server account this install uses, and what the client keeps between syncs. */
data class AuthState(
    val serverUrl: String? = null,
    val token: String? = null,
    /** Made once per install; the server's device ID for this phone. */
    val deviceId: String? = null,
    val username: String? = null,
    /** The server database's ID; a change means it was reset and the cache is wiped. */
    val dbId: String? = null,
    val mustChangePassword: Boolean = false,
    /** `GET /engagement`'s cursor. */
    val engagementCursor: Long = 0,
    val legacyMigration: LegacyMigration = LegacyMigration.NONE,
    /** The `Idempotency-Key` of the legacy upload, kept until it is done. */
    val importKey: String? = null,
    /**
     * The highest event `seq` the server has acknowledged from this device. New events are
     * numbered above it even after the cache is reset, since the server ignores any at or below.
     */
    val ackedSeq: Long = 0,
    val lastSyncAt: Instant? = null,
    /** The manifest `version` of each edition whose bundle the cache holds, by edition ID. */
    val editionVersions: Map<Long, String> = emptyMap(),
    /** The manifest `version` of the saved shelf last fetched. */
    val savedVersion: String? = null,
) {
    val isLoggedIn: Boolean get() = serverUrl != null && token != null
    val migrationSettled: Boolean get() = legacyMigration == LegacyMigration.DONE || legacyMigration == LegacyMigration.SKIPPED
}

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_auth")

/**
 * The account and sync bookkeeping, in DataStore `server_auth` (`docs/server-design.md`, 5.2).
 * Device-local settings stay in the settings store.
 */
class AuthStore(private val dataStore: DataStore<Preferences>) {
    val state: Flow<AuthState> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::toState)
        .distinctUntilChanged()

    suspend fun current(): AuthState = state.first()

    /** This install's device ID, made the first time it is asked for. */
    suspend fun deviceId(): String {
        current().deviceId?.let { return it }
        var id = ""
        dataStore.edit { prefs ->
            id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it }
        }
        return id
    }

    suspend fun setServer(url: String) = dataStore.edit { it[SERVER_URL] = url }

    /** After `POST /auth/login`: the account, and whether its password must be changed first. */
    suspend fun loggedIn(serverUrl: String, token: String, username: String, dbId: String, mustChangePassword: Boolean) =
        dataStore.edit {
            val sameAccount = it[USERNAME] == username && it[SERVER_URL] == serverUrl
            it[SERVER_URL] = serverUrl
            it[TOKEN] = token
            it[USERNAME] = username
            it[MUST_CHANGE_PASSWORD] = mustChangePassword
            // Another account's cache is not this one's: start again (the caller resets the cache).
            if (!sameAccount) clearSync(it)
            // For the same account the stored one stays, so that the next sync sees a reset server.
            if (it[DB_ID] == null) it[DB_ID] = dbId
        }

    suspend fun setMustChangePassword(value: Boolean) = dataStore.edit { it[MUST_CHANGE_PASSWORD] = value }

    /** The token is gone (logged out, or revoked): the reader logs in again. The device ID and [AuthState.ackedSeq] stay. */
    suspend fun signedOut() = dataStore.edit {
        it.remove(TOKEN)
        it.remove(MUST_CHANGE_PASSWORD)
        clearSync(it)
    }

    /** Forgets what the cache held, after it has been wiped; [dbId] is the server database it will hold now. */
    suspend fun cacheReset(dbId: String?) = dataStore.edit {
        clearSync(it)
        if (dbId != null) it[DB_ID] = dbId
    }

    private fun clearSync(prefs: MutablePreferences) {
        prefs.remove(ENGAGEMENT_CURSOR)
        prefs.remove(EDITION_VERSIONS)
        prefs.remove(SAVED_VERSION)
        prefs.remove(LAST_SYNC_AT)
        prefs.remove(DB_ID)
    }

    suspend fun setLegacyMigration(value: LegacyMigration, importKey: String? = null) = dataStore.edit {
        it[LEGACY_MIGRATION] = value.name
        if (importKey != null) it[IMPORT_KEY] = importKey
        if (value == LegacyMigration.DONE || value == LegacyMigration.SKIPPED) it.remove(IMPORT_KEY)
    }

    /** The legacy upload's key: the stored one, or a new one stored now. */
    suspend fun importKey(): String {
        var key = ""
        dataStore.edit { prefs -> key = prefs[IMPORT_KEY] ?: UUID.randomUUID().toString().also { prefs[IMPORT_KEY] = it } }
        return key
    }

    /** Raises [AuthState.ackedSeq]; it never goes down. */
    suspend fun acked(seq: Long) = dataStore.edit { prefs ->
        if (seq > (prefs[ACKED_SEQ] ?: 0)) prefs[ACKED_SEQ] = seq
    }

    suspend fun setEngagementCursor(cursor: Long) = dataStore.edit { it[ENGAGEMENT_CURSOR] = cursor }

    suspend fun setDbId(dbId: String) = dataStore.edit { it[DB_ID] = dbId }

    suspend fun setEditionVersions(versions: Map<Long, String>) = dataStore.edit {
        it[EDITION_VERSIONS] = json.encodeToString(VERSIONS, versions.mapKeys { (id, _) -> id.toString() })
    }

    suspend fun setSavedVersion(version: String?) = dataStore.edit { if (version == null) it.remove(SAVED_VERSION) else it[SAVED_VERSION] = version }

    suspend fun setLastSyncAt(at: Instant) = dataStore.edit { it[LAST_SYNC_AT] = at.toEpochMilli() }

    private fun toState(prefs: Preferences) = AuthState(
        serverUrl = prefs[SERVER_URL],
        token = prefs[TOKEN],
        deviceId = prefs[DEVICE_ID],
        username = prefs[USERNAME],
        dbId = prefs[DB_ID],
        mustChangePassword = prefs[MUST_CHANGE_PASSWORD] ?: false,
        engagementCursor = prefs[ENGAGEMENT_CURSOR] ?: 0,
        legacyMigration = LegacyMigration.entries.firstOrNull { it.name == prefs[LEGACY_MIGRATION] } ?: LegacyMigration.NONE,
        importKey = prefs[IMPORT_KEY],
        ackedSeq = prefs[ACKED_SEQ] ?: 0,
        lastSyncAt = prefs[LAST_SYNC_AT]?.let(Instant::ofEpochMilli),
        editionVersions = prefs[EDITION_VERSIONS]?.let { stored ->
            runCatching { json.decodeFromString(VERSIONS, stored) }.getOrNull()
                ?.mapNotNull { (id, version) -> id.toLongOrNull()?.let { it to version } }?.toMap()
        }.orEmpty(),
        savedVersion = prefs[SAVED_VERSION],
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val VERSIONS = MapSerializer(String.serializer(), String.serializer())

        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val USERNAME = stringPreferencesKey("username")
        val DB_ID = stringPreferencesKey("db_id")
        val MUST_CHANGE_PASSWORD = booleanPreferencesKey("must_change_password")
        val ENGAGEMENT_CURSOR = longPreferencesKey("engagement_cursor")
        val LEGACY_MIGRATION = stringPreferencesKey("legacy_migration")
        val IMPORT_KEY = stringPreferencesKey("import_key")
        val ACKED_SEQ = longPreferencesKey("acked_seq")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val EDITION_VERSIONS = stringPreferencesKey("edition_versions")
        val SAVED_VERSION = stringPreferencesKey("saved_version")
    }
}

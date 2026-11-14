package net.tgnra.morningissue.data.upgrade

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.settings.SettingsRepository

/**
 * The first start after updating from 0.5, the client of a self-hosted Morning Issue server.
 *
 * Its database (version 7) has the local app's tables, and `Migrations.MIGRATION_7_8` keeps
 * every row. What those rows are depends on how far the client got, which only its own
 * records say (DataStore `server_auth`):
 *
 * - The library this phone kept before 0.5 was uploaded to the server (`legacy_migration`
 *   DONE), or the reader chose the account's library instead (SKIPPED): the database then
 *   became the server's cache. A cache holds only what the server sent lately, under the
 *   server's IDs, with stand-in feeds and posts where it lacked details. It is not a library
 *   the app can go on from, nor one an archive merges cleanly into, so it is cleared and
 *   onboarding starts again with a notice ([SettingsRepository.setRestoreNotice]): export the
 *   library from the server and import it here, into the now empty library, which also takes
 *   on the file's settings.
 * - Otherwise (nothing recorded, or an upload that never finished) the database still holds
 *   the reader's own library from before 0.5, and it is kept as it is.
 *
 * Either way the client's records, the server address and its token among them, are then
 * deleted. Nothing is sent anywhere.
 */
class ServerClientUpgrade(
    private val store: DataStore<Preferences>,
    private val database: MorningIssueDatabase,
    private val settings: SettingsRepository,
) {
    enum class Outcome {
        /** No server client was here. */
        NOTHING,

        /** The server client had not taken the database over: the reader's library is kept. */
        KEPT_LIBRARY,

        /** The database was the server's cache: cleared, and onboarding shows the restore notice. */
        CLEARED_CACHE,
    }

    suspend fun run(): Outcome {
        val prefs = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.first()
        if (prefs.asMap().isEmpty()) return Outcome.NOTHING
        val wasCache = prefs[LEGACY_MIGRATION] in CACHE_STATES
        val outcome = if (wasCache) {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            settings.setOnboardingDone(false)
            settings.setRestoreNotice(true)
            Outcome.CLEARED_CACHE
        } else {
            Outcome.KEPT_LIBRARY
        }
        store.edit { it.clear() }
        Log.i(TAG, "Upgraded from the server client: $outcome")
        return outcome
    }

    companion object {
        private const val TAG = "ServerClientUpgrade"
        private const val STORE_NAME = "server_auth"

        /** The client's `LegacyMigration` states in which the database had become the server's cache. */
        private val CACHE_STATES = setOf("DONE", "SKIPPED")
        private val LEGACY_MIGRATION = stringPreferencesKey("legacy_migration")

        /** The file DataStore keeps [STORE_NAME] in. */
        private fun file(context: Context): File = File(context.filesDir, "datastore/$STORE_NAME.preferences_pb")

        /** Whether the server client left its records; checked without opening anything. */
        fun isPending(context: Context): Boolean = file(context).exists()

        /** Runs the upgrade over the app's own stores, then deletes the client's emptied file. */
        suspend fun runFor(context: Context, database: MorningIssueDatabase, settings: SettingsRepository): Outcome {
            val outcome = ServerClientUpgrade(context.serverClientStore, database, settings).run()
            file(context).delete()
            return outcome
        }
    }
}

/** The 0.5 server client's account store, only ever read and emptied here. */
private val Context.serverClientStore: DataStore<Preferences> by preferencesDataStore(name = "server_auth")

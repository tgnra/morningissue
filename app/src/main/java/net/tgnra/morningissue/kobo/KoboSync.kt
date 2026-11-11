package net.tgnra.morningissue.kobo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.SyncPeerEntity

/**
 * The install's `dbId`: a random UUID made the first time it is asked for and kept in the
 * settings store. A Kobo that sees a different one drops everything it holds from this phone,
 * because a reinstalled app reuses Room IDs.
 */
class InstallId(private val dataStore: DataStore<Preferences>) {
    suspend fun get(): String {
        dataStore.data.first()[KEY]?.let { return it }
        var id = ""
        dataStore.edit { prefs -> id = prefs[KEY] ?: UUID.randomUUID().toString().also { prefs[KEY] = it } }
        return id
    }

    private companion object {
        val KEY = stringPreferencesKey("db_id")
    }
}

/** What the Sync screen's status line says. */
data class KoboSyncStatus(
    val state: State = State.STOPPED,
    /** Where a Kobo reaches the phone; null when it is not on a local network. */
    val address: LanAddress? = null,
    val port: Int = KoboProtocol.HTTP_PORT,
    /** The last thing a Kobo did while the screen was open. */
    val last: SyncActivity? = null,
) {
    enum class State { STOPPED, STARTING, LISTENING, PORT_IN_USE }
}

/**
 * The Kobo sync on the phone: the HTTP server and the discovery beacon, the pairing request
 * waiting for an answer, and the paired devices. [run] serves only while it is running, which
 * the Sync screen ties to its own lifecycle: nothing listens while the screen is not started.
 */
class KoboSync(
    private val database: MorningIssueDatabase,
    private val installId: InstallId,
    private val imageFile: (String) -> File,
    private val clock: Clock,
    /** The name the beacon gives ("Pixel 9"). */
    private val deviceName: () -> String,
) {
    val pairing = Pairing()
    private val running = Mutex()

    private val state = MutableStateFlow(KoboSyncStatus())
    val status: StateFlow<KoboSyncStatus> = state.asStateFlow()

    val peers: Flow<List<SyncPeerEntity>> = database.syncPeerDao().observeAll()

    suspend fun forget(deviceId: String) = database.syncPeerDao().forget(deviceId)

    /**
     * Serves and beacons until cancelled. A port another app holds is reported in [status], not
     * thrown. A second call waits for the first to finish, so that reopening the screen quickly
     * does not find its own port still taken.
     */
    suspend fun run() = running.withLock {
        val dbId = installId.get()
        val routes = KoboRoutes(database, pairing, { dbId }, imageFile, clock, onActivity = ::record)
        val server = LanHttpServer(KoboProtocol.HTTP_PORT, routes::handle)
        state.update { it.copy(state = KoboSyncStatus.State.STARTING) }
        try {
            coroutineScope {
                val payload = KoboProtocol.json.encodeToString(Beacon(name = deviceName(), dbId = dbId)).toByteArray()
                val beacon = launch {
                    BeaconSender().run(payload = { payload }, onAddress = { lan -> state.update { it.copy(address = lan) } })
                }
                try {
                    server.run(onListening = { state.update { it.copy(state = KoboSyncStatus.State.LISTENING) } })
                } catch (_: IOException) {
                    state.update { it.copy(state = KoboSyncStatus.State.PORT_IN_USE) }
                    // The beacon would point a Kobo at a port that is not ours.
                    beacon.cancel()
                }
            }
        } finally {
            // A pairing request still waiting is not denied: its connection closes, and the
            // Kobo reports that the phone went away rather than that the reader refused.
            state.update { it.copy(state = if (it.state == KoboSyncStatus.State.PORT_IN_USE) it.state else KoboSyncStatus.State.STOPPED) }
        }
    }

    /** A manifest fetch right after events keeps the count of the events, so the line reads as one sync. */
    private fun record(activity: SyncActivity) = state.update { current ->
        val previous = current.last
        val merged = activity.eventsMerged ?: previous?.takeIf { it.deviceId == activity.deviceId }?.eventsMerged
        current.copy(last = activity.copy(eventsMerged = merged))
    }
}

package net.tgnra.morningissue.client

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import net.tgnra.morningissue.data.archive.RoomArchiveTransfer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.settings.DataStoreSettingsRepository
import net.tgnra.morningissue.edition.MutableClock
import okhttp3.OkHttpClient

/**
 * The client as the app wires it ([net.tgnra.morningissue.DefaultAppContainer]), against a
 * [FakeServer], with its stores in [dir] and the cache in memory (or in [databaseFile]).
 */
internal class ClientKit(
    val server: FakeServer,
    private val dir: File,
    val clock: MutableClock = MutableClock(FakeServer.T0.plusSeconds(3_600)),
    databaseFile: File? = null,
) {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: MorningIssueDatabase = if (databaseFile == null) {
        MorningIssueDatabase.inMemory(context)
    } else {
        androidx.room3.Room.databaseBuilder<MorningIssueDatabase>(context, databaseFile.absolutePath)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .addMigrations(*net.tgnra.morningissue.data.db.Migrations.ALL)
            .build()
    }

    val auth = AuthStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "auth.preferences_pb") })
    val local = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "settings.preferences_pb") })
    private val http = OkHttpClient()
    val connection = ServerConnection(auth) { http }
    val outbox = Outbox(database, clock, ackedFloor = { auth.current().ackedSeq })
    val uploader = OutboxUploader(outbox, auth)
    val engagement = LocalEngagement(database, outbox, clock)
    val settings = ServerSettingsRepository(local, connection)

    /** How many times the cache was wiped because the server's database changed. */
    var wipes = 0

    val sync = ServerSync(
        database = database,
        connection = connection,
        auth = auth,
        outbox = outbox,
        uploader = uploader,
        engagement = engagement,
        settings = settings,
        clock = clock,
        resetCache = {
            wipes++
            database.clearAllTables()
        },
    )

    suspend fun resetLocalCache() {
        database.clearAllTables()
        auth.cacheReset(auth.current().dbId)
    }

    val account = AccountController(auth, connection, outbox, uploader, deviceName = { "Test phone" }, resetLocalCache = ::resetLocalCache)
    val uploadFile = File(dir, "legacy-upload.zip")
    val migration = LegacyUpload(
        scope = scope,
        database = database,
        auth = auth,
        connection = connection,
        exporter = RoomArchiveTransfer(database, clock, settings = { local.settings.first() }),
        sync = sync,
        uploadFile = uploadFile,
        resetLocalCache = ::resetLocalCache,
        pollMillis = 1,
    )
    val articles = CachedArticleRepository(database, engagement, connection, clock)
    val editions = CachedEditionRepository(database, engagement, sync, EditionFetcher(connection, uploader, CacheWriter(database)), local, clock)
    val koboDevices = KoboDevices(connection)

    /** Logged in to [server] with the migration settled, as after the first start. */
    suspend fun loggedIn() {
        server.mustChangePassword = false
        auth.loggedIn(server.url, server.token, server.username, server.dbId, mustChangePassword = false)
        auth.setLegacyMigration(LegacyMigration.DONE)
    }

    fun close() {
        scope.cancel()
        database.close()
        storeScope.cancel()
    }
}

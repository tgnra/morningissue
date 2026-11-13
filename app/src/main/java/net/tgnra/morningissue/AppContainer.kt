package net.tgnra.morningissue

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.client.AccountController
import net.tgnra.morningissue.client.AuthStore
import net.tgnra.morningissue.client.CacheWriter
import net.tgnra.morningissue.client.CachedArticleRepository
import net.tgnra.morningissue.client.CachedEditionRepository
import net.tgnra.morningissue.client.ClientSyncScheduler
import net.tgnra.morningissue.client.DiscoveryRepository
import net.tgnra.morningissue.client.EditionFetcher
import net.tgnra.morningissue.client.FeedFinder
import net.tgnra.morningissue.client.KoboDevices
import net.tgnra.morningissue.client.LegacyUpload
import net.tgnra.morningissue.client.LocalEngagement
import net.tgnra.morningissue.client.Outbox
import net.tgnra.morningissue.client.OutboxUploader
import net.tgnra.morningissue.client.PrintClient
import net.tgnra.morningissue.client.ServerConnection
import net.tgnra.morningissue.client.ServerDiscoveryRepository
import net.tgnra.morningissue.client.ServerFeedHealthRepository
import net.tgnra.morningissue.client.ServerFeedRepository
import net.tgnra.morningissue.client.ServerOpml
import net.tgnra.morningissue.client.ServerSettingsRepository
import net.tgnra.morningissue.client.ServerSync
import net.tgnra.morningissue.client.authDataStore
import net.tgnra.morningissue.data.archive.ArchiveController
import net.tgnra.morningissue.data.archive.RoomArchiveTransfer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.CoilDiskOriginals
import net.tgnra.morningissue.data.images.FileImageStore
import net.tgnra.morningissue.data.images.ImageFiles
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.images.StoredImageFetcher
import net.tgnra.morningissue.data.repository.ArticleRepository
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.repository.FeedHealthRepository
import net.tgnra.morningissue.data.repository.FeedRepository
import net.tgnra.morningissue.data.settings.DataStoreSettingsRepository
import net.tgnra.morningissue.data.settings.settingsDataStore
import net.tgnra.morningissue.data.storage.RoomStorageRepository
import net.tgnra.morningissue.data.storage.StorageRepository
import okhttp3.OkHttpClient

/**
 * Everything the UI and the workers may depend on. Hand-written dependency injection: there is
 * one instance per process, held by [MorningIssueApp]. Composables read it from
 * `LocalAppContainer`; workers and receivers use `Context.appContainer`.
 *
 * The phone is a client of a Morning Issue server (`docs/server-design.md`, 5): the database is
 * a cache of the account's library, the reader's actions go to the server through the outbox,
 * and editions are printed on the server.
 */
interface AppContainer {
    /** Server settings from `/settings`, cached for offline display, plus the device-local ones. */
    val settings: ServerSettingsRepository
    val feeds: FeedRepository
    val articles: ArticleRepository
    val editions: EditionRepository

    /** The Subscriptions desk: flagged feeds and their fixes. */
    val feedHealth: FeedHealthRepository

    /** The storage budget of the cache: stored article text and stored images. */
    val storage: StorageRepository

    /** Images kept in the app's files for display, re-encoded to fit; see [ImageStore]. */
    val images: ImageStore

    /** Archive export to a file the reader picks (a copy of the library). */
    val archive: ArchiveController

    /** Finds the feeds of a site or page (Add a feed), on the server. */
    val feedFinder: FeedFinder

    /** OPML import and export of the subscriptions, on the server. */
    val opml: ServerOpml

    /** Find sources: the server's suggestions, and the decisions on them. */
    val discovery: DiscoveryRepository

    /** Print now and the Extra edition, on the server. */
    val printClient: PrintClient

    /** Brings the cache up to date with the server. */
    val serverSync: ServerSync
    val syncScheduler: ClientSyncScheduler

    /** The account, and the sync bookkeeping. */
    val auth: AuthStore
    val account: AccountController

    /** The first start after updating from the app that kept everything on the phone. */
    val migration: LegacyUpload

    /** The account's devices, and Kobo pairing. */
    val koboDevices: KoboDevices

    val imageLoader: ImageLoader
    val clock: Clock

    /** Lives as long as the process; for work that must outlive a screen. */
    val appScope: CoroutineScope

    /** Empties the cache (every table, and the stored images) and forgets what it held. */
    suspend fun resetLocalCache()
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val clock: Clock = Clock.systemDefaultZone()

    /** The cache. Debug and benchmark tools reach it directly. */
    val database: MorningIssueDatabase by lazy { MorningIssueDatabase.create(appContext) }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // No call timeout: the legacy upload may take minutes on a slow connection.
            .build()
    }

    private val imageFiles: ImageFiles by lazy { ImageFiles.of(appContext) }

    override val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components {
                // In this order: a stored image answers before the network is asked.
                add(StoredImageFetcher.Factory(imageFiles))
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
    }

    override val images: ImageStore by lazy {
        FileImageStore(database, imageFiles, { okHttpClient }, clock, CoilDiskOriginals { imageLoader.diskCache })
    }

    private val localSettings by lazy { DataStoreSettingsRepository(appContext.settingsDataStore) }

    override val auth: AuthStore by lazy { AuthStore(appContext.authDataStore) }
    private val connection by lazy { ServerConnection(auth) { okHttpClient } }
    private val outbox by lazy { Outbox(database, clock, ackedFloor = { auth.current().ackedSeq }) }
    private val uploader by lazy { OutboxUploader(outbox, auth) }
    private val engagement by lazy { LocalEngagement(database, outbox, clock) }

    override val settings: ServerSettingsRepository by lazy { ServerSettingsRepository(localSettings, connection) }
    override val storage: StorageRepository by lazy { RoomStorageRepository(database, settings, clock, images = images) }
    override val syncScheduler: ClientSyncScheduler by lazy { ClientSyncScheduler(appContext) }

    override val serverSync: ServerSync by lazy {
        ServerSync(
            database = database,
            connection = connection,
            auth = auth,
            outbox = outbox,
            uploader = uploader,
            engagement = engagement,
            settings = settings,
            clock = clock,
            images = images,
            storage = storage,
            resetCache = ::wipeCache,
            onStand = { stand -> syncScheduler.aimAtRelease(stand.nextRelease.at, clock.instant()) },
        )
    }

    private val serverFeeds by lazy { ServerFeedRepository(database, connection, images) }
    override val feeds: FeedRepository get() = serverFeeds
    override val feedFinder: FeedFinder get() = serverFeeds
    override val articles: ArticleRepository by lazy { CachedArticleRepository(database, engagement, connection, clock) }
    override val editions: EditionRepository by lazy {
        CachedEditionRepository(database, engagement, serverSync, EditionFetcher(connection, uploader, CacheWriter(database)), settings, clock)
    }
    override val feedHealth: FeedHealthRepository by lazy { ServerFeedHealthRepository(database, connection, serverFeeds, clock) }
    override val discovery: DiscoveryRepository by lazy { ServerDiscoveryRepository(database, connection) }
    override val opml: ServerOpml by lazy { ServerOpml(connection, serverSync) }
    override val printClient: PrintClient by lazy { PrintClient(appScope, connection, serverSync, clock) }
    override val koboDevices: KoboDevices by lazy { KoboDevices(connection) }

    private val exporter by lazy { RoomArchiveTransfer(database, clock, settings = { localSettings.settings.first() }) }
    override val archive: ArchiveController by lazy { ArchiveController(appScope, appContext.contentResolver, exporter, storage) }

    override val account: AccountController by lazy {
        AccountController(
            auth = auth,
            connection = connection,
            outbox = outbox,
            uploader = uploader,
            deviceName = ::deviceName,
            resetLocalCache = ::resetLocalCache,
            onLoggedOut = { syncScheduler.cancelAll() },
        )
    }

    override val migration: LegacyUpload by lazy {
        LegacyUpload(
            scope = appScope,
            database = database,
            auth = auth,
            connection = connection,
            exporter = exporter,
            sync = serverSync,
            uploadFile = File(appContext.cacheDir, "legacy-upload.zip"),
            resetLocalCache = ::resetLocalCache,
        )
    }

    /**
     * The tables are emptied in place, not the database file deleted, so that the DAOs and the
     * flows the screens hold stay valid.
     */
    private suspend fun wipeCache() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        imageFiles.directory.deleteRecursively()
    }

    override suspend fun resetLocalCache() {
        wipeCache()
        auth.cacheReset(auth.current().dbId)
    }

    private fun deviceName(): String {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase() }
        return when {
            model.isEmpty() -> maker.ifEmpty { "Android" }
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }
}

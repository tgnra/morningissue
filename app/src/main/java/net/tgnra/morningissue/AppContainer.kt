package net.tgnra.morningissue

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.tgnra.morningissue.data.archive.ArchiveController
import net.tgnra.morningissue.data.archive.RoomArchiveTransfer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.discovery.CatalogueStore
import net.tgnra.morningissue.data.discovery.DeckStore
import net.tgnra.morningissue.data.discovery.DiscoveryRepository
import net.tgnra.morningissue.data.images.CoilDiskOriginals
import net.tgnra.morningissue.data.images.FileImageStore
import net.tgnra.morningissue.data.images.ImageFiles
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.images.StoredImageFetcher
import net.tgnra.morningissue.data.repository.ArticleRepository
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.repository.FeedHealthRepository
import net.tgnra.morningissue.data.repository.FeedRepository
import net.tgnra.morningissue.data.repository.RoomArticleRepository
import net.tgnra.morningissue.data.repository.RoomEditionRepository
import net.tgnra.morningissue.data.repository.RoomFeedRepository
import net.tgnra.morningissue.data.settings.DataStoreSettingsRepository
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.data.settings.settingsDataStore
import net.tgnra.morningissue.data.signals.EmbeddingBackfill
import net.tgnra.morningissue.data.signals.SignalsUpkeep
import net.tgnra.morningissue.data.signals.TraitsBackfill
import net.tgnra.morningissue.data.storage.RoomStorageRepository
import net.tgnra.morningissue.data.storage.StorageRepository
import net.tgnra.morningissue.edition.EarlyBuild
import net.tgnra.morningissue.edition.EditionModule
import net.tgnra.morningissue.edition.EditionPrinter
import net.tgnra.morningissue.edition.EditionPublisher
import net.tgnra.morningissue.edition.EditionScheduler
import net.tgnra.morningissue.edition.ReleaseTimes
import net.tgnra.morningissue.embedding.EmbedderAsset
import net.tgnra.morningissue.kobo.InstallId
import net.tgnra.morningissue.kobo.KoboImages
import net.tgnra.morningissue.relay.AndroidKeystoreWrapper
import net.tgnra.morningissue.relay.ImagePreparer
import net.tgnra.morningissue.relay.RelayCleanupWorker
import net.tgnra.morningissue.relay.RelaySettings
import net.tgnra.morningissue.relay.RelaySync
import net.tgnra.morningissue.sync.CandidateFetcher
import net.tgnra.morningissue.sync.FeedDiscovery
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedSyncer
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.OpmlTransfer
import net.tgnra.morningissue.sync.SyncModule
import net.tgnra.morningissue.sync.SyncScheduler
import okhttp3.OkHttpClient

/**
 * Everything the UI and the workers may depend on. Hand-written dependency injection: there is
 * one instance per process, held by [MorningIssueApp]. Composables read it from
 * `LocalAppContainer`; workers and receivers use `Context.appContainer`.
 */
interface AppContainer {
    val settings: SettingsRepository
    val feeds: FeedRepository
    val articles: ArticleRepository
    val editions: EditionRepository

    /** The Subscriptions desk: flagged feeds and their fixes. */
    val feedHealth: FeedHealthRepository

    /** The storage budget: usage and trimming of stored article text and stored images. */
    val storage: StorageRepository

    /** Images kept in the app's files for display, re-encoded to fit; see [ImageStore]. */
    val images: ImageStore

    /** Archive export and import to a file the reader picks. */
    val archive: ArchiveController

    /** The text embedder, loaded from its asset on first use. */
    val embedder: EmbedderAsset

    /** Fills in the traits and embeddings the within-feed ranking reads; see [SignalsUpkeep]. */
    val signals: SignalsUpkeep

    val feedSyncer: FeedSyncer
    val syncScheduler: SyncScheduler
    val feedDiscovery: FeedDiscovery
    val fullTextFetcher: FullTextFetcher
    val opmlTransfer: OpmlTransfer

    /** Find sources: suggestions from the bundled catalogue, and the decisions on them. */
    val discovery: DiscoveryRepository

    /** Live fetches of the suggested feeds on screen, for discovery's cards. */
    val candidates: CandidateFetcher

    val editionPublisher: EditionPublisher
    val editionScheduler: EditionScheduler

    /** Changes the reset time and the Evening edition's time so that they apply from the next release. */
    val releaseTimes: ReleaseTimes

    /** Print now: the foreground build of an edition that was not printed in the background. */
    val editionPrinter: EditionPrinter

    /** Sync with a Kobo, through the relay server built into the app; see `docs/relay-sync.md`. */
    val relaySync: RelaySync

    val imageLoader: ImageLoader
    val clock: Clock

    /** Lives as long as the process; for work that must outlive a screen. */
    val appScope: CoroutineScope
}

/**
 * The shared infrastructure that `SyncModule` and `EditionModule` build on. Sync and edition
 * code may use the DAOs on [database] directly; UI code goes through the repositories.
 */
class CoreComponents(
    val appContext: Context,
    val appScope: CoroutineScope,
    val clock: Clock,
    val database: MorningIssueDatabase,
    /** Shared client; call `newBuilder()` for per-use timeouts or interceptors. */
    val okHttpClient: OkHttpClient,
    /** Coil loader backed by [okHttpClient]; it reads [images] first and the network after. */
    val imageLoader: ImageLoader,
    /** The image store; swept after every full sync. */
    val images: ImageStore,
    val settings: SettingsRepository,
    val feeds: FeedRepository,
    val articles: ArticleRepository,
    val storage: StorageRepository,
    /** Run after every full sync, with the other housekeeping. */
    val signals: SignalsUpkeep,
)

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val clock: Clock = Clock.systemDefaultZone()

    private val database: MorningIssueDatabase by lazy { MorningIssueDatabase.create(appContext) }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
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

    override val settings: SettingsRepository by lazy { DataStoreSettingsRepository(appContext.settingsDataStore) }
    override val feeds: FeedRepository by lazy { RoomFeedRepository(database, clock, images = images) }
    override val articles: ArticleRepository by lazy { RoomArticleRepository(database, clock) }
    override val storage: StorageRepository by lazy { RoomStorageRepository(database, settings, clock, images = images) }
    override val archive: ArchiveController by lazy {
        ArchiveController(appScope, appContext.contentResolver, RoomArchiveTransfer(database, clock), storage)
    }

    override val embedder: EmbedderAsset by lazy { EmbedderAsset(appContext) }
    override val signals: SignalsUpkeep by lazy {
        SignalsUpkeep(TraitsBackfill(database), EmbeddingBackfill(database, embedder = { embedder.get() }))
    }

    val core: CoreComponents by lazy {
        CoreComponents(
            appContext = appContext,
            appScope = appScope,
            clock = clock,
            database = database,
            okHttpClient = okHttpClient,
            imageLoader = imageLoader,
            images = images,
            settings = settings,
            feeds = feeds,
            articles = articles,
            storage = storage,
            signals = signals,
        )
    }

    private val sync by lazy { SyncModule.create(core) }
    private val edition by lazy { EditionModule.create(core, sync) }

    override val feedSyncer: FeedSyncer get() = sync.feedSyncer
    override val syncScheduler: SyncScheduler get() = sync.syncScheduler
    override val feedDiscovery: FeedDiscovery get() = sync.feedDiscovery
    override val fullTextFetcher: FullTextFetcher get() = sync.fullTextFetcher
    override val opmlTransfer: OpmlTransfer get() = sync.opmlTransfer
    override val feedHealth: FeedHealthRepository get() = sync.feedHealth

    override val discovery: DiscoveryRepository by lazy {
        DiscoveryRepository(database, CatalogueStore.of(appContext), feeds, DeckStore.Prefs(appContext), clock, images = images)
    }
    override val candidates: CandidateFetcher by lazy {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        CandidateFetcher(
            fetcher = FeedFetcher(okHttpClient),
            cacheDir = appContext.cacheDir,
            clock = clock,
            isMetered = { connectivity?.isActiveNetworkMetered ?: true },
            embedder = { embedder.get() },
        )
    }

    override val editionPublisher: EditionPublisher get() = edition.publisher
    override val editionScheduler: EditionScheduler get() = edition.scheduler
    override val releaseTimes: ReleaseTimes get() = edition.releaseTimes
    override val editionPrinter: EditionPrinter by lazy {
        EditionPrinter(appScope, earlyBuild = { EarlyBuild.from(this) }, clock = clock)
    }

    override val editions: EditionRepository by lazy { RoomEditionRepository(database, editionPublisher, clock) }

    /** The image preparation of the Kobo sync; each package makes one, over one cache. */
    private fun newKoboImages() = KoboImages(
        storeFile = imageFiles::fileFor,
        client = { okHttpClient },
        cacheDirectory = File(appContext.cacheDir, "kobo-images"),
        originals = CoilDiskOriginals { imageLoader.diskCache },
    )

    private val relaySyncLazy = lazy {
        RelaySync(
            database = database,
            settings = RelaySettings(appContext.settingsDataStore),
            keys = AndroidKeystoreWrapper(),
            installId = InstallId(appContext.settingsDataStore),
            client = { okHttpClient },
            newImages = { newKoboImages().let { images -> ImagePreparer(images::prepare) } },
            fullText = fullTextFetcher,
            cacheDirectory = appContext.cacheDir,
            clock = clock,
            allowLocalHttp = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            scope = appScope,
            onLeft = { RelayCleanupWorker.enqueue(appContext) },
        )
    }
    override val relaySync: RelaySync by relaySyncLazy

    /** Relay sync if anything has used it yet; a session can only be open if so. */
    val relaySyncIfMade: RelaySync? get() = if (relaySyncLazy.isInitialized()) relaySync else null
}

package net.tgnra.morningissue

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.tgnra.morningissue.data.archive.ArchiveController
import net.tgnra.morningissue.data.archive.RoomArchiveTransfer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
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
import net.tgnra.morningissue.data.storage.RoomStorageRepository
import net.tgnra.morningissue.data.storage.StorageRepository
import net.tgnra.morningissue.edition.EarlyBuild
import net.tgnra.morningissue.edition.EditionModule
import net.tgnra.morningissue.edition.EditionPrinter
import net.tgnra.morningissue.edition.EditionPublisher
import net.tgnra.morningissue.edition.EditionScheduler
import net.tgnra.morningissue.edition.ReleaseTimes
import net.tgnra.morningissue.sync.FeedDiscovery
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

    /** The storage budget: usage and trimming of stored article text. */
    val storage: StorageRepository

    /** Archive export and import to a file the reader picks. */
    val archive: ArchiveController

    val feedSyncer: FeedSyncer
    val syncScheduler: SyncScheduler
    val feedDiscovery: FeedDiscovery
    val fullTextFetcher: FullTextFetcher
    val opmlTransfer: OpmlTransfer

    val editionPublisher: EditionPublisher
    val editionScheduler: EditionScheduler

    /** Changes the reset time and the Evening edition's time so that they apply from the next release. */
    val releaseTimes: ReleaseTimes

    /** Print now: the foreground build of an edition that was not printed in the background. */
    val editionPrinter: EditionPrinter

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
    /** Coil loader backed by [okHttpClient]; its disk cache is what edition prefetch fills. */
    val imageLoader: ImageLoader,
    val settings: SettingsRepository,
    val feeds: FeedRepository,
    val articles: ArticleRepository,
    val storage: StorageRepository,
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

    override val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .build()
    }

    override val settings: SettingsRepository by lazy { DataStoreSettingsRepository(appContext.settingsDataStore) }
    override val feeds: FeedRepository by lazy { RoomFeedRepository(database, clock) }
    override val articles: ArticleRepository by lazy { RoomArticleRepository(database, clock) }
    override val storage: StorageRepository by lazy { RoomStorageRepository(database, settings, clock) }
    override val archive: ArchiveController by lazy {
        ArchiveController(appScope, appContext.contentResolver, RoomArchiveTransfer(database, clock), storage)
    }

    val core: CoreComponents by lazy {
        CoreComponents(
            appContext = appContext,
            appScope = appScope,
            clock = clock,
            database = database,
            okHttpClient = okHttpClient,
            imageLoader = imageLoader,
            settings = settings,
            feeds = feeds,
            articles = articles,
            storage = storage,
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

    override val editionPublisher: EditionPublisher get() = edition.publisher
    override val editionScheduler: EditionScheduler get() = edition.scheduler
    override val releaseTimes: ReleaseTimes get() = edition.releaseTimes
    override val editionPrinter: EditionPrinter by lazy {
        EditionPrinter(appScope, earlyBuild = { EarlyBuild.from(appContext, this) }, clock = clock)
    }

    override val editions: EditionRepository by lazy { RoomEditionRepository(database, editionPublisher, clock) }
}

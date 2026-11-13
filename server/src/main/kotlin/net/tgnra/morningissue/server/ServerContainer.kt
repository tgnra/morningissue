package net.tgnra.morningissue.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.server.auth.DbAuthService
import net.tgnra.morningissue.server.auth.DbDeviceService
import net.tgnra.morningissue.server.config.ServerConfig
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.BuildRunner
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.editions.EditionJobActions
import net.tgnra.morningissue.server.editions.EditionPublisher
import net.tgnra.morningissue.server.editions.EditionServiceImpl
import net.tgnra.morningissue.server.editions.EditionTimings
import net.tgnra.morningissue.server.editions.Jobs
import net.tgnra.morningissue.server.editions.KoboImageServiceImpl
import net.tgnra.morningissue.server.editions.KoboImages
import net.tgnra.morningissue.server.editions.Scheduler
import net.tgnra.morningissue.server.editions.SettingsServiceImpl
import net.tgnra.morningissue.server.editions.StandServiceImpl
import net.tgnra.morningissue.server.editions.UserSettingsStore
import net.tgnra.morningissue.server.feeds.DefaultArticleService
import net.tgnra.morningissue.server.feeds.DefaultFeedRefresher
import net.tgnra.morningissue.server.feeds.DefaultFeedService
import net.tgnra.morningissue.server.feeds.DefaultFullTextService
import net.tgnra.morningissue.server.feeds.DefaultSearchService
import net.tgnra.morningissue.server.feeds.DiscoveryCleanup
import net.tgnra.morningissue.server.feeds.FeedGarbageCollector
import net.tgnra.morningissue.server.feeds.FeedHealthChecker
import net.tgnra.morningissue.server.feeds.OpmlTransfer
import net.tgnra.morningissue.server.feeds.Subscriptions
import net.tgnra.morningissue.server.feeds.TeaserBackfill
import net.tgnra.morningissue.server.feeds.discovery.CatalogueStore
import net.tgnra.morningissue.server.feeds.discovery.DefaultDiscoveryService
import net.tgnra.morningissue.server.feeds.signals.EmbeddingBackfill
import net.tgnra.morningissue.server.feeds.signals.SignalsUpkeep
import net.tgnra.morningissue.server.feeds.signals.TraitsBackfill
import net.tgnra.morningissue.server.http.ServerServices
import net.tgnra.morningissue.server.importer.ArchiveImporter
import net.tgnra.morningissue.server.importer.ImportServiceImpl
import net.tgnra.morningissue.server.sync.Relearner
import net.tgnra.morningissue.server.sync.SyncServiceImpl
import net.tgnra.morningissue.sync.CandidateFetcher
import net.tgnra.morningissue.sync.DefaultFeedDiscovery
import net.tgnra.morningissue.sync.FeedFetcher
import net.tgnra.morningissue.sync.FeedRepairer
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * The whole server, wired by hand like the app's `AppContainer`: one database, one HTTP
 * client, one long-lived coroutine scope, and every service the routes call. [start] restores
 * and starts the scheduler (for `serve` only); [close] stops the background work, then the
 * database.
 *
 * The hooks between workstreams are made here:
 * - a subscription change schedules an affinity relearn ([Relearner.schedule]);
 * - a refresh that stored new posts runs the signals upkeep (traits, then embeddings);
 * - the scheduler's periodic refresh is followed by the health check with repair, discovery
 *   clean-up and, once a day, the global garbage collection;
 * - an import is followed by the signals upkeep, the teaser backfill and a relearn;
 * - the desk notice counts feeds the way the Subscriptions desk shows them.
 */
class ServerContainer(
    override val config: ServerConfig,
    private val clock: Clock = Clock.systemUTC(),
) : Container {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val database: ServerDatabase = ServerDatabase.open(config.databaseFile)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The edition timings from `[refresh]` and `[editions]`. */
    val timings: EditionTimings = timingsOf(config)

    private val fetcher = FeedFetcher(httpClient)
    private val feedDiscovery = DefaultFeedDiscovery(fetcher, clock)

    // Text embedding, loaded from the classpath on first use.
    private val embedderLock = Mutex()

    @Volatile
    private var loadedEmbedder: StaticEmbedder? = null

    private val embedder: suspend () -> StaticEmbedder = {
        loadedEmbedder ?: embedderLock.withLock {
            loadedEmbedder ?: withContext(Dispatchers.IO) {
                val stream = ServerContainer::class.java.classLoader.getResourceAsStream(StaticEmbedder.ASSET_PATH)
                    ?: error("No ${StaticEmbedder.ASSET_PATH} on the classpath")
                stream.use(StaticEmbedder::load)
            }.also { loadedEmbedder = it }
        }
    }

    private val signalsUpkeep = SignalsUpkeep(TraitsBackfill(database), EmbeddingBackfill(database, embedder))

    private val settingsStore = UserSettingsStore(database, clock)
    private val relearner = Relearner(database, scope, clock)

    private val fullText = DefaultFullTextService(database, fetcher, clock)

    /** The shared feed refresher; every fetch of a feed goes through it. */
    val refresher: DefaultFeedRefresher = DefaultFeedRefresher(
        database,
        fetcher,
        clock,
        scope,
        parallelism = config.refresh.concurrency,
        fullText = fullText,
        afterBatch = { signalsUpkeep.run() },
    )

    private val subscriptions = Subscriptions(database, clock, onChanged = { userId -> relearner.schedule(userId) })
    private val healthChecker = FeedHealthChecker(database, FeedRepairer(fetcher, feedDiscovery, clock), clock)
    private val opml = OpmlTransfer(database, subscriptions, clock)
    private val discoveryCleanup = DiscoveryCleanup(database, clock)
    private val garbageCollector = FeedGarbageCollector(database)
    private val teaserBackfill = TeaserBackfill(database, fullText, clock)

    private val publisher = EditionPublisher(database, settingsStore, clock)
    private val bundles = EditionBundles(database, clock)
    private val koboImages = KoboImageServiceImpl(
        database,
        KoboImages(client = { httpClient }, cacheDirectory = config.koboImagesDir, maxCacheBytes = config.kobo.imageCacheBytes),
        bundles,
    )
    private val runner = BuildRunner(database, publisher, refresher, fullText, koboImages, settingsStore, clock, timings)
    private val jobs = Jobs(scope, clock, EditionJobActions(database, runner, publisher, refresher, settingsStore, clock, timings))

    private val feedService = DefaultFeedService(database, subscriptions, refresher, healthChecker, feedDiscovery, opml, jobs, clock)

    private val discovery = DefaultDiscoveryService(
        database,
        CatalogueStore.of(config.dataDir),
        subscriptions,
        feedService,
        CandidateFetcher(fetcher, cacheDir = java.io.File(config.cacheDir, "candidates"), clock = clock, isMetered = { false }, embedder = embedder),
        clock,
        zoneOf = { userId -> settingsStore.get(userId).zone },
    )

    private val imports = ImportServiceImpl(
        database,
        ArchiveImporter(database, settingsStore, clock),
        jobs,
        clock,
        afterImport = { userId ->
            signalsUpkeep.run()
            teaserBackfill.run()
            relearner.relearnNow(userId)
        },
    )

    /** Builds editions ahead of releases and refreshes every feed on the interval. */
    val scheduler: Scheduler = Scheduler(
        database, settingsStore, runner, refresher, clock, scope, timings,
        maintenance = { maintenance() },
    )

    override val services: ServerServices = ServerServices(
        auth = DbAuthService(database, config.initialPasswordsDir, clock),
        devices = DbDeviceService(database, config.publicUrl, clock),
        feeds = feedService,
        articles = DefaultArticleService(database, fullText),
        search = DefaultSearchService(database, clock),
        discovery = discovery,
        editions = EditionServiceImpl(database, bundles, settingsStore, clock, needingAttention = feedService::needingAttention),
        stand = StandServiceImpl(database, settingsStore, clock, StandServiceImpl.runningPrintOf(jobs)),
        jobs = jobs,
        sync = SyncServiceImpl(database, bundles, clock, relearn = { userId -> relearner.schedule(userId) }, fullText = fullText, scope = scope),
        settings = SettingsServiceImpl(database, settingsStore, clock),
        imports = imports,
        koboImages = koboImages,
    )

    @Volatile
    private var lastGcAt: Instant? = null

    @Volatile
    private var schedulerJob: Job? = null

    override fun start() {
        if (schedulerJob != null) return
        schedulerJob = scope.launch {
            scheduler.restore()
            scheduler.start().join()
        }
    }

    /** After each periodic refresh: health checks with repair, discovery clean-up, and the daily GC. Each step's failure is logged and does not stop the next. */
    private suspend fun maintenance() {
        step("Feed health check") { healthChecker.run(repair = true) }
        step("Discovery clean-up") { discoveryCleanup.run() }
        val now = clock.instant()
        val last = lastGcAt
        if (last == null || Duration.between(last, now) >= GC_INTERVAL) {
            lastGcAt = now
            step("Garbage collection") { garbageCollector.run() }
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("{} failed", name, e)
        }
    }

    override fun close() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        database.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerContainer::class.java)

        /** Feeds and posts nobody refers to are collected at most this often. */
        val GC_INTERVAL: Duration = Duration.ofHours(24)

        /** `[refresh]` and `[editions]` as the scheduler and the build runner take them; the rest keep their defaults. */
        fun timingsOf(config: ServerConfig): EditionTimings = EditionTimings(
            buildLead = config.editions.buildLead,
            prePrintMaxAge = config.refresh.prePrintMaxAge,
            refreshInterval = config.refresh.interval,
            refreshTimeout = config.refresh.timeout,
        )
    }
}

package net.tgnra.morningissue.kobo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.SyncPeerEntity
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.sync.FullTextFetcher

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

/**
 * The width each paired Kobo last asked images at, kept in the settings store so that the
 * next opening of the Sync screen prepares that size. Only a hint: losing it costs the
 * pre-warm a guess at [KoboImageConfig.DEFAULT_WIDTH].
 */
class KoboImageWidths(private val dataStore: DataStore<Preferences>) {
    suspend fun all(): Map<String, Int> = parse(dataStore.data.first()[KEY])

    /** Records [width] for [deviceId] when it differs from what is stored. */
    suspend fun record(deviceId: String, width: Int) {
        dataStore.edit { prefs ->
            val current = parse(prefs[KEY])
            if (current[deviceId] != width) prefs[KEY] = format(current + (deviceId to width))
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("kobo_image_widths")

        /** `deviceId=width` pairs, one per line; a line that does not read is dropped. */
        fun parse(stored: String?): Map<String, Int> = stored.orEmpty().lines().mapNotNull { line ->
            val id = line.substringBeforeLast('=', "")
            val width = line.substringAfterLast('=', "").toIntOrNull()
            if (id.isEmpty() || width == null) null else id to width
        }.toMap()

        fun format(widths: Map<String, Int>): String = widths.entries.joinToString("\n") { "${it.key}=${it.value}" }

        /** The widths to prepare for [paired] devices: each one's last, or the default for one that has not asked yet. */
        fun toPrepare(paired: List<String>, known: Map<String, Int>): List<Int> =
            paired.map { known[it] ?: KoboImageConfig.DEFAULT_WIDTH }.ifEmpty { listOf(KoboImageConfig.DEFAULT_WIDTH) }.distinct()
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
    /** Images prepared ahead of the Kobo so far; null when none are being prepared. */
    val images: ImageProgress? = null,
) {
    enum class State { STOPPED, STARTING, LISTENING, PORT_IN_USE }
}

data class ImageProgress(val done: Int, val total: Int)

/**
 * The Kobo sync on the phone: the HTTP server and the discovery beacon, the pairing request
 * waiting for an answer, and the paired devices. [run] serves only while it is running, which
 * the Sync screen ties to its own lifecycle: nothing listens while the screen is not started.
 *
 * While it runs it also gets ahead of the Kobo ([prewarm]): it fetches the full text of saved
 * articles that lack it, and prepares the images of everything the manifest offers.
 */
class KoboSync(
    private val database: MorningIssueDatabase,
    private val installId: InstallId,
    /** Makes the image preparation for one run; a fresh one forgets which addresses failed last time. */
    private val newImages: () -> KoboImages,
    private val fullText: FullTextFetcher,
    private val widths: KoboImageWidths,
    private val clock: Clock,
    /** The name the beacon gives ("Pixel 9"). */
    private val deviceName: () -> String,
) {
    val pairing = Pairing()
    private val running = Mutex()

    private val state = MutableStateFlow(KoboSyncStatus())
    val status: StateFlow<KoboSyncStatus> = state.asStateFlow()

    /** Devices paired for local-network sync; relay pairings are on the Relay screen. */
    val peers: Flow<List<SyncPeerEntity>> = database.syncPeerDao().observeLan()

    suspend fun forget(deviceId: String) = database.syncPeerDao().forget(deviceId)

    /**
     * Serves and beacons until cancelled. A port another app holds is reported in [status], not
     * thrown. A second call waits for the first to finish, so that reopening the screen quickly
     * does not find its own port still taken.
     */
    suspend fun run() = running.withLock {
        val dbId = installId.get()
        val images = newImages()
        val known = widths.all().toMutableMap()
        val routes = KoboRoutes(
            database, pairing, { dbId }, images, clock, onActivity = ::record,
            onImageWidth = { deviceId, width ->
                if (known[deviceId] != width) {
                    known[deviceId] = width
                    widths.record(deviceId, width)
                }
            },
        )
        val server = LanHttpServer(KoboProtocol.HTTP_PORT, routes::handle)
        state.update { it.copy(state = KoboSyncStatus.State.STARTING) }
        try {
            coroutineScope {
                val payload = KoboProtocol.json.encodeToString(Beacon(name = deviceName(), dbId = dbId)).toByteArray()
                val beacon = launch {
                    BeaconSender().run(payload = { payload }, onAddress = { lan -> state.update { it.copy(address = lan) } })
                }
                val warm = launch {
                    try {
                        prewarm(images)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Getting ahead is a nicety: the Kobo's own requests are served regardless.
                        state.update { it.copy(images = null) }
                    }
                }
                try {
                    server.run(onListening = { state.update { it.copy(state = KoboSyncStatus.State.LISTENING) } })
                } catch (_: IOException) {
                    state.update { it.copy(state = KoboSyncStatus.State.PORT_IN_USE) }
                    // The beacon would point a Kobo at a port that is not ours, and nothing will ask for the images.
                    beacon.cancel()
                    warm.cancel()
                }
            }
        } finally {
            // A pairing request still waiting is not denied: its connection closes, and the
            // Kobo reports that the phone went away rather than that the reader refused.
            state.update { it.copy(state = if (it.state == KoboSyncStatus.State.PORT_IN_USE) it.state else KoboSyncStatus.State.STOPPED, images = null) }
        }
    }

    /**
     * Gets ahead of the Kobo while the screen is open, and stops when it closes: fetches the
     * full text of every saved article that has none (`GET /v1/saved` changes version as it
     * arrives), and meanwhile prepares the images of the offered editions and the saved
     * articles at the width each paired Kobo last asked for, so that its requests find them
     * ready. The saved articles' images are gone over again once their text is in.
     */
    suspend fun prewarm(images: KoboImages) = coroutineScope {
        val exporter = KoboExporter(database, clock)
        val text = launch { fetchSavedText() }
        val sizes = KoboImageWidths.toPrepare(database.syncPeerDao().all().filter { it.isLanPaired }.map { it.deviceId }, widths.all())
        val progress = Progress()
        val first = exporter.offeredArticles()
        val firstUrls = BundleExport.imageUrls(first.editions + first.saved)
        progress.add(firstUrls.size)
        prepareAll(images, firstUrls, sizes, progress)
        text.join()
        val again = BundleExport.imageUrls(exporter.offeredArticles().saved) - firstUrls.toSet()
        progress.add(again.size)
        prepareAll(images, again, sizes, progress)
        state.update { it.copy(images = null) }
    }

    /** Counts the addresses prepared, at every width, for the status line. */
    private inner class Progress {
        private val done = AtomicInteger()

        @Volatile private var total = 0

        fun add(images: Int) {
            total += images
            show()
        }

        fun step() {
            done.incrementAndGet()
            show()
        }

        private fun show() {
            val t = total
            state.update { it.copy(images = ImageProgress(done.get(), t)) }
        }
    }

    private suspend fun prepareAll(images: KoboImages, urls: List<String>, sizes: List<Int>, progress: Progress) = coroutineScope {
        val permits = Semaphore(PREWARM_PARALLEL)
        for (url in urls) {
            permits.acquire()
            launch {
                try {
                    images.prepare(url, sizes)
                } finally {
                    permits.release()
                    progress.step()
                }
            }
        }
    }

    private suspend fun fetchSavedText() = fetchSavedFullText(database, fullText)

    private companion object {
        /** Images prepared at once ahead of the Kobo; downloads among them are capped by `KoboImageConfig.PARALLEL_FETCHES`. */
        const val PREWARM_PARALLEL = 4
    }

    /** A manifest fetch right after events keeps the count of the events, so the line reads as one sync. */
    private fun record(activity: SyncActivity) = state.update { current ->
        val previous = current.last
        val merged = activity.eventsMerged ?: previous?.takeIf { it.deviceId == activity.deviceId }?.eventsMerged
        current.copy(last = activity.copy(eventsMerged = merged))
    }
}

/** Saved articles' pages fetched at once, as the edition prefetch does. */
private const val TEXT_PARALLEL = 4

/**
 * The reader's full-text fetch for each saved article whose page has not been fetched yet, so
 * that the saved shelf a Kobo gets, over the local network or a relay, carries the full text.
 */
suspend fun fetchSavedFullText(database: MorningIssueDatabase, fullText: FullTextFetcher) = coroutineScope {
    val articles = database.articleDao()
    val permits = Semaphore(TEXT_PARALLEL)
    for (row in articles.getSaved()) {
        val id = row.article.id
        if (articles.getContentSource(id) == ContentSource.FETCHED) continue
        launch { permits.withPermit { fullText.ensureFullText(id) } }
    }
}

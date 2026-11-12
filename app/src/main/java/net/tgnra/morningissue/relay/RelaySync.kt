package net.tgnra.morningissue.relay

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.SyncPeerEntity
import net.tgnra.morningissue.kobo.InstallId
import net.tgnra.morningissue.kobo.KoboEventMerger
import net.tgnra.morningissue.kobo.KoboEvents
import net.tgnra.morningissue.kobo.KoboExporter
import net.tgnra.morningissue.kobo.KoboImageConfig
import net.tgnra.morningissue.kobo.KoboProtocol
import net.tgnra.morningissue.kobo.PreparedImage
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.kobo.fetchSavedFullText
import net.tgnra.morningissue.sync.FullTextFetcher
import okhttp3.OkHttpClient

/** Prepares an image at several widths, as `GET /v1/images` would; `KoboImages.prepare` in the app. */
fun interface ImagePreparer {
    suspend fun prepare(url: String, widths: List<Int>): Map<Int, PreparedImage>
}

/** The changes plaintext: `{"deviceId": "…", "events": [ … ]}`. */
@Serializable
data class RelayChanges(val deviceId: String, val events: List<SyncEvent>)

/** Why a relay task did not finish, in terms the screen can put plainly. */
enum class RelayProblem {
    /** The build has no relay address. */
    NO_ADDRESS,

    /** The address is not `https://`. */
    NOT_HTTPS,

    /** The address does not read as one. */
    BAD_ADDRESS,

    /** The relay could not be reached, or the connection broke. */
    NETWORK,

    /** The relay wants a registration token, and the build's is missing or wrong (`403`). */
    REGISTRATION_REFUSED,

    /** The relay refused the phone's signature even after registering again (`401`). */
    UNAUTHORIZED,

    /** Over one of the relay's size limits (`413`). */
    TOO_LARGE,

    /** Over one of the relay's count limits (`429`). */
    TOO_MANY,

    /** Anything else the relay answered. */
    RELAY_ERROR,

    /** Building the package failed on the phone. */
    PHONE_ERROR,
}

/** What a task is doing, for the screen. */
sealed interface RelayProgress {
    data object Connecting : RelayProgress
    data class PreparingImages(val done: Int, val total: Int) : RelayProgress
    data class Uploading(val bytes: Long) : RelayProgress
    data object Publishing : RelayProgress
    data class Collecting(val done: Int, val total: Int) : RelayProgress
}

/** How a task ended. */
sealed interface RelayResult {
    val at: Instant

    data class Published(override val at: Instant, val devices: Int, val editions: Int, val saved: Int, val images: Int, val bytes: Long) : RelayResult

    /** Nothing to publish to: no device is paired for relay sync. */
    data class NoDevices(override val at: Instant) : RelayResult

    /**
     * Done finished: [batches] collected, [events] merged from them, [dropped] batches that
     * could not be read or did not belong to the key that sent them, and [left] that could not
     * be downloaded and stay on the relay for next time.
     */
    data class Collected(override val at: Instant, val batches: Int, val events: Int, val dropped: Int, val left: Int) : RelayResult

    data class Paired(override val at: Instant, val name: String, val toldRelay: Boolean, val problem: RelayProblem?) : RelayResult

    data class PairingRefused(override val at: Instant, val problem: PairingProblem) : RelayResult

    data class Forgotten(override val at: Instant, val toldRelay: Boolean, val problem: RelayProblem?) : RelayResult

    data class Failed(override val at: Instant, val task: RelayTask, val problem: RelayProblem, val status: Int? = null) : RelayResult
}

enum class RelayTask { PUBLISH, DONE, PAIR, FORGET }

data class RelayActivity(val running: RelayTask? = null, val progress: RelayProgress? = null, val last: RelayResult? = null)

/** A relay device's line from `GET /v1/group/status`, matched to its pairing. */
data class RelayDeviceStatus(val lastSeenAt: Instant?, val hasCurrentPackage: Boolean, val pendingChanges: Int)

/** `GET /v1/group/status` by `deviceId`, or why it could not be read. */
data class RelayGroupStatus(
    val devices: Map<String, RelayDeviceStatus> = emptyMap(),
    val packagePublishedAt: Instant? = null,
    val hasPackage: Boolean = false,
    val problem: RelayProblem? = null,
)

/**
 * The phone's side of relay sync (`docs/relay-sync.md`): pairing from a device's code, the
 * device list, Publish and Done. One task runs at a time.
 *
 * Merging goes through [KoboEventMerger], as protocol v1 merged events: per device, only events
 * above its `ackedSeq`, in one transaction that stores the new `ackedSeq` and relearns affinity
 * when what it learns from changed.
 */
class RelaySync(
    private val database: MorningIssueDatabase,
    private val settings: RelaySettings,
    private val keys: KeyWrapper,
    private val installId: InstallId,
    /** The app's shared client. */
    private val client: () -> OkHttpClient,
    /** Makes the image preparation for one package. */
    private val newImages: () -> ImagePreparer,
    /** Fetches saved articles' full text before a package is built; null skips it. */
    private val fullText: FullTextFetcher?,
    /** Where the package is written before upload; it is deleted after. */
    private val cacheDirectory: File,
    private val clock: Clock,
    /** Debug builds: lets `http://` through for a relay on this machine or the emulator's host. */
    private val allowLocalHttp: Boolean,
    /** For work started from the screen, so that it carries on if the screen is left. */
    private val scope: CoroutineScope,
    /** The size of each part the package is uploaded in (smaller in tests). */
    private val partBytes: Long = RelayClient.PART_BYTES,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(RelayActivity())
    val activity: StateFlow<RelayActivity> = state.asStateFlow()

    val config: Flow<RelayConfig> = settings.config
    val peers: Flow<List<SyncPeerEntity>> = database.syncPeerDao().observeRelay()

    private val peerDao = database.syncPeerDao()
    private val merger = KoboEventMerger(database, clock)

    /** Reads [text] as requests to the relay would, for the screen to show the address in use. */
    fun parseAddress(text: String): RelayAddress.Parse = RelayAddress.parse(text, allowLocalHttp)

    // --- Tasks started from the screen -----------------------------------------------------

    fun startPublish() = start(RelayTask.PUBLISH) { publish() }

    fun startDone() = start(RelayTask.DONE) { done() }

    fun startPair(text: String) = start(RelayTask.PAIR) { pair(text) }

    fun startForget(deviceId: String) = start(RelayTask.FORGET) { forget(deviceId) }

    private fun start(task: RelayTask, block: suspend () -> RelayResult) {
        if (state.value.running != null) return
        state.update { it.copy(running = task, progress = null) }
        scope.launch {
            try {
                block()
            } finally {
                state.update { it.copy(running = null, progress = null) }
            }
        }
    }

    private fun progress(progress: RelayProgress) = state.update { it.copy(progress = progress) }

    private fun finish(result: RelayResult): RelayResult {
        state.update { it.copy(last = result) }
        return result
    }

    // --- Background entry points ----------------------------------------------------------

    /** After the early build: Done, so the package carries the latest, then Publish. Only when a relay and a device are set. */
    suspend fun afterEarlyBuild() {
        if (!isActive()) return
        done()
        publish()
    }

    /** From the periodic sync: Done, when a relay and a device are set. */
    suspend fun doneIfConfigured(): RelayResult? = if (isActive()) done() else null

    private suspend fun isActive(): Boolean = settings.current().isConfigured && peerDao.relayPeers().isNotEmpty()

    // --- Pairing --------------------------------------------------------------------------

    /**
     * Pairs the device whose code is [text]: stores its keys, wrapped, and sends the relay the
     * new device list at once, when a relay is set. Scanning is the consent.
     */
    suspend fun pair(text: String): RelayResult {
        val pairing = when (val parsed = RelayPairingUri.parse(text)) {
            is PairingParse.Refused -> return finish(RelayResult.PairingRefused(clock.instant(), parsed.problem))
            is PairingParse.Ok -> parsed.pairing
        }
        val auth = RelayCrypto.authKey(pairing.secret)
        val enc = RelayCrypto.encKey(pairing.secret)
        withContext(Dispatchers.IO) {
            peerDao.pairRelay(
                deviceId = pairing.deviceId,
                name = pairing.name,
                width = pairing.width,
                authKey = RelayCrypto.b64(keys.wrap(auth)),
                encKey = RelayCrypto.b64(keys.wrap(enc)),
                at = clock.instant(),
            )
        }
        val problem = if (settings.current().isConfigured) sendDevices() else RelayProblem.NO_ADDRESS
        return finish(RelayResult.Paired(clock.instant(), pairing.name, toldRelay = problem == null, problem = problem.takeIf { it != RelayProblem.NO_ADDRESS }))
    }

    /** Forgets [deviceId] and tells the relay, which deletes its pending changes. */
    suspend fun forget(deviceId: String): RelayResult {
        peerDao.forget(deviceId)
        val problem = if (settings.current().isConfigured) sendDevices() else null
        return finish(RelayResult.Forgotten(clock.instant(), toldRelay = problem == null && settings.current().isConfigured, problem = problem))
    }

    private suspend fun sendDevices(): RelayProblem? = lock.withLock {
        runTask { connection -> connection.withGroup { connection.client.putDevices(connection.devices.map { it.auth }) } }.second?.first
    }

    // --- Publish --------------------------------------------------------------------------

    /**
     * Publish: registers if needed, sends the device list, builds the package from the three
     * latest released editions, the saved shelf and the images at each device's width,
     * uploads it encrypted with a fresh key, and publishes it with the key wrapped for each
     * device.
     */
    suspend fun publish(): RelayResult = lock.withLock {
        val (result, problem) = runTask { connection ->
            val devices = connection.devices
            progress(RelayProgress.Connecting)
            connection.withGroup { connection.client.putDevices(devices.map { it.auth }) }
            if (devices.isEmpty()) return@runTask RelayResult.NoDevices(clock.instant())
            fullText?.let { fetcher -> withTimeoutOrNull(FULL_TEXT_TIMEOUT_MILLIS) { fetchSavedFullText(database, fetcher) } }

            val packageId = RelayCrypto.b64(RelayCrypto.randomBytes(16))
            val packageKey = RelayCrypto.randomBytes(RelayCrypto.KEY_BYTES)
            val directory = File(cacheDirectory, "relay").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "$packageId.mir1")
            try {
                val built = withContext(Dispatchers.IO) {
                    val snapshot = KoboExporter(database, clock).snapshot(installId.get())
                    // Fresh from the database, so a Done just before is reflected.
                    val acked = peerDao.relayPeers().associate { it.deviceId to it.ackedSeq }
                    val widths = devices.map { it.peer.relayWidth ?: KoboImageConfig.DEFAULT_WIDTH }.distinct()
                    val images = newImages()
                    RelayPackageWriter(images::prepare).write(
                        snapshot, acked, widths, clock.instant(), packageKey, packageId, target,
                        onImages = { done, total -> progress(RelayProgress.PreparingImages(done, total)) },
                    )
                }
                progress(RelayProgress.Uploading(built.size))
                connection.withGroup { connection.client.uploadPackage(packageId, target, built.sha256) }
                progress(RelayProgress.Publishing)
                val wrapped = devices.associate { device ->
                    val kid = RelayCrypto.kid(device.auth)
                    kid to RelayCrypto.b64(Mir1.seal(device.enc, Mir1.KIND_WRAPPED_KEY, Mir1.wrapContext(kid, packageId), packageKey))
                }
                connection.withGroup { connection.client.publish(packageId, wrapped) }
                settings.setLastPublished(clock.instant())
                RelayResult.Published(clock.instant(), devices.size, built.editions, built.saved, built.images, built.size)
            } finally {
                target.delete()
            }
        }
        finish(result ?: RelayResult.Failed(clock.instant(), RelayTask.PUBLISH, problem!!.first, problem.second))
    }

    // --- Done -----------------------------------------------------------------------------

    /**
     * Done: downloads every batch of changes, decrypts it with the enc key of the device that
     * sent it, checks it names that device and stays within its range, merges it, and tells
     * the relay to delete every batch handled, merged or dropped. A batch that could not be
     * downloaded stays for next time.
     */
    suspend fun done(): RelayResult = lock.withLock {
        val (result, problem) = runTask { connection ->
            progress(RelayProgress.Connecting)
            val byKid = connection.devices.associateBy { RelayCrypto.kid(it.auth) }
            val listed = connection.withGroup { connection.client.changes() }
            // Per device in seq order, so that a later batch never acknowledges past an earlier one.
            val ordered = listed.withIndex().sortedWith(compareBy({ it.value.kid }, { firstSeq(it.value.batch) ?: Long.MAX_VALUE }, { it.index })).map { it.value }
            val handled = ArrayList<String>()
            var events = 0
            var batches = 0
            var dropped = 0
            var left = 0
            ordered.forEachIndexed { index, change ->
                progress(RelayProgress.Collecting(index, ordered.size))
                val device = byKid[change.kid]
                if (device == null) {
                    // From a key no longer paired, or whose keys this phone lost: unreadable.
                    handled += change.id
                    dropped++
                    return@forEachIndexed
                }
                val envelope = try {
                    connection.withGroup { connection.client.change(change.id, MAX_CHANGES_ENVELOPE_BYTES) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RelayHttpException) {
                    if (e.status == 413) {
                        handled += change.id
                        dropped++
                    } else {
                        left++
                    }
                    return@forEachIndexed
                } catch (_: IOException) {
                    left++
                    return@forEachIndexed
                }
                if (envelope == null) {
                    // Already gone from the relay.
                    return@forEachIndexed
                }
                val read = readBatch(device.peer.deviceId, device.enc, change.kid, change.batch, envelope)
                if (read == null) {
                    handled += change.id
                    dropped++
                    return@forEachIndexed
                }
                val merged = merger.merge(device.peer.deviceId, read)
                if (merged == null) {
                    // Forgotten while this ran.
                    handled += change.id
                    dropped++
                } else {
                    handled += change.id
                    batches++
                    events += merged.merged
                }
            }
            progress(RelayProgress.Collecting(ordered.size, ordered.size))
            if (handled.isNotEmpty()) connection.withGroup { connection.client.done(handled) }
            settings.setLastDone(clock.instant())
            RelayResult.Collected(clock.instant(), batches, events, dropped, left)
        }
        finish(result ?: RelayResult.Failed(clock.instant(), RelayTask.DONE, problem!!.first, problem.second))
    }

    // --- Status ---------------------------------------------------------------------------

    /** `GET /v1/group/status`, matched to the paired devices. */
    suspend fun status(): RelayGroupStatus {
        val (result, problem) = runTask { connection ->
            val status = connection.withGroup { connection.client.status() }
            val byKid = connection.devices.associateBy { RelayCrypto.kid(it.auth) }
            val fetched = status.currentPackage?.fetchedBy.orEmpty().toSet()
            val devices = status.devices.mapNotNull { line ->
                val device = byKid[line.kid] ?: return@mapNotNull null
                device.peer.deviceId to RelayDeviceStatus(
                    lastSeenAt = KoboEvents.parseTime(line.lastSeenAt),
                    hasCurrentPackage = line.kid in fetched,
                    pendingChanges = line.pendingChanges,
                )
            }.toMap()
            RelayGroupStatus(devices, KoboEvents.parseTime(status.currentPackage?.publishedAt), status.currentPackage != null)
        }
        return result ?: RelayGroupStatus(problem = problem?.first)
    }

    // --- Shared ---------------------------------------------------------------------------

    /** A paired device with its keys unwrapped. */
    private class Device(val peer: SyncPeerEntity, val auth: ByteArray, val enc: ByteArray)

    private inner class Connection(val address: String, val client: RelayClient, val devices: List<Device>, val token: String) {
        /** Registers when the group is not known to exist at this address. */
        suspend fun ensureGroup(force: Boolean = false) {
            if (!force && settings.registeredAt() == address) return
            client.register(token)
            settings.setRegisteredAt(address)
        }

        /**
         * Runs [block]; on a `401 unknown` the relay has forgotten the group (or never had it),
         * so registers, sends the device list and runs [block] once more.
         */
        suspend fun <T> withGroup(block: suspend () -> T): T {
            ensureGroup()
            return try {
                block()
            } catch (e: RelayUnauthorizedException) {
                if (!e.isUnknown) throw e
                settings.setRegisteredAt(null)
                ensureGroup(force = true)
                client.putDevices(devices.map { it.auth })
                block()
            }
        }
    }

    /**
     * Sets up a connection to the relay and runs [block], turning failures into a problem. A
     * null result comes with the problem and, for an HTTP answer, its status.
     */
    private suspend fun <T> runTask(block: suspend (Connection) -> T): Pair<T?, Pair<RelayProblem, Int?>?> {
        val config = settings.current()
        val address = when (val parsed = RelayAddress.parse(config.address, allowLocalHttp)) {
            RelayAddress.Parse.Empty -> return null to (RelayProblem.NO_ADDRESS to null)
            RelayAddress.Parse.NotHttps -> return null to (RelayProblem.NOT_HTTPS to null)
            RelayAddress.Parse.Invalid -> return null to (RelayProblem.BAD_ADDRESS to null)
            is RelayAddress.Parse.Ok -> parsed.url
        }
        return try {
            val owner = RelayCrypto.ownerKey(settings.ownerSecret())
            val connection = Connection(address, RelayClient(address, owner, client(), clock, partBytes), devices(), config.registrationToken)
            block(connection) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayUnauthorizedException) {
            null to (RelayProblem.UNAUTHORIZED to 401)
        } catch (e: RelayHttpException) {
            val problem = when (e.status) {
                403 -> RelayProblem.REGISTRATION_REFUSED
                413 -> RelayProblem.TOO_LARGE
                429 -> RelayProblem.TOO_MANY
                else -> RelayProblem.RELAY_ERROR
            }
            null to (problem to e.status)
        } catch (_: IOException) {
            null to (RelayProblem.NETWORK to null)
        } catch (_: SerializationException) {
            null to (RelayProblem.RELAY_ERROR to null)
        } catch (_: IllegalArgumentException) {
            // An unexpected JSON shape from the relay, or a bad address that slipped through.
            null to (RelayProblem.RELAY_ERROR to null)
        } catch (_: RuntimeException) {
            null to (RelayProblem.PHONE_ERROR to null)
        }
    }

    /** The relay devices whose keys unwrap; one whose keys are lost is left out and must be paired again. */
    private suspend fun devices(): List<Device> = peerDao.relayPeers().mapNotNull { peer ->
        try {
            val auth = keys.unwrap(RelayCrypto.unb64(peer.relayAuthKey!!) ?: return@mapNotNull null)
            val enc = keys.unwrap(RelayCrypto.unb64(peer.relayEncKey!!) ?: return@mapNotNull null)
            if (auth.size == RelayCrypto.KEY_BYTES && enc.size == RelayCrypto.KEY_BYTES) Device(peer, auth, enc) else null
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Which relay devices' keys this phone can no longer unwrap (its data was restored onto another phone). */
    suspend fun devicesWithLostKeys(): Set<String> {
        val usable = devices().map { it.peer.deviceId }.toSet()
        return peerDao.relayPeers().map { it.deviceId }.filter { it !in usable }.toSet()
    }

    companion object {
        /** Changes bodies are at most 4 MiB; with the header and tags, a little more. */
        const val MAX_CHANGES_ENVELOPE_BYTES = (4L shl 20) + 64 * 1024
        const val MAX_CHANGES_PLAIN_BYTES = 4L shl 20
        private const val FULL_TEXT_TIMEOUT_MILLIS = 120_000L

        private val BATCH = Regex("^(\\d{1,18})-(\\d{1,18})$")

        fun firstSeq(batch: String): Long? = BATCH.matchEntire(batch)?.groupValues?.get(1)?.toLongOrNull()

        /**
         * The events of one changes envelope, or null to drop it: it fails to decrypt with
         * [encKey], is not a changes body, names a device other than [deviceId], carries more
         * than 500 events, or has a `seq` outside [batch].
         */
        fun readBatch(deviceId: String, encKey: ByteArray, kid: String, batch: String, envelope: ByteArray): List<SyncEvent>? {
            val match = BATCH.matchEntire(batch) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: return null
            if (first > last) return null
            val plain = try {
                Mir1.open(encKey, Mir1.KIND_CHANGES, Mir1.changesContext(kid, batch), envelope, MAX_CHANGES_PLAIN_BYTES)
            } catch (_: EnvelopeException) {
                return null
            }
            val changes = try {
                KoboProtocol.json.decodeFromString<RelayChanges>(plain.decodeToString())
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (changes.deviceId != deviceId) return null
            if (changes.events.size > KoboProtocol.MAX_EVENTS_PER_REQUEST) return null
            if (changes.events.any { it.seq !in first..last }) return null
            return changes.events
        }
    }
}

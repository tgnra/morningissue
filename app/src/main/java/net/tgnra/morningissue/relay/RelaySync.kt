package net.tgnra.morningissue.relay

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/** Why a session did not finish, in terms the screen can put plainly. */
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

    /** The relay refused the phone's signature (`401`). */
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

/** Where a sync session is. */
enum class SessionStage {
    /** Building the package: the saved articles' text, the editions and the pictures. */
    PREPARING,

    /** Making the group on the relay and uploading the package. */
    UPLOADING,

    /** Listing the devices and publishing the package. */
    PUBLISHING,

    /** Waiting for the Kobos to sync, and collecting each one's changes as it does. */
    WAITING,

    /** Collecting the changes that are left, then deleting the group. */
    FINISHING,

    /** Stopping, then deleting the group. */
    CANCELLING,
}

/** Where one paired Kobo is in a session. */
enum class KoboStage {
    /** It has not fetched the package yet. */
    WAITING,

    /** It has fetched the package; its changes are being collected, or could not all be downloaded yet. */
    DOWNLOADED,

    /** It has the package and its changes are merged: its part is done. */
    COLLECTED,
}

/** What the preparing and uploading stages have done so far, for the screen. */
sealed interface RelayProgress {
    data class PreparingImages(val done: Int, val total: Int) : RelayProgress
    data class Uploading(val sent: Long, val total: Long) : RelayProgress
}

/** An open sync session. */
data class RelaySession(
    val stage: SessionStage = SessionStage.PREPARING,
    val progress: RelayProgress? = null,
    /** Each Kobo in the session, by `deviceId`: the paired ones whose keys unwrap. */
    val kobos: Map<String, KoboStage> = emptyMap(),
    /** The name of the Kobo whose code was just scanned, when that started the session. */
    val paired: String? = null,
)

/** How a session or a pairing ended. */
sealed interface RelayResult {
    val at: Instant

    /**
     * A session ended: [synced] of the [kobos] fetched the package and had their changes
     * merged, by the end of waiting or when Finish now was pressed ([early]). [batches] were
     * merged with [events] in them, [dropped] could not be read or did not belong to the key
     * that sent them, and [left] could not be downloaded; the relay deleted them with the group,
     * and the Kobo sends their events again next time.
     */
    data class Synced(
        override val at: Instant,
        val early: Boolean,
        val synced: Int,
        val kobos: Int,
        val batches: Int,
        val events: Int,
        val dropped: Int,
        val left: Int,
    ) : RelayResult

    /** Nothing to sync with: no Kobo is paired, or none whose keys unwrap. */
    data class NoDevices(override val at: Instant) : RelayResult

    /** Cancel was pressed, or the app was left ([byLeaving]). Nothing was merged. */
    data class Cancelled(override val at: Instant, val byLeaving: Boolean) : RelayResult

    data class PairingRefused(override val at: Instant, val problem: PairingProblem) : RelayResult

    /** [name] is paired, and a session started for it. */
    data class Paired(override val at: Instant, val name: String) : RelayResult

    data class Failed(override val at: Instant, val problem: RelayProblem, val status: Int? = null) : RelayResult
}

/** The open session, if any, and how the last one (or a pairing) ended. */
data class RelayActivity(val session: RelaySession? = null, val last: RelayResult? = null) {
    val sessionOpen: Boolean get() = session != null
}

/**
 * The phone's side of relay sync (`docs/relay-sync.md`): pairing from a device's code, and the
 * sync session. The relay holds the group only while a session is open:
 *
 * 1. Preparing: the package is built from the three latest released editions, the saved
 *    shelf and the pictures at each device's width.
 * 2. Uploading: `PUT /v1/group` makes the group, and the package goes up in parts.
 * 3. Publishing: the device list, then the package with the key wrapped for each device. The
 *    relay only keeps keys for listed devices, so the list cannot come later; listing the
 *    devices only now keeps a Kobo from syncing before the package is there (it gets `401`).
 * 4. Waiting: `GET /v1/group/status` every few seconds. A Kobo uploads its changes before it
 *    fetches the package, so once its kid is in `fetchedBy` its changes are collected and
 *    merged straight away.
 * 5. When every device is done, or on Finish now: whatever changes are left are merged, and
 *    `DELETE /v1/group` removes everything.
 *
 * Cancel, leaving the app and any failure delete the group too, without merging.
 *
 * Merging goes through [KoboEventMerger]: per device, only events above its `ackedSeq`, in one
 * transaction that stores the new `ackedSeq` and relearns affinity when what it learns from
 * changed.
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
    /** The app's scope: a session lives as long as the process, not as long as the screen. */
    private val scope: CoroutineScope,
    /**
     * Told when a session is cancelled because the app was left; the app queues a job that
     * deletes the group, in case the process dies before the session's own `DELETE` is sent.
     */
    private val onLeft: () -> Unit = {},
    /** The size of each part the package is uploaded in (smaller in tests). */
    private val partBytes: Long = RelayClient.PART_BYTES,
    /** How often the relay is asked whether the Kobos have synced (shorter in tests). */
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(RelayActivity())
    val activity: StateFlow<RelayActivity> = state.asStateFlow()

    val config: Flow<RelayConfig> = settings.config
    val peers: Flow<List<SyncPeerEntity>> = database.syncPeerDao().observeRelay()

    private val peerDao = database.syncPeerDao()
    private val merger = KoboEventMerger(database, clock)

    /** The open session's job, set together with [RelayActivity.session]. */
    private var job: Job? = null
    private val finishRequested = MutableStateFlow(false)

    @Volatile private var cancelledByLeaving = false

    /** Reads [text] as requests to the relay would, for the screen to show the address in use. */
    fun parseAddress(text: String): RelayAddress.Parse = RelayAddress.parse(text, allowLocalHttp)

    // --- From the screen ------------------------------------------------------------------

    /** Opens a session, unless one is open already. False when one was. */
    fun startSession(paired: String? = null): Boolean = synchronized(this) {
        if (state.value.session != null) return false
        finishRequested.value = false
        cancelledByLeaving = false
        state.update { it.copy(session = RelaySession(paired = paired)) }
        job = scope.launch { runSession() }
        true
    }

    /** Ends the open session now: merges what has arrived, then deletes the group. Kobos that have not synced get nothing. */
    fun finishNow() {
        finishRequested.value = true
    }

    /** Stops the open session, whatever it is doing, and deletes the group without merging. */
    fun cancel() = cancelSession(byLeaving = false)

    /**
     * The app went to the background: an open session is cancelled, its group deleted from the
     * app's scope, and [onLeft] queues a job that deletes it again in case the process dies first.
     */
    fun cancelForLeaving() {
        if (cancelSession(byLeaving = true)) onLeft()
    }

    private fun cancelSession(byLeaving: Boolean): Boolean = synchronized(this) {
        val running = job ?: return false
        if (state.value.session == null) return false
        cancelledByLeaving = byLeaving
        running.cancel()
        true
    }

    /** Pairs from [text] and starts a session; nothing while a session is open. */
    fun startPair(text: String) {
        if (state.value.session != null) return
        scope.launch { pair(text) }
    }

    /** Forgets a device on this phone; nothing while a session is open. */
    fun startForget(deviceId: String) {
        if (state.value.session != null) return
        scope.launch { forget(deviceId) }
    }

    private fun session(change: (RelaySession) -> RelaySession) = state.update { s -> s.copy(session = s.session?.let(change)) }

    private fun stage(stage: SessionStage) = session { it.copy(stage = stage, progress = null) }

    private fun progress(progress: RelayProgress) = session { it.copy(progress = progress) }

    private fun kobo(deviceId: String, stage: KoboStage) = session { it.copy(kobos = it.kobos + (deviceId to stage)) }

    private fun record(result: RelayResult): RelayResult {
        state.update { it.copy(last = result) }
        return result
    }

    // --- Pairing --------------------------------------------------------------------------

    /**
     * Pairs the device whose code is [text]: stores its keys, wrapped, and starts a session, so
     * that the Kobo, which asks the relay while its code is on screen, syncs as soon as it is
     * listed. Scanning is the consent.
     */
    suspend fun pair(text: String): RelayResult {
        val pairing = when (val parsed = RelayPairingUri.parse(text)) {
            is PairingParse.Refused -> return record(RelayResult.PairingRefused(clock.instant(), parsed.problem))
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
        val result = record(RelayResult.Paired(clock.instant(), pairing.name))
        startSession(paired = pairing.name)
        return result
    }

    /**
     * Forgets [deviceId] on this phone. The relay is not told: it holds nothing between
     * sessions, and the next session's device list leaves the device out.
     */
    suspend fun forget(deviceId: String) {
        peerDao.forget(deviceId)
    }

    // --- The session ----------------------------------------------------------------------

    /** What a session has made on the relay, so that whatever ends it can delete it. */
    private class Made {
        var client: RelayClient? = null
    }

    private suspend fun runSession() {
        val made = Made()
        var result: RelayResult? = null
        try {
            result = lock.withLock {
                try {
                    session(made)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val (problem, status) = problemOf(e)
                    RelayResult.Failed(clock.instant(), problem, status)
                }
            }
        } catch (e: CancellationException) {
            result = RelayResult.Cancelled(clock.instant(), cancelledByLeaving)
            throw e
        } finally {
            withContext(NonCancellable) {
                made.client?.let { client ->
                    if (result !is RelayResult.Failed) stage(SessionStage.CANCELLING)
                    deleteGroup(client)
                }
                synchronized(this@RelaySync) {
                    state.update { it.copy(session = null, last = result ?: RelayResult.Cancelled(clock.instant(), cancelledByLeaving)) }
                    job = null
                }
            }
        }
    }

    private suspend fun session(made: Made): RelayResult {
        val config = settings.current()
        val address = when (val parsed = RelayAddress.parse(config.address, allowLocalHttp)) {
            RelayAddress.Parse.Empty -> return RelayResult.Failed(clock.instant(), RelayProblem.NO_ADDRESS)
            RelayAddress.Parse.NotHttps -> return RelayResult.Failed(clock.instant(), RelayProblem.NOT_HTTPS)
            RelayAddress.Parse.Invalid -> return RelayResult.Failed(clock.instant(), RelayProblem.BAD_ADDRESS)
            is RelayAddress.Parse.Ok -> parsed.url
        }
        val devices = devices()
        if (devices.isEmpty()) return RelayResult.NoDevices(clock.instant())
        session { it.copy(kobos = devices.associate { device -> device.peer.deviceId to KoboStage.WAITING }) }

        // Preparing.
        fullText?.let { fetcher -> withTimeoutOrNull(FULL_TEXT_TIMEOUT_MILLIS) { fetchSavedFullText(database, fetcher) } }
        val packageId = RelayCrypto.b64(RelayCrypto.randomBytes(16))
        val packageKey = RelayCrypto.randomBytes(RelayCrypto.KEY_BYTES)
        val directory = File(cacheDirectory, "relay").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "$packageId.mir1")
        val owner = RelayCrypto.ownerKey(settings.ownerSecret())
        val relay = RelayClient(address, owner, client(), clock, partBytes)
        try {
            val built = withContext(Dispatchers.IO) {
                val snapshot = KoboExporter(database, clock).snapshot(installId.get())
                val acked = peerDao.relayPeers().associate { it.deviceId to it.ackedSeq }
                val widths = devices.map { it.peer.relayWidth ?: KoboImageConfig.DEFAULT_WIDTH }.distinct()
                val images = newImages()
                RelayPackageWriter(images::prepare).write(
                    snapshot, acked, widths, clock.instant(), packageKey, packageId, target,
                    onImages = { done, total -> progress(RelayProgress.PreparingImages(done, total)) },
                )
            }

            // Uploading. Marked as made before the request, so that a group whose answer is lost is deleted too.
            stage(SessionStage.UPLOADING)
            progress(RelayProgress.Uploading(0, built.size))
            made.client = relay
            relay.register(config.registrationToken)
            relay.uploadPackage(packageId, target, built.sha256) { sent, total -> progress(RelayProgress.Uploading(sent, total)) }
        } finally {
            target.delete()
        }

        // Publishing.
        stage(SessionStage.PUBLISHING)
        relay.putDevices(devices.map { it.auth })
        val wrapped = devices.associate { device ->
            val kid = RelayCrypto.kid(device.auth)
            kid to RelayCrypto.b64(Mir1.seal(device.enc, Mir1.KIND_WRAPPED_KEY, Mir1.wrapContext(kid, packageId), packageKey))
        }
        relay.publish(packageId, wrapped)

        // Waiting.
        stage(SessionStage.WAITING)
        val collector = Collector(relay, devices)
        val done = HashSet<String>()
        var failures = 0
        while (done.size < devices.size && !finishRequested.value) {
            try {
                val fetched = relay.status().currentPackage?.fetchedBy.orEmpty().toSet()
                for (device in devices) {
                    val kid = RelayCrypto.kid(device.auth)
                    if (kid in done || kid !in fetched) continue
                    kobo(device.peer.deviceId, KoboStage.DOWNLOADED)
                    if (collector.collect(kid)) {
                        done += kid
                        peerDao.setLastSync(device.peer.deviceId, clock.instant())
                        kobo(device.peer.deviceId, KoboStage.COLLECTED)
                    }
                }
                failures = 0
            } catch (e: RelayHttpException) {
                throw e
            } catch (e: IOException) {
                // A moment without a connection: asked again next time, up to a point.
                if (++failures >= MAX_POLL_FAILURES) throw e
            }
            if (done.size < devices.size) withTimeoutOrNull(pollMillis) { finishRequested.first { it } }
        }

        // Finishing: what arrived from Kobos that did not fetch, or could not be downloaded before.
        stage(SessionStage.FINISHING)
        collector.collect(null)
        relay.deleteGroup()
        made.client = null
        return RelayResult.Synced(
            at = clock.instant(),
            early = done.size < devices.size,
            synced = done.size,
            kobos = devices.size,
            batches = collector.batches,
            events = collector.events,
            dropped = collector.dropped,
            left = collector.left,
        )
    }

    /**
     * Collects changes during a session: downloads each batch, decrypts it with the enc key of
     * the device that sent it, checks it names that device and stays within its range, and
     * merges it. Batches are remembered once handled, merged or dropped, and left on the relay,
     * which deletes them with the group.
     */
    private inner class Collector(private val relay: RelayClient, devices: List<Device>) {
        private val byKid = devices.associateBy { RelayCrypto.kid(it.auth) }
        private val handled = HashSet<String>()
        var batches = 0
        var events = 0
        var dropped = 0

        /** Batches the last pass could not download. */
        var left = 0

        /** Collects the batches from [kid], or from every key when null. True when none was left undownloaded. */
        suspend fun collect(kid: String?): Boolean {
            val listed = relay.changes().filter { (kid == null || it.kid == kid) && it.id !in handled }
            // Per device in seq order, so that a later batch never acknowledges past an earlier one.
            val ordered = listed.withIndex().sortedWith(compareBy({ it.value.kid }, { firstSeq(it.value.batch) ?: Long.MAX_VALUE }, { it.index })).map { it.value }
            left = 0
            for (change in ordered) {
                val device = byKid[change.kid]
                if (device == null) {
                    // From a key not in this session: unreadable.
                    handled += change.id
                    dropped++
                    continue
                }
                val envelope = try {
                    relay.change(change.id, MAX_CHANGES_ENVELOPE_BYTES)
                } catch (e: RelayHttpException) {
                    if (e.status == 413) {
                        handled += change.id
                        dropped++
                    } else {
                        left++
                    }
                    continue
                } catch (_: IOException) {
                    left++
                    continue
                }
                handled += change.id
                if (envelope == null) continue // Already gone from the relay.
                val read = readBatch(device.peer.deviceId, device.enc, change.kid, change.batch, envelope)
                val merged = read?.let { merger.merge(device.peer.deviceId, it) }
                if (merged == null) {
                    // Unreadable, or the device was forgotten while this ran.
                    dropped++
                } else {
                    batches++
                    events += merged.merged
                }
            }
            return left == 0
        }
    }

    // --- Clearing the relay outside a session ---------------------------------------------

    /**
     * Once after upgrading: deletes the group an earlier version kept on the relay between
     * syncs. Tried again at the next start until the relay confirms it is gone.
     */
    suspend fun clearLeftoverGroup() {
        if (settings.leftoverGroupCleared()) return
        val address = (RelayAddress.parse(settings.current().address, allowLocalHttp) as? RelayAddress.Parse.Ok)?.url ?: return
        if (!settings.hasOwnerSecret()) {
            // No owner secret, so no group was ever made.
            settings.setLeftoverGroupCleared()
            return
        }
        // A session holding the lock ends by deleting the group anyway; try again next time.
        if (!lock.tryLock()) return
        try {
            if (deleteGroup(RelayClient(address, RelayCrypto.ownerKey(settings.ownerSecret()), client(), clock))) settings.setLeftoverGroupCleared()
        } finally {
            lock.unlock()
        }
    }

    /**
     * For the job queued when the app was left: deletes the group, unless a session is open
     * (it deletes its own at the end). True when there is nothing left to delete.
     */
    suspend fun deleteAbandonedGroup(): Boolean {
        if (state.value.session != null) return true
        val address = (RelayAddress.parse(settings.current().address, allowLocalHttp) as? RelayAddress.Parse.Ok)?.url ?: return true
        if (!settings.hasOwnerSecret()) return true
        if (!lock.tryLock()) return true
        return try {
            deleteGroup(RelayClient(address, RelayCrypto.ownerKey(settings.ownerSecret()), client(), clock))
        } finally {
            lock.unlock()
        }
    }

    /** `DELETE /v1/group`, best effort. True when the group is gone, including when the relay did not know it (`401 unknown`, `404`). */
    private suspend fun deleteGroup(relay: RelayClient): Boolean = try {
        relay.deleteGroup()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: RelayUnauthorizedException) {
        e.isUnknown
    } catch (e: RelayHttpException) {
        e.status == 404
    } catch (_: IOException) {
        false
    }

    // --- Shared ---------------------------------------------------------------------------

    /** A paired device with its keys unwrapped. */
    private class Device(val peer: SyncPeerEntity, val auth: ByteArray, val enc: ByteArray)

    /** A failure as a problem and, for an HTTP answer, its status. */
    private fun problemOf(e: Exception): Pair<RelayProblem, Int?> = when (e) {
        is RelayUnauthorizedException -> RelayProblem.UNAUTHORIZED to 401
        is RelayHttpException -> when (e.status) {
            403 -> RelayProblem.REGISTRATION_REFUSED
            413 -> RelayProblem.TOO_LARGE
            429 -> RelayProblem.TOO_MANY
            else -> RelayProblem.RELAY_ERROR
        } to e.status
        is IOException -> RelayProblem.NETWORK to null
        // An unexpected JSON shape from the relay, or a bad address that slipped through.
        is IllegalArgumentException -> RelayProblem.RELAY_ERROR to null
        else -> RelayProblem.PHONE_ERROR to null
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

        /** How often a waiting session asks the relay about the Kobos. */
        const val POLL_MILLIS = 3_000L

        /** Asks in a row that may fail to reach the relay before a waiting session gives up. */
        private const val MAX_POLL_FAILURES = 5

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

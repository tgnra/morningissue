package net.tgnra.morningissue.relay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockWebServer
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.kobo.BundleExport
import net.tgnra.morningissue.kobo.InstallId
import net.tgnra.morningissue.kobo.KoboProtocol
import net.tgnra.morningissue.kobo.KoboTestData
import net.tgnra.morningissue.kobo.NOW
import net.tgnra.morningissue.kobo.PreparedImage
import net.tgnra.morningissue.kobo.Seeded
import net.tgnra.morningissue.kobo.SyncEvent
import net.tgnra.morningissue.kobo.GreyFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Sync sessions end to end against [FakeRelay], with an in-memory database, the test acting as
 * two Kobos: it pairs them from their codes, uploads their changes and opens the package each
 * downloads, as the Kobo would, while the session waits.
 */
@RunWith(AndroidJUnit4::class)
class RelaySyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: MorningIssueDatabase
    private lateinit var ids: Seeded
    private lateinit var store: DataStore<Preferences>
    private lateinit var installId: InstallId
    private lateinit var settings: RelaySettings
    private lateinit var sync: RelaySync
    private val keys = FakeKeyWrapper()

    /** How many times the session told the app it was cancelled by leaving. */
    @Volatile private var left = 0

    /** Runs from [NOW], as the seeded editions expect, but moves on with real time so that signatures differ. */
    private val clock: Clock = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), NOW))
    private val prepared = mutableListOf<Pair<String, List<Int>>>()

    private class Kobo(val deviceId: String, val name: String, val width: Int) {
        val secret = RelayCrypto.randomBytes(32)
        val auth = RelayCrypto.authKey(secret)
        val enc = RelayCrypto.encKey(secret)
        val kid = RelayCrypto.kid(auth)
        fun code() = "morningissue:pair?v=1&s=${RelayCrypto.b64(secret)}&d=$deviceId&n=${name.replace(" ", "%20")}&w=$width"
    }

    private val libra = Kobo("b7e2", "Kobo Libra 2", 1264)
    private val clara = Kobo("c9f1", "Kobo Clara", 1072)

    @Before
    fun setUp() = runBlocking<Unit> {
        relay.clockSeconds = { clock.instant().epochSecond }
        server.dispatcher = relay
        server.start()
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        ids = KoboTestData.seed(db)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        installId = InstallId(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "id.preferences_pb") })
        useRelay(server.url("/").toString())
    }

    /** As though the app were built with [address] and [token]: the settings store, and so the owner secret, stay. */
    private fun useRelay(address: String, token: String = "") {
        settings = RelaySettings(store, address, token)
        sync = newSync()
    }

    private fun newSync() = RelaySync(
        database = db,
        settings = settings,
        keys = keys,
        installId = installId,
        client = { okhttp3.OkHttpClient() },
        newImages = {
            ImagePreparer { url, widths ->
                synchronized(prepared) { prepared += url to widths }
                // A fake JPEG per width, naming its address and width, so the test can tell them apart.
                widths.associateWith { w -> PreparedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "$url@$w".toByteArray(), GreyFormat.JPEG) }
            }
        },
        fullText = null,
        cacheDirectory = folder.newFolder(),
        clock = clock,
        allowLocalHttp = true,
        scope = scope,
        onLeft = { left++ },
        // Small parts, so that every test's package is uploaded in several.
        partBytes = PART_BYTES,
        pollMillis = 200,
    )

    @After
    fun tearDown() {
        db.close()
        server.close()
        scope.cancel()
    }

    /** What a Kobo unpacks: every zip entry by name. */
    private fun download(kobo: Kobo): Map<String, ByteArray> {
        val (id, key, envelope) = relay.deviceDownloads(kobo.auth)!!
        val packageKey = Mir1.open(kobo.enc, Mir1.KIND_WRAPPED_KEY, Mir1.wrapContext(kobo.kid, id), RelayCrypto.unb64(key)!!)
        val zip = ByteArrayOutputStream()
        Mir1.open(packageKey, Mir1.KIND_PACKAGE, Mir1.packageContext(id), ByteArrayInputStream(envelope), zip)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }
        relay.deviceFetched(kobo.auth)
        return entries
    }

    private fun manifest(entries: Map<String, ByteArray>): JsonObject = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString()).jsonObject

    private fun changes(kobo: Kobo, batch: String, deviceId: String, vararg events: SyncEvent): ByteArray {
        val plain = KoboProtocol.json.encodeToString(RelayChanges(deviceId, events.toList())).toByteArray()
        return Mir1.seal(kobo.enc, Mir1.KIND_CHANGES, Mir1.changesContext(kobo.kid, batch), plain)
    }

    private fun opened(seq: Long, articleId: Long, minutes: Long) =
        SyncEvent(seq = seq, type = "opened", articleId = articleId, at = BundleExport.time(NOW.plusSeconds(minutes * 60)))

    /** Pairs both Kobos as scanning would, without starting a session. */
    private suspend fun storeBoth() {
        for (kobo in listOf(libra, clara)) {
            db.syncPeerDao().pairRelay(
                kobo.deviceId, kobo.name, kobo.width,
                RelayCrypto.b64(keys.wrap(kobo.auth)), RelayCrypto.b64(keys.wrap(kobo.enc)), NOW,
            )
        }
    }

    private suspend fun awaitActivity(until: (RelayActivity) -> Boolean): RelayActivity = withTimeout(30_000) { sync.activity.first(until) }

    /** Waits for the open session to wait for the Kobos: the package is published and the devices listed. */
    private suspend fun waiting(): RelaySession = awaitActivity { it.session?.stage == SessionStage.WAITING }.session!!

    /** Waits for the open session to end, and returns how. */
    private suspend fun ended(): RelayResult = awaitActivity { it.session == null }.last!!

    /** A Kobo's sync: it uploads [batches], then downloads the package and records that it has it. */
    private fun koboSyncs(kobo: Kobo, vararg batches: Pair<String, ByteArray>): Map<String, ByteArray> {
        for ((batch, envelope) in batches) relay.deviceUploads(kobo.auth, batch, envelope)
        return download(kobo)
    }

    @Test
    fun aSessionWithTwoKobosEndToEnd() = runBlocking<Unit> {
        storeBoth()
        assertTrue(sync.startSession())
        assertFalse("one session at a time", sync.startSession())
        val session = waiting()
        assertEquals(mapOf("b7e2" to KoboStage.WAITING, "c9f1" to KoboStage.WAITING), session.kobos)

        // The group is made, the package uploaded and completed, and only then are the devices
        // listed, so that a Kobo cannot sync before the package is there.
        val order = relay.requests.toList()
        assertEquals("PUT /v1/group", order.first())
        val completed = order.indexOfFirst { it.endsWith("/complete") }
        val listed = order.indexOf("PUT /v1/group/devices")
        val published = order.indexOfFirst { it.endsWith("/publish") }
        assertTrue(order.toString(), completed in 1 until listed)
        assertEquals(listed + 1, published)
        assertEquals(setOf(libra.kid, clara.kid), relay.devices.keys)
        // The package was a cache file, gone after upload.
        assertTrue(File(folder.root.listFiles()!!.first { it.isDirectory && File(it, "relay").exists() }, "relay").list()!!.isEmpty())

        // The Libra uploads its changes, then fetches the package.
        val entries = koboSyncs(libra, "1-2" to changes(libra, "1-2", "b7e2", opened(1, ids.lead, 10), opened(2, ids.top, 12)))
        assertEquals("manifest.json", entries.keys.first())
        val manifest = manifest(entries)
        assertEquals("relay/1", manifest["protocol"]!!.jsonPrimitive.content)
        assertEquals(1, manifest["v"]!!.jsonPrimitive.long.toInt())
        assertNull(manifest["ackedSeq"])
        assertNotNull(manifest["builtAt"])
        assertEquals(mapOf("b7e2" to 0L, "c9f1" to 0L), manifest["acked"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
        val editionIds = manifest["editions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
        // The two released editions, newest first; never the one waiting for tomorrow.
        assertEquals(listOf(ids.edition, ids.older), editionIds)
        for (id in editionIds) assertTrue("editions/$id.json" in entries)
        assertTrue("saved.json" in entries)

        // Each picture at each paired Kobo's width.
        val images = Json.parseToJsonElement(entries.getValue("images.json").decodeToString()).jsonArray.map { it.jsonObject }
        val urls = images.map { it["url"]!!.jsonPrimitive.content }.toSet()
        assertTrue("https://garden.example/img/lead.jpg" in urls)
        assertTrue("https://garden.example/img/frost.jpg" in urls)
        for (url in urls) {
            for (w in listOf(1072, 1264)) {
                val line = images.single { it["url"]!!.jsonPrimitive.content == url && it["w"]!!.jsonPrimitive.long.toInt() == w }
                val file = line["file"]!!.jsonPrimitive.content
                assertEquals(RelayPackageWriter.imageFile(url, w), file)
                assertTrue(file.matches(Regex("images/[0-9a-f]{64}-$w")))
                assertEquals("image/jpeg", line["type"]!!.jsonPrimitive.content)
                assertTrue(entries.getValue(file).decodeToString().endsWith("$url@$w"))
            }
        }
        assertTrue(prepared.all { it.second == listOf(1072, 1264) })

        // Its changes are merged as soon as it has fetched, while the Clara is still awaited.
        awaitActivity { it.session?.kobos?.get("b7e2") == KoboStage.COLLECTED }
        assertEquals(NOW.plusSeconds(600), db.engagementDao().get(ids.lead)!!.openedAt)
        assertEquals(2L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertNotNull(db.syncPeerDao().get("b7e2")!!.lastSyncAt)
        assertEquals(KoboStage.WAITING, sync.activity.value.session!!.kobos["c9f1"])
        assertNotNull("the group stays while a Kobo is awaited", relay.ownerKey)

        // The Clara sends a batch that names the Libra, which is dropped, then fetches.
        koboSyncs(clara, "1-1" to changes(clara, "1-1", "b7e2", opened(1, ids.section, 5)))
        val done = ended() as RelayResult.Synced
        assertFalse(done.early)
        assertEquals(2, done.synced)
        assertEquals(2, done.kobos)
        assertEquals(1, done.batches)
        assertEquals(2, done.events)
        assertEquals(1, done.dropped)
        assertEquals(0, done.left)
        assertNull(db.engagementDao().get(ids.section)?.openedAt)
        assertEquals(0L, db.syncPeerDao().get("c9f1")!!.ackedSeq)
        assertNotNull(db.syncPeerDao().get("c9f1")!!.lastSyncAt)

        // The relay holds nothing afterwards: one DELETE, and no Done.
        assertNull(relay.ownerKey)
        assertEquals(1, relay.groupDeletions)
        assertTrue(relay.packages.isEmpty())
        assertTrue(relay.changes.isEmpty())
        assertFalse(relay.requests.any { it == "POST /v1/group/done" })

        // The next session's package carries what was merged.
        sync.startSession()
        waiting()
        val next = koboSyncs(clara)
        assertEquals(mapOf("b7e2" to 2L, "c9f1" to 0L), manifest(next)["acked"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
        val bundle = Json.parseToJsonElement(next.getValue("editions/${ids.edition}.json").decodeToString()).jsonObject
        assertEquals(
            BundleExport.time(NOW.plusSeconds(600)),
            bundle["articles"]!!.jsonObject[ids.lead.toString()]!!.jsonObject["engagement"]!!.jsonObject["openedAt"]!!.jsonPrimitive.content,
        )
        sync.finishNow()
        assertTrue(ended() is RelayResult.Synced)
    }

    @Test
    fun finishNowMergesWhatArrivedAndEnds() = runBlocking<Unit> {
        storeBoth()
        sync.startSession()
        waiting()
        koboSyncs(libra, "1-1" to changes(libra, "1-1", "b7e2", opened(1, ids.lead, 1)))
        awaitActivity { it.session?.kobos?.get("b7e2") == KoboStage.COLLECTED }
        // The Clara uploads its changes but never fetches the package.
        relay.deviceUploads(clara.auth, "1-1", changes(clara, "1-1", "c9f1", opened(1, ids.top, 2)))
        sync.finishNow()
        val done = ended() as RelayResult.Synced
        assertTrue(done.early)
        assertEquals(1, done.synced)
        assertEquals(2, done.kobos)
        // What arrived from the Clara is merged all the same.
        assertEquals(2, done.events)
        assertEquals(1L, db.syncPeerDao().get("c9f1")!!.ackedSeq)
        assertNull(relay.ownerKey)
        assertEquals(1, relay.groupDeletions)
    }

    @Test
    fun cancelStopsTheUploadAndDeletesTheGroup() = runBlocking<Unit> {
        storeBoth()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        relay.onPart = { n ->
            if (n == 1) {
                reached.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        sync.startSession()
        assertTrue(reached.await(20, TimeUnit.SECONDS))
        val uploading = sync.activity.value.session!!
        assertEquals(SessionStage.UPLOADING, uploading.stage)
        // Progress counts the parts sent.
        assertEquals(PART_BYTES, (uploading.progress as RelayProgress.Uploading).sent)
        sync.cancel()
        release.countDown()
        assertEquals(RelayResult.Cancelled::class, ended()::class)
        assertFalse((sync.activity.value.last as RelayResult.Cancelled).byLeaving)
        assertEquals(1, relay.groupDeletions)
        assertNull(relay.ownerKey)
        // Nothing was completed, published or listed, and nothing merged.
        assertFalse(relay.requests.any { it.endsWith("/complete") || it.endsWith("/publish") || it == "PUT /v1/group/devices" })
        assertNull(relay.current)
        assertEquals(0, left)
    }

    @Test
    fun leavingTheAppCancelsTheSessionAndQueuesTheBackup() = runBlocking<Unit> {
        storeBoth()
        // Without a session there is nothing to cancel or back up.
        sync.cancelForLeaving()
        assertEquals(0, left)

        sync.startSession()
        waiting()
        relay.deviceUploads(libra.auth, "1-1", changes(libra, "1-1", "b7e2", opened(1, ids.lead, 1)))
        sync.cancelForLeaving()
        val result = ended() as RelayResult.Cancelled
        assertTrue(result.byLeaving)
        assertEquals(1, left)
        assertEquals(1, relay.groupDeletions)
        // Cancelled sessions merge nothing.
        assertNull(db.engagementDao().get(ids.lead)?.openedAt)
        // The backup job finds the group gone, and that is fine.
        assertTrue(sync.deleteAbandonedGroup())
    }

    @Test
    fun aFailurePartwayDeletesTheGroup() = runBlocking<Unit> {
        storeBoth()
        relay.failPartAlways = 1
        sync.startSession()
        val result = ended()
        assertTrue(result.toString(), result is RelayResult.Failed)
        assertEquals(RelayProblem.RELAY_ERROR, (result as RelayResult.Failed).problem)
        assertEquals(RelayClient.PART_ATTEMPTS, relay.partRequests.count { it.endsWith("/1") })
        // Nothing was completed or published, and the group is gone.
        assertTrue(relay.packages.isEmpty())
        assertNull(relay.current)
        assertEquals(1, relay.groupDeletions)
        assertNull(relay.ownerKey)
    }

    @Test
    fun pairingStartsASession() = runBlocking<Unit> {
        val paired = sync.pair(libra.code()) as RelayResult.Paired
        assertEquals("Kobo Libra 2", paired.name)
        // The keys are kept wrapped.
        val stored = db.syncPeerDao().get("b7e2")!!
        assertFalse(stored.relayAuthKey!!.contains(RelayCrypto.b64(libra.auth)))
        assertNull(stored.tokenHash)

        val session = waiting()
        assertEquals("Kobo Libra 2", session.paired)
        assertEquals(setOf(libra.kid), relay.devices.keys)
        // The Kobo, polling while its code is on screen, is now listed and syncs.
        koboSyncs(libra)
        val done = ended() as RelayResult.Synced
        assertEquals(1, done.synced)
        assertNull(relay.ownerKey)
    }

    @Test
    fun aRefusedCodeStartsNothing() = runBlocking<Unit> {
        assertTrue(sync.pair("morningissue:pair?v=9&s=x") is RelayResult.PairingRefused)
        assertNull(sync.activity.value.session)
        assertTrue(relay.requests.isEmpty())
    }

    @Test
    fun forgettingADeviceOnlyChangesThePhone() = runBlocking<Unit> {
        storeBoth()
        sync.forget("b7e2")
        assertNull(db.syncPeerDao().get("b7e2"))
        assertEquals(listOf("c9f1"), db.syncPeerDao().relayPeers().map { it.deviceId })
        assertTrue(relay.requests.isEmpty())
        // The next session lists only the Clara.
        sync.startSession()
        waiting()
        assertEquals(setOf(clara.kid), relay.devices.keys)
        sync.cancel()
        ended()
    }

    @Test
    fun batchesOfOneDeviceMergeInSeqOrder() = runBlocking<Unit> {
        storeBoth()
        sync.startSession()
        waiting()
        koboSyncs(
            libra,
            "3-3" to changes(libra, "3-3", "b7e2", opened(3, ids.top, 3)),
            "1-2" to changes(libra, "1-2", "b7e2", opened(1, ids.lead, 1), opened(2, ids.section, 2)),
        )
        sync.finishNow()
        val done = ended() as RelayResult.Synced
        assertEquals(3, done.events)
        assertNotNull(db.engagementDao().get(ids.lead)!!.openedAt)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
    }

    @Test
    fun reUploadedAndOverlappingBatchesMergeEachEventOnce() = runBlocking<Unit> {
        storeBoth()
        // After a 401 unknown, or 48 hours without an ack, a Kobo uploads its unacknowledged events
        // again, in new batches whose ranges overlap the old ones.
        sync.startSession()
        waiting()
        koboSyncs(
            libra,
            "1-2" to changes(libra, "1-2", "b7e2", opened(1, ids.lead, 1), opened(2, ids.top, 2)),
            "2-3" to changes(libra, "2-3", "b7e2", opened(2, ids.top, 2), opened(3, ids.section, 3)),
        )
        sync.finishNow()
        var done = ended() as RelayResult.Synced
        assertEquals(2, done.batches)
        assertEquals(3, done.events)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        // A later re-upload of everything since 1 adds only what is new.
        sync.startSession()
        waiting()
        koboSyncs(
            libra,
            "1-4" to changes(libra, "1-4", "b7e2", opened(1, ids.lead, 1), opened(2, ids.top, 2), opened(3, ids.section, 3), opened(4, ids.index, 4)),
        )
        sync.finishNow()
        done = ended() as RelayResult.Synced
        assertEquals(1, done.events)
        assertEquals(4L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
    }

    @Test
    fun eventsAlreadyMergedAreSkipped() = runBlocking<Unit> {
        // Events 1-2 were merged before: over Wi-Fi by an earlier version, or from a batch sent twice.
        storeBoth()
        db.syncPeerDao().setAcked("b7e2", 2, NOW)
        val pageView = SyncEvent(seq = 2, type = "pageView", editionId = ids.edition, page = 1, ms = 5_000, at = BundleExport.time(NOW))
        sync.startSession()
        waiting()
        // The same events come again through the relay, with one new one.
        koboSyncs(libra, "1-3" to changes(libra, "1-3", "b7e2", opened(1, ids.lead, 1), pageView, opened(3, ids.top, 3)))
        sync.finishNow()
        val done = ended() as RelayResult.Synced
        assertEquals(1, done.events)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        // The page time from before was not added again.
        assertTrue(db.editionDao().getPageViews(ids.edition).isEmpty())
    }

    @Test
    fun batchesThatDoNotReadAreDropped() = runBlocking<Unit> {
        storeBoth()
        sync.startSession()
        waiting()
        // Encrypted with the other device's key; seq outside its range; and not an envelope at all.
        koboSyncs(libra, "1-1" to changes(clara, "1-1", "b7e2", opened(1, ids.lead, 1)))
        koboSyncs(clara, "1-1" to changes(clara, "1-1", "c9f1", opened(2, ids.lead, 1)), "5-6" to "garbage".toByteArray())
        val done = ended() as RelayResult.Synced
        assertEquals(0, done.batches)
        assertEquals(3, done.dropped)
        assertNull(db.engagementDao().get(ids.lead)?.openedAt)
        assertEquals(0L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
    }

    @Test
    fun theRegistrationTokenIsSentWhenTheRelayAsksForOne() = runBlocking<Unit> {
        relay.registrationToken = "letmein"
        storeBoth()
        sync.startSession()
        assertEquals(RelayProblem.REGISTRATION_REFUSED, (ended() as RelayResult.Failed).problem)
        assertEquals(0, relay.groupCreations)
        useRelay(server.url("/").toString(), token = "letmein")
        sync.startSession()
        waiting()
        assertEquals(1, relay.groupCreations)
        sync.finishNow()
        assertTrue(ended() is RelayResult.Synced)
    }

    @Test
    fun aSkewedClockIsCorrectedFromTheRelaysDate() = runBlocking<Unit> {
        storeBoth()
        relay.clockSeconds = { clock.instant().epochSecond + 1_000 }
        sync.startSession()
        waiting()
        sync.finishNow()
        assertTrue(ended() is RelayResult.Synced)
    }

    @Test
    fun pairingTheSameDeviceAgainKeepsAckedSeq() = runBlocking<Unit> {
        storeBoth()
        db.syncPeerDao().setAcked("b7e2", 1, NOW)
        // Reset on the Kobo makes a new secret; the same deviceId keeps its place.
        val reset = Kobo("b7e2", "Kobo Libra 2", 1264)
        sync.pair(reset.code())
        waiting()
        assertEquals(1L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertEquals(setOf(reset.kid, clara.kid), relay.devices.keys)
        sync.cancel()
        ended()
    }

    @Test
    fun thePackageIsUploadedInPartsAndJoinedOnTheRelay() = runBlocking<Unit> {
        storeBoth()
        sync.startSession()
        waiting()
        val id = relay.current!!
        val bytes = relay.packages[id]!!.size.toLong()
        val parts = ((bytes + PART_BYTES - 1) / PART_BYTES).toInt()
        assertTrue("several parts", parts > 1)
        assertEquals((0 until parts).map { "$id/$it" }, relay.partRequests.toList())
        assertTrue(relay.parts.isEmpty())
        // What the Kobo gets reads whole.
        assertTrue(koboSyncs(libra).containsKey("manifest.json"))
        sync.finishNow()
        ended()
    }

    @Test
    fun aPartThatFailsIsSentAgain() = runBlocking<Unit> {
        storeBoth()
        relay.failPartsOnce += 1
        sync.startSession()
        waiting()
        assertEquals(2, relay.partRequests.count { it.endsWith("/1") })
        assertTrue(koboSyncs(libra).containsKey("manifest.json"))
        sync.finishNow()
        ended()
    }

    @Test
    fun withNoDeviceThereIsNothingToSync() = runBlocking<Unit> {
        sync.startSession()
        assertTrue(ended() is RelayResult.NoDevices)
        assertTrue(relay.requests.isEmpty())
    }

    @Test
    fun aPlainHttpRelayIsRefused() = runBlocking<Unit> {
        useRelay("http://relay.example")
        storeBoth()
        sync.startSession()
        assertEquals(RelayProblem.NOT_HTTPS, (ended() as RelayResult.Failed).problem)
        assertTrue(relay.requests.isEmpty())
    }

    @Test
    fun devicesWhoseKeysAreLostAreLeftOut() = runBlocking<Unit> {
        storeBoth()
        keys.lost = true
        assertEquals(setOf("b7e2", "c9f1"), sync.devicesWithLostKeys())
        sync.startSession()
        assertTrue(ended() is RelayResult.NoDevices)
        assertTrue(relay.requests.isEmpty())
    }

    @Test
    fun theGroupAnEarlierVersionLeftIsDeletedOnce() = runBlocking<Unit> {
        // No owner secret: this install never had a group, and nothing is sent.
        sync.clearLeftoverGroup()
        assertTrue(relay.requests.isEmpty())
        assertTrue(settings.leftoverGroupCleared())
    }

    @Test
    fun theLeftoverGroupIsDeletedAndTriedAgainUntilItIs() = runBlocking<Unit> {
        // A group made by an earlier version, which kept it between syncs.
        RelayClient(server.url("/").toString(), RelayCrypto.ownerKey(settings.ownerSecret()), okhttp3.OkHttpClient(), clock).register("")
        assertNotNull(relay.ownerKey)
        // The relay cannot be reached: tried again next time.
        useRelay("https://127.0.0.1:1")
        sync.clearLeftoverGroup()
        assertFalse(settings.leftoverGroupCleared())
        useRelay(server.url("/").toString())
        sync.clearLeftoverGroup()
        assertNull(relay.ownerKey)
        assertTrue(settings.leftoverGroupCleared())
        val sent = relay.requests.size
        sync.clearLeftoverGroup()
        assertEquals(sent, relay.requests.size)
    }

    private companion object {
        /** The part size the tests upload in. */
        const val PART_BYTES = 1024L
    }
}

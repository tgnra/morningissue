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
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * Publish and Done end to end against [FakeRelay], with an in-memory database, the test acting
 * as two Kobos: it pairs them from their codes, opens the package each downloads, and uploads
 * their changes as the Kobo would.
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
    fun setUp() = runBlocking {
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
        // Small parts, so that every test's package is uploaded in several.
        partBytes = PART_BYTES,
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

    private suspend fun pairBoth() {
        assertTrue(sync.pair(libra.code()) is RelayResult.Paired)
        val second = sync.pair(clara.code()) as RelayResult.Paired
        assertTrue(second.toldRelay)
        assertEquals(setOf(libra.kid, clara.kid), relay.devices.keys)
    }

    @Test
    fun publishAndDoneEndToEnd() = runBlocking {
        pairBoth()
        // The keys are kept wrapped.
        val stored = db.syncPeerDao().get("b7e2")!!
        assertFalse(stored.relayAuthKey!!.contains(RelayCrypto.b64(libra.auth)))
        assertNull(stored.tokenHash)

        val published = sync.publish() as RelayResult.Published
        assertEquals(2, published.devices)
        assertEquals(2, published.editions)
        // The package was a cache file, gone after upload.
        assertTrue(File(folder.root.listFiles()!!.first { it.isDirectory && File(it, "relay").exists() }, "relay").list()!!.isEmpty())

        val entries = download(libra)
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

        // The Libra reads; the Clara sends a batch that names the Libra, which must be dropped.
        relay.deviceUploads(libra.auth, "1-2", changes(libra, "1-2", "b7e2", opened(1, ids.lead, 10), opened(2, ids.top, 12)))
        relay.deviceUploads(clara.auth, "1-1", changes(clara, "1-1", "b7e2", opened(1, ids.section, 5)))

        val done = sync.done() as RelayResult.Collected
        assertEquals(1, done.batches)
        assertEquals(2, done.events)
        assertEquals(1, done.dropped)
        assertEquals(0, done.left)
        // Every batch handled is deleted from the relay, the dropped one included.
        assertTrue(relay.changes.isEmpty())
        assertEquals(NOW.plusSeconds(600), db.engagementDao().get(ids.lead)!!.openedAt)
        assertNull(db.engagementDao().get(ids.section)?.openedAt)
        assertEquals(2L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertEquals(0L, db.syncPeerDao().get("c9f1")!!.ackedSeq)

        // The next package carries what was merged.
        sync.publish() as RelayResult.Published
        val next = download(clara)
        assertEquals(mapOf("b7e2" to 2L, "c9f1" to 0L), manifest(next)["acked"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
        val bundle = Json.parseToJsonElement(next.getValue("editions/${ids.edition}.json").decodeToString()).jsonObject
        assertEquals(
            BundleExport.time(NOW.plusSeconds(600)),
            bundle["articles"]!!.jsonObject[ids.lead.toString()]!!.jsonObject["engagement"]!!.jsonObject["openedAt"]!!.jsonPrimitive.content,
        )

        // A retried batch (seq at or below ackedSeq) changes nothing; a new one merges.
        relay.deviceUploads(libra.auth, "1-2", changes(libra, "1-2", "b7e2", opened(1, ids.lead, 1), opened(2, ids.top, 1)))
        relay.deviceUploads(libra.auth, "3-3", changes(libra, "3-3", "b7e2", opened(3, ids.index, 20)))
        val again = sync.done() as RelayResult.Collected
        assertEquals(2, again.batches)
        assertEquals(1, again.events)
        assertEquals(NOW.plusSeconds(600), db.engagementDao().get(ids.lead)!!.openedAt)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)

        // Status: the Clara has the current package, the Libra has not fetched it.
        val status = sync.status()
        assertNull(status.problem)
        assertTrue(status.devices.getValue("c9f1").hasCurrentPackage)
        assertFalse(status.devices.getValue("b7e2").hasCurrentPackage)
        // Twice in the same second: the relay refuses the repeated signature, and the client signs again.
        assertNull(sync.status().problem)
    }

    @Test
    fun batchesOfOneDeviceMergeInSeqOrder() = runBlocking {
        pairBoth()
        relay.deviceUploads(libra.auth, "3-3", changes(libra, "3-3", "b7e2", opened(3, ids.top, 3)))
        relay.deviceUploads(libra.auth, "1-2", changes(libra, "1-2", "b7e2", opened(1, ids.lead, 1), opened(2, ids.section, 2)))
        val done = sync.done() as RelayResult.Collected
        assertEquals(3, done.events)
        assertNotNull(db.engagementDao().get(ids.lead)!!.openedAt)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
    }

    @Test
    fun reUploadedAndOverlappingBatchesMergeEachEventOnce() = runBlocking {
        pairBoth()
        // After a 401 unknown, or 48 hours without an ack, a Kobo uploads its unacknowledged events
        // again, in new batches whose ranges overlap the old ones.
        relay.deviceUploads(libra.auth, "1-2", changes(libra, "1-2", "b7e2", opened(1, ids.lead, 1), opened(2, ids.top, 2)))
        relay.deviceUploads(libra.auth, "2-3", changes(libra, "2-3", "b7e2", opened(2, ids.top, 2), opened(3, ids.section, 3)))
        var done = sync.done() as RelayResult.Collected
        assertEquals(2, done.batches)
        assertEquals(3, done.events)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        // A later re-upload of everything since 1 adds only what is new.
        relay.deviceUploads(
            libra.auth, "1-4",
            changes(libra, "1-4", "b7e2", opened(1, ids.lead, 1), opened(2, ids.top, 2), opened(3, ids.section, 3), opened(4, ids.index, 4)),
        )
        done = sync.done() as RelayResult.Collected
        assertEquals(1, done.events)
        assertEquals(4L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertTrue(relay.changes.isEmpty())
    }

    @Test
    fun eventsAlreadyMergedAreSkipped() = runBlocking {
        // Events 1-2 were merged before: over Wi-Fi by an earlier version, or from a batch sent twice.
        pairBoth()
        db.syncPeerDao().setAcked("b7e2", 2, NOW)
        val pageView = SyncEvent(seq = 2, type = "pageView", editionId = ids.edition, page = 1, ms = 5_000, at = BundleExport.time(NOW))
        // The same events come again through the relay, with one new one.
        relay.deviceUploads(libra.auth, "1-3", changes(libra, "1-3", "b7e2", opened(1, ids.lead, 1), pageView, opened(3, ids.top, 3)))
        val done = sync.done() as RelayResult.Collected
        assertEquals(1, done.events)
        assertEquals(3L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        // The page time from before was not added again.
        assertTrue(db.editionDao().getPageViews(ids.edition).isEmpty())
    }

    @Test
    fun batchesThatDoNotReadAreDropped() = runBlocking {
        pairBoth()
        // Encrypted with the other device's key; seq outside its range; and not an envelope at all.
        relay.deviceUploads(libra.auth, "1-1", changes(clara, "1-1", "b7e2", opened(1, ids.lead, 1)))
        relay.deviceUploads(clara.auth, "1-1", changes(clara, "1-1", "c9f1", opened(2, ids.lead, 1)))
        relay.deviceUploads(clara.auth, "5-6", "garbage".toByteArray())
        val done = sync.done() as RelayResult.Collected
        assertEquals(0, done.batches)
        assertEquals(3, done.dropped)
        assertTrue(relay.changes.isEmpty())
        assertNull(db.engagementDao().get(ids.lead)?.openedAt)
        assertEquals(0L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
    }

    @Test
    fun aRelayThatLostTheGroupIsRegisteredAgain() = runBlocking {
        pairBoth()
        assertEquals(1, relay.groupCreations)
        relay.forgetGroup()
        assertTrue(sync.done() is RelayResult.Collected)
        assertEquals(2, relay.groupCreations)
        assertEquals(setOf(libra.kid, clara.kid), relay.devices.keys)
    }

    @Test
    fun theRegistrationTokenIsSentWhenTheRelayAsksForOne() = runBlocking {
        relay.registrationToken = "letmein"
        val first = sync.pair(libra.code()) as RelayResult.Paired
        assertEquals(RelayProblem.REGISTRATION_REFUSED, first.problem)
        assertEquals(RelayProblem.REGISTRATION_REFUSED, (sync.publish() as RelayResult.Failed).problem)
        useRelay(server.url("/").toString(), token = "letmein")
        assertTrue(sync.publish() is RelayResult.Published)
        assertEquals(setOf(libra.kid), relay.devices.keys)
    }

    @Test
    fun aSkewedClockIsCorrectedFromTheRelaysDate() = runBlocking {
        pairBoth()
        relay.clockSeconds = { clock.instant().epochSecond + 1_000 }
        assertNull(sync.status().problem)
        assertTrue(sync.done() is RelayResult.Collected)
    }

    @Test
    fun forgettingADeviceTellsTheRelay() = runBlocking {
        pairBoth()
        sync.forget("b7e2")
        assertEquals(setOf(clara.kid), relay.devices.keys)
        assertNull(db.syncPeerDao().get("b7e2"))
        // Its batches are gone from the relay, and a batch from an unknown key would be dropped.
        assertEquals(listOf("c9f1"), db.syncPeerDao().relayPeers().map { it.deviceId })
    }

    @Test
    fun pairingTheSameDeviceAgainKeepsAckedSeq() = runBlocking {
        pairBoth()
        relay.deviceUploads(libra.auth, "1-1", changes(libra, "1-1", "b7e2", opened(1, ids.lead, 1)))
        sync.done()
        assertEquals(1L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        // Reset on the Kobo makes a new secret; the same deviceId keeps its place.
        val reset = Kobo("b7e2", "Kobo Libra 2", 1264)
        sync.pair(reset.code())
        assertEquals(1L, db.syncPeerDao().get("b7e2")!!.ackedSeq)
        assertEquals(setOf(reset.kid, clara.kid), relay.devices.keys)
    }

    @Test
    fun thePackageIsUploadedInPartsAndJoinedOnTheRelay() = runBlocking {
        pairBoth()
        val published = sync.publish() as RelayResult.Published
        val id = relay.current!!
        val parts = ((published.bytes + PART_BYTES - 1) / PART_BYTES).toInt()
        assertTrue("several parts", parts > 1)
        assertEquals((0 until parts).map { "$id/$it" }, relay.partRequests.toList())
        assertEquals(published.bytes, relay.packages[id]!!.size.toLong())
        assertTrue(relay.parts.isEmpty())
        // What the Kobo gets reads whole.
        assertTrue(download(libra).containsKey("manifest.json"))
    }

    @Test
    fun aPartThatFailsIsSentAgain() = runBlocking {
        pairBoth()
        relay.failPartsOnce += 1
        val published = sync.publish()
        assertTrue(published.toString(), published is RelayResult.Published)
        assertEquals(2, relay.partRequests.count { it.endsWith("/1") })
        assertTrue(download(libra).containsKey("manifest.json"))
    }

    @Test
    fun aPartThatKeepsFailingFailsThePublish() = runBlocking {
        pairBoth()
        relay.failPartAlways = 1
        val result = sync.publish()
        assertTrue(result.toString(), result is RelayResult.Failed)
        assertEquals(RelayClient.PART_ATTEMPTS, relay.partRequests.count { it.endsWith("/1") })
        // Nothing was completed or published.
        assertTrue(relay.packages.isEmpty())
        assertNull(relay.current)
    }

    @Test
    fun withNoDeviceThereIsNothingToPublish() = runBlocking {
        assertTrue(sync.publish() is RelayResult.NoDevices)
        assertNull(sync.doneIfConfigured())
    }

    @Test
    fun aPlainHttpRelayIsRefused() = runBlocking {
        useRelay("http://relay.example")
        sync.pair(libra.code())
        assertEquals(RelayProblem.NOT_HTTPS, (sync.publish() as RelayResult.Failed).problem)
        assertTrue(relay.requests.isEmpty())
    }

    @Test
    fun devicesWhoseKeysAreLostAreLeftOut() = runBlocking {
        pairBoth()
        keys.lost = true
        assertEquals(setOf("b7e2", "c9f1"), sync.devicesWithLostKeys())
        assertTrue(sync.publish() is RelayResult.NoDevices)
        assertTrue(relay.devices.isEmpty())
    }

    private companion object {
        /** The part size the tests upload in. */
        const val PART_BYTES = 1024L
    }
}

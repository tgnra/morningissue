package net.tgnra.morningissue.server.sync

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.ManifestDto
import net.tgnra.morningissue.api.ManifestSavedDto
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.editions.BundleExport
import net.tgnra.morningissue.server.editions.EditionBundles
import net.tgnra.morningissue.server.editions.TestWorld
import net.tgnra.morningissue.server.service.ApiFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The manifest, the edition bundle and the saved shelf as JSON, against `docs/kobo-sync.md`
 * and section 4.3: the app's `BundleExportTest`, with the server's additions for the phone.
 */
class BundleExportTest {
    private val world = TestWorld(NOW)
    private val db = world.db
    private val ids: Seeded = runBlocking { SyncTestData.seed(world) }
    private val user = ids.user
    private val device: String = runBlocking { world.addDevice(user, "3f0c", width = 1264) }
    private val bundles = EditionBundles(db, world.clock)
    private val sync = SyncServiceImpl(db, bundles, world.clock, relearn = {})

    @After
    fun tearDown() = world.close()

    private suspend fun encoded(id: Long = ids.edition): String = BundleExport.encode(bundles.bundle(user, id))

    private suspend fun bundle(id: Long = ids.edition): JsonObject = Json.parseToJsonElement(encoded(id)).jsonObject

    private suspend fun manifest(): ManifestDto = sync.manifest(user, device, 3)

    @Test
    fun theBundleHasTheSpecsShape() = runBlocking {
        val bundle = bundle()
        // The Kobo's fields first, in the spec's order; then those for the phone.
        assertEquals(
            listOf("id", "date", "kind", "number", "length", "releaseAt", "finishedAt", "pages", "articles"),
            bundle.keys.toList().take(9),
        )
        assertEquals(listOf("cutoffAt", "builtAt", "openedAt", "pageViewsTracked", "pageViews"), bundle.keys.toList().drop(9))
        assertEquals(ids.edition, bundle.getValue("id").jsonPrimitive.long)
        assertEquals("2026-11-11", bundle.getValue("date").jsonPrimitive.content)
        assertEquals("MORNING", bundle.getValue("kind").jsonPrimitive.content)
        assertEquals("STANDARD", bundle.getValue("length").jsonPrimitive.content)
        assertEquals("2026-11-11T06:30:00Z", bundle.getValue("releaseAt").jsonPrimitive.content)
        assertEquals(JsonNull, bundle.getValue("finishedAt"))

        // Pages by page number, items by slot, whatever order they were stored in.
        val pages = bundle.getValue("pages").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1L, 2L, 3L), pages.map { it.getValue("page").jsonPrimitive.long })
        val front = pages[0].getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(ids.lead, ids.top), front.map { it.getValue("articleId").jsonPrimitive.long })
        assertEquals(listOf("articleId", "slot", "kind", "section", "reason"), front[0].keys.toList())
        assertEquals("LEAD", front[0].getValue("kind").jsonPrimitive.content)
        assertEquals(JsonNull, front[0].getValue("section"))
        val section = pages[1].getValue("items").jsonArray.single().jsonObject
        assertEquals("SECTION", section.getValue("kind").jsonPrimitive.content)
        assertEquals("Growing", section.getValue("section").jsonPrimitive.content)
        assertEquals("INDEX", pages[2].getValue("items").jsonArray.single().jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(JsonNull, pages[2].getValue("items").jsonArray.single().jsonObject.getValue("reason"))

        // Every article an item names, keyed by its ID as a string.
        val articles = bundle.getValue("articles").jsonObject
        assertEquals(setOf(ids.lead, ids.top, ids.section, ids.index).map(Long::toString).toSet(), articles.keys)
        val lead = articles.getValue(ids.lead.toString()).jsonObject
        assertEquals(
            listOf("feedTitle", "title", "author", "url", "publishedAt", "summary", "imageUrl", "wordCount", "blocks", "engagement"),
            lead.keys.toList().take(10),
        )
        assertEquals("The Kitchen Garden", lead.getValue("feedTitle").jsonPrimitive.content)
        assertEquals("M. Fenwick", lead.getValue("author").jsonPrimitive.content)
        assertEquals("2026-11-11T04:30:00Z", lead.getValue("publishedAt").jsonPrimitive.content)
        assertEquals(420L, lead.getValue("wordCount").jsonPrimitive.long)
        // For the phone.
        assertEquals(ids.feed, lead.getValue("feedId").jsonPrimitive.long)
        assertEquals("frost", lead.getValue("guid").jsonPrimitive.content)
        assertEquals("2026-11-11T05:30:00Z", lead.getValue("firstSeenAt").jsonPrimitive.content)
        val top = articles.getValue(ids.top.toString()).jsonObject
        assertEquals(JsonNull, top.getValue("author"))
        assertEquals(JsonNull, top.getValue("imageUrl"))
        assertEquals(JsonNull, articles.getValue(ids.section.toString()).jsonObject.getValue("summary"))
    }

    @Test
    fun blocksAreTheStoredJsonEmbeddedAsAValue() = runBlocking {
        val articles = bundle().getValue("articles").jsonObject
        val blocks = articles.getValue(ids.lead.toString()).jsonObject.getValue("blocks")
        // An array, not a string holding one: the stored text, parsed, unchanged.
        assertTrue(blocks is JsonArray)
        assertEquals(Json.parseToJsonElement(ContentBlocks.encode(EVERY_BLOCK)), blocks)
        assertEquals(listOf("h", "p", "img", "quote", "list", "list", "pre"), blocks.jsonArray.map { it.jsonObject.getValue("t").jsonPrimitive.content })
        assertEquals(EVERY_BLOCK, ContentBlocks.decode(blocks.toString()))
        // No stored text is an empty list; the Kobo shows the summary.
        assertEquals(JsonArray(emptyList()), articles.getValue(ids.top.toString()).jsonObject.getValue("blocks"))
        assertEquals(JsonArray(emptyList()), BundleExport.blocks("not json"))
        assertEquals(JsonArray(emptyList()), BundleExport.blocks("{\"t\":\"p\"}"))
    }

    @Test
    fun engagementIsTheUsersState() = runBlocking {
        val untouched = bundle().getValue("articles").jsonObject.getValue(ids.lead.toString()).jsonObject.getValue("engagement").jsonObject
        assertEquals(JsonNull, untouched.getValue("openedAt"))
        assertEquals(JsonPrimitive(0.0f), untouched.getValue("readFraction"))

        world.open(user, ids.lead, RELEASE.plusMillis(61_500))
        world.readFraction(user, ids.lead, 0.75f)
        world.like(user, ids.lead, RELEASE.plusSeconds(120))
        val engaged = bundle().getValue("articles").jsonObject.getValue(ids.lead.toString()).jsonObject.getValue("engagement").jsonObject
        assertEquals("2026-11-11T06:31:01.500Z", engaged.getValue("openedAt").jsonPrimitive.content)
        assertEquals(JsonPrimitive(0.75f), engaged.getValue("readFraction"))
        assertEquals("2026-11-11T06:32:00Z", engaged.getValue("likedAt").jsonPrimitive.content)
        assertEquals(JsonNull, engaged.getValue("savedAt"))

        // Another user reading the same post does not show here.
        val other = world.addUser()
        SyncTestData.seed(world, userId = other)
        world.save(other, ids.lead, RELEASE)
        val mine = bundle().getValue("articles").jsonObject.getValue(ids.lead.toString()).jsonObject.getValue("engagement").jsonObject
        assertEquals(JsonNull, mine.getValue("savedAt"))
    }

    @Test
    fun theManifestOffersTheNewestReleasedEditionsNewestFirst() = runBlocking {
        val manifest = Json.parseToJsonElement(ApiJson.json.encodeToString(ManifestDto.serializer(), manifest())).jsonObject
        assertEquals(listOf("v", "protocol", "dbId", "serverTime", "editions", "saved", "acked"), manifest.keys.toList())
        assertEquals(listOf("count", "version"), manifest.getValue("saved").jsonObject.keys.toList())
        val editions = manifest.getValue("editions").jsonArray.map { it.jsonObject }
        assertEquals(listOf(ids.edition, ids.older), editions.map { it.getValue("id").jsonPrimitive.long })
        assertEquals(listOf("id", "date", "kind", "number", "releaseAt", "version"), editions[0].keys.toList())
        assertEquals(120L, editions[0].getValue("number").jsonPrimitive.long)

        // Two more released editions push the oldest out.
        for (day in 1..2) {
            db.editionDao().insertEditionWithItems(
                EditionEntity(
                    userId = user, date = LocalDate.parse("2026-11-11"), kind = if (day == 1) EditionKind.EVENING else EditionKind.EXTRA,
                    cutoffAt = RELEASE.plusSeconds(day * 60L), releaseAt = RELEASE.plusSeconds(day * 60L), builtAt = RELEASE,
                    length = EditionLength.SHORT, number = 120 + day,
                ),
                emptyList(),
            )
        }
        val three = manifest().editions
        assertEquals(3, three.size)
        assertEquals(listOf(EditionKind.EXTRA, EditionKind.EVENING, EditionKind.MORNING), three.map { it.kind })
    }

    @Test
    fun theVersionChangesExactlyWhenTheBundleDoes() = runBlocking {
        val first = manifest().editions.first().version
        assertEquals(first, manifest().editions.first().version)
        assertEquals(64, first.length)
        assertEquals(BundleExport.version(encoded()), first)
        world.save(user, ids.lead, RELEASE.plusSeconds(60))
        val second = manifest().editions.first().version
        assertNotEquals(first, second)
        assertEquals(BundleExport.version(encoded()), second)
        // Full text arriving changes the bundle without touching any change stamp.
        world.storeBody(ids.top, ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText("Earth them up well.")))), "Earth them up well.", 4)
        val third = manifest().editions.first().version
        assertNotEquals(second, third)
        assertEquals(BundleExport.version(encoded()), third)
        // So does a page view, which arrives as an event.
        sync.postEvents(
            user,
            device,
            net.tgnra.morningissue.api.EventsRequest(
                listOf(net.tgnra.morningissue.kobo.SyncEvent(1, "pageView", editionId = ids.edition, page = 2, ms = 4_000, at = RELEASE.toString())),
            ),
        )
        val fourth = manifest().editions.first().version
        assertNotEquals(third, fourth)
        assertEquals(BundleExport.version(encoded()), fourth)
    }

    @Test
    fun theImagesAKoboFetchesAreEveryLeadAndBodyImageOnce() = runBlocking {
        val bundle = bundles.bundle(user, ids.edition)
        // The lead's own picture, then the one in its body; the other stories have none.
        assertEquals(
            listOf("https://garden.example/img/lead.jpg", "https://garden.example/img/frost.jpg"),
            BundleExport.imageUrls(bundle.articles.values),
        )
        // Named twice, listed once; blocks that are not a JSON array are passed over.
        val twice = bundle.articles.values.first { it.imageUrl != null }
        assertEquals(2, BundleExport.imageUrls(listOf(twice, twice.copy(blocks = JsonPrimitive("x")))).size)
    }

    @Test
    fun theSavedListIsVersionedByItsContent() = runBlocking {
        world.save(user, ids.section, RELEASE.plusSeconds(60))
        val saved = bundles.saved(user)
        assertEquals(listOf(ids.section), saved.order)
        assertEquals(64, saved.version.length)
        assertEquals(BundleExport.version(BundleExport.encode(saved.copy(version = ""))), saved.version)
        assertEquals(ManifestSavedDto(1, saved.version), manifest().saved)
        // Another user's saved articles are not on this shelf.
        val other = world.addUser()
        SyncTestData.seed(world, userId = other)
        world.save(other, ids.lead, RELEASE)
        assertEquals(saved, bundles.saved(user))
    }

    @Test
    fun anUnreleasedMissingOrOtherUsersEditionIsNotExported() = runBlocking {
        val other = world.addUser()
        val theirs = SyncTestData.seed(world, userId = other)
        for (id in listOf(ids.unreleased, 999_999L, theirs.edition)) {
            try {
                bundles.bundle(user, id)
                fail("edition $id was exported")
            } catch (e: ApiFailure) {
                assertEquals(404, e.status)
            }
        }
        // It is exported once its release time has passed.
        world.clock.now = RELEASE.plusSeconds(86_400)
        assertEquals(ids.unreleased, bundles.bundle(user, ids.unreleased).id)
    }
}

private val SHED_BLOCKS = listOf(
    ContentBlock.Paragraph(RichText("Before the frost, the tools go in.")),
    ContentBlock.Image(url = "https://garden.example/img/shed-door.jpg", alt = "The shed door", width = 900, height = 1200),
    ContentBlock.Paragraph(RichText("Oil the blades.")),
)

/**
 * Writes the sample manifest, edition bundle and saved shelf the Kobo side parses, through the
 * real export path, to `docs/kobo-sync-fixtures/`: the server's formats (`protocol: server/1`,
 * with the fields for the phone). The output is deterministic, so a change to the format shows
 * up as a change to the committed files.
 */
class KoboFixturesTest {
    private val pretty = Json(ApiJson.json) { prettyPrint = true }

    @Test
    fun writesTheFixtures() = runBlocking {
        val world = TestWorld(NOW)
        val db = world.db
        try {
            val ids = SyncTestData.seed(world)
            val user = ids.user
            val device = world.addDevice(user, "b7e2c1d4-0f8a-4d6e-9b3c-5a7e2f1c8d90", width = 1264, ackedSeq = 41)
            world.open(user, ids.lead, RELEASE.plusSeconds(600))
            world.readFraction(user, ids.lead, 0.5f)
            world.save(user, ids.top, RELEASE.plusSeconds(900))
            world.viewPages(user, ids.edition, at = RELEASE.plusSeconds(300))
            // A saved article from no edition on offer, with a body and pictures.
            val shed = db.importDao().insertNew(
                ArticleEntity(
                    feedId = ids.feed, guid = "shed", url = "https://garden.example/shed", title = "A shed for the winter",
                    summary = "Tools, dry.", imageUrl = "https://garden.example/img/shed.jpg", author = "M. Fenwick",
                    publishedAt = RELEASE.minusSeconds(200_000), firstSeenAt = RELEASE.minusSeconds(190_000), wordCount = 610,
                ),
                blocksJson = ContentBlocks.encode(SHED_BLOCKS),
                plainText = ContentBlocks.plainText(SHED_BLOCKS),
            )!!
            world.save(user, shed, RELEASE.plusSeconds(1_200))
            val bundles = EditionBundles(db, world.clock)
            val manifest = SyncServiceImpl(db, bundles, world.clock, relearn = {}).manifest(user, device, 3)
            val bundle = bundles.bundle(user, ids.edition)
            val saved = bundles.saved(user)

            // Tests run in server/; the fixtures live beside the spec.
            val dir = File("../docs/kobo-sync-fixtures").apply { mkdirs() }
            File(dir, "manifest.json").writeText(pretty.encodeToString(ManifestDto.serializer(), manifest) + "\n")
            File(dir, "edition.json").writeText(pretty.encodeToString(EditionBundleDto.serializer(), bundle) + "\n")
            File(dir, "saved.json").writeText(pretty.encodeToString(SavedBundleDto.serializer(), saved) + "\n")

            val written = Json.parseToJsonElement(File(dir, "edition.json").readText()).jsonObject
            val types = written.getValue("articles").jsonObject.values
                .flatMap { it.jsonObject.getValue("blocks").jsonArray }
                .map { it.jsonObject.getValue("t").jsonPrimitive.content }.toSet()
            assertEquals(setOf("p", "h", "img", "quote", "list", "pre"), types)
            val styles = written.toString().let { text -> listOf("b", "i", "code", "a", "s", "sup", "sub").filter { "\"style\":\"$it\"" in text } }
            assertEquals(7, styles.size)
            val writtenManifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
            assertEquals("server/1", writtenManifest.getValue("protocol").jsonPrimitive.content)
            assertEquals(41L, writtenManifest.getValue("acked").jsonObject.getValue(device).jsonPrimitive.long)
            assertEquals(manifest.editions.first().version, BundleExport.version(BundleExport.encode(bundle)))
            assertEquals(listOf(shed, ids.top), saved.order)
            assertEquals(ManifestSavedDto(2, saved.version), manifest.saved)
            // The written files read back as what was exported.
            assertEquals(bundle, ApiJson.json.decodeFromString(EditionBundleDto.serializer(), File(dir, "edition.json").readText()))
            assertEquals(saved.version, BundleExport.version(BundleExport.encode(ApiJson.json.decodeFromString(SavedBundleDto.serializer(), File(dir, "saved.json").readText()).copy(version = ""))))
        } finally {
            db.close()
        }
    }
}

package net.tgnra.morningissue.kobo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.data.db.EditionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The manifest and the edition bundle, as JSON, against `docs/kobo-sync.md`. */
@RunWith(AndroidJUnit4::class)
class BundleExportTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var ids: Seeded
    private lateinit var exporter: KoboExporter

    @Before
    fun setUp() = runTest {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        ids = KoboTestData.seed(db)
        exporter = KoboExporter(db, NOW_CLOCK)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bundle(id: Long = ids.edition): JsonObject = Json.parseToJsonElement(exporter.edition(id)!!).jsonObject

    @Test
    fun theBundleHasTheSpecsShape() = runTest {
        val bundle = bundle()
        assertEquals(
            listOf("id", "date", "kind", "number", "length", "releaseAt", "finishedAt", "pages", "articles"),
            bundle.keys.toList(),
        )
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
            lead.keys.toList(),
        )
        assertEquals("The Kitchen Garden", lead.getValue("feedTitle").jsonPrimitive.content)
        assertEquals("M. Fenwick", lead.getValue("author").jsonPrimitive.content)
        assertEquals("2026-11-11T04:30:00Z", lead.getValue("publishedAt").jsonPrimitive.content)
        assertEquals(420L, lead.getValue("wordCount").jsonPrimitive.long)
        val top = articles.getValue(ids.top.toString()).jsonObject
        assertEquals(JsonNull, top.getValue("author"))
        assertEquals(JsonNull, top.getValue("imageUrl"))
        assertEquals(JsonNull, articles.getValue(ids.section.toString()).jsonObject.getValue("summary"))
    }

    @Test
    fun blocksAreTheStoredJsonEmbeddedAsAValue() = runTest {
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
    fun engagementIsThePhonesState() = runTest {
        val untouched = bundle().getValue("articles").jsonObject.getValue(ids.lead.toString()).jsonObject.getValue("engagement").jsonObject
        assertEquals(listOf("openedAt", "readFraction", "readAt", "savedAt", "likedAt"), untouched.keys.toList())
        assertEquals(JsonNull, untouched.getValue("openedAt"))
        assertEquals(JsonPrimitive(0.0f), untouched.getValue("readFraction"))

        db.engagementDao().markOpened(ids.lead, RELEASE.plusMillis(61_500))
        db.engagementDao().recordReadFraction(ids.lead, 0.75f)
        db.engagementDao().setLiked(ids.lead, RELEASE.plusSeconds(120))
        val engaged = bundle().getValue("articles").jsonObject.getValue(ids.lead.toString()).jsonObject.getValue("engagement").jsonObject
        // To the second.
        assertEquals("2026-11-11T06:31:01Z", engaged.getValue("openedAt").jsonPrimitive.content)
        assertEquals(JsonPrimitive(0.75f), engaged.getValue("readFraction"))
        assertEquals("2026-11-11T06:32:00Z", engaged.getValue("likedAt").jsonPrimitive.content)
        assertEquals(JsonNull, engaged.getValue("savedAt"))
    }

    @Test
    fun theManifestOffersTheThreeNewestReleasedEditionsNewestFirst() = runTest {
        val manifest = Json.parseToJsonElement(KoboProtocol.json.encodeToString(exporter.manifest("3f0c", 41))).jsonObject
        assertEquals(listOf("v", "dbId", "ackedSeq", "editions", "saved"), manifest.keys.toList())
        assertEquals(listOf("count", "version"), manifest.getValue("saved").jsonObject.keys.toList())
        assertEquals(0L, manifest.getValue("saved").jsonObject.getValue("count").jsonPrimitive.long)
        assertEquals(1L, manifest.getValue("v").jsonPrimitive.long)
        assertEquals(41L, manifest.getValue("ackedSeq").jsonPrimitive.long)
        val editions = manifest.getValue("editions").jsonArray.map { it.jsonObject }
        // Tomorrow's edition is built but not released: never offered.
        assertEquals(listOf(ids.edition, ids.older), editions.map { it.getValue("id").jsonPrimitive.long })
        assertEquals(listOf("id", "date", "kind", "number", "releaseAt", "version"), editions[0].keys.toList())
        assertEquals(120L, editions[0].getValue("number").jsonPrimitive.long)

        // Two more released editions push the oldest out.
        for (day in 1..2) {
            db.editionDao().insertEditionWithItems(
                EditionEntity(
                    date = java.time.LocalDate.parse("2026-11-11"), kind = if (day == 1) EditionKind.EVENING else EditionKind.EXTRA,
                    cutoffAt = RELEASE.plusSeconds(day * 60L), releaseAt = RELEASE.plusSeconds(day * 60L), builtAt = RELEASE,
                    length = EditionLength.SHORT, number = 120 + day,
                ),
                emptyList(),
            )
        }
        val three = exporter.manifest("3f0c", 0).editions
        assertEquals(3, three.size)
        assertEquals(listOf("EXTRA", "EVENING", "MORNING"), three.map { it.kind })
    }

    @Test
    fun theVersionChangesExactlyWhenTheBundleDoes() = runTest {
        val first = exporter.manifest("3f0c", 0).editions.first().version
        assertEquals(first, exporter.manifest("3f0c", 0).editions.first().version)
        assertEquals(32, first.length)
        db.engagementDao().setSaved(ids.lead, RELEASE.plusSeconds(60))
        val second = exporter.manifest("3f0c", 0).editions.first().version
        assertNotEquals(first, second)
        assertEquals(BundleExport.version(exporter.edition(ids.edition)!!), second)
    }

    @Test
    fun theImagesAKoboFetchesAreEveryLeadAndBodyImageOnce() = runTest {
        val bundle = KoboProtocol.json.decodeFromString<EditionBundle>(exporter.edition(ids.edition)!!)
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
    fun theSavedListIsVersionedByItsContent() = runTest {
        db.engagementDao().setSaved(ids.section, RELEASE.plusSeconds(60))
        val saved = KoboProtocol.json.decodeFromString<SavedBundle>(exporter.saved())
        assertEquals(listOf(ids.section), saved.order)
        assertEquals(32, saved.version.length)
        assertEquals(BundleExport.version(KoboProtocol.json.encodeToString(saved.copy(version = ""))), saved.version)
        assertEquals(ManifestSaved(1, saved.version), exporter.manifest("3f0c", 0).saved)
        // Saving changes the edition's bundle too, since it carries the engagement.
        val offered = exporter.offeredArticles()
        assertEquals(5, offered.editions.size)
        assertEquals(1, offered.saved.size)
    }

    @Test
    fun anUnreleasedOrMissingEditionIsNotExported() = runTest {
        assertNull(exporter.edition(ids.unreleased))
        assertNull(exporter.edition(999_999))
        // It is exported once its release time has passed.
        val tomorrow = KoboExporter(db, Clock.fixed(RELEASE.plusSeconds(86_400), ZoneOffset.UTC))
        assertEquals(ids.unreleased, Json.parseToJsonElement(tomorrow.edition(ids.unreleased)!!).jsonObject.getValue("id").jsonPrimitive.long)
    }
}

private val SHED_BLOCKS = listOf(
    ContentBlock.Paragraph(RichText("Before the frost, the tools go in.")),
    ContentBlock.Image(url = "https://garden.example/img/shed-door.jpg", alt = "The shed door", width = 900, height = 1200),
    ContentBlock.Paragraph(RichText("Oil the blades.")),
)

/**
 * Writes the sample manifest, edition bundle and saved list the Kobo side parses, through the real export
 * path, to `docs/kobo-sync-fixtures/`. The output is deterministic, so a change to the format
 * shows up as a change to the committed files.
 */
@RunWith(AndroidJUnit4::class)
class KoboFixturesTest {
    private val pretty = Json(KoboProtocol.json) { prettyPrint = true }

    @Test
    fun writesTheFixtures() = runTest {
        val db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val ids = KoboTestData.seed(db)
            db.engagementDao().markOpened(ids.lead, RELEASE.plusSeconds(600))
            db.engagementDao().recordReadFraction(ids.lead, 0.5f)
            db.engagementDao().setSaved(ids.top, RELEASE.plusSeconds(900))
            // A saved article from no edition on offer, with a body and pictures.
            val feedId = db.articleDao().getById(ids.lead)!!.feedId
            val shed = db.articleDao().insertNew(
                ArticleEntity(
                    feedId = feedId, guid = "shed", url = "https://garden.example/shed", title = "A shed for the winter",
                    summary = "Tools, dry.", imageUrl = "https://garden.example/img/shed.jpg", author = "M. Fenwick",
                    publishedAt = RELEASE.minusSeconds(200_000), firstSeenAt = RELEASE.minusSeconds(190_000), wordCount = 610,
                ),
                blocksJson = ContentBlocks.encode(SHED_BLOCKS),
                plainText = ContentBlocks.plainText(SHED_BLOCKS),
            )!!
            db.engagementDao().setSaved(shed, RELEASE.plusSeconds(1_200))
            val exporter = KoboExporter(db, NOW_CLOCK)
            val manifest = exporter.manifest("3f0c6f2e-6c55-4f7b-9d1e-0a6f2b9e5c11", 41)
            val bundle = KoboProtocol.json.decodeFromString<EditionBundle>(exporter.edition(ids.edition)!!)
            val saved = KoboProtocol.json.decodeFromString<SavedBundle>(exporter.saved())

            // Tests run in app/; the fixtures live beside the spec.
            val dir = File("../docs/kobo-sync-fixtures").apply { mkdirs() }
            File(dir, "manifest.json").writeText(pretty.encodeToString(manifest) + "\n")
            File(dir, "edition.json").writeText(pretty.encodeToString(bundle) + "\n")
            File(dir, "saved.json").writeText(pretty.encodeToString(saved) + "\n")

            val written = Json.parseToJsonElement(File(dir, "edition.json").readText()).jsonObject
            val types = written.getValue("articles").jsonObject.values
                .flatMap { it.jsonObject.getValue("blocks").jsonArray }
                .map { it.jsonObject.getValue("t").jsonPrimitive.content }.toSet()
            assertEquals(setOf("p", "h", "img", "quote", "list", "pre"), types)
            val styles = written.toString().let { text -> listOf("b", "i", "code", "a", "s", "sup", "sub").filter { "\"style\":\"$it\"" in text } }
            assertEquals(7, styles.size)
            assertEquals(manifest.editions.first().version, BundleExport.version(BundleExport.encode(bundle)))
            assertEquals(listOf(shed, ids.top), saved.order)
            assertEquals(ManifestSaved(2, saved.version), manifest.saved)
        } finally {
            db.close()
        }
    }
}

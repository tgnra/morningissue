package net.tgnra.morningissue.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery catalogue's feed vectors are made offline by a Python copy of the embedder
 * (`tools/discovery/embedder.py`), and are compared on the phone with vectors made here. This
 * test holds the two together: `parity.json`, written by
 * `tools/discovery/build_catalogue.py parity`, holds 100 posts (title, summary, body) with the
 * text, script verdict and vector the Python side made for each. The app must agree on all three.
 *
 * If this fails after a change to [EmbeddableText], [WordPieceTokenizer] or [StaticEmbedder],
 * port the change to `tools/discovery/embedder.py`, regenerate `parity.json` and rebuild the
 * catalogue: its vectors are stale.
 */
class CatalogueParityTest {
    private class Case(
        val title: String,
        val summary: String?,
        val body: String?,
        val text: String,
        val nonLatin: Boolean,
        val vector: FloatArray?,
    )

    private val cases: List<Case> by lazy {
        val json = checkNotNull(javaClass.getResourceAsStream("/discovery/parity.json")) {
            "parity.json is missing from the test resources"
        }.use { it.readBytes().decodeToString() }
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(StaticEmbedder.MODEL_ID, root.getValue("modelId").jsonPrimitive.int)
        fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        root.getValue("cases").jsonArray.map { it.jsonObject }.map { case ->
            Case(
                title = case.string("title").orEmpty(),
                summary = case.string("summary"),
                body = case.string("body"),
                text = case.string("text").orEmpty(),
                nonLatin = case.getValue("nonLatin").jsonPrimitive.boolean,
                vector = case["vector"]?.takeIf { it !is JsonNull }?.jsonArray
                    ?.map { it.jsonPrimitive.float }?.toFloatArray(),
            )
        }
    }

    @Test
    fun theFileHoldsAHundredCases() {
        assertEquals(100, cases.size)
        assertTrue(cases.count { it.vector != null } >= 80)
    }

    @Test
    fun embeddableTextIsBuiltTheSameWay() {
        for (case in cases) {
            assertEquals("text of \"${case.title.take(60)}\"", case.text, EmbeddableText.of(case.title, case.summary, case.body))
        }
    }

    @Test
    fun theScriptRuleAgrees() {
        for (case in cases) {
            assertEquals("script of \"${case.text.take(60)}\"", case.nonLatin, EmbeddableText.isMostlyNonLatin(case.text))
        }
    }

    @Test
    fun vectorsAgreeWithinCosine0999() {
        var lowest = 1f
        for (case in cases) {
            if (case.nonLatin) continue
            val mine = EmbeddingFixtures.embedder.embed(case.text)
            if (case.vector == null) {
                assertNull("Python found no vector for \"${case.text.take(60)}\"", mine)
                continue
            }
            assertNotNull("no vector for \"${case.text.take(60)}\"", mine)
            val cosine = StaticEmbedder.cosine(mine!!, case.vector)
            lowest = minOf(lowest, cosine)
            assertTrue("cosine $cosine for \"${case.text.take(60)}\"", cosine >= 0.999f)
        }
        println("CatalogueParityTest: lowest cosine $lowest")
    }
}

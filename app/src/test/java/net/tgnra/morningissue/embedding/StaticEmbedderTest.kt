package net.tgnra.morningissue.embedding

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticEmbedderTest {
    private val embedder = EmbeddingFixtures.embedder

    private fun embed(text: String): FloatArray = checkNotNull(embedder.embed(text)) { "no vector for \"$text\"" }

    private fun similarity(a: String, b: String): Float = StaticEmbedder.cosine(embed(a), embed(b))

    private fun length(vector: FloatArray): Double = sqrt(vector.sumOf { it.toDouble() * it })

    @Test
    fun aVectorHasUnitLength() {
        val vector = embed("Central bank holds interest rates steady as inflation cools")
        assertEquals(StaticEmbedder.DIMENSIONS, vector.size)
        assertEquals(1.0, length(vector), 1e-5)
        assertEquals(1f, StaticEmbedder.cosine(vector, vector), 1e-5f)
    }

    @Test
    fun textWithoutAKnownPieceHasNoVector() {
        assertNull(embedder.embed(""))
        assertNull(embedder.embed("   \n\t "))
        assertNull(embedder.embed("😀 🚀 👍"))
        assertNull(embedder.embed("a".repeat(150)))
        // One known word among unknown ones is enough, and the unknown ones change nothing.
        assertArrayEquals(embed("news"), embed("😀 news 🚀"), 0f)
    }

    /**
     * Agreement with the float32 model. The reference vectors come from the original table and
     * Hugging Face's tokenizer (`tools/embedding/make_reference.py`); the only difference left
     * is the asset's 8-bit table, which the script measures at a cosine of 0.99994 or better.
     */
    @Test
    fun vectorsAgreeWithTheFloatReference() {
        val cases = EmbeddingFixtures.reference.embeddings
        assertTrue(cases.size >= 30)
        var worst = 1f
        var worstStored = 1f
        for (case in cases) {
            val vector = embed(case.text)
            val cosine = StaticEmbedder.cosine(vector, case.vector)
            assertTrue("cosine $cosine for \"${case.text.take(60)}\"", cosine >= REFERENCE_TOLERANCE)
            worst = minOf(worst, cosine)
            val stored = StaticEmbedder.cosine(StaticEmbedder.fromBytes(StaticEmbedder.toBytes(vector)), case.vector)
            assertTrue("stored cosine $stored for \"${case.text.take(60)}\"", stored >= STORED_TOLERANCE)
            worstStored = minOf(worstStored, stored)
        }
        println("EMBEDDING reference agreement over ${cases.size} texts: lowest cosine $worst, after toBytes/fromBytes $worstStored")
    }

    @Test
    fun theStoredFormRoundTripsToNearlyTheSameVector() {
        val vector = embed("Scientists discover a new species of deep-sea octopus off the coast of Chile")
        val bytes = StaticEmbedder.toBytes(vector)
        assertEquals(StaticEmbedder.DIMENSIONS, bytes.size)
        // The largest component uses the full range, whatever the vector's scale.
        assertEquals(127, bytes.maxOf { abs(it.toInt()) })
        assertArrayEquals(bytes, StaticEmbedder.toBytes(FloatArray(vector.size) { vector[it] * 40f }))
        val back = StaticEmbedder.fromBytes(bytes)
        assertEquals(1.0, length(back), 1e-5)
        assertTrue(StaticEmbedder.cosine(vector, back) > 0.9998f)
        // Stored bytes are stable: storing what was read back changes nothing.
        assertArrayEquals(bytes, StaticEmbedder.toBytes(back))
    }

    @Test
    fun aZeroVectorIsStoredAsZerosAndReadBackAsZeros() {
        val bytes = StaticEmbedder.toBytes(FloatArray(StaticEmbedder.DIMENSIONS))
        assertTrue(bytes.all { it.toInt() == 0 })
        assertTrue(StaticEmbedder.fromBytes(bytes).all { it == 0f })
    }

    /**
     * The point of the whole thing: headlines about the same subject are closer than headlines
     * about different ones, with room to spare, also when they share no important word.
     */
    @Test
    fun relatedHeadlinesAreCloserThanUnrelatedOnes() {
        val triples = listOf(
            Triple(
                "Central bank raises interest rates to fight inflation",
                "Fed lifts borrowing costs again as prices keep climbing",
                "Recipe: slow-cooked lamb with rosemary and garlic",
            ),
            Triple(
                "Arsenal beat Chelsea with a late goal in the London derby",
                "Premier League: Liverpool held to a draw by ten-man Everton",
                "A new telescope captures the birth of a distant galaxy",
            ),
            Triple(
                "Writing a memory allocator in Rust",
                "How garbage collection works in the JVM",
                "Parliament passes the new immigration bill",
            ),
            Triple(
                "How to prune apple trees in winter",
                "Planting a vegetable garden: what to sow in spring",
                "Chipmaker's shares soar after record quarterly earnings",
            ),
            Triple(
                "NASA delays its crewed mission to the Moon",
                "Rocket launch scrubbed after a fuel leak on the pad",
                "Ten tips for a better night's sleep",
            ),
        )
        for ((headline, related, unrelated) in triples) {
            val near = similarity(headline, related)
            val far = similarity(headline, unrelated)
            println("EMBEDDING similarity %.3f related, %.3f unrelated: %s".format(near, far, headline))
            assertTrue("\"$headline\": related $near, unrelated $far", near > far + 0.1f)
        }
    }

    @Test
    fun aLongPostIsReadOnlyUpToTheLimits() {
        val opening = "Stock markets slide as trade talks stall. ".repeat(100)
        assertTrue(opening.length > StaticEmbedder.MAX_CODE_POINTS)
        val cut = opening.substring(0, StaticEmbedder.MAX_CODE_POINTS)
        assertArrayEquals(embed(cut), embed(opening + "Entirely different words about gardening and football."), 0f)
    }

    /**
     * Not a rule, a record of how the English model treats other languages, so that nobody is
     * surprised: Latin-script languages keep some signal through shared and borrowed words;
     * scripts the vocabulary only knows letter by letter give a vector, but one that says
     * "this is Russian" far more than what the text is about.
     */
    @Test
    fun otherLanguagesGetAVectorButLittleMeaning() {
        val germanFootball = "Fußball: Bayern München gewinnt das Spitzenspiel gegen Dortmund"
        val germanFootball2 = "Bundesliga: Leipzig verliert das Heimspiel gegen Stuttgart"
        val germanPolitics = "Die Regierung stellt den neuen Haushalt für das kommende Jahr vor"
        val englishFootball = "Football: Bayern Munich win the top match against Dortmund"
        val russianPolitics = "Правительство объявило о новых мерах поддержки"
        val russianFootball = "Спартак обыграл Зенит в центральном матче тура"
        val russianPolitics2 = "Парламент принял закон о бюджете на следующий год"
        assertNotNull(embedder.embed("東京で新しい美術館が開館"))
        println("EMBEDDING German football vs German football %.3f, vs German politics %.3f, vs English football %.3f".format(
            similarity(germanFootball, germanFootball2), similarity(germanFootball, germanPolitics),
            similarity(germanFootball, englishFootball)))
        println("EMBEDDING Russian politics vs Russian politics %.3f, vs Russian football %.3f, vs English football %.3f".format(
            similarity(russianPolitics, russianPolitics2), similarity(russianPolitics, russianFootball),
            similarity(russianPolitics, englishFootball)))
        // Any two Russian texts look alike, whatever they are about.
        assertTrue(similarity(russianPolitics, russianFootball) > similarity(russianPolitics, englishFootball))
    }

    /**
     * A rough speed figure for this machine's JVM, printed, not asserted beyond "not absurdly
     * slow": a post is a title and about two hundred words, as the sync job will pass.
     */
    @Test
    fun speedOnThisMachine() {
        val words = EmbeddingFixtures.reference.embeddings.flatMap { it.text.split(' ', '\n') }
        val posts = List(500) { post -> List(215) { words[(post * 31 + it * 7) % words.size] }.joinToString(" ") }
        val piecesPerPost = posts.sumOf { embedder.pieceIds(it).size } / posts.size
        repeat(4) { posts.forEach(embedder::embed) } // warm-up
        val rounds = 10
        val start = System.nanoTime()
        var sink = 0f
        repeat(rounds) { for (post in posts) sink += embed(post)[0] }
        val seconds = (System.nanoTime() - start) / 1e9
        val perSecond = rounds * posts.size / seconds
        println("EMBEDDING speed: %.0f posts a second, %.1f microseconds a post (215 words, %d pieces each; one thread; sink %.2f)".format(
            perSecond, 1e6 / perSecond, piecesPerPost, sink))
        assertTrue("only $perSecond posts a second", perSecond > 200)
    }

    private companion object {
        /** Lowest acceptable cosine between [StaticEmbedder.embed] and the float32 reference. */
        const val REFERENCE_TOLERANCE = 0.9999f

        /** The same after a [StaticEmbedder.toBytes] round trip, which is what gets compared in use. */
        const val STORED_TOLERANCE = 0.9997f
    }
}

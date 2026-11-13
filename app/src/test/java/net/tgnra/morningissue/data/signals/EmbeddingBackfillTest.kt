package net.tgnra.morningissue.data.signals

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.repository.WithinFeedLearner
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.embedding.StaticEmbedder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The embedding pass and `SignalsUpkeep` against an in-memory database, with the real asset. */
@RunWith(AndroidJUnit4::class)
class EmbeddingBackfillTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var dao: ArticleSignalDao
    private var loads = 0
    private val embedder: suspend () -> StaticEmbedder = {
        loads++
        EmbeddingFixtures.embedder
    }

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.articleSignalDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun blog(url: String = "https://blog.example/feed.xml"): Long =
        db.feedDao().insert(FeedEntity(url = url, siteUrl = "https://blog.example/", title = "A blog", addedAt = T0))

    private suspend fun post(feedId: Long, guid: String, title: String, summary: String? = null, body: String? = null): Long =
        db.articleDao().insertNew(article(feedId, guid, title = title, summary = summary), blocksJson = null, plainText = body)!!

    private suspend fun storedOf(articleId: Long): ByteArray = dao.getEmbedding(articleId)!!.vector

    @Test
    fun everyPostGetsAVectorOrAMarkAndASecondPassDoesNothing() = runBlocking<Unit> {
        val feedId = blog()
        val english = post(feedId, "en", "Heatwave pushes electricity demand to a record high", "Grid operators across southern Europe asked for restraint.")
        val russian = post(feedId, "ru", "Учёные обнаружили новую экзопланету", "В зоне обитаемости своей звезды.")
        val japanese = post(feedId, "ja", "科学者たちは新しい系外惑星を発見しました")
        val emoji = post(feedId, "emoji", "🚀🚀🚀")
        val german = post(feedId, "de", "Wissenschaftler entdecken einen neuen Exoplaneten")
        val pass = EmbeddingBackfill(db, embedder)

        assertEquals(EmbeddingBackfill.Result(embedded = 2, marked = 3), pass.run())

        assertEquals(StaticEmbedder.DIMENSIONS, storedOf(english).size)
        assertEquals(StaticEmbedder.DIMENSIONS, storedOf(german).size)
        assertEquals(StoredEmbedding.NON_LATIN, StoredEmbedding.markOf(storedOf(russian)))
        assertEquals(StoredEmbedding.NON_LATIN, StoredEmbedding.markOf(storedOf(japanese)))
        assertEquals(StoredEmbedding.NO_KNOWN_WORDS, StoredEmbedding.markOf(storedOf(emoji)))
        assertEquals(StaticEmbedder.MODEL_ID, dao.getEmbedding(russian)!!.model)
        assertEquals(0, dao.countWithoutEmbedding(StaticEmbedder.MODEL_ID))

        // The ranking sees a vector for the embedded posts and none for the marked ones.
        val traits = dao.signalsFor(listOf(english, russian, japanese, emoji, german), StaticEmbedder.MODEL_ID)
            .associate { it.articleId to WithinFeedLearner.toPostTraits(it) }
        assertEquals(StaticEmbedder.DIMENSIONS, traits.getValue(english).embedding!!.size)
        assertNotNull(traits.getValue(german).embedding)
        for (id in listOf(russian, japanese, emoji)) assertNull(traits.getValue(id).embedding)

        // Nothing is left to do: the marked posts are not read again, and the embedder is not asked for.
        val before = listOf(english, russian, emoji).map { storedOf(it) }
        loads = 0
        assertEquals(EmbeddingBackfill.Result(), pass.run())
        assertEquals(0, loads)
        listOf(english, russian, emoji).forEachIndexed { index, id -> assertArrayEquals(before[index], storedOf(id)) }
    }

    @Test
    fun theVectorIsOfTheTitleTheSummaryAndTheOpeningOfTheBody() = runBlocking<Unit> {
        val feedId = blog()
        val body = "Grid operators asked households to hold back in the evening. " + "The network held. ".repeat(400)
        val id = post(feedId, "a", "Heatwave pushes demand to a record", "A standfirst about the weather.", body)
        EmbeddingBackfill(db, embedder).run()

        val words = body.trim().split(' ')
        // Six words of title, five of summary, and the rest of the two hundred from the body.
        val expected = "Heatwave pushes demand to a record\nA standfirst about the weather.\n" + words.take(189).joinToString(" ")
        assertArrayEquals(StaticEmbedder.toBytes(EmbeddingFixtures.embedder.embed(expected)!!), storedOf(id))
    }

    @Test
    fun aLargeLibraryIsDoneInBatchesNewestFirstAndOnlyWhatIsMissing() = runBlocking<Unit> {
        val feedId = blog()
        val ids = (1..23).map { post(feedId, "p$it", "Post number $it about the harbour") }
        // One post was embedded by an older model and one by this one already.
        dao.upsertEmbeddings(
            listOf(
                ArticleEmbeddingEntity(ids[3], StaticEmbedder.MODEL_ID - 1, byteArrayOf(9, 9)),
                ArticleEmbeddingEntity(ids[7], StaticEmbedder.MODEL_ID, ByteArray(StaticEmbedder.DIMENSIONS) { 5 }),
            ),
        )
        assertEquals(22, dao.countWithoutEmbedding(StaticEmbedder.MODEL_ID))
        // The queue hands out the newest posts first.
        assertEquals(ids.takeLast(5).reversed(), dao.articlesWithoutEmbedding(StaticEmbedder.MODEL_ID, Long.MAX_VALUE, 5, 100).map { it.articleId })

        assertEquals(EmbeddingBackfill.Result(embedded = 22), EmbeddingBackfill(db, embedder, batchSize = 5).run())

        assertEquals(23, dao.embeddingCount())
        assertEquals(StaticEmbedder.MODEL_ID, dao.getEmbedding(ids[3])!!.model)
        assertEquals(StaticEmbedder.DIMENSIONS, storedOf(ids[3]).size)
        // The post this model had already embedded was left alone.
        assertTrue(storedOf(ids[7]).all { it == 5.toByte() })
    }

    @Test
    fun aFeedRemovedWhileABatchIsBeingEmbeddedIsPassedOver() = runBlocking<Unit> {
        val kept = blog()
        val removed = blog("https://gone.example/feed.xml")
        val keptPost = post(kept, "a", "A post that stays")
        post(removed, "b", "A post whose feed is removed")
        // The feed goes after the batch has been read and before it is written.
        val pass = EmbeddingBackfill(db, embedder = {
            db.feedDao().delete(removed)
            EmbeddingFixtures.embedder
        })

        assertEquals(EmbeddingBackfill.Result(embedded = 1), pass.run())
        assertEquals(1, dao.embeddingCount())
        assertNotNull(dao.getEmbedding(keptPost))
    }

    @Test
    fun cancellingStopsBetweenBatchesAndTheNextPassFinishes() = runBlocking<Unit> {
        val feedId = blog()
        repeat(12) { post(feedId, "p$it", "Post number $it about the harbour") }
        val firstBatchRead = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val pass = EmbeddingBackfill(db, batchSize = 4, embedder = {
            firstBatchRead.complete(Unit)
            goOn.await()
            EmbeddingFixtures.embedder
        })
        val job = launch { pass.run() }
        firstBatchRead.await()
        job.cancelAndJoin()
        assertEquals(0, dao.embeddingCount())

        assertEquals(12, EmbeddingBackfill(db, embedder, batchSize = 4).run().total)
        assertEquals(0, dao.countWithoutEmbedding(StaticEmbedder.MODEL_ID))
    }

    @Test
    fun upkeepRunsBothPassesAndOnlyOneAtATime() = runBlocking<Unit> {
        val feedId = blog()
        repeat(6) { post(feedId, "p$it", "Post number $it about the harbour") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var passes = 0
        val upkeep = SignalsUpkeep(
            TraitsBackfill(db),
            EmbeddingBackfill(db, embedder = {
                passes++
                entered.complete(Unit)
                release.await()
                EmbeddingFixtures.embedder
            }),
        )

        val first = async(start = CoroutineStart.UNDISPATCHED) { upkeep.run() }
        entered.await()
        // A second call while the first is at work neither waits nor starts another pass ...
        assertEquals(SignalsUpkeep.Result(), upkeep.run())
        // ... but posts stored meanwhile are picked up by the running one before it returns.
        val late = post(feedId, "late", "A post stored while the pass was running")
        release.complete(Unit)

        assertEquals(SignalsUpkeep.Result(traits = 7, embedded = 7), first.await())
        assertEquals(2, passes)
        assertNotNull(dao.getTraits(late))
        assertNotNull(dao.getEmbedding(late))
        assertEquals(SignalsUpkeep.Result(), upkeep.run())
    }

    @Test
    fun aPostGetsAVectorOrAMarkSayingWhyNot() {
        val embedder = EmbeddingFixtures.embedder
        val english = EmbeddingBackfill.stored(embedder, "Heatwave pushes electricity demand to a record high", "Across southern Europe.", null)
        assertEquals(StaticEmbedder.DIMENSIONS, english.size)
        assertNull(StoredEmbedding.markOf(english))
        assertNotNull(StoredEmbedding.vectorOf(english))

        // The model would give this a vector (its vocabulary has Cyrillic letters); it must not be kept.
        val russian = "Учёные обнаружили новую экзопланету"
        assertNotNull(embedder.embed(russian))
        val marked = EmbeddingBackfill.stored(embedder, russian, "В зоне обитаемости.", null)
        assertEquals(StoredEmbedding.NON_LATIN, StoredEmbedding.markOf(marked))
        assertNull(StoredEmbedding.vectorOf(marked))

        val nothing = EmbeddingBackfill.stored(embedder, "🚀🚀🚀", null, null)
        assertEquals(StoredEmbedding.NO_KNOWN_WORDS, StoredEmbedding.markOf(nothing))
        assertNull(StoredEmbedding.vectorOf(nothing))
        assertNull(StoredEmbedding.vectorOf(null))

        // German passes the script test and is embedded, for what its vector is worth.
        assertNull(StoredEmbedding.markOf(EmbeddingBackfill.stored(embedder, "Wissenschaftler entdecken einen neuen Exoplaneten", null, null)))
    }
}

package net.tgnra.morningissue.edition

import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.ArticleCategoryEntity
import net.tgnra.morningissue.data.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.ArticleTraitsEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.data.repository.WithinFeedLearner
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What within-feed ranking adds to an edition build, measured here and printed for the record:
 * the reads of signals and vectors that `WithinFeedLearner` makes, against a seeded in-memory
 * database the size of a well-used library ([LABELLED] posts with evidence in the learning
 * window, [CANDIDATES] new posts, every one with traits, two categories and a vector).
 *
 * The numbers are this machine's, on Robolectric's SQLite. A phone is several times slower;
 * the on-open build's budget there is 300 ms, measured by `:macrobenchmark`.
 */
@RunWith(AndroidJUnit4::class)
class WithinFeedBuildCostTest {
    private val now: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val clock = MutableClock(now)
    private lateinit var db: MorningIssueDatabase
    private val candidateIds = ArrayList<Long>()

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() = db.withWriteTransaction {
        val random = Random(7)
        val signalDao = db.articleSignalDao()
        val feeds = (0 until FEEDS).map { index ->
            db.feedDao().insert(
                FeedEntity(
                    url = "https://example.org/$index/feed.xml",
                    title = "Feed $index",
                    tier = if (index % 4 == 0) CadenceTier.WEEKLY else CadenceTier.DAILY,
                    medianIntervalHrs = if (index % 4 == 0) 168.0 else 12.0,
                    addedAt = now.minus(Duration.ofDays(200)),
                ),
            )
        }
        var guid = 0
        suspend fun post(seenAt: Instant, liked: Boolean): Long {
            val index = guid++
            val id = db.articleDao().insertNew(
                ArticleEntity(
                    feedId = feeds[index % FEEDS],
                    guid = "post-$index",
                    url = "https://example.org/posts/$index",
                    title = "Headline number $index about something or other",
                    summary = "A standfirst of ordinary length, as a feed would give.",
                    publishedAt = seenAt.minus(Duration.ofMinutes(random.nextInt(600).toLong())),
                    firstSeenAt = seenAt,
                    wordCount = 50 + random.nextInt(2500),
                ),
            )!!
            signalDao.insertCategories(
                listOf("topic ${random.nextInt(12)}", "kind ${random.nextInt(5)}").map { ArticleCategoryEntity(id, it, it) },
            )
            signalDao.upsertTraits(
                ArticleTraitsEntity(id, ArticleTraitRules.VERSION, random.nextInt(9), random.nextInt(4), random.nextInt(5) == 0, EnclosureKind.NONE, random.nextInt(6) == 0, null),
            )
            // Posts the reader took to lean one way, so that there is a text taste to learn
            // and the candidates' vectors are read: the most a build can be asked to do.
            val vector = ByteArray(StaticEmbedder.DIMENSIONS) { d -> (random.nextInt(160) - 80 + if (liked && d < 64) 40 else 0).toByte() }
            signalDao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(id, StaticEmbedder.MODEL_ID, vector)))
            return id
        }

        // Sixty mornings of fifty printed stories each; the reader opened a third of them.
        for (day in EDITIONS downTo 1) {
            val releaseAt = now.minus(Duration.ofDays(day.toLong())).minus(Duration.ofMinutes(30))
            val items = (0 until LABELLED / EDITIONS).map { slot ->
                val opened = random.nextInt(3) == 0
                val id = post(releaseAt.minus(Duration.ofHours(2)), liked = opened)
                if (opened) {
                    db.engagementDao().markOpened(id, releaseAt.plus(Duration.ofHours(1)))
                    if (random.nextBoolean()) db.engagementDao().markRead(id, releaseAt.plus(Duration.ofHours(1)))
                }
                EditionItemEntity(0, id, page = 1 + slot / 10, slot = slot % 10, kind = if (slot == 0) ItemKind.LEAD else ItemKind.TOP, score = 1.0)
            }
            val edition = EditionEntity(
                date = releaseAt.atZone(ZoneOffset.UTC).toLocalDate(),
                kind = EditionKind.MORNING,
                cutoffAt = releaseAt.minus(Duration.ofMinutes(90)),
                releaseAt = releaseAt,
                builtAt = releaseAt.minus(Duration.ofMinutes(90)),
                length = EditionLength.STANDARD,
                number = EDITIONS - day + 1,
                openedAt = releaseAt.plus(Duration.ofHours(1)),
            )
            db.editionDao().insertEditionWithItems(edition, items)
        }
        repeat(CANDIDATES) { candidateIds += post(now.minus(Duration.ofMinutes(5L + random.nextInt(20 * 60))), liked = false) }
    }

    private suspend fun <T> timed(runs: Int, block: suspend () -> T): Pair<DoubleArray, T> {
        val millis = DoubleArray(runs)
        var last: T? = null
        repeat(runs) { run ->
            val start = System.nanoTime()
            last = block()
            millis[run] = (System.nanoTime() - start) / 1e6
        }
        @Suppress("UNCHECKED_CAST")
        return millis to last as T
    }

    private fun DoubleArray.report(): String {
        val sorted = sorted()
        return "first %.1f ms, then median %.1f (fastest %.1f, slowest %.1f)".format(this[0], sorted[sorted.size / 2], sorted.first(), sorted.last())
    }

    @Test
    fun whatLoadingSignalsAndVectorsAddsToABuild() = runBlocking<Unit> {
        seed()
        val signalDao = db.articleSignalDao()
        val affinity = AffinityLearner(db)
        val within = WithinFeedLearner(db)
        val since = affinity.windowStart(now)

        // The parts, each read the way the publisher reads it. The first call is what a cold
        // process pays; the rest say what the work itself costs.
        val (evidenceMillis, evidence) = timed(RUNS) { affinity.evidence(now, emptySet()) }
        assertEquals(LABELLED, evidence.size)
        val (labelledBare, _) = timed(RUNS) { signalDao.signalsWithEvidenceSince(since, null) }
        val (labelledVectors, labelled) = timed(RUNS) { signalDao.signalsWithEvidenceSince(since, StaticEmbedder.MODEL_ID) }
        assertEquals(LABELLED, labelled.size)
        assertTrue(labelled.all { it.embedding?.size == StaticEmbedder.DIMENSIONS && it.categories.size == 2 && it.traits != null })
        val (learnMillis, model) = timed(RUNS) { within.learn(evidence, since, at = now) }
        assertNotNull(model.text)
        assertTrue("the seeded taste was found, so candidates' vectors are read", model.usesText)
        val (candidatesBare, _) = timed(RUNS) { signalDao.signalsFor(candidateIds, null) }
        val (candidatesVectors, _) = timed(RUNS) { signalDao.signalsFor(candidateIds, StaticEmbedder.MODEL_ID) }
        val (traitsMillis, traits) = timed(RUNS) { within.traitsOf(candidateIds, model) }
        assertEquals(CANDIDATES, traits.size)
        assertTrue(traits.values.all { it.embedding != null })

        // The whole on-open build, once, as the newsstand asks for it.
        val publisher = RoomEditionPublisher(db, FakeSettings(), clock, zone = { ZoneOffset.UTC })
        val (buildMillis, editionId) = timed(1) { publisher.ensureCurrentEdition() }
        assertNotNull(editionId)
        assertEquals(EDITIONS + 1, db.editionDao().count())

        println(
            """
            |Within-feed build cost on this machine ($LABELLED labelled posts, $CANDIDATES candidates, $FEEDS feeds, ${StaticEmbedder.DIMENSIONS}-byte vectors):
            |  evidence (paid before too, by affinity learning):        ${evidenceMillis.report()}
            |  read signals of labelled posts, without vectors:         ${labelledBare.report()}
            |  read signals of labelled posts, with vectors:            ${labelledVectors.report()}
            |  WithinFeedLearner.learn (that read, decoding, the engine): ${learnMillis.report()}
            |  read signals of candidates, without vectors:             ${candidatesBare.report()}
            |  read signals of candidates, with vectors:                ${candidatesVectors.report()}
            |  WithinFeedLearner.traitsOf (that read and decoding):     ${traitsMillis.report()}
            |  the whole on-open build, run once after the above:       %.1f ms
            """.trimMargin().format(buildMillis[0]),
        )
        // Generous: other builds share this machine. The point of the test is the printed record.
        val sortedLearn = learnMillis.sorted()
        val sortedTraits = traitsMillis.sorted()
        assertTrue("median added ${sortedLearn[RUNS / 2] + sortedTraits[RUNS / 2]} ms", sortedLearn[RUNS / 2] + sortedTraits[RUNS / 2] < 300.0)
    }

    private companion object {
        const val FEEDS = 40
        const val EDITIONS = 60
        const val LABELLED = 3000
        const val CANDIDATES = 1000
        const val RUNS = 9
    }
}

package net.tgnra.morningissue.server.editions

import androidx.room3.withWriteTransaction
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ArticleTraitsEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What within-feed ranking adds to an edition build, measured here and printed for the record
 * (the app's `WithinFeedBuildCostTest`): the reads of signals and vectors that
 * `WithinFeedLearner` makes for one user, against a seeded in-memory database the size of a
 * well-used library ([LABELLED] posts with evidence in the learning window, [CANDIDATES] new
 * posts, every one with traits, two categories and a vector).
 *
 * A second user subscribed to the same feeds, with editions and engagement of their own, is
 * seeded too: the user's reads must not pick up the other's rows, and their cost is the
 * user's alone.
 */
class WithinFeedBuildCostTest {
    private val now: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val world = TestWorld(now)
    private val db = world.db
    private val candidateIds = ArrayList<Long>()
    private var user = 0L
    private var other = 0L

    @After
    fun tearDown() = world.close()

    private suspend fun seed() {
        user = world.addUser()
        other = world.addUser()
        val random = Random(7)
        val feeds = (0 until FEEDS).map { index ->
            world.addGlobalFeed(
                "Feed $index",
                tier = if (index % 4 == 0) CadenceTier.WEEKLY else CadenceTier.DAILY,
                medianHours = if (index % 4 == 0) 168.0 else 12.0,
                url = "https://example.org/$index/feed.xml",
            ).also { feed ->
                world.subscribe(user, feed, addedAt = now.minus(Duration.ofDays(200)))
                world.subscribe(other, feed, addedAt = now.minus(Duration.ofDays(200)))
            }
        }
        db.withWriteTransaction {
            val importDao = db.importDao()
            val seq = db.userStateDao().nextChangeSeq(user)
            val otherSeq = db.userStateDao().nextChangeSeq(other)
            var guid = 0
            suspend fun post(seenAt: Instant, liked: Boolean): Long {
                val index = guid++
                val id = importDao.insertNew(
                    ArticleEntity(
                        feedId = feeds[index % FEEDS],
                        guid = "post-$index",
                        url = "https://example.org/posts/$index",
                        title = "Headline number $index about something or other",
                        summary = "A standfirst of ordinary length, as a feed would give.",
                        publishedAt = seenAt.minus(Duration.ofMinutes(random.nextInt(600).toLong())),
                        firstSeenAt = seenAt,
                        feedWordCount = 50 + random.nextInt(2500),
                    ),
                )!!
                importDao.insertCategories(
                    listOf("topic ${random.nextInt(12)}", "kind ${random.nextInt(5)}").map { ArticleCategoryEntity(id, it, it) },
                )
                importDao.upsertTraits(
                    ArticleTraitsEntity(id, ArticleTraitRules.VERSION, random.nextInt(9), random.nextInt(4), random.nextInt(5) == 0, EnclosureKind.NONE, random.nextInt(6) == 0, null),
                )
                // Posts the reader took to lean one way, so that there is a text taste to learn
                // and the candidates' vectors are read: the most a build can be asked to do.
                val vector = ByteArray(StaticEmbedder.DIMENSIONS) { d -> (random.nextInt(160) - 80 + if (liked && d < 64) 40 else 0).toByte() }
                world.storeEmbedding(id, vector)
                return id
            }

            // Sixty mornings of fifty printed stories each; the reader opened a third of them.
            for (day in EDITIONS downTo 1) {
                val releaseAt = now.minus(Duration.ofDays(day.toLong())).minus(Duration.ofMinutes(30))
                val items = (0 until LABELLED / EDITIONS).map { slot ->
                    val opened = random.nextInt(3) == 0
                    val id = post(releaseAt.minus(Duration.ofHours(2)), liked = opened)
                    if (opened) {
                        db.engagementDao().mergeOpened(user, id, releaseAt.plus(Duration.ofHours(1)), seq)
                        if (random.nextBoolean()) db.engagementDao().mergeRead(user, id, releaseAt.plus(Duration.ofHours(1)), seq)
                    }
                    // The other reader opened every one: none of it is the user's evidence.
                    db.engagementDao().mergeOpened(other, id, releaseAt.plus(Duration.ofHours(2)), otherSeq)
                    EditionItemEntity(0, id, page = 1 + slot / 10, slot = slot % 10, kind = if (slot == 0) ItemKind.LEAD else ItemKind.TOP, score = 1.0)
                }
                for ((owner, ownItems) in listOf(user to items, other to items.take(10))) {
                    val edition = EditionEntity(
                        userId = owner,
                        date = releaseAt.atZone(ZoneOffset.UTC).toLocalDate(),
                        kind = EditionKind.MORNING,
                        cutoffAt = releaseAt.minus(Duration.ofMinutes(90)),
                        releaseAt = releaseAt,
                        builtAt = releaseAt.minus(Duration.ofMinutes(90)),
                        length = EditionLength.STANDARD,
                        number = EDITIONS - day + 1,
                        openedAt = releaseAt.plus(Duration.ofHours(1)),
                    )
                    db.editionDao().insertEditionWithItems(edition, ownItems)
                }
            }
            repeat(CANDIDATES) { candidateIds += post(now.minus(Duration.ofMinutes(5L + random.nextInt(20 * 60))), liked = false) }
        }
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
        val learningDao = db.learningDao()
        val affinity = AffinityLearner(db)
        val within = WithinFeedLearner(db)
        val since = affinity.windowStart(now)

        // The parts, each read the way the publisher reads it. The first call is what a cold
        // process pays; the rest say what the work itself costs.
        val (evidenceMillis, evidence) = timed(RUNS) { affinity.evidence(user, now, emptySet()) }
        assertEquals(LABELLED, evidence.size)
        val (labelledBare, _) = timed(RUNS) { learningDao.signalRowsWithEvidenceSince(user, since, null) }
        val (labelledVectors, labelled) = timed(RUNS) { learningDao.signalRowsWithEvidenceSince(user, since, StaticEmbedder.MODEL_ID) }
        assertEquals(LABELLED, labelled.size)
        assertTrue(labelled.all { it.vector?.size == StaticEmbedder.DIMENSIONS && it.traits != null })
        val categories = learningDao.categoriesWithEvidenceSince(user, since).groupBy { it.articleId }
        assertEquals(LABELLED, categories.size)
        assertTrue(categories.values.all { it.size == 2 })
        val (learnMillis, model) = timed(RUNS) { within.learn(user, evidence, since, at = now) }
        assertNotNull(model.text)
        assertTrue("the seeded taste was found, so candidates' vectors are read", model.usesText)
        val (candidatesBare, _) = timed(RUNS) { within.signalsFor(candidateIds, null) }
        val (candidatesVectors, _) = timed(RUNS) { within.signalsFor(candidateIds, StaticEmbedder.MODEL_ID) }
        val (traitsMillis, traits) = timed(RUNS) { within.traitsOf(candidateIds, model) }
        assertEquals(CANDIDATES, traits.size)
        assertTrue(traits.values.all { it.embedding != null })

        // The whole build of the current edition, once, as Print now asks for it.
        val publisher = EditionPublisher(db, world.settings, world.clock)
        val (buildMillis, editionId) = timed(1) { publisher.ensureCurrentEdition(user) }
        assertNotNull(editionId)
        assertEquals(EDITIONS + 1, db.editionDao().count(user))
        assertEquals(EDITIONS, db.editionDao().count(other))

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
            |  the whole build, run once after the above:               %.1f ms
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

package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import net.tgnra.morningissue.model.EnclosureKind

/**
 * What a learning pass costs at the size it has to handle: 3,000 labelled posts with
 * 256-dimension vectors, the most the evidence window is expected to hold. The figure is
 * printed; it is this machine's JVM, not a phone, so the assertion only guards against the
 * pass becoming something other than linear.
 */
class WithinFeedCostTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")

    @Test
    fun learningFromThreeThousandPostsFitsTheBuildBudget() {
        val random = Random(11)
        val posts = List(POSTS) { index ->
            val feedId = 1L + index % FEEDS
            val ranAt = now.minus(Duration.ofHours(random.nextInt(180 * 24).toLong()))
            val read = random.nextInt(3) == 0
            val vector = FloatArray(DIMENSION) { random.nextGaussian().toFloat() }
            var squares = 0.0
            for (x in vector) squares += x * x
            val length = sqrt(squares).toFloat()
            for (j in vector.indices) vector[j] /= length
            LabelledPost(
                articleId = index.toLong(),
                evidence = ArticleEvidence(
                    feedId,
                    ranAt = if (random.nextInt(4) == 0) listOf(ranAt, ranAt.plus(Duration.ofDays(1))) else listOf(ranAt),
                    openedAt = ranAt.takeIf { read },
                    readAt = ranAt.takeIf { read && random.nextBoolean() },
                    likedAt = ranAt.takeIf { read && random.nextInt(8) == 0 },
                ),
                traits = PostTraits(
                    feedId = feedId,
                    categories = setOf("topic-${random.nextInt(12)}", "topic-${random.nextInt(12)}"),
                    wordCount = random.nextInt(3000),
                    enclosure = if (random.nextInt(10) == 0) EnclosureKind.AUDIO else EnclosureKind.NONE,
                    isLinkPost = random.nextInt(6) == 0,
                    hasCode = random.nextInt(5) == 0,
                    titlePrefix = if (random.nextInt(10) == 0) "links" else null,
                    embedding = vector,
                ),
            )
        }
        val candidates = posts.take(CANDIDATES).map { it.traits }
        val learner = WithinFeed()

        var sink = 0.0
        // The very first pass, before the JIT has seen the code: what an edition build pays.
        val coldStart = System.nanoTime()
        sink += learner.learn(posts, now).weight(candidates[0]).multiplier
        val coldMillis = (System.nanoTime() - coldStart) / 1e6
        repeat(WARM_UP) { sink += learner.learn(posts, now).weight(candidates[0]).multiplier }
        val learnMillis = DoubleArray(RUNS)
        val weighMillis = DoubleArray(RUNS)
        repeat(RUNS) { run ->
            val start = System.nanoTime()
            val model = learner.learn(posts, now)
            val learned = System.nanoTime()
            for (candidate in candidates) sink += model.weight(candidate).multiplier
            val weighed = System.nanoTime()
            learnMillis[run] = (learned - start) / 1e6
            weighMillis[run] = (weighed - learned) / 1e6
        }
        learnMillis.sort()
        weighMillis.sort()
        println(
            "WithinFeed cost: learning from $POSTS posts of $DIMENSION dimensions took a median of " +
                "%.2f ms (fastest %.2f, slowest %.2f, $RUNS runs after $WARM_UP to warm up); ".format(learnMillis[RUNS / 2], learnMillis[0], learnMillis[RUNS - 1]) +
                "the first, cold pass took %.2f ms; ".format(coldMillis) +
                "weighing $CANDIDATES candidates took a median of %.2f ms".format(weighMillis[RUNS / 2]),
        )
        assertTrue(sink > 0.0)
        // Generous: other builds share this machine. A phone is several times slower than this JVM.
        assertTrue("median ${learnMillis[RUNS / 2]} ms", learnMillis[RUNS / 2] < 100.0)
    }

    private companion object {
        const val POSTS = 3000
        const val DIMENSION = 256
        const val FEEDS = 40
        const val CANDIDATES = 500
        const val WARM_UP = 10
        const val RUNS = 21
    }
}

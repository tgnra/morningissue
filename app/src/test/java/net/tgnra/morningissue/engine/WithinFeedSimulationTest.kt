package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small synthetic world: 8 daily feeds with 60 days of posts each, every post with one or
 * two of its feed's six categories, a length, sometimes code, a link or a "links" prefix, and
 * a 64-dimension vector that leans towards its first category's subject. A simulated reader
 * goes through it, the model is learned, and 40 fresh posts per feed are weighed. Run over
 * [SEEDS] worlds. It shows the rules behave as meant on data shaped like the real thing; it
 * says nothing about real readers.
 */
class WithinFeedSimulationTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val learner = WithinFeed()

    private class World(val history: List<LabelledPost>, val fresh: List<PostTraits>)

    private fun gaussianVector(random: Random) = FloatArray(DIMENSION) { random.nextGaussian().toFloat() }

    private fun normalised(vector: FloatArray): FloatArray {
        var squares = 0.0
        for (x in vector) squares += x * x
        val length = sqrt(squares).toFloat()
        return FloatArray(vector.size) { vector[it] / length }
    }

    /**
     * @param readChance the chance the reader reads a post, given its feed's own rate and its
     *   traits; a read post is Liked one time in ten
     * @param withKeys false leaves every post without categories or any other key, so that
     *   only the vectors can tell posts apart
     */
    private fun world(seed: Long, withKeys: Boolean = true, readChance: (feedRate: Double, PostTraits) -> Double): World {
        val random = Random(seed)
        val history = ArrayList<LabelledPost>()
        val fresh = ArrayList<PostTraits>()
        var articleId = 1L
        // Subject 0 is shared by every feed ("space"); the others are each feed's own.
        val shared = gaussianVector(random)
        for (feedId in 1..FEEDS.toLong()) {
            val feedRate = 0.15 + 0.7 * random.nextDouble()
            val subjects = List(CATEGORIES) { if (it == 0) shared else gaussianVector(random) }
            fun traits(): PostTraits {
                val first = random.nextInt(CATEGORIES)
                val categories = HashSet<String>()
                categories += categoryName(feedId, first)
                if (random.nextInt(3) == 0) categories += categoryName(feedId, random.nextInt(CATEGORIES))
                val noise = gaussianVector(random)
                val vector = normalised(FloatArray(DIMENSION) { subjects[first][it] + noise[it] })
                return PostTraits(
                    feedId = feedId,
                    categories = if (withKeys) categories else emptySet(),
                    wordCount = if (withKeys) intArrayOf(80, 300, 900, 2500)[random.nextInt(4)] else PostTraits.UNKNOWN_WORD_COUNT,
                    isLinkPost = withKeys && random.nextInt(100) < 15,
                    hasCode = withKeys && random.nextInt(100) < 20,
                    titlePrefix = if (withKeys && random.nextInt(100) < 10) "links" else null,
                    embedding = vector,
                    // Kept for the reader, who may go by subject even when no key says it.
                    imageCount = first,
                )
            }
            for (day in 1..DAYS) {
                val post = traits()
                val ranAt = now.minus(Duration.ofDays(day.toLong()))
                val read = random.nextDouble() < readChance(feedRate, post)
                val liked = read && random.nextInt(10) == 0
                history += LabelledPost(
                    articleId++,
                    ArticleEvidence(
                        feedId,
                        ranAt = listOf(ranAt),
                        openedAt = ranAt.takeIf { read },
                        readAt = ranAt.takeIf { read },
                        likedAt = ranAt.takeIf { liked },
                    ),
                    post,
                )
            }
            repeat(FRESH_PER_FEED) { fresh += traits() }
        }
        return World(history, fresh)
    }

    private fun categoryName(feedId: Long, index: Int) = if (index == 0) "space" else "topic-$feedId-$index"

    /** The post's subject, which [world] leaves in `imageCount`. */
    private val PostTraits.subject: Int get() = imageCount

    @Test
    fun aReaderEngagingAtRandomLeavesNearlyEveryPostAlone() {
        var total = 0
        var near = 0
        var lowest = 1.0
        var highest = 1.0
        var reasons = 0
        val scorer = RankingConfig.Default
        for (seed in 1..SEEDS) {
            val world = world(seed.toLong()) { feedRate, _ -> feedRate }
            val model = learner.learn(world.history, now)
            for (post in world.fresh) {
                val w = model.weight(post).multiplier
                total++
                if (w in 0.9..1.1) near++
                if (w >= scorer.withinFeedReasonMinWeight) reasons++
                lowest = minOf(lowest, w)
                highest = maxOf(highest, w)
            }
        }
        println("WithinFeed simulation, random reader: $near of $total within 0.9..1.1, range $lowest..$highest, $reasons at reason strength")
        // The tolerance: at least 97% of posts within 10% of neutral, none moved by more
        // than 20%, and under 1 in 200 strong enough to be given a reason line.
        assertTrue("$near of $total within 10%", near >= total * 0.97)
        assertTrue("lowest $lowest", lowest >= 0.8)
        assertTrue("highest $highest", highest <= 1.2)
        assertTrue("$reasons reason lines from noise", reasons <= total / 200)
    }

    @Test
    fun aPlantedCategoryPreferenceIsRecovered() {
        var pairs = 0L
        var ordered = 0L
        var spaceSum = 0.0
        var spaceCount = 0
        var otherSum = 0.0
        var otherCount = 0
        for (seed in 1..SEEDS) {
            // In every feed the reader reads nine in ten posts about space and one in seven of the rest.
            val world = world(seed.toLong()) { _, post -> if ("space" in post.categories) 0.9 else 0.15 }
            val model = learner.learn(world.history, now)
            for ((_, posts) in world.fresh.groupBy { it.feedId }) {
                val (space, other) = posts.partition { "space" in it.categories }
                val spaceW = space.map { model.weight(it).multiplier }
                val otherW = other.map { model.weight(it).multiplier }
                spaceSum += spaceW.sum()
                spaceCount += spaceW.size
                otherSum += otherW.sum()
                otherCount += otherW.size
                for (s in spaceW) for (o in otherW) {
                    pairs++
                    if (s > o) ordered++
                }
            }
        }
        val spaceMean = spaceSum / spaceCount
        val otherMean = otherSum / otherCount
        println("WithinFeed simulation, planted category: space $spaceMean, others $otherMean, $ordered of $pairs pairs ordered")
        assertTrue("space posts average $spaceMean", spaceMean >= 1.2)
        assertTrue("other posts average $otherMean", otherMean < 1.0)
        // A space post outranks a same-feed post about something else at least 19 times in 20.
        assertTrue("$ordered of $pairs pairs ordered", ordered >= pairs * 0.95)
    }

    @Test
    fun aPlantedSubjectIsRecoveredFromTheVectorsAlone() {
        var pairs = 0L
        var ordered = 0L
        var textSignals = 0
        var lifted = 0
        for (seed in 1..SEEDS) {
            // No categories or other keys at all: only the vectors know which posts are about space.
            val world = world(seed.toLong(), withKeys = false) { _, post -> if (post.subject == 0) 0.9 else 0.15 }
            val model = learner.learn(world.history, now)
            for ((_, posts) in world.fresh.groupBy { it.feedId }) {
                val (space, other) = posts.partition { it.subject == 0 }
                val spaceW = space.map { model.weight(it) }
                val otherW = other.map { model.weight(it).multiplier }
                lifted += spaceW.count { it.multiplier > 1.0 }
                textSignals += spaceW.count { it.multiplier > 1.0 && it.signal?.kind == WithinFeedSignalKind.TEXT }
                for (s in spaceW) for (o in otherW) {
                    pairs++
                    if (s.multiplier > o) ordered++
                }
            }
        }
        println("WithinFeed simulation, planted subject (vectors only): $ordered of $pairs pairs ordered")
        assertTrue("$ordered of $pairs pairs ordered", ordered >= pairs * 0.9)
        assertTrue(lifted > 0 && textSignals == lifted)
    }

    private companion object {
        const val SEEDS = 20
        const val FEEDS = 8
        const val DAYS = 60
        const val CATEGORIES = 6
        const val FRESH_PER_FEED = 40
        const val DIMENSION = 64
    }
}

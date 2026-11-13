package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import net.tgnra.morningissue.model.CadenceTier

/**
 * Every tunable constant of feed discovery ([Discovery]). The numbers are those of the design
 * in `docs/how-it-works.md`, "Discovery".
 */
data class DiscoveryConfig(
    /** A subscribed feed's centroid is the mean of its newest this many post vectors... */
    val feedVectors: Int = 50,
    /** ...and it needs at least this many to have one. */
    val minFeedVectors: Int = 3,
    /** Effective affinity at or below this gives a subscribed feed no weight; 1.0 gives it weight 1. */
    val weightFloorAffinity: Double = 0.35,
    /** Liked and read posts from the last this many days count as points of taste. */
    val postWindowDays: Long = 180,
    val maxPostPoints: Int = 200,
    val likedPostWeight: Double = 1.0,
    val readPostWeight: Double = 0.5,
    /** A passed card pushes similar feeds down with a weight halving every this many days. */
    val passHalfLifeDays: Double = 90.0,
    /** A feed the reader unsubscribed from pushes similar feeds down for good. */
    val droppedWeight: Double = 1.0,
    /** Catalogue feeds sampled to put each similarity on a common scale (z-scores). */
    val zSampleSize: Int = 1000,
    val feedTopK: Int = 3,
    val postTopK: Int = 5,
    val feedShare: Double = 0.6,
    val postShare: Double = 0.4,
    val negativeWeight: Double = 0.5,
    val categoryWeight: Double = 0.15,
    // Cadence fit: the paper rewards scarcity, so slow feeds are the sweet spot.
    val rareFit: Double = 1.0,
    val weeklyFit: Double = 1.0,
    val dailyFit: Double = 0.85,
    val firehoseFit: Double = 0.5,
    /** Firehose fit for a reader who already loves a firehose feed. */
    val lovedFirehoseFit: Double = 0.8,
    /** Quality factor for a feed that mostly sends excerpts (full posts are 1.0). */
    val excerptQuality: Double = 0.8,
    /** Quality factor once the newest post is older than [staleDays]. */
    val staleQuality: Double = 0.7,
    val staleDays: Long = 45,
    /** A feed whose posts' full-text share is at least this counts as sending full posts. */
    val fullTextShareMin: Double = 0.5,
    /** Raw cosine above which two feeds are the same feed (a mirror, a category feed). */
    val nearDuplicateCosine: Double = 0.97,
    /** The best this many by score are considered for the deck. */
    val mmrPool: Int = 200,
    /** MMR's trade-off: 1 is pure score, 0 pure variety. */
    val mmrLambda: Double = 0.7,
    val deckSize: Int = 20,
    /** Every this many cards one comes from a cluster the reader's sources do not cover. */
    val explorationEvery: Int = 5,
    /** Weight of a topic picked on the cold-start screen, as a subscribed feed's. */
    val pickedTopicWeight: Double = 1.0,
) {
    companion object {
        val Default = DiscoveryConfig()
    }
}

/** A topic cluster of the catalogue: its label and centre (unit length, not centred). */
class CatalogueCluster(val id: Int, val label: String, val centroid: FloatArray)

/** A recent headline of a catalogue feed, as the catalogue was built. */
data class CatalogueHeadline(
    val title: String,
    val url: String?,
    val publishedAt: Instant?,
    val wordCount: Int?,
)

/** One feed of the bundled catalogue. [centroid] is unit length and not centred; null when the catalogue has none. */
class CatalogueFeed(
    val id: Long,
    val url: String,
    /** `FeedUrls.normalize(url)`. */
    val urlKey: String,
    val siteUrl: String?,
    /** The registrable host ("example.co.uk", "someone.bearblog.dev"). */
    val host: String?,
    val title: String,
    val description: String?,
    val iconUrl: String?,
    val language: String?,
    val tier: CadenceTier?,
    val medianIntervalHours: Double?,
    val postsPerWeek: Double?,
    /** Share of posts that carry their full text, 0..1; null when unknown. */
    val fullTextShare: Double?,
    val lastPostAt: Instant?,
    val clusterId: Int?,
    val categories: List<String>,
    val source: String?,
    /** The catalogue's own quality estimate, 0..1. */
    val quality: Double,
    val centroid: FloatArray?,
) {
    override fun toString(): String = "CatalogueFeed($id, $title)"
}

/** A subscribed feed as discovery sees it: its centroid and how much the reader cares for it (0..1). */
class ProfileFeed(val title: String, val vector: FloatArray, val weight: Double, val tier: CadenceTier = CadenceTier.WEEKLY)

/** A post the reader Liked or read: a point of taste. */
class ProfilePost(val vector: FloatArray, val weight: Double, val liked: Boolean)

/** A passed or dropped feed: pushes similar feeds down. */
class ProfileNegative(val vector: FloatArray, val weight: Double)

/** Decisions on suggestions from one cluster: the exploration bandit's evidence. */
data class ClusterTally(val subscribes: Int = 0, val passes: Int = 0)

/**
 * Everything [Discovery] knows of the reader. Built by `DiscoveryRepository`; all vectors are
 * unit length and not centred.
 *
 * @param excludedKeys URL keys never to suggest: subscriptions and every verdict
 * @param excludedHosts registrable hosts of subscribed feeds and their sites
 * @param readerTerms the reader's tag names and the categories of posts they read, lower-cased
 * @param pickedClusters topics chosen on the cold-start screen
 */
class DiscoveryProfile(
    val feeds: List<ProfileFeed> = emptyList(),
    val posts: List<ProfilePost> = emptyList(),
    val negatives: List<ProfileNegative> = emptyList(),
    val readerTerms: Set<String> = emptySet(),
    val pickedClusters: Set<Int> = emptySet(),
    val excludedKeys: Set<String> = emptySet(),
    val excludedHosts: Set<String> = emptySet(),
    val clusterTallies: Map<Int, ClusterTally> = emptyMap(),
) {
    /** False on a cold start: nothing subscribed with posts, nothing Liked or read, no topic picked. */
    val hasTaste: Boolean get() = feeds.any { it.weight > 0.0 } || posts.isNotEmpty() || pickedClusters.isNotEmpty()
}

/** One suggestion: the feed, its score, the reason line, and whether it is an exploration card. */
class DeckCard(
    val feed: CatalogueFeed,
    val score: Double,
    val reason: String,
    val exploration: Boolean = false,
) {
    override fun toString(): String = "DeckCard(${feed.title}, ${"%.3f".format(score)}, $reason)"
}

/** A candidate's score with the parts the reason line needs. Exposed for tests. */
class ScoredCandidate(
    val feed: CatalogueFeed,
    val score: Double,
    val topic: Double,
    val feedSim: Double?,
    val postSim: Double?,
    val negSim: Double?,
    /** Titles of the profile feeds behind [feedSim], best first. */
    val closeTo: List<String>,
    /** Whether the best of those is a picked topic rather than a subscribed feed. */
    val closestIsTopic: Boolean,
    /** Whether the post behind [postSim] was Liked (else read). */
    val likedPostLeads: Boolean,
)

/**
 * Feed discovery's ranking: which catalogue feeds to suggest, in which order, and why. Pure,
 * with no Android imports, like the rest of `engine/`.
 *
 * **Why not the taste direction.** The within-feed taste (`WithinFeed`) is built from residuals
 * that sum to zero inside each feed, so the subject a feed was subscribed for cancels out. A
 * candidate feed's subject needs a between-feed model, which is this.
 *
 * **Centring.** Static embeddings crowd into a narrow cone, so every raw cosine looks alike.
 * Every vector has the catalogue's mean [mean] subtracted and is normalised again before
 * similarities are taken.
 *
 * **The score.** For a candidate with centred centroid `c`, and each similarity put on a common
 * scale, `z(x) = (cos(c, x) − m_x) / s_x` with `m_x`, `s_x` taken over a sample of the catalogue:
 *
 * ```
 * feedSim = mean of the top 3 of  w_f · z(u_f)      subscribed feeds (and picked topics)
 * postSim = mean of the top 5 of  w_i · z(v_i)      Liked (1) and read (0.5) posts
 * negSim  = max over passed and dropped feeds of  d_p · z(u_p)
 * topic   = 0.6·feedSim + 0.4·postSim − 0.5·max(0, negSim)
 * score   = topic + ln(cadenceFit) + ln(quality) + 0.15·categoryOverlap
 * ```
 *
 * Top-k rather than a mean, so that a reader of both woodworking and Rust gets feeds about
 * either rather than about neither. With only one of feedSim and postSim, topic is that one.
 *
 * **Filters**, before scoring: already subscribed (URL key or registrable host), any verdict,
 * no centroid, not English, YouTube, comics, and near-duplicates of a subscription (raw cosine
 * above 0.97).
 *
 * **The deck.** The best 200 by score; then MMR picks 20, each time the candidate with the best
 * `λ·relevance − (1−λ)·max similarity to the cards already picked` (λ 0.7), never two from one
 * host. Every fifth card is an exploration card from a cluster no subscribed feed sits nearest:
 * the cluster is chosen by Thompson sampling on `Beta(1 + subscribes, 1 + passes)` and the card
 * is its best-scoring feed.
 */
class Discovery(
    private val mean: FloatArray,
    private val clusters: List<CatalogueCluster>,
    private val config: DiscoveryConfig = DiscoveryConfig.Default,
    private val now: Instant,
) {
    private val dims = mean.size
    private val clusterById = clusters.associateBy { it.id }

    /** [vector] minus the catalogue mean, normalised; a new array. */
    fun centre(vector: FloatArray): FloatArray {
        val out = FloatArray(dims) { vector[it] - mean[it] }
        normalise(out)
        return out
    }

    /** Whether [feed] may be suggested to the reader of [profile] at all. */
    fun isEligible(feed: CatalogueFeed, profile: DiscoveryProfile): Boolean {
        if (feed.centroid == null || feed.centroid.size != dims) return false
        if (feed.urlKey in profile.excludedKeys) return false
        if (feed.host != null && feed.host in profile.excludedHosts) return false
        if (exactHost(feed.siteUrl)?.let { it in profile.excludedHosts } == true) return false
        if (exactHost(feed.url)?.let { it in profile.excludedHosts } == true) return false
        val language = feed.language?.lowercase()
        if (language != null && language.isNotBlank() && !language.startsWith("en")) return false
        val host = feed.host?.lowercase().orEmpty()
        if (host == "youtube.com" || host.endsWith(".youtube.com") || feed.url.contains("youtube.com/feeds", ignoreCase = true)) return false
        if (feed.source?.contains("comic", ignoreCase = true) == true) return false
        if (feed.categories.any { it.contains("comic", ignoreCase = true) }) return false
        return true
    }

    /** Scores every eligible feed of [catalogue], best first. */
    fun score(catalogue: List<CatalogueFeed>, profile: DiscoveryProfile): List<ScoredCandidate> {
        val subscribedRaw = profile.feeds.map { it.vector }
        val eligible = catalogue.filter { feed ->
            isEligible(feed, profile) && subscribedRaw.none { cosine(it, feed.centroid!!) > config.nearDuplicateCosine }
        }
        if (eligible.isEmpty()) return emptyList()
        val centred = eligible.map { centre(it.centroid!!) }

        // The profile's points, centred, each with the mean and spread of its similarity to the catalogue.
        val topicPoints = profile.pickedClusters.mapNotNull { id ->
            clusterById[id]?.let { ProfileFeed(it.label, it.centroid, config.pickedTopicWeight) }
        }
        val feedPoints = profile.feeds.filter { it.weight > 0.0 } + topicPoints
        val topicTitles = topicPoints.mapTo(HashSet()) { it.title }
        val sample = sampleOf(centred)
        val feeds = feedPoints.map { Point(centre(it.vector), it.weight, sample) }
        val posts = profile.posts.map { Point(centre(it.vector), it.weight, sample) }
        val negatives = profile.negatives.map { Point(centre(it.vector), it.weight, sample) }
        val lovesFirehose = profile.feeds.any { it.tier == CadenceTier.FIREHOSE && it.weight >= LOVED_WEIGHT }

        val scored = eligible.mapIndexed { index, feed ->
            val c = centred[index]
            val feedTerms = feeds.mapIndexed { i, p -> i to p.weight * p.z(c) }.sortedByDescending { it.second }.take(config.feedTopK)
            val postTerms = posts.mapIndexed { i, p -> i to p.weight * p.z(c) }.sortedByDescending { it.second }.take(config.postTopK)
            val feedSim = feedTerms.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
            val postSim = postTerms.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
            val negSim = negatives.maxOfOrNull { it.weight * it.z(c) }
            val positive = when {
                feedSim != null && postSim != null -> config.feedShare * feedSim + config.postShare * postSim
                feedSim != null -> feedSim
                postSim != null -> postSim
                else -> 0.0
            }
            val topic = positive - config.negativeWeight * max(0.0, negSim ?: 0.0)
            val score = topic + ln(cadenceFit(feed.tier, lovesFirehose)) + ln(quality(feed)) +
                config.categoryWeight * categoryOverlap(feed, profile.readerTerms)
            val closeTo = feedTerms.filter { it.second > 0.0 }.map { feedPoints[it.first].title }
            ScoredCandidate(
                feed = feed,
                score = score,
                topic = topic,
                feedSim = feedSim,
                postSim = postSim,
                negSim = negSim,
                closeTo = closeTo,
                closestIsTopic = closeTo.firstOrNull()?.let { it in topicTitles } == true,
                likedPostLeads = postTerms.firstOrNull()?.let { profile.posts[it.first].liked } == true,
            )
        }
        return scored.sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.feed.id })
    }

    /**
     * The week's deck: up to `deckSize` cards, every `explorationEvery`th from a cluster the
     * reader's sources do not cover (see the class comment). [random] drives the bandit; pass a
     * seeded one for a deck that does not change between openings.
     */
    fun deck(catalogue: List<CatalogueFeed>, profile: DiscoveryProfile, random: Random): List<DeckCard> {
        val ranked = score(catalogue, profile)
        if (ranked.isEmpty()) return emptyList()
        val pool = ranked.take(config.mmrPool)
        val top = pool.first().score
        val bottom = pool.last().score
        val span = (top - bottom).takeIf { it > 1e-9 } ?: 1.0
        val centredById = HashMap<Long, FloatArray>()
        fun centredOf(feed: CatalogueFeed) = centredById.getOrPut(feed.id) { centre(feed.centroid!!) }

        val covered = coveredClusters(profile)
        val picked = ArrayList<DeckCard>()
        val pickedIds = HashSet<Long>()
        val pickedHosts = HashSet<String>()

        fun fits(candidate: ScoredCandidate): Boolean {
            val feed = candidate.feed
            if (feed.id in pickedIds) return false
            if (feed.host != null && feed.host in pickedHosts) return false
            return picked.none { cosine(it.feed.centroid!!, feed.centroid!!) > config.nearDuplicateCosine }
        }
        fun take(candidate: ScoredCandidate, exploration: Boolean) {
            picked += DeckCard(candidate.feed, candidate.score, reason(candidate, profile, exploration), exploration)
            pickedIds += candidate.feed.id
            candidate.feed.host?.let { pickedHosts += it }
        }

        while (picked.size < config.deckSize) {
            val slot = picked.size + 1
            if (config.explorationEvery > 0 && slot % config.explorationEvery == 0) {
                val explore = explorationPick(ranked, covered, profile, random, ::fits)
                if (explore != null) {
                    take(explore, exploration = true)
                    continue
                }
            }
            var best: ScoredCandidate? = null
            var bestValue = Double.NEGATIVE_INFINITY
            for (candidate in pool) {
                if (!fits(candidate)) continue
                val relevance = (candidate.score - bottom) / span
                val c = centredOf(candidate.feed)
                val similarity = picked.maxOfOrNull { max(0.0, cosine(c, centredOf(it.feed)).toDouble()) } ?: 0.0
                val value = config.mmrLambda * relevance - (1.0 - config.mmrLambda) * similarity
                if (value > bestValue) {
                    best = candidate
                    bestValue = value
                }
            }
            take(best ?: break, exploration = false)
        }
        return picked
    }

    /**
     * Clusters the reader already has: the nearest cluster of each subscribed feed, and every
     * picked topic. Exploration cards come from the others.
     */
    fun coveredClusters(profile: DiscoveryProfile): Set<Int> {
        val covered = HashSet(profile.pickedClusters)
        for (feed in profile.feeds) {
            clusters.maxByOrNull { cosine(it.centroid, feed.vector) }?.let { covered += it.id }
        }
        return covered
    }

    /**
     * Thompson sampling: one draw from `Beta(1 + subscribes, 1 + passes)` per uncovered cluster
     * that has a candidate left, and the best-scoring candidate of the cluster with the highest
     * draw. Null when every cluster is covered or used up.
     */
    private fun explorationPick(
        ranked: List<ScoredCandidate>,
        covered: Set<Int>,
        profile: DiscoveryProfile,
        random: Random,
        fits: (ScoredCandidate) -> Boolean,
    ): ScoredCandidate? {
        val bestByCluster = LinkedHashMap<Int, ScoredCandidate>()
        for (candidate in ranked) {
            val cluster = candidate.feed.clusterId ?: continue
            if (cluster in covered || cluster in bestByCluster) continue
            if (fits(candidate)) bestByCluster[cluster] = candidate
        }
        if (bestByCluster.isEmpty()) return null
        // Draw in cluster order, so the same seed gives the same choice.
        val draws = bestByCluster.keys.sorted().associateWith { id ->
            val tally = profile.clusterTallies[id] ?: ClusterTally()
            sampleBeta(1 + tally.subscribes, 1 + tally.passes, random)
        }
        val chosen = draws.maxByOrNull { it.value }!!.key
        return bestByCluster.getValue(chosen)
    }

    /** The reason line, in the voice of `Scorer.reason`. */
    fun reason(candidate: ScoredCandidate, profile: DiscoveryProfile, exploration: Boolean): String {
        val label = candidate.feed.clusterId?.let { clusterById[it]?.label }
        if (exploration) return if (label != null) "Something different: $label" else "Something different"
        val feedPull = candidate.feedSim?.let { config.feedShare * it } ?: Double.NEGATIVE_INFINITY
        val postPull = candidate.postSim?.let { config.postShare * it } ?: Double.NEGATIVE_INFINITY
        if (candidate.topic > 0.0) {
            if (feedPull >= postPull && candidate.closeTo.isNotEmpty()) {
                if (candidate.closestIsTopic) return "Because you picked ${candidate.closeTo.first()}"
                val names = candidate.closeTo.filter { name -> profile.pickedClusters.none { clusterById[it]?.label == name } }.take(2)
                when (names.size) {
                    1 -> return "Close to ${names[0]}"
                    2 -> return "Close to ${names[0]} and ${names[1]}"
                }
            }
            if (postPull > Double.NEGATIVE_INFINITY && (candidate.postSim ?: 0.0) > 0.0) {
                return if (candidate.likedPostLeads) "Like posts you have Liked" else "Like posts you have read"
            }
        }
        return if (label != null) "Recommended in $label" else "Recommended from the catalogue"
    }

    /** Rare and Weekly 1.0, Daily 0.85, Firehose 0.5 (0.8 for a reader who loves a firehose feed). */
    fun cadenceFit(tier: CadenceTier?, lovesFirehose: Boolean): Double = when (tier) {
        CadenceTier.RARE -> config.rareFit
        CadenceTier.WEEKLY, null -> config.weeklyFit
        CadenceTier.DAILY -> config.dailyFit
        CadenceTier.FIREHOSE -> if (lovesFirehose) config.lovedFirehoseFit else config.firehoseFit
    }

    /**
     * 1.0 for a feed of full posts, 0.8 for excerpts; × 0.7 once its newest post is more than
     * 45 days old; × (0.5 + 0.5 × the catalogue's own estimate), so that estimate can lower a
     * feed by at most half.
     */
    fun quality(feed: CatalogueFeed): Double {
        val text = if ((feed.fullTextShare ?: 1.0) >= config.fullTextShareMin) 1.0 else config.excerptQuality
        val stale = feed.lastPostAt?.let { Duration.between(it, now).toDays() > config.staleDays } == true
        val own = 0.5 + 0.5 * feed.quality.coerceIn(0.0, 1.0)
        return text * (if (stale) config.staleQuality else 1.0) * own
    }

    /** Jaccard overlap of the feed's categories and cluster label with the reader's tags and read categories. */
    fun categoryOverlap(feed: CatalogueFeed, readerTerms: Set<String>): Double {
        if (readerTerms.isEmpty()) return 0.0
        val terms = (feed.categories + listOfNotNull(feed.clusterId?.let { clusterById[it]?.label }))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (terms.isEmpty()) return 0.0
        val union = (terms + readerTerms).size
        return (terms intersect readerTerms).size.toDouble() / union
    }

    /** Evenly spaced catalogue feeds, so the z-scale does not depend on the order or a random draw. */
    private fun sampleOf(centred: List<FloatArray>): List<FloatArray> {
        if (centred.size <= config.zSampleSize) return centred
        val step = centred.size.toDouble() / config.zSampleSize
        return List(config.zSampleSize) { centred[(it * step).toInt()] }
    }

    /** A profile point with the mean and spread of its similarity over the catalogue sample. */
    private inner class Point(val vector: FloatArray, val weight: Double, sample: List<FloatArray>) {
        private val mean: Double
        private val deviation: Double

        init {
            var sum = 0.0
            var squares = 0.0
            for (s in sample) {
                val c = cosine(vector, s).toDouble()
                sum += c
                squares += c * c
            }
            val n = sample.size.coerceAtLeast(1)
            mean = sum / n
            deviation = sqrt(max(squares / n - mean * mean, 0.0)).coerceAtLeast(MIN_DEVIATION)
        }

        fun z(candidate: FloatArray): Double = (cosine(vector, candidate) - mean) / deviation
    }

    companion object {
        private const val MIN_DEVIATION = 1e-3

        /** A subscribed feed this heavy (effective affinity at the "loved" 0.7) counts as loved. */
        private const val LOVED_WEIGHT = (0.7 - 0.35) / 0.65 - 1e-9

        /**
         * A subscribed feed's weight from its effective affinity (`Affinity.effective`, the
         * slider applied): `clamp((affinity − 0.35) / 0.65, 0, 1)`. Less and Rarely weigh 0,
         * Favourite 1, a neutral feed on Auto 0.23.
         */
        fun feedWeight(effectiveAffinity: Double, config: DiscoveryConfig = DiscoveryConfig.Default): Double =
            ((effectiveAffinity - config.weightFloorAffinity) / (1.0 - config.weightFloorAffinity)).coerceIn(0.0, 1.0)

        /** A passed card's weight as a negative: halves every `passHalfLifeDays`. */
        fun passWeight(decidedAt: Instant, now: Instant, config: DiscoveryConfig = DiscoveryConfig.Default): Double {
            val days = Duration.between(decidedAt, now).toMillis().coerceAtLeast(0) / 86_400_000.0
            return 0.5.pow(days / config.passHalfLifeDays)
        }

        /** The normalised mean of [vectors]; null when there are none or they cancel out. */
        fun centroid(vectors: List<FloatArray>): FloatArray? {
            if (vectors.isEmpty()) return null
            val sum = FloatArray(vectors.first().size)
            for (v in vectors) for (d in sum.indices) sum[d] += v[d]
            return if (normalise(sum)) sum else null
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (d in a.indices) dot += a[d] * b[d]
            return dot
        }

        /** Scales [vector] to length 1 in place; false, leaving it alone, if its length is zero. */
        fun normalise(vector: FloatArray): Boolean {
            var squares = 0.0
            for (v in vector) squares += v.toDouble() * v
            if (squares == 0.0) return false
            val factor = (1.0 / sqrt(squares)).toFloat()
            for (d in vector.indices) vector[d] *= factor
            return true
        }

        /** The host of [url], lower-cased and without `www.`; null when it has none. */
        fun exactHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val trimmed = url.trim()
            val host = runCatching { java.net.URI(if ("://" in trimmed) trimmed else "https://$trimmed").host }.getOrNull() ?: return null
            return host.lowercase().removePrefix("www.")
        }

        /** A draw from `Beta(a, b)` for whole-number shapes, as the ratio of two gamma draws. */
        fun sampleBeta(a: Int, b: Int, random: Random): Double {
            val x = sampleGamma(a, random)
            val y = sampleGamma(b, random)
            return x / (x + y)
        }

        /** `Gamma(k, 1)` for whole-number k: the sum of k exponential draws. */
        private fun sampleGamma(k: Int, random: Random): Double {
            var sum = 0.0
            repeat(k.coerceAtLeast(1)) { sum += -ln(1.0 - random.nextDouble()) }
            return sum
        }
    }
}

package net.tgnra.morningissue.data.repository

import java.time.Instant
import net.tgnra.morningissue.data.db.ArticleSignals
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.signals.StoredEmbedding
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.ArticleEvidence
import net.tgnra.morningissue.engine.LabelledPost
import net.tgnra.morningissue.engine.PostTraits
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.WithinFeed
import net.tgnra.morningissue.engine.WithinFeedModel
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.EnclosureKind

/**
 * The storage side of within-feed ranking (`engine/WithinFeed.kt`): reads the stored signals
 * of posts (`ArticleSignalDao`) and hands them to the pure engine as [PostTraits].
 *
 * `RoomEditionPublisher` uses it once per build, in the engine's order: [learn] from the
 * evidence `AffinityLearner.evidence` gathered, then [traitsOf] the build's candidates, then
 * `model.source(traits)` for the scorer. Nothing is stored: like affinity, the model is derived
 * afresh from `edition_item`, `engagement` and the signal tables every time.
 *
 * Neither function opens a transaction; the publisher calls them inside its own.
 */
class WithinFeedLearner(
    database: MorningIssueDatabase,
    config: RankingConfig = RankingConfig.Default,
) {
    private val signalDao = database.articleSignalDao()
    private val withinFeed = WithinFeed(config)

    /**
     * Learns the model as of [at].
     *
     * @param evidence what `AffinityLearner.evidence` returned for this build: the same posts,
     *   window and rule for editions not yet superseded as feed affinity learns from
     * @param since the start of that window (`AffinityLearner.windowStart`)
     */
    suspend fun learn(evidence: Map<Long, ArticleEvidence>, since: Instant, at: Instant): WithinFeedModel {
        if (evidence.isEmpty()) return withinFeed.learn(emptyList(), at)
        // One read for the whole window. It can hold a few posts the evidence leaves out (the
        // untouched stories of a pending edition); they are simply not asked for.
        val signals = signalDao.signalsWithEvidenceSince(since, StaticEmbedder.MODEL_ID).associateBy { it.articleId }
        val posts = evidence.map { (articleId, record) ->
            // A post without stored signals still counts towards its feed's rate.
            LabelledPost(articleId, record, signals[articleId]?.let(::toPostTraits) ?: PostTraits(record.feedId))
        }
        return withinFeed.learn(posts, at)
    }

    /**
     * The traits of [articleIds], an edition's candidates, for [model] to weigh; a post that
     * no longer exists is left out. Nothing is read for a model that has learned nothing (a
     * first edition), and the vectors are left in the database when the model has no text
     * taste to hold them against.
     */
    suspend fun traitsOf(articleIds: Collection<Long>, model: WithinFeedModel): Map<Long, PostTraits> {
        if (articleIds.isEmpty() || model.isEmpty) return emptyMap()
        val vectors = StaticEmbedder.MODEL_ID.takeIf { model.usesText }
        return signalDao.signalsFor(articleIds, vectors).associate { it.articleId to toPostTraits(it) }
    }

    companion object {
        /**
         * What the engine reads of a post's stored signals.
         *
         * - Categories are matched by key.
         * - A word count of 0 is "no body stored", not a very short post: the length is not known.
         * - A traits row that is missing, or behind the current rules (a row that only records
         *   an enclosure, written by an archive import), is trusted for its enclosure alone.
         * - A stored embedding that is a mark and not a vector (`StoredEmbedding`) is no vector.
         */
        fun toPostTraits(signals: ArticleSignals): PostTraits {
            val traits = signals.traits
            val current = traits?.takeIf { it.traitsVersion == ArticleTraitRules.VERSION }
            return PostTraits(
                feedId = signals.feedId,
                categories = signals.categories.mapTo(HashSet(signals.categories.size * 2)) { it.key },
                wordCount = signals.wordCount.takeIf { it > 0 } ?: PostTraits.UNKNOWN_WORD_COUNT,
                enclosure = traits?.enclosure ?: EnclosureKind.NONE,
                isLinkPost = current?.isLinkPost ?: false,
                hasCode = current?.hasCode ?: false,
                imageCount = current?.imageCount ?: 0,
                linkCount = current?.linkCount ?: 0,
                titlePrefix = current?.titlePrefix,
                embedding = StoredEmbedding.vectorOf(signals.embedding),
            )
        }
    }
}

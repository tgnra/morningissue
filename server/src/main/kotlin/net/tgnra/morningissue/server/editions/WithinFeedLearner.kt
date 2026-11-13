package net.tgnra.morningissue.server.editions

import java.time.Instant
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.ArticleEvidence
import net.tgnra.morningissue.engine.LabelledPost
import net.tgnra.morningissue.engine.PostTraits
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.WithinFeed
import net.tgnra.morningissue.engine.WithinFeedModel
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleTraitsEntity
import net.tgnra.morningissue.server.db.EditionArticleDao
import net.tgnra.morningissue.server.db.LearningSignalRow
import net.tgnra.morningissue.server.db.ServerDatabase

/** Everything stored about a post that the within-feed ranking reads, without its text. */
class PostSignals(
    val articleId: Long,
    val feedId: Long,
    val feedWordCount: Int,
    val categories: List<ArticleCategory>,
    val traits: ArticleTraitsEntity?,
    val embedding: ByteArray?,
)

/** How `article_embedding.vector` is read: a vector, or a one-byte mark saying why there is none. */
object StoredEmbedding {
    /** The unit vector a row holds; null for no row and for a mark. */
    fun vectorOf(stored: ByteArray?): FloatArray? =
        stored?.takeIf { it.size == StaticEmbedder.DIMENSIONS }?.let(StaticEmbedder::fromBytes)
}

/**
 * The storage side of within-feed ranking for one user: the app's `WithinFeedLearner`, reading
 * the global signal tables through `LearningDao`. Nothing is stored.
 *
 * Neither function opens a transaction; the publisher calls them inside its own.
 */
class WithinFeedLearner(
    database: ServerDatabase,
    config: RankingConfig = RankingConfig.Default,
) {
    private val learningDao = database.learningDao()
    private val withinFeed = WithinFeed(config)

    /** Learns the user's model as of [at] from [evidence] (`AffinityLearner.evidence`), whose window starts at [since]. */
    suspend fun learn(userId: Long, evidence: Map<Long, ArticleEvidence>, since: Instant, at: Instant): WithinFeedModel {
        if (evidence.isEmpty()) return withinFeed.learn(emptyList(), at)
        val signals = attach(
            learningDao.signalRowsWithEvidenceSince(userId, since, StaticEmbedder.MODEL_ID),
            learningDao.categoriesWithEvidenceSince(userId, since),
        ).associateBy { it.articleId }
        val posts = evidence.map { (articleId, record) ->
            LabelledPost(articleId, record, signals[articleId]?.let(::toPostTraits) ?: PostTraits(record.feedId))
        }
        return withinFeed.learn(posts, at)
    }

    /** The traits of [articleIds], an edition's candidates, for [model] to weigh. */
    suspend fun traitsOf(articleIds: Collection<Long>, model: WithinFeedModel): Map<Long, PostTraits> {
        if (articleIds.isEmpty() || model.isEmpty) return emptyMap()
        val vectors = StaticEmbedder.MODEL_ID.takeIf { model.usesText }
        return signalsFor(articleIds, vectors).associate { it.articleId to toPostTraits(it) }
    }

    /** The signals of [articleIds]; vectors of [model], or none when it is null. */
    suspend fun signalsFor(articleIds: Collection<Long>, model: Int?): List<PostSignals> =
        articleIds.distinct().chunked(EditionArticleDao.MAX_IDS).flatMap { ids ->
            attach(learningDao.signalRows(ids, model), learningDao.categoriesOf(ids))
        }

    private fun attach(rows: List<LearningSignalRow>, categories: List<ArticleCategoryEntity>): List<PostSignals> {
        val byArticle = categories.groupBy({ it.articleId }) { ArticleCategory(it.key, it.label) }
        return rows.map { PostSignals(it.articleId, it.feedId, it.feedWordCount, byArticle[it.articleId].orEmpty(), it.traits, it.vector) }
    }

    companion object {
        /** What the engine reads of a post's stored signals; the rules are the app's. */
        fun toPostTraits(signals: PostSignals): PostTraits {
            val traits = signals.traits
            val current = traits?.takeIf { it.traitsVersion == ArticleTraitRules.VERSION }
            return PostTraits(
                feedId = signals.feedId,
                categories = signals.categories.mapTo(HashSet(signals.categories.size * 2)) { it.key },
                wordCount = signals.feedWordCount.takeIf { it > 0 } ?: PostTraits.UNKNOWN_WORD_COUNT,
                enclosure = traits?.enclosure ?: EnclosureKind.NONE,
                isLinkPost = current?.isLinkPost ?: false,
                hasCode = current?.hasCode ?: false,
                imageCount = current?.imageCount ?: 0,
                linkCount = current?.linkCount ?: 0,
                titlePrefix = current?.titlePrefix,
                traitsKnown = current != null,
                embedding = StoredEmbedding.vectorOf(signals.embedding),
            )
        }
    }
}

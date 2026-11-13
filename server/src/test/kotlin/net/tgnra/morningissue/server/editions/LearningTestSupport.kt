package net.tgnra.morningissue.server.editions

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import net.tgnra.morningissue.embedding.EmbeddableText
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleTraitsEntity

/**
 * What the app's `RoomArticleRepository` did on the phone, for one user of a [TestWorld]: each
 * action is written as merged events write it, then the user's affinity is relearned at once,
 * as `sync/Relearner` does after its debounce.
 */
class Reader(private val world: TestWorld, val userId: Long, private val config: RankingConfig = RankingConfig.Default) {
    private val learner = AffinityLearner(world.db, config)

    suspend fun relearn() = world.db.withWriteTransaction { learner.refresh(userId, world.clock.now) }

    suspend fun markOpened(articleId: Long) = then { world.open(userId, articleId) }

    suspend fun markRead(articleId: Long) = then { world.read(userId, articleId) }

    /** The phone sends a READ event too once the reader has scrolled far enough (`readScrollFraction`). */
    suspend fun setReadFraction(articleId: Long, fraction: Float) = then {
        world.readFraction(userId, articleId, fraction)
        if (fraction >= config.readScrollFraction) world.read(userId, articleId)
    }

    suspend fun setLiked(articleId: Long, liked: Boolean) = then { world.like(userId, articleId, if (liked) world.clock.now else null) }

    suspend fun setSaved(articleId: Long, saved: Boolean) = then { world.save(userId, articleId, if (saved) world.clock.now else null) }

    private suspend fun then(action: suspend () -> Unit) {
        action()
        relearn()
    }
}

/** [userId]'s stored affinity for [feedId] (`subscription.affinity`). */
suspend fun TestWorld.affinityOf(userId: Long, feedId: Long): Double = db.editionArticleDao().feed(userId, feedId)!!.affinity

/** [userId]'s tag affinities by name. */
suspend fun TestWorld.tagAffinities(userId: Long): Map<String, Double> = db.learningDao().tags(userId).associate { it.name to it.affinity }

/** Files [articleId] under [category], as feed sync stores a post's categories. */
suspend fun TestWorld.addCategory(articleId: Long, category: String) {
    val parsed = ArticleCategories.of(category)!!
    db.importDao().insertCategories(listOf(ArticleCategoryEntity(articleId, parsed.key, parsed.label)))
}

/**
 * The app's `SignalsUpkeep` (traits, then embeddings) for every post that has neither, standing
 * in for W-A's signals upkeep: traits by `ArticleTraitRules`, vectors by the real static
 * embedder, as `EmbeddingBackfill` stores them.
 */
suspend fun TestWorld.runSignalsUpkeep(siteUrl: String = "https://example.com/") {
    val ids = db.useWriterConnection { c ->
        c.usePrepared("SELECT id FROM article WHERE id NOT IN (SELECT articleId FROM article_traits) ORDER BY id") { s ->
            buildList { while (s.step()) add(s.getLong(0)) }
        }
    }
    val embedder = EmbeddingFixtures.embedder
    for (id in ids) {
        val article = db.importDao().article(id)!!
        val body = db.editionArticleDao().contents(listOf(id)).singleOrNull()
        val blocks = body?.blocksJson?.let(ContentBlocks::decode).orEmpty()
        val traits = ArticleTraitRules.compute(title = article.title, url = article.url, siteUrl = siteUrl, blocks = blocks, enclosure = EnclosureKind.NONE)
        db.importDao().upsertTraits(
            ArticleTraitsEntity(id, ArticleTraitRules.VERSION, traits.linkCount, traits.imageCount, traits.hasCode, traits.enclosure, traits.isLinkPost, traits.titlePrefix),
        )
        val vector = embedder.embed(EmbeddableText.of(article.title, article.summary, null)) ?: continue
        storeEmbedding(id, StaticEmbedder.toBytes(vector))
    }
}

/** Stores [vector] as [articleId]'s static-embedder vector (`article_embedding`, W-A's table). */
suspend fun TestWorld.storeEmbedding(articleId: Long, vector: ByteArray) = db.useWriterConnection { c ->
    c.usePrepared("INSERT OR REPLACE INTO article_embedding (articleId, model, vector) VALUES (?, ?, ?)") { s ->
        s.bindLong(1, articleId)
        s.bindLong(2, StaticEmbedder.MODEL_ID.toLong())
        s.bindBlob(3, vector)
        s.step()
    }
}

/** Posts without a stored vector of the static embedder. */
suspend fun TestWorld.countWithoutEmbedding(): Long = db.useWriterConnection { c ->
    c.usePrepared(
        "SELECT COUNT(*) FROM article WHERE id NOT IN (SELECT articleId FROM article_embedding WHERE model = ${StaticEmbedder.MODEL_ID})",
    ) { it.step(); it.getLong(0) }
}

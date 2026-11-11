package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.time.Instant
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.EnclosureKind

/**
 * The per-post signals the within-feed ranking is built on: the feed's categories
 * (`article_category`), structural traits (`article_traits`) and text embeddings
 * (`article_embedding`). Sync, the archive and the backfills write them; the ranking reads them
 * through [signalsFor] and [signalsWithEvidenceSince], which never touch a post's text.
 */
@Dao
abstract class ArticleSignalDao {
    // --- Categories -------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCategories(categories: List<ArticleCategoryEntity>)

    /** Files the post under [categories], cleaned as `ArticleCategories.clean` does; categories it already has stay. */
    suspend fun addCategories(articleId: Long, categories: List<ArticleCategory>) {
        if (categories.isEmpty()) return
        insertCategories(ArticleCategories.clean(categories).map { ArticleCategoryEntity(articleId, it.key, it.label) })
    }

    /** The categories of each post, in key order. */
    @Query("SELECT * FROM article_category WHERE articleId IN (:articleIds) ORDER BY articleId, `key`")
    abstract suspend fun categoriesOf(articleIds: List<Long>): List<ArticleCategoryEntity>

    @Query("SELECT COUNT(*) FROM article_category")
    abstract suspend fun categoryCount(): Int

    // --- Traits -----------------------------------------------------------------------------

    @Upsert
    abstract suspend fun upsertTraits(traits: ArticleTraitsEntity)

    @Upsert
    abstract suspend fun upsertTraits(traits: List<ArticleTraitsEntity>)

    @Query("SELECT * FROM article_traits WHERE articleId = :articleId")
    abstract suspend fun getTraits(articleId: Long): ArticleTraitsEntity?

    @Query("SELECT * FROM article_traits WHERE articleId IN (:articleIds)")
    abstract suspend fun traitsOf(articleIds: List<Long>): List<ArticleTraitsEntity>

    @Query("SELECT COUNT(*) FROM article_traits")
    abstract suspend fun traitsCount(): Int

    /** Records an enclosure learned after the row was written (the feed is the only source of it). */
    @Query("UPDATE article_traits SET enclosure = :enclosure WHERE articleId = :articleId")
    abstract suspend fun updateEnclosure(articleId: Long, enclosure: EnclosureKind)

    /**
     * Records the enclosure of a post whose traits have not been computed, in a row marked
     * `ArticleTraitRules.VERSION_PENDING` so that the backfill completes it and keeps the enclosure.
     */
    suspend fun insertPendingEnclosure(articleId: Long, enclosure: EnclosureKind) = upsertTraits(
        ArticleTraitsEntity(articleId, ArticleTraitRules.VERSION_PENDING, 0, 0, false, enclosure, false, null),
    )

    /**
     * Posts with no `article_traits` row computed under rules [version], in ID order, a batch
     * at a time: pass the last ID of the previous batch. Input to `TraitsBackfill`.
     */
    @Query(
        "SELECT a.id AS articleId, a.title AS title, a.url AS url, f.siteUrl AS siteUrl, c.blocksJson AS blocksJson, " +
            "t.enclosure AS enclosure FROM article a JOIN feed f ON f.id = a.feedId " +
            "LEFT JOIN article_content c ON c.articleId = a.id LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.id > :afterId AND (t.articleId IS NULL OR t.traitsVersion != :version) ORDER BY a.id LIMIT :limit",
    )
    abstract suspend fun articlesNeedingTraits(version: Int, afterId: Long, limit: Int): List<TraitsInputRow>

    /**
     * Which of [articleIds] still exist and still have no row under rules [version]. The
     * backfill asks again inside its write transaction, since a sync may have written a row,
     * or a feed been removed, while it was computing. At most [MAX_IDS] IDs per call.
     */
    @Query(
        "SELECT a.id FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.id IN (:articleIds) AND (t.articleId IS NULL OR t.traitsVersion != :version)",
    )
    abstract suspend fun stillNeedingTraits(version: Int, articleIds: List<Long>): List<Long>

    // --- What sync asks about the posts of a fetched feed -----------------------------------

    /**
     * Which of [guids] the feed already holds, and whether each has categories and traits.
     * One indexed read per fetched feed, so that sync can complete posts stored before these
     * tables existed without a query per post. At most [MAX_IDS] GUIDs per call.
     */
    @Query(
        "SELECT a.id AS articleId, a.guid AS guid, " +
            "EXISTS (SELECT 1 FROM article_category c WHERE c.articleId = a.id) AS hasCategories, " +
            "t.traitsVersion AS traitsVersion, t.enclosure AS enclosure " +
            "FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.feedId = :feedId AND a.guid IN (:guids)",
    )
    abstract suspend fun storedPosts(feedId: Long, guids: List<String>): List<StoredPostRow>

    // --- Embeddings -------------------------------------------------------------------------

    /** Stores vectors, replacing any a post already has (from this model or an older one). */
    @Upsert
    abstract suspend fun upsertEmbeddings(embeddings: List<ArticleEmbeddingEntity>)

    @Query("SELECT * FROM article_embedding WHERE articleId = :articleId")
    abstract suspend fun getEmbedding(articleId: Long): ArticleEmbeddingEntity?

    @Query("SELECT COUNT(*) FROM article_embedding")
    abstract suspend fun embeddingCount(): Int

    /** How many posts [articlesWithoutEmbedding] has yet to return for [model]. */
    @Query(
        "SELECT COUNT(*) FROM article a WHERE NOT EXISTS " +
            "(SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model)",
    )
    abstract suspend fun countWithoutEmbedding(model: Int): Int

    /**
     * Posts that [model] has not embedded, newest first (the posts the next edition ranks),
     * a batch at a time: pass the last ID of the previous batch as [beforeId], starting from
     * `Long.MAX_VALUE`. Paging by ID rather than by "still missing" means a post the embedder
     * cannot handle is passed over instead of being returned for ever.
     *
     * The body is the plain text kept in the search index, cut to [bodyChars] characters so a
     * batch of long articles stays small; it is empty when the post has no stored body.
     */
    @Query(
        "SELECT a.id AS articleId, a.title AS title, a.summary AS summary, substr(x.body, 1, :bodyChars) AS body " +
            "FROM article a LEFT JOIN article_fts x ON x.rowid = a.id " +
            "WHERE a.id < :beforeId AND NOT EXISTS " +
            "(SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model) " +
            "ORDER BY a.id DESC LIMIT :limit",
    )
    abstract suspend fun articlesWithoutEmbedding(model: Int, beforeId: Long, limit: Int, bodyChars: Int): List<EmbeddingInputRow>

    /**
     * Which of [articleIds] still exist and still have no row written by [model]. The
     * embedding pass asks again inside its write transaction, since a feed may have been
     * removed while it was computing. At most [MAX_IDS] IDs per call.
     */
    @Query(
        "SELECT a.id FROM article a WHERE a.id IN (:articleIds) AND NOT EXISTS " +
            "(SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model)",
    )
    abstract suspend fun stillWithoutEmbedding(model: Int, articleIds: List<Long>): List<Long>

    // --- Reads for ranking ------------------------------------------------------------------

    @Query("$SIGNAL_SELECT WHERE a.id IN (:articleIds)")
    protected abstract suspend fun signalRows(articleIds: List<Long>, model: Int?): List<ArticleSignalRow>

    @Query("$SIGNAL_SELECT WHERE a.id IN ($EVIDENCE_IDS) AND a.feedId IN (SELECT id FROM feed WHERE status = 'SUBSCRIBED')")
    protected abstract suspend fun signalRowsWithEvidenceSince(since: Instant, model: Int?): List<ArticleSignalRow>

    @Query("SELECT * FROM article_category WHERE articleId IN ($EVIDENCE_IDS) ORDER BY articleId, `key`")
    protected abstract suspend fun categoriesWithEvidenceSince(since: Instant): List<ArticleCategoryEntity>

    /**
     * The signals of the posts in [articleIds] (an edition's candidates). Short rows only: no
     * title, summary or body is read.
     *
     * @param model the embedding model whose vectors to return; null leaves the vectors out,
     *   for a caller that only needs categories and traits.
     */
    @Transaction
    open suspend fun signalsFor(articleIds: Collection<Long>, model: Int?): List<ArticleSignals> =
        articleIds.distinct().chunked(MAX_IDS).flatMap { ids -> attach(signalRows(ids, model), categoriesOf(ids)) }

    /**
     * The signals of every post with evidence at or after [since], where evidence is what
     * `AffinityLearner` counts: the post was printed (not merely indexed) in an edition
     * released since then, or the reader opened, read, saved or Liked it since then. What the
     * reader did with each post comes from `EditionDao.getRunsSince` and
     * `EngagementDao.engagedSince`, as it does for affinity.
     */
    @Transaction
    open suspend fun signalsWithEvidenceSince(since: Instant, model: Int?): List<ArticleSignals> =
        attach(signalRowsWithEvidenceSince(since, model), categoriesWithEvidenceSince(since))

    private fun attach(rows: List<ArticleSignalRow>, categories: List<ArticleCategoryEntity>): List<ArticleSignals> {
        val byArticle = categories.groupBy({ it.articleId }) { ArticleCategory(it.key, it.label) }
        return rows.map { ArticleSignals(it.articleId, it.feedId, it.feedWordCount, byArticle[it.articleId].orEmpty(), it.traits, it.vector) }
    }

    companion object {
        /** IDs per `IN (...)` list, safely under SQLite's limit of 999 bound values on older Android. */
        const val MAX_IDS = 500

        /** A traits row for [articleId] computed under the current rules. */
        fun traitsRow(articleId: Long, traits: ArticleTraits, version: Int = ArticleTraitRules.VERSION) = ArticleTraitsEntity(
            articleId = articleId,
            traitsVersion = version,
            linkCount = traits.linkCount,
            imageCount = traits.imageCount,
            hasCode = traits.hasCode,
            enclosure = traits.enclosure,
            isLinkPost = traits.isLinkPost,
            titlePrefix = traits.titlePrefix,
        )

        /** Shared projection for [ArticleSignalRow]; append a WHERE clause. */
        private const val SIGNAL_SELECT =
            "SELECT a.id AS articleId, a.feedId AS feedId, a.feedWordCount AS feedWordCount, " +
                "t.articleId AS t_articleId, t.traitsVersion AS t_traitsVersion, t.linkCount AS t_linkCount, " +
                "t.imageCount AS t_imageCount, t.hasCode AS t_hasCode, t.enclosure AS t_enclosure, " +
                "t.isLinkPost AS t_isLinkPost, t.titlePrefix AS t_titlePrefix, v.vector AS vector " +
                "FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
                "LEFT JOIN article_embedding v ON v.articleId = a.id AND v.model = :model"

        /** The posts with evidence since `:since`; the same rule as `getRunsSince` plus `engagedSince`. */
        private const val EVIDENCE_IDS =
            "SELECT i.articleId FROM edition_item i JOIN edition d ON d.id = i.editionId " +
                "WHERE i.kind != 'INDEX' AND d.releaseAt >= :since " +
                "UNION SELECT g.articleId FROM engagement g " +
                "WHERE g.openedAt >= :since OR g.readAt >= :since OR g.savedAt >= :since OR g.likedAt >= :since"
    }
}

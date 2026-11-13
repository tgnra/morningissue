package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.EnclosureKind

/**
 * Post signals, global: the feed's categories (`article_category`), structural traits
 * (`article_traits`) and text embeddings (`article_embedding`), as the refresher and the
 * backfills write them. Owned by W-A (`docs/server-design.md`, section 8); the ranking's reads
 * are W-B's `LearningDao`. Ported from the app's `ArticleSignalDao`.
 */
@Dao
abstract class SignalDao {
    // --- Categories ----------------------------------------------------------------------------

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

    // --- Traits --------------------------------------------------------------------------------

    @Upsert
    abstract suspend fun upsertTraits(traits: ArticleTraitsEntity)

    @Upsert
    abstract suspend fun upsertTraits(traits: List<ArticleTraitsEntity>)

    @Query("SELECT * FROM article_traits WHERE articleId = :articleId")
    abstract suspend fun getTraits(articleId: Long): ArticleTraitsEntity?

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

    /** Posts with no `article_traits` row under rules [version], in ID order, a batch at a time. Input to `TraitsBackfill`. */
    @Query(
        "SELECT a.id AS articleId, a.title AS title, a.url AS url, f.siteUrl AS siteUrl, c.blocksJson AS blocksJson, " +
            "t.enclosure AS enclosure FROM article a JOIN feed f ON f.id = a.feedId " +
            "LEFT JOIN article_content c ON c.articleId = a.id LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.id > :afterId AND (t.articleId IS NULL OR t.traitsVersion != :version) ORDER BY a.id LIMIT :limit",
    )
    abstract suspend fun articlesNeedingTraits(version: Int, afterId: Long, limit: Int): List<TraitsInputRow>

    /** Which of [articleIds] still exist and still have no row under rules [version]. At most [MAX_IDS] IDs per call. */
    @Query(
        "SELECT a.id FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.id IN (:articleIds) AND (t.articleId IS NULL OR t.traitsVersion != :version)",
    )
    abstract suspend fun stillNeedingTraits(version: Int, articleIds: List<Long>): List<Long>

    // --- What refresh asks about the posts of a fetched feed -----------------------------------

    /**
     * Which of [guids] the feed already holds, and whether each has categories and traits. One
     * indexed read per fetched feed. At most [MAX_IDS] GUIDs per call.
     */
    @Query(
        "SELECT a.id AS articleId, a.guid AS guid, " +
            "EXISTS (SELECT 1 FROM article_category c WHERE c.articleId = a.id) AS hasCategories, " +
            "t.traitsVersion AS traitsVersion, t.enclosure AS enclosure " +
            "FROM article a LEFT JOIN article_traits t ON t.articleId = a.id " +
            "WHERE a.feedId = :feedId AND a.guid IN (:guids)",
    )
    abstract suspend fun storedPosts(feedId: Long, guids: List<String>): List<StoredPostRow>

    // --- Embeddings ----------------------------------------------------------------------------

    /** Stores vectors, replacing any a post already has (from this model or an older one). */
    @Upsert
    abstract suspend fun upsertEmbeddings(embeddings: List<ArticleEmbeddingEntity>)

    @Query("SELECT * FROM article_embedding WHERE articleId = :articleId")
    abstract suspend fun getEmbedding(articleId: Long): ArticleEmbeddingEntity?

    @Query("SELECT COUNT(*) FROM article_embedding")
    abstract suspend fun embeddingCount(): Int

    /** How many posts [articlesWithoutEmbedding] has yet to return for [model]. */
    @Query("SELECT COUNT(*) FROM article a WHERE NOT EXISTS (SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model)")
    abstract suspend fun countWithoutEmbedding(model: Int): Int

    /**
     * Posts that [model] has not embedded, newest first, a batch at a time: pass the last ID of
     * the previous batch as [beforeId], starting from `Long.MAX_VALUE`. The body is the plain
     * text kept in the search index, cut to [bodyChars] characters.
     */
    @Query(
        "SELECT a.id AS articleId, a.title AS title, a.summary AS summary, substr(x.body, 1, :bodyChars) AS body " +
            "FROM article a LEFT JOIN article_fts x ON x.rowid = a.id " +
            "WHERE a.id < :beforeId AND NOT EXISTS " +
            "(SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model) " +
            "ORDER BY a.id DESC LIMIT :limit",
    )
    abstract suspend fun articlesWithoutEmbedding(model: Int, beforeId: Long, limit: Int, bodyChars: Int): List<EmbeddingInputRow>

    /** Which of [articleIds] still exist and still have no row written by [model]. At most [MAX_IDS] IDs per call. */
    @Query(
        "SELECT a.id FROM article a WHERE a.id IN (:articleIds) AND NOT EXISTS " +
            "(SELECT 1 FROM article_embedding v WHERE v.articleId = a.id AND v.model = :model)",
    )
    abstract suspend fun stillWithoutEmbedding(model: Int, articleIds: List<Long>): List<Long>

    companion object {
        /** IDs per `IN (...)` list. */
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
    }
}

/** What refresh needs to know about a post it already holds; see [SignalDao.storedPosts]. */
data class StoredPostRow(
    val articleId: Long,
    val guid: String,
    val hasCategories: Boolean,
    val traitsVersion: Int?,
    val enclosure: EnclosureKind?,
)

/** A post whose traits are missing or behind, with what they are computed from; see [SignalDao.articlesNeedingTraits]. */
data class TraitsInputRow(
    val articleId: Long,
    val title: String,
    val url: String?,
    val siteUrl: String?,
    val blocksJson: String?,
    val enclosure: EnclosureKind?,
)

/** A post to embed; see [SignalDao.articlesWithoutEmbedding]. */
data class EmbeddingInputRow(
    val articleId: Long,
    val title: String,
    val summary: String?,
    val body: String?,
)

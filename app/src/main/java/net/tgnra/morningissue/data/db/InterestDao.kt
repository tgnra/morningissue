package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import java.time.Instant
import net.tgnra.morningissue.ai.PostForRating

/** How many ratings there are in a window, and their mean (null with none). */
data class RatingSummary(val count: Int, val mean: Double?)

/**
 * The on-device model's ratings (`article_interest`) and the little the model is told about the
 * reader. Written by `ai/InterestRater` while the app is in the foreground; read by the
 * edition build, which never asks the model itself.
 */
@Dao
abstract class InterestDao {
    @Upsert
    abstract suspend fun upsert(ratings: List<ArticleInterestEntity>)

    @Query("SELECT * FROM article_interest WHERE articleId IN (:articleIds)")
    protected abstract suspend fun ratingsChunk(articleIds: List<Long>): List<ArticleInterestEntity>

    /** The stored ratings of [articleIds], by article ID; a post never rated is missing. */
    suspend fun ratingsOf(articleIds: Collection<Long>): Map<Long, ArticleInterestEntity> =
        articleIds.distinct().chunked(ArticleSignalDao.MAX_IDS).flatMap { ratingsChunk(it) }.associateBy { it.articleId }

    @Query(
        "SELECT a.id AS articleId, a.title AS title, f.title AS feed, a.summary AS summary " +
            "FROM article a JOIN feed f ON f.id = a.feedId WHERE a.id IN (:articleIds)",
    )
    protected abstract suspend fun postsChunk(articleIds: List<Long>): List<PostForRating>

    /** What the model is shown of each of [articleIds]: its title, its feed's name and its summary. */
    suspend fun postsForRating(articleIds: Collection<Long>): List<PostForRating> =
        articleIds.distinct().chunked(ArticleSignalDao.MAX_IDS).flatMap { postsChunk(it) }

    @Query("SELECT COUNT(*) FROM article_interest")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) AS count, AVG(rating) AS mean FROM article_interest WHERE ratedAt >= :since")
    abstract suspend fun summarySince(since: Instant): RatingSummary

    /**
     * The mean of the ratings given at or after [since], when there are at least [minRatings]
     * of them; null otherwise. What an unrated post's N is taken from (`engine/Interest`).
     */
    suspend fun meanSince(since: Instant, minRatings: Int): Double? =
        summarySince(since).takeIf { it.count >= minRatings && it.count > 0 }?.mean

    @Query("DELETE FROM article_interest")
    abstract suspend fun deleteAll()

    /**
     * The titles of the posts the reader most recently Liked or saved, newest first, for the
     * taste profile. A post counts by the later of the two. Subscribed feeds only, like
     * everything learned about the reader: a post saved from discovery is the reading list's.
     */
    @Query(
        "SELECT a.title FROM engagement e JOIN article a ON a.id = e.articleId JOIN feed f ON f.id = a.feedId " +
            "WHERE f.status = 'SUBSCRIBED' AND (e.likedAt IS NOT NULL OR e.savedAt IS NOT NULL) " +
            "ORDER BY MAX(COALESCE(e.likedAt, 0), COALESCE(e.savedAt, 0)) DESC, a.id DESC LIMIT :limit",
    )
    abstract suspend fun recentLikedOrSavedTitles(limit: Int): List<String>
}

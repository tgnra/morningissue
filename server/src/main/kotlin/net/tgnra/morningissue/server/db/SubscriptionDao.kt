package net.tgnra.morningissue.server.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.SliderStep

/**
 * Subscriptions, tags and `subscription_tag`; every query names `user_id`, except the global
 * scans at the end, which `UserScopedQueryTest` allows by name. Owned by W-A
 * (`docs/server-design.md`, section 8).
 */
@Dao
abstract class SubscriptionDao {
    // --- Subscriptions -------------------------------------------------------------------------

    /** Returns the new row ID, or -1 when the user already has a subscription to the feed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(subscription: SubscriptionEntity): Long

    @Query("SELECT * FROM subscription WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun get(userId: Long, feedId: Long): SubscriptionEntity?

    /** The user's subscribed feeds (not those only discovered). */
    @Query("SELECT feed_id FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED' ORDER BY feed_id")
    abstract suspend fun subscribedFeedIds(userId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM subscription WHERE user_id = :userId AND status = 'SUBSCRIBED'")
    abstract suspend fun countSubscribed(userId: Long): Int

    /** Every feed of the user's, subscribed and discovered, with the shared feed, its status and the user's flag. */
    @Query("$DETAILS_SELECT WHERE s.user_id = :userId ORDER BY COALESCE(s.title_override, f.title) COLLATE NOCASE, f.id")
    abstract suspend fun details(userId: Long): List<SubscriptionRow>

    /** [details] for subscribed feeds only. */
    @Query(
        "$DETAILS_SELECT WHERE s.user_id = :userId AND s.status = 'SUBSCRIBED' " +
            "ORDER BY COALESCE(s.title_override, f.title) COLLATE NOCASE, f.id",
    )
    abstract suspend fun subscribedDetails(userId: Long): List<SubscriptionRow>

    @Query("$DETAILS_SELECT WHERE s.user_id = :userId AND s.feed_id = :feedId")
    abstract suspend fun detail(userId: Long, feedId: Long): SubscriptionRow?

    /** Makes a discovered feed a subscription as of [at]. Returns 1 when it was discovered, 0 otherwise. */
    @Query(
        "UPDATE subscription SET status = 'SUBSCRIBED', added_at = :at, discovered_at = NULL " +
            "WHERE user_id = :userId AND feed_id = :feedId AND status = 'DISCOVERED'",
    )
    abstract suspend fun promote(userId: Long, feedId: Long, at: Instant): Int

    /** Undoes [promote]: the feed holds discovery posts again, as it did before. */
    @Query(
        "UPDATE subscription SET status = 'DISCOVERED', added_at = :addedAt, discovered_at = :discoveredAt " +
            "WHERE user_id = :userId AND feed_id = :feedId",
    )
    abstract suspend fun revertToDiscovered(userId: Long, feedId: Long, addedAt: Instant, discoveredAt: Instant?)

    @Query("UPDATE subscription SET title_override = :title WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun updateTitle(userId: Long, feedId: Long, title: String?)

    @Query("UPDATE subscription SET slider_step = :step WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun updateSliderStep(userId: Long, feedId: Long, step: SliderStep)

    @Query("UPDATE subscription SET mute_until = :until WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun updateMuteUntil(userId: Long, feedId: Long, until: Instant?)

    /** Deletes the subscription; its `subscription_tag` rows go with it by cascade. */
    @Query("DELETE FROM subscription WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun delete(userId: Long, feedId: Long)

    // --- Unsubscribe clean-up (section 2.3) ----------------------------------------------------

    @Query("DELETE FROM engagement WHERE user_id = :userId AND articleId IN (SELECT id FROM article WHERE feedId = :feedId)")
    abstract suspend fun deleteEngagementForFeed(userId: Long, feedId: Long)

    @Query(
        "DELETE FROM edition_item WHERE articleId IN (SELECT id FROM article WHERE feedId = :feedId) " +
            "AND editionId IN (SELECT id FROM edition WHERE user_id = :userId)",
    )
    abstract suspend fun deletePlacementsForFeed(userId: Long, feedId: Long)

    @Query("DELETE FROM feed_flag WHERE user_id = :userId AND feed_id = :feedId")
    abstract suspend fun deleteFlag(userId: Long, feedId: Long)

    // --- Tags ----------------------------------------------------------------------------------

    @Query("SELECT * FROM tag WHERE user_id = :userId ORDER BY name COLLATE NOCASE")
    abstract suspend fun tags(userId: Long): List<TagEntity>

    @Query("SELECT * FROM tag WHERE user_id = :userId AND id = :tagId")
    abstract suspend fun tag(userId: Long, tagId: Long): TagEntity?

    @Query("SELECT * FROM tag WHERE user_id = :userId AND name = :name COLLATE NOCASE")
    abstract suspend fun tagByName(userId: Long, name: String): TagEntity?

    /** Every (feed, tag name) pair of the user's. */
    @Query(
        "SELECT st.feed_id AS feedId, t.name AS name FROM subscription_tag st JOIN tag t ON t.id = st.tag_id " +
            "WHERE st.user_id = :userId AND t.user_id = :userId",
    )
    abstract suspend fun tagLinks(userId: Long): List<TagLinkRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun link(link: SubscriptionTagEntity)

    @Query("DELETE FROM subscription_tag WHERE user_id = :userId AND feed_id = :feedId")
    protected abstract suspend fun unlinkAll(userId: Long, feedId: Long)

    /** Removes the user's tags no feed of theirs carries any more. */
    @Query("DELETE FROM tag WHERE user_id = :userId AND id NOT IN (SELECT tag_id FROM subscription_tag WHERE user_id = :userId)")
    abstract suspend fun deleteUnusedTags(userId: Long)

    /** The user's tag named [name] (matched without regard to case), created if need be. */
    @Transaction
    open suspend fun getOrCreateTag(userId: Long, name: String): TagEntity {
        val clean = name.trim()
        tagByName(userId, clean)?.let { return it }
        val id = insertTag(TagEntity(userId = userId, name = clean))
        return TagEntity(id = id, userId = userId, name = clean)
    }

    /** Replaces the feed's tags with [names]; blank names are ignored and unused tags removed. */
    @Transaction
    open suspend fun setFeedTags(userId: Long, feedId: Long, names: List<String>) {
        unlinkAll(userId, feedId)
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
            link(SubscriptionTagEntity(userId = userId, feedId = feedId, tagId = getOrCreateTag(userId, name).id))
        }
        deleteUnusedTags(userId)
    }

    // --- Global scans (allowed by name in UserScopedQueryTest) ---------------------------------

    /** Every (user, feed) subscription in SUBSCRIBED status: health checks and refresh reports go by it. */
    @Query("SELECT user_id, feed_id FROM subscription WHERE status = 'SUBSCRIBED' ORDER BY user_id, feed_id")
    abstract suspend fun subscribedPairs(): List<UserFeedPair>

    /**
     * Discovered subscriptions (feeds a user only looked into) older than [before] on which the
     * user saved nothing, Liked nothing and opened nothing since [before]: the discovery clean-up
     * deletes them.
     */
    @Query(
        "SELECT s.user_id, s.feed_id FROM subscription s WHERE s.status = 'DISCOVERED' " +
            "AND COALESCE(s.discovered_at, s.added_at) < :before " +
            "AND NOT EXISTS (SELECT 1 FROM engagement g JOIN article a ON a.id = g.articleId " +
            "WHERE g.user_id = s.user_id AND a.feedId = s.feed_id " +
            "AND (g.savedAt IS NOT NULL OR g.likedAt IS NOT NULL OR g.openedAt >= :before))",
    )
    abstract suspend fun expiredDiscovered(before: Instant): List<UserFeedPair>

    companion object {
        /** Shared projection for [SubscriptionRow]; append a WHERE clause naming `s.user_id`. */
        const val DETAILS_SELECT =
            "SELECT s.*, f.id AS f_id, f.url AS f_url, f.url_key AS f_url_key, f.siteUrl AS f_siteUrl, f.title AS f_title, " +
                "f.iconUrl AS f_iconUrl, f.medianIntervalHrs AS f_medianIntervalHrs, f.burstiness AS f_burstiness, " +
                "f.tier AS f_tier, f.etag AS f_etag, f.lastModified AS f_lastModified, f.addedAt AS f_addedAt, " +
                "f.last_attempt_at AS f_last_attempt_at, f.last_success_at AS f_last_success_at, " +
                "x.last_flag AS lastFlag, x.flag_dismissed_until AS flagDismissedUntil, " +
                "x.moved_acknowledged_at AS movedAcknowledgedAt, st.moved_at AS movedAt, st.error_since AS errorSince, " +
                "st.last_http_status AS lastHttpStatus, st.last_post_at AS lastPostAt, " +
                "COALESCE(st.error_streak, 0) AS errorStreak, st.last_repair_at AS lastRepairAt " +
                "FROM subscription s JOIN feed f ON f.id = s.feed_id " +
                "LEFT JOIN feed_flag x ON x.user_id = s.user_id AND x.feed_id = s.feed_id " +
                "LEFT JOIN feed_status st ON st.feed_id = s.feed_id"
    }
}

/** A user's subscription with the shared feed, its fetch record and the user's flag. */
data class SubscriptionRow(
    @Embedded val subscription: SubscriptionEntity,
    @Embedded(prefix = "f_") val feed: FeedEntity,
    val lastFlag: HealthFlag?,
    val flagDismissedUntil: Instant?,
    val movedAcknowledgedAt: Instant?,
    val movedAt: Instant?,
    val errorSince: Instant?,
    val lastHttpStatus: Int?,
    val lastPostAt: Instant?,
    val errorStreak: Int,
    val lastRepairAt: Instant?,
) {
    /** The title the user sees: their own, else the feed's. */
    val title: String get() = subscription.titleOverride ?: feed.title

    /** The feed moved while the user subscribed, after they last acknowledged a move. */
    val movePending: Boolean
        get() = movedAt != null && movedAt.isAfter(subscription.addedAt) &&
            (movedAcknowledgedAt == null || movedAcknowledgedAt.isBefore(movedAt))
}

/** See [SubscriptionDao.tagLinks]. */
data class TagLinkRow(
    @ColumnInfo(name = "feedId") val feedId: Long,
    val name: String,
)

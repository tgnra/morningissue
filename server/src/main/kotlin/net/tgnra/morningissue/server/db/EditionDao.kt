package net.tgnra.morningissue.server.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import java.time.LocalDate
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.server.db.EditionArticleDao.Companion.USER_ROW_SELECT

/**
 * Editions, their items and page views, per user: the app's `EditionDao` with `user_id`
 * threaded through. Owned by W-B. Edition IDs are global, so every query also names the user,
 * and an ID belonging to someone else reads as missing.
 */
@Dao
abstract class EditionDao {
    @Insert
    protected abstract suspend fun insertEdition(edition: EditionEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<EditionItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPageViews(views: List<EditionPageViewEntity>)

    /**
     * Stores a frozen edition and its items in one transaction and returns the edition ID.
     * The `editionId` of [items] is ignored and replaced. Fails if the user already has an
     * edition for the same date and kind. An [EditionEntity.id] other than 0 is kept (a
     * reprinted Extra edition keeps its ID).
     */
    @Transaction
    open suspend fun insertEditionWithItems(edition: EditionEntity, items: List<EditionItemEntity>): Long {
        val id = insertEdition(edition)
        insertItems(items.map { it.copy(editionId = id) })
        return id
    }

    @Query("DELETE FROM edition WHERE id = :editionId AND user_id = :userId")
    abstract suspend fun delete(userId: Long, editionId: Long)

    @Query("SELECT * FROM edition WHERE id = :editionId AND user_id = :userId")
    abstract suspend fun getById(userId: Long, editionId: Long): EditionEntity?

    @Query("SELECT * FROM edition WHERE user_id = :userId AND date = :date AND kind = :kind")
    abstract suspend fun getByDate(userId: Long, date: LocalDate, kind: EditionKind): EditionEntity?

    /** The edition of one of [kinds] with the latest cutoff, released or not. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND kind IN (:kinds) ORDER BY cutoffAt DESC LIMIT 1")
    abstract suspend fun getLatestByCutoffOf(userId: Long, kinds: List<EditionKind>): EditionEntity?

    /** Editions of [kind] built after [after], oldest first. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND kind = :kind AND cutoffAt > :after ORDER BY cutoffAt")
    abstract suspend fun getBuiltAfter(userId: Long, kind: EditionKind, after: Instant): List<EditionEntity>

    /**
     * The editions no later build has superseded yet: the newest Morning or Evening edition
     * and every Extra edition printed since.
     */
    @Transaction
    open suspend fun getPending(userId: Long): List<EditionEntity> {
        val scheduled = getLatestByCutoffOf(userId, listOf(EditionKind.MORNING, EditionKind.EVENING))
        return listOfNotNull(scheduled) + getBuiltAfter(userId, EditionKind.EXTRA, scheduled?.cutoffAt ?: Instant.EPOCH)
    }

    /** The newest edition of [kind] already released at [now]. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND kind = :kind AND releaseAt <= :now ORDER BY releaseAt DESC LIMIT 1")
    abstract suspend fun getLatestReleasedOf(userId: Long, kind: EditionKind, now: Instant): EditionEntity?

    /** Editions released in `(since, now]` that the user never opened, oldest first. */
    @Query(
        "SELECT * FROM edition WHERE user_id = :userId AND openedAt IS NULL AND releaseAt > :since AND releaseAt <= :now " +
            "ORDER BY releaseAt",
    )
    abstract suspend fun getUnopenedReleasedBetween(userId: Long, since: Instant, now: Instant): List<EditionEntity>

    /**
     * The untouched stories printed in [editionIds] (the index excluded) that have never been
     * on a While you were away page of an edition the user opened.
     */
    @Query(
        "$USER_ROW_SELECT WHERE a.id IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.editionId IN (:editionIds) AND i.kind != 'INDEX') " +
            "AND a.id NOT IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.kind = 'AWAY' AND e.openedAt IS NOT NULL) " +
            "AND g.openedAt IS NULL AND g.readAt IS NULL AND g.savedAt IS NULL AND g.likedAt IS NULL AND s.status = 'SUBSCRIBED'",
    )
    abstract suspend fun untouchedPrintedIn(userId: Long, editionIds: List<Long>): List<UserArticleRow>

    /** Articles printed (index excluded) in any of the user's editions built after [after]. */
    @Query(
        "SELECT DISTINCT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND e.cutoffAt > :after AND i.kind != 'INDEX'",
    )
    abstract suspend fun printedArticleIdsSince(userId: Long, after: Instant): List<Long>

    /** The newest edition already released at [now]. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND releaseAt <= :now ORDER BY releaseAt DESC LIMIT 1")
    abstract suspend fun getLatestReleased(userId: Long, now: Instant): EditionEntity?

    /** Newest first, released or not. */
    @Query("SELECT * FROM edition WHERE user_id = :userId ORDER BY releaseAt DESC LIMIT :limit")
    abstract suspend fun getRecent(userId: Long, limit: Int): List<EditionEntity>

    @Query("SELECT COALESCE(MAX(number), 0) FROM edition WHERE user_id = :userId")
    abstract suspend fun maxNumber(userId: Long): Int

    @Query("SELECT COUNT(*) FROM edition WHERE user_id = :userId")
    abstract suspend fun count(userId: Long): Int

    /**
     * Released editions with cover details, newest (highest number) first, numbered below
     * [beforeNumber].
     */
    @Query(
        "SELECT e.*, a.title AS leadTitle, a.imageUrl AS leadImageUrl, COALESCE(s.title_override, f.title) AS leadFeedTitle, " +
            "(SELECT COUNT(*) FROM edition_item c WHERE c.editionId = e.id AND c.kind != 'INDEX') AS storyCount " +
            "FROM edition e " +
            "LEFT JOIN edition_item li ON li.editionId = e.id AND li.kind = 'LEAD' " +
            "LEFT JOIN article a ON a.id = li.articleId " +
            "LEFT JOIN feed f ON f.id = a.feedId " +
            "LEFT JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
            "WHERE e.user_id = :userId AND e.releaseAt <= :now AND e.number < :beforeNumber " +
            "GROUP BY e.id ORDER BY e.number DESC, e.releaseAt DESC LIMIT :limit",
    )
    abstract suspend fun releasedCovers(userId: Long, now: Instant, beforeNumber: Int, limit: Int): List<UserCoverRow>

    @Query("$ITEM_SELECT WHERE i.editionId = :editionId AND e.user_id = :userId ORDER BY i.page, i.slot")
    abstract suspend fun getItems(userId: Long, editionId: Long): List<UserItemRow>

    /**
     * Articles printed (index excluded) in any of [editionIds], once per edition they ran in
     * where the user saw them (see [SEEN]).
     */
    @Query(
        "SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.editionId IN (:editionIds) AND i.kind != 'INDEX' AND $SEEN",
    )
    abstract suspend fun seenRuns(userId: Long, editionIds: List<Long>, seenMinMillis: Long): List<Long>

    /** Articles printed (index excluded) in any of [editionIds]. */
    @Query(
        "SELECT DISTINCT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.editionId IN (:editionIds) AND i.kind != 'INDEX'",
    )
    abstract suspend fun printedArticleIds(userId: Long, editionIds: List<Long>): List<Long>

    @Query(
        "INSERT OR IGNORE INTO edition_page_view (editionId, page, msVisible, firstSeenAt) " +
            "SELECT id, :page, 0, :at FROM edition WHERE id = :editionId AND user_id = :userId",
    )
    protected abstract suspend fun ensurePageView(userId: Long, editionId: Long, page: Int, at: Instant)

    @Query(
        "UPDATE edition_page_view SET msVisible = msVisible + :millis WHERE editionId = :editionId AND page = :page " +
            "AND editionId IN (SELECT id FROM edition WHERE user_id = :userId)",
    )
    protected abstract suspend fun addPageViewMillis(userId: Long, editionId: Long, page: Int, millis: Long)

    /**
     * Adds [millis] to the time page [page] of [editionId] has been on screen; the first call
     * for a page records [at] as when it was first shown. Nothing is stored for an edition the
     * user does not have, or for no time at all. Returns whether anything was stored.
     */
    @Transaction
    open suspend fun recordPageView(userId: Long, editionId: Long, page: Int, millis: Long, at: Instant): Boolean {
        if (millis <= 0 || getById(userId, editionId) == null) return false
        ensurePageView(userId, editionId, page, at)
        addPageViewMillis(userId, editionId, page, millis)
        return true
    }

    @Query(
        "SELECT v.* FROM edition_page_view v JOIN edition e ON e.id = v.editionId " +
            "WHERE e.user_id = :userId AND v.editionId = :editionId ORDER BY v.page",
    )
    abstract suspend fun getPageViews(userId: Long, editionId: Long): List<EditionPageViewEntity>

    /** Keeps the earlier of the stored first open and [at]; returns 1 when that changed it. */
    @Query(
        "UPDATE edition SET openedAt = :at, change_seq = :seq WHERE id = :editionId AND user_id = :userId " +
            "AND (openedAt IS NULL OR openedAt > :at)",
    )
    abstract suspend fun mergeOpened(userId: Long, editionId: Long, at: Instant, seq: Long): Int

    /** Keeps the earlier of the stored back-page time and [at]; returns 1 when that changed it. */
    @Query(
        "UPDATE edition SET finishedAt = :at, change_seq = :seq WHERE id = :editionId AND user_id = :userId " +
            "AND (finishedAt IS NULL OR finishedAt > :at)",
    )
    abstract suspend fun mergeFinished(userId: Long, editionId: Long, at: Instant, seq: Long): Int

    @Query("UPDATE edition SET change_seq = :seq WHERE id = :editionId AND user_id = :userId")
    abstract suspend fun setChangeSeq(userId: Long, editionId: Long, seq: Long)

    /** The newest [limit] editions already released at [now], newest first: what the manifest offers. */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND releaseAt <= :now ORDER BY releaseAt DESC LIMIT :limit")
    abstract suspend fun getRecentReleased(userId: Long, now: Instant, limit: Int): List<EditionEntity>

    /** Every edition released at [now], newest first (few hundred rows at most). */
    @Query("SELECT * FROM edition WHERE user_id = :userId AND releaseAt <= :now ORDER BY releaseAt DESC")
    abstract suspend fun getReleased(userId: Long, now: Instant): List<EditionEntity>

    /** When a From your reading list story was last printed, in an edition released by [now]; null if never. */
    @Query(
        "SELECT MAX(e.releaseAt) FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.user_id = :userId AND i.kind = 'READING_LIST' AND e.releaseAt <= :now",
    )
    abstract suspend fun lastReadingListAt(userId: Long, now: Instant): Instant?

    /** When the user last opened any edition; null if never. */
    @Query("SELECT MAX(openedAt) FROM edition WHERE user_id = :userId")
    abstract suspend fun lastOpenedAt(userId: Long): Instant?

    /** Editions whose reading state changed after [since], for the engagement delta. */
    @Query("SELECT id, openedAt, finishedAt FROM edition WHERE user_id = :userId AND change_seq > :since ORDER BY change_seq, id")
    abstract suspend fun changedSince(userId: Long, since: Long): List<UserEditionStateRow>

    /**
     * What an edition's bundle depends on besides its own rows: the edition's `change_seq`,
     * the highest engagement `change_seq` among its articles, and the newest stored body.
     */
    @Query(
        "SELECT e.change_seq AS editionSeq, " +
            "COALESCE((SELECT MAX(g.change_seq) FROM engagement g JOIN edition_item i ON i.articleId = g.articleId " +
            "WHERE i.editionId = e.id AND g.user_id = :userId), 0) AS engagementSeq, " +
            "COALESCE((SELECT MAX(c.storedAt) FROM article_content c JOIN edition_item i ON i.articleId = c.articleId " +
            "WHERE i.editionId = e.id), 0) AS contentAt, " +
            "(SELECT COUNT(*) FROM article_content c JOIN edition_item i ON i.articleId = c.articleId " +
            "WHERE i.editionId = e.id) AS contentCount " +
            "FROM edition e WHERE e.id = :editionId AND e.user_id = :userId",
    )
    abstract suspend fun bundleKey(userId: Long, editionId: Long): BundleKeyRow?

    /** The user an edition belongs to, for work that starts from an edition ID alone (warming Kobo pictures). */
    @Query("SELECT e.user_id FROM edition e WHERE e.id = :editionId")
    abstract suspend fun ownerOf(editionId: Long): Long?

    companion object {
        /**
         * Whether the run of `edition_item i` in `edition e` was seen: `e` predates page views,
         * or the page holding the story was on screen for at least `:seenMinMillis` in all.
         */
        const val SEEN =
            "(e.pageViewsTracked = 0 OR COALESCE((SELECT v.msVisible FROM edition_page_view v " +
                "WHERE v.editionId = i.editionId AND v.page = i.page), 0) >= :seenMinMillis)"

        /** Shared projection for [UserItemRow]; names `:userId`. Append a WHERE clause. */
        const val ITEM_SELECT =
            "SELECT i.editionId AS item_editionId, i.articleId AS item_articleId, i.page AS item_page, " +
                "i.section AS item_section, i.slot AS item_slot, i.kind AS item_kind, i.score AS item_score, " +
                "i.reason AS item_reason, a.*, COALESCE(s.title_override, f.title) AS feedTitle, f.iconUrl AS feedIconUrl, " +
                "s.status AS feedStatus, MAX(a.firstSeenAt, COALESCE(s.added_at, a.firstSeenAt)) AS seenAt, " +
                "g.openedAt AS openedAt, g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, " +
                "g.likedAt AS likedAt, g.editionsRunUnopened AS editionsRunUnopened, g.leadCount AS leadCount " +
                "FROM edition_item i JOIN edition e ON e.id = i.editionId JOIN article a ON a.id = i.articleId " +
                "JOIN feed f ON f.id = a.feedId " +
                "LEFT JOIN subscription s ON s.feed_id = a.feedId AND s.user_id = :userId " +
                "LEFT JOIN engagement g ON g.articleId = a.id AND g.user_id = :userId"
    }
}

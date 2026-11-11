package net.tgnra.morningissue.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.EditionKind

@Dao
abstract class EditionDao {
    @Insert
    protected abstract suspend fun insertEdition(edition: EditionEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<EditionItemEntity>)

    /**
     * Stores a frozen edition and its items in one transaction and returns the edition ID.
     * The `editionId` of [items] is ignored and replaced. Fails if an edition already exists
     * for the same date and kind.
     */
    @Transaction
    open suspend fun insertEditionWithItems(edition: EditionEntity, items: List<EditionItemEntity>): Long {
        val id = insertEdition(edition)
        insertItems(items.map { it.copy(editionId = id) })
        return id
    }

    @Query("DELETE FROM edition WHERE id = :editionId")
    abstract suspend fun delete(editionId: Long)

    @Query("SELECT * FROM edition WHERE id = :editionId")
    abstract suspend fun getById(editionId: Long): EditionEntity?

    @Query("SELECT * FROM edition WHERE id = :editionId")
    abstract fun observeById(editionId: Long): Flow<EditionEntity?>

    @Query("SELECT * FROM edition WHERE date = :date AND kind = :kind")
    abstract suspend fun getByDate(date: LocalDate, kind: EditionKind): EditionEntity?

    /** The edition with the latest cutoff, released or not; its cutoff starts the next edition. */
    @Query("SELECT * FROM edition ORDER BY cutoffAt DESC LIMIT 1")
    abstract suspend fun getLatestByCutoff(): EditionEntity?

    /** The edition of one of [kinds] with the latest cutoff, released or not. */
    @Query("SELECT * FROM edition WHERE kind IN (:kinds) ORDER BY cutoffAt DESC LIMIT 1")
    abstract suspend fun getLatestByCutoffOf(kinds: List<EditionKind>): EditionEntity?

    /** Editions of [kind] built after [after], oldest first. */
    @Query("SELECT * FROM edition WHERE kind = :kind AND cutoffAt > :after ORDER BY cutoffAt")
    abstract suspend fun getBuiltAfter(kind: EditionKind, after: Instant): List<EditionEntity>

    /**
     * The editions no later build has superseded yet: the newest Morning or Evening edition
     * and every Extra edition printed since. Their untouched stories do not count as ignored,
     * and their unopened runs have not been counted. An Extra edition supersedes nothing.
     */
    @Transaction
    open suspend fun getPending(): List<EditionEntity> {
        val scheduled = getLatestByCutoffOf(listOf(EditionKind.MORNING, EditionKind.EVENING))
        return listOfNotNull(scheduled) + getBuiltAfter(EditionKind.EXTRA, scheduled?.cutoffAt ?: Instant.EPOCH)
    }

    /** The newest edition of [kind] already released at [now]. */
    @Query("SELECT * FROM edition WHERE kind = :kind AND releaseAt <= :now ORDER BY releaseAt DESC LIMIT 1")
    abstract suspend fun getLatestReleasedOf(kind: EditionKind, now: Instant): EditionEntity?

    /**
     * Editions released in `(since, now]` that the reader never opened, oldest first: the
     * missed editions behind While you were away.
     */
    @Query("SELECT * FROM edition WHERE openedAt IS NULL AND releaseAt > :since AND releaseAt <= :now ORDER BY releaseAt")
    abstract suspend fun getUnopenedReleasedBetween(since: Instant, now: Instant): List<EditionEntity>

    /**
     * The untouched stories printed in [editionIds] (the index excluded) that have never been
     * on a While you were away page of an edition the reader opened: what the pull-out may
     * draw on. A pull-out in an edition that was itself missed does not use a story up.
     */
    @Query(
        "${ArticleDao.ROW_SELECT} WHERE a.id IN (SELECT articleId FROM edition_item WHERE editionId IN (:editionIds) AND kind != 'INDEX') " +
            "AND a.id NOT IN (SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE i.kind = 'AWAY' AND e.openedAt IS NOT NULL) " +
            "AND g.openedAt IS NULL AND g.readAt IS NULL AND g.savedAt IS NULL AND g.likedAt IS NULL AND f.status = 'SUBSCRIBED'",
    )
    abstract suspend fun untouchedPrintedIn(editionIds: List<Long>): List<ArticleRow>

    /** Articles printed (index excluded) in any edition built after [after]. */
    @Query(
        "SELECT DISTINCT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE e.cutoffAt > :after AND i.kind != 'INDEX'",
    )
    abstract suspend fun printedArticleIdsSince(after: Instant): List<Long>

    /** The newest edition already released at [now]. */
    @Query("SELECT * FROM edition WHERE releaseAt <= :now ORDER BY releaseAt DESC LIMIT 1")
    abstract suspend fun getLatestReleased(now: Instant): EditionEntity?

    /** Newest first, released or not. */
    @Query("SELECT * FROM edition ORDER BY releaseAt DESC LIMIT :limit")
    abstract suspend fun getRecent(limit: Int): List<EditionEntity>

    @Query("SELECT COALESCE(MAX(number), 0) FROM edition")
    abstract suspend fun maxNumber(): Int

    @Query("SELECT COUNT(*) FROM edition")
    abstract suspend fun count(): Int

    /** All editions with cover details, newest first, released or not. */
    @Query(
        "SELECT e.*, a.title AS leadTitle, a.imageUrl AS leadImageUrl, f.title AS leadFeedTitle, " +
            "(SELECT COUNT(*) FROM edition_item c WHERE c.editionId = e.id AND c.kind != 'INDEX') AS storyCount " +
            "FROM edition e " +
            "LEFT JOIN edition_item li ON li.editionId = e.id AND li.kind = 'LEAD' " +
            "LEFT JOIN article a ON a.id = li.articleId " +
            "LEFT JOIN feed f ON f.id = a.feedId " +
            "GROUP BY e.id ORDER BY e.releaseAt DESC",
    )
    abstract fun observeCovers(): Flow<List<EditionCoverRow>>

    @Query("$ITEM_SELECT WHERE i.editionId = :editionId ORDER BY i.page, i.slot")
    abstract suspend fun getItems(editionId: Long): List<EditionItemRow>

    @Query("$ITEM_SELECT WHERE i.editionId = :editionId ORDER BY i.page, i.slot")
    abstract fun observeItems(editionId: Long): Flow<List<EditionItemRow>>

    /**
     * Articles printed (index excluded) in any of [editionIds], once per edition they ran in
     * where the reader saw them: on a page on screen for at least [seenMinMillis] in all, or
     * in an edition from before page views were recorded (see [SEEN]).
     */
    @Query(
        "SELECT i.articleId FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE i.editionId IN (:editionIds) AND i.kind != 'INDEX' AND $SEEN",
    )
    abstract suspend fun seenRuns(editionIds: List<Long>, seenMinMillis: Long): List<Long>

    /** Articles printed (index excluded) in any of [editionIds]. */
    @Query("SELECT DISTINCT articleId FROM edition_item WHERE editionId IN (:editionIds) AND kind != 'INDEX'")
    abstract suspend fun printedArticleIds(editionIds: List<Long>): List<Long>

    /** Every placement of one article, across editions. */
    @Query("SELECT * FROM edition_item WHERE articleId = :articleId")
    abstract suspend fun getItemsForArticle(articleId: Long): List<EditionItemEntity>

    @Query(
        "SELECT a.feedId AS feedId, i.articleId AS articleId, i.editionId AS editionId, e.releaseAt AS ranAt, " +
            "g.openedAt AS openedAt, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
            "CASE WHEN $SEEN THEN 1 ELSE 0 END AS seen " +
            "FROM edition_item i JOIN edition e ON e.id = i.editionId JOIN article a ON a.id = i.articleId " +
            "JOIN feed f ON f.id = a.feedId LEFT JOIN engagement g ON g.articleId = i.articleId " +
            "WHERE i.kind != 'INDEX' AND e.releaseAt >= :since AND f.status = 'SUBSCRIBED'",
    )
    protected abstract suspend fun runsSince(since: Instant, seenMinMillis: Long): List<EditionRunRow>

    /**
     * Printed (non-index) stories of subscribed feeds in editions released since [since], with
     * what the reader did and whether the run was [EditionRunRow.seen] (its page on screen for
     * at least [seenMinMillis] in all, or an edition from before page views). A reading-list
     * post printed before its feed was subscribed counts from the day it is subscribed, like
     * the rest of that feed's dormant evidence.
     */
    open suspend fun getRunsSince(since: Instant, seenMinMillis: Long = RankingConfig.Default.pageSeenMinMillis): List<EditionRunRow> =
        runsSince(since, seenMinMillis)

    @Query("INSERT OR IGNORE INTO edition_page_view (editionId, page, msVisible, firstSeenAt) VALUES (:editionId, :page, 0, :at)")
    protected abstract suspend fun ensurePageView(editionId: Long, page: Int, at: Instant)

    @Query("UPDATE edition_page_view SET msVisible = msVisible + :millis WHERE editionId = :editionId AND page = :page")
    protected abstract suspend fun addPageViewMillis(editionId: Long, page: Int, millis: Long)

    /**
     * Adds [millis] to the time page [page] (1-based, as in `edition_item.page`) of [editionId]
     * has been on screen; the first call for a page records [at] as when it was first shown.
     * Nothing is stored for an edition that no longer exists, or for no time at all.
     */
    @Transaction
    open suspend fun recordPageView(editionId: Long, page: Int, millis: Long, at: Instant) {
        if (millis <= 0 || getById(editionId) == null) return
        ensurePageView(editionId, page, at)
        addPageViewMillis(editionId, page, millis)
    }

    @Query("SELECT * FROM edition_page_view WHERE editionId = :editionId ORDER BY page")
    abstract suspend fun getPageViews(editionId: Long): List<EditionPageViewEntity>

    /** Records the first time the reader opened the edition. */
    @Query("UPDATE edition SET openedAt = COALESCE(openedAt, :at) WHERE id = :editionId")
    abstract suspend fun markOpened(editionId: Long, at: Instant)

    /** Records the first time the reader reached the back page. */
    @Query("UPDATE edition SET finishedAt = COALESCE(finishedAt, :at) WHERE id = :editionId")
    abstract suspend fun markFinished(editionId: Long, at: Instant)

    /** When a From your reading list story was last printed, in an edition released by [now]; null if never. */
    @Query(
        "SELECT MAX(e.releaseAt) FROM edition_item i JOIN edition e ON e.id = i.editionId " +
            "WHERE i.kind = 'READING_LIST' AND e.releaseAt <= :now",
    )
    abstract suspend fun lastReadingListAt(now: Instant): Instant?

    /** When the reader last opened any edition; null if never. */
    @Query("SELECT MAX(openedAt) FROM edition")
    abstract suspend fun lastOpenedAt(): Instant?

    companion object {
        /**
         * Whether the run of `edition_item i` in `edition e` was seen: `e` predates page views,
         * or the page holding the story was on screen for at least `:seenMinMillis` in all.
         * A story is printed at most once per edition, so each run has exactly one page.
         */
        const val SEEN =
            "(e.pageViewsTracked = 0 OR COALESCE((SELECT v.msVisible FROM edition_page_view v " +
                "WHERE v.editionId = i.editionId AND v.page = i.page), 0) >= :seenMinMillis)"

        const val ITEM_SELECT =
            "SELECT i.editionId AS item_editionId, i.articleId AS item_articleId, i.page AS item_page, " +
                "i.section AS item_section, i.slot AS item_slot, i.kind AS item_kind, i.score AS item_score, " +
                "i.reason AS item_reason, a.*, f.title AS feedTitle, f.iconUrl AS feedIconUrl, f.status AS feedStatus, g.openedAt AS openedAt, " +
                "g.readFraction AS readFraction, g.readAt AS readAt, g.savedAt AS savedAt, g.likedAt AS likedAt, " +
                "g.editionsRunUnopened AS editionsRunUnopened, g.leadCount AS leadCount " +
                "FROM edition_item i JOIN article a ON a.id = i.articleId JOIN feed f ON f.id = a.feedId " +
                "LEFT JOIN engagement g ON g.articleId = a.id"
    }
}

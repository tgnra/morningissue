package net.tgnra.morningissue.client

import androidx.room3.withWriteTransaction
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import net.tgnra.morningissue.api.ArticleSummaryDto
import net.tgnra.morningissue.api.BundleArticleDto
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionSummaryDto
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedsResponse
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.data.db.ArticleContentEntity
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.ArticleFtsEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.EditionPageViewEntity
import net.tgnra.morningissue.data.db.EngagementEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.FeedTagEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.TagEntity
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ReadingTime

/**
 * Writes the server's answers into the cache, under the server's IDs. Each public function is
 * one write transaction.
 */
class CacheWriter(private val database: MorningIssueDatabase) {
    private val cache = database.cacheDao()
    private val feedDao = database.feedDao()
    private val articleDao = database.articleDao()
    private val editionDao = database.editionDao()
    private val healthDao = database.feedHealthDao()

    // --- Feeds ------------------------------------------------------------------------------

    /**
     * Makes the cache's feeds and tags those of `GET /feeds`. A feed the answer no longer has
     * is deleted with its posts, unless an edition places or the reader saved or Liked one of
     * them: then it stays, as a feed the reader does not subscribe to, so that those still show.
     */
    suspend fun applyFeeds(response: FeedsResponse) = database.withWriteTransaction {
        for (tag in response.tags) {
            cache.deleteTagClashingWith(tag.id, tag.name)
            cache.upsertTag(TagEntity(id = tag.id, name = tag.name, affinity = tag.affinity))
        }
        val tagIds = response.tags.associate { it.name.lowercase() to it.id }
        val listed = response.feeds.map { it.id }.toSet()
        for (feed in response.feeds) upsertFeedInTransaction(feed, tagIds)
        for (id in cache.feedIds()) {
            if (id in listed) continue
            if (cache.feedReferenced(id)) cache.setFeedStatus(id, FeedStatus.DISCOVERED) else feedDao.delete(id)
        }
        cache.deleteTagsExcept(response.tags.map { it.id })
    }

    /** One feed as a REST answer gave it (after adding, renaming, muting...). */
    suspend fun upsertFeed(feed: FeedDto) = database.withWriteTransaction { upsertFeedInTransaction(feed, null) }

    private suspend fun upsertFeedInTransaction(feed: FeedDto, tagIds: Map<String, Long>?) {
        val existing = feedDao.getById(feed.id)
        cache.deleteFeedClashingWith(feed.id, feed.url)
        cache.upsertFeed(
            FeedEntity(
                id = feed.id,
                url = feed.url,
                siteUrl = feed.siteUrl,
                title = feed.title,
                iconUrl = feed.iconUrl,
                medianIntervalHrs = feed.medianIntervalHrs,
                burstiness = existing?.burstiness,
                tier = feed.tier,
                affinity = feed.affinity,
                sliderStep = feed.sliderStep,
                muteUntil = feed.muteUntil,
                lastSyncAt = feed.lastSyncAt,
                addedAt = feed.addedAt,
                status = feed.status,
                discoveredAt = feed.discoveredAt,
            ),
        )
        cache.unlinkTags(feed.id)
        for (name in feed.tags) {
            val tagId = tagIds?.get(name.lowercase()) ?: database.tagDao().getOrCreate(name).id
            cache.linkTag(FeedTagEntity(feed.id, tagId))
        }
        healthDao.setFlag(feed.id, feed.flag)
    }

    /** A feed the cache holds no row for yet: a stand-in under its ID, until `GET /feeds` lists it. */
    private suspend fun ensureFeed(feedId: Long, title: String) {
        if (cache.existingFeedIds(listOf(feedId)).isNotEmpty()) return
        cache.upsertFeed(
            FeedEntity(
                id = feedId,
                url = "$PLACEHOLDER_URL$feedId",
                title = title,
                addedAt = Instant.EPOCH,
                status = FeedStatus.DISCOVERED,
            ),
        )
    }

    // --- Editions ---------------------------------------------------------------------------

    /** The back issues of `GET /editions`; their stories come with each bundle. */
    suspend fun upsertEditionSummaries(editions: List<EditionSummaryDto>) = database.withWriteTransaction {
        for (summary in editions) {
            cache.deleteEditionClashingWith(summary.id, summary.date, summary.kind)
            cache.upsertEdition(
                EditionEntity(
                    id = summary.id,
                    date = summary.date,
                    kind = summary.kind,
                    cutoffAt = summary.cutoffAt,
                    releaseAt = summary.releaseAt,
                    builtAt = summary.builtAt,
                    length = summary.length,
                    number = summary.number,
                    openedAt = summary.openedAt,
                    finishedAt = summary.finishedAt,
                    pageViewsTracked = summary.pageViewsTracked,
                ),
            )
        }
    }

    /** One edition's bundle: the edition, its pages in place of whatever the cache had, its stories and their engagement. */
    suspend fun storeBundle(bundle: EditionBundleDto) = database.withWriteTransaction {
        val existing = editionDao.getById(bundle.id)
        cache.deleteEditionClashingWith(bundle.id, bundle.date, bundle.kind)
        cache.upsertEdition(
            EditionEntity(
                id = bundle.id,
                date = bundle.date,
                kind = bundle.kind,
                cutoffAt = bundle.cutoffAt ?: existing?.cutoffAt ?: bundle.releaseAt,
                releaseAt = bundle.releaseAt,
                builtAt = bundle.builtAt ?: existing?.builtAt ?: bundle.releaseAt,
                length = bundle.length,
                number = bundle.number,
                openedAt = bundle.openedAt ?: existing?.openedAt,
                finishedAt = bundle.finishedAt,
                pageViewsTracked = bundle.pageViewsTracked ?: existing?.pageViewsTracked ?: false,
            ),
        )
        for ((key, article) in bundle.articles) {
            val id = key.toLongOrNull() ?: continue
            storeArticleInTransaction(id, article)
        }
        cache.deleteItems(bundle.id)
        val items = bundle.pages.flatMap { page ->
            page.items.filter { it.articleId.toString() in bundle.articles }.map { item ->
                EditionItemEntity(
                    editionId = bundle.id,
                    articleId = item.articleId,
                    page = page.page,
                    section = item.section,
                    slot = item.slot,
                    kind = item.kind,
                    score = 0.0,
                    reason = item.reason,
                )
            }
        }
        if (items.isNotEmpty()) cache.insertItems(items)
        bundle.pageViews?.forEach { view ->
            if (view.page >= 1) cache.upsertPageView(EditionPageViewEntity(bundle.id, view.page, view.ms, view.firstSeenAt))
        }
    }

    /** The saved shelf: its articles and their engagement. */
    suspend fun storeSaved(saved: SavedBundleDto) = database.withWriteTransaction {
        for ((key, article) in saved.articles) {
            val id = key.toLongOrNull() ?: continue
            storeArticleInTransaction(id, article)
        }
    }

    /** One article with its body (`GET /articles/{id}`, `POST .../fulltext`). */
    suspend fun storeArticle(articleId: Long, article: BundleArticleDto) = database.withWriteTransaction {
        storeArticleInTransaction(articleId, article)
    }

    private suspend fun storeArticleInTransaction(id: Long, article: BundleArticleDto) {
        val feedId = article.feedId ?: placeholderFeedId(article.feedTitle)
        ensureFeed(feedId, article.feedTitle)
        val existing = articleDao.getById(id)
        val guid = article.guid ?: existing?.guid ?: "$PLACEHOLDER_GUID$id"
        val entity = ArticleEntity(
            id = id,
            feedId = feedId,
            guid = guid,
            url = article.url,
            title = article.title,
            summary = article.summary,
            imageUrl = article.imageUrl,
            author = article.author,
            publishedAt = article.publishedAt,
            firstSeenAt = article.firstSeenAt ?: existing?.firstSeenAt ?: article.publishedAt,
            wordCount = article.wordCount,
            feedWordCount = article.feedWordCount ?: existing?.feedWordCount ?: 0,
            contentHash = existing?.contentHash,
        )
        cache.deleteArticleClashingWith(id, feedId, guid)
        cache.upsertArticle(entity)

        // An empty body is no body: one the cache already has (fetched, or trimmed and fetched again) stays.
        val blocks = (article.blocks as? JsonArray)?.takeIf { it.isNotEmpty() }
        val plain = if (blocks != null) {
            val json = blocks.toString()
            val text = ContentBlocks.plainText(ContentBlocks.decode(json))
            cache.upsertContent(ArticleContentEntity(id, json, article.contentSource ?: ContentSource.FEED, Instant.now()))
            text
        } else if (existing != null) {
            cache.indexedBody(id)
        } else {
            null
        }
        cache.deleteFts(id)
        cache.insertFts(ArticleFtsEntity(id, entity.title, entity.summary.orEmpty(), entity.author.orEmpty(), plain.orEmpty()))

        val engagement = article.engagement
        val local = database.engagementDao().get(id)
        cache.upsertEngagement(
            EngagementEntity(
                articleId = id,
                openedAt = engagement.openedAt,
                readFraction = engagement.readFraction,
                readAt = engagement.readAt,
                savedAt = engagement.savedAt,
                likedAt = engagement.likedAt,
                editionsRunUnopened = local?.editionsRunUnopened ?: 0,
                leadCount = local?.leadCount ?: 0,
            ),
        )
    }

    /**
     * Posts the server listed without their bodies (a source page, search results): stored if
     * the cache does not have them, so that they open, and left alone if it does.
     */
    suspend fun storeSummaries(articles: List<ArticleSummaryDto>) = database.withWriteTransaction {
        for (article in articles) {
            if (cache.articleExists(article.articleId)) continue
            ensureFeed(article.feedId, article.feedTitle)
            val entity = ArticleEntity(
                id = article.articleId,
                feedId = article.feedId,
                guid = "$PLACEHOLDER_GUID${article.articleId}",
                url = article.url,
                title = article.title,
                summary = article.summary,
                imageUrl = article.imageUrl,
                author = article.author,
                publishedAt = article.publishedAt,
                firstSeenAt = article.firstSeenAt,
                wordCount = article.readMinutes * ReadingTime.WORDS_PER_MINUTE,
            )
            cache.deleteArticleClashingWith(entity.id, entity.feedId, entity.guid)
            cache.upsertArticle(entity)
            cache.deleteFts(entity.id)
            cache.insertFts(ArticleFtsEntity(entity.id, entity.title, entity.summary.orEmpty(), entity.author.orEmpty(), ""))
        }
    }

    // --- Engagement -------------------------------------------------------------------------

    /** `GET /engagement`: rows changed on the server (by a Kobo, or another phone) since the cursor. */
    suspend fun applyEngagement(delta: EngagementDeltaResponse) = database.withWriteTransaction {
        for (row in delta.engagement) {
            if (!cache.articleExists(row.articleId)) continue
            val local = database.engagementDao().get(row.articleId)
            cache.upsertEngagement(
                EngagementEntity(
                    articleId = row.articleId,
                    openedAt = row.openedAt,
                    readFraction = row.readFraction,
                    readAt = row.readAt,
                    savedAt = row.savedAt,
                    likedAt = row.likedAt,
                    editionsRunUnopened = local?.editionsRunUnopened ?: 0,
                    leadCount = local?.leadCount ?: 0,
                ),
            )
        }
        for (edition in delta.editions) {
            if (cache.editionExists(edition.id)) cache.setEditionState(edition.id, edition.openedAt, edition.finishedAt)
        }
    }

    companion object {
        /** The URL of a stand-in feed row; never fetched, since the phone fetches no feed. */
        const val PLACEHOLDER_URL = "morningissue:feed/"
        const val PLACEHOLDER_GUID = "morningissue:article/"

        /** A stand-in feed for an article that came without its feed's ID: one per feed title, below every real ID. */
        fun placeholderFeedId(title: String): Long = -1L - (title.hashCode().toLong() and 0x7fffffffL)
    }
}

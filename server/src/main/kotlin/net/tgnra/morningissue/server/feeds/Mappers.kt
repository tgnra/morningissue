package net.tgnra.morningissue.server.feeds

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ArticleSummaryDto
import net.tgnra.morningissue.api.BundleArticleDto
import net.tgnra.morningissue.api.BundleEngagementDto
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.TagDto
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.ReadingTime
import net.tgnra.morningissue.server.db.ArticleContentEntity
import net.tgnra.morningissue.server.db.SubscriptionRow
import net.tgnra.morningissue.server.db.TagEntity
import net.tgnra.morningissue.server.db.UserArticleRow

/** Later than any stored time, and still representable in epoch milliseconds: "before" with no bound. */
internal val END_OF_TIME: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)

/** Most items a list endpoint answers with at once. */
internal const val MAX_LIMIT = 200

/**
 * The flag this user's desk shows for the feed now: the stored flag unless dismissed, with a move
 * the user has not acknowledged (`feed_status.moved_at` after `feed_flag.moved_acknowledged_at`)
 * showing as Moved unless a more urgent flag stands.
 */
internal fun SubscriptionRow.visibleFlag(now: Instant): HealthFlag? {
    val storedFlag = lastFlag?.takeUnless { it == HealthFlag.MOVED && !movePending }
    val stored = FeedHealthRules.visibleFlag(storedFlag, flagDismissedUntil, now)
    if (!movePending) return stored
    if (stored == null) return HealthFlag.MOVED
    val priority = FeedHealthRules.PRIORITY
    return if (priority.indexOf(HealthFlag.MOVED) < priority.indexOf(stored)) HealthFlag.MOVED else stored
}

internal fun SubscriptionRow.toDto(tags: List<String>, now: Instant): FeedDto = FeedDto(
    id = feed.id,
    url = feed.url,
    siteUrl = feed.siteUrl,
    title = title,
    iconUrl = feed.iconUrl,
    tier = feed.tier,
    medianIntervalHrs = feed.medianIntervalHrs,
    affinity = subscription.affinity,
    sliderStep = subscription.sliderStep,
    muteUntil = subscription.muteUntil,
    tags = tags.sortedBy { it.lowercase() },
    status = subscription.status,
    addedAt = subscription.addedAt,
    discoveredAt = subscription.discoveredAt,
    lastSyncAt = feed.lastSuccessAt,
    flag = visibleFlag(now),
)

internal fun TagEntity.toDto(): TagDto = TagDto(id = id, name = name, affinity = affinity)

internal fun UserArticleRow.toSummary(snippet: String? = null): ArticleSummaryDto = ArticleSummaryDto(
    articleId = article.id,
    feedId = article.feedId,
    feedTitle = feedTitle,
    feedIconUrl = feedIconUrl,
    title = article.title,
    summary = article.summary,
    imageUrl = article.imageUrl,
    author = article.author,
    url = article.url,
    publishedAt = article.publishedAt,
    firstSeenAt = article.firstSeenAt,
    readMinutes = ReadingTime.minutes(article.wordCount),
    isOpened = openedAt != null,
    isRead = readAt != null,
    isLiked = likedAt != null,
    isSaved = savedAt != null,
    snippet = snippet,
    feedSubscribed = feedStatus == FeedStatus.SUBSCRIBED,
)

/** The bundle's article object, with the phone's optional fields, as `GET /articles/{id}` answers it. */
internal fun UserArticleRow.toBundleArticle(content: ArticleContentEntity?): BundleArticleDto = BundleArticleDto(
    feedTitle = feedTitle,
    title = article.title,
    author = article.author,
    url = article.url,
    publishedAt = article.publishedAt,
    summary = article.summary,
    imageUrl = article.imageUrl,
    wordCount = article.wordCount,
    blocks = blocksOf(content?.blocksJson),
    engagement = BundleEngagementDto(
        openedAt = openedAt,
        readFraction = readFraction ?: 0f,
        readAt = readAt,
        savedAt = savedAt,
        likedAt = likedAt,
    ),
    feedId = article.feedId,
    guid = article.guid,
    firstSeenAt = article.firstSeenAt,
    feedWordCount = article.feedWordCount,
    contentSource = content?.source,
)

/** Stored block JSON as a JSON value; `[]` when there is none or it cannot be read. */
private fun blocksOf(json: String?): JsonElement =
    json?.let { runCatching { ApiJson.json.parseToJsonElement(it) }.getOrNull() } ?: JsonArray(emptyList())

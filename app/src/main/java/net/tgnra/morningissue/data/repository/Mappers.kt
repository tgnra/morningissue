package net.tgnra.morningissue.data.repository

import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.EditionCoverRow
import net.tgnra.morningissue.data.db.EditionItemRow
import net.tgnra.morningissue.data.db.FeedWithDetails
import net.tgnra.morningissue.data.db.TagEntity
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.ReadingTime
import net.tgnra.morningissue.model.Tag

internal fun FeedWithDetails.toModel(): Feed = Feed(
    id = feed.id,
    url = feed.url,
    siteUrl = feed.siteUrl,
    title = feed.title,
    iconUrl = feed.iconUrl,
    tier = feed.tier,
    medianIntervalHours = feed.medianIntervalHrs,
    burstiness = feed.burstiness,
    affinity = feed.affinity,
    sliderStep = feed.sliderStep,
    tags = tags.map { it.name }.sortedBy { it.lowercase() },
    notify = feed.notify,
    muteUntil = feed.muteUntil,
    lastSyncAt = feed.lastSyncAt,
    addedAt = feed.addedAt,
    healthFlag = health?.lastFlag,
)

internal fun TagEntity.toModel(): Tag = Tag(id = id, name = name, affinity = affinity)

internal fun ArticleRow.toSummary(snippet: String? = null): ArticleSummary = ArticleSummary(
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
)

internal fun EditionItemRow.toStory(): EditionStory = EditionStory(
    articleId = row.article.id,
    feedId = row.article.feedId,
    feedTitle = row.feedTitle,
    feedIconUrl = row.feedIconUrl,
    title = row.article.title,
    summary = row.article.summary,
    imageUrl = row.article.imageUrl,
    author = row.article.author,
    url = row.article.url,
    publishedAt = row.article.publishedAt,
    readMinutes = ReadingTime.minutes(row.article.wordCount),
    kind = item.kind,
    slot = item.slot,
    score = item.score,
    reason = item.reason,
    isOpened = row.openedAt != null,
    isRead = row.readAt != null,
    isLiked = row.likedAt != null,
    isSaved = row.savedAt != null,
)

internal fun EditionCoverRow.toSummary(): EditionSummary = EditionSummary(
    id = edition.id,
    date = edition.date,
    kind = edition.kind,
    number = edition.number,
    length = edition.length,
    releaseAt = edition.releaseAt,
    leadHeadline = leadTitle,
    leadImageUrl = leadImageUrl,
    leadSource = leadFeedTitle,
    storyCount = storyCount,
    isRead = edition.finishedAt != null,
)

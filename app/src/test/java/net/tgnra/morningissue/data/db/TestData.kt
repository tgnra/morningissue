package net.tgnra.morningissue.data.db

import java.time.Instant

internal val T0: Instant = Instant.parse("2026-11-10T06:30:00Z")

internal fun feed(url: String = "https://example.com/feed.xml", title: String = "Example") =
    FeedEntity(url = url, title = title, addedAt = T0)

internal fun article(
    feedId: Long,
    guid: String,
    title: String = "Title $guid",
    summary: String? = null,
    firstSeenAt: Instant = T0,
    publishedAt: Instant = firstSeenAt,
) = ArticleEntity(
    feedId = feedId,
    guid = guid,
    url = "https://example.com/$guid",
    title = title,
    summary = summary,
    publishedAt = publishedAt,
    firstSeenAt = firstSeenAt,
)

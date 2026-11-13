@file:UseSerializers(IsoInstantSerializer::class)

package net.tgnra.morningissue.api

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import net.tgnra.morningissue.model.CadenceTier

// 4.7 Discovery: the catalogue, profile, deck and live headlines all run on the server.

/** A recent headline of a suggested feed, from the catalogue or a live fetch. */
@Serializable
data class HeadlineDto(
    val title: String,
    val url: String? = null,
    val publishedAt: Instant? = null,
    val wordCount: Int? = null,
)

/** One card of the week's deck: a catalogue feed, why it is suggested, and three headlines. */
@Serializable
data class CardDto(
    val url: String,
    /** `FeedUrls.normalize(url)`; verdicts are kept by it. */
    val urlKey: String,
    val siteUrl: String? = null,
    val host: String? = null,
    val title: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val tier: CadenceTier? = null,
    val postsPerWeek: Double? = null,
    /** Share of posts that carry their full text, 0..1. */
    val fullTextShare: Double? = null,
    /** The reason line. */
    val reason: String,
    val headlines: List<HeadlineDto> = emptyList(),
)

/** `GET /discovery/deck` and `POST /discovery/topics`: the week's undecided cards. */
@Serializable
data class DeckResponse(
    val cards: List<CardDto>,
    /** A cold start: the user should pick topics first (`GET /discovery/topics`). */
    val needsTopics: Boolean,
    /** The ISO week of the deck, such as `2026-W46`. */
    val week: String,
)

/** A catalogue cluster offered as a topic. */
@Serializable
data class TopicDto(
    val clusterId: Int,
    val label: String,
)

/** `GET /discovery/topics`. */
@Serializable
data class TopicsResponse(
    val topics: List<TopicDto>,
)

/** `POST /discovery/topics`: the topics picked on a cold start. */
@Serializable
data class TopicsRequest(
    val clusterIds: List<Int>,
)

/** What a live fetch of a suggested feed found. */
enum class LiveState { FETCHED, DEAD, UNREACHABLE }

/** `GET /discovery/live?url=`: a suggested feed's latest headlines (`CandidateFetcher`, cached 24 hours). */
@Serializable
data class LiveResponse(
    val headlines: List<HeadlineDto>,
    val state: LiveState,
)

/** A decision on a card. */
enum class CardVerdict { PASSED, SUBSCRIBED }

/** `POST /discovery/verdicts`. `DELETE /discovery/verdicts?url=` undoes it, removing a subscription it made. */
@Serializable
data class VerdictRequest(
    val url: String,
    val verdict: CardVerdict,
)

/** `POST /discovery/verdicts`' answer: the new subscription when the verdict was SUBSCRIBED. */
@Serializable
data class VerdictResponse(
    val feed: FeedDto? = null,
)

/** A feed the user passed on. */
@Serializable
data class PassedFeedDto(
    val urlKey: String,
    val url: String,
    val title: String,
    val passedAt: Instant,
)

/** `GET /discovery/passed`. */
@Serializable
data class PassedResponse(
    val passed: List<PassedFeedDto>,
)

/** `POST /discovery/open`: store a suggested feed as DISCOVERED, with one post, for reading a headline. */
@Serializable
data class OpenDiscoveredRequest(
    val feedUrl: String,
    val articleUrl: String,
)

/** `POST /discovery/open`'s answer: the post to open. */
@Serializable
data class OpenDiscoveredResponse(
    val articleId: Long,
    val feedId: Long,
)

package net.tgnra.morningissue.client

import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import net.tgnra.morningissue.api.CardDto
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.HeadlineDto
import net.tgnra.morningissue.api.LiveState
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.CatalogueCluster
import net.tgnra.morningissue.engine.CatalogueFeed
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.engine.DeckCard

/** One card: the suggestion and its headlines. */
class Suggestion(val card: DeckCard, val headlines: List<CatalogueHeadline>) {
    val feed: CatalogueFeed get() = card.feed
}

/** What the discovery screen has to show. */
sealed interface DeckState {
    /** The server has no suggestions to give, or cannot be reached. */
    data object Unavailable : DeckState

    /** A cold start: pick some topics first. */
    data class NeedsTopics(val topics: List<CatalogueCluster>) : DeckState

    /** The week's cards still undecided, in order; [total] is the deck's size as far as is known. */
    data class Cards(val suggestions: List<Suggestion>, val total: Int, val topics: List<CatalogueCluster>) : DeckState

    /** Every card of the week's deck is decided. */
    data class Finished(val total: Int, val topics: List<CatalogueCluster>) : DeckState
}

/** A decision on a card, kept so that Undo can take it back. */
sealed interface Decision {
    val feed: CatalogueFeed

    data class Passed(override val feed: CatalogueFeed) : Decision

    data class Subscribed(override val feed: CatalogueFeed, val feedId: Long?) : Decision
}

data class PassedFeed(val urlKey: String, val url: String, val title: String, val passedAt: Instant)

/** A suggested feed's latest posts, fetched by the server. */
sealed interface LiveHeadlines {
    data class Fetched(val headlines: List<CatalogueHeadline>) : LiveHeadlines

    /** The feed is gone: the card is dropped. */
    data object Dead : LiveHeadlines

    data object Unreachable : LiveHeadlines
}

/** Find sources: the server's suggestions, and the reader's decisions on them. */
interface DiscoveryRepository {
    /** The week's deck; with [topics], after the reader picked them. */
    suspend fun deck(topics: Set<Int>? = null): DeckState

    suspend fun pass(feed: CatalogueFeed): Decision.Passed

    suspend fun subscribe(feed: CatalogueFeed): Decision.Subscribed

    suspend fun undo(decision: Decision)

    /** Whether undoing [decision] loses posts that are only kept because of it. */
    suspend fun undoLosesPosts(decision: Decision): Boolean = false

    fun observePassed(): Flow<List<PassedFeed>>

    suspend fun unpass(urlKey: String)

    /** Stores [headline] as a post of a discovered feed and returns its article ID, for the reader. */
    suspend fun openPost(feed: CatalogueFeed, headline: CatalogueHeadline): Long?

    suspend fun live(url: String): LiveHeadlines
}

/**
 * [DiscoveryRepository] on the server (`/discovery/...`): the catalogue, the profile and the deck
 * all live there. Nothing here throws but cancellation; a server that cannot be reached is an
 * unavailable deck, an unreachable feed, or a decision not taken.
 */
class ServerDiscoveryRepository(
    database: MorningIssueDatabase,
    private val connection: ServerConnection,
) : DiscoveryRepository {
    private val writer = CacheWriter(database)
    private val passed = MutableStateFlow<List<PassedFeed>?>(null)
    private var topics: List<CatalogueCluster> = emptyList()
    private var total = 0

    override suspend fun deck(topics: Set<Int>?): DeckState {
        val answer = online {
            connection.call { api -> if (topics != null) api.chooseTopics(topics.toList()) else api.deck() }
        } ?: return DeckState.Unavailable
        return stateOf(answer)
    }

    private suspend fun stateOf(deck: DeckResponse): DeckState {
        if (topics.isEmpty()) {
            topics = online { connection.call { it.topics() }.topics.map { CatalogueCluster(it.clusterId, it.label, FloatArray(0)) } }.orEmpty()
        }
        if (deck.needsTopics) return DeckState.NeedsTopics(topics)
        if (deck.cards.isEmpty()) return DeckState.Finished(total, topics)
        total = maxOf(total, deck.cards.size)
        return DeckState.Cards(deck.cards.map { it.toSuggestion() }, total, topics)
    }

    override suspend fun pass(feed: CatalogueFeed): Decision.Passed {
        online { connection.call { it.decide(feed.url, CardVerdict.PASSED) } }
        return Decision.Passed(feed)
    }

    override suspend fun subscribe(feed: CatalogueFeed): Decision.Subscribed {
        val created = online { connection.call { it.decide(feed.url, CardVerdict.SUBSCRIBED) }.feed }
        created?.let { writer.upsertFeed(it) }
        return Decision.Subscribed(feed, created?.id)
    }

    override suspend fun undo(decision: Decision) {
        online {
            connection.call { api ->
                api.undoVerdict(decision.feed.url)
                // Undoing a subscription removes it on the server; the cache follows.
                if (decision is Decision.Subscribed) writer.applyFeeds(api.feeds())
            }
        }
    }

    override fun observePassed(): Flow<List<PassedFeed>> = flow {
        refreshPassed()
        emitAll(passed.filterNotNull())
    }

    private suspend fun refreshPassed() {
        val list = online { connection.call { it.passed() }.passed.map { PassedFeed(it.urlKey, it.url, it.title, it.passedAt) } }
        passed.value = list ?: passed.value ?: emptyList()
    }

    override suspend fun unpass(urlKey: String) {
        val feed = passed.value?.firstOrNull { it.urlKey == urlKey } ?: return
        online { connection.call { it.undoVerdict(feed.url) } }
        refreshPassed()
    }

    override suspend fun openPost(feed: CatalogueFeed, headline: CatalogueHeadline): Long? {
        val articleUrl = headline.url ?: return null
        return online {
            connection.call { api ->
                val opened = api.openDiscovered(OpenDiscoveredRequest(feed.url, articleUrl))
                writer.storeArticle(opened.articleId, api.article(opened.articleId).article)
                opened.articleId
            }
        }
    }

    override suspend fun live(url: String): LiveHeadlines {
        val answer = online { connection.call { it.live(url) } } ?: return LiveHeadlines.Unreachable
        return when (answer.state) {
            LiveState.FETCHED -> LiveHeadlines.Fetched(answer.headlines.map { it.toHeadline() })
            LiveState.DEAD -> LiveHeadlines.Dead
            LiveState.UNREACHABLE -> LiveHeadlines.Unreachable
        }
    }

    private suspend fun <T> online(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        null
    }

    private fun HeadlineDto.toHeadline() = CatalogueHeadline(title, url, publishedAt, wordCount)

    private fun CardDto.toSuggestion(): Suggestion {
        val feed = CatalogueFeed(
            id = 0,
            url = url,
            urlKey = urlKey,
            siteUrl = siteUrl,
            host = host,
            title = title,
            description = description,
            iconUrl = iconUrl,
            language = null,
            tier = tier,
            medianIntervalHours = postsPerWeek?.takeIf { it > 0 }?.let { 168.0 / it },
            postsPerWeek = postsPerWeek,
            fullTextShare = fullTextShare,
            lastPostAt = null,
            clusterId = null,
            categories = emptyList(),
            source = null,
            quality = 0.0,
            centroid = null,
        )
        return Suggestion(DeckCard(feed, score = 0.0, reason = reason), headlines.map { it.toHeadline() })
    }
}

package net.tgnra.morningissue.server.feeds.discovery

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.api.CardDto
import net.tgnra.morningissue.api.CardVerdict
import net.tgnra.morningissue.api.DeckResponse
import net.tgnra.morningissue.api.HeadlineDto
import net.tgnra.morningissue.api.LiveResponse
import net.tgnra.morningissue.api.LiveState
import net.tgnra.morningissue.api.OpenDiscoveredRequest
import net.tgnra.morningissue.api.OpenDiscoveredResponse
import net.tgnra.morningissue.api.PassedFeedDto
import net.tgnra.morningissue.api.PassedResponse
import net.tgnra.morningissue.api.TopicDto
import net.tgnra.morningissue.api.TopicsRequest
import net.tgnra.morningissue.api.TopicsResponse
import net.tgnra.morningissue.api.VerdictRequest
import net.tgnra.morningissue.api.VerdictResponse
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.CatalogueCluster
import net.tgnra.morningissue.engine.CatalogueFeed
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.engine.ClusterTally
import net.tgnra.morningissue.engine.DeckCard
import net.tgnra.morningissue.engine.Discovery
import net.tgnra.morningissue.engine.DiscoveryConfig
import net.tgnra.morningissue.engine.DiscoveryProfile
import net.tgnra.morningissue.engine.ProfileFeed
import net.tgnra.morningissue.engine.ProfileNegative
import net.tgnra.morningissue.engine.ProfilePost
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.DiscoveryVerdictEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SignalDao
import net.tgnra.morningissue.server.feeds.DefaultFeedService
import net.tgnra.morningissue.server.feeds.Subscriptions
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.DiscoveryService
import net.tgnra.morningissue.sync.CandidateFetcher
import net.tgnra.morningissue.sync.CandidateResult
import net.tgnra.morningissue.sync.LiveCandidate
import net.tgnra.morningissue.sync.ParsedItem

/** One card: the suggestion and the catalogue's headlines for it. */
class Suggestion(val card: DeckCard, val headlines: List<CatalogueHeadline>) {
    val feed: CatalogueFeed get() = card.feed
}

/** What discovery has to show a user. */
sealed interface DeckState {
    /** No catalogue in this build, or one the server cannot read. */
    data object Unavailable : DeckState

    /** A cold start (nothing to go on): pick some topics first. */
    data class NeedsTopics(val topics: List<CatalogueCluster>) : DeckState

    /** The week's cards still undecided, in order; [total] is the deck's size. */
    data class Cards(val suggestions: List<Suggestion>, val total: Int, val topics: List<CatalogueCluster>) : DeckState

    /** Every card of the week's deck is decided. */
    data class Finished(val total: Int, val topics: List<CatalogueCluster>) : DeckState
}

/** The week's deck, kept in `user_state` so that discovery is a finite set of cards (the app's `SavedDeck`). */
@Serializable
data class SavedDeck(
    /** ISO week, such as "2026-W46". */
    val week: String,
    /** The catalogue's `built_at`, in epoch milliseconds; 0 when it has none. */
    val catalogueBuiltAt: Long,
    val topics: List<Int> = emptyList(),
    val cards: List<SavedCard> = emptyList(),
)

@Serializable
data class SavedCard(
    val urlKey: String,
    val reason: String,
    val exploration: Boolean = false,
    val score: Double = 0.0,
)

/** How to undo a Subscribe on a card, kept in `user_state` until the verdict is undone or replaced. */
@Serializable
internal data class SubscribeUndo(
    val feedId: Long,
    val created: Boolean,
    val previousAddedAt: Long? = null,
    val previousDiscoveredAt: Long? = null,
)

/**
 * Discovery on the server (4.7), ported from the app's `DiscoveryRepository` with `userId`
 * threaded through: the user's profile from what is stored, the week's deck from the bundled
 * catalogue ([CatalogueStore]) and the pure ranking ([Discovery]), the user's verdicts, live
 * headlines ([CandidateFetcher], never metered on a server) and the posts a user reads from a
 * suggested feed, stored under a DISCOVERED subscription.
 */
class DefaultDiscoveryService(
    private val database: ServerDatabase,
    private val catalogues: CatalogueStore,
    private val subscriptions: Subscriptions,
    private val feeds: DefaultFeedService,
    private val candidates: CandidateFetcher,
    private val clock: Clock,
    /** The user's time zone, for the deck's week. */
    private val zoneOf: suspend (userId: Long) -> ZoneId = { ZoneOffset.UTC },
    private val config: DiscoveryConfig = DiscoveryConfig.Default,
    ranking: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DiscoveryService {
    private val articleDao = database.articleDao()
    private val subscriptionDao = database.subscriptionDao()
    private val discoveryDao = database.discoveryDao()
    private val signalDao = database.signalDao()
    private val affinity = Affinity(ranking)
    private val json = Json { ignoreUnknownKeys = true }

    // --- The deck ------------------------------------------------------------------------------

    override suspend fun deck(userId: Long): DeckResponse = response(userId, deckState(userId))

    override suspend fun chooseTopics(userId: Long, request: TopicsRequest): DeckResponse =
        response(userId, deckState(userId, request.clusterIds.toSet()))

    override suspend fun topics(userId: Long): TopicsResponse {
        val catalogue = (catalogues.load() as? CatalogueStatus.Ready)?.catalogue ?: return TopicsResponse(emptyList())
        return TopicsResponse(topicChoices(catalogue).map { TopicDto(it.id, it.label) })
    }

    private suspend fun response(userId: Long, state: DeckState): DeckResponse {
        val week = weekOf(clock.instant(), zoneOf(userId))
        return when (state) {
            DeckState.Unavailable -> DeckResponse(emptyList(), needsTopics = false, week = week)
            is DeckState.NeedsTopics -> DeckResponse(emptyList(), needsTopics = true, week = week)
            is DeckState.Finished -> DeckResponse(emptyList(), needsTopics = false, week = week)
            is DeckState.Cards -> DeckResponse(state.suggestions.map { it.toDto() }, needsTopics = false, week = week)
        }
    }

    /**
     * The week's deck. Built once per ISO week and catalogue, then kept: later openings show the
     * same cards minus those decided. [topics] builds a new deck from picked topics; null keeps
     * the week's deck, or asks for topics on a cold start.
     */
    suspend fun deckState(userId: Long, topics: Set<Int>? = null): DeckState {
        val catalogue = (catalogues.load() as? CatalogueStatus.Ready)?.catalogue ?: return DeckState.Unavailable
        val clusters = catalogue.clusters
        val now = clock.instant()
        val week = weekOf(now, zoneOf(userId))
        val builtAt = catalogue.builtAt?.toEpochMilli() ?: 0L
        val saved = loadDeck(userId)?.takeIf { topics == null && it.week == week && it.catalogueBuiltAt == builtAt }
        val cards: List<DeckCard> = if (saved != null) {
            saved.cards.mapNotNull { card -> catalogue.byKey[card.urlKey]?.let { DeckCard(it, card.score, card.reason, card.exploration) } }
        } else {
            val profile = profile(userId, topics.orEmpty())
            if (topics == null && !profile.hasTaste) return DeckState.NeedsTopics(topicChoices(catalogue))
            val discovery = Discovery(catalogue.mean, clusters, config, now)
            val built = withContext(dispatcher) { discovery.deck(catalogue.feeds, profile, Random(seedOf(week, builtAt, topics))) }
            saveDeck(
                userId,
                SavedDeck(week, builtAt, topics.orEmpty().sorted(), built.map { SavedCard(it.feed.urlKey, it.reason, it.exploration, it.score) }),
            )
            built
        }
        val decided = decidedKeys(userId)
        val open = cards.filter { it.feed.urlKey !in decided }
        val choices = topicChoices(catalogue)
        if (open.isEmpty()) return DeckState.Finished(cards.size, choices)
        return DeckState.Cards(open.map { Suggestion(it, catalogue.headlines(it.feed.id)) }, cards.size, choices)
    }

    private fun topicChoices(catalogue: Catalogue): List<CatalogueCluster> {
        val counts = catalogue.feeds.groupingBy { it.clusterId }.eachCount()
        return catalogue.clusters.filter { (counts[it.id] ?: 0) > 0 && it.label.isNotBlank() }.sortedBy { it.label.lowercase() }
    }

    /** URL keys never to show as a card again: every verdict, and every subscribed feed. */
    private suspend fun decidedKeys(userId: Long): Set<String> =
        discoveryDao.verdictKeys(userId).toHashSet() + subscriptionDao.subscribedDetails(userId).map { it.feed.urlKey }

    private suspend fun loadDeck(userId: Long): SavedDeck? =
        discoveryDao.state(userId, DECK_KEY)?.let { runCatching { json.decodeFromString<SavedDeck>(it) }.getOrNull() }

    private suspend fun saveDeck(userId: Long, deck: SavedDeck) = discoveryDao.putState(userId, DECK_KEY, json.encodeToString(deck))

    /** Everything [Discovery] needs to know of the user, read from the database now. */
    suspend fun profile(userId: Long, topics: Set<Int> = emptySet()): DiscoveryProfile {
        val now = clock.instant()
        val subscribed = subscriptionDao.subscribedDetails(userId)
        val profileFeeds = subscribed.mapNotNull { row ->
            val centroid = FeedCentroids.of(discoveryDao, row.feed.id, config) ?: return@mapNotNull null
            val weight = Discovery.feedWeight(affinity.effective(row.subscription.affinity, row.subscription.sliderStep), config)
            ProfileFeed(row.title, centroid, weight, row.feed.tier)
        }
        val since = now.minus(Duration.ofDays(config.postWindowDays))
        val posts = discoveryDao.engagedVectors(userId, since, StaticEmbedder.MODEL_ID, config.maxPostPoints)
            .filter { it.vector.size == StaticEmbedder.DIMENSIONS }
            .map { row ->
                val liked = row.likedAt != null
                ProfilePost(StaticEmbedder.fromBytes(row.vector), if (liked) config.likedPostWeight else config.readPostWeight, liked)
            }
        val verdicts = discoveryDao.verdicts(userId)
        val negatives = verdicts.mapNotNull { verdict ->
            val vector = verdict.centroid?.takeIf { it.size == StaticEmbedder.DIMENSIONS }?.let(StaticEmbedder::fromBytes) ?: return@mapNotNull null
            when (verdict.verdict) {
                DiscoveryVerdict.PASSED -> ProfileNegative(vector, Discovery.passWeight(verdict.decidedAt, now, config))
                DiscoveryVerdict.DROPPED -> ProfileNegative(vector, config.droppedWeight)
                DiscoveryVerdict.SUBSCRIBED -> null
            }
        }
        val tallies = HashMap<Int, ClusterTally>()
        for (verdict in verdicts) {
            val cluster = verdict.clusterId ?: continue
            val tally = tallies[cluster] ?: ClusterTally()
            tallies[cluster] = when (verdict.verdict) {
                DiscoveryVerdict.SUBSCRIBED -> tally.copy(subscribes = tally.subscribes + 1)
                DiscoveryVerdict.PASSED -> tally.copy(passes = tally.passes + 1)
                DiscoveryVerdict.DROPPED -> tally
            }
        }
        val terms = (subscriptionDao.tags(userId).map { it.name } + discoveryDao.readCategories(userId, since, READER_TERMS))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val keys = HashSet<String>()
        val hosts = HashSet<String>()
        for (row in subscribed) {
            keys += row.feed.urlKey
            for (url in listOfNotNull(row.feed.url, row.feed.siteUrl)) {
                Discovery.exactHost(url)?.let { hosts += it }
                Hosts.registrable(url)?.takeIf { it !in SHARED_PLATFORMS }?.let { hosts += it }
            }
        }
        keys += verdicts.map { it.urlKey }
        return DiscoveryProfile(
            feeds = profileFeeds,
            posts = posts,
            negatives = negatives,
            readerTerms = terms,
            pickedClusters = topics,
            excludedKeys = keys,
            excludedHosts = hosts,
            clusterTallies = tallies,
        )
    }

    // --- Decisions -----------------------------------------------------------------------------

    override suspend fun decide(userId: Long, request: VerdictRequest): VerdictResponse {
        val key = FeedUrls.normalize(request.url)
        val feed = catalogueFeed(key)
        val url = feed?.url ?: request.url.trim()
        val live = candidates.cached(url)
        val verdict = { kind: DiscoveryVerdict, title: String? ->
            DiscoveryVerdictEntity(
                userId = userId, urlKey = key, url = url, verdict = kind, decidedAt = clock.instant(),
                title = feed?.title ?: live?.parsed?.title ?: title, clusterId = feed?.clusterId,
                centroid = (live?.centroid ?: feed?.centroid)?.let(StaticEmbedder::toBytes),
            )
        }
        return when (request.verdict) {
            CardVerdict.PASSED -> {
                database.withWriteTransaction {
                    discoveryDao.upsertVerdict(verdict(DiscoveryVerdict.PASSED, null))
                    discoveryDao.deleteState(userId, undoKey(key))
                }
                VerdictResponse(null)
            }
            CardVerdict.SUBSCRIBED -> {
                val result = subscriptions.subscribeFromCard(
                    userId, url,
                    title = live?.parsed?.title?.takeIf { it.isNotBlank() } ?: feed?.title,
                    siteUrl = live?.parsed?.siteUrl ?: feed?.siteUrl,
                    iconUrl = live?.parsed?.iconUrl ?: feed?.iconUrl,
                ) { row -> verdict(DiscoveryVerdict.SUBSCRIBED, row.title) }
                val undo = when {
                    result.created -> SubscribeUndo(result.feedId, created = true)
                    result.previous != null ->
                        SubscribeUndo(result.feedId, false, result.previous.addedAt.toEpochMilli(), result.previous.discoveredAt?.toEpochMilli())
                    else -> null
                }
                if (undo != null) discoveryDao.putState(userId, undoKey(key), json.encodeToString(undo)) else discoveryDao.deleteState(userId, undoKey(key))
                feeds.firstFetch(result.feedId)
                VerdictResponse(feeds.feed(userId, result.feedId))
            }
        }
    }

    /**
     * Undo restores what was there before: the verdict is deleted (no new one is written), a
     * subscription the verdict created is removed, and a discovered feed subscribed in place is
     * discovered again. Also Undo in the Passed list. A DROPPED verdict is never undone here.
     */
    override suspend fun undo(userId: Long, url: String) {
        val key = FeedUrls.normalize(url)
        val verdict = discoveryDao.verdict(userId, key) ?: return
        if (verdict.verdict == DiscoveryVerdict.DROPPED) return
        val undo = discoveryDao.state(userId, undoKey(key))?.let { runCatching { json.decodeFromString<SubscribeUndo>(it) }.getOrNull() }
        database.withWriteTransaction {
            discoveryDao.deleteVerdict(userId, key)
            discoveryDao.deleteState(userId, undoKey(key))
        }
        if (verdict.verdict != DiscoveryVerdict.SUBSCRIBED || undo == null) return
        val current = subscriptionDao.get(userId, undo.feedId) ?: return
        when {
            undo.created -> subscriptions.remove(userId, undo.feedId, dropped = false)
            undo.previousAddedAt != null -> subscriptions.revertToDiscovered(
                userId,
                current.copy(addedAt = Instant.ofEpochMilli(undo.previousAddedAt), discoveredAt = undo.previousDiscoveredAt?.let(Instant::ofEpochMilli)),
            )
        }
    }

    override suspend fun passed(userId: Long): PassedResponse = PassedResponse(
        discoveryDao.verdictsOf(userId, DiscoveryVerdict.PASSED).map {
            PassedFeedDto(it.urlKey, it.url, it.title ?: FeedUrls.hostOf(it.url), it.decidedAt)
        },
    )

    // --- Live headlines and reading a suggested feed's posts -----------------------------------

    override suspend fun live(userId: Long, url: String): LiveResponse = when (val result = candidates.fetch(url.trim(), force = true)) {
        is CandidateResult.Fetched -> LiveResponse(headlinesOf(result.candidate).map { it.toDto() }, LiveState.FETCHED)
        is CandidateResult.Dead -> LiveResponse(emptyList(), LiveState.DEAD)
        is CandidateResult.Unreachable, CandidateResult.SkippedMetered -> LiveResponse(emptyList(), LiveState.UNREACHABLE)
    }

    override suspend fun open(userId: Long, request: OpenDiscoveredRequest): OpenDiscoveredResponse {
        val feedUrl = request.feedUrl.trim()
        val catalogue = (catalogues.load() as? CatalogueStatus.Ready)?.catalogue
        val feed = catalogue?.byKey?.get(FeedUrls.normalize(feedUrl))
        val url = feed?.url ?: feedUrl
        var headline = feed?.let { f -> catalogue.headlines(f.id, HEADLINE_SEARCH).firstOrNull { it.url == request.articleUrl } }
        var live = candidates.cached(url)
        if (headline == null && live == null) live = (candidates.fetch(url, force = true) as? CandidateResult.Fetched)?.candidate
        if (headline == null) {
            headline = live?.parsed?.items?.firstOrNull { it.url == request.articleUrl || it.guid == request.articleUrl }?.let {
                CatalogueHeadline(it.title, it.url, it.publishedAt.takeIf { _ -> it.hasDate }, it.content.wordCount.takeIf { w -> w > 0 })
            }
        }
        if (headline == null || (feed == null && live == null)) throw ApiFailure.notFound("No such headline")
        val stored = openPost(userId, url, feed, headline, live) ?: throw ApiFailure.notFound("No such headline")
        return OpenDiscoveredResponse(articleId = stored.second, feedId = stored.first)
    }

    /**
     * Stores [headline] of a suggested feed as an article of the shared feed, under a DISCOVERED
     * subscription of the user's (created if need be), and returns (feed ID, article ID). The
     * live post is used when the feed was fetched, so the reader has its text at once. Opening
     * the same headline again returns the same post.
     */
    suspend fun openPost(userId: Long, url: String, feed: CatalogueFeed?, headline: CatalogueHeadline, live: LiveCandidate? = null): Pair<Long, Long>? {
        val item = live?.parsed?.items?.firstOrNull { matches(it, headline) }
        val prepared = item?.let { withContext(dispatcher) { it.content } }
        return database.withWriteTransaction {
            val now = clock.instant()
            val row = subscriptions.discover(
                userId, url,
                title = live?.parsed?.title?.takeIf { it.isNotBlank() } ?: feed?.title,
                siteUrl = live?.parsed?.siteUrl ?: feed?.siteUrl,
                iconUrl = live?.parsed?.iconUrl ?: feed?.iconUrl,
            )
            val guid = item?.guid ?: headline.url ?: "$url#${headline.title}"
            articleDao.getByGuid(row.id, guid)?.let { return@withWriteTransaction row.id to it.id }
            val blocks = prepared?.blocks?.takeIf { it.isNotEmpty() }?.let(ContentBlocks::encode)
            val id = articleDao.insertNew(
                ArticleEntity(
                    feedId = row.id,
                    guid = guid,
                    url = item?.url ?: headline.url,
                    title = item?.title ?: headline.title,
                    summary = prepared?.summary,
                    imageUrl = prepared?.imageUrl,
                    author = item?.author,
                    publishedAt = minOf(item?.publishedAt ?: headline.publishedAt ?: now, now),
                    firstSeenAt = now,
                    wordCount = prepared?.wordCount ?: headline.wordCount ?: 0,
                    feedWordCount = prepared?.wordCount ?: 0,
                    contentHash = prepared?.contentHash,
                ),
                blocks,
                prepared?.plainText?.takeIf { blocks != null },
            ) ?: return@withWriteTransaction articleDao.getByGuid(row.id, guid)?.let { row.id to it.id }
            if (item != null && prepared != null) {
                signalDao.addCategories(id, item.categories)
                signalDao.upsertTraits(SignalDao.traitsRow(id, prepared.traits))
            }
            row.id to id
        }
    }

    /** The headlines to show for a fetched feed: its newest three posts, in the catalogue's shape. */
    fun headlinesOf(live: LiveCandidate, limit: Int = 3): List<CatalogueHeadline> =
        live.parsed.items.sortedByDescending { it.publishedAt }.take(limit).map {
            CatalogueHeadline(it.title, it.url, it.publishedAt.takeIf { _ -> it.hasDate }, it.content.wordCount.takeIf { w -> w > 0 })
        }

    private suspend fun catalogueFeed(key: String): CatalogueFeed? =
        (catalogues.load() as? CatalogueStatus.Ready)?.catalogue?.byKey?.get(key)

    private fun matches(item: ParsedItem, headline: CatalogueHeadline): Boolean =
        (headline.url != null && (item.url == headline.url || item.guid == headline.url)) ||
            item.title.trim().equals(headline.title.trim(), ignoreCase = true)

    private fun Suggestion.toDto() = CardDto(
        url = feed.url,
        urlKey = feed.urlKey,
        siteUrl = feed.siteUrl,
        host = feed.host,
        title = feed.title,
        description = feed.description,
        iconUrl = feed.iconUrl,
        tier = feed.tier,
        postsPerWeek = feed.postsPerWeek,
        fullTextShare = feed.fullTextShare,
        reason = card.reason,
        headlines = headlines.map { it.toDto() },
    )

    private fun CatalogueHeadline.toDto() = HeadlineDto(title = title, url = url, publishedAt = publishedAt, wordCount = wordCount)

    private fun weekOf(now: Instant, zone: ZoneId): String {
        val date = now.atZone(zone).toLocalDate()
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    private fun seedOf(week: String, builtAt: Long, topics: Set<Int>?): Long =
        (week.hashCode().toLong() shl 32) xor builtAt xor (topics?.sorted()?.hashCode()?.toLong() ?: 0L)

    private companion object {
        const val READER_TERMS = 50
        const val HEADLINE_SEARCH = 10
        const val DECK_KEY = "discovery.deck"

        fun undoKey(urlKey: String) = "discovery.undo.$urlKey"

        /** Hosts where every user has a site of their own without the public suffix list saying so. */
        val SHARED_PLATFORMS = setOf(
            "substack.com", "medium.com", "wordpress.com", "blogspot.com", "tumblr.com", "micro.blog",
            "svbtle.com", "ghost.io", "write.as", "mataroa.blog", "bearblog.dev", "github.io", "gitlab.io",
            "neocities.org", "netlify.app", "pages.dev", "vercel.app", "posthaven.com", "typepad.com",
            "livejournal.com", "dreamwidth.org", "hashnode.dev", "dev.to", "beehiiv.com", "buttondown.email",
        )
    }
}

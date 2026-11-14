package net.tgnra.morningissue.data.discovery

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.DiscoveryVerdictEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.repository.FeedRepository
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
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.sync.LiveCandidate
import net.tgnra.morningissue.sync.ParsedItem

/** One card: the suggestion and the catalogue's headlines for it. */
class Suggestion(val card: DeckCard, val headlines: List<CatalogueHeadline>) {
    val feed: CatalogueFeed get() = card.feed
}

/** What the discovery screen has to show. */
sealed interface DeckState {
    /** No catalogue in this build, or one this app cannot read: "No suggestions available". */
    data object Unavailable : DeckState

    /** A cold start (nothing to go on): pick some topics first. */
    data class NeedsTopics(val topics: List<CatalogueCluster>) : DeckState

    /** The week's cards still undecided, in order; [total] is the deck's size. */
    data class Cards(val suggestions: List<Suggestion>, val total: Int, val topics: List<CatalogueCluster>) : DeckState

    /** Every card of the week's deck is decided. */
    data class Finished(val total: Int, val topics: List<CatalogueCluster>) : DeckState
}

/** A decision on a card, kept so that Undo can restore exactly what was there before. */
sealed interface Decision {
    val feed: CatalogueFeed

    data class Passed(override val feed: CatalogueFeed) : Decision

    /**
     * @param created the subscription made a new feed row; otherwise a discovered row was
     *   subscribed in place, and [previous] is how it was
     */
    data class Subscribed(
        override val feed: CatalogueFeed,
        val feedId: Long,
        val created: Boolean,
        val previous: FeedEntity?,
    ) : Decision
}

/**
 * Feed discovery's data side: the reader's profile from what is stored, the week's deck from
 * the bundled catalogue ([CatalogueStore]) and the pure ranking ([Discovery]), the decisions
 * (`discovery_verdict`), and the rows of feeds the reader looks into without subscribing.
 *
 * **Profile.** Each subscribed feed's centroid is the mean of its newest 50 post vectors in
 * `article_embedding` (at least 3), weighted by its effective affinity; Liked (1) and read (0.5)
 * posts of the last 180 days are points of their own, whatever their feed; passed (decaying over
 * 90 days) and dropped (lasting) feeds are the negatives. Computed when the screen opens, never
 * stored.
 *
 * **Opening a headline** stores the feed as `FeedStatus.DISCOVERED` (never synced, never in an
 * edition) and the post as an ordinary article, so the reader, Save, search and the image store
 * work unchanged. If the reader later subscribes, the same row becomes the subscription and the
 * saved post is its first evidence. Unsaved posts of such feeds are deleted after 7 days by
 * [DiscoveryCleanup].
 */
class DiscoveryRepository(
    private val database: MorningIssueDatabase,
    private val catalogues: CatalogueStore,
    private val feeds: FeedRepository,
    private val deckStore: DeckStore,
    private val clock: Clock,
    private val images: ImageStore? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val config: DiscoveryConfig = DiscoveryConfig.Default,
    private val ranking: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val feedDao = database.feedDao()
    private val articleDao = database.articleDao()
    private val tagDao = database.tagDao()
    private val discoveryDao = database.discoveryDao()
    private val signalDao = database.articleSignalDao()
    private val affinity = Affinity(ranking)

    /**
     * The week's deck. Built once per ISO week and catalogue, then kept ([DeckStore]): later
     * openings show the same cards minus those decided. [topics] builds a new deck from picked
     * topics (the cold-start screen, or "Browse by topic"); an empty set builds one with nothing
     * picked. Null keeps the week's deck, or asks for topics on a cold start.
     */
    suspend fun deck(topics: Set<Int>? = null): DeckState {
        val catalogue = (catalogues.load() as? CatalogueStatus.Ready)?.catalogue ?: return DeckState.Unavailable
        val clusters = catalogue.clusters
        val now = clock.instant()
        val week = weekOf(now)
        val builtAt = catalogue.builtAt?.toEpochMilli() ?: 0L
        val saved = deckStore.load()?.takeIf { topics == null && it.week == week && it.catalogueBuiltAt == builtAt }
        val byKey by lazy { catalogue.feeds.associateBy { it.urlKey } }
        val cards: List<DeckCard> = if (saved != null) {
            saved.cards.mapNotNull { card -> byKey[card.urlKey]?.let { DeckCard(it, card.score, card.reason, card.exploration) } }
        } else {
            val profile = profile(topics.orEmpty())
            if (topics == null && !profile.hasTaste) return DeckState.NeedsTopics(topicChoices(catalogue))
            val discovery = Discovery(catalogue.mean, clusters, config, now)
            val built = withContext(dispatcher) { discovery.deck(catalogue.feeds, profile, Random(seedOf(week, builtAt, topics))) }
            deckStore.save(
                SavedDeck(week, builtAt, topics.orEmpty().sorted(), built.map { SavedCard(it.feed.urlKey, it.reason, it.exploration, it.score) }),
            )
            built
        }
        val decided = decidedKeys()
        val open = cards.filter { it.feed.urlKey !in decided }
        val choices = topicChoices(catalogue)
        if (open.isEmpty()) return DeckState.Finished(cards.size, choices)
        return DeckState.Cards(open.map { Suggestion(it, catalogue.headlines(it.feed.id)) }, cards.size, choices)
    }

    /** Clusters worth offering as topic chips: those with feeds, by label. */
    private fun topicChoices(catalogue: Catalogue): List<CatalogueCluster> {
        val counts = catalogue.feeds.groupingBy { it.clusterId }.eachCount()
        return catalogue.clusters.filter { (counts[it.id] ?: 0) > 0 && it.label.isNotBlank() }.sortedBy { it.label.lowercase() }
    }

    /** URL keys never to show as a card again: every verdict, and every subscribed feed. */
    private suspend fun decidedKeys(): Set<String> =
        discoveryDao.verdictKeys().toHashSet() + feedDao.getSubscribed().map { FeedUrls.normalize(it.url) }

    /** Everything [Discovery] needs to know of the reader, read from the database now. */
    suspend fun profile(topics: Set<Int> = emptySet()): DiscoveryProfile {
        val now = clock.instant()
        val subscribed = feedDao.getSubscribedWithDetails()
        val profileFeeds = subscribed.mapNotNull { details ->
            val feed = details.feed
            val centroid = FeedCentroids.of(discoveryDao, feed.id, config) ?: return@mapNotNull null
            val weight = Discovery.feedWeight(affinity.effective(feed.affinity, feed.sliderStep), config)
            ProfileFeed(feed.title, centroid, weight, feed.tier)
        }
        val since = now.minus(Duration.ofDays(config.postWindowDays))
        val posts = discoveryDao.engagedVectors(since, StaticEmbedder.MODEL_ID, config.maxPostPoints)
            .filter { it.vector.size == StaticEmbedder.DIMENSIONS }
            .map { row ->
                val liked = row.likedAt != null
                ProfilePost(StaticEmbedder.fromBytes(row.vector), if (liked) config.likedPostWeight else config.readPostWeight, liked)
            }
        val verdicts = discoveryDao.verdicts()
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
        val terms = (tagDao.getAll().map { it.name } + discoveryDao.readCategories(since, READER_TERMS))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val keys = HashSet<String>()
        val hosts = HashSet<String>()
        for (details in subscribed) {
            val feed = details.feed
            keys += FeedUrls.normalize(feed.url)
            for (url in listOfNotNull(feed.url, feed.siteUrl)) {
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

    // --- Decisions ---------------------------------------------------------------------------

    /** Pass: the feed is never suggested again, and feeds like it are pushed down for a while. */
    suspend fun pass(feed: CatalogueFeed, live: LiveCandidate? = null): Decision.Passed {
        discoveryDao.upsertVerdict(verdictOf(feed, DiscoveryVerdict.PASSED, live))
        return Decision.Passed(feed)
    }

    /**
     * Subscribe: the feed becomes an ordinary subscription (a discovered row is reused, so
     * posts already saved from it are its evidence). The caller then syncs [Decision.Subscribed.feedId].
     */
    suspend fun subscribe(feed: CatalogueFeed, live: LiveCandidate? = null): Decision.Subscribed = database.withWriteTransaction {
        val key = feed.urlKey
        val existing = feedDao.getByUrl(feed.url) ?: feedDao.getUrls().firstOrNull { FeedUrls.normalize(it.url) == key }?.let { feedDao.getById(it.id) }
        val decision = if (existing != null) {
            val previous = existing.takeIf { it.status == FeedStatus.DISCOVERED }
            feeds.subscribe(existing.id)
            Decision.Subscribed(feed, existing.id, created = false, previous = previous)
        } else {
            val id = feedDao.insert(
                FeedEntity(
                    url = feed.url,
                    siteUrl = live?.parsed?.siteUrl ?: feed.siteUrl,
                    title = live?.parsed?.title?.takeIf { it.isNotBlank() } ?: feed.title,
                    iconUrl = live?.parsed?.iconUrl ?: feed.iconUrl,
                    addedAt = clock.instant(),
                ),
            )
            Decision.Subscribed(feed, id, created = true, previous = null)
        }
        discoveryDao.upsertVerdict(verdictOf(feed, DiscoveryVerdict.SUBSCRIBED, live))
        decision
    }

    /** Whether undoing [decision] would also throw away posts already fetched for the new subscription. */
    suspend fun undoLosesPosts(decision: Decision): Boolean =
        decision is Decision.Subscribed && feedDao.getById(decision.feedId)?.lastSyncAt != null

    /**
     * Undo restores exactly what was there before: the verdict row is deleted (no new one is
     * written), a feed created by the subscription is removed with its images and posts, and a
     * discovered feed subscribed in place becomes discovered again.
     */
    suspend fun undo(decision: Decision) {
        when (decision) {
            is Decision.Passed -> discoveryDao.deleteVerdict(decision.feed.urlKey)
            is Decision.Subscribed -> {
                if (decision.created) images?.deleteForFeed(decision.feedId)
                database.withWriteTransaction {
                    discoveryDao.deleteVerdict(decision.feed.urlKey)
                    val previous = decision.previous
                    when {
                        decision.created -> feedDao.delete(decision.feedId)
                        previous != null -> feedDao.revertToDiscovered(decision.feedId, previous.addedAt, previous.discoveredAt)
                    }
                }
            }
        }
    }

    /** The Passed list, newest first. */
    fun observePassed(): Flow<List<PassedFeed>> =
        discoveryDao.observeVerdicts(DiscoveryVerdict.PASSED).map { rows ->
            rows.map { PassedFeed(it.urlKey, it.url, it.title ?: FeedUrls.hostOf(it.url), it.decidedAt) }
        }

    /** Undo in the Passed list: the feed may be suggested again. */
    suspend fun unpass(urlKey: String) {
        val verdict = discoveryDao.verdict(urlKey) ?: return
        if (verdict.verdict == DiscoveryVerdict.PASSED) discoveryDao.deleteVerdict(urlKey)
    }

    private fun verdictOf(feed: CatalogueFeed, verdict: DiscoveryVerdict, live: LiveCandidate?) = DiscoveryVerdictEntity(
        urlKey = feed.urlKey,
        url = feed.url,
        verdict = verdict,
        decidedAt = clock.instant(),
        title = feed.title,
        clusterId = feed.clusterId,
        centroid = (live?.centroid ?: feed.centroid)?.let(StaticEmbedder::toBytes),
    )

    // --- Reading a suggested feed's posts ------------------------------------------------------

    /**
     * Stores [headline] of a suggested feed as an article of a discovered feed (creating that
     * feed if need be) and returns its ID, for the reader. The live post is used when the feed
     * was fetched ([live]), so the reader has its text at once; otherwise the reader fetches the
     * page as it does for any excerpt. Opening the same headline again returns the same post.
     */
    suspend fun openPost(feed: CatalogueFeed, headline: CatalogueHeadline, live: LiveCandidate? = null): Long? {
        val item = live?.parsed?.items?.firstOrNull { matches(it, headline) }
        val prepared = item?.let { withContext(dispatcher) { it.content } }
        return database.withWriteTransaction {
            val now = clock.instant()
            val row = feedDao.getByUrl(feed.url)
                ?: feedDao.getUrls().firstOrNull { FeedUrls.normalize(it.url) == feed.urlKey }?.let { feedDao.getById(it.id) }
            val feedId = row?.id ?: feedDao.insert(
                FeedEntity(
                    url = feed.url,
                    siteUrl = live?.parsed?.siteUrl ?: feed.siteUrl,
                    title = live?.parsed?.title?.takeIf { it.isNotBlank() } ?: feed.title,
                    iconUrl = live?.parsed?.iconUrl ?: feed.iconUrl,
                    addedAt = now,
                    status = FeedStatus.DISCOVERED,
                    discoveredAt = now,
                ),
            ).takeIf { it != -1L } ?: return@withWriteTransaction null
            val guid = item?.guid ?: headline.url ?: "${feed.url}#${headline.title}"
            articleDao.getByGuid(feedId, guid)?.let { return@withWriteTransaction it.id }
            val blocks = prepared?.blocks?.takeIf { it.isNotEmpty() }?.let(ContentBlocks::encode)
            val words = prepared?.wordCount ?: headline.wordCount ?: 0
            val id = articleDao.insertNew(
                ArticleEntity(
                    feedId = feedId,
                    guid = guid,
                    url = item?.url ?: headline.url,
                    title = item?.title ?: headline.title,
                    summary = prepared?.summary,
                    imageUrl = prepared?.imageUrl,
                    author = item?.author,
                    publishedAt = minOf(item?.publishedAt ?: headline.publishedAt ?: now, now),
                    firstSeenAt = now,
                    wordCount = words,
                    feedWordCount = prepared?.wordCount ?: 0,
                    contentHash = prepared?.contentHash,
                ),
                blocks,
                prepared?.plainText?.takeIf { blocks != null },
            ) ?: return@withWriteTransaction articleDao.getByGuid(feedId, guid)?.id
            if (item != null && prepared != null) {
                signalDao.addCategories(id, item.categories)
                signalDao.upsertTraits(ArticleSignalDao.traitsRow(id, prepared.traits))
            }
            id
        }
    }

    /**
     * The headlines to show for a fetched feed: its newest three posts, in the catalogue's
     * shape, so that a card reads the same fetched or not.
     */
    fun headlinesOf(live: LiveCandidate, limit: Int = 3): List<CatalogueHeadline> =
        live.parsed.items.sortedByDescending { it.publishedAt }.take(limit).map {
            CatalogueHeadline(it.title, it.url, it.publishedAt.takeIf { _ -> it.hasDate }, it.content.wordCount.takeIf { w -> w > 0 })
        }

    private fun matches(item: ParsedItem, headline: CatalogueHeadline): Boolean =
        (headline.url != null && (item.url == headline.url || item.guid == headline.url)) ||
            item.title.trim().equals(headline.title.trim(), ignoreCase = true)

    private fun weekOf(now: Instant): String {
        val date = now.atZone(zone()).toLocalDate()
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    private fun seedOf(week: String, builtAt: Long, topics: Set<Int>?): Long =
        (week.hashCode().toLong() shl 32) xor builtAt xor (topics?.sorted()?.hashCode()?.toLong() ?: 0L)

    private companion object {
        const val READER_TERMS = 50

        /**
         * Hosts where every user has a site of their own without the public suffix list saying
         * so: following one blog there must not hide every other blog on the platform.
         */
        val SHARED_PLATFORMS = setOf(
            "substack.com", "medium.com", "wordpress.com", "blogspot.com", "tumblr.com", "micro.blog",
            "svbtle.com", "ghost.io", "write.as", "mataroa.blog", "bearblog.dev", "github.io", "gitlab.io",
            "neocities.org", "netlify.app", "pages.dev", "vercel.app", "posthaven.com", "typepad.com",
            "livejournal.com", "dreamwidth.org", "hashnode.dev", "dev.to", "beehiiv.com", "buttondown.email",
        )
    }
}

/** A feed in the Passed list. */
data class PassedFeed(val urlKey: String, val url: String, val title: String, val passedAt: Instant)

/**
 * Housekeeping after every full sync (`MaintainedFeedSyncer`): posts of discovered feeds that
 * the reader neither saved nor Liked go 7 days after they were opened, and a discovered feed
 * left with no posts goes with them. Their pictures are deleted first, as on feed removal,
 * since files cannot be deleted by a cascade.
 */
class DiscoveryCleanup(
    private val database: MorningIssueDatabase,
    private val images: ImageStore?,
    private val clock: Clock,
    private val keepDays: Long = KEEP_DAYS,
) {
    /** Returns how many posts were deleted. */
    suspend fun run(): Int {
        val articleDao = database.articleDao()
        val feedDao = database.feedDao()
        val expired = articleDao.expiredDiscoveredIds(clock.instant().minus(Duration.ofDays(keepDays)))
        for (batch in expired.chunked(ArticleSignalDao.MAX_IDS)) {
            images?.deleteForArticles(batch)
            database.withWriteTransaction { articleDao.delete(batch) }
        }
        database.withWriteTransaction { feedDao.emptyDiscoveredIds().forEach { feedDao.delete(it) } }
        return expired.size
    }

    companion object {
        const val KEEP_DAYS = 7L
    }
}

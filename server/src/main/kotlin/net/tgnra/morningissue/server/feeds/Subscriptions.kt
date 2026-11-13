package net.tgnra.morningissue.server.feeds

import androidx.room3.withWriteTransaction
import java.time.Clock
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.model.DiscoveryVerdict
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.server.db.DiscoveryVerdictEntity
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SubscriptionEntity
import net.tgnra.morningissue.server.feeds.discovery.FeedCentroids

/**
 * A user's subscriptions to shared feeds: the write half of the app's `RoomFeedRepository`,
 * with `userId` threaded through (section 2.3). Adding reuses the global feed by its URL key;
 * removing deletes only the user's rows, and global garbage collection deletes a feed nobody
 * subscribes to any more.
 *
 * @param onChanged called after a change that shapes affinity (subscriptions and tags), so W-B's
 *   learner can relearn the user's affinities; the app did that inside the same transaction.
 */
class Subscriptions(
    private val database: ServerDatabase,
    private val clock: Clock,
    private val onChanged: suspend (userId: Long) -> Unit = {},
) {
    private val feedDao = database.feedDao()
    private val subscriptionDao = database.subscriptionDao()
    private val discoveryDao = database.discoveryDao()

    /** What [add] did. */
    enum class Added {
        /** A new subscription (the feed may have been new too). */
        CREATED,

        /** A feed the user had only discovered became a subscription. */
        PROMOTED,

        /** The user already subscribed; nothing changed. */
        ALREADY,
    }

    data class AddResult(val feedId: Long, val added: Added)

    /**
     * Subscribes [userId] to the feed at [url]. A feed another user (or this one, through
     * discovery) already has is matched by its normalised URL and reused; otherwise a global feed
     * row is created, titled [title] or the host until its first fetch. [title], when given, is
     * the user's own name for the feed. A discovered subscription is promoted in place, so the
     * posts saved from it are its first evidence. Tags are set on a new or promoted subscription.
     */
    suspend fun add(
        userId: Long,
        url: String,
        title: String? = null,
        siteUrl: String? = null,
        iconUrl: String? = null,
        tags: List<String> = emptyList(),
    ): AddResult {
        val result = database.withWriteTransaction {
            val now = clock.instant()
            val feed = findOrCreateFeed(url, title, siteUrl, iconUrl)
            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            val existing = subscriptionDao.get(userId, feed.id)
            val added = when {
                existing == null -> {
                    subscriptionDao.insert(SubscriptionEntity(userId = userId, feedId = feed.id, titleOverride = cleanTitle, addedAt = now))
                    Added.CREATED
                }
                existing.status == FeedStatus.DISCOVERED -> {
                    subscriptionDao.promote(userId, feed.id, now)
                    recordSubscribed(userId, feed)
                    Added.PROMOTED
                }
                else -> Added.ALREADY
            }
            if (added != Added.ALREADY && tags.isNotEmpty()) subscriptionDao.setFeedTags(userId, feed.id, tags)
            AddResult(feed.id, added)
        }
        if (result.added != Added.ALREADY) onChanged(userId)
        return result
    }

    /** The global feed for [url] by its URL key, created if need be. Call inside a transaction. */
    internal suspend fun findOrCreateFeed(url: String, title: String?, siteUrl: String?, iconUrl: String?): FeedEntity {
        val clean = url.trim()
        val key = FeedUrls.normalize(clean)
        feedDao.getByUrlKey(key)?.let { return it }
        feedDao.getByUrl(clean)?.let { return it }
        val entity = FeedEntity(
            url = clean,
            urlKey = key,
            siteUrl = siteUrl,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: FeedUrls.hostOf(clean),
            iconUrl = iconUrl,
            addedAt = clock.instant(),
        )
        val id = feedDao.insert(entity)
        return if (id == -1L) checkNotNull(feedDao.getByUrlKey(key) ?: feedDao.getByUrl(clean)) else entity.copy(id = id)
    }

    /** Records for discovery that the user subscribed to [feed], keeping what an earlier verdict knew. */
    internal suspend fun recordSubscribed(userId: Long, feed: FeedEntity) {
        val previous = discoveryDao.verdict(userId, feed.urlKey)
        discoveryDao.upsertVerdict(
            DiscoveryVerdictEntity(
                userId = userId, urlKey = feed.urlKey, url = feed.url, verdict = DiscoveryVerdict.SUBSCRIBED,
                decidedAt = clock.instant(), title = feed.title, clusterId = previous?.clusterId, centroid = previous?.centroid,
            ),
        )
    }

    /**
     * Unsubscribes (section 2.3): deletes the subscription, its tags and flag, and the user's
     * engagement and edition placements for the feed's posts; writes a DROPPED verdict for a
     * subscribed feed when [dropped] (discovery never suggests it again). Returns false when the
     * user had no subscription to the feed.
     */
    suspend fun remove(userId: Long, feedId: Long, dropped: Boolean = true): Boolean {
        val subscription = subscriptionDao.get(userId, feedId) ?: return false
        val feed = feedDao.getById(feedId) ?: return false
        // The centroid is taken while the posts are there.
        val verdict = if (dropped && subscription.status == FeedStatus.SUBSCRIBED) {
            DiscoveryVerdictEntity(
                userId = userId, urlKey = feed.urlKey, url = feed.url, verdict = DiscoveryVerdict.DROPPED, decidedAt = clock.instant(),
                title = subscription.titleOverride ?: feed.title, clusterId = discoveryDao.verdict(userId, feed.urlKey)?.clusterId,
                centroid = FeedCentroids.of(discoveryDao, feedId)?.let(StaticEmbedder::toBytes),
            )
        } else {
            null
        }
        database.withWriteTransaction {
            verdict?.let { discoveryDao.upsertVerdict(it) }
            subscriptionDao.deleteEngagementForFeed(userId, feedId)
            subscriptionDao.deletePlacementsForFeed(userId, feedId)
            subscriptionDao.deleteFlag(userId, feedId)
            subscriptionDao.delete(userId, feedId)
            subscriptionDao.deleteUnusedTags(userId)
        }
        onChanged(userId)
        return true
    }

    /**
     * What a Subscribe on a discovery card did, so that Undo can restore exactly what was there:
     * [created] a new subscription, or promoted a discovered one that was [previous]; neither
     * when the user already subscribed.
     */
    data class CardSubscribe(val feedId: Long, val created: Boolean, val previous: SubscriptionEntity?)

    /** Subscribe from a discovery card: as [add], also recording the SUBSCRIBED verdict [verdict]. */
    suspend fun subscribeFromCard(
        userId: Long,
        url: String,
        title: String?,
        siteUrl: String?,
        iconUrl: String?,
        verdict: (FeedEntity) -> DiscoveryVerdictEntity,
    ): CardSubscribe {
        val result = database.withWriteTransaction {
            val now = clock.instant()
            val feed = findOrCreateFeed(url, title, siteUrl, iconUrl)
            val existing = subscriptionDao.get(userId, feed.id)
            val result = when {
                existing == null -> {
                    subscriptionDao.insert(SubscriptionEntity(userId = userId, feedId = feed.id, addedAt = now))
                    CardSubscribe(feed.id, created = true, previous = null)
                }
                existing.status == FeedStatus.DISCOVERED -> {
                    subscriptionDao.promote(userId, feed.id, now)
                    CardSubscribe(feed.id, created = false, previous = existing)
                }
                else -> CardSubscribe(feed.id, created = false, previous = null)
            }
            discoveryDao.upsertVerdict(verdict(feed))
            result
        }
        if (result.created || result.previous != null) onChanged(userId)
        return result
    }

    /** Undoes a promotion: the subscription is discovered again, as [previous] was. */
    suspend fun revertToDiscovered(userId: Long, previous: SubscriptionEntity) {
        subscriptionDao.revertToDiscovered(userId, previous.feedId, previous.addedAt, previous.discoveredAt)
        onChanged(userId)
    }

    /**
     * The feed at [url] as one the user only looks into: the global feed (created if need be)
     * and a DISCOVERED subscription unless the user has one already. Call inside a transaction.
     */
    internal suspend fun discover(userId: Long, url: String, title: String?, siteUrl: String?, iconUrl: String?): FeedEntity {
        val feed = findOrCreateFeed(url, title, siteUrl, iconUrl)
        if (subscriptionDao.get(userId, feed.id) == null) {
            val now = clock.instant()
            subscriptionDao.insert(
                SubscriptionEntity(userId = userId, feedId = feed.id, status = FeedStatus.DISCOVERED, addedAt = now, discoveredAt = now),
            )
        }
        return feed
    }

    /** Replaces the feed's tags for the user. */
    suspend fun setTags(userId: Long, feedId: Long, tags: List<String>) {
        subscriptionDao.setFeedTags(userId, feedId, tags)
        onChanged(userId)
    }
}

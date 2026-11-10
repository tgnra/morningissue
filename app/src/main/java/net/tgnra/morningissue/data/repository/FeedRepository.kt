package net.tgnra.morningissue.data.repository

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.model.Tag

/**
 * The user's subscriptions. Called by the UI (Sources, onboarding, long-press menu) and by
 * OPML import. Implemented by [RoomFeedRepository]. None of these methods touch the network:
 * finding a feed's address is `FeedDiscovery`, fetching its posts is `FeedSyncer`.
 */
interface FeedRepository {
    /** All feeds with tags and health flag, sorted by title; emits again on any change. */
    fun observeFeeds(): Flow<List<Feed>>

    /** One feed; emits null if it does not exist or is removed. */
    fun observeFeed(feedId: Long): Flow<Feed?>

    /** A snapshot of [observeFeeds]. */
    suspend fun getFeeds(): List<Feed>

    suspend fun getFeed(feedId: Long): Feed?

    /** All tags in use, sorted by name. */
    fun observeTags(): Flow<List<Tag>>

    /**
     * Subscribes to a feed whose document URL is known. If a feed with the same normalised URL
     * (`FeedUrls.normalize`) exists, nothing changes and [AddFeedResult.AlreadySubscribed] is
     * returned. The new feed has no posts until it is synced.
     */
    suspend fun addFeed(feed: NewFeed): AddFeedResult

    /** Unsubscribes and deletes the feed's posts, engagement and edition placements. */
    suspend fun removeFeed(feedId: Long)

    /** Sets the display title; blank titles are ignored. */
    suspend fun renameFeed(feedId: Long, title: String)

    suspend fun setSliderStep(feedId: Long, step: SliderStep)

    /** Replaces the feed's tags. Names are trimmed and matched without regard to case. */
    suspend fun setTags(feedId: Long, tags: List<String>)

    /** Mutes the feed until [until]; null unmutes. Muted feeds are left out of new editions. */
    suspend fun setMutedUntil(feedId: Long, until: Instant?)
}

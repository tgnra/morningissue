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
 * OPML import. Implemented by `client.ServerFeedRepository`: reads come from the phone's cache,
 * changes go to the server; finding a feed's address is `client.FeedFinder`.
 */
interface FeedRepository {
    /**
     * All subscribed feeds with tags and health flag, sorted by title; emits again on any
     * change. Feeds that only hold posts saved from discovery are not listed.
     */
    fun observeFeeds(): Flow<List<Feed>>

    /** One feed, subscribed or not (see [Feed.isSubscribed]); emits null if it does not exist or is removed. */
    fun observeFeed(feedId: Long): Flow<Feed?>

    /** A snapshot of [observeFeeds]. */
    suspend fun getFeeds(): List<Feed>

    suspend fun getFeed(feedId: Long): Feed?

    /** All tags in use, sorted by name. */
    fun observeTags(): Flow<List<Tag>>

    /**
     * Subscribes to a feed whose document URL is known. If a subscribed feed with the same
     * normalised URL (`FeedUrls.normalize`) exists, nothing changes and
     * [AddFeedResult.AlreadySubscribed] is returned. A feed that only holds posts saved from
     * discovery is subscribed in place ([subscribe]) and counts as added. The new feed has no
     * new posts until it is synced.
     */
    suspend fun addFeed(feed: NewFeed): AddFeedResult

    /**
     * Unsubscribes and deletes the feed's posts, engagement and edition placements. Feed
     * discovery remembers the removal (a DROPPED verdict) and never suggests the feed again.
     */
    suspend fun removeFeed(feedId: Long)

    /**
     * Subscribes to a feed that so far only held posts opened or saved from discovery
     * (`FeedStatus.DISCOVERED`): the same row becomes a subscription, so its saved posts are
     * its first evidence. Returns false when the feed was not a discovered one. The caller
     * syncs it.
     */
    suspend fun subscribe(feedId: Long): Boolean

    /** Sets the display title; blank titles are ignored. */
    suspend fun renameFeed(feedId: Long, title: String)

    suspend fun setSliderStep(feedId: Long, step: SliderStep)

    /** Replaces the feed's tags. Names are trimmed and matched without regard to case. */
    suspend fun setTags(feedId: Long, tags: List<String>)

    /** Mutes the feed until [until]; null unmutes. Muted feeds are left out of new editions. */
    suspend fun setMutedUntil(feedId: Long, until: Instant?)
}

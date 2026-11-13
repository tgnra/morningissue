package net.tgnra.morningissue.server.service

import java.time.Duration
import java.time.Instant

/** What one [FeedRefresher.refresh] did. */
data class RefreshReport(
    /** Feeds fetched (not skipped by `maxAge`). */
    val attempted: Int,
    /** Of those, feeds that answered (including 304). */
    val answered: Int,
    val newArticles: Int,
    /** False when the deadline passed with fetches still running. */
    val finishedInTime: Boolean,
) {
    /** "Reached the feeds": finished in time and at least one answered, or there was nothing to fetch. */
    val reachedFeeds: Boolean get() = finishedInTime && (attempted == 0 || answered > 0)
}

/**
 * Fetches shared feeds into the global tables (section 3). Implemented by W-A in
 * `feeds/FeedRefresher.kt`, ported from `DefaultFeedSyncer`; W-B's scheduler uses a fake until
 * integration.
 *
 * In-flight fetches are shared: a second caller for a feed being fetched awaits the same fetch.
 * Every fetch sets `feed.last_attempt_at`, and `last_success_at` on success.
 */
interface FeedRefresher {
    /**
     * Fetches [feedIds], skipping any whose `last_attempt_at` is within [maxAge] of now, and
     * stops waiting at [deadline]. New posts get `firstSeenAt = now`, categories, traits and
     * teaser full text; signals upkeep runs after the batch.
     */
    suspend fun refresh(feedIds: Collection<Long>, maxAge: Duration, deadline: Instant? = null): RefreshReport

    /** Whether the latest refresh covering the user's subscribed feeds reached them (the scheduler's `refreshed`). */
    fun reachedFeedsOf(userId: Long): Boolean
}

package net.tgnra.morningissue.sync

/** A feed found at or behind an address the user typed. */
data class DiscoveredFeed(
    /** The feed document URL, ready for `FeedRepository.addFeed`. */
    val url: String,
    val title: String?,
    val siteUrl: String?,
    val iconUrl: String?,
)

sealed interface DiscoveryResult {
    /** One or more feeds, best match first. Never empty. */
    data class Found(val feeds: List<DiscoveredFeed>) : DiscoveryResult

    /** The address answered but offers no feed. */
    data object NoFeed : DiscoveryResult

    /** The address could not be reached or read. [message] is a short reason for the user. */
    data class Failed(val message: String) : DiscoveryResult
}

/**
 * Finds the feed for a site or feed address. **Owned by the sync agent**; the stub is
 * [NoOpFeedDiscovery]. Called by the add-feed UI and onboarding, which then pass the chosen
 * [DiscoveredFeed] to `FeedRepository.addFeed` and `FeedSyncer.syncFeed`.
 */
interface FeedDiscovery {
    /**
     * [input] is whatever the user typed: a feed URL, a site URL, or a bare host with no
     * scheme. Fetches it, follows `<link rel="alternate">` and common feed paths, and returns
     * what it finds. Stores nothing. Never throws except on cancellation.
     */
    suspend fun discover(input: String): DiscoveryResult
}

class NoOpFeedDiscovery : FeedDiscovery {
    override suspend fun discover(input: String): DiscoveryResult = DiscoveryResult.NoFeed
}

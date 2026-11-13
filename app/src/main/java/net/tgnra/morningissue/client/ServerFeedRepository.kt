package net.tgnra.morningissue.client

import android.util.Log
import androidx.room3.withWriteTransaction
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.api.AddFeedRequest
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.FeedCandidateDto
import net.tgnra.morningissue.api.FeedDto
import net.tgnra.morningissue.api.FeedPatch
import net.tgnra.morningissue.api.Patch
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.repository.FeedRepository
import net.tgnra.morningissue.data.repository.toModel
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.model.Tag

/** What looking for a feed at an address found (`POST /feeds/discover`). */
sealed interface FeedSearch {
    data class Found(val feeds: List<FeedCandidateDto>) : FeedSearch

    /** The address answered and offers no feed. */
    data object NoFeed : FeedSearch

    data class Failed(val message: String) : FeedSearch
}

/** Finds the feeds a site offers, on the server. */
fun interface FeedFinder {
    suspend fun discover(address: String): FeedSearch
}

/**
 * The subscriptions: read from the cache, changed on the server. A change is made in the
 * cache at once where it can be (a rename, the slider, tags, a mute), then sent; the server's
 * answer is written back. When the server cannot be reached the next sync puts the cache right.
 * Adding and removing wait for the server.
 */
class ServerFeedRepository(
    private val database: MorningIssueDatabase,
    private val connection: ServerConnection,
    private val images: ImageStore? = null,
) : FeedRepository, FeedFinder {
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()
    private val writer = CacheWriter(database)

    override fun observeFeeds(): Flow<List<Feed>> =
        feedDao.observeSubscribedWithDetails().map { feeds -> feeds.map { it.toModel() } }

    override fun observeFeed(feedId: Long): Flow<Feed?> = feedDao.observeWithDetails(feedId).map { it?.toModel() }

    override suspend fun getFeeds(): List<Feed> = feedDao.getSubscribedWithDetails().map { it.toModel() }

    override suspend fun getFeed(feedId: Long): Feed? = feedDao.getWithDetails(feedId)?.toModel()

    override fun observeTags(): Flow<List<Tag>> = tagDao.observeAll().map { tags -> tags.map { it.toModel() } }

    override suspend fun discover(address: String): FeedSearch = try {
        val found = connection.call { it.discoverFeeds(address) }.candidates
        if (found.isEmpty()) FeedSearch.NoFeed else FeedSearch.Found(found)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.code == ApiError.NOT_A_FEED) FeedSearch.NoFeed else FeedSearch.Failed(e.message.orEmpty())
    }

    /** Subscribes on the server (which fetches the feed first). Throws [ApiException] when it cannot be reached or refuses. */
    override suspend fun addFeed(feed: NewFeed): AddFeedResult {
        val dto = connection.call { it.addFeed(AddFeedRequest(url = feed.url.trim(), title = feed.title?.trim()?.takeIf(String::isNotEmpty), tags = feed.tags)) }
        val wasSubscribed = feedDao.getById(dto.id)?.status == FeedStatus.SUBSCRIBED
        writer.upsertFeed(dto)
        return if (wasSubscribed) AddFeedResult.AlreadySubscribed(dto.id) else AddFeedResult.Added(dto.id)
    }

    override suspend fun removeFeed(feedId: Long) {
        val ok = attempt { connection.call { it.deleteFeed(feedId) } }
        if (!ok) return
        images?.deleteForFeed(feedId)
        database.withWriteTransaction {
            feedDao.delete(feedId)
            tagDao.deleteUnused()
        }
    }

    override suspend fun subscribe(feedId: Long): Boolean {
        val feed = feedDao.getById(feedId)?.takeIf { it.status == FeedStatus.DISCOVERED } ?: return false
        return attempt { writer.upsertFeed(connection.call { it.addFeed(AddFeedRequest(url = feed.url)) }) }
    }

    override suspend fun renameFeed(feedId: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        feedDao.updateTitle(feedId, clean)
        patch(feedId, FeedPatch(title = clean))
    }

    override suspend fun setSliderStep(feedId: Long, step: SliderStep) {
        feedDao.updateSliderStep(feedId, step)
        patch(feedId, FeedPatch(sliderStep = step))
    }

    override suspend fun setTags(feedId: Long, tags: List<String>) {
        tagDao.setFeedTags(feedId, tags)
        patch(feedId, FeedPatch(tags = tags.map { it.trim() }.filter { it.isNotEmpty() }))
    }

    override suspend fun setMutedUntil(feedId: Long, until: Instant?) {
        feedDao.updateMuteUntil(feedId, until)
        patch(feedId, FeedPatch(muteUntil = Patch.Set(until)))
    }

    private suspend fun patch(feedId: Long, patch: FeedPatch) {
        attempt { writer.upsertFeed(connection.call { api: MorningIssueApi -> api.patchFeed(feedId, patch) }) }
    }

    /** Writes a feed a REST answer gave into the cache. */
    suspend fun store(feed: FeedDto) = writer.upsertFeed(feed)

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        Log.w(TAG, "A change to the subscriptions did not reach the server", e)
        false
    }

    private companion object {
        const val TAG = "ServerFeeds"
    }
}

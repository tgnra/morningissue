package net.tgnra.morningissue.data.repository

import androidx.room.withTransaction
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.model.Tag

class RoomFeedRepository(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
) : FeedRepository {
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()

    override fun observeFeeds(): Flow<List<Feed>> =
        feedDao.observeAllWithDetails().map { feeds -> feeds.map { it.toModel() } }

    override fun observeFeed(feedId: Long): Flow<Feed?> =
        feedDao.observeWithDetails(feedId).map { it?.toModel() }

    override suspend fun getFeeds(): List<Feed> = feedDao.getAllWithDetails().map { it.toModel() }

    override suspend fun getFeed(feedId: Long): Feed? = feedDao.getWithDetails(feedId)?.toModel()

    override fun observeTags(): Flow<List<Tag>> = tagDao.observeAll().map { tags -> tags.map { it.toModel() } }

    override suspend fun addFeed(feed: NewFeed): AddFeedResult = database.withTransaction {
        val url = feed.url.trim()
        val key = FeedUrls.normalize(url)
        feedDao.getUrls().firstOrNull { FeedUrls.normalize(it.url) == key }?.let {
            return@withTransaction AddFeedResult.AlreadySubscribed(it.id)
        }
        val id = feedDao.insert(
            FeedEntity(
                url = url,
                siteUrl = feed.siteUrl,
                title = feed.title?.trim()?.takeIf { it.isNotEmpty() } ?: FeedUrls.hostOf(url),
                iconUrl = feed.iconUrl,
                addedAt = clock.instant(),
            ),
        )
        if (feed.tags.isNotEmpty()) tagDao.setFeedTags(id, feed.tags)
        AddFeedResult.Added(id)
    }

    override suspend fun removeFeed(feedId: Long) = database.withTransaction {
        feedDao.delete(feedId)
        tagDao.deleteUnused()
    }

    override suspend fun renameFeed(feedId: Long, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) feedDao.updateTitle(feedId, clean)
    }

    override suspend fun setSliderStep(feedId: Long, step: SliderStep) = feedDao.updateSliderStep(feedId, step)

    override suspend fun setTags(feedId: Long, tags: List<String>) = tagDao.setFeedTags(feedId, tags)

    override suspend fun setMutedUntil(feedId: Long, until: Instant?) = feedDao.updateMuteUntil(feedId, until)
}

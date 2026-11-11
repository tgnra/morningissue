package net.tgnra.morningissue.data.repository

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.model.Tag

class RoomFeedRepository(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    config: RankingConfig = RankingConfig.Default,
    /** The image store, so that a removed feed's pictures go with it; null in tests that store none. */
    private val images: ImageStore? = null,
) : FeedRepository {
    /** Tags and subscriptions shape affinity (a feed borrows from its tags), so they relearn it. */
    private val learner = AffinityLearner(database, config)
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()

    override fun observeFeeds(): Flow<List<Feed>> =
        feedDao.observeAllWithDetails().map { feeds -> feeds.map { it.toModel() } }

    override fun observeFeed(feedId: Long): Flow<Feed?> =
        feedDao.observeWithDetails(feedId).map { it?.toModel() }

    override suspend fun getFeeds(): List<Feed> = feedDao.getAllWithDetails().map { it.toModel() }

    override suspend fun getFeed(feedId: Long): Feed? = feedDao.getWithDetails(feedId)?.toModel()

    override fun observeTags(): Flow<List<Tag>> = tagDao.observeAll().map { tags -> tags.map { it.toModel() } }

    override suspend fun addFeed(feed: NewFeed): AddFeedResult = database.withWriteTransaction {
        val url = feed.url.trim()
        val key = FeedUrls.normalize(url)
        feedDao.getUrls().firstOrNull { FeedUrls.normalize(it.url) == key }?.let {
            return@withWriteTransaction AddFeedResult.AlreadySubscribed(it.id)
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
        if (feed.tags.isNotEmpty()) {
            tagDao.setFeedTags(id, feed.tags)
            learner.refresh(clock.instant())
        }
        AddFeedResult.Added(id)
    }

    override suspend fun removeFeed(feedId: Long) {
        // Files cannot be deleted by a cascade, so the feed's images go first, rows and files
        // together. If the removal then failed, all that would be lost is pictures that can be fetched again.
        images?.deleteForFeed(feedId)
        database.withWriteTransaction {
            feedDao.delete(feedId)
            tagDao.deleteUnused()
            learner.refresh(clock.instant())
        }
    }

    override suspend fun renameFeed(feedId: Long, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) feedDao.updateTitle(feedId, clean)
    }

    override suspend fun setSliderStep(feedId: Long, step: SliderStep) = feedDao.updateSliderStep(feedId, step)

    override suspend fun setTags(feedId: Long, tags: List<String>) = database.withWriteTransaction {
        tagDao.setFeedTags(feedId, tags)
        learner.refresh(clock.instant())
    }

    override suspend fun setMutedUntil(feedId: Long, until: Instant?) = feedDao.updateMuteUntil(feedId, until)
}

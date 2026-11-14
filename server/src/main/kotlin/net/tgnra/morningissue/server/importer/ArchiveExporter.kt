package net.tgnra.morningissue.server.importer

import androidx.room3.withReadTransaction
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.archive.ArchiveCategory
import net.tgnra.morningissue.data.archive.ArchiveContent
import net.tgnra.morningissue.data.archive.ArchiveEdition
import net.tgnra.morningissue.data.archive.ArchiveEngagement
import net.tgnra.morningissue.data.archive.ArchiveFeed
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveItem
import net.tgnra.morningissue.data.archive.ArchiveManifest
import net.tgnra.morningissue.data.archive.ArchivePageView
import net.tgnra.morningissue.data.archive.ArchivePost
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EngagementEntity
import net.tgnra.morningissue.server.db.ExportPostRow
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.StoredSettings
import net.tgnra.morningissue.server.editions.UserSettingsStore
import net.tgnra.morningissue.server.service.ExportReport
import net.tgnra.morningissue.server.service.ExportService

/**
 * Writes one user's library as a legacy archive (`ArchiveFormat`, version 2): the file the
 * phone app's "Restore from archive" reads, so a reader can move back to the phone. The
 * reverse of [ArchiveImporter], written as the app's `RoomArchiveTransfer.export` writes it.
 *
 * - **Feeds**: each of the user's subscriptions, in either status, under the title the user
 *   sees (their own name for it, else the feed's), with their tags, slider, mute and the time
 *   they added it. A feed the user holds posts of without subscribing (engaged with, or placed
 *   by an edition) comes as DISCOVERED, so those posts are not dropped.
 * - **Posts**: every post of those feeds the server holds, plus any other post the user
 *   engaged with or an edition of theirs placed, with its stored body, categories, enclosure
 *   kind and the user's engagement. `firstSeenAt` is when the user first saw it
 *   (`MAX(firstSeenAt, subscription.added_at)`), as their editions count it.
 * - **Editions**: the user's released editions with their items (kind, score, section,
 *   reason) and page views.
 * - **Settings** (`settings.json`): the schedule, edition length and time zone, when the user
 *   has set any (an account on the defaults writes none, so a server import elsewhere keeps
 *   its own).
 *
 * The file is written a line at a time inside one read transaction, so it is the library at
 * one moment and is never held in memory whole. Feed and post numbers are the server's IDs.
 * Server-only state (affinity, health flags, notify, discovery verdicts, devices, Kobo
 * pairing) has no place in the format and is left out.
 */
class ArchiveExporter(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
) : ExportService {
    private val json = ArchiveFormat.json
    private val dao = database.exportDao()
    private val signalDao = database.signalDao()

    /** Writes [userId]'s archive to [output]. Does not close [output]. */
    override suspend fun export(userId: Long, output: OutputStream): ExportReport = withContext(Dispatchers.IO) {
        // Settings are a JSON document beside the tables; read them first.
        val archivedSettings = if (settings.isCustomized(userId)) archiveSettings(settings.get(userId)) else null
        database.withReadTransaction {
            val now = clock.instant()
            val feeds = feeds(userId, now.toEpochMilli())
            val editions = dao.releasedEditions(userId, now)
            val postsTotal = dao.countPosts(userId)

            val zip = ZipOutputStream(BufferedOutputStream(output))
            val writer = zip.bufferedWriter(Charsets.UTF_8)
            fun begin(name: String) = zip.putNextEntry(ZipEntry(name))
            fun end() {
                writer.flush()
                zip.closeEntry()
            }
            fun line(text: String) {
                writer.write(text)
                writer.write("\n")
            }

            begin(ArchiveFormat.MANIFEST)
            line(json.encodeToString(ArchiveManifest(ArchiveFormat.FORMAT, ArchiveFormat.VERSION, now.toEpochMilli(), feeds.size, postsTotal, editions.size)))
            end()

            begin(ArchiveFormat.FEEDS)
            feeds.forEach { line(json.encodeToString(it)) }
            end()

            begin(ArchiveFormat.POSTS)
            var posts = 0
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.postsAfter(userId, afterId, PAGE)
                if (page.isEmpty()) break
                afterId = page.last().article.id
                val ids = page.map { it.article.id }
                val contents = dao.contents(ids).associateBy { it.articleId }
                val engagements = dao.engagements(userId, ids).associateBy { it.articleId }
                val categories = signalDao.categoriesOf(ids).groupBy({ it.articleId }) { ArchiveCategory(it.key, it.label) }
                val enclosures = dao.enclosures(ids).associate { it.articleId to it.enclosure.name }
                for (row in page) {
                    val id = row.article.id
                    val content = contents[id]?.let { ArchiveContent(it.blocksJson, it.source.name, it.storedAt.toEpochMilli()) }
                    line(json.encodeToString(row.toArchive(content, engagements[id]?.toArchive(), categories[id].orEmpty(), enclosures[id])))
                }
                posts += page.size
            }
            end()

            begin(ArchiveFormat.EDITIONS)
            for (edition in editions) {
                currentCoroutineContext().ensureActive()
                val items = dao.items(edition.id).map { ArchiveItem(it.articleId, it.page, it.slot, it.kind.name, it.score, it.section, it.reason) }
                val views = dao.pageViews(edition.id).map { ArchivePageView(it.page, it.msVisible, it.firstSeenAt.toEpochMilli()) }
                line(json.encodeToString(edition.toArchive(items, views)))
            }
            end()

            if (archivedSettings != null) {
                begin(ArchiveFormat.SETTINGS)
                line(json.encodeToString(archivedSettings))
                end()
            }

            // Writes the zip's directory; the caller closes the stream.
            zip.finish()
            zip.flush()
            ExportReport(feeds.size, posts, editions.size)
        }
    }

    /** The user's subscriptions, then the feeds of posts they hold without one, as DISCOVERED. */
    private suspend fun feeds(userId: Long, nowMillis: Long): List<ArchiveFeed> {
        val tags = dao.tagLinks(userId).groupBy({ it.feedId }) { it.name }
        val subscribed = dao.subscriptions(userId).map { row ->
            val s = row.subscription
            ArchiveFeed(
                n = s.feedId, url = row.feed.url, title = s.titleOverride ?: row.feed.title, siteUrl = row.feed.siteUrl,
                iconUrl = row.feed.iconUrl, tags = tags[s.feedId].orEmpty(), sliderStep = s.sliderStep.name,
                addedAt = s.addedAt.toEpochMilli(), status = s.status.name, discoveredAt = s.discoveredAt?.toEpochMilli(),
                muteUntil = s.muteUntil?.toEpochMilli(),
            )
        }
        val others = dao.otherFeeds(userId).map { feed ->
            ArchiveFeed(
                n = feed.id, url = feed.url, title = feed.title, siteUrl = feed.siteUrl, iconUrl = feed.iconUrl,
                addedAt = nowMillis, status = FeedStatus.DISCOVERED.name, discoveredAt = nowMillis,
            )
        }
        return subscribed + others
    }

    private fun ExportPostRow.toArchive(
        content: ArchiveContent?,
        engagement: ArchiveEngagement?,
        categories: List<ArchiveCategory>,
        enclosure: String?,
    ) = ArchivePost(
        n = article.id, feed = article.feedId, guid = article.guid, url = article.url, title = article.title,
        summary = article.summary, imageUrl = article.imageUrl, author = article.author,
        publishedAt = article.publishedAt.toEpochMilli(), firstSeenAt = seenAt.toEpochMilli(), wordCount = article.wordCount,
        feedWordCount = article.feedWordCount, contentHash = article.contentHash, content = content, engagement = engagement,
        categories = categories, enclosure = enclosure,
    )

    private fun EngagementEntity.toArchive() = ArchiveEngagement(
        openedAt?.toEpochMilli(), readFraction, readAt?.toEpochMilli(), savedAt?.toEpochMilli(), likedAt?.toEpochMilli(),
        editionsRunUnopened, leadCount,
    )

    private fun EditionEntity.toArchive(items: List<ArchiveItem>, pageViews: List<ArchivePageView>) = ArchiveEdition(
        date = date.toString(), kind = kind.name, cutoffAt = cutoffAt.toEpochMilli(), releaseAt = releaseAt.toEpochMilli(),
        builtAt = builtAt.toEpochMilli(), length = length.name, number = number, openedAt = openedAt?.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(), items = items,
        pageViewsTracked = pageViewsTracked, pageViews = pageViews.takeIf { it.isNotEmpty() },
    )

    companion object {
        private const val PAGE = 200

        /** The account's settings as `settings.json` holds them: the reverse of [ArchiveImporter.toStored]. */
        fun archiveSettings(stored: StoredSettings) = ArchiveSettings(
            resetTime = stored.resetTime,
            editionLength = stored.editionLength.name,
            eveningEnabled = stored.eveningEnabled,
            eveningTime = stored.eveningTime,
            timeZone = stored.timeZone,
            onboardingDone = stored.onboardingDone,
            nothingNewFor = stored.nothingNewFor,
            resetChange = stored.resetChange,
            eveningChange = stored.eveningChange,
        )
    }
}

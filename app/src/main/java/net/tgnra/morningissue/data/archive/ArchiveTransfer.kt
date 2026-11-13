package net.tgnra.morningissue.data.archive

import androidx.room3.withReadTransaction
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EngagementEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.EnclosureKind

data class ExportReport(val feeds: Int = 0, val posts: Int = 0, val editions: Int = 0)

/** How far an export or import has got, in posts (the bulk of the work). */
data class ArchiveProgress(val postsDone: Int, val postsTotal: Int)

/** The file is not an archive this version can read. */
class ArchiveFormatException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NOT_AN_ARCHIVE, NEWER_VERSION }
}

/**
 * Writes the whole library (feeds with their tags, every stored post with its text, its feed
 * categories and the reader's engagement, and every released edition) to one file: a copy
 * the reader keeps, and the upload of the library to the server (`POST /import`). The server
 * does the importing now.
 * Implemented by [RoomArchiveTransfer]. The file format is [ArchiveFormat].
 */
interface ArchiveTransfer {
    /** Writes the archive to [output], a line at a time. Does not close [output]. */
    suspend fun export(output: OutputStream, onProgress: (ArchiveProgress) -> Unit = {}): ExportReport

}

class RoomArchiveTransfer(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The settings written to `settings.json`; null writes no such entry. Import ignores the entry. */
    private val settings: (suspend () -> AppSettings)? = null,
    /** The time zone written with the settings: the phone's own. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ArchiveTransfer {
    private val json = ArchiveFormat.json
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()
    private val articleDao = database.articleDao()
    private val engagementDao = database.engagementDao()
    private val editionDao = database.editionDao()
    private val archiveDao = database.archiveDao()
    private val signalDao = database.articleSignalDao()

    // --- Export ----------------------------------------------------------------------------

    override suspend fun export(output: OutputStream, onProgress: (ArchiveProgress) -> Unit): ExportReport = withContext(dispatcher) {
        // Read before the transaction: the settings are not in the database.
        val archivedSettings = settings?.invoke()?.let { ArchiveSettings.of(it, zone()) }
        // One read transaction, so the file is the library at one moment.
        database.withReadTransaction {
            val now = clock.instant()
            val feeds = feedDao.getAllWithDetailsIncludingDiscovered()
            val editions = archiveDao.releasedEditions(now)
            val postsTotal = articleDao.count()
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
            line(
                json.encodeToString(
                    ArchiveManifest(ArchiveFormat.FORMAT, ArchiveFormat.VERSION, now.toEpochMilli(), feeds.size, postsTotal, editions.size),
                ),
            )
            end()

            begin(ArchiveFormat.FEEDS)
            for (details in feeds) {
                val feed = details.feed
                line(
                    json.encodeToString(
                        ArchiveFeed(
                            n = feed.id, url = feed.url, title = feed.title, siteUrl = feed.siteUrl, iconUrl = feed.iconUrl,
                            tags = details.tags.map { it.name }, sliderStep = feed.sliderStep.name, addedAt = feed.addedAt.toEpochMilli(),
                            status = feed.status.name, discoveredAt = feed.discoveredAt?.toEpochMilli(),
                            muteUntil = feed.muteUntil?.toEpochMilli(),
                        ),
                    ),
                )
            }
            end()

            begin(ArchiveFormat.POSTS)
            var posts = 0
            var afterId = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = archiveDao.articlesAfter(afterId, PAGE)
                if (page.isEmpty()) break
                afterId = page.last().id
                val ids = page.map { it.id }
                val contents = archiveDao.contents(ids).associateBy { it.articleId }
                val engagements = archiveDao.engagements(ids).associateBy { it.articleId }
                val categories = signalDao.categoriesOf(ids).groupBy({ it.articleId }) { ArchiveCategory(it.key, it.label) }
                val enclosures = signalDao.traitsOf(ids).filter { it.enclosure != EnclosureKind.NONE }.associate { it.articleId to it.enclosure.name }
                for (article in page) {
                    val content = contents[article.id]?.let { ArchiveContent(it.blocksJson, it.source.name, it.storedAt.toEpochMilli()) }
                    val post = article.toArchive(content, engagements[article.id]?.toArchive())
                    line(json.encodeToString(post.copy(categories = categories[article.id].orEmpty(), enclosure = enclosures[article.id])))
                }
                posts += page.size
                onProgress(ArchiveProgress(posts, postsTotal))
            }
            end()

            begin(ArchiveFormat.EDITIONS)
            for (edition in editions) {
                currentCoroutineContext().ensureActive()
                val items = archiveDao.items(edition.id).map {
                    ArchiveItem(it.articleId, it.page, it.slot, it.kind.name, it.score, it.section, it.reason)
                }
                val pageViews = editionDao.getPageViews(edition.id)
                    .map { ArchivePageView(it.page, it.msVisible, it.firstSeenAt.toEpochMilli()) }
                line(json.encodeToString(edition.toArchive(items, pageViews)))
            }
            end()

            if (archivedSettings != null) {
                begin(ArchiveFormat.SETTINGS)
                line(json.encodeToString(archivedSettings))
                end()
            }

            // Writes the zip's directory; the caller closes the stream itself.
            zip.finish()
            zip.flush()
            ExportReport(feeds.size, posts, editions.size)
        }
    }

    private fun ArticleEntity.toArchive(content: ArchiveContent?, engagement: ArchiveEngagement?) = ArchivePost(
        n = id, feed = feedId, guid = guid, url = url, title = title, summary = summary, imageUrl = imageUrl, author = author,
        publishedAt = publishedAt.toEpochMilli(), firstSeenAt = firstSeenAt.toEpochMilli(), wordCount = wordCount,
        feedWordCount = feedWordCount,
        contentHash = contentHash, content = content, engagement = engagement,
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

    private companion object {
        const val PAGE = 200
    }
}

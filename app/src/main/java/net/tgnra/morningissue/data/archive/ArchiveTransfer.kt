package net.tgnra.morningissue.data.archive

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.EngagementEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

data class ExportReport(val feeds: Int = 0, val posts: Int = 0, val editions: Int = 0)

data class ImportReport(
    /** Posts the database did not hold. */
    val postsImported: Int = 0,
    /** Posts it already held (matched by feed address and post ID), left as they were. */
    val postsSkipped: Int = 0,
    /** Of the posts already held, those whose trimmed text was put back. Not counted in [postsSkipped]. */
    val textsRestored: Int = 0,
    val editionsImported: Int = 0,
    /** Editions already held (matched by date and kind). */
    val editionsSkipped: Int = 0,
    /** Feeds in the archive that were not subscribed and have been added back. */
    val feedsAdded: Int = 0,
)

/** How far an export or import has got, in posts (the bulk of the work). */
data class ArchiveProgress(val postsDone: Int, val postsTotal: Int)

/** The file is not an archive this version can read. */
class ArchiveFormatException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NOT_AN_ARCHIVE, NEWER_VERSION }
}

/**
 * Writes the whole library (feeds with their tags, every stored post with its text and the
 * reader's engagement, and every released edition) to one file, and merges such a file back.
 * Implemented by [RoomArchiveTransfer]. The file format is [ArchiveFormat].
 */
interface ArchiveTransfer {
    /** Writes the archive to [output], a line at a time. Does not close [output]. */
    suspend fun export(output: OutputStream, onProgress: (ArchiveProgress) -> Unit = {}): ExportReport

    /**
     * Merges an archive into the database. [open] is called once per pass over the file and
     * must return a fresh stream each time; each is closed here.
     *
     * - A feed is matched by its normalised address; one not subscribed is added back with
     *   its tags and slider. Feeds already subscribed keep their own tags and slider.
     * - A post is matched by feed and post ID. One already held is left alone, except that
     *   text it has lost to trimming is put back.
     * - Engagement is merged without overwriting anything set locally.
     * - An edition is matched by date and kind; one already held is skipped.
     *
     * @throws ArchiveFormatException when the file is not an archive, or is from a newer version.
     */
    suspend fun import(open: () -> InputStream, onProgress: (ArchiveProgress) -> Unit = {}): ImportReport
}

class RoomArchiveTransfer(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ArchiveTransfer {
    private val json = ArchiveFormat.json
    private val feedDao = database.feedDao()
    private val tagDao = database.tagDao()
    private val articleDao = database.articleDao()
    private val engagementDao = database.engagementDao()
    private val editionDao = database.editionDao()
    private val archiveDao = database.archiveDao()

    // --- Export ----------------------------------------------------------------------------

    override suspend fun export(output: OutputStream, onProgress: (ArchiveProgress) -> Unit): ExportReport = withContext(dispatcher) {
        // One read transaction, so the file is the library at one moment.
        database.withReadTransaction {
            val now = clock.instant()
            val feeds = feedDao.getAllWithDetails()
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
                for (article in page) {
                    val content = contents[article.id]?.let { ArchiveContent(it.blocksJson, it.source.name, it.storedAt.toEpochMilli()) }
                    line(json.encodeToString(article.toArchive(content, engagements[article.id]?.toArchive())))
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
                line(json.encodeToString(edition.toArchive(items)))
            }
            end()

            // Writes the zip's directory; the caller closes the stream itself.
            zip.finish()
            zip.flush()
            ExportReport(feeds.size, posts, editions.size)
        }
    }

    private fun ArticleEntity.toArchive(content: ArchiveContent?, engagement: ArchiveEngagement?) = ArchivePost(
        n = id, feed = feedId, guid = guid, url = url, title = title, summary = summary, imageUrl = imageUrl, author = author,
        publishedAt = publishedAt.toEpochMilli(), firstSeenAt = firstSeenAt.toEpochMilli(), wordCount = wordCount,
        contentHash = contentHash, content = content, engagement = engagement,
    )

    private fun EngagementEntity.toArchive() = ArchiveEngagement(
        openedAt?.toEpochMilli(), readFraction, readAt?.toEpochMilli(), savedAt?.toEpochMilli(), likedAt?.toEpochMilli(),
        editionsRunUnopened, leadCount,
    )

    private fun EditionEntity.toArchive(items: List<ArchiveItem>) = ArchiveEdition(
        date = date.toString(), kind = kind.name, cutoffAt = cutoffAt.toEpochMilli(), releaseAt = releaseAt.toEpochMilli(),
        builtAt = builtAt.toEpochMilli(), length = length.name, number = number, openedAt = openedAt?.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(), items = items,
    )

    // --- Import ----------------------------------------------------------------------------

    override suspend fun import(open: () -> InputStream, onProgress: (ArchiveProgress) -> Unit): ImportReport = withContext(dispatcher) {
        val manifest = readEntry(open, ArchiveFormat.MANIFEST) { lines ->
            lines.firstOrNull()?.let { decode<ArchiveManifest>(it) }
        } ?: throw ArchiveFormatException(ArchiveFormatException.Reason.NOT_AN_ARCHIVE)
        if (manifest.format != ArchiveFormat.FORMAT) throw ArchiveFormatException(ArchiveFormatException.Reason.NOT_AN_ARCHIVE)
        if (manifest.version > ArchiveFormat.VERSION) throw ArchiveFormatException(ArchiveFormatException.Reason.NEWER_VERSION)

        var report = ImportReport()

        // Feeds: archive number to local feed ID.
        val feedIds = HashMap<Long, Long>()
        readEntry(open, ArchiveFormat.FEEDS) { lines ->
            val feeds = lines.mapNotNull { decode<ArchiveFeed>(it) }.toList()
            database.withWriteTransaction {
                val known = feedDao.getUrls().associate { FeedUrls.normalize(it.url) to it.id }.toMutableMap()
                var added = 0
                for (feed in feeds) {
                    val key = FeedUrls.normalize(feed.url)
                    val id = known[key] ?: run {
                        val newId = feedDao.insert(
                            FeedEntity(
                                url = feed.url, siteUrl = feed.siteUrl, title = feed.title, iconUrl = feed.iconUrl,
                                sliderStep = enumOrNull<SliderStep>(feed.sliderStep) ?: SliderStep.AUTO,
                                addedAt = Instant.ofEpochMilli(feed.addedAt),
                            ),
                        )
                        if (newId == -1L) return@run null
                        if (feed.tags.isNotEmpty()) tagDao.setFeedTags(newId, feed.tags)
                        known[key] = newId
                        added++
                        newId
                    } ?: continue
                    feedIds[feed.n] = id
                }
                report = report.copy(feedsAdded = added)
            }
        }

        // Posts, a batch per transaction: archive number to local article ID.
        val postIds = HashMap<Long, Long>()
        readEntry(open, ArchiveFormat.POSTS) { lines ->
            var done = 0
            for (batch in lines.chunked(BATCH)) {
                currentCoroutineContext().ensureActive()
                database.withWriteTransaction {
                    for (text in batch) {
                        val post = decode<ArchivePost>(text)
                        val feedId = post?.let { feedIds[it.feed] }
                        if (post == null || feedId == null) {
                            report = report.copy(postsSkipped = report.postsSkipped + 1)
                            continue
                        }
                        report = importPost(post, feedId, postIds, report)
                    }
                }
                done += batch.size
                onProgress(ArchiveProgress(done, maxOf(manifest.posts, done)))
            }
        }

        readEntry(open, ArchiveFormat.EDITIONS) { lines ->
            for (text in lines) {
                currentCoroutineContext().ensureActive()
                val edition = decode<ArchiveEdition>(text) ?: continue
                val imported = database.withWriteTransaction { importEdition(edition, postIds) }
                report = if (imported) report.copy(editionsImported = report.editionsImported + 1)
                else report.copy(editionsSkipped = report.editionsSkipped + 1)
            }
        }

        // Imported runs and engagement are evidence for affinity like any other.
        database.withWriteTransaction { AffinityLearner(database, config).refresh(clock.instant()) }
        report
    }

    private suspend fun importPost(post: ArchivePost, feedId: Long, postIds: MutableMap<Long, Long>, report: ImportReport): ImportReport {
        val existing = articleDao.getByGuid(feedId, post.guid)
        val content = post.content?.let { content ->
            val blocks = ContentBlocks.decode(content.blocks)
            val source = enumOrNull<ContentSource>(content.source)
            if (blocks.isEmpty() || source == null) null else Triple(content, ContentBlocks.plainText(blocks), source)
        }
        val articleId: Long
        var result = report
        if (existing == null) {
            articleId = articleDao.insertNew(
                ArticleEntity(
                    feedId = feedId, guid = post.guid, url = post.url, title = post.title, summary = post.summary,
                    imageUrl = post.imageUrl, author = post.author, publishedAt = Instant.ofEpochMilli(post.publishedAt),
                    firstSeenAt = Instant.ofEpochMilli(post.firstSeenAt), wordCount = post.wordCount, contentHash = post.contentHash,
                ),
            ) ?: return report.copy(postsSkipped = report.postsSkipped + 1)
            if (content != null) storeContent(articleId, post.wordCount, content)
            result = result.copy(postsImported = result.postsImported + 1)
        } else {
            articleId = existing.id
            val held = articleDao.getContentSource(articleId)
            // Put back text lost to trimming; a fetched full text also replaces a feed excerpt.
            val restore = content != null && (held == null || (held == ContentSource.FEED && content.third == ContentSource.FETCHED))
            if (restore) {
                storeContent(articleId, post.wordCount, content!!)
                result = result.copy(textsRestored = result.textsRestored + 1)
            } else {
                result = result.copy(postsSkipped = result.postsSkipped + 1)
            }
        }
        postIds[post.n] = articleId
        post.engagement?.let { archived ->
            val local = engagementDao.get(articleId)
            val merged = mergeEngagement(articleId, local, archived)
            if (merged != local) archiveDao.upsertEngagement(merged)
        }
        return result
    }

    private suspend fun storeContent(articleId: Long, wordCount: Int, content: Triple<ArchiveContent, String, ContentSource>) {
        val (archived, plainText, source) = content
        articleDao.storeContent(articleId, archived.blocks, plainText, wordCount, source, Instant.ofEpochMilli(archived.storedAt))
    }

    /** Returns false when an edition for the same date and kind is already held. */
    private suspend fun importEdition(edition: ArchiveEdition, postIds: Map<Long, Long>): Boolean {
        val date = runCatching { LocalDate.parse(edition.date) }.getOrNull() ?: return false
        val kind = enumOrNull<EditionKind>(edition.kind) ?: return false
        val length = enumOrNull<EditionLength>(edition.length) ?: EditionLength.STANDARD
        if (editionDao.getByDate(date, kind) != null) return false
        val items = edition.items.mapNotNull { item ->
            val articleId = postIds[item.post] ?: return@mapNotNull null
            val itemKind = enumOrNull<ItemKind>(item.kind) ?: return@mapNotNull null
            EditionItemEntity(0, articleId, item.page, item.section, item.slot, itemKind, item.score, item.reason)
        }.distinctBy { it.articleId }
        editionDao.insertEditionWithItems(
            EditionEntity(
                date = date, kind = kind, cutoffAt = Instant.ofEpochMilli(edition.cutoffAt),
                releaseAt = Instant.ofEpochMilli(edition.releaseAt), builtAt = Instant.ofEpochMilli(edition.builtAt),
                length = length, number = edition.number, openedAt = edition.openedAt?.let(Instant::ofEpochMilli),
                finishedAt = edition.finishedAt?.let(Instant::ofEpochMilli),
            ),
            items,
        )
        return true
    }

    private inline fun <reified T> decode(line: String): T? {
        if (line.isBlank()) return null
        return try {
            json.decodeFromString<T>(line)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

    /** Runs [block] over the lines of the zip entry [name]; returns null when the file has no such entry. */
    private inline fun <R> readEntry(open: () -> InputStream, name: String, block: (Sequence<String>) -> R): R? {
        ZipInputStream(BufferedInputStream(open())).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (e: java.util.zip.ZipException) {
                    null
                } ?: return null
                if (entry.name == name) return block(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).lineSequence())
            }
        }
    }

    companion object {
        private const val PAGE = 200
        private const val BATCH = 100

        /**
         * Local engagement wins wherever it says anything: a time set locally is never
         * replaced, and counters only go up. The archive fills in what is missing, so Likes and
         * saves come back after a feed was removed or on a new phone.
         */
        internal fun mergeEngagement(articleId: Long, local: EngagementEntity?, archived: ArchiveEngagement): EngagementEntity {
            val base = local ?: EngagementEntity(articleId = articleId)
            return base.copy(
                openedAt = base.openedAt ?: archived.openedAt?.let(Instant::ofEpochMilli),
                readFraction = maxOf(base.readFraction, archived.readFraction.coerceIn(0f, 1f)),
                readAt = base.readAt ?: archived.readAt?.let(Instant::ofEpochMilli),
                savedAt = base.savedAt ?: archived.savedAt?.let(Instant::ofEpochMilli),
                likedAt = base.likedAt ?: archived.likedAt?.let(Instant::ofEpochMilli),
                editionsRunUnopened = maxOf(base.editionsRunUnopened, archived.editionsRunUnopened),
                leadCount = maxOf(base.leadCount, archived.leadCount),
            )
        }
    }
}

package net.tgnra.morningissue.server.importer

import androidx.room3.withWriteTransaction
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.data.archive.ArchiveContent
import net.tgnra.morningissue.data.archive.ArchiveEdition
import net.tgnra.morningissue.data.archive.ArchiveEngagement
import net.tgnra.morningissue.data.archive.ArchiveFeed
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveManifest
import net.tgnra.morningissue.data.archive.ArchivePost
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.FeedWordCount
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.ArticleTraitsEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import net.tgnra.morningissue.server.db.EditionPageViewEntity
import net.tgnra.morningissue.server.db.EngagementEntity
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SubscriptionEntity
import net.tgnra.morningissue.server.editions.AffinityLearner
import net.tgnra.morningissue.server.editions.StoredSettings
import net.tgnra.morningissue.server.editions.UserSettingsStore

/** The file is not an archive this version can read. */
class ArchiveFormatException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_AN_ARCHIVE, NEWER_VERSION }
}

/** How far an import has got, in posts (the bulk of the work). */
fun interface ImportProgress {
    fun report(postsDone: Int, postsTotal: Int)
}

/**
 * Merges a legacy archive (`ArchiveFormat`, 4.13) into one user's account: the import half of
 * the app's `RoomArchiveTransfer`, with its merge rules applied per user (4.9).
 *
 * - **Feeds** by normalised address: the shared global feed is reused (or created), and the
 *   user gets a subscription with the file's tags, slider, mute and status. A subscription the
 *   user already has keeps its own settings; a DISCOVERED one becomes SUBSCRIBED when the file
 *   says so. A version 1 file's feeds are all subscriptions.
 * - **Posts** by feed and post ID (global). One the server already holds is left alone, except
 *   that a body it lacks is filled in, and a fetched full text replaces a feed excerpt.
 * - **Engagement** fills gaps for the user and never overwrites.
 * - **Categories and enclosure** fill what the post lacks.
 * - **Editions** by date and kind for the user, keeping their numbers; one already held is
 *   skipped. An imported edition records page views only when its line carries them.
 * - **Settings** (`settings.json`) apply only while the account has none of its own
 *   (`user_settings.customized` false): an empty account takes the phone's schedule; one in use
 *   keeps its own.
 *
 * Afterwards the user's affinity is relearned. Imported engagement is not a device's events
 * and moves no `acked_seq`.
 */
class ArchiveImporter(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val json = ArchiveFormat.json
    private val importDao = database.importDao()
    private val editionDao = database.editionDao()
    private val engagementDao = database.engagementDao()
    private val userStateDao = database.userStateDao()

    /**
     * Imports the archive [open] returns (called once per pass, a fresh stream each time;
     * each is closed here) into [userId]'s account.
     *
     * @throws ArchiveFormatException when it is not an archive, or is from a newer version
     */
    suspend fun import(userId: Long, open: () -> InputStream, onProgress: ImportProgress = ImportProgress { _, _ -> }): ImportReportDto {
        val manifest = readEntry(open, ArchiveFormat.MANIFEST) { lines -> lines.firstOrNull()?.let { decode<ArchiveManifest>(it) } }
            ?: throw ArchiveFormatException(ArchiveFormatException.Reason.NOT_AN_ARCHIVE)
        if (manifest.format != ArchiveFormat.FORMAT) throw ArchiveFormatException(ArchiveFormatException.Reason.NOT_AN_ARCHIVE)
        if (manifest.version > ArchiveFormat.VERSION) throw ArchiveFormatException(ArchiveFormatException.Reason.NEWER_VERSION)

        var report = ImportReportDto()
        val now = clock.instant()

        // Feeds: archive number to global feed ID.
        val feedIds = HashMap<Long, Long>()
        readEntry(open, ArchiveFormat.FEEDS) { lines ->
            val feeds = lines.mapNotNull { decode<ArchiveFeed>(it) }.toList()
            database.withWriteTransaction {
                var added = 0
                for (feed in feeds) {
                    val (id, subscribed) = importFeed(userId, feed, now) ?: continue
                    if (subscribed) added++
                    feedIds[feed.n] = id
                }
                report = report.copy(feeds = added)
            }
        }

        // Posts, a batch per transaction: archive number to article ID.
        val postIds = HashMap<Long, Long>()
        readEntry(open, ArchiveFormat.POSTS) { lines ->
            var done = 0
            for (batch in lines.chunked(BATCH)) {
                currentCoroutineContext().ensureActive()
                database.withWriteTransaction {
                    val seq = userStateDao.nextChangeSeq(userId)
                    for (text in batch) {
                        val post = decode<ArchivePost>(text)
                        val feedId = post?.let { feedIds[it.feed] }
                        report = if (post == null || feedId == null) {
                            report.copy(skippedPosts = report.skippedPosts + 1)
                        } else {
                            importPost(userId, post, feedId, postIds, report, seq)
                        }
                    }
                }
                done += batch.size
                onProgress.report(done, maxOf(manifest.posts, done))
            }
        }

        readEntry(open, ArchiveFormat.EDITIONS) { lines ->
            for (text in lines) {
                currentCoroutineContext().ensureActive()
                val edition = decode<ArchiveEdition>(text) ?: continue
                val imported = database.withWriteTransaction { importEdition(userId, edition, postIds) }
                report = if (imported) report.copy(editions = report.editions + 1) else report.copy(skippedEditions = report.skippedEditions + 1)
            }
        }

        val archived = readEntry(open, ArchiveFormat.SETTINGS) { lines -> lines.firstOrNull()?.let { decode<ArchiveSettings>(it) } }
        if (archived != null) {
            val applied = database.withWriteTransaction {
                if (settings.isCustomized(userId)) return@withWriteTransaction false
                val stored = toStored(archived, settings.get(userId)) ?: return@withWriteTransaction false
                settings.update(userId, customize = true) { stored }
                true
            }
            report = report.copy(settingsApplied = applied)
        }

        // Imported runs and engagement are evidence for affinity like any other.
        database.withWriteTransaction { AffinityLearner(database, config).refresh(userId, clock.instant()) }
        return report
    }

    /** The user's subscription to [feed]'s global feed, made if missing; null when the feed cannot be stored. Second: a subscription was added. */
    private suspend fun importFeed(userId: Long, feed: ArchiveFeed, now: Instant): Pair<Long, Boolean>? {
        val key = FeedUrls.normalize(feed.url)
        val feedId = importDao.feedIdByUrlKey(key) ?: run {
            val inserted = importDao.insertFeed(
                FeedEntity(url = feed.url, urlKey = key, siteUrl = feed.siteUrl, title = feed.title, iconUrl = feed.iconUrl, addedAt = now),
            )
            if (inserted == -1L) importDao.feedIdByUrl(feed.url) else inserted
        } ?: return null
        // A version 1 file has no status: every feed in it was a subscription.
        val status = enumOrNull<FeedStatus>(feed.status) ?: FeedStatus.SUBSCRIBED
        val addedAt = Instant.ofEpochMilli(feed.addedAt)
        val existing = importDao.subscription(userId, feedId)
        if (existing != null) {
            // Subscribed in the file, only discovered here: the file's word stands.
            val promoted = status == FeedStatus.SUBSCRIBED && importDao.promoteDiscovered(userId, feedId, addedAt) > 0
            return feedId to promoted
        }
        importDao.insertSubscription(
            SubscriptionEntity(
                userId = userId,
                feedId = feedId,
                sliderStep = enumOrNull<SliderStep>(feed.sliderStep) ?: SliderStep.AUTO,
                muteUntil = feed.muteUntil?.let(Instant::ofEpochMilli),
                status = status,
                addedAt = addedAt,
                discoveredAt = if (status == FeedStatus.DISCOVERED) Instant.ofEpochMilli(feed.discoveredAt ?: feed.addedAt) else null,
            ),
        )
        if (feed.tags.isNotEmpty()) importDao.addSubscriptionTags(userId, feedId, feed.tags)
        return feedId to (status == FeedStatus.SUBSCRIBED)
    }

    private suspend fun importPost(
        userId: Long,
        post: ArchivePost,
        feedId: Long,
        postIds: MutableMap<Long, Long>,
        report: ImportReportDto,
        seq: Long,
    ): ImportReportDto {
        val existing = importDao.articleByGuid(feedId, post.guid)
        val content = post.content?.let { content ->
            val blocks = ContentBlocks.decode(content.blocks)
            val source = enumOrNull<ContentSource>(content.source)
            if (blocks.isEmpty() || source == null) null else Triple(content, ContentBlocks.plainText(blocks), source)
        }
        val articleId: Long
        var result = report
        if (existing == null) {
            articleId = importDao.insertNew(
                ArticleEntity(
                    feedId = feedId, guid = post.guid, url = post.url, title = post.title, summary = post.summary,
                    imageUrl = post.imageUrl, author = post.author, publishedAt = Instant.ofEpochMilli(post.publishedAt),
                    firstSeenAt = Instant.ofEpochMilli(post.firstSeenAt), wordCount = post.wordCount,
                    feedWordCount = post.feedWordCount ?: FeedWordCount.estimate(post.wordCount, post.summary, content?.third),
                    contentHash = post.contentHash,
                ),
            ) ?: return report.copy(skippedPosts = report.skippedPosts + 1)
            if (content != null) storeContent(articleId, post.wordCount, content)
            result = result.copy(posts = result.posts + 1)
        } else {
            articleId = existing.id
            val held = importDao.contentSource(articleId)
            // Fill a body the server lacks; a fetched full text also replaces a feed excerpt.
            val restore = content != null && (held == null || (held == ContentSource.FEED && content.third == ContentSource.FETCHED))
            if (restore) storeContent(articleId, post.wordCount, content!!)
            result = result.copy(skippedPosts = result.skippedPosts + 1)
        }
        postIds[post.n] = articleId
        importSignals(post, articleId, isNew = existing == null)
        post.engagement?.let { archived ->
            val local = engagementDao.get(userId, articleId)
            val merged = mergeEngagement(userId, articleId, local, archived)
            if (merged != local) engagementDao.upsert(if (merged.visiblyDiffers(local)) merged.copy(changeSeq = seq) else merged)
        }
        return result
    }

    private suspend fun importSignals(post: ArchivePost, articleId: Long, isNew: Boolean) {
        val categories = post.categories.mapNotNull { ArticleCategories.of(it.key, it.label) }
        if (categories.isNotEmpty() && (isNew || importDao.categoryCount(articleId) == 0)) {
            importDao.insertCategories(ArticleCategories.clean(categories).map { ArticleCategoryEntity(articleId, it.key, it.label) })
        }
        val enclosure = post.enclosure?.let { enumOrNull<EnclosureKind>(it) }?.takeIf { it != EnclosureKind.NONE } ?: return
        val held = if (isNew) null else importDao.traits(articleId)
        when {
            held == null -> importDao.upsertTraits(
                ArticleTraitsEntity(articleId, ArticleTraitRules.VERSION_PENDING, 0, 0, false, enclosure, false, null),
            )
            held.enclosure == EnclosureKind.NONE -> importDao.updateEnclosure(articleId, enclosure)
        }
    }

    private suspend fun storeContent(articleId: Long, wordCount: Int, content: Triple<ArchiveContent, String, ContentSource>) {
        val (archived, plainText, source) = content
        importDao.storeContent(articleId, archived.blocks, plainText, wordCount, source, Instant.ofEpochMilli(archived.storedAt))
    }

    /** Returns false when the user already has an edition for the same date and kind. */
    private suspend fun importEdition(userId: Long, edition: ArchiveEdition, postIds: Map<Long, Long>): Boolean {
        val date = runCatching { LocalDate.parse(edition.date) }.getOrNull() ?: return false
        val kind = enumOrNull<EditionKind>(edition.kind) ?: return false
        val length = enumOrNull<EditionLength>(edition.length) ?: EditionLength.STANDARD
        if (editionDao.getByDate(userId, date, kind) != null) return false
        val items = edition.items.mapNotNull { item ->
            val articleId = postIds[item.post] ?: return@mapNotNull null
            val itemKind = enumOrNull<ItemKind>(item.kind) ?: return@mapNotNull null
            EditionItemEntity(0, articleId, item.page, item.section, item.slot, itemKind, item.score, item.reason)
        }.distinctBy { it.articleId }
        val views = edition.pageViews.orEmpty()
        val tracked = edition.pageViewsTracked != false && views.isNotEmpty()
        val id = editionDao.insertEditionWithItems(
            EditionEntity(
                userId = userId,
                date = date, kind = kind, cutoffAt = Instant.ofEpochMilli(edition.cutoffAt),
                releaseAt = Instant.ofEpochMilli(edition.releaseAt), builtAt = Instant.ofEpochMilli(edition.builtAt),
                length = length, number = edition.number, openedAt = edition.openedAt?.let(Instant::ofEpochMilli),
                finishedAt = edition.finishedAt?.let(Instant::ofEpochMilli),
                pageViewsTracked = tracked,
                changeSeq = userStateDao.nextChangeSeq(userId),
            ),
            items,
        )
        if (tracked) {
            editionDao.insertPageViews(
                views.filter { it.page >= 1 && it.ms >= 0 }.distinctBy { it.page }
                    .map { EditionPageViewEntity(id, it.page, it.ms, Instant.ofEpochMilli(it.firstSeenAt)) },
            )
        }
        return true
    }

    private inline fun <reified T> decode(line: String): T? {
        if (line.isBlank()) return null
        return try {
            json.decodeFromString<T>(line)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Runs [block] over the lines of the zip entry [name]; null when the file has no such entry. */
    private inline fun <R> readEntry(open: () -> InputStream, name: String, block: (Sequence<String>) -> R): R? {
        ZipInputStream(BufferedInputStream(open())).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (_: ZipException) {
                    null
                } ?: return null
                if (entry.name == name) return block(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).lineSequence())
            }
        }
    }

    companion object {
        private const val BATCH = 100

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

        /**
         * The user's engagement wins wherever it says anything: a time already set is never
         * replaced, and counters only go up. The archive fills in what is missing.
         */
        fun mergeEngagement(userId: Long, articleId: Long, local: EngagementEntity?, archived: ArchiveEngagement): EngagementEntity {
            val base = local ?: EngagementEntity(userId = userId, articleId = articleId)
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

        /** Whether the engagement delta would show a difference between [this] and [other]. */
        private fun EngagementEntity.visiblyDiffers(other: EngagementEntity?): Boolean =
            other == null || openedAt != other.openedAt || readFraction != other.readFraction || readAt != other.readAt ||
                savedAt != other.savedAt || likedAt != other.likedAt

        /**
         * The phone's schedule and edition settings as the account's, keeping what the file
         * does not say ([current]'s other fields). Null when the file's values cannot be read.
         */
        fun toStored(archived: ArchiveSettings, current: StoredSettings): StoredSettings? {
            val reset = runCatching { LocalTime.parse(archived.resetTime) }.getOrNull() ?: return null
            val evening = runCatching { LocalTime.parse(archived.eveningTime) }.getOrNull() ?: return null
            val zone = try {
                ZoneId.of(archived.timeZone).id
            } catch (_: DateTimeException) {
                current.timeZone
            }
            return current.copy(
                resetTime = StoredSettings.time(reset),
                editionLength = enumOrNull<EditionLength>(archived.editionLength) ?: current.editionLength,
                eveningEnabled = archived.eveningEnabled,
                eveningTime = StoredSettings.time(evening),
                timeZone = zone,
                onboardingDone = archived.onboardingDone || current.onboardingDone,
                nothingNewFor = archived.nothingNewFor,
                resetChange = archived.resetChange,
                eveningChange = archived.eveningChange,
            )
        }
    }
}

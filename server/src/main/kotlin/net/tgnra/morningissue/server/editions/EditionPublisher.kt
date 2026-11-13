package net.tgnra.morningissue.server.editions

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.AwayPool
import net.tgnra.morningissue.engine.Candidate
import net.tgnra.morningissue.engine.EditionBuilder
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.EditionDraft
import net.tgnra.morningissue.engine.FeedProfile
import net.tgnra.morningissue.engine.LearnedAffinities
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.ReadingListPick
import net.tgnra.morningissue.engine.Release
import net.tgnra.morningissue.engine.Scorer
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.UserArticleRow
import net.tgnra.morningissue.server.db.UserFeedRow
import net.tgnra.morningissue.server.db.UserTagLinkRow

/**
 * Builds and freezes editions for each user from stored posts: the app's `RoomEditionPublisher`
 * with `userId` threaded through. It never touches the network. The class comment of the app's
 * publisher holds the rules (windows, Evening and Extra editions, superseding, held-over posts,
 * learning, subscribed feeds only, nothing to build, time zones); they are unchanged, except:
 *
 * - "first seen" is the user's effective first-seen time, `MAX(article.firstSeenAt,
 *   subscription.added_at)` (`EditionArticleDao.firstSeenBetween`);
 * - the zone and the schedule are the user's settings ([UserSettingsStore]), read on every call;
 * - builds run one at a time per user (a mutex per user), and every user's in parallel.
 */
class EditionPublisher(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) {
    private val editionDao = database.editionDao()
    private val articleDao = database.editionArticleDao()
    private val engagementDao = database.engagementDao()
    private val userStateDao = database.userStateDao()
    private val affinity = Affinity(config)
    private val learner = AffinityLearner(database, config)
    private val withinFeed = WithinFeedLearner(database, config)

    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    /** The user's build lock: one build at a time per user. */
    fun mutexOf(userId: Long): Mutex = mutexes.computeIfAbsent(userId) { Mutex() }

    /**
     * The ID of the user's newest released edition after making sure the edition for the most
     * recent reset exists, building it first if it is missing (`cutoffAt = now`, `releaseAt` =
     * that reset), and the Evening edition when it is on and due. Null when there is none.
     */
    suspend fun ensureCurrentEdition(userId: Long): Long? = mutexOf(userId).withLock {
        val now = clock.instant()
        val settings = settings.get(userId)
        val zone = settings.zone
        val schedule = settings.schedule
        val reset = schedule.latestReset(now, zone)
        val date = EditionClock.editionDate(reset, zone)

        val released = editionDao.getLatestReleasedOf(userId, EditionKind.MORNING, now)
        // A newer morning edition than this reset's is already out (see "Time zones").
        if (released == null || released.date < date) {
            build(userId, EditionKind.MORNING, date, releaseAt = reset, now = now, length = settings.editionLength)
        }
        schedule.currentEveningRelease(now, zone)?.let { evening ->
            build(userId, EditionKind.EVENING, EditionClock.editionDate(evening, zone), releaseAt = evening, now = now, length = settings.editionLength)
        }
        editionDao.getLatestReleased(userId, now)?.id
    }

    /**
     * Builds the edition for the user's *next* release ahead of time and stores it unreleased.
     * Returns its ID, the existing one if it was already built, or null when there is nothing
     * to build from.
     */
    suspend fun buildNextEdition(userId: Long): Long? {
        val settings = settings.get(userId)
        return buildFor(userId, settings.schedule.nextRelease(clock.instant(), settings.zone))
    }

    /** Builds the edition for [release] (the scheduler's), with `cutoffAt = now`. */
    suspend fun buildFor(userId: Long, release: Release): Long? = mutexOf(userId).withLock {
        val now = clock.instant()
        val settings = settings.get(userId)
        build(userId, release.kind, EditionClock.editionDate(release.at, settings.zone), releaseAt = release.at, now = now, length = settings.editionLength)?.id
    }

    /** When the user's next edition will be released. */
    suspend fun nextRelease(userId: Long): Release {
        val settings = settings.get(userId)
        return settings.schedule.nextRelease(clock.instant(), settings.zone)
    }

    /**
     * Prints an Extra edition at once from the stored posts of [feedIds], or reprints today's
     * (see the app's `EditionPublisher.publishExtraEdition`). Null when those feeds have
     * nothing printable.
     */
    suspend fun publishExtraEdition(userId: Long, feedIds: Set<Long>): Long? = mutexOf(userId).withLock {
        if (feedIds.isEmpty()) return@withLock null
        val now = clock.instant()
        val settings = settings.get(userId)
        val date = now.atZone(settings.zone).toLocalDate()
        build(userId, EditionKind.EXTRA, date, releaseAt = now, now = now, length = settings.editionLength, extraFeedIds = feedIds)?.id
    }

    /**
     * Returns the user's [kind] edition for [date], building and storing it first if it is
     * missing; null when it is missing and there is nothing to build. Reading the pool, the
     * check for an existing edition and every write share one transaction. An Extra edition
     * asked for again on its date is reprinted in place (see the app's publisher).
     */
    private suspend fun build(
        userId: Long,
        kind: EditionKind,
        date: LocalDate,
        releaseAt: Instant,
        now: Instant,
        length: EditionLength,
        extraFeedIds: Set<Long> = emptySet(),
    ): EditionEntity? = database.withWriteTransaction {
        val existing = editionDao.getByDate(userId, date, kind)
        if (existing != null && kind != EditionKind.EXTRA) return@withWriteTransaction existing
        val reprintOf = existing
        val reprinted = reprintOf?.let { editionDao.getItems(userId, it.id) }.orEmpty()
        val extraFeeds = extraFeedIds + reprinted.map { it.row.article.feedId }

        val window = Duration.ofDays(config.firstEditionWindowDays)
        val firstWindow = now.minus(window)
        val after = when (kind) {
            EditionKind.MORNING -> editionDao.getLatestByCutoffOf(userId, listOf(EditionKind.MORNING))?.cutoffAt ?: firstWindow
            EditionKind.EVENING -> editionDao.getLatestByCutoffOf(userId, SCHEDULED)?.cutoffAt ?: return@withWriteTransaction null
            EditionKind.EXTRA -> reprintOf?.cutoffAt?.minus(window) ?: firstWindow
        }
        if (!now.isAfter(after)) return@withWriteTransaction null

        val printedSince = if (reprintOf == null) editionDao.printedArticleIdsSince(userId, after).toSet() else printedSinceExcept(userId, after, reprintOf.id)
        val seen = articleDao.firstSeenBetween(userId, after, now)
        val rows = seen.filter { it.article.id !in printedSince && (kind != EditionKind.EXTRA || it.article.feedId in extraFeeds) }
        if (rows.isEmpty()) return@withWriteTransaction null

        val pending = editionDao.getPending(userId)
        val supersedes = kind != EditionKind.EXTRA
        val evidence = learner.evidence(userId, now, if (supersedes) emptySet() else pending.mapTo(HashSet()) { it.id })
        val learned = learner.learn(userId, evidence, now)
        val subscribed = articleDao.subscribedFeeds(userId)
        val tagsByFeed = articleDao.tagLinks(userId).groupBy { it.feedId }
        val tagAffinity = HashMap<String, Double>()
        subscribed.forEach { feed -> tagsByFeed[feed.feedId].orEmpty().forEach { tagAffinity[it.name] = learned.tags[it.tagId] ?: it.affinity } }
        val feeds = subscribed.map { it.toProfile(tagsByFeed[it.feedId].orEmpty(), learned, tagAffinity) }
        val feedsById = feeds.associateBy { it.id }

        val newIds = rows.mapTo(HashSet()) { it.article.id }
        val fresh = withComebacks(userId, rows, after).let { if (kind == EditionKind.EXTRA) exemptNewest(it) else it }
        val alreadyRun = seen.filter { it.article.id in printedSince }
        val heldOver = if (kind == EditionKind.MORNING) heldOver(userId, feeds, alreadyRun, pending, after, releaseAt, newIds) else emptyList()
        val heldOverIds = heldOver.mapTo(HashSet()) { it.articleId }
        val away = if (kind == EditionKind.MORNING) awayPool(userId, now) else null
        val readingList = if (kind == EditionKind.MORNING) readingListPick(userId, releaseAt) else null
        val shadows = alreadyRun.filter { it.article.id !in heldOverIds && it.article.feedId in feedsById }.map { it.toCandidate() }

        val taste = withinFeed.learn(userId, evidence, learner.windowStart(now), at = releaseAt)
        val scored = fresh + heldOver + shadows + away?.candidates.orEmpty()
        val traits = withinFeed.traitsOf(scored.map { it.articleId }, taste)
        val builder = EditionBuilder(config, Scorer(config, withinFeedSource = taste.source(traits)))

        val draft = builder.build(
            candidates = fresh + heldOver,
            feeds = feeds,
            length = length,
            releaseAt = releaseAt,
            tagAffinity = tagAffinity,
            kind = kind,
            alreadyRun = shadows,
            away = away,
            readingList = readingList,
        )
        if (draft.printed.none { it.articleId in newIds && (reprintOf == null || it.feedId in extraFeedIds) }) return@withWriteTransaction null

        if (supersedes) {
            pending.forEach { engagementDao.countUnopenedRun(userId, it.id, config.pageSeenMinMillis) }
            learner.store(userId, learned)
        }

        // A new edition, or a reprint, is a change the manifest's bundle cache must see.
        val seq = userStateDao.nextChangeSeq(userId)
        val edition = reprintOf?.copy(
            cutoffAt = now, releaseAt = releaseAt, builtAt = now, length = length, finishedAt = null, pageViewsTracked = true, changeSeq = seq,
        ) ?: EditionEntity(
            userId = userId,
            date = date,
            kind = kind,
            cutoffAt = now,
            releaseAt = releaseAt,
            builtAt = now,
            length = length,
            number = editionDao.maxNumber(userId) + 1,
            pageViewsTracked = true,
            changeSeq = seq,
        )
        if (reprintOf != null) editionDao.delete(userId, reprintOf.id)
        val id = editionDao.insertEditionWithItems(edition, draft.toEntities())
        val ledBefore = reprinted.firstOrNull { it.item.kind == ItemKind.LEAD }?.item?.articleId
        draft.lead?.takeIf { it.articleId != ledBefore }?.let { engagementDao.incrementLeadCount(userId, it.articleId) }
        edition.copy(id = id)
    }

    private suspend fun printedSinceExcept(userId: Long, after: Instant, editionId: Long): Set<Long> {
        val others = EditionKind.entries.flatMap { editionDao.getBuiltAfter(userId, it, after) }.map { it.id }.filter { it != editionId }
        return if (others.isEmpty()) emptySet() else editionDao.printedArticleIds(userId, others).toSet()
    }

    /** Step 1's held-over posts: the untouched posts of loved slow feeds (see the app's publisher). */
    private suspend fun heldOver(
        userId: Long,
        feeds: List<FeedProfile>,
        alreadyRun: List<UserArticleRow>,
        pending: List<EditionEntity>,
        after: Instant,
        releaseAt: Instant,
        newIds: Set<Long>,
    ): List<Candidate> {
        val pendingRuns = HashMap<Long, Int>()
        if (pending.isNotEmpty()) {
            editionDao.seenRuns(userId, pending.map { it.id }, config.pageSeenMinMillis).forEach { pendingRuns.merge(it, 1, Int::plus) }
        }
        val recent = editionDao.getRecent(userId, config.archiveCooldownEditions).map { it.id }
        val ranRecently = if (recent.isEmpty()) emptySet() else editionDao.printedArticleIds(userId, recent).toSet()
        val untouchedRuns = alreadyRun
            .filter { it.openedAt == null && it.readAt == null && it.savedAt == null && it.likedAt == null }
            .groupBy { it.article.feedId }
        return feeds
            .filter { affinity.holdsPostsOver(it) && it.muteUntil?.isAfter(releaseAt) != true }
            .flatMap { feed ->
                (untouchedRuns[feed.id].orEmpty() + articleDao.heldOverForFeed(userId, feed.id, upTo = after, limit = config.heldOverPerFeed))
                    .sortedWith(compareByDescending<UserArticleRow> { it.article.publishedAt }.thenByDescending { it.article.id })
                    .take(config.heldOverPerFeed)
            }
            .filter { it.article.id !in newIds }
            .map { row ->
                val candidate = row.toCandidate()
                candidate.copy(
                    isHeldOver = true,
                    ranRecently = row.article.id in ranRecently,
                    editionsRunUnopened = candidate.editionsRunUnopened + (pendingRuns[row.article.id] ?: 0),
                )
            }
    }

    /** Welcome back: each feed's first new post, marked with when the feed last posted before it. */
    private suspend fun withComebacks(userId: Long, rows: List<UserArticleRow>, after: Instant): List<Candidate> {
        val comebacks = HashMap<Long, Instant>()
        for ((feedId, posts) in rows.groupBy { it.article.feedId }) {
            val previous = articleDao.lastKnownPostAt(userId, feedId, seenUpTo = after) ?: continue
            val first = posts.filter { it.article.publishedAt.isAfter(previous) }.minByOrNull { it.article.publishedAt } ?: continue
            comebacks[first.article.id] = previous
        }
        return rows.map { row -> row.toCandidate().copy(previousPostAt = comebacks[row.article.id]) }
    }

    /** An Extra edition shows what its feeds have, however old: each feed's newest posts are exempt from the age limits. */
    private fun exemptNewest(candidates: List<Candidate>): List<Candidate> {
        val exempt = candidates.groupBy { it.feedId }.values.flatMap { posts ->
            posts.sortedByDescending { it.publishedAt }.take(config.extraNewestPerFeed).map { it.articleId }
        }.toSet()
        return candidates.map { if (it.articleId in exempt) it.copy(exemptFromAgeLimits = true) else it }
    }

    /** While you were away (see the app's publisher). */
    private suspend fun awayPool(userId: Long, now: Instant): AwayPool? {
        val lastVisit = editionDao.lastOpenedAt(userId) ?: return null
        val window = now.minus(Duration.ofDays(config.awayWindowDays))
        val missed = editionDao.getUnopenedReleasedBetween(userId, since = maxOf(lastVisit, window), now = now)
        if (missed.isEmpty()) return null
        val stories = editionDao.untouchedPrintedIn(userId, missed.map { it.id })
        if (stories.isEmpty()) return null
        return AwayPool(
            candidates = stories.map { it.toCandidate() },
            lastVisitAt = lastVisit,
            engagedFeedIds = articleDao.feedsLikedOrSavedSince(userId, now.minus(Duration.ofDays(config.awayFirehoseEngagementDays))).toSet(),
        )
    }

    /** From your reading list (see the app's publisher). */
    private suspend fun readingListPick(userId: Long, releaseAt: Instant): ReadingListPick? {
        val last = editionDao.lastReadingListAt(userId, releaseAt)
        if (last != null && Duration.between(last, releaseAt) < Duration.ofDays(config.readingListIntervalDays)) return null
        val row = articleDao.oldestUnreadSavedFromUnsubscribed(userId) ?: return null
        val feed = articleDao.feed(userId, row.article.feedId) ?: return null
        val profile = FeedProfile(id = feed.feedId, title = feed.title, tier = feed.tier, medianIntervalHours = feed.medianIntervalHrs)
        return ReadingListPick(row.toCandidate(), profile)
    }

    private fun EditionDraft.toEntities(): List<EditionItemEntity> = items.map {
        EditionItemEntity(
            editionId = 0,
            articleId = it.articleId,
            page = it.page,
            section = it.section,
            slot = it.slot,
            kind = it.kind,
            score = it.score,
            reason = it.reason,
        )
    }

    private fun UserFeedRow.toProfile(tags: List<UserTagLinkRow>, learned: LearnedAffinities, tagAffinity: Map<String, Double>): FeedProfile {
        val blended = learned.feeds[feedId] ?: affinity
        val own = learned.own[feedId] ?: blended
        return FeedProfile(
            id = feedId,
            title = title,
            tier = tier,
            medianIntervalHours = medianIntervalHrs,
            affinity = blended,
            sliderStep = sliderStep,
            muteUntil = muteUntil,
            tags = tags.map { it.name },
            borrowedFromTag = tags.takeIf { own < config.lovedAffinity && blended >= config.lovedAffinity }
                ?.maxByOrNull { tagAffinity[it.name] ?: it.affinity }?.name,
        )
    }

    companion object {
        private val SCHEDULED = listOf(EditionKind.MORNING, EditionKind.EVENING)

        /** A stored post as the engine's candidate; its first-seen time is the user's. */
        fun UserArticleRow.toCandidate() = Candidate(
            articleId = article.id,
            feedId = article.feedId,
            publishedAt = article.publishedAt,
            firstSeenAt = seenAt,
            hasImage = article.imageUrl != null,
            editionsRunUnopened = editionsRunUnopened ?: 0,
            leadCount = leadCount ?: 0,
            isOpened = openedAt != null,
            isRead = readAt != null,
            title = article.title,
            url = article.url,
        )
    }
}

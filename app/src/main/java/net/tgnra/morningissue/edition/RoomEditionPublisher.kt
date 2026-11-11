package net.tgnra.morningissue.edition

import android.os.Build
import android.os.Trace
import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedWithDetails
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.data.repository.WithinFeedLearner
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.LearnedAffinities
import net.tgnra.morningissue.engine.AwayPool
import net.tgnra.morningissue.engine.Candidate
import net.tgnra.morningissue.engine.EditionBuilder
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.EditionDraft
import net.tgnra.morningissue.engine.FeedProfile
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.Scorer
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength

/** The trace section around the on-open fallback build; `:macrobenchmark` measures it by this name. */
const val TRACE_ON_OPEN_BUILD = "MorningIssue.onOpenEditionBuild"

/** Tells concurrent trace sections of the same name apart; shared by every publisher in the process. */
private val traceCookies = AtomicInteger()

/**
 * Builds editions from stored posts with the pure `EditionBuilder` and freezes them in Room. It
 * never touches the network.
 *
 * **Which posts a morning edition covers.** Posts first seen after the previous *morning*
 * edition's cutoff (released or not) and up to now, which becomes the new cutoff, minus the
 * stories an Evening or Extra edition printed in between. With no earlier edition, the first
 * one covers posts first seen in the last `firstEditionWindowDays` days: right after
 * onboarding's first sync that is everything.
 *
 * **Evening edition** (optional). Covers Firehose and Daily feeds' posts first seen since the
 * latest Morning or Evening cutoff. Weekly and Rare posts are not placed in it at all, and a
 * post it only listed in its index was not printed, so the next morning edition, whose window
 * runs from the previous morning cutoff, still offers both. What it printed is not new again.
 *
 * **Extra edition** (on request). Printed at once from the chosen feeds' posts first seen in
 * the last `firstEditionWindowDays` days and not printed before. It moves no cutoff and
 * supersedes nothing. What it printed is not new in the next scheduled edition (a loved slow
 * feed's untouched posts are held over as usual); what it only indexed still is.
 *
 * **Superseding.** A Morning or Evening build supersedes every pending edition (the newest
 * scheduled one and the Extras since): their untouched printed stories each count one more
 * unopened run, and count against their feeds in learning.
 *
 * **Held-over posts.** Untouched posts of loved Weekly and Rare feeds (`Affinity.holdsPostsOver`,
 * judged on the affinity learned for this build) from before the previous cutoff join the pool,
 * the `heldOverPerFeed` newest per feed. "Untouched" means never opened, read, saved or Liked:
 * a post leaves the pool at the first open, which is the one signal that also covers reading
 * in the browser, and is what the seen penalty P counts. Muted feeds are not asked; when the
 * mute runs out their posts are still held over.
 *
 * **Learning.** Feed and tag affinity are relearned first (see `AffinityLearner`), counting the
 * edition being superseded, and stored together with the new edition. Within-feed ranking
 * (`WithinFeedLearner`) is learned from the same evidence for every kind of build and stored
 * nowhere: it reaches the edition through each story's score and reason line.
 *
 * **Nothing to build.** No edition is stored unless at least one post first seen since the
 * previous cutoff is printed (there are none, or they are all muted, older than `maxAgeDays`
 * or already read): held-over posts alone do not make a paper. The cutoff then stays where it
 * was and the posts are offered to the next build.
 *
 * **Time zones.** [zone] is asked on every call, so a zone change applies at once. An edition
 * belongs to the local date of its reset, and there is one per date: travelling west can make
 * the "latest reset" fall on a date older than the newest released edition, in which case that
 * newest edition stays current; travelling east can put the local reset ahead of the release
 * time of an edition already built for that date, which then stays hidden until its own
 * release time while the previous edition remains current.
 *
 * @param zone the device's current time zone; not taken from [clock], whose zone is fixed when
 *   the process starts
 */
class RoomEditionPublisher(
    private val database: MorningIssueDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : EditionPublisher {
    private val editionDao = database.editionDao()
    private val articleDao = database.articleDao()
    private val feedDao = database.feedDao()
    private val engagementDao = database.engagementDao()
    private val affinity = Affinity(config)
    private val learner = AffinityLearner(database, config)
    private val withinFeed = WithinFeedLearner(database, config)

    /** One build at a time in this process; the transaction in [build] guards the rest. */
    private val mutex = Mutex()

    override suspend fun ensureCurrentEdition(): Long? = mutex.withLock {
        val now = clock.instant()
        val settings = settings.settings.first()
        val zone = zone()
        val reset = EditionClock.latestReset(now, settings.resetTime, zone)
        val date = EditionClock.editionDate(reset, zone)

        val released = editionDao.getLatestReleasedOf(EditionKind.MORNING, now)
        // A newer morning edition than this reset's is already out (see "Time zones").
        if (released == null || released.date < date) {
            traced(TRACE_ON_OPEN_BUILD) {
                build(EditionKind.MORNING, date, releaseAt = reset, now = now, length = settings.editionLength)
            }
        }
        if (settings.eveningEditionEnabled) {
            EditionClock.currentEveningRelease(now, settings.resetTime, settings.eveningEditionTime, zone)?.let { evening ->
                build(EditionKind.EVENING, EditionClock.editionDate(evening, zone), releaseAt = evening, now = now, length = settings.editionLength)
            }
        }
        // Whatever is newest on the stand, of any kind. An edition whose own release time is
        // still ahead (built early, or see "Time zones") is not shown yet.
        editionDao.getLatestReleased(now)?.id
    }

    override suspend fun buildNextEdition(): Long? = mutex.withLock {
        val now = clock.instant()
        val settings = settings.settings.first()
        val zone = zone()
        val next = EditionClock.nextRelease(now, settings.resetTime, settings.eveningTimeIfEnabled, zone)
        build(next.kind, EditionClock.editionDate(next.at, zone), releaseAt = next.at, now = now, length = settings.editionLength)?.id
    }

    override suspend fun nextReleaseAt(): Instant? {
        val settings = settings.settings.first()
        return EditionClock.nextRelease(clock.instant(), settings.resetTime, settings.eveningTimeIfEnabled, zone()).at
    }

    override suspend fun publishExtraEdition(feedIds: Set<Long>): Long? = mutex.withLock {
        if (feedIds.isEmpty()) return@withLock null
        val now = clock.instant()
        val settings = settings.settings.first()
        val date = now.atZone(zone()).toLocalDate()
        build(EditionKind.EXTRA, date, releaseAt = now, now = now, length = settings.editionLength, extraFeedIds = feedIds)?.id
    }

    private val AppSettings.eveningTimeIfEnabled get() = eveningEditionTime.takeIf { eveningEditionEnabled }

    /**
     * Returns the [kind] edition for [date], building and storing it first if it is missing;
     * null when it is missing and there is nothing to build. Reading the pool, the check for
     * an existing edition and every write share one transaction, so concurrent callers (even
     * through different publisher instances) end up with a single edition per date and kind.
     *
     * A second Extra edition on a date that already has one is refused (null): the schema
     * allows one edition per date and kind.
     */
    private suspend fun build(
        kind: EditionKind,
        date: LocalDate,
        releaseAt: Instant,
        now: Instant,
        length: EditionLength,
        extraFeedIds: Set<Long> = emptySet(),
    ): EditionEntity? = database.withWriteTransaction {
        editionDao.getByDate(date, kind)?.let { return@withWriteTransaction it.takeIf { kind != EditionKind.EXTRA } }

        // The window: posts first seen in (after, now].
        val firstWindow = now.minus(Duration.ofDays(config.firstEditionWindowDays))
        val after = when (kind) {
            EditionKind.MORNING -> editionDao.getLatestByCutoffOf(listOf(EditionKind.MORNING))?.cutoffAt ?: firstWindow
            // An evening paper follows a morning one; it is never the first edition.
            EditionKind.EVENING -> editionDao.getLatestByCutoffOf(SCHEDULED)?.cutoffAt ?: return@withWriteTransaction null
            EditionKind.EXTRA -> firstWindow
        }
        // The clock was set back past the last cutoff: nothing can be new yet.
        if (!now.isAfter(after)) return@withWriteTransaction null

        // Stories an edition since [after] already printed (an Evening or Extra edition, or for
        // an Extra any edition of the last few days) are not new again.
        val printedSince = editionDao.printedArticleIdsSince(after).toSet()
        val seen = articleDao.firstSeenBetween(after, now)
        val rows = seen.filter { it.article.id !in printedSince && (kind != EditionKind.EXTRA || it.article.feedId in extraFeedIds) }
        if (rows.isEmpty()) return@withWriteTransaction null

        // A scheduled build supersedes the pending editions, so their untouched stories now
        // count as ignored. An Extra edition supersedes nothing.
        val pending = editionDao.getPending()
        val supersedes = kind != EditionKind.EXTRA
        val evidence = learner.evidence(now, if (supersedes) emptySet() else pending.mapTo(HashSet()) { it.id })
        val learned = learner.learn(evidence, now)
        val details = feedDao.getAllWithDetails()
        val tagAffinity = HashMap<String, Double>()
        details.forEach { feed -> feed.tags.forEach { tagAffinity[it.name] = learned.tags[it.id] ?: it.affinity } }
        val feeds = details.map { it.toProfile(learned, tagAffinity) }
        val feedsById = feeds.associateBy { it.id }

        val newIds = rows.mapTo(HashSet()) { it.article.id }
        val fresh = withComebacks(rows, after).let { if (kind == EditionKind.EXTRA) exemptNewest(it) else it }
        val alreadyRun = seen.filter { it.article.id in printedSince }
        val heldOver = if (kind == EditionKind.MORNING) heldOver(feeds, alreadyRun, pending, after, releaseAt, newIds) else emptyList()
        val heldOverIds = heldOver.mapTo(HashSet()) { it.articleId }
        val away = if (kind == EditionKind.MORNING) awayPool(now) else null
        val shadows = alreadyRun.filter { it.article.id !in heldOverIds && it.article.feedId in feedsById }.map { it.toCandidate() }

        // Within-feed ranking: learned from the same evidence as affinity, as of the release
        // time, then given the traits of every post this build scores.
        val taste = withinFeed.learn(evidence, learner.windowStart(now), at = releaseAt)
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
        )
        if (draft.printed.none { it.articleId in newIds }) return@withWriteTransaction null

        if (supersedes) {
            // Each untouched story of a superseded edition counts one more run.
            pending.forEach { engagementDao.countUnopenedRun(it.id) }
            learner.store(learned)
        }

        val edition = EditionEntity(
            date = date,
            kind = kind,
            cutoffAt = now,
            releaseAt = releaseAt,
            builtAt = now,
            length = length,
            number = editionDao.maxNumber() + 1,
        )
        val id = editionDao.insertEditionWithItems(edition, draft.toEntities())
        draft.lead?.let { engagementDao.incrementLeadCount(it.articleId) }
        edition.copy(id = id)
    }

    /**
     * Step 1's held-over posts: the untouched posts of loved slow feeds from before [after],
     * and those among [alreadyRun] (printed since, in an Extra edition), which are no longer
     * new but stay eligible like any other untouched post of such a feed. Their stored
     * unopened count does not include the [pending] editions yet (it is only written once
     * this build is known to succeed), so it is added here.
     */
    private suspend fun heldOver(
        feeds: List<FeedProfile>,
        alreadyRun: List<ArticleRow>,
        pending: List<EditionEntity>,
        after: Instant,
        releaseAt: Instant,
        newIds: Set<Long>,
    ): List<Candidate> {
        val pendingRuns = HashMap<Long, Int>()
        if (pending.isNotEmpty()) editionDao.printedRuns(pending.map { it.id }).forEach { pendingRuns.merge(it, 1, Int::plus) }
        val recent = editionDao.getRecent(config.archiveCooldownEditions).map { it.id }
        val ranRecently = if (recent.isEmpty()) emptySet() else editionDao.printedArticleIds(recent).toSet()
        val untouchedRuns = alreadyRun
            .filter { it.openedAt == null && it.readAt == null && it.savedAt == null && it.likedAt == null }
            .groupBy { it.article.feedId }
        return feeds
            .filter { affinity.holdsPostsOver(it) && it.muteUntil?.isAfter(releaseAt) != true }
            .flatMap { feed ->
                (untouchedRuns[feed.id].orEmpty() + articleDao.heldOverForFeed(feed.id, upTo = after, limit = config.heldOverPerFeed))
                    .sortedWith(compareByDescending<ArticleRow> { it.article.publishedAt }.thenByDescending { it.article.id })
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

    /**
     * Welcome back: marks each feed's first new post with when the feed last posted before
     * it, as far as the app knew when this edition's window opened. The scorer decides
     * whether the silence was long enough to count. One indexed lookup per feed with new posts.
     */
    private suspend fun withComebacks(rows: List<ArticleRow>, after: Instant): List<Candidate> {
        val comebacks = HashMap<Long, Instant>()
        for ((feedId, posts) in rows.groupBy { it.article.feedId }) {
            val previous = articleDao.lastKnownPostAt(feedId, seenUpTo = after) ?: continue
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

    /**
     * While you were away. An edition counts as missed when it has been released, the reader
     * never opened it, and it came out after their last visit (the last time they opened any
     * edition) and within `awayWindowDays`. Null when nothing was missed, and for a reader who
     * has never opened an edition: there is no visit to have been away since.
     */
    private suspend fun awayPool(now: Instant): AwayPool? {
        val lastVisit = editionDao.lastOpenedAt() ?: return null
        val window = now.minus(Duration.ofDays(config.awayWindowDays))
        val missed = editionDao.getUnopenedReleasedBetween(since = maxOf(lastVisit, window), now = now)
        if (missed.isEmpty()) return null
        val stories = editionDao.untouchedPrintedIn(missed.map { it.id })
        if (stories.isEmpty()) return null
        return AwayPool(
            candidates = stories.map { it.toCandidate() },
            lastVisitAt = lastVisit,
            engagedFeedIds = articleDao.feedsLikedOrSavedSince(now.minus(Duration.ofDays(config.awayFirehoseEngagementDays))).toSet(),
        )
    }

    /**
     * Runs [block] inside a trace section, for the macrobenchmark of the on-open build's 300 ms
     * budget. An async section, because a suspending block may finish on another thread; those
     * need Android 10, and on older versions nothing is traced.
     */
    private suspend fun <T> traced(name: String, block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
        val cookie = traceCookies.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        try {
            return block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
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

    private fun ArticleRow.toCandidate() = Candidate(
        articleId = article.id,
        feedId = article.feedId,
        publishedAt = article.publishedAt,
        firstSeenAt = article.firstSeenAt,
        hasImage = article.imageUrl != null,
        editionsRunUnopened = editionsRunUnopened ?: 0,
        leadCount = leadCount ?: 0,
        isOpened = openedAt != null,
        isRead = readAt != null,
        title = article.title,
        url = article.url,
    )

    private companion object {
        val SCHEDULED = listOf(EditionKind.MORNING, EditionKind.EVENING)
    }

    private fun FeedWithDetails.toProfile(learned: LearnedAffinities, tagAffinity: Map<String, Double>): FeedProfile {
        val blended = learned.feeds[feed.id] ?: feed.affinity
        val own = learned.own[feed.id] ?: blended
        return FeedProfile(
            id = feed.id,
            title = feed.title,
            tier = feed.tier,
            medianIntervalHours = feed.medianIntervalHrs,
            affinity = blended,
            sliderStep = feed.sliderStep,
            muteUntil = feed.muteUntil,
            tags = tags.map { it.name },
            // The feed's own record would not make it loved; its tags lifted it there.
            borrowedFromTag = tags.takeIf { own < config.lovedAffinity && blended >= config.lovedAffinity }
                ?.maxByOrNull { tagAffinity[it.name] ?: it.affinity }?.name,
        )
    }
}

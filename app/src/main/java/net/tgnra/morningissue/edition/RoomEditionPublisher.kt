package net.tgnra.morningissue.edition

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedWithDetails
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.LearnedAffinities
import net.tgnra.morningissue.engine.Candidate
import net.tgnra.morningissue.engine.EditionBuilder
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.EditionDraft
import net.tgnra.morningissue.engine.FeedProfile
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength

/**
 * Builds morning editions from stored posts with the pure `EditionBuilder` and freezes them in
 * Room. It never touches the network.
 *
 * **Which posts an edition covers.** Posts first seen after the previous edition's cutoff (the
 * edition with the latest cutoff, released or not) and up to now, which becomes the new
 * cutoff. With no earlier edition, the first one covers posts first seen in the last
 * `firstEditionWindowDays` days: right after onboarding's first sync that is everything.
 *
 * **Held-over posts.** Untouched posts of loved Weekly and Rare feeds (`Affinity.holdsPostsOver`,
 * judged on the affinity learned for this build) from before the previous cutoff join the pool,
 * the `heldOverPerFeed` newest per feed. "Untouched" means never opened, read, saved or Liked:
 * a post leaves the pool at the first open, which is the one signal that also covers reading
 * in the browser, and is what the seen penalty P counts. Muted feeds are not asked; when the
 * mute runs out their posts are still held over.
 *
 * **Learning.** Feed and tag affinity are relearned first (see `AffinityLearner`), counting the
 * edition being superseded, and stored together with the new edition.
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
    private val builder = EditionBuilder(config)
    private val affinity = Affinity(config)
    private val learner = AffinityLearner(database, config)

    /** One build at a time in this process; the transaction in [build] guards the rest. */
    private val mutex = Mutex()

    override suspend fun ensureCurrentEdition(): Long? = mutex.withLock {
        val now = clock.instant()
        val settings = settings.settings.first()
        val zone = zone()
        val reset = EditionClock.latestReset(now, settings.resetTime, zone)
        val date = EditionClock.editionDate(reset, zone)

        val released = editionDao.getLatestReleased(now)
        if (released != null && released.date >= date) return@withLock released.id
        build(date, releaseAt = reset, now = now, length = settings.editionLength)
            // Built just now, or found stored for this date. A stored one whose own release
            // time is still ahead (see "Time zones") is not shown yet.
            ?.takeIf { !it.releaseAt.isAfter(now) }?.id
            ?: released?.id
    }

    override suspend fun buildNextEdition(): Long? = mutex.withLock {
        val now = clock.instant()
        val settings = settings.settings.first()
        val zone = zone()
        val reset = EditionClock.nextReset(now, settings.resetTime, zone)
        build(EditionClock.editionDate(reset, zone), releaseAt = reset, now = now, length = settings.editionLength)?.id
    }

    override suspend fun nextReleaseAt(): Instant? =
        EditionClock.nextReset(clock.instant(), settings.settings.first().resetTime, zone())

    /**
     * Returns the morning edition for [date], building and storing it first if it is missing;
     * null when it is missing and there is nothing to build. Reading the pool, the check for
     * an existing edition and every write share one transaction, so concurrent callers (even
     * through different publisher instances) end up with a single edition per date.
     */
    private suspend fun build(date: LocalDate, releaseAt: Instant, now: Instant, length: EditionLength): EditionEntity? =
        database.withWriteTransaction {
            editionDao.getByDate(date, EditionKind.MORNING)?.let { return@withWriteTransaction it }

            val previous = editionDao.getLatestByCutoff()
            val after = previous?.cutoffAt ?: now.minus(Duration.ofDays(config.firstEditionWindowDays))
            // The clock was set back past the last cutoff: nothing can be new yet.
            if (!now.isAfter(after)) return@withWriteTransaction null

            val rows = articleDao.firstSeenBetween(after, now)
            if (rows.isEmpty()) return@withWriteTransaction null

            // Building supersedes [previous], so its untouched stories now count as ignored.
            val learned = learner.learn(now, pendingEditionId = null)
            val details = feedDao.getAllWithDetails()
            val tagAffinity = HashMap<String, Double>()
            details.forEach { feed -> feed.tags.forEach { tagAffinity[it.name] = learned.tags[it.id] ?: it.affinity } }
            val feeds = details.map { it.toProfile(learned, tagAffinity) }

            // Step 1's held-over posts. Their stored unopened count does not include [previous]
            // yet (it is only written once this build is known to succeed), so add it here.
            val inPrevious = previous?.let { editionDao.printedArticleIds(listOf(it.id)).toSet() }.orEmpty()
            val recent = editionDao.getRecent(config.archiveCooldownEditions).map { it.id }
            val ranRecently = if (recent.isEmpty()) emptySet() else editionDao.printedArticleIds(recent).toSet()
            val newIds = rows.mapTo(HashSet()) { it.article.id }
            val heldOver = feeds
                .filter { affinity.holdsPostsOver(it) && it.muteUntil?.isAfter(releaseAt) != true }
                .flatMap { articleDao.heldOverForFeed(it.id, upTo = after, limit = config.heldOverPerFeed) }
                .filter { it.article.id !in newIds }
                .map { row ->
                    val candidate = row.toCandidate()
                    candidate.copy(
                        isHeldOver = true,
                        ranRecently = row.article.id in ranRecently,
                        editionsRunUnopened = candidate.editionsRunUnopened + if (row.article.id in inPrevious) 1 else 0,
                    )
                }

            val draft = builder.build(rows.map { it.toCandidate() } + heldOver, feeds, length, releaseAt, tagAffinity)
            if (draft.printed.none { it.articleId in newIds }) return@withWriteTransaction null

            // The previous edition is superseded: its untouched stories each count one more run.
            if (previous != null) engagementDao.countUnopenedRun(previous.id)
            learner.store(learned)

            val edition = EditionEntity(
                date = date,
                kind = EditionKind.MORNING,
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
    )

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

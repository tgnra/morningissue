package net.tgnra.morningissue.server.editions

import androidx.room3.withReadTransaction
import java.time.Clock
import java.time.Instant
import net.tgnra.morningissue.api.DueDto
import net.tgnra.morningissue.api.DueState
import net.tgnra.morningissue.api.ExtraOfferDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.ReleaseDto
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.edition.DueEditions
import net.tgnra.morningissue.edition.DueStatus
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.editions.EditionServiceImpl.Companion.toSummary
import net.tgnra.morningissue.server.service.StandService

/**
 * `GET /stand` (4.3): what the newsstand shows, in the user's zone. The due edition's state is
 * `DueEditions.status`; the Extra offer is the app's `ExtraOffer` rule: subscribed feeds added
 * since the newest released edition closed for copy and since the user last turned the offer
 * down, none before the first edition; a reprint of today's Extra when there is one.
 */
class StandServiceImpl(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val clock: Clock,
    /** The user's running print, if any; `Jobs.runningJobId`. */
    private val runningPrint: (userId: Long) -> String? = { null },
) : StandService {
    private val editionDao = database.editionDao()
    private val articleDao = database.editionArticleDao()

    override suspend fun stand(userId: Long): StandDto = database.withReadTransaction {
        val now = clock.instant()
        val stored = settings.get(userId)
        val zone = stored.zone
        val schedule = stored.schedule
        val released = editionDao.getReleased(userId, now)
        val summaries = released.map { it.toSummary() }
        val hasSources = articleDao.countSubscribed(userId) > 0
        val due = if (!hasSources && released.isEmpty()) {
            null
        } else {
            when (val status = DueEditions.status(now, schedule, zone, summaries, stored.nothingNew, hasSources)) {
                DueStatus.Printed -> DueEditions.due(now, schedule, zone).first().let { DueDto(it.kind, it.at, DueState.PRINTED) }
                is DueStatus.NothingNew -> DueDto(status.release.kind, status.release.at, DueState.NOTHING_NEW)
                is DueStatus.NotPrinted -> DueDto(status.release.kind, status.release.at, DueState.NOT_PRINTED)
            }
        }
        val next = schedule.nextRelease(now, zone)
        StandDto(
            latestEditionId = released.firstOrNull()?.id,
            due = due,
            nextRelease = ReleaseDto(next.kind, next.at),
            hasSources = hasSources,
            extraOffer = extraOffer(userId, released.maxOfOrNull { it.cutoffAt }, stored, now),
            jobRunning = runningPrint(userId),
        )
    }

    private suspend fun extraOffer(userId: Long, newestCutoff: Instant?, stored: StoredSettings, now: Instant): ExtraOfferDto? {
        newestCutoff ?: return null
        val dismissed = stored.extraOfferDismissedAt?.let(Instant::ofEpochMilli)
        val since = if (dismissed != null && dismissed > newestCutoff) dismissed else newestCutoff
        val feeds = articleDao.subscribedAddedAfter(userId, since)
        if (feeds.isEmpty()) return null
        val today = now.atZone(stored.zone).toLocalDate()
        val reprintOf = editionDao.getByDate(userId, today, EditionKind.EXTRA)?.takeIf { !it.releaseAt.isAfter(now) }?.id
        return ExtraOfferDto(feeds, reprintOf)
    }

    companion object {
        /** The stand's `jobRunning` from [jobs]. */
        fun runningPrintOf(jobs: Jobs): (Long) -> String? = { jobs.runningJobId(it, JobKind.PRINT) }
    }
}

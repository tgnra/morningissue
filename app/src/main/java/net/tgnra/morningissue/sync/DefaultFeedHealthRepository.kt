package net.tgnra.morningissue.sync

import android.content.Context
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.FeedHealthRepository
import net.tgnra.morningissue.data.repository.toModel
import net.tgnra.morningissue.engine.DeskNoticeRule
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep

/** Remembers which edition carries the month's Subscriptions desk notice. */
interface DeskNoticeStore {
    fun get(): DeskNoticeRule.Claim?
    fun set(claim: DeskNoticeRule.Claim)
}

class PrefsDeskNoticeStore(context: Context) : DeskNoticeStore {
    private val prefs = context.applicationContext.getSharedPreferences("desk_notice", Context.MODE_PRIVATE)

    override fun get(): DeskNoticeRule.Claim? {
        val month = prefs.getString(KEY_MONTH, null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return null
        return DeskNoticeRule.Claim(month, prefs.getLong(KEY_EDITION, -1))
    }

    override fun set(claim: DeskNoticeRule.Claim) {
        prefs.edit().putString(KEY_MONTH, claim.month.toString()).putLong(KEY_EDITION, claim.editionId).apply()
    }

    private companion object {
        const val KEY_MONTH = "month"
        const val KEY_EDITION = "edition"
    }
}

class DefaultFeedHealthRepository(
    private val database: MorningIssueDatabase,
    private val checker: FeedHealthChecker,
    private val syncer: FeedSyncer,
    private val noticeStore: DeskNoticeStore,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) : FeedHealthRepository {
    private val feedDao = database.feedDao()
    private val healthDao = database.feedHealthDao()

    override fun observeFlagged(): Flow<List<FlaggedFeed>> = feedDao.observeSubscribedWithDetails().map { feeds ->
        val now = clock.instant()
        feeds.mapNotNull { details ->
            val flag = FeedHealthRules.visibleFlag(details.health?.lastFlag, details.health?.flagDismissedUntil, now)
                ?: return@mapNotNull null
            FlaggedFeed(details.toModel(), flag, details.health?.errorSince, details.health?.lastHttpStatus)
        }.sortedWith(compareBy({ FeedHealthRules.PRIORITY.indexOf(it.flag) }, { it.feed.title.lowercase() }))
    }

    override suspend fun refresh() {
        checker.run(repair = false)
    }

    override suspend fun dismiss(feedId: Long) =
        healthDao.dismissFlag(feedId, FeedHealthRules.dismissUntil(clock.instant(), config))

    override suspend fun acknowledge(feedId: Long) = healthDao.setVerdict(feedId, null, null)

    override suspend fun applySlider(feedId: Long, step: SliderStep) {
        feedDao.updateSliderStep(feedId, step)
        healthDao.setVerdict(feedId, null, null)
    }

    override suspend fun retry(feedId: Long): RetryOutcome = try {
        when (checker.repairNow(feedId)) {
            RepairResult.Failed -> RetryOutcome.STILL_BROKEN
            RepairResult.Recovered -> fetched(feedId, RetryOutcome.WORKING)
            is RepairResult.Relocated -> fetched(feedId, RetryOutcome.MOVED)
        }.also { checker.run(repair = false) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        RetryOutcome.STILL_BROKEN
    }

    private suspend fun fetched(feedId: Long, outcome: RetryOutcome): RetryOutcome =
        if (syncer.syncFeed(feedId).feedsFailed == 0) outcome else RetryOutcome.STILL_BROKEN

    override suspend fun deskNotice(editionId: Long, editionDate: LocalDate): Int? {
        val now = clock.instant()
        val needing = healthDao.getAll().count { health ->
            FeedHealthRules.visibleFlag(health.lastFlag, health.flagDismissedUntil, now)?.let(FeedHealthRules::needsAttention) == true
        }
        val latest = database.editionDao().getLatestReleased(now)?.id == editionId
        val today = now.atZone(clock.zone).toLocalDate()
        val decision = withContext(Dispatchers.IO) {
            DeskNoticeRule.decide(editionId, editionDate, latest, today, needing, noticeStore.get()).also {
                if (it == DeskNoticeRule.Decision.CLAIM) noticeStore.set(DeskNoticeRule.Claim(YearMonth.from(today), editionId))
            }
        }
        return needing.takeIf { decision != DeskNoticeRule.Decision.HIDE }
    }
}

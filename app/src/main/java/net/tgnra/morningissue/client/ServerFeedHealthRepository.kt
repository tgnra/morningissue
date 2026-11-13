package net.tgnra.morningissue.client

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.data.db.FeedHealthEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.FeedHealthRepository
import net.tgnra.morningissue.data.repository.toModel
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep

/**
 * The Subscriptions desk. The server keeps the health check; its flags are kept in the cache's
 * `feed_health` (from `GET /feeds` and `GET /feeds/health`), so the desk shows offline too.
 */
class ServerFeedHealthRepository(
    private val database: MorningIssueDatabase,
    private val connection: ServerConnection,
    private val feeds: ServerFeedRepository,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) : FeedHealthRepository {
    private val feedDao = database.feedDao()
    private val healthDao = database.feedHealthDao()

    override fun observeFlagged(): Flow<List<FlaggedFeed>> = feedDao.observeSubscribedWithDetails().map { rows ->
        val now = clock.instant()
        rows.mapNotNull { details ->
            val flag = FeedHealthRules.visibleFlag(details.health?.lastFlag, details.health?.flagDismissedUntil, now)
                ?: return@mapNotNull null
            FlaggedFeed(details.toModel(), flag, details.health?.errorSince, details.health?.lastHttpStatus)
        }.sortedWith(compareBy({ FeedHealthRules.PRIORITY.indexOf(it.flag) }, { it.feed.title.lowercase() }))
    }

    /** `GET /feeds/health`: the server recomputes this account's flags from what it holds. */
    override suspend fun refresh() {
        val flagged = online { connection.call { it.feedHealth() }.flagged } ?: return
        database.withWriteTransaction {
            val byFeed = flagged.associateBy { it.feed.id }
            for (feedId in database.cacheDao().feedIds()) {
                val dto = byFeed[feedId]
                val existing = healthDao.get(feedId)
                if (dto == null && existing?.lastFlag == null) continue
                healthDao.upsert(
                    (existing ?: FeedHealthEntity(feedId)).copy(
                        lastFlag = dto?.flag,
                        flagDismissedUntil = null,
                        errorSince = dto?.errorSince,
                        lastHttpStatus = dto?.lastHttpStatus ?: existing?.lastHttpStatus,
                    ),
                )
            }
        }
    }

    override suspend fun dismiss(feedId: Long) {
        healthDao.dismissFlag(feedId, FeedHealthRules.dismissUntil(clock.instant(), config))
        online { connection.call { it.dismissHealthFlag(feedId) } }
    }

    override suspend fun acknowledge(feedId: Long) {
        healthDao.setVerdict(feedId, null, null)
        online { connection.call { it.acknowledgeMove(feedId) } }
    }

    override suspend fun applySlider(feedId: Long, step: SliderStep) {
        healthDao.setVerdict(feedId, null, null)
        feeds.setSliderStep(feedId, step)
    }

    override suspend fun retry(feedId: Long): RetryOutcome {
        val outcome = online { connection.call { it.retryFeed(feedId) }.outcome } ?: return RetryOutcome.STILL_BROKEN
        // The feed's address may have changed, and its flag gone.
        online { connection.call { api -> api.feeds() }.feeds.firstOrNull { it.id == feedId }?.let { feeds.store(it) } }
        return outcome
    }

    override suspend fun deskNotice(editionId: Long, editionDate: LocalDate): Int? =
        online { connection.call { it.deskNotice(editionId) }.count }

    private suspend fun <T> online(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        null
    }
}

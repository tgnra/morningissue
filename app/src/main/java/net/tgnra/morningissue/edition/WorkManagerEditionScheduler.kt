package net.tgnra.morningissue.edition

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.data.db.EditionDao
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.engine.RankingConfig

/**
 * Keeps one [EarlyBuildWorker] scheduled as unique one-off work, aimed at
 * `reset − earlyBuildLead` (`RankingConfig.earlyBuildLeadMinutes`, 90 minutes) and requiring a
 * network connection.
 *
 * A release is the morning reset or, when the Evening edition is switched on, its release
 * time; "reset" below means either. Which one it aims for:
 * - normally the coming one. If its build moment has already passed, the work runs as soon as
 *   it can rather than skipping the day;
 * - the one after, once the coming reset has been dealt with: its edition is stored, or a run
 *   aimed at it has finished (a run that found nothing to build is not repeated in a loop).
 *
 * Every call replaces the earlier schedule unless it already aims at the same reset, and
 * except while a run is in flight: replacing would cancel that run, so the next one is chained
 * behind it instead (once).
 *
 * @param workManager resolved on first use so creating the container does not start WorkManager
 * @param zone the device's current time zone; not taken from [clock], whose zone is fixed when
 *   the process starts
 */
class WorkManagerEditionScheduler(
    private val workManager: () -> WorkManager,
    private val settings: SettingsRepository,
    private val editionDao: EditionDao,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: RankingConfig = RankingConfig.Default,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : EditionScheduler {
    private val mutex = Mutex()

    override fun scheduleEarlyBuild() {
        scope.launch {
            try {
                reschedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule the early build", e)
            }
        }
    }

    /** Does the work of [scheduleEarlyBuild] and returns once the schedule is stored. */
    suspend fun reschedule(): Unit = mutex.withLock {
        val workManager = workManager()
        val now = clock.instant()
        val settings = settings.settings.first()
        val resetTime = settings.resetTime
        val eveningTime = settings.eveningEditionTime.takeIf { settings.eveningEditionEnabled }
        val zone = zone()
        val lead = Duration.ofMinutes(config.earlyBuildLeadMinutes)
        val comingRelease = EditionClock.nextRelease(now, resetTime, eveningTime, zone)
        val coming = comingRelease.at
        val following = EditionClock.nextRelease(coming, resetTime, eveningTime, zone).at

        val existing = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
        val running = existing.any { it.state == WorkInfo.State.RUNNING }
        val target: Instant
        val policy: ExistingWorkPolicy
        if (running) {
            // The run in flight covers the coming reset if its build moment has arrived.
            if (existing.any { it.isWaiting }) return@withLock
            target = if (now.isBefore(coming.minus(lead))) coming else following
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE
        } else {
            val dealtWith = editionDao.getByDate(EditionClock.editionDate(coming, zone), comingRelease.kind) != null ||
                existing.any { it.state == WorkInfo.State.SUCCEEDED && resetTag(coming) in it.tags }
            target = if (dealtWith) following else coming
            // Already waiting for exactly that reset: leave it (and the record of the finished
            // run it may be chained behind) alone.
            if (existing.any { it.isWaiting && resetTag(target) in it.tags }) return@withLock
            policy = ExistingWorkPolicy.REPLACE
        }

        val delay = Duration.between(now, target.minus(lead)).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<EarlyBuildWorker>()
            .setInitialDelay(delay)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(resetTag(target))
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request).await()
    }

    private val WorkInfo.isWaiting: Boolean
        get() = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED

    companion object {
        const val UNIQUE_WORK_NAME = "early-edition-build"
        private const val TAG = "EditionScheduler"

        /** Marks a work request with the reset it is aimed at. */
        fun resetTag(reset: Instant): String = "reset:${reset.epochSecond}"
    }
}

package net.tgnra.morningissue.server.editions

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.edition.DueEditions
import net.tgnra.morningissue.edition.DueStatus
import net.tgnra.morningissue.engine.Release
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.UserStateEntity
import net.tgnra.morningissue.server.editions.EditionServiceImpl.Companion.toSummary
import net.tgnra.morningissue.server.service.FeedRefresher
import org.slf4j.LoggerFactory

/**
 * Builds every user's editions on time (section 3). One coroutine ticks every
 * [EditionTimings.tick]; a tick is robust to settings and time-zone changes, DST and restarts,
 * with no re-arming.
 *
 * On each tick, for every enabled user, in the user's own zone and schedule:
 *
 * - **Due builds.** When the next release (morning or evening) is within
 *   [EditionTimings.buildLead] and has not been handled, it is built ahead of time.
 * - **Catch-up, once.** When the latest release has passed and its edition is missing with no
 *   "nothing new" record (`DueEditions.status` is `NotPrinted`), and it has not been tried
 *   since the server started, the current edition is built late once. A later failure is left
 *   to Print now.
 *
 * Builds due together refresh their feeds first as **one union**, so a feed shared by several
 * users is fetched once, with the 2-hour leeway (`prePrintMaxAge`): a feed tried that recently
 * is not fetched again. Then each user's build runs under the publisher's per-user lock, two
 * users at a time.
 *
 * Separately, every [EditionTimings.refreshInterval] the subscribed feeds of every enabled user
 * are refreshed (skipping those tried within the interval less five minutes), followed by
 * [maintenance] (health, signals, clean-up), which integration wires to W-A's upkeep.
 *
 * Handled releases are persisted in `user_state["lastBuild"]`, so a restart does not build
 * them again.
 */
class Scheduler(
    private val database: ServerDatabase,
    private val settings: UserSettingsStore,
    private val runner: BuildRunner,
    private val refresher: FeedRefresher,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val timings: EditionTimings = EditionTimings(),
    private val maintenance: suspend (refreshedFeeds: Collection<Long>) -> Unit = {},
) {
    private val userStateDao = database.userStateDao()
    private val articleDao = database.editionArticleDao()
    private val editionDao = database.editionDao()

    /** A release whose build is due for a user; [late] builds the current edition instead. */
    data class DueBuild(val userId: Long, val release: Release, val late: Boolean)

    private val handled = ConcurrentHashMap<Long, MutableSet<Instant>>()
    private val triedLate = ConcurrentHashMap<Long, MutableSet<Instant>>()
    private val busy: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val tickLock = Mutex()
    private val users = Semaphore(timings.userParallelism.coerceAtLeast(1))

    @Volatile
    private var lastPeriodicAt: Instant? = null

    @Volatile
    private var periodicLoaded = false

    /** Starts ticking in [scope]; cancel the job (or the scope) to stop. */
    fun start(): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Scheduler tick failed", e)
            }
            delay(timings.tick.toMillis())
        }
    }

    /**
     * One tick: works out what is due and launches it in [scope]. Returns the launched jobs
     * (builds, then the periodic refresh), for tests to wait on.
     */
    suspend fun tick(): List<Job> = tickLock.withLock {
        val now = clock.instant()
        val due = ArrayList<DueBuild>()
        for (userId in userStateDao.enabledUserIds()) {
            if (userId in busy) continue
            val build = dueFor(userId, now) ?: continue
            due += build
        }
        val jobs = ArrayList<Job>()
        if (due.isNotEmpty()) {
            due.forEach { busy += it.userId }
            jobs += scope.launch {
                try {
                    runBuilds(due, now)
                } finally {
                    due.forEach { busy -= it.userId }
                }
            }
        }
        periodic(now)?.let(jobs::add)
        jobs
    }

    /** What is due for [userId] at [now], if anything; marks a late build as tried. */
    private suspend fun dueFor(userId: Long, now: Instant): DueBuild? {
        val stored = settings.get(userId)
        val zone = stored.zone
        val schedule = stored.schedule
        val next = schedule.nextRelease(now, zone)
        if (!now.isBefore(next.at.minus(timings.buildLead)) && next.at !in handledOf(userId)) {
            return DueBuild(userId, next, late = false)
        }
        val released = editionDao.getReleased(userId, now).map { it.toSummary() }
        val hasSources = articleDao.countSubscribed(userId) > 0
        val status = DueEditions.status(now, schedule, zone, released, stored.nothingNew, hasSources)
        if (status is DueStatus.NotPrinted) {
            val tried = triedLate.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
            if (tried.add(status.release.at)) return DueBuild(userId, Release(status.release.at, status.release.kind), late = true)
        }
        return null
    }

    private suspend fun runBuilds(due: List<DueBuild>, now: Instant) {
        val feeds = due.map { it.userId }.distinct().flatMapTo(LinkedHashSet()) { articleDao.subscribedFeedIds(it) }
        try {
            withTimeoutOrNull(timings.refreshTimeout.toMillis()) {
                refresher.refresh(feeds, timings.prePrintMaxAge, now.plus(timings.refreshTimeout))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Pre-print refresh failed", e)
        }
        coroutineScope {
            due.map { build ->
                async {
                    users.withPermit {
                        val target = if (build.late) BuildTarget.Current else BuildTarget.Scheduled(build.release)
                        val outcome = runner.run(build.userId, target, refreshMaxAge = null)
                        outcome.error?.let { log.warn("Build for user {} failed", build.userId, it) }
                        if (!build.late) markHandled(build.userId, build.release.at)
                    }
                }
            }.awaitAll()
        }
    }

    private fun periodic(now: Instant): Job? {
        val last = lastPeriodicAt
        if (last != null && Duration.between(last, now) < timings.refreshInterval) return null
        lastPeriodicAt = now
        return scope.launch {
            try {
                val feeds = userStateDao.enabledUserIds().flatMapTo(LinkedHashSet()) { articleDao.subscribedFeedIds(it) }
                refresher.refresh(feeds, timings.refreshInterval.minus(Duration.ofMinutes(5)))
                maintenance(feeds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Periodic refresh failed", e)
            }
        }
    }

    /**
     * Picks up where the server left off: the periodic refresh counts from the newest fetch
     * attempt in the database. Call once before [start]; without it the first tick refreshes.
     */
    suspend fun restore() {
        if (periodicLoaded) return
        periodicLoaded = true
        lastPeriodicAt = articleDao.lastAttemptAt()
    }

    private suspend fun handledOf(userId: Long): MutableSet<Instant> = handled[userId] ?: run {
        val stored = userStateDao.get(userId, LAST_BUILD_KEY)
            ?.let { runCatching { json.decodeFromString(LastBuild.serializer(), it) }.getOrNull() }
        val set: MutableSet<Instant> = ConcurrentHashMap.newKeySet()
        stored?.handled?.mapTo(set, Instant::ofEpochMilli)
        handled.putIfAbsent(userId, set) ?: set
    }

    private suspend fun markHandled(userId: Long, releaseAt: Instant) {
        val set = handledOf(userId)
        set += releaseAt
        val kept = set.sortedDescending().take(KEPT)
        set.retainAll(kept.toSet())
        database.withWriteTransaction {
            userStateDao.put(UserStateEntity(userId, LAST_BUILD_KEY, json.encodeToString(LastBuild.serializer(), LastBuild(kept.map { it.toEpochMilli() }))))
        }
    }

    @Serializable
    private data class LastBuild(val handled: List<Long>)

    companion object {
        const val LAST_BUILD_KEY = "lastBuild"
        private const val KEPT = 8
        private val json = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(Scheduler::class.java)
    }
}

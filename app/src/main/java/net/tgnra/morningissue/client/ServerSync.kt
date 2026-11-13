package net.tgnra.morningissue.client

import android.util.Log
import androidx.room3.withWriteTransaction
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.StandDto
import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.storage.StorageRepository

/** How a [ServerSync] run ended. */
sealed interface SyncOutcome {
    /** The cache is the server's as of now; [stand] is what `GET /stand` said. */
    data class Synced(val stand: StandDto?) : SyncOutcome

    /** Not logged in, the password must be changed, or the old library is not uploaded yet. */
    data object NotReady : SyncOutcome

    /** The server could not be reached; the cache is as it was. */
    data object Offline : SyncOutcome

    /** The token was refused: the reader has to log in again. */
    data object SignedOut : SyncOutcome

    data class Failed(val error: Exception) : SyncOutcome
}

/**
 * Brings the cache up to date with the server (`docs/server-design.md`, 5.2):
 *
 * 1. upload the outbox;
 * 2. `GET /feeds` into `feed`, `tag` and `feed_tag`;
 * 3. `GET /editions` into `edition`;
 * 4. `GET /sync/manifest` through the events gate: on `409 events_pending` the outbox is
 *    uploaded again and the manifest asked for again, at most [ApiLimits.MAX_GATE_ROUNDS] times;
 *    a changed `dbId` (the server's database was reset) wipes the cache and starts again;
 * 5. each edition bundle whose `version` changed;
 * 6. the saved shelf, if its `version` changed;
 * 7. `GET /engagement` since the cursor, then the outbox's pending events again on top, so
 *    what the reader did since stays visible;
 * 8. the settings and the stand (the server's verdict on the edition that is due);
 * 9. the newest edition's pictures into the image store, and the storage trim.
 *
 * One run at a time: a caller that arrives while one is running waits for it and takes its outcome.
 */
class ServerSync(
    private val database: MorningIssueDatabase,
    private val connection: ServerConnection,
    private val auth: AuthStore,
    private val outbox: Outbox,
    private val uploader: OutboxUploader,
    private val engagement: LocalEngagement,
    private val settings: ServerSettingsRepository,
    private val clock: Clock,
    private val images: ImageStore? = null,
    private val storage: StorageRepository? = null,
    /** Empties the cache (rows and image files); see `AppContainer.resetLocalCache`. */
    private val resetCache: suspend () -> Unit = { database.clearAllTables() },
    private val manifestEditions: Int = MANIFEST_EDITIONS,
    /** Told the stand after each sync, to aim the next background sync at the next release. */
    private val onStand: (StandDto) -> Unit = {},
) {
    private val writer = CacheWriter(database)
    private val lock = Mutex()
    private var finished = 0L
    private var lastOutcome: SyncOutcome? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun sync(reason: String = "manual"): SyncOutcome {
        val asked = finished
        return lock.withLock {
            // One finished while this caller waited: it covers this request too.
            lastOutcome?.takeIf { finished != asked }?.let { return@withLock it }
            _running.value = true
            try {
                runCatchingOutcome(reason).also {
                    lastOutcome = it
                    finished++
                }
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun runCatchingOutcome(reason: String): SyncOutcome {
        val state = auth.current()
        if (!state.isLoggedIn || state.mustChangePassword || !state.migrationSettled) return SyncOutcome.NotReady
        return try {
            connection.call { api -> run(api, wipedOnce = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException.Offline) {
            SyncOutcome.Offline
        } catch (_: ApiException.Unauthenticated) {
            SyncOutcome.SignedOut
        } catch (_: ApiException.PasswordChangeRequired) {
            SyncOutcome.NotReady
        } catch (e: Exception) {
            Log.w(TAG, "Sync ($reason) failed", e)
            SyncOutcome.Failed(e)
        }
    }

    private suspend fun run(api: MorningIssueApi, wipedOnce: Boolean): SyncOutcome {
        // 1
        uploader.flush(api)
        // 2, 3
        writer.applyFeeds(api.feeds())
        writer.upsertEditionSummaries(api.editions(limit = ApiLimits.DEFAULT_EDITIONS_PAGE).editions)
        // 4
        val manifest = uploader.gated(api) { through -> api.manifest(through, manifestEditions) }
        val state = auth.current()
        state.deviceId?.let { id -> manifest.acked[id]?.let { uploader.acked(it) } }
        if (state.dbId != null && state.dbId != manifest.dbId) {
            check(!wipedOnce) { "The server's database ID changed twice in one sync" }
            resetCache()
            auth.cacheReset(manifest.dbId)
            return run(api, wipedOnce = true)
        }
        if (state.dbId == null) auth.setDbId(manifest.dbId)
        // 5
        val versions = state.editionVersions.toMutableMap()
        for (edition in manifest.editions) {
            if (versions[edition.id] == edition.version && database.cacheDao().itemCount(edition.id) > 0) continue
            writer.storeBundle(uploader.gated(api) { through -> api.bundle(edition.id, through) })
            versions[edition.id] = edition.version
            auth.setEditionVersions(versions)
        }
        // 6
        if (manifest.saved.version != state.savedVersion) {
            writer.storeSaved(uploader.gated(api) { through -> api.saved(through) })
            auth.setSavedVersion(manifest.saved.version)
        }
        // 7
        val delta = api.engagement(state.engagementCursor)
        writer.applyEngagement(delta)
        auth.setEngagementCursor(delta.cursor)
        database.withWriteTransaction { engagement.reapply(outbox.pending()) }
        // 8
        settings.applyServer(api.settings())
        settings.followDeviceTimeZone(api)
        val stand = api.stand()
        settings.applyStand(stand)
        onStand(stand)
        auth.setLastSyncAt(clock.instant())
        // 9
        bestEffort("images") {
            val newest = manifest.editions.maxByOrNull { it.releaseAt }
            if (newest != null) images?.storeForArticles(database.editionDao().getItems(newest.id).map { it.row.article.id })
        }
        bestEffort("trim") { storage?.trimToBudget() }
        return SyncOutcome.Synced(stand)
    }

    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync: $what failed", e)
        }
    }

    companion object {
        private const val TAG = "ServerSync"

        /** Editions the manifest is asked for, whose bundles the cache keeps current. */
        const val MANIFEST_EDITIONS = 10
    }
}

package net.tgnra.morningissue.edition

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.data.settings.SettingsRepository
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.model.IndexGroup
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.ReleaseChange
import net.tgnra.morningissue.model.ThemeMode
import net.tgnra.morningissue.sync.FeedSyncer
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult
import net.tgnra.morningissue.sync.SyncReport
import net.tgnra.morningissue.sync.SyncStatus

internal class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
}

internal class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun setResetTime(time: LocalTime) = state.update { it.copy(resetTime = time, resetChange = null) }
    override suspend fun changeResetTime(time: LocalTime, change: ReleaseChange) =
        state.update { it.copy(resetTime = time, resetChange = change) }
    override suspend fun changeEveningEdition(enabled: Boolean, time: LocalTime, change: ReleaseChange?) =
        state.update { it.copy(eveningEditionEnabled = enabled, eveningEditionTime = time, eveningChange = change) }
    override suspend fun setEditionLength(length: EditionLength) = state.update { it.copy(editionLength = length) }
    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
    override suspend fun setDisplayMode(mode: DisplayMode) = state.update { it.copy(displayMode = mode) }
    override suspend fun setEveningEditionEnabled(enabled: Boolean) =
        state.update { it.copy(eveningEditionEnabled = enabled, eveningChange = null) }
    override suspend fun setEveningEditionTime(time: LocalTime) =
        state.update { it.copy(eveningEditionTime = time, eveningChange = null) }
    override suspend fun setStorageBudgetMb(megabytes: Int) = state.update { it.copy(storageBudgetMb = megabytes) }
    override suspend fun setOnboardingDone(done: Boolean) = state.update { it.copy(onboardingDone = done) }
    override suspend fun recordNothingNew(releaseAt: Instant) =
        state.update { it.copy(nothingNewFor = (listOf(releaseAt) + it.nothingNewFor).distinct().take(AppSettings.NOTHING_NEW_KEPT)) }

    val current: AppSettings get() = state.value
}

/** Records the order of everything the early build does. */
internal class Journal {
    private val entries = mutableListOf<String>()

    @Synchronized
    fun add(entry: String) {
        entries += entry
    }

    @Synchronized
    fun all(): List<String> = entries.toList()
}

internal class FakeSyncer(
    private val journal: Journal,
    private val behaviour: suspend () -> SyncReport = { SyncReport(feedsChecked = 3, newArticles = 5) },
) : FeedSyncer {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)

    override suspend fun syncAll(): SyncReport {
        journal.add("sync")
        return behaviour()
    }

    override suspend fun syncFeed(feedId: Long): SyncReport = SyncReport()
}

internal class FakePublisher(
    private val journal: Journal,
    var nextRelease: Instant?,
    var nextEditionId: Long? = 7,
    var currentEditionId: Long? = 3,
    var buildFailure: Exception? = null,
) : EditionPublisher {
    override suspend fun ensureCurrentEdition(): Long? {
        journal.add("ensureCurrent")
        return currentEditionId
    }

    override suspend fun buildNextEdition(): Long? {
        journal.add("buildNext")
        buildFailure?.let { throw it }
        return nextEditionId
    }

    override suspend fun nextReleaseAt(): Instant? = nextRelease
}

internal class FakeEditions(
    private val editions: Map<Long, Edition>,
    /** The stand: which editions count as released, as IDs into [editions]. */
    var released: List<Long> = emptyList(),
) : EditionRepository {
    override fun observeReleasedEditions(): Flow<List<EditionSummary>> = flowOf(released.map { summary(editions.getValue(it)) })
    override fun observeEdition(editionId: Long): Flow<Edition?> = flowOf(editions[editionId])
    override suspend fun getEdition(editionId: Long): Edition? = editions[editionId]
    override suspend fun ensureCurrentEdition(): Long? = null
    override suspend fun markEditionOpened(editionId: Long) = Unit
    override suspend fun markEditionFinished(editionId: Long) = Unit
}

internal class FakeFullText(
    private val journal: Journal,
    private val result: (Long) -> FullTextResult = { FullTextResult.Stored },
) : FullTextFetcher {
    override suspend fun ensureFullText(articleId: Long): FullTextResult {
        journal.add("text:$articleId")
        return result(articleId)
    }
}

internal fun summary(edition: Edition) = EditionSummary(
    id = edition.id,
    date = edition.date,
    kind = edition.kind,
    number = edition.number,
    length = edition.length,
    releaseAt = edition.releaseAt,
    cutoffAt = edition.cutoffAt,
    leadHeadline = null,
    leadImageUrl = null,
    leadSource = null,
    storyCount = 5,
    isRead = false,
)

internal fun story(articleId: Long, kind: ItemKind, imageUrl: String? = null, slot: Int = 0) = EditionStory(
    articleId = articleId,
    feedId = 1,
    feedTitle = "Feed",
    feedIconUrl = null,
    title = "Story $articleId",
    summary = null,
    imageUrl = imageUrl,
    author = null,
    url = "https://example.com/$articleId",
    publishedAt = Instant.EPOCH,
    readMinutes = 3,
    kind = kind,
    slot = slot,
    score = 0.5,
    reason = null,
    isOpened = false,
    isRead = false,
    isLiked = false,
    isSaved = false,
)

/** Front page (1 with an image, 2 without), Top stories (3, 4), In brief (5), index (6). */
internal fun sampleEdition(id: Long) = Edition(
    id = id,
    date = LocalDate.of(2026, 11, 11),
    kind = EditionKind.MORNING,
    number = 2,
    length = EditionLength.STANDARD,
    cutoffAt = Instant.EPOCH,
    releaseAt = Instant.EPOCH,
    builtAt = Instant.EPOCH,
    pages = listOf(
        EditionPage.Front(1, story(1, ItemKind.LEAD, "https://img.example/1.jpg"), listOf(story(2, ItemKind.FRONT))),
        EditionPage.TopStories(
            2,
            listOf(story(3, ItemKind.TOP, "https://img.example/3.jpg"), story(4, ItemKind.TOP, "https://img.example/1.jpg")),
        ),
        EditionPage.InBrief(3, listOf(story(5, ItemKind.BRIEF, "https://img.example/5.jpg"))),
        EditionPage.Back(
            4,
            listOf(IndexGroup(1, "Feed", listOf(story(6, ItemKind.INDEX, "https://img.example/6.jpg")))),
            nextEditionAt = null,
        ),
    ),
    readMinutes = 12,
    isRead = false,
)

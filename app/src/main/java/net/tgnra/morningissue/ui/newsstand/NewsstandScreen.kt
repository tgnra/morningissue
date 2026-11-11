package net.tgnra.morningissue.ui.newsstand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.tgnra.morningissue.model.EditionKind
import kotlinx.coroutines.launch
import net.tgnra.morningissue.engine.EditionClock
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.sync.SyncReport
import net.tgnra.morningissue.sync.SyncStatus
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.LocalPagedScroller
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.components.pagedKeys
import net.tgnra.morningissue.ui.components.pagedVerticalScroll
import net.tgnra.morningissue.ui.components.rememberPagedScroller
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.NewsstandArrangement
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** How many earlier editions are racked behind today's; older ones are in the calendar. */
private const val RACKED_EDITIONS = 6

/**
 * Home: today's edition facing out, the last six racked below or beside it newest first, an
 * "Older issues" tile to the calendar, and the button bar (Search, Saved, Sources, Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsstandScreen(
    onOpenEdition: (editionId: Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val spec = LocalPaperLayoutSpec.current
    val colors = MorningIssueTheme.colors
    val eink = MorningIssueTheme.isEink
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    val editions by retainedState("editions") { container.editions.observeReleasedEditions() }
    val feeds by retainedState("feeds") { container.feeds.observeFeeds() }
    val syncStatus by container.feedSyncer.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val zone = ZoneId.systemDefault()

    // The reset for which the release check below has finished. Until it has, a missing edition
    // may simply not have been printed yet, so nothing is said about it.
    var checkedReset by rememberSaveable { mutableStateOf<Long?>(null) }

    // Release is a clock check on coming to the foreground; it never syncs.
    LifecycleStartEffect(container) {
        val job = scope.launch {
            container.editions.ensureCurrentEdition()
            val resetTime = container.settings.settings.first().resetTime
            checkedReset = EditionClock.latestReset(container.clock.instant(), resetTime, zone).toEpochMilli()
        }
        onStopOrDispose { job.cancel() }
    }

    val now = container.clock.instant()
    val resetTime = settings?.resetTime
    // An Extra edition is not the day's paper: the notice below is about scheduled editions.
    val newest = editions?.firstOrNull { it.kind != EditionKind.EXTRA }
    // Set when a reset has passed and the feeds had nothing new to print.
    val noNewEdition = newest != null && resetTime != null &&
        checkedReset == EditionClock.latestReset(now, resetTime, zone).toEpochMilli() &&
        StandEditions.of(newest.date, now, resetTime, zone) == StandEdition.NoNewEdition

    var refreshing by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            notice = null
            scope.launch {
                try {
                    // Fetches for the next edition; today's is frozen and stays as it is.
                    val report = container.feedSyncer.syncAll()
                    notice = refreshMessage(report) { id, count -> resources.getQuantityString(id, count, count) }
                        ?: resources.getString(if (report.feedsChecked == 0) R.string.refresh_no_sources else R.string.refresh_no_new)
                } finally {
                    refreshing = false
                }
            }
        }
    }

    val newSources = remember(feeds, editions, settings?.extraOfferDismissedAt) {
        ExtraOffer.newSources(
            feeds = feeds.orEmpty(),
            editions = editions.orEmpty(),
            dismissedAt = settings?.extraOfferDismissedAt,
            today = now.atZone(zone).toLocalDate(),
        )
    }
    var printingExtra by remember { mutableStateOf(false) }
    val printExtra: () -> Unit = {
        if (!printingExtra && newSources.isNotEmpty()) {
            printingExtra = true
            notice = null
            val ids = newSources.map { it.id }.toSet()
            scope.launch {
                try {
                    // Fetch the new sources first: an imported list has not been read at all yet.
                    coroutineScope { ids.map { id -> async { container.feedSyncer.syncFeed(id) } }.awaitAll() }
                    val printed = container.editions.publishExtraEdition(ids)
                    if (printed == null) {
                        // Nothing recent enough to print; do not keep offering the same sources.
                        container.settings.setExtraOfferDismissedAt(container.clock.instant())
                    }
                    notice = resources.getString(if (printed == null) R.string.extra_nothing_to_print else R.string.extra_printed)
                } finally {
                    printingExtra = false
                }
            }
        }
    }

    val buttons = listOf(
        StandButton(Icons.Filled.Search, stringResource(R.string.nav_search), onOpenSearch),
        StandButton(PaperIcons.Bookmark, stringResource(R.string.nav_saved), onOpenSaved),
        StandButton(PaperIcons.Feed, stringResource(R.string.nav_sources), onOpenSources),
        StandButton(Icons.Filled.Settings, stringResource(R.string.nav_settings), onOpenSettings),
    )

    val stand: @Composable (Modifier) -> Unit = { modifier ->
        val scroller = rememberPagedScroller()
        val body: @Composable () -> Unit = {
            CompositionLocalProvider(LocalPagedScroller provides scroller) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .pagedKeys(
                            onForward = { scope.launch { scroller.stepForward() } },
                            onBackward = { scope.launch { scroller.stepBackward() } },
                        )
                        .pagedVerticalScroll(scroller)
                        .padding(bottom = 24.dp),
                ) {
                    StandHeader(refreshing = refreshing, onRefresh = refresh)
                    StandStatus(
                        status = syncStatus.takeIf { refreshing },
                        notice = notice,
                        modifier = Modifier.padding(horizontal = spec.pageMargin),
                    )
                    if (noNewEdition) {
                        NoNewEditionNotice(
                            newestDate = newest.date,
                            expectedDate = StandEditions.expectedDate(now, resetTime, zone),
                            nextEditionAt = EditionClock.nextReset(now, resetTime, zone).atZone(zone).toLocalTime(),
                            refreshing = refreshing,
                            onRefresh = refresh,
                            modifier = Modifier.padding(horizontal = spec.pageMargin),
                        )
                    }
                    if (newSources.isNotEmpty()) {
                        ExtraEditionOffer(
                            sourceCount = newSources.size,
                            printing = printingExtra,
                            onPrint = printExtra,
                            onDismiss = { scope.launch { container.settings.setExtraOfferDismissedAt(container.clock.instant()) } },
                            modifier = Modifier.padding(horizontal = spec.pageMargin),
                        )
                    }
                    val list = editions
                    when {
                        list == null -> Unit
                        list.isEmpty() -> EmptyStand(
                            hasSources = !feeds.isNullOrEmpty(),
                            refreshing = refreshing,
                            onAddSources = onOpenSources,
                            onRefresh = refresh,
                        )
                        else -> Rack(
                            editions = list,
                            arrangement = spec.newsstand,
                            margin = spec.pageMargin,
                            onOpenEdition = onOpenEdition,
                            onOpenBackIssues = onOpenBackIssues,
                        )
                    }
                }
            }
        }
        if (eink) {
            // No pull gesture on e-ink (its indicator is an animation); the header button refreshes.
            Box(modifier) { body() }
        } else {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = refresh,
                modifier = modifier,
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = colors.paperRaised,
                        color = colors.accent,
                    )
                },
            ) { body() }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.stand)) {
        if (spec.useButtonRail) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                StandButtonRail(buttons)
                stand(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                stand(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                )
                StandButtonBar(buttons)
            }
        }
    }
}

/** "12 new posts saved for tomorrow", with unreachable sources appended; null when nothing is new. */
internal fun refreshMessage(report: SyncReport, plural: (id: Int, count: Int) -> String): String? {
    val failed = report.feedsFailed.takeIf { it > 0 }?.let { plural(R.plurals.sources_unreachable, it) }
    val saved = report.newArticles.takeIf { it > 0 }?.let { plural(R.plurals.new_posts_saved, it) }
    return when {
        saved != null && failed != null -> "$saved · $failed"
        saved != null -> saved
        failed != null -> failed
        else -> null
    }
}

@Composable
private fun StandHeader(refreshing: Boolean, onRefresh: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val spec = LocalPaperLayoutSpec.current
    Row(
        Modifier.fillMaxWidth().padding(start = spec.pageMargin, end = spec.pageMargin - 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MorningIssueTheme.typography.masthead,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            SectionLabel(PaperFormat.longDate(LocalDate.now()))
        }
        PaperIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.newsstand_refresh),
            onClick = onRefresh,
            enabled = !refreshing,
        )
    }
}

/** The line under the header: sync progress while refreshing, then its result. */
@Composable
private fun StandStatus(status: SyncStatus?, notice: String?, modifier: Modifier = Modifier) {
    val type = MorningIssueTheme.typography
    val colors = MorningIssueTheme.colors
    when {
        status is SyncStatus.Running -> Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                pluralStringResource(R.plurals.fetching_feeds, status.feedsTotal, status.feedsTotal),
                style = type.label,
                color = colors.ink,
            )
            ThinProgressBar(if (status.feedsTotal > 0) status.feedsDone.toFloat() / status.feedsTotal else 0f)
        }
        notice != null -> NoticeLine(notice, modifier.padding(top = 8.dp))
    }
}

/**
 * Said above the cover when a reset has passed without a new edition because the feeds had
 * nothing new. Set like a notice in the paper itself, between rules, not like an error: there
 * is nothing wrong, and the way to look again is right under it.
 */
@Composable
private fun NoNewEditionNotice(
    newestDate: LocalDate,
    expectedDate: LocalDate,
    nextEditionAt: LocalTime,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val weekday = StandEditions.sinceWeekday(newestDate, expectedDate)
    val since = if (weekday != null) {
        stringResource(R.string.newsstand_no_edition_since_day, weekday.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale))
    } else {
        stringResource(R.string.newsstand_no_edition_since_date, PaperFormat.shortDate(newestDate))
    }
    Box(modifier.fillMaxWidth().stepAnchor().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.rule))
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.newsstand_no_edition_title), color = colors.ink, heading = true)
            Spacer(Modifier.height(6.dp))
            Text(
                text = since + " " + stringResource(R.string.newsstand_next_edition, PaperFormat.time(nextEditionAt, use24Hour)),
                style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            if (refreshing) {
                Spacer(Modifier.height(12.dp))
            } else {
                TextLink(stringResource(R.string.newsstand_refresh), onRefresh)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        }
    }
}

/** Offered after sources are added: a paper from them now, without waiting for the next reset. */
@Composable
private fun ExtraEditionOffer(sourceCount: Int, printing: Boolean, onPrint: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Box(modifier.fillMaxWidth().stepAnchor().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.rule))
            Spacer(Modifier.height(12.dp))
            SectionLabel(pluralStringResource(R.plurals.extra_offer_title, sourceCount, sourceCount), color = colors.ink, heading = true)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (printing) R.string.extra_printing else R.string.extra_offer_detail),
                style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            if (printing) {
                Spacer(Modifier.height(12.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextLink(stringResource(R.string.extra_offer_print), onPrint)
                    TextLink(stringResource(R.string.extra_offer_dismiss), onDismiss)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        }
    }
}

@Composable
private fun EmptyStand(hasSources: Boolean, refreshing: Boolean, onAddSources: () -> Unit, onRefresh: () -> Unit) {
    val colors = MorningIssueTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BlankCover(stringResource(R.string.newsstand_empty_title), Modifier.width(220.dp))
        ShelfBoard(Modifier.widthIn(max = 320.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(if (hasSources) R.string.newsstand_empty_waiting else R.string.newsstand_empty_no_sources),
            style = MorningIssueTheme.typography.standfirst,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (hasSources) {
            PaperButton(stringResource(R.string.newsstand_refresh), onRefresh, icon = Icons.Filled.Refresh, enabled = !refreshing)
        } else {
            PaperButton(stringResource(R.string.newsstand_add_sources), onAddSources, icon = PaperIcons.Feed, primary = true)
        }
    }
}

/** Today's cover and the shelves, arranged for the window. */
@Composable
private fun Rack(
    editions: List<EditionSummary>,
    arrangement: NewsstandArrangement,
    margin: Dp,
    onOpenEdition: (Long) -> Unit,
    onOpenBackIssues: () -> Unit,
) {
    val today = editions.first()
    val earlier = editions.drop(1).take(RACKED_EDITIONS)
    val eink = MorningIssueTheme.isEink

    if (arrangement == NewsstandArrangement.CoverWithShelfGrid) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(margin * 1.5f),
        ) {
            TodayDisplay(today, onOpenEdition, Modifier.weight(0.42f), maxCoverWidth = 420.dp)
            Column(Modifier.weight(0.58f)) {
                ShelfHeading()
                ShelfGrid(earlier, onOpenEdition, onOpenBackIssues, targetCoverWidth = 128.dp)
            }
        }
    } else {
        val twoShelves = arrangement == NewsstandArrangement.TwoShelves
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TodayDisplay(
                edition = today,
                onOpenEdition = onOpenEdition,
                modifier = Modifier.fillMaxWidth().padding(horizontal = margin),
                maxCoverWidth = if (twoShelves) 340.dp else 264.dp,
            )
            Spacer(Modifier.height(20.dp))
            ShelfHeading(Modifier.padding(horizontal = margin))
            if (twoShelves || eink) {
                // E-ink never scrolls sideways, so its phone shelf wraps onto further shelves too.
                ShelfGrid(
                    editions = earlier,
                    onOpenEdition = onOpenEdition,
                    onOpenBackIssues = onOpenBackIssues,
                    targetCoverWidth = if (twoShelves) 132.dp else 100.dp,
                    modifier = Modifier.padding(horizontal = margin),
                )
            } else {
                ScrollingShelf(earlier, margin, onOpenEdition, onOpenBackIssues)
            }
        }
    }
}

@Composable
private fun ShelfHeading(modifier: Modifier = Modifier) {
    SectionLabel(
        stringResource(R.string.newsstand_recent),
        modifier = modifier.stepAnchor().padding(bottom = 12.dp),
        heading = true,
    )
}

/** Today's edition, front-facing on its own shelf, with its caption. */
@Composable
private fun TodayDisplay(
    edition: EditionSummary,
    onOpenEdition: (Long) -> Unit,
    modifier: Modifier = Modifier,
    maxCoverWidth: Dp,
) {
    val colors = MorningIssueTheme.colors
    val isToday = edition.date == LocalDate.now()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val coverWidth = (maxWidth - 48.dp).coerceAtMost(maxCoverWidth)
            CoverButton(edition, onOpenEdition, Modifier.width(coverWidth))
        }
        ShelfBoard()
        Spacer(Modifier.height(10.dp))
        SectionLabel(
            text = stringResource(if (isToday) R.string.newsstand_today else R.string.newsstand_latest),
            color = colors.ink,
        )
        Text(
            text = pluralStringResource(R.plurals.story_count, edition.storyCount, edition.storyCount) +
                if (isToday) "" else " · " + PaperFormat.shortDate(edition.date),
            style = MorningIssueTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}

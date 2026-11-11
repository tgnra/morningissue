package net.tgnra.morningissue.ui.newsstand

import android.text.format.DateFormat
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.edition.BuildStage
import net.tgnra.morningissue.edition.DueEditions
import net.tgnra.morningissue.edition.DueRelease
import net.tgnra.morningissue.edition.DueStatus
import net.tgnra.morningissue.edition.PrintResult
import net.tgnra.morningissue.edition.PrintState
import net.tgnra.morningissue.engine.schedule
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.sync.SyncStatus
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.LocalPagedScroller
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.components.pagedKeys
import net.tgnra.morningissue.ui.components.pagedVerticalScroll
import net.tgnra.morningissue.ui.components.rememberPagedScroller
import net.tgnra.morningissue.ui.components.retainedState
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
 *
 * The stand never builds an edition by being opened, and it has no refresh: editions are
 * printed in the background ahead of their release. When the one that is due failed to build
 * (`DueEditions` has the rule), an unprinted cover stands in its place with a Print now
 * button, which hands the work to the app-wide `EditionPrinter`; this screen only shows where
 * that has got to, so leaving or rotating it loses nothing.
 */
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
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    val editions by retainedState("editions") { container.editions.observeReleasedEditions() }
    val feeds by retainedState("feeds") { container.feeds.observeFeeds() }
    val syncStatus by container.feedSyncer.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val printState by container.editionPrinter.state.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    // Every release time below comes from the schedule, which knows how a time changed in
    // Settings takes effect: from the next release, never for one that has already come.
    val schedule = settings?.schedule

    // Release is a clock check, taken when the stand comes to the foreground and again each
    // time a release time passes while it is on screen. Nothing is built or fetched here.
    var clockCheck by remember { mutableIntStateOf(0) }
    LifecycleStartEffect(container, settings?.resetTime, settings?.eveningEditionEnabled, settings?.eveningEditionTime, settings?.resetChange, settings?.eveningChange) {
        clockCheck++
        val job = scope.launch {
            if (schedule == null) return@launch
            while (true) {
                val next = schedule.nextRelease(container.clock.instant(), zone).at
                delay(Duration.between(container.clock.instant(), next).toMillis().coerceAtLeast(0) + 1)
                clockCheck++
            }
        }
        onStopOrDispose { job.cancel() }
    }
    val now = remember(clockCheck) { container.clock.instant() }

    val printing = printState as? PrintState.Printing
    // An edition Print now has stored but is still fetching stories for stays behind the
    // placeholder until the run is over.
    val onStand = editions?.filter { it.id != printing?.editionId }
    val subscribed = feeds

    // Null until the editions, the sources and the settings have all been read: until then a
    // missing edition may simply not have been loaded, so nothing is said about it.
    val status = if (onStand != null && subscribed != null && schedule != null) {
        DueEditions.status(
            now = now,
            schedule = schedule,
            zone = zone,
            released = onStand,
            nothingNewFor = settings?.nothingNewFor.orEmpty(),
            hasSources = subscribed.isNotEmpty(),
        )
    } else {
        null
    }
    // The edition the placeholder stands in for: one that failed to build, or one being
    // printed at the reader's request after a "nothing new" verdict.
    val unprinted: DueRelease? = when (status) {
        is DueStatus.NotPrinted -> status.release
        is DueStatus.NothingNew -> status.release.takeIf { printing != null }
        else -> null
    }

    // What the last Print now run came to, while it is still about the paper that is due.
    val lastResult = (printState as? PrintState.Finished)
        ?.takeIf { schedule != null && !it.at.isBefore(DueEditions.due(now, schedule, zone).first().at) }
        ?.result
    val printMessage = when (lastResult) {
        PrintResult.PrintedFromStored -> R.string.print_from_stored
        PrintResult.NothingNew -> R.string.print_nothing_new
        PrintResult.Unreachable -> R.string.print_unreachable
        PrintResult.Failed -> R.string.print_failed
        PrintResult.Printed, null -> null
    }
    val printFailed = lastResult == PrintResult.Unreachable || lastResult == PrintResult.Failed

    // An Extra edition is not the day's paper: the notice below is about scheduled editions.
    val newest = onStand?.firstOrNull { it.kind != EditionKind.EXTRA }
    // Set when a reset has passed and the feeds had nothing new to print.
    val noNewEdition = status is DueStatus.NothingNew && unprinted == null && newest != null && schedule != null &&
        StandEditions.of(newest.date, status.release.date) == StandEdition.NoNewEdition

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val printNow: () -> Unit = {
        notice = null
        // Does nothing if a run is already under way.
        container.editionPrinter.printNow()
    }

    val newSources = remember(feeds, editions, settings?.extraOfferDismissedAt) {
        ExtraOffer.newSources(
            feeds = feeds.orEmpty(),
            editions = editions.orEmpty(),
            dismissedAt = settings?.extraOfferDismissedAt,
        )
    }
    // Today's Extra edition, if there is one, is reprinted with the new sources.
    val reprintsExtra = ExtraOffer.reprints(editions.orEmpty(), now.atZone(zone).toLocalDate())
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
                    notice = resources.getString(
                        when {
                            printed == null -> R.string.extra_nothing_to_print
                            reprintsExtra -> R.string.extra_reprinted
                            else -> R.string.extra_printed
                        },
                    )
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
        Box(modifier) {
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
                    StandHeader()
                    if (printing == null) {
                        // A Print now result outranks an older Extra edition notice.
                        val message = printMessage?.let { stringResource(it) } ?: notice
                        if (message != null) {
                            NoticeLine(
                                text = message,
                                modifier = Modifier.padding(horizontal = spec.pageMargin).padding(top = 8.dp),
                                isError = printMessage != null && printFailed,
                            )
                        }
                    }
                    if (noNewEdition) {
                        NoNewEditionNotice(
                            newestDate = newest.date,
                            expectedDate = status.release.date,
                            nextEditionAt = schedule.nextReset(now, zone).atZone(zone).toLocalTime(),
                            onCheck = printNow,
                            modifier = Modifier.padding(horizontal = spec.pageMargin),
                        )
                    }
                    // While today's edition is unprinted, Print now is the offer: it takes the
                    // new sources too, so an Extra edition from them is not offered beside it.
                    if (newSources.isNotEmpty() && unprinted == null) {
                        ExtraEditionOffer(
                            sourceCount = newSources.size,
                            reprint = reprintsExtra,
                            printing = printingExtra,
                            onPrint = printExtra,
                            onDismiss = { scope.launch { container.settings.setExtraOfferDismissedAt(container.clock.instant()) } },
                            modifier = Modifier.padding(horizontal = spec.pageMargin),
                        )
                    }
                    when {
                        onStand == null || status == null -> Unit
                        unprinted != null -> Rack(
                            front = { frontModifier, maxCoverWidth ->
                                UnprintedDisplay(
                                    release = unprinted,
                                    today = now.atZone(zone).toLocalDate(),
                                    stage = printing?.stage,
                                    syncStatus = syncStatus,
                                    tryAgain = printFailed,
                                    onPrint = printNow,
                                    modifier = frontModifier,
                                    maxCoverWidth = maxCoverWidth,
                                )
                            },
                            earlier = onStand.take(RACKED_EDITIONS),
                            // With nothing printed yet there are no shelves to show beneath it.
                            shelves = onStand.isNotEmpty(),
                            arrangement = spec.newsstand,
                            margin = spec.pageMargin,
                            onOpenEdition = onOpenEdition,
                            onOpenBackIssues = onOpenBackIssues,
                        )
                        onStand.isEmpty() -> EmptyStand(
                            hasSources = !subscribed.isNullOrEmpty(),
                            onAddSources = onOpenSources,
                            onCheck = printNow,
                        )
                        else -> Rack(
                            front = { frontModifier, maxCoverWidth ->
                                TodayDisplay(onStand.first(), onOpenEdition, frontModifier, maxCoverWidth)
                            },
                            earlier = onStand.drop(1).take(RACKED_EDITIONS),
                            shelves = true,
                            arrangement = spec.newsstand,
                            margin = spec.pageMargin,
                            onOpenEdition = onOpenEdition,
                            onOpenBackIssues = onOpenBackIssues,
                        )
                    }
                }
            }
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

/**
 * How far a Print now run has got, 0..1, as one bar that only moves forward: the sync is the
 * first half, the build a step, the stories' text most of the rest, the pictures the end.
 * [sync] is the syncer's own count of feeds, which only matters during [BuildStage.Syncing].
 */
internal fun printProgress(stage: BuildStage, sync: SyncStatus): Float = when (stage) {
    BuildStage.Syncing -> {
        val running = sync as? SyncStatus.Running
        if (running != null && running.feedsTotal > 0) 0.5f * running.feedsDone / running.feedsTotal else 0f
    }
    BuildStage.Building -> 0.5f
    is BuildStage.FetchingText -> 0.55f + if (stage.total > 0) 0.35f * stage.done / stage.total else 0.35f
    is BuildStage.FetchingImages -> 0.95f
}

@Composable
private fun StandHeader() {
    val colors = MorningIssueTheme.colors
    val spec = LocalPaperLayoutSpec.current
    Column(Modifier.fillMaxWidth().padding(start = spec.pageMargin, end = spec.pageMargin, top = 12.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MorningIssueTheme.typography.masthead,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        SectionLabel(PaperFormat.longDate(LocalDate.now()))
    }
}

/**
 * Said above the cover when a reset has passed without a new edition because the feeds had
 * nothing new. Set like a notice in the paper itself, between rules, not like an error: there
 * is nothing wrong. The link under it looks again: it runs Print now, which fetches every
 * source and prints today's edition if anything has arrived since.
 */
@Composable
private fun NoNewEditionNotice(
    newestDate: LocalDate,
    expectedDate: LocalDate,
    nextEditionAt: LocalTime,
    onCheck: () -> Unit,
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
            TextLink(stringResource(R.string.newsstand_check), onCheck)
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        }
    }
}

/**
 * Offered after sources are added: a paper from them now, without waiting for the next reset.
 * With [reprint], today's Extra edition was printed before they were added, and the offer is
 * to print it again with them in it.
 */
@Composable
private fun ExtraEditionOffer(
    sourceCount: Int,
    reprint: Boolean,
    printing: Boolean,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Box(modifier.fillMaxWidth().stepAnchor().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.rule))
            Spacer(Modifier.height(12.dp))
            SectionLabel(pluralStringResource(R.plurals.extra_offer_title, sourceCount, sourceCount), color = colors.ink, heading = true)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    when {
                        printing && reprint -> R.string.extra_reprinting
                        printing -> R.string.extra_printing
                        reprint -> R.string.extra_reprint_detail
                        else -> R.string.extra_offer_detail
                    },
                ),
                style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            if (printing) {
                Spacer(Modifier.height(12.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextLink(
                        if (reprint) pluralStringResource(R.plurals.extra_reprint, sourceCount, sourceCount)
                        else stringResource(R.string.extra_offer_print),
                        onPrint,
                    )
                    TextLink(stringResource(R.string.extra_offer_dismiss), onDismiss)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        }
    }
}

/**
 * The stand with no edition on it and none that failed to build: either there are no sources
 * yet, or they have been checked and had nothing recent to print.
 */
@Composable
private fun EmptyStand(hasSources: Boolean, onAddSources: () -> Unit, onCheck: () -> Unit) {
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
            PaperButton(stringResource(R.string.newsstand_check), onCheck)
        } else {
            PaperButton(stringResource(R.string.newsstand_add_sources), onAddSources, icon = PaperIcons.Feed, primary = true)
        }
    }
}

/**
 * The front display and the shelves, arranged for the window. [front] is today's cover or the
 * unprinted placeholder, given the modifier and the widest cover its place allows; [earlier]
 * are the covers racked behind it. With [shelves] off only the front display is shown.
 */
@Composable
private fun Rack(
    front: @Composable (modifier: Modifier, maxCoverWidth: Dp) -> Unit,
    earlier: List<EditionSummary>,
    shelves: Boolean,
    arrangement: NewsstandArrangement,
    margin: Dp,
    onOpenEdition: (Long) -> Unit,
    onOpenBackIssues: () -> Unit,
) {
    val eink = MorningIssueTheme.isEink
    val twoShelves = arrangement == NewsstandArrangement.TwoShelves

    if (!shelves) {
        Box(Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            front(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                when (arrangement) {
                    NewsstandArrangement.CoverWithShelfGrid -> 420.dp
                    NewsstandArrangement.TwoShelves -> 340.dp
                    else -> 264.dp
                },
            )
        }
    } else if (arrangement == NewsstandArrangement.CoverWithShelfGrid) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(margin * 1.5f),
        ) {
            front(Modifier.weight(0.42f), 420.dp)
            Column(Modifier.weight(0.58f)) {
                ShelfHeading()
                ShelfGrid(earlier, onOpenEdition, onOpenBackIssues, targetCoverWidth = 128.dp)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            front(Modifier.fillMaxWidth().padding(horizontal = margin), if (twoShelves) 340.dp else 264.dp)
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

/**
 * The placeholder in today's place on the rack: the unprinted cover on its shelf and, where
 * a printed edition has its caption, the line that says it has not been printed and the
 * Print now button. While a run is under way ([stage] is set) the button gives way to its
 * progress: a line naming the stage and one bar. Nothing in it is animated, so it is the
 * same in E-ink mode; the bar is redrawn only when a feed or a story finishes.
 *
 * It takes the same [modifier] and [maxCoverWidth] as [TodayDisplay], so it sits where the
 * cover would in all three window classes; its words are capped at a readable width for the
 * wide column of the landscape tablet.
 */
@Composable
private fun UnprintedDisplay(
    release: DueRelease,
    today: LocalDate,
    stage: BuildStage?,
    syncStatus: SyncStatus,
    tryAgain: Boolean,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
    maxCoverWidth: Dp,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val title = when {
        release.date != today -> stringResource(R.string.newsstand_unprinted_date, PaperFormat.shortDate(release.date))
        release.kind == EditionKind.EVENING -> stringResource(R.string.newsstand_unprinted_evening)
        else -> stringResource(R.string.newsstand_unprinted_today)
    }
    Column(modifier.stepAnchor(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val coverWidth = (maxWidth - 48.dp).coerceAtMost(maxCoverWidth)
            UnprintedCover(release.date, Modifier.width(coverWidth))
        }
        ShelfBoard()
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            if (stage == null) {
                PaperButton(
                    text = stringResource(if (tryAgain) R.string.print_try_again else R.string.print_now),
                    onClick = onPrint,
                    icon = PaperIcons.Newspaper,
                    primary = true,
                )
            } else {
                PrintProgress(stage, syncStatus)
            }
        }
    }
}

/** Where a Print now run has got to: the stage in words, a count where there is one, and the bar. */
@Composable
private fun PrintProgress(stage: BuildStage, syncStatus: SyncStatus) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val running = syncStatus as? SyncStatus.Running
    val line = when (stage) {
        BuildStage.Syncing ->
            if (running != null) pluralStringResource(R.plurals.fetching_feeds, running.feedsTotal, running.feedsTotal)
            else stringResource(R.string.print_stage_syncing)
        BuildStage.Building -> stringResource(R.string.print_stage_building)
        is BuildStage.FetchingText -> stringResource(R.string.print_stage_text)
        is BuildStage.FetchingImages -> pluralStringResource(R.plurals.print_stage_images, stage.count, stage.count)
    }
    val count = when (stage) {
        BuildStage.Syncing -> running?.let { stringResource(R.string.fetching_progress, it.feedsDone, it.feedsTotal) }
        is BuildStage.FetchingText -> stringResource(R.string.fetching_progress, stage.done, stage.total)
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Announced when the stage changes, not at every count.
        Text(line, style = type.label, color = colors.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ThinProgressBar(printProgress(stage, syncStatus), height = 4.dp)
        Text(count ?: stringResource(R.string.print_stay), style = type.byline, color = colors.inkSecondary)
    }
}

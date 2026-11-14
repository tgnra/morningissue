package net.tgnra.morningissue.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.sync.SyncStatus
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DoubleRule
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.sources.AddFeedForm
import net.tgnra.morningissue.ui.sources.rememberAddFeedFlow
import net.tgnra.morningissue.ui.sources.rememberOpmlActions
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** How many of the added sources are named before "and N more". */
private const val LISTED_SOURCES = 5

private enum class Step {
    AddSources,

    /** Fetch every feed, then set the first edition. */
    FirstEdition,

    /** Straight to the stand with nothing fetched. */
    Skip,
}

/**
 * First launch: add feeds by address or OPML, fetch them all once ("Fetching 42 feeds…"),
 * build the first edition and move on to the newsstand. This is the only time the app waits on
 * the network. If nothing can be fetched or built, the stand's empty state takes over.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onFindSources: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val colors = MorningIssueTheme.colors
    val feeds by remember(container) { container.feeds.observeFeeds() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncStatus by container.feedSyncer.status.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(Step.AddSources) }
    var syncDone by remember { mutableStateOf(false) }
    val finished = rememberUpdatedState(onFinished)

    // Keyed on the step, which survives rotation, so an interrupted first sync is picked up again
    // (a second `syncAll` joins the one already running).
    LaunchedEffect(step) {
        if (step == Step.AddSources) return@LaunchedEffect
        if (step == Step.FirstEdition) {
            container.feedSyncer.syncAll()
            syncDone = true
            container.editions.ensureCurrentEdition()
        }
        container.settings.setOnboardingDone(true)
        finished.value()
    }

    Box(Modifier.fillMaxSize().background(colors.paper).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        when (step) {
            Step.AddSources -> AddSources(feeds, onPrint = { step = Step.FirstEdition }, onSkip = { step = Step.Skip }, onFindSources = onFindSources)
            Step.FirstEdition -> FirstEditionProgress(feedCount = feeds.size, status = syncStatus, syncing = !syncDone)
            Step.Skip -> Unit
        }
    }
}

@Composable
private fun Nameplate() {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Text(
        text = stringResource(R.string.app_name),
        style = type.masthead.copy(fontSize = type.masthead.fontSize * 1.3f, lineHeight = type.masthead.lineHeight * 1.3f),
        color = colors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { heading() },
    )
    DoubleRule()
}

@Composable
private fun AddSources(feeds: List<Feed>, onPrint: () -> Unit, onSkip: () -> Unit, onFindSources: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val addFlow = rememberAddFeedFlow(syncAfterAdd = false)
    var importNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var importFailed by rememberSaveable { mutableStateOf(false) }
    val opml = rememberOpmlActions(syncAfterImport = false) { message, isError ->
        importNotice = message
        importFailed = isError
    }

    PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 28.dp, bottom = 32.dp), maxWidth = 520.dp) {
        Nameplate()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_tagline), style = type.leadHeadline, color = colors.ink)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.onboarding_intro), style = type.standfirst, color = colors.ink)

        Spacer(Modifier.height(32.dp))
        SectionLabel(stringResource(R.string.onboarding_add_title), color = colors.ink, heading = true)
        Spacer(Modifier.height(10.dp))
        AddFeedForm(addFlow)
        Spacer(Modifier.height(8.dp))
        HairlineRule()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_import_detail), style = type.body, color = colors.inkSecondary)
        Spacer(Modifier.height(10.dp))
        PaperButton(stringResource(R.string.sources_import), opml.import)
        importNotice?.let { NoticeLine(it, Modifier.padding(top = 12.dp), isError = importFailed) }
        Spacer(Modifier.height(12.dp))
        HairlineRule()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_discover_detail), style = type.body, color = colors.inkSecondary)
        Spacer(Modifier.height(10.dp))
        PaperButton(stringResource(R.string.discover_entry), onFindSources)

        if (feeds.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionLabel(pluralStringResource(R.plurals.onboarding_sources_added, feeds.size, feeds.size), color = colors.ink, heading = true)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
            feeds.take(LISTED_SOURCES).forEach { feed ->
                Text(feed.title, style = type.brief, color = colors.ink, maxLines = 1, modifier = Modifier.padding(vertical = 8.dp))
                HairlineRule()
            }
            if (feeds.size > LISTED_SOURCES) {
                val more = feeds.size - LISTED_SOURCES
                Text(
                    pluralStringResource(R.plurals.onboarding_more_sources, more, more),
                    style = type.body.copy(fontStyle = FontStyle.Italic),
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        PaperButton(
            text = stringResource(R.string.onboarding_print),
            onClick = onPrint,
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = feeds.isNotEmpty(),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextLink(stringResource(R.string.onboarding_skip), onSkip)
        }
    }
}

@Composable
private fun FirstEditionProgress(feedCount: Int, status: SyncStatus, syncing: Boolean) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Nameplate()
            Spacer(Modifier.height(18.dp))
            val running = status as? SyncStatus.Running
            val total = running?.feedsTotal ?: feedCount
            Text(
                text = if (syncing) pluralStringResource(R.plurals.fetching_feeds, total, total) else stringResource(R.string.onboarding_setting),
                style = type.headline,
                color = colors.ink,
            )
            ThinProgressBar(
                fraction = when {
                    !syncing -> 1f
                    running != null && running.feedsTotal > 0 -> running.feedsDone.toFloat() / running.feedsTotal
                    else -> 0f
                },
                height = 4.dp,
            )
            if (syncing && running != null) {
                Text(
                    stringResource(R.string.fetching_progress, running.feedsDone, running.feedsTotal),
                    style = type.byline,
                    color = colors.inkSecondary,
                )
            }
            Text(stringResource(R.string.onboarding_wait), style = type.body, color = colors.inkSecondary)
        }
    }
}

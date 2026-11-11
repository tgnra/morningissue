package net.tgnra.morningissue.ui.sources

import android.content.ActivityNotFoundException
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.AppContainer
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.sync.DiscoveredFeed
import net.tgnra.morningissue.sync.DiscoveryResult
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** Where adding a feed by address has got to. */
sealed interface AddFeedState {
    data object Idle : AddFeedState
    data object Searching : AddFeedState

    /** The address offers several feeds; the user picks. */
    data class Choose(val feeds: List<DiscoveredFeed>) : AddFeedState
    data class Failed(val message: String) : AddFeedState
    data class Added(val message: String) : AddFeedState
}

/**
 * Adding a feed by site or feed address: discovery, then subscription. Shared by the Sources
 * dialog and onboarding. [syncAfterAdd] fetches the new feed straight away (not wanted in
 * onboarding, whose first sync fetches everything).
 */
@Stable
class AddFeedFlow(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val resources: Resources,
    private val syncAfterAdd: Boolean,
) {
    var input by mutableStateOf("")
    var state by mutableStateOf<AddFeedState>(AddFeedState.Idle)
        private set
    private var job: Job? = null

    val isBusy: Boolean get() = state == AddFeedState.Searching

    fun onInputChange(value: String) {
        input = value
        if (state is AddFeedState.Failed || state is AddFeedState.Choose) state = AddFeedState.Idle
    }

    fun submit() {
        val address = input.trim()
        if (address.isEmpty()) {
            state = AddFeedState.Failed(resources.getString(R.string.add_feed_blank))
            return
        }
        if (isBusy) return
        state = AddFeedState.Searching
        job = scope.launch {
            state = when (val result = container.feedDiscovery.discover(address)) {
                is DiscoveryResult.Found -> if (result.feeds.size == 1) subscribe(result.feeds.first()) else AddFeedState.Choose(result.feeds)
                DiscoveryResult.NoFeed -> AddFeedState.Failed(resources.getString(R.string.add_feed_none))
                is DiscoveryResult.Failed -> AddFeedState.Failed(resources.getString(R.string.add_feed_failed, result.message))
            }
        }
    }

    fun choose(feed: DiscoveredFeed) {
        if (isBusy) return
        state = AddFeedState.Searching
        job = scope.launch { state = subscribe(feed) }
    }

    fun reset() {
        job?.cancel()
        input = ""
        state = AddFeedState.Idle
    }

    private suspend fun subscribe(feed: DiscoveredFeed): AddFeedState {
        val name = feed.title?.takeIf { it.isNotBlank() } ?: FeedUrls.hostOf(feed.url)
        val added = container.feeds.addFeed(NewFeed(url = feed.url, title = feed.title, siteUrl = feed.siteUrl, iconUrl = feed.iconUrl))
        return when (added) {
            is AddFeedResult.AlreadySubscribed -> AddFeedState.Failed(resources.getString(R.string.add_feed_already, name))
            is AddFeedResult.Added -> {
                // Outlives the dialog: the posts are for the next edition either way.
                if (syncAfterAdd) container.appScope.launch { container.feedSyncer.syncFeed(added.feedId) }
                input = ""
                AddFeedState.Added(resources.getString(R.string.add_feed_added, name))
            }
        }
    }
}

/** Keeps an [AddFeedFlow] across rotation, so a discovery in flight is not thrown away with the screen. */
class AddFeedHolder : ViewModel() {
    private val flows = HashMap<Boolean, AddFeedFlow>()

    fun flow(container: AppContainer, resources: Resources, syncAfterAdd: Boolean): AddFeedFlow =
        flows.getOrPut(syncAfterAdd) { AddFeedFlow(container, viewModelScope, resources, syncAfterAdd) }
}

/**
 * The add-feed flow of the current destination. It lives in the destination's view-model store,
 * not in the composition: rotating the screen while a site is being searched keeps the search,
 * the typed address and the result.
 */
@Composable
fun rememberAddFeedFlow(syncAfterAdd: Boolean): AddFeedFlow {
    val container = LocalAppContainer.current
    // The application's resources: the flow outlives any one activity instance.
    val resources = LocalContext.current.applicationContext.resources
    val holder: AddFeedHolder = viewModel()
    return holder.flow(container, resources, syncAfterAdd)
}

/** The address field, its button and whatever the flow has to say. */
@Composable
fun AddFeedForm(flow: AddFeedFlow, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperTextField(
            value = flow.input,
            onValueChange = flow::onInputChange,
            placeholder = stringResource(R.string.add_feed_hint),
            isUrl = true,
            imeAction = ImeAction.Go,
            onImeAction = flow::submit,
            enabled = !flow.isBusy,
        )
        when (val state = flow.state) {
            AddFeedState.Idle -> Unit
            AddFeedState.Searching -> Text(stringResource(R.string.add_feed_searching), style = type.label, color = colors.inkSecondary)
            is AddFeedState.Failed -> NoticeLine(state.message, isError = true)
            is AddFeedState.Added -> NoticeLine(state.message)
            is AddFeedState.Choose -> Column {
                SectionLabel(stringResource(R.string.add_feed_choose), modifier = Modifier.padding(bottom = 4.dp))
                state.feeds.forEachIndexed { index, feed ->
                    if (index > 0) HairlineRule()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { flow.choose(feed) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(feed.title?.takeIf { it.isNotBlank() } ?: FeedUrls.hostOf(feed.url), style = type.headlineSmall, color = colors.ink)
                        Text(feed.url, style = type.caption, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                text = stringResource(R.string.add_feed_find),
                onClick = flow::submit,
                primary = true,
                enabled = !flow.isBusy,
            )
        }
    }
}

/** Starts the system file pickers for OPML. */
class OpmlActions(val import: () -> Unit, val export: () -> Unit)

const val OPML_EXPORT_NAME = "morning-issue-feeds.opml"

/** OPML rarely has a registered type, so the picker has to allow any file. */
private val OPML_IMPORT_TYPES = arrayOf("text/x-opml", "text/xml", "application/xml", "application/octet-stream", "*/*")

/** The outcome of an OPML import or export, waiting to be shown. */
class OpmlOutcome(val message: String, val isError: Boolean)

/**
 * Runs OPML imports and exports in the destination's view-model scope, so one in flight is not
 * thrown away when the screen rotates. The outcome waits in [outcome] until a screen has shown it.
 */
class OpmlHolder : ViewModel() {
    var outcome by mutableStateOf<OpmlOutcome?>(null)
        private set

    fun consume() {
        outcome = null
    }

    /** [work] runs off the main thread and returns the line to show; any failure shows [failure]. */
    fun run(failure: String, work: suspend () -> String) {
        viewModelScope.launch {
            outcome = try {
                OpmlOutcome(withContext(Dispatchers.IO) { work() }, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OpmlOutcome(failure, isError = true)
            }
        }
    }
}

/**
 * OPML import and export through the Storage Access Framework: the user picks the file, so no
 * storage permission is involved. [onResult] gets a line such as "Added 38, skipped 4".
 * [syncAfterImport] fetches the new feeds afterwards. The work survives rotation (see
 * [OpmlHolder]); its outcome is delivered to whichever instance of the screen is showing.
 */
@Composable
fun rememberOpmlActions(syncAfterImport: Boolean, onResult: (message: String, isError: Boolean) -> Unit): OpmlActions {
    val container = LocalAppContainer.current
    // The application's context and resources: the work outlives any one activity instance.
    val appContext = LocalContext.current.applicationContext
    val resources = appContext.resources
    val report = rememberUpdatedState(onResult)
    val holder: OpmlHolder = viewModel()

    val outcome = holder.outcome
    LaunchedEffect(outcome) {
        if (outcome != null) {
            report.value(outcome.message, outcome.isError)
            holder.consume()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        holder.run(failure = resources.getString(R.string.opml_import_failed)) {
            val stream = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
            val result = stream.use { container.opmlTransfer.importFrom(it) }
            if (syncAfterImport && result.added > 0) container.appScope.launch { container.feedSyncer.syncAll() }
            resources.getString(R.string.opml_import_result, result.added, result.skipped)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        holder.run(failure = resources.getString(R.string.opml_export_failed)) {
            val stream = appContext.contentResolver.openOutputStream(uri) ?: throw IOException("No stream for $uri")
            val count = stream.use { container.opmlTransfer.exportTo(it) }
            resources.getQuantityString(R.plurals.opml_exported, count, count)
        }
    }
    return remember(importLauncher, exportLauncher) {
        OpmlActions(
            import = {
                try {
                    importLauncher.launch(OPML_IMPORT_TYPES)
                } catch (_: ActivityNotFoundException) {
                    report.value(resources.getString(R.string.opml_no_picker), true)
                }
            },
            export = {
                try {
                    exportLauncher.launch(OPML_EXPORT_NAME)
                } catch (_: ActivityNotFoundException) {
                    report.value(resources.getString(R.string.opml_no_picker), true)
                }
            },
        )
    }
}

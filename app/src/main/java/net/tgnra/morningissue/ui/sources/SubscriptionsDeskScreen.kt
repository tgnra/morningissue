package net.tgnra.morningissue.ui.sources

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.settings.SettingsSection
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * The feed health check: feeds that carry a flag, grouped by flag, each with its one-tap
 * fixes. Nothing here happens on its own; Unsubscribe asks first. With no flags the desk says
 * so and shows nothing else.
 */
@Composable
fun SubscriptionsDeskScreen(
    onOpenSource: (feedId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val flagged by retainedState("desk-flagged") { container.feedHealth.observeFlagged() }
    var removingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var retryingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeIsError by rememberSaveable { mutableStateOf(false) }

    // The rules run after every sync; this catches what has changed since (a Like, a slider).
    LaunchedEffect(Unit) { container.feedHealth.refresh() }

    val perform: (FlaggedFeed, DeskAction) -> Unit = { item, action ->
        val id = item.feed.id
        when (action) {
            DeskAction.UNSUBSCRIBE -> removingId = id
            DeskAction.DISMISS -> scope.launch { container.feedHealth.dismiss(id) }
            DeskAction.ACCEPT -> scope.launch { container.feedHealth.acknowledge(id) }
            DeskAction.SHOW_LESS -> scope.launch { container.feedHealth.applySlider(id, SliderStep.LESS) }
            DeskAction.FAVOURITE -> scope.launch { container.feedHealth.applySlider(id, SliderStep.FAVOURITE) }
            DeskAction.RETRY -> if (retryingId == null) {
                retryingId = id
                notice = resources.getString(R.string.desk_retrying, item.feed.title)
                noticeIsError = false
                scope.launch {
                    val outcome = try {
                        container.feedHealth.retry(id)
                    } finally {
                        retryingId = null
                    }
                    noticeIsError = outcome == RetryOutcome.STILL_BROKEN
                    notice = resources.getString(
                        when (outcome) {
                            RetryOutcome.WORKING -> R.string.desk_retry_working
                            RetryOutcome.MOVED -> R.string.desk_retry_moved
                            RetryOutcome.STILL_BROKEN -> R.string.desk_retry_failed
                        },
                        item.feed.title,
                    )
                }
            }
        }
    }

    PaperScreenFrame(
        title = stringResource(R.string.desk_title),
        onBack = onBack,
        overlay = {
            val removing = flagged?.firstOrNull { it.feed.id == removingId }?.feed
            if (removing != null) {
                RemoveFeedDialog(
                    feed = removing,
                    onDismiss = { removingId = null },
                    onConfirm = {
                        removingId = null
                        container.appScope.launch { container.feeds.removeFeed(removing.id) }
                    },
                )
            }
        },
    ) {
        val list = flagged ?: return@PaperScreenFrame
        if (list.isEmpty()) {
            notice?.let { NoticeLine(it, Modifier.padding(horizontal = margin, vertical = 10.dp), isError = noticeIsError) }
            EmptyMessage(stringResource(R.string.desk_healthy_title), stringResource(R.string.desk_healthy_detail))
            return@PaperScreenFrame
        }
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            Text(
                stringResource(R.string.desk_intro),
                style = MorningIssueTheme.typography.body,
                color = MorningIssueTheme.colors.inkSecondary,
                modifier = Modifier.padding(top = 16.dp),
            )
            notice?.let { NoticeLine(it, Modifier.padding(top = 12.dp), isError = noticeIsError) }
            Desk.groups(list).forEach { (flag, feeds) ->
                SettingsSection(flagTitle(flag, feeds.size)) {
                    Text(
                        flagExplanation(flag),
                        style = MorningIssueTheme.typography.body,
                        color = MorningIssueTheme.colors.inkSecondary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    feeds.forEachIndexed { index, item ->
                        if (index > 0) HairlineRule(Modifier.padding(top = 12.dp))
                        FlaggedFeedRow(
                            item = item,
                            busy = retryingId == item.feed.id,
                            onOpen = { onOpenSource(item.feed.id) },
                            onAction = { perform(item, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun flagTitle(flag: HealthFlag, count: Int): String = stringResource(
    when (flag) {
        HealthFlag.BROKEN -> R.string.desk_flag_broken
        HealthFlag.MOVED -> R.string.desk_flag_moved
        HealthFlag.QUIET -> R.string.desk_flag_quiet
        HealthFlag.IGNORED -> R.string.desk_flag_ignored
        HealthFlag.NOISY -> R.string.desk_flag_noisy
        HealthFlag.LOVED -> R.string.desk_flag_loved
    },
) + " · $count"

@Composable
private fun flagExplanation(flag: HealthFlag): String = stringResource(
    when (flag) {
        HealthFlag.BROKEN -> R.string.desk_about_broken
        HealthFlag.MOVED -> R.string.desk_about_moved
        HealthFlag.QUIET -> R.string.desk_about_quiet
        HealthFlag.IGNORED -> R.string.desk_about_ignored
        HealthFlag.NOISY -> R.string.desk_about_noisy
        HealthFlag.LOVED -> R.string.desk_about_loved
    },
)

@Composable
private fun actionLabel(action: DeskAction): String = stringResource(
    when (action) {
        DeskAction.RETRY -> R.string.desk_action_retry
        DeskAction.ACCEPT -> R.string.desk_action_accept
        DeskAction.SHOW_LESS -> R.string.desk_action_less
        DeskAction.FAVOURITE -> R.string.desk_action_favourite
        DeskAction.UNSUBSCRIBE -> R.string.desk_action_unsubscribe
        DeskAction.DISMISS -> R.string.desk_action_dismiss
    },
)

private fun dateOf(instant: Instant): String = PaperFormat.shortDate(instant.atZone(ZoneId.systemDefault()).toLocalDate())

/** What the fetch record says about the feed, in one line; null when the cadence line says it all. */
@Composable
private fun flagDetail(item: FlaggedFeed): String? = when (item.flag) {
    HealthFlag.BROKEN -> when {
        item.lastHttpStatus == 410 -> stringResource(R.string.desk_detail_gone)
        item.errorSince != null -> stringResource(R.string.desk_detail_failing_since, dateOf(item.errorSince))
        else -> null
    }
    HealthFlag.MOVED -> stringResource(R.string.desk_detail_moved_to, item.feed.url)
    HealthFlag.QUIET -> item.feed.lastPostAt?.let { stringResource(R.string.desk_detail_last_post, dateOf(it)) }
        ?: stringResource(R.string.desk_detail_no_posts)
    HealthFlag.IGNORED, HealthFlag.NOISY, HealthFlag.LOVED -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlaggedFeedRow(
    item: FlaggedFeed,
    busy: Boolean,
    onOpen: () -> Unit,
    onAction: (DeskAction) -> Unit,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(Modifier.fillMaxWidth().stepAnchor().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.detail_posts), role = Role.Button, onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(item.feed.title, style = type.headlineSmall, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FeedCadence(item.feed)
            flagDetail(item)?.let { Text(it, style = type.body, color = colors.ink) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Desk.actions(item.flag).forEachIndexed { index, action ->
                when {
                    action == DeskAction.DISMISS -> TextLink(actionLabel(action), { onAction(action) })
                    action == DeskAction.RETRY && busy -> PaperButton(stringResource(R.string.desk_action_retrying), {}, enabled = false)
                    // The suggested fix comes first and is the filled one.
                    else -> PaperButton(actionLabel(action), { onAction(action) }, primary = index == 0 && action != DeskAction.UNSUBSCRIBE)
                }
            }
        }
    }
}

/**
 * The Subscriptions desk as the edition carries it: a boxed notice on the back page of one
 * edition a month, and only while a feed needs attention (see `DeskNoticeRule`).
 */
@Composable
fun DeskNotice(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .border(1.5.dp, colors.ink)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(stringResource(R.string.desk_notice_label), color = colors.ink, heading = true)
        Text(pluralStringResource(R.plurals.desk_notice_headline, count, count), style = type.headlineSmall, color = colors.ink)
        Text(stringResource(R.string.desk_notice_detail), style = type.body, color = colors.inkSecondary)
        TextLink(stringResource(R.string.desk_notice_open), onOpen)
    }
}

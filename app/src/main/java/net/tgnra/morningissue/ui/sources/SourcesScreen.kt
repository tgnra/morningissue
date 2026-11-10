package net.tgnra.morningissue.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.MenuItem
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.OverflowMenu
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TagChip
import net.tgnra.morningissue.ui.format.PostRate
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * The subscription list: each feed with its cadence tier and how often it posts. Feeds are
 * added by site or feed address; OPML import and export are in the overflow menu.
 */
@Composable
fun SourcesScreen(
    onOpenSource: (feedId: Long) -> Unit,
    onOpenSubscriptionsDesk: () -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val feeds by remember(container) { container.feeds.observeFeeds() }.collectAsStateWithLifecycle(initialValue = null)

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeIsError by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Feed?>(null) }
    val addFlow = rememberAddFeedFlow(syncAfterAdd = true)
    val opml = rememberOpmlActions(syncAfterImport = true) { message, isError ->
        notice = message
        noticeIsError = isError
    }

    PaperScreenFrame(
        title = stringResource(R.string.sources_title),
        onBack = onBack,
        actions = {
            PaperIconButton(Icons.Filled.Add, stringResource(R.string.sources_add), onClick = { adding = true })
            OverflowMenu(
                items = listOf(
                    MenuItem(stringResource(R.string.sources_import), opml.import),
                    MenuItem(stringResource(R.string.sources_export), opml.export),
                    MenuItem(stringResource(R.string.desk_title), onOpenSubscriptionsDesk),
                ),
                contentDescription = stringResource(R.string.action_more),
            )
        },
        overlay = {
            if (adding) {
                val close = {
                    adding = false
                    addFlow.reset()
                }
                PaperDialog(title = stringResource(R.string.sources_add), onDismiss = close) {
                    Text(
                        stringResource(R.string.add_feed_detail),
                        style = MorningIssueTheme.typography.body,
                        color = MorningIssueTheme.colors.inkSecondary,
                    )
                    AddFeedForm(addFlow)
                    DialogButtons { PaperButton(stringResource(R.string.action_done), close) }
                }
            }
            removing?.let { feed ->
                PaperDialog(title = stringResource(R.string.remove_feed_title, feed.title), onDismiss = { removing = null }) {
                    Text(
                        stringResource(R.string.remove_feed_detail),
                        style = MorningIssueTheme.typography.body,
                        color = MorningIssueTheme.colors.ink,
                    )
                    DialogButtons {
                        PaperButton(stringResource(R.string.action_cancel), { removing = null })
                        PaperButton(
                            text = stringResource(R.string.action_remove),
                            onClick = {
                                removing = null
                                scope.launch { container.feeds.removeFeed(feed.id) }
                            },
                            primary = true,
                        )
                    }
                }
            }
        },
    ) {
        notice?.let { NoticeLine(it, Modifier.padding(horizontal = margin, vertical = 10.dp), isError = noticeIsError) }
        val list = feeds
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(
                title = stringResource(R.string.sources_empty_title),
                detail = stringResource(R.string.sources_empty_detail),
            ) {
                Spacer(Modifier.height(6.dp))
                PaperButton(stringResource(R.string.sources_add), { adding = true }, icon = Icons.Filled.Add, primary = true)
                PaperButton(stringResource(R.string.sources_import), opml.import)
            }
            else -> PagedList(
                items = list,
                key = { it.id },
                header = {
                    SectionLabel(
                        pluralStringResource(R.plurals.sources_count, list.size, list.size),
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                },
            ) { feed ->
                FeedRow(
                    feed = feed,
                    onOpen = { onOpenSource(feed.id) },
                    onRemove = { removing = feed },
                )
            }
        }
    }
}

@Composable
private fun FeedRow(feed: Feed, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = MorningIssueTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = feed.title,
                style = MorningIssueTheme.typography.headlineSmall,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FeedCadence(feed)
        }
        OverflowMenu(
            items = listOf(
                MenuItem(stringResource(R.string.source_open), onOpen),
                MenuItem(stringResource(R.string.action_remove), onRemove),
            ),
            contentDescription = stringResource(R.string.source_options, feed.title),
        )
    }
}

@Composable
fun tierLabel(tier: CadenceTier): String = stringResource(
    when (tier) {
        CadenceTier.FIREHOSE -> R.string.tier_firehose
        CadenceTier.DAILY -> R.string.tier_daily
        CadenceTier.WEEKLY -> R.string.tier_weekly
        CadenceTier.RARE -> R.string.tier_rare
    },
)

@Composable
fun postRateLabel(rate: PostRate): String = when (rate) {
    is PostRate.PerWeek -> pluralStringResource(R.plurals.rate_per_week, rate.count, rate.count)
    is PostRate.PerMonth -> pluralStringResource(R.plurals.rate_per_month, rate.count, rate.count)
    PostRate.LessThanMonthly -> stringResource(R.string.rate_rare)
}

/** The tier chip and posting rate once cadence is known, then the feed's host. */
@Composable
fun FeedCadence(feed: Feed, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val rate = PostRate.of(feed.postsPerWeek)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A feed that has not been fetched enough has no cadence yet; its default tier would mislead.
        if (rate != null) TagChip(tierLabel(feed.tier))
        Text(
            text = listOfNotNull(rate?.let { postRateLabel(it) }, FeedUrls.hostOf(feed.siteUrl ?: feed.url)).joinToString(" · "),
            style = MorningIssueTheme.typography.caption,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

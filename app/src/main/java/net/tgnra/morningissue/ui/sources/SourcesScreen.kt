package net.tgnra.morningissue.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.AffinitySlider
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.FilterChip
import net.tgnra.morningissue.ui.components.MenuItem
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.OverflowMenu
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TagChip
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.format.PostRate
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.learning.SourceFilter
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * The subscription list. Each feed shows its cadence tier, how often it posts, its tags, a
 * mute if one is running, and the 5-step slider, which can be set right in the list. Chips
 * above the list narrow it to one tag (or to untagged or muted feeds). Everything else about a
 * feed (tags, mute, name, removal) is on its detail sheet, behind the pencil. Feeds are added
 * by site or feed address; OPML import and export are in the overflow menu.
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
    val feeds by retainedState("feeds") { container.feeds.observeFeeds() }

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var noticeIsError by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val addFlow = rememberAddFeedFlow(syncAfterAdd = true)
    val opml = rememberOpmlActions(syncAfterImport = true) { message, isError ->
        notice = message
        noticeIsError = isError
    }
    val now = container.clock.instant()

    Box(Modifier.fillMaxSize()) {
        PaperScreenFrame(
            title = stringResource(R.string.sources_title),
            onBack = onBack,
            // Under the detail sheet the list is not there for screen readers.
            modifier = if (editingId != null) Modifier.clearAndSetSemantics { } else Modifier,
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
                else -> {
                    val filter = SourceFilter.valid(SourceFilter.decode(filterKey), list, now)
                    PagedList(
                        items = list.filter { filter.matches(it, now) },
                        key = { it.id },
                        header = { SourcesHeader(list, filter, now, onFilter = { filterKey = it.encode() }) },
                    ) { feed ->
                        FeedRow(
                            feed = feed,
                            now = now,
                            onOpen = { onOpenSource(feed.id) },
                            onEdit = { editingId = feed.id },
                            onStepChange = { scope.launch { container.feeds.setSliderStep(feed.id, it) } },
                            onUnmute = { scope.launch { container.feeds.setMutedUntil(feed.id, null) } },
                        )
                    }
                }
            }
        }
        editingId?.let { id ->
            FeedDetail(
                feedId = id,
                onClose = { editingId = null },
                onOpenPosts = { onOpenSource(id) },
                onRemoved = { editingId = null },
            )
        }
    }
}

/** The count, the tag filter chips once there is something to filter by, and a line on the slider. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesHeader(feeds: List<Feed>, filter: SourceFilter, now: Instant, onFilter: (SourceFilter) -> Unit) {
    val colors = MorningIssueTheme.colors
    val tags = feeds.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val untagged = feeds.count { it.tags.isEmpty() }
    val muted = feeds.count { it.isMuted(now) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tags.isNotEmpty() || muted > 0) {
            val label = stringResource(R.string.sources_filter_label)
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                @Composable
                fun chip(target: SourceFilter, name: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
                    FilterChip("$name · $count", selected = filter == target, onClick = { onFilter(target) }, icon = icon)
                }
                chip(SourceFilter.All, stringResource(R.string.sources_filter_all), feeds.size)
                tags.forEach { tag -> chip(SourceFilter.Tagged(tag), tag, feeds.count { SourceFilter.Tagged(tag).matches(it, now) }) }
                if (untagged > 0 && tags.isNotEmpty()) chip(SourceFilter.Untagged, stringResource(R.string.sources_filter_untagged), untagged)
                if (muted > 0) chip(SourceFilter.Muted, stringResource(R.string.sources_filter_muted), muted, PaperIcons.Muted)
            }
        } else {
            SectionLabel(pluralStringResource(R.plurals.sources_count, feeds.size, feeds.size))
        }
        Text(stringResource(R.string.sources_slider_hint), style = MorningIssueTheme.typography.caption, color = colors.inkSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedRow(
    feed: Feed,
    now: Instant,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onStepChange: (SliderStep) -> Unit,
    onUnmute: () -> Unit,
) {
    val colors = MorningIssueTheme.colors
    val mutedUntil = Mutes.activeUntil(feed.muteUntil, now)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.detail_posts), role = Role.Button, onClick = onOpen)
                    .heightIn(min = 48.dp)
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = feed.title,
                    style = MorningIssueTheme.typography.headlineSmall,
                    color = if (mutedUntil != null) colors.inkSecondary else colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FeedCadence(feed)
                if (feed.tags.isNotEmpty()) SectionLabel(feed.tags.joinToString("  ·  "))
            }
            PaperIconButton(Icons.Filled.Edit, stringResource(R.string.source_edit, feed.title), onEdit, tint = colors.inkSecondary)
        }
        if (mutedUntil != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PaperIcons.Muted, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
                SectionLabel(
                    stringResource(R.string.source_muted_until, PaperFormat.shortDate(mutedUntil.atZone(ZoneId.systemDefault()).toLocalDate())),
                    color = colors.ink,
                )
                TextLink(stringResource(R.string.mute_unmute), onUnmute)
            }
        }
        AffinitySlider(
            step = feed.sliderStep,
            onStepChange = onStepChange,
            description = stringResource(R.string.slider_description, feed.title),
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

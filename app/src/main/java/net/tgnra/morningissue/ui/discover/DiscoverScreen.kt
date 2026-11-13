package net.tgnra.morningissue.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.tgnra.morningissue.R
import net.tgnra.morningissue.client.DeckState
import net.tgnra.morningissue.client.Decision
import net.tgnra.morningissue.client.Suggestion
import net.tgnra.morningissue.engine.CatalogueCluster
import net.tgnra.morningissue.engine.CatalogueHeadline
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.FilterChip
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.MenuItem
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.OverflowMenu
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TagChip
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.UndoLine
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.format.PostRate
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.sources.postRateLabel
import net.tgnra.morningissue.ui.sources.tierLabel
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Find sources: the week's suggestions from the bundled catalogue, one card at a time, each with
 * Pass and Subscribe buttons (no swiping or dragging; screen readers get both as actions on the
 * card). A cold start asks for topics first. Nothing animates, so E-ink needs nothing special.
 */
@Composable
fun DiscoverScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onOpenPassed: () -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val holder: DiscoverHolder = viewModel()
    val model = remember(holder, container) { holder.model(container) }
    val margin = LocalPaperLayoutSpec.current.pageMargin
    var explaining by rememberSaveable { mutableStateOf(false) }
    val deck = model.deck

    PaperScreenFrame(
        title = stringResource(R.string.discover_title),
        onBack = onBack,
        actions = {
            OverflowMenu(
                items = listOfNotNull(
                    MenuItem(stringResource(R.string.discover_browse_topics)) { model.browsingTopics = true }
                        .takeIf { deck != null && deck != DeckState.Unavailable },
                    MenuItem(stringResource(R.string.discover_passed_list), onOpenPassed),
                    MenuItem(stringResource(R.string.discover_how)) { explaining = true },
                ),
                contentDescription = stringResource(R.string.action_more),
            )
        },
        overlay = {
            if (explaining) {
                PaperDialog(title = stringResource(R.string.discover_how), onDismiss = { explaining = false }) {
                    Text(stringResource(R.string.discover_how_detail), style = MorningIssueTheme.typography.body, color = MorningIssueTheme.colors.ink)
                    DialogButtons { PaperButton(stringResource(R.string.action_done), { explaining = false }) }
                }
            }
        },
    ) {
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 16.dp, bottom = 32.dp)) {
            when {
                deck == null -> Unit
                deck == DeckState.Unavailable -> EmptyMessage(
                    stringResource(R.string.discover_unavailable_title),
                    stringResource(R.string.discover_unavailable_detail),
                )
                deck is DeckState.NeedsTopics -> TopicPicker(deck.topics, onPick = { model.load(it) })
                model.browsingTopics -> TopicPicker(
                    topics = (deck as? DeckState.Cards)?.topics ?: (deck as? DeckState.Finished)?.topics.orEmpty(),
                    onPick = { model.load(it) },
                    onCancel = { model.browsingTopics = false },
                )
                deck is DeckState.Cards -> {
                    val shown = model.shown(deck)
                    val current = shown.firstOrNull()
                    if (current == null) {
                        Finished(deck.total, onBrowse = { model.browsingTopics = true }, onOpenPassed = onOpenPassed)
                    } else {
                        val position = deck.total - deck.suggestions.size + 1
                        SuggestionCard(
                            suggestion = current,
                            position = position,
                            total = deck.total,
                            live = model.live[current.feed.url],
                            headlines = model.headlines(current),
                            onLoadLatest = { model.fetch(current.feed.url, force = true) },
                            onOpenHeadline = { model.open(current, it, onOpenArticle) },
                            onPass = { model.pass(current) },
                            onSubscribe = { model.subscribe(current) },
                        )
                    }
                }
                deck is DeckState.Finished -> Finished(deck.total, onBrowse = { model.browsingTopics = true }, onOpenPassed = onOpenPassed)
            }
            model.lastDecision?.let { decision ->
                LaunchedEffect(decision) { model.checkUndo() }
                val name = decision.feed.title
                val text = when (decision) {
                    is Decision.Passed -> stringResource(R.string.discover_passed_one, name)
                    is Decision.Subscribed -> stringResource(R.string.discover_subscribed_one, name)
                }
                val warning = if (model.undoLosesPosts) " " + stringResource(R.string.discover_undo_loses_posts) else ""
                UndoLine(
                    text = text + warning,
                    onUndo = model::undo,
                    modifier = Modifier.padding(top = 20.dp),
                    undoLabel = stringResource(R.string.discover_undo),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicPicker(topics: List<CatalogueCluster>, onPick: (Set<Int>) -> Unit, onCancel: (() -> Unit)? = null) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    var picked by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.discover_topics_title), style = type.leadHeadline, color = colors.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.discover_topics_detail), style = type.body, color = colors.inkSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.forEach { topic ->
                val selected = topic.id in picked
                FilterChip(topic.label, selected = selected, onClick = { picked = if (selected) picked - topic.id else picked + topic.id })
            }
        }
        Spacer(Modifier.height(4.dp))
        PaperButton(
            text = stringResource(R.string.discover_topics_show),
            onClick = { onPick(picked.toSet()) },
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = picked.isNotEmpty(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextLink(stringResource(R.string.discover_topics_skip), { onPick(emptySet()) })
            if (onCancel != null) TextLink(stringResource(R.string.action_cancel), onCancel)
        }
    }
}

@Composable
private fun Finished(total: Int, onBrowse: () -> Unit, onOpenPassed: () -> Unit) {
    EmptyMessage(
        title = stringResource(R.string.discover_finished_title),
        detail = stringResource(R.string.discover_finished_count, total) + " " + stringResource(R.string.discover_finished_detail),
    ) {
        Spacer(Modifier.height(6.dp))
        PaperButton(stringResource(R.string.discover_browse_topics), onBrowse, primary = true)
        PaperButton(stringResource(R.string.discover_passed_list), onOpenPassed)
    }
}

/**
 * One suggestion, set like a newspaper notice: the source's name, what it is and how often it
 * posts, why it is suggested, its latest headlines (each opens in the reader), then the two
 * decisions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    position: Int,
    total: Int,
    live: LiveState?,
    headlines: List<CatalogueHeadline>,
    onLoadLatest: () -> Unit,
    onOpenHeadline: (CatalogueHeadline) -> Unit,
    onPass: () -> Unit,
    onSubscribe: () -> Unit,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val feed = suggestion.feed
    val title = feed.title
    val passLabel = stringResource(R.string.discover_pass)
    val subscribeLabel = stringResource(R.string.discover_subscribe)
    Column(
        Modifier.fillMaxWidth().semantics {
            customActions = listOf(
                CustomAccessibilityAction(passLabel) { onPass(); true },
                CustomAccessibilityAction(subscribeLabel) { onSubscribe(); true },
            )
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(stringResource(R.string.discover_card_count, position, total))
            if (suggestion.card.exploration) SectionLabel(stringResource(R.string.discover_something_different), color = colors.accent)
        }
        HeavyRule()
        Text(title, style = type.leadHeadline, color = colors.ink, modifier = Modifier.semantics { heading() })
        val rate = PostRate.of(feed.postsPerWeek)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            feed.tier?.let { TagChip(tierLabel(it)) }
            Text(
                text = listOfNotNull(rate?.let { postRateLabel(it) }, feed.host ?: FeedUrls.hostOf(feed.siteUrl ?: feed.url)).joinToString(" · "),
                style = type.caption,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        feed.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = type.standfirst, color = colors.ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Text(suggestion.card.reason, style = type.body.copy(fontStyle = FontStyle.Italic), color = colors.inkSecondary)

        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(if (live is LiveState.Fetched) R.string.discover_latest else R.string.discover_catalogue_headlines), color = colors.ink)
        HairlineRule()
        if (headlines.isEmpty()) {
            Text(stringResource(R.string.discover_no_headlines), style = type.caption, color = colors.inkSecondary)
        }
        headlines.forEach { headline ->
            val label = stringResource(R.string.discover_open_headline, headline.title)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = label, role = Role.Button) { onOpenHeadline(headline) }
                    .padding(vertical = 8.dp),
            ) {
                Text(headline.title, style = type.headlineSmall, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    headline.publishedAt?.let { PaperFormat.articleDate(it) },
                    headline.wordCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.discover_words, it, it) },
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = type.byline, color = colors.inkSecondary)
            }
            HairlineRule()
        }
        when (live) {
            LiveState.Loading -> NoticeLine(stringResource(R.string.discover_loading))
            LiveState.Metered -> {
                NoticeLine(stringResource(R.string.discover_metered))
                TextLink(stringResource(R.string.discover_load_latest), onLoadLatest)
            }
            LiveState.Unreachable -> NoticeLine(stringResource(R.string.discover_unreachable))
            LiveState.Dead -> NoticeLine(stringResource(R.string.discover_dead), isError = true)
            is LiveState.Fetched, null -> Unit
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton(passLabel, onPass, modifier = Modifier.weight(1f), icon = Icons.Filled.Close)
            PaperButton(subscribeLabel, onSubscribe, modifier = Modifier.weight(1f), icon = Icons.Filled.Add, primary = true)
        }
    }
}

package net.tgnra.morningissue.ui.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.AffinitySlider
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.MuteControl
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TagToken
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.components.stepDescription
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.learning.TagInput
import net.tgnra.morningissue.ui.settings.SettingsSection
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

internal class LoadedFeed(val feed: Feed?)

/**
 * Everything that can be set on one source, on a sheet of its own so the Sources list stays a
 * list: the slider with every step named, tags, mute, its name, and removal. Drawn over the
 * screen that opened it (compose it last, over everything), and closed with back.
 *
 * [onOpenPosts] is null where the source's posts are already what is underneath. [onRemoved]
 * is called as the source is removed, in place of [onClose].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedDetail(
    feedId: Long,
    onClose: () -> Unit,
    onOpenPosts: (() -> Unit)?,
    onRemoved: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val uriHandler = LocalUriHandler.current
    val loaded by retainedState("detail-feed-$feedId") { container.feeds.observeFeed(feedId).map { LoadedFeed(it) } }
    val allTags by retainedState("tags") { container.feeds.observeTags() }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    val feed = loaded?.feed
    PaperScreenFrame(
        title = feed?.title ?: stringResource(R.string.source_edit_action),
        onBack = onClose,
        overlay = {
            if (confirmRemove && feed != null) {
                RemoveFeedDialog(
                    feed = feed,
                    onDismiss = { confirmRemove = false },
                    onConfirm = {
                        confirmRemove = false
                        // Leave first: there is nothing to edit once the feed is gone.
                        onRemoved()
                        container.appScope.launch { container.feeds.removeFeed(feed.id) }
                    },
                )
            }
        },
    ) {
        if (feed == null) return@PaperScreenFrame
        val now = container.clock.instant()
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            FeedCadence(feed, Modifier.padding(top = 16.dp))

            SettingsSection(stringResource(R.string.detail_how_much)) {
                AffinitySlider(
                    step = feed.sliderStep,
                    onStepChange = { scope.launch { container.feeds.setSliderStep(feed.id, it) } },
                    description = stringResource(R.string.slider_description, feed.title),
                    modifier = Modifier.padding(top = 8.dp),
                    showLabels = true,
                )
                Text(stepDescription(feed.sliderStep), style = type.body, color = colors.inkSecondary, modifier = Modifier.padding(top = 6.dp))
            }

            SettingsSection(stringResource(R.string.detail_tags)) {
                TagEditor(
                    tags = feed.tags,
                    known = allTags.orEmpty().map { it.name },
                    onChange = { scope.launch { container.feeds.setTags(feed.id, it) } },
                )
            }

            SettingsSection(stringResource(R.string.detail_mute)) {
                MuteControl(
                    muteUntil = feed.muteUntil,
                    now = now,
                    onMute = { days -> scope.launch { container.feeds.setMutedUntil(feed.id, Mutes.until(container.clock.instant(), days)) } },
                    onUnmute = { scope.launch { container.feeds.setMutedUntil(feed.id, null) } },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            SettingsSection(stringResource(R.string.detail_name)) {
                var name by rememberSaveable(feed.id, feed.title) { mutableStateOf(feed.title) }
                val changed = name.trim().isNotEmpty() && name.trim() != feed.title
                val rename: () -> Unit = {
                    if (changed) scope.launch { container.feeds.renameFeed(feed.id, name) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.detail_name),
                        modifier = Modifier.weight(1f),
                        onImeAction = { rename() },
                    )
                    PaperButton(stringResource(R.string.detail_rename), { rename() }, enabled = changed)
                }
            }

            FlowRow(
                Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onOpenPosts != null) PaperButton(stringResource(R.string.detail_posts), onOpenPosts, icon = PaperIcons.Newspaper)
                feed.siteUrl?.let { site ->
                    PaperButton(stringResource(R.string.source_open_site), { runCatching { uriHandler.openUri(site) } }, icon = PaperIcons.OpenInBrowser)
                }
            }

            SettingsSection(stringResource(R.string.detail_remove_section)) {
                Text(
                    stringResource(R.string.remove_feed_detail),
                    style = type.body,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
                PaperButton(stringResource(R.string.detail_remove), { confirmRemove = true })
            }
        }
    }
}

/**
 * A feed's tags as tokens: the ones it carries (press to take off), the reader's other tags
 * (press to put on), and a field for a new one. Each change is applied at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(tags: List<String>, known: List<String>, onChange: (List<String>) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    var typed by rememberSaveable { mutableStateOf("") }
    val add = {
        val next = TagInput.add(tags, typed, known)
        if (next != tags) onChange(next)
        typed = ""
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.detail_tags_hint), style = type.body, color = colors.inkSecondary)
        if (tags.isEmpty()) {
            Text(stringResource(R.string.detail_no_tags), style = type.body.copy(fontStyle = FontStyle.Italic), color = colors.ink)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    TagToken(
                        text = tag,
                        icon = Icons.Filled.Close,
                        filled = true,
                        actionLabel = stringResource(R.string.detail_tag_remove, tag),
                        onClick = { onChange(TagInput.remove(tags, tag)) },
                    )
                }
            }
        }
        val suggestions = TagInput.suggestions(known, tags, typed)
        if (suggestions.isNotEmpty()) {
            SectionLabel(stringResource(R.string.detail_add_existing))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { tag ->
                    TagToken(
                        text = tag,
                        icon = Icons.Filled.Add,
                        filled = false,
                        actionLabel = stringResource(R.string.detail_tag_add, tag),
                        onClick = {
                            onChange(TagInput.add(tags, tag, known))
                            typed = ""
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = stringResource(R.string.detail_new_tag),
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                onImeAction = { add() },
            )
            PaperButton(stringResource(R.string.action_add), { add() }, icon = Icons.Filled.Add, enabled = TagInput.parse(typed).isNotEmpty())
        }
    }
}

/** "Remove X?": shared by the Sources list's detail sheet and the source page. */
@Composable
fun RemoveFeedDialog(feed: Feed, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PaperDialog(title = stringResource(R.string.remove_feed_title, feed.title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.remove_feed_detail),
            style = MorningIssueTheme.typography.body,
            color = MorningIssueTheme.colors.ink,
        )
        DialogButtons {
            PaperButton(stringResource(R.string.action_cancel), onDismiss)
            PaperButton(stringResource(R.string.action_remove), onConfirm, primary = true)
        }
    }
}

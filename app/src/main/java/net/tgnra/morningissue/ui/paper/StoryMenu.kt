package net.tgnra.morningissue.ui.paper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.components.AffinityStepper
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.MuteControl
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperToggleButton
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.paperShadow
import net.tgnra.morningissue.ui.components.stepDescription
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import net.tgnra.morningissue.ui.learning.StoryReason
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** What the story menu can do; the screen supplies the repositories. */
class StoryMenuActions(
    val onSavedChange: (Boolean) -> Unit,
    val onLikedChange: (Boolean) -> Unit,
    val onStepChange: (SliderStep) -> Unit,
    val onMute: (days: Int) -> Unit,
    val onUnmute: () -> Unit,
    val onOpenSource: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * A story's long-press menu, as a slip of paper laid over the page: Save and Like, then "Why
 * is this here?" with Show less and Show more beside the answer, then Mute. It is drawn inside
 * the screen (no window, no animation) and stays open while its controls are used, so the
 * result of each (the feed's new step, the mute's end) can be read before it is put away.
 *
 * [feed] is null until the story's feed has been read; its controls appear when it has.
 * [sectionName] is the story's section when it sits on a section page.
 */
@Composable
fun StoryMenu(
    story: EditionStory,
    sectionName: String?,
    feed: Feed?,
    now: Instant,
    actions: StoryMenuActions,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val eink = colors.isEink
    val compact = LocalPaperLayoutSpec.current.widthClass == PaperWidthClass.Compact
    val title = stringResource(R.string.story_options)
    BackHandler(onBack = actions.onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // E-ink has no tints to dim with, so the slip stands alone on a blank page.
            .background(if (eink) colors.paper else Color.Black.copy(alpha = if (colors.isDark) 0.7f else 0.4f))
            .pointerInput(actions) { detectTapGestures { actions.onDismiss() } }
            // On a phone the slip runs to the bottom edge, under the gesture bar.
            .windowInsetsPadding(if (compact) WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) else WindowInsets.safeDrawing)
            .padding(horizontal = if (compact) 0.dp else 24.dp, vertical = if (compact) 0.dp else 24.dp)
            .padding(top = 24.dp),
        // On a phone it comes up from the bottom edge, under the thumb; elsewhere it sits mid-page.
        contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .paperShadow(radius = 24.dp, offsetY = 8.dp)
                .background(colors.paperRaised)
                .border(if (eink) 2.dp else 1.dp, if (eink) colors.ink else colors.hairline)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.rule))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    SectionLabel(story.feedTitle, color = colors.accent)
                    Text(
                        text = story.title,
                        style = type.headlineSmall,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                }
                PaperIconButton(Icons.Filled.Close, stringResource(R.string.action_close), actions.onDismiss)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (compact) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier)
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperToggleButton(
                        text = stringResource(if (story.isSaved) R.string.reader_saved else R.string.reader_save),
                        checked = story.isSaved,
                        onCheckedChange = actions.onSavedChange,
                        modifier = Modifier.weight(1f),
                        icon = if (story.isSaved) PaperIcons.Bookmark else PaperIcons.BookmarkBorder,
                    )
                    PaperToggleButton(
                        text = stringResource(if (story.isLiked) R.string.reader_liked else R.string.reader_like),
                        checked = story.isLiked,
                        onCheckedChange = actions.onLikedChange,
                        modifier = Modifier.weight(1f),
                        icon = if (story.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                }

                HairlineRule()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.story_why), color = colors.ink, heading = true)
                    Text(
                        text = story.reason?.takeIf { it.isNotBlank() }
                            ?: stringResource(StoryReason.fallback(story.kind), sectionName.orEmpty()),
                        style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                        color = colors.ink,
                    )
                }
                if (feed != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AffinityStepper(feed.sliderStep, actions.onStepChange)
                        Text(stepDescription(feed.sliderStep), style = type.caption, color = colors.inkSecondary)
                    }

                    HairlineRule()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(stringResource(R.string.mute_title, feed.title), color = colors.ink, heading = true)
                        MuteControl(feed.muteUntil, now, actions.onMute, actions.onUnmute)
                    }
                }

                HairlineRule()
                Column {
                    TextLink(stringResource(R.string.story_more_from, story.feedTitle), actions.onOpenSource)
                    Text(stringResource(R.string.story_menu_note), style = type.caption, color = colors.inkSecondary)
                }
            }
        }
    }
}

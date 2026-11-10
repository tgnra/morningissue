package net.tgnra.morningissue.ui.paper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.IndexGroup
import net.tgnra.morningissue.ui.components.ColumnRule
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.ImageTreatment
import net.tgnra.morningissue.ui.components.PaperImage
import net.tgnra.morningissue.ui.components.ReadTick
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * What a story block can do. Every block goes through [story], so the long-press menu of the
 * next milestone (Save, Like, Show more or less, Mute, Why is this here?) is added in one place.
 */
@Immutable
class StoryActions(
    val onOpenArticle: (articleId: Long) -> Unit,
    val onOpenSource: (feedId: Long) -> Unit,
)

val LocalStoryActions = staticCompositionLocalOf { StoryActions({}, {}) }

/** Makes a block open its story in the reader. */
@Composable
fun Modifier.story(story: EditionStory): Modifier {
    val actions = LocalStoryActions.current
    return clickable(role = Role.Button, onClick = { actions.onOpenArticle(story.articleId) })
}

/** "Daring Fireball · 12 min read", with the read tick in front once the story has been read. */
@Composable
fun Byline(story: EditionStory, modifier: Modifier = Modifier, extra: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (story.isRead) {
            ReadTick()
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = stringResource(R.string.byline_source_minutes, story.feedTitle, story.readMinutes) +
                extra?.let { " · $it" }.orEmpty(),
            style = MorningIssueTheme.typography.byline,
            color = MorningIssueTheme.colors.inkSecondary,
            // A dated byline may run to a second line rather than lose its date.
            maxLines = if (extra == null) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The front page's lead. With a photo: photo, headline, two-line standfirst. Without: the
 * headline grows, the standfirst runs to four lines and heavy rules above and below anchor
 * the page.
 */
@Composable
fun LeadStory(story: EditionStory, modifier: Modifier = Modifier, bylineExtra: String? = null) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val headlineColor = if (story.isRead) colors.inkSecondary else colors.ink
    Column(modifier.fillMaxWidth().stepAnchor().story(story)) {
        if (story.imageUrl != null) {
            // Full width on a tablet page is wide: a shallower crop keeps the headline on the first screen.
            val wide = LocalPaperLayoutSpec.current.let { it.widthClass != PaperWidthClass.Compact && it.leadColumnSpan == 0 }
            PaperImage(story.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 2f else 3f / 2f))
            Spacer(Modifier.height(12.dp))
            Text(story.title, style = type.leadHeadline, color = headlineColor)
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = type.standfirst, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Byline(story, extra = bylineExtra)
        } else {
            HeavyRule()
            Spacer(Modifier.height(14.dp))
            Text(
                text = story.title,
                style = type.leadHeadline.copy(
                    fontSize = type.leadHeadline.fontSize * 1.22f,
                    lineHeight = type.leadHeadline.lineHeight * 1.2f,
                ),
                color = headlineColor,
            )
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = type.standfirst, color = colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Byline(story, extra = bylineExtra)
            Spacer(Modifier.height(14.dp))
            HeavyRule()
        }
    }
}

/**
 * The first story of an inside page: a headline with its excerpt, and a small photo beside it
 * when there is one (dropped in E-ink).
 */
@Composable
fun PageLeadStory(story: EditionStory, modifier: Modifier = Modifier, bylineExtra: String? = null) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier.fillMaxWidth().stepAnchor().story(story).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(story.title, style = type.headline, color = if (story.isRead) colors.inkSecondary else colors.ink)
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = type.body, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Byline(story, extra = bylineExtra)
        }
        if (story.imageUrl != null && ImageTreatment.showThumbnails) {
            PaperImage(story.imageUrl, contentDescription = null, modifier = Modifier.width(112.dp).aspectRatio(1f))
        }
    }
}

/**
 * A grid cell: optional small photo, headline, byline. A story without a photo (or any story
 * in E-ink, where thumbnails are dropped) gets a few lines of its excerpt instead.
 */
@Composable
fun SecondaryStory(story: EditionStory, modifier: Modifier = Modifier, bylineExtra: String? = null) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val showImage = story.imageUrl != null && ImageTreatment.showThumbnails
    Column(modifier.story(story).padding(vertical = 12.dp)) {
        if (showImage) {
            PaperImage(story.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f))
            Spacer(Modifier.height(8.dp))
        }
        Text(story.title, style = type.headlineSmall, color = if (story.isRead) colors.inkSecondary else colors.ink)
        if (!showImage) {
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = type.body, color = colors.inkSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Byline(story, extra = bylineExtra)
    }
}

/**
 * Stories set in [columns] newspaper columns: column rules between cells, a hairline between
 * rows, no boxes.
 */
@Composable
fun StoryGrid(
    stories: List<EditionStory>,
    columns: Int,
    gutter: Dp,
    modifier: Modifier = Modifier,
    cell: @Composable (EditionStory, Modifier) -> Unit = { story, cellModifier -> SecondaryStory(story, cellModifier) },
) {
    Column(modifier.fillMaxWidth()) {
        stories.chunked(columns.coerceAtLeast(1)).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HairlineRule()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).stepAnchor()) {
                for (column in 0 until columns) {
                    if (column > 0) {
                        Box(Modifier.width(gutter).fillMaxHeight().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            ColumnRule()
                        }
                    }
                    val story = row.getOrNull(column)
                    if (story != null) cell(story, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One line of In brief: the headline with its source beneath. */
@Composable
fun BriefLine(story: EditionStory, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier.fillMaxWidth().stepAnchor().story(story).heightIn(min = 48.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(story.title, style = type.brief, color = if (story.isRead) colors.inkSecondary else colors.ink)
            Text(story.feedTitle, style = type.byline, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (story.isRead) ReadTick()
    }
}

/** A column of lines divided by hairlines, split over [columns] columns on wide pages. */
@Composable
fun <T> RuledColumns(
    items: List<T>,
    columns: Int,
    gutter: Dp,
    modifier: Modifier = Modifier,
    item: @Composable (T) -> Unit,
) {
    val count = columns.coerceIn(1, items.size.coerceAtLeast(1))
    val perColumn = (items.size + count - 1) / count
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        items.chunked(perColumn.coerceAtLeast(1)).forEachIndexed { index, chunk ->
            if (index > 0) {
                Box(Modifier.width(gutter * 1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) { ColumnRule() }
            }
            Column(Modifier.weight(1f)) {
                chunk.forEachIndexed { line, value ->
                    if (line > 0) HairlineRule()
                    item(value)
                }
            }
        }
    }
}

/** One source's leftovers on the back page: its name, then headlines only. */
@Composable
fun IndexGroupBlock(group: IndexGroup, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val actions = LocalStoryActions.current
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .stepAnchor()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { actions.onOpenSource(group.feedId) },
            contentAlignment = Alignment.BottomStart,
        ) {
            SectionLabel(group.feedTitle, color = colors.accent, modifier = Modifier.padding(bottom = 6.dp), heading = true)
        }
        HairlineRule()
        group.stories.forEach { story ->
            Row(
                Modifier.fillMaxWidth().stepAnchor().story(story).heightIn(min = 48.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = story.title,
                    style = type.body,
                    color = if (story.isRead) colors.inkSecondary else colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (story.isRead) ReadTick()
            }
        }
    }
}

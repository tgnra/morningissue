package net.tgnra.morningissue.ui.paper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.IndexGroup
import net.tgnra.morningissue.ui.components.ColumnRule
import net.tgnra.morningissue.ui.components.FramedImage
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.ImageTreatment
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperImage
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.StoryMarks
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * What a story block can do. Every block goes through [story], so opening, the long-press menu
 * and the accessibility actions are wired in one place.
 */
@Immutable
class StoryActions(
    val onOpenArticle: (articleId: Long) -> Unit,
    val onOpenSource: (feedId: Long) -> Unit,
    /** Opens the story's menu: Save, Like, Show more or less, Mute, Why is this here? */
    val onOpenMenu: (EditionStory) -> Unit = {},
    val onToggleSaved: (EditionStory) -> Unit = {},
    val onToggleLiked: (EditionStory) -> Unit = {},
    /** Subscribes to the story's source: a From your reading list story's feed is not subscribed. */
    val onSubscribe: (EditionStory) -> Unit = {},
)

val LocalStoryActions = staticCompositionLocalOf { StoryActions({}, {}) }

/**
 * Makes a block open its story in the reader, and its menu on a long press. Screen readers get
 * the menu, Save and Like as named actions on the story, so none of them depends on the gesture.
 */
@Composable
fun Modifier.story(story: EditionStory): Modifier {
    val actions = LocalStoryActions.current
    val menuLabel = stringResource(R.string.story_options)
    val saveLabel = stringResource(if (story.isSaved) R.string.story_unsave else R.string.reader_save)
    val likeLabel = stringResource(if (story.isLiked) R.string.story_unlike else R.string.reader_like)
    return this
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(saveLabel) {
                    actions.onToggleSaved(story)
                    true
                },
                CustomAccessibilityAction(likeLabel) {
                    actions.onToggleLiked(story)
                    true
                },
                CustomAccessibilityAction(menuLabel) {
                    actions.onOpenMenu(story)
                    true
                },
            )
        }
        .combinedClickable(
            role = Role.Button,
            onLongClickLabel = menuLabel,
            onLongClick = { actions.onOpenMenu(story) },
            onClick = { actions.onOpenArticle(story.articleId) },
        )
}

/**
 * The three dots at the end of a byline or line: the story's menu without a long press, for
 * anyone who does not know the gesture and for screens where holding a finger down is awkward.
 * Drawn small; the touch target is the usual 48dp. Hidden from screen readers, which reach the
 * same menu through the story's own actions.
 */
@Composable
fun StoryMoreButton(story: EditionStory, modifier: Modifier = Modifier) {
    val actions = LocalStoryActions.current
    Box(
        modifier
            .size(width = 28.dp, height = 24.dp)
            .clickable(role = Role.Button) { actions.onOpenMenu(story) }
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(PaperIcons.MoreHoriz, contentDescription = null, tint = MorningIssueTheme.colors.inkSecondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * "Daring Fireball · 12 min read", with the story's marks in front (read, Liked, Saved) and
 * its menu button at the end. With [sourceLink] the source's name opens its source page; that
 * is only asked for where there is room around the byline for a 48dp target.
 */
@Composable
fun Byline(story: EditionStory, modifier: Modifier = Modifier, extra: String? = null, sourceLink: Boolean = false) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val actions = LocalStoryActions.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StoryMarks(story.isRead, story.isLiked, story.isSaved)
        if (sourceLink) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = story.feedTitle,
                    style = type.byline.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (colors.isEink) TextDecoration.Underline else null,
                    ),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(onClickLabel = stringResource(R.string.story_more_from, story.feedTitle), role = Role.Button) {
                            actions.onOpenSource(story.feedId)
                        },
                )
                Text(
                    text = " · " + stringResource(R.string.minutes_read, story.readMinutes) + extra?.let { " · $it" }.orEmpty(),
                    style = type.byline,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // The reading time is unbreakable: in a narrow cell, where the menu button takes room,
            // it moves whole to a second line under the source's name.
            val minutes = stringResource(R.string.minutes_read, story.readMinutes)
            Text(
                text = stringResource(R.string.byline_source_minutes, story.feedTitle, story.readMinutes)
                    .replace(minutes, minutes.replace(' ', ' ')) +
                    extra?.let { " · $it" }.orEmpty(),
                style = type.byline,
                color = colors.inkSecondary,
                // A byline runs to a second line rather than lose its reading time or date.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        StoryMoreButton(story)
    }
}

/** The preferred shape of a lead photo: shallower on a wide page so the headline stays on the first screen. */
@Composable
private fun leadPhotoRatio(): Float {
    val spec = LocalPaperLayoutSpec.current
    return if (spec.widthClass != PaperWidthClass.Compact && spec.leadColumnSpan == 0) 2f else 3f / 2f
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
            // Framed, not blindly cropped: a strip or a tall picture keeps its shape.
            FramedImage(story.imageUrl, contentDescription = null, preferredRatio = leadPhotoRatio())
            Spacer(Modifier.height(12.dp))
            // A second place for an E-ink step to stop: under the picture, at the headline.
            Text(story.title, style = type.leadHeadline, color = headlineColor, modifier = Modifier.stepAnchor())
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = type.standfirst, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Byline(story, extra = bylineExtra, sourceLink = true)
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
            Byline(story, extra = bylineExtra, sourceLink = true)
            Spacer(Modifier.height(14.dp))
            HeavyRule()
        }
    }
}

/** Whether a compact [PageLeadStory] has room for its photo; a [StoryGrid] says no in a crowded row. */
val LocalSidePhotos = compositionLocalOf { true }

/**
 * The first story of an inside page: a headline with its excerpt, and a small photo beside it
 * when there is one (dropped in E-ink). [compact] sets it a size down, for a story that closes
 * a grid on a line of its own.
 */
@Composable
fun PageLeadStory(story: EditionStory, modifier: Modifier = Modifier, bylineExtra: String? = null, compact: Boolean = false) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier.fillMaxWidth().stepAnchor().story(story).padding(vertical = if (compact) 12.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = story.title,
                style = if (compact) type.headlineSmall else type.headline,
                color = if (story.isRead) colors.inkSecondary else colors.ink,
            )
            story.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                Text(
                    text = it,
                    style = type.body,
                    color = if (compact) colors.inkSecondary else colors.ink,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
            Byline(story, extra = bylineExtra, sourceLink = !compact)
        }
        if (story.imageUrl != null && ImageTreatment.showThumbnails && (!compact || LocalSidePhotos.current)) {
            PaperImage(story.imageUrl, contentDescription = null, modifier = Modifier.width(if (compact) 88.dp else 112.dp).aspectRatio(1f))
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
 * How a grid of [count] stories in [columns] columns is divided into rows: full rows, then
 * whatever is left. Each entry is the number of stories in that row.
 */
internal fun gridRows(count: Int, columns: Int): List<Int> {
    val perRow = columns.coerceAtLeast(1)
    if (count <= 0) return emptyList()
    val rest = count % perRow
    return List(count / perRow) { perRow } + if (rest > 0) listOf(rest) else emptyList()
}

/**
 * Stories set in [columns] newspaper columns: column rules between cells, a hairline between
 * rows, no boxes. A short last row is spread over the full width with its stories set by
 * [orphan] (headline, excerpt, small photo beside), so no column stands empty and no picture
 * grows to fill a wider cell. Three or more left over are set without the photo, for room.
 */
@Composable
fun StoryGrid(
    stories: List<EditionStory>,
    columns: Int,
    gutter: Dp,
    modifier: Modifier = Modifier,
    orphan: @Composable (EditionStory) -> Unit = { PageLeadStory(it, compact = true) },
    cell: @Composable (EditionStory, Modifier) -> Unit = { story, cellModifier -> SecondaryStory(story, cellModifier) },
) {
    Column(modifier.fillMaxWidth()) {
        var index = 0
        gridRows(stories.size, columns).forEachIndexed { rowIndex, size ->
            val row = stories.subList(index, index + size)
            index += size
            if (rowIndex > 0) HairlineRule()
            if (size == 1 && columns > 1) {
                orphan(row.single())
            } else {
                val spread = size < columns
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).stepAnchor()) {
                    for (column in 0 until size) {
                        if (column > 0) {
                            // A spread row's cells have padding of their own, so its rules need more room.
                            Box(
                                Modifier.width(if (spread) gutter * 2 else gutter).fillMaxHeight().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ColumnRule()
                            }
                        }
                        if (spread) {
                            // Spread over wider cells, a grid cell's picture would swell into a
                            // second lead; the stories of a short row are set like the orphan.
                            // Three or more beside one another are too narrow for a photo at
                            // the headline's side as well: they are set as text alone.
                            CompositionLocalProvider(LocalSidePhotos provides (size <= 2)) {
                                Box(Modifier.weight(1f).fillMaxHeight()) { orphan(row[column]) }
                            }
                        } else {
                            cell(row[column], Modifier.weight(1f).fillMaxHeight())
                        }
                    }
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
        StoryMarks(story.isRead, story.isLiked, story.isSaved)
        StoryMoreButton(story)
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
                StoryMarks(story.isRead, story.isLiked, story.isSaved)
                StoryMoreButton(story)
            }
        }
    }
}

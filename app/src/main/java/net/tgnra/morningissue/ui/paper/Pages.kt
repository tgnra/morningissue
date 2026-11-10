package net.tgnra.morningissue.ui.paper

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import net.tgnra.morningissue.ui.components.FramedImage
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.StoryMarks
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.ui.components.ColumnRule
import net.tgnra.morningissue.ui.components.DoubleRule
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** The name a page goes by in the contents list and its own heading. */
@Composable
fun pageTitle(page: EditionPage): String = when (page) {
    is EditionPage.Front -> stringResource(R.string.page_front)
    is EditionPage.TopStories -> stringResource(R.string.page_top_stories)
    is EditionPage.Section -> page.name
    is EditionPage.InBrief -> stringResource(R.string.page_in_brief)
    is EditionPage.FromTheArchive -> stringResource(R.string.page_archive)
    is EditionPage.WhileYouWereAway -> stringResource(R.string.page_away)
    is EditionPage.Back -> stringResource(R.string.page_back)
}

/** What the back page needs beyond the edition itself. */
class BackPageLinks(
    /** When the next edition is due if the edition does not say; null if unknown. */
    val fallbackNextEditionAt: Instant?,
    val onOpenBackIssues: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onBackToStand: () -> Unit,
)

/**
 * One page's content, laid out for the current `PaperLayoutSpec`. The caller provides the
 * vertical scroll and the page margins. Every [EditionPage] type is handled, so pages that
 * later milestones start printing (sections, While you were away) need nothing new here.
 */
@Composable
fun EditionPageContent(edition: Edition, page: EditionPage, links: BackPageLinks) {
    when (page) {
        is EditionPage.Front -> FrontPage(edition, page, links.onBackToStand)
        is EditionPage.TopStories -> StoriesPage(pageTitle(page), page.stories)
        is EditionPage.Section -> StoriesPage(
            title = pageTitle(page),
            stories = page.stories,
            heading = { SectionBanner(page.name, page.stories.size) },
        )
        is EditionPage.WhileYouWereAway -> StoriesPage(
            title = pageTitle(page),
            stories = page.stories,
            intro = stringResource(R.string.page_away_intro),
            datedBylines = true,
        )
        is EditionPage.InBrief -> BriefPage(page)
        is EditionPage.FromTheArchive -> ArchivePage(page)
        is EditionPage.Back -> BackPage(edition, page, links)
    }
}

// --- Front page ----------------------------------------------------------------------------

/**
 * The masthead: the paper's name set modestly so the stories get the space, then the date,
 * edition number and estimated reading time between rules. Tapping the name returns to the stand.
 */
@Composable
fun Masthead(edition: Edition, onBackToStand: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            style = type.masthead,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.paper_back_to_stand), onClick = onBackToStand)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { heading() },
        )
        DoubleRule()
        Text(
            text = listOf(
                PaperFormat.longDate(edition.date),
                stringResource(R.string.edition_number, edition.number),
                stringResource(R.string.minutes_read, edition.readMinutes),
            ).joinToString("  ·  ").uppercase(),
            style = type.sectionLabel.copy(letterSpacing = 0.08.em),
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 7.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
    }
}

/**
 * Columns for the front page's secondaries: the layout's own count, or one more when that
 * sets them in a single full row (four stories across a small tablet rather than three and one).
 */
internal fun frontGridColumns(count: Int, spec: PaperLayoutSpec): Int = when {
    spec.leadColumnSpan > 0 -> (spec.columns - spec.leadColumnSpan).coerceAtLeast(1)
    spec.columns > 2 && count == spec.columns + 1 -> count
    else -> spec.columns
}

@Composable
private fun FrontPage(edition: Edition, page: EditionPage.Front, onBackToStand: () -> Unit) {
    val spec = LocalPaperLayoutSpec.current
    Masthead(edition, onBackToStand)
    Spacer(Modifier.height(16.dp))
    val lead = page.lead
    val columns = frontGridColumns(page.secondaries.size, spec)
    if (lead != null && spec.leadColumnSpan > 0 && page.secondaries.isNotEmpty()) {
        // Tablet landscape: the lead spans its columns with the secondaries set beside it.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            LeadStory(lead, Modifier.weight(spec.leadColumnSpan.toFloat()))
            Box(Modifier.width(spec.gutter * 2).fillMaxHeight(), contentAlignment = Alignment.Center) { ColumnRule() }
            StoryGrid(page.secondaries, columns, spec.gutter, Modifier.weight((spec.columns - spec.leadColumnSpan).toFloat()))
        }
    } else {
        if (lead != null) {
            LeadStory(lead)
            Spacer(Modifier.height(if (lead.imageUrl != null) 14.dp else 2.dp))
            if (lead.imageUrl != null && page.secondaries.isNotEmpty()) HairlineRule()
        }
        StoryGrid(page.secondaries, columns, spec.gutter)
    }
    if (lead == null && page.secondaries.isEmpty()) EmptyPageNote()
}

// --- Inside pages --------------------------------------------------------------------------

/** A page's banner: its name in spaced capitals between a heavy rule and a hairline. */
@Composable
fun PageHeading(title: String, modifier: Modifier = Modifier, intro: String? = null) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(modifier.fillMaxWidth().stepAnchor(), horizontalAlignment = Alignment.CenterHorizontally) {
        HeavyRule(thickness = 3.dp)
        Text(
            text = title.uppercase(),
            style = type.headlineSmall.copy(letterSpacing = 0.16.em),
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = if (intro == null) 10.dp else 4.dp).semantics { heading() },
        )
        if (intro != null) {
            Text(
                text = intro,
                style = type.body.copy(fontStyle = FontStyle.Italic),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
    }
}

/**
 * A section page's banner: the tag's name in spaced capitals under the masthead's double rule,
 * with a line of furniture saying what the page is. Heavier than [PageHeading], so the turn
 * from the mixed pages into the reader's own sections is felt.
 */
@Composable
fun SectionBanner(name: String, storyCount: Int, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(modifier.fillMaxWidth().stepAnchor(), horizontalAlignment = Alignment.CenterHorizontally) {
        DoubleRule()
        Text(
            text = name.uppercase(),
            style = type.headline.copy(letterSpacing = 0.2.em),
            color = colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp).semantics { heading() },
        )
        Text(
            text = (stringResource(R.string.contents_section) + "  ·  " + pluralStringResource(R.plurals.story_count, storyCount, storyCount)).uppercase(),
            style = type.sectionLabel,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
    }
}

/**
 * From the archive: one held-over story set as a feature, centred at a book measure with air
 * around it. The source leads as a kicker (and opens its source page), then the headline, a
 * short rule, the standfirst at length, the picture, and when it was first published.
 */
@Composable
fun ArchiveFeature(story: EditionStory, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val actions = LocalStoryActions.current
    val wide = LocalPaperLayoutSpec.current.widthClass != PaperWidthClass.Compact
    Column(
        modifier.widthIn(max = 640.dp).fillMaxWidth().stepAnchor().story(story).padding(top = 14.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.story_more_from, story.feedTitle), role = Role.Button) {
                    actions.onOpenSource(story.feedId)
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            SectionLabel(story.feedTitle, color = colors.accent)
        }
        Text(
            text = story.title,
            style = type.leadHeadline.copy(
                fontSize = type.leadHeadline.fontSize * 1.12f,
                lineHeight = type.leadHeadline.lineHeight * 1.12f,
            ),
            color = if (story.isRead) colors.inkSecondary else colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(56.dp).height(2.dp).background(colors.rule))
        story.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = it,
                style = type.standfirst.copy(fontStyle = FontStyle.Italic),
                color = colors.ink,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (story.imageUrl != null) {
            Spacer(Modifier.height(22.dp))
            FramedImage(story.imageUrl, contentDescription = null, preferredRatio = if (wide) 2f else 3f / 2f)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Balances the menu button, so the dateline itself sits on the centre line.
            Spacer(Modifier.width(28.dp))
            StoryMarks(story.isRead, story.isLiked, story.isSaved)
            Text(
                text = listOfNotNull(
                    story.author?.takeIf { it.isNotBlank() },
                    stringResource(R.string.archive_first_published, PaperFormat.articleDate(story.publishedAt)),
                    // Unbreakable, so a narrow page does not leave "read" on a line of its own.
                    stringResource(R.string.minutes_read, story.readMinutes).replace(' ', '\u00A0'),
                ).joinToString(" · "),
                style = type.byline,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            StoryMoreButton(story)
        }
    }
}

@Composable
private fun EmptyPageNote() {
    Text(
        text = stringResource(R.string.page_empty),
        style = MorningIssueTheme.typography.standfirst.copy(fontStyle = FontStyle.Italic),
        color = MorningIssueTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

/** Top stories, a section, or the While you were away pull-out: one page lead, then the grid. */
@Composable
private fun StoriesPage(
    title: String,
    stories: List<EditionStory>,
    intro: String? = null,
    datedBylines: Boolean = false,
    heading: @Composable () -> Unit = { PageHeading(title, intro = intro) },
) {
    val spec = LocalPaperLayoutSpec.current
    val extra: (EditionStory) -> String? = { if (datedBylines) PaperFormat.articleDate(it.publishedAt) else null }
    val orphan: @Composable (EditionStory) -> Unit = { PageLeadStory(it, bylineExtra = extra(it), compact = true) }
    heading()
    if (stories.isEmpty()) {
        EmptyPageNote()
        return
    }
    val first = stories.first()
    val rest = stories.drop(1)
    if (spec.leadColumnSpan > 0 && rest.size >= 2) {
        // Wide pages: the page lead takes the lead's columns with the first row beside it.
        val beside = (spec.columns - spec.leadColumnSpan).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            PageLeadStory(first, Modifier.weight(spec.leadColumnSpan.toFloat()), bylineExtra = extra(first))
            Box(Modifier.width(spec.gutter).fillMaxHeight().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { ColumnRule() }
            StoryGrid(rest.take(beside), beside, spec.gutter, Modifier.weight(beside.toFloat())) { story, cell ->
                SecondaryStory(story, cell, bylineExtra = extra(story))
            }
        }
        if (rest.size > beside) HairlineRule()
        StoryGrid(rest.drop(beside), spec.columns, spec.gutter, orphan = orphan) { story, cell -> SecondaryStory(story, cell, bylineExtra = extra(story)) }
    } else {
        PageLeadStory(first, bylineExtra = extra(first))
        if (rest.isNotEmpty()) HairlineRule()
        StoryGrid(rest, spec.columns, spec.gutter, orphan = orphan) { story, cell -> SecondaryStory(story, cell, bylineExtra = extra(story)) }
    }
}

@Composable
private fun BriefPage(page: EditionPage.InBrief) {
    val spec = LocalPaperLayoutSpec.current
    PageHeading(pageTitle(page))
    if (page.stories.isEmpty()) {
        EmptyPageNote()
        return
    }
    RuledColumns(page.stories, columns = (spec.columns + 1) / 2, gutter = spec.gutter) { BriefLine(it) }
}

@Composable
private fun ArchivePage(page: EditionPage.FromTheArchive) {
    PageHeading(pageTitle(page), intro = stringResource(R.string.page_archive_intro))
    if (page.stories.isEmpty()) {
        EmptyPageNote()
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ArchiveFeature(page.stories.first())
        // The builder prints one; any more are set plainly beneath it.
        page.stories.drop(1).forEach { story ->
            HairlineRule(Modifier.widthIn(max = 640.dp))
            PageLeadStory(
                story = story,
                modifier = Modifier.widthIn(max = 640.dp),
                bylineExtra = stringResource(R.string.archive_first_published, PaperFormat.articleDate(story.publishedAt)),
            )
        }
    }
}

// --- Back page -----------------------------------------------------------------------------

@Composable
private fun BackPage(edition: Edition, page: EditionPage.Back, links: BackPageLinks) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val spec = LocalPaperLayoutSpec.current
    val isToday = edition.date == LocalDate.now()
    val nextAt = page.nextEditionAt ?: links.fallbackNextEditionAt
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)

    Column(
        Modifier.fillMaxWidth().stepAnchor().padding(top = 40.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(56.dp).height(3.dp).background(colors.rule))
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (isToday) {
                stringResource(R.string.back_thats_today)
            } else {
                stringResource(R.string.back_thats_edition, PaperFormat.longDate(edition.date))
            },
            style = type.leadHeadline.copy(fontStyle = FontStyle.Italic),
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (nextAt != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.back_next_edition,
                    PaperFormat.time(nextAt.atZone(ZoneId.systemDefault()).toLocalTime(), use24Hour),
                ),
                style = type.standfirst,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton(stringResource(R.string.back_back_issues), links.onOpenBackIssues, icon = PaperIcons.Calendar)
            PaperButton(stringResource(R.string.back_search), links.onOpenSearch, icon = Icons.Filled.Search)
        }
    }

    if (page.alsoInYourFeeds.isNotEmpty()) {
        val headlines = page.alsoInYourFeeds.sumOf { it.stories.size }
        PageHeading(
            title = stringResource(R.string.back_also_in_feeds),
            intro = pluralStringResource(R.plurals.back_index_intro, headlines, headlines),
        )
        Spacer(Modifier.height(4.dp))
        val columns = ((spec.columns + 1) / 2).coerceAtMost(page.alsoInYourFeeds.size)
        // Sources are dealt across the columns in order, so each column stays a readable list.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spec.gutter * 1.5f)) {
            for (column in 0 until columns) {
                Column(Modifier.weight(1f)) {
                    page.alsoInYourFeeds.filterIndexed { index, _ -> index % columns == column }.forEach { IndexGroupBlock(it) }
                }
            }
        }
    }
}

package net.tgnra.morningissue.ui.newsstand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.LocalDate
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.ui.components.editionNumberLabel
import net.tgnra.morningissue.ui.components.PaperImage
import net.tgnra.morningissue.ui.components.paperShadow
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** Width over height of a cover: a tabloid page. */
const val COVER_ASPECT = 0.72f

/**
 * An edition's front as it stands on the rack: masthead, dateline, lead headline, lead photo
 * and greeked columns. It is drawn, not a screenshot, so it follows the theme and scales to any
 * width: every measure inside is a fraction of the cover's width, type included, which is why
 * it ignores the system font scale (the cover is a picture; its words are in its description).
 *
 * The caller sets the width; the height follows from [COVER_ASPECT].
 */
@Composable
fun EditionCover(
    edition: EditionSummary,
    modifier: Modifier = Modifier,
) {
    val description = coverDescription(edition)
    CoverSheet(modifier.clearAndSetSemantics { contentDescription = description }) { unit ->
        val colors = MorningIssueTheme.colors
        val type = MorningIssueTheme.typography
        val hasImage = edition.leadImageUrl != null &&
            // A photo this small is noise on e-ink; the headline gets the room instead.
            !(MorningIssueTheme.isEink && unit < 1.6.dp)

        CoverMasthead(unit)
        Row(
            Modifier.fillMaxWidth().padding(vertical = unit * 1.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val dateline = type.sectionLabel.sized(unit * 2.7f).copy(letterSpacing = 0.08.em)
            Text(PaperFormat.longDate(edition.date).uppercase(), style = dateline, color = colors.inkSecondary, maxLines = 1)
            Text(editionNumberLabel(edition.kind, edition.number).uppercase(), style = dateline, color = colors.inkSecondary, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(hairline(unit)).background(colors.rule))
        Spacer(Modifier.height(unit * 3.5f))

        if (hasImage) {
            PaperImage(
                url = edition.leadImageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            )
            Spacer(Modifier.height(unit * 3f))
        }
        edition.leadHeadline?.let { headline ->
            Text(
                text = headline,
                style = type.leadHeadline.sized(unit * if (hasImage) 7.4f else 9.6f),
                color = colors.ink,
                maxLines = if (hasImage) 3 else 5,
                overflow = TextOverflow.Ellipsis,
            )
            edition.leadSource?.let { source ->
                Spacer(Modifier.height(unit * 1.8f))
                Text(
                    text = source.uppercase(),
                    style = type.sectionLabel.sized(unit * 2.6f),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(unit * 3f))
        }
        GreekedColumns(seed = edition.id, unit = unit, modifier = Modifier.fillMaxWidth().weight(1f))
    }
    // The tick is outside the sheet's semantics; the description already says "read".
}

/** [EditionCover] with the read tick laid over its corner. */
@Composable
fun EditionCoverWithTick(edition: EditionSummary, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        EditionCover(edition, Modifier.fillMaxWidth())
        if (edition.isRead) {
            val colors = MorningIssueTheme.colors
            val size = (maxWidth * 0.13f).coerceIn(14.dp, 34.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(maxWidth * 0.05f)
                    .size(size)
                    .background(if (colors.isEink) colors.paper else colors.accent, CircleShape)
                    .border(if (colors.isEink) 1.5.dp else 0.dp, if (colors.isEink) colors.ink else colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (colors.isEink) colors.ink else colors.onAccent,
                    modifier = Modifier.size(size * 0.66f),
                )
            }
        }
    }
}

/** A cover-shaped sheet with only the masthead and a message, for the stand before any edition exists. */
@Composable
fun BlankCover(message: String, modifier: Modifier = Modifier) {
    CoverSheet(modifier) { unit ->
        val colors = MorningIssueTheme.colors
        CoverMasthead(unit)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = MorningIssueTheme.typography.standfirst.sized(unit * 6f).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.fillMaxWidth().height(hairline(unit)).background(colors.rule))
    }
}

/**
 * The cover of an edition that is due and has not been printed: the masthead and the dateline
 * over a page with nothing set on it, only the dashed outlines where the lead photo and the
 * two columns would go. It stands in today's place on the rack; the words and the Print now
 * button are beneath it, not on it (see `UnprintedDisplay`), because the cover is a picture
 * that scales with its width.
 */
@Composable
fun UnprintedCover(date: LocalDate, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.cover_description_unprinted, PaperFormat.longDate(date))
    CoverSheet(modifier.clearAndSetSemantics { contentDescription = description }) { unit ->
        val colors = MorningIssueTheme.colors
        val type = MorningIssueTheme.typography
        CoverMasthead(unit)
        Row(
            Modifier.fillMaxWidth().padding(vertical = unit * 1.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val dateline = type.sectionLabel.sized(unit * 2.7f).copy(letterSpacing = 0.08.em)
            Text(PaperFormat.longDate(date).uppercase(), style = dateline, color = colors.inkSecondary, maxLines = 1)
            Text(stringResource(R.string.cover_unprinted).uppercase(), style = dateline, color = colors.inkSecondary, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(hairline(unit)).background(colors.rule))
        Spacer(Modifier.height(unit * 3.5f))
        UnsetPage(unit, Modifier.fillMaxWidth().weight(1f))
    }
}

/** The forme with no type in it: dashed outlines for the lead photo and the two columns. */
@Composable
private fun UnsetPage(unit: Dp, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val outline = if (colors.isEink) colors.ink else colors.inkSecondary.copy(alpha = 0.45f)
    Canvas(modifier) {
        val u = unit.toPx()
        val stroke = Stroke(width = (u * 0.3f).coerceAtLeast(1f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 1.6f, u * 1.2f)))
        val gutter = u * 5f
        val columnWidth = (size.width - gutter) / 2f
        val photoHeight = (size.width * 10f / 16f).coerceAtMost(size.height * 0.5f)
        drawRect(outline, Offset.Zero, Size(size.width, photoHeight), style = stroke)
        val top = photoHeight + u * 3f
        if (size.height > top) {
            for (column in 0..1) {
                drawRect(outline, Offset(column * (columnWidth + gutter), top), Size(columnWidth, size.height - top), style = stroke)
            }
        }
    }
}

@Composable
private fun CoverSheet(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(unit: Dp) -> Unit,
) {
    val colors = MorningIssueTheme.colors
    BoxWithConstraints(
        modifier
            .aspectRatio(COVER_ASPECT)
            .paperShadow(radius = 8.dp, offsetY = 3.dp)
            .background(colors.paperRaised)
            .border(1.dp, if (colors.isEink) colors.ink else colors.hairline),
    ) {
        val unit = maxWidth / 100f
        Column(Modifier.fillMaxSize().padding(horizontal = unit * 6f, vertical = unit * 5f)) {
            content(unit)
        }
    }
}

@Composable
private fun CoverMasthead(unit: Dp) {
    val colors = MorningIssueTheme.colors
    Text(
        text = stringResource(R.string.app_name),
        style = MorningIssueTheme.typography.masthead.sized(unit * 10.5f),
        color = colors.ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(unit * 1.6f))
    Box(Modifier.fillMaxWidth().height((unit * 0.8f).coerceAtLeast(1.dp)).background(colors.rule))
    Spacer(Modifier.height((unit * 0.5f).coerceAtLeast(1.dp)))
    Box(Modifier.fillMaxWidth().height(hairline(unit)).background(colors.rule))
}

/** Two columns of stand-in text lines, the same for a given [seed] every time. */
@Composable
private fun GreekedColumns(seed: Long, unit: Dp, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val eink = colors.isEink
    val lineColor = if (eink) colors.ink else colors.inkSecondary.copy(alpha = 0.3f)
    val ruleColor = colors.hairline
    Canvas(modifier) {
        val u = unit.toPx()
        val gutter = u * 5f
        val columnWidth = (size.width - gutter) / 2f
        val pitch = u * 2.5f
        val thickness = if (eink) (u * 0.35f).coerceAtLeast(1f) else u * 1.05f
        val lines = (size.height / pitch).toInt()
        var state = seed * 6364136223846793005L + 1442695040888963407L
        for (column in 0..1) {
            val left = column * (columnWidth + gutter)
            var untilBreak = 3 + ((state ushr 33) % 4).toInt()
            for (line in 0 until lines) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val random = ((state ushr 40) % 1000).toInt() / 1000f
                val isLast = untilBreak == 0
                val width = columnWidth * if (isLast) 0.35f + random * 0.4f else 0.93f + random * 0.07f
                drawRect(lineColor, Offset(left, line * pitch), Size(width, thickness))
                untilBreak = if (isLast) 3 + (random * 5).toInt() else untilBreak - 1
            }
        }
        drawRect(ruleColor, Offset(columnWidth + gutter / 2f, 0f), Size(1f, lines * pitch - (pitch - thickness)))
    }
}

/** Sets a style to an exact size in [Dp], ignoring the system font scale. */
@Composable
private fun TextStyle.sized(size: Dp): TextStyle {
    val sp = with(LocalDensity.current) { size.toSp() }
    val ratio = if (fontSize.value > 0f) lineHeight.value / fontSize.value else 1.2f
    return copy(fontSize = sp, lineHeight = sp * ratio)
}

private fun hairline(unit: Dp): Dp = (unit * 0.3f).coerceAtLeast(1.dp)

@Composable
private fun coverDescription(edition: EditionSummary): String = buildString {
    val description = when (edition.kind) {
        EditionKind.MORNING -> R.string.cover_description
        EditionKind.EVENING -> R.string.cover_description_evening
        EditionKind.EXTRA -> R.string.cover_description_extra
    }
    append(stringResource(description, edition.number, PaperFormat.longDate(edition.date)))
    edition.leadHeadline?.let { append(". ").append(stringResource(R.string.cover_lead, it)) }
    if (edition.isRead) append(". ").append(stringResource(R.string.read_tick))
}

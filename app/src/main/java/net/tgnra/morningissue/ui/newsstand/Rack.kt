package net.tgnra.morningissue.ui.newsstand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.boxOutlineColor
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

private val BoardTop = 8.dp
private val BoardEdge = 4.dp
private val BoardShadow = 6.dp

/**
 * A shelf board seen slightly from above: its surface, its front edge and the shade beneath,
 * all from flat theme colours. In E-ink it is a single ruled line.
 */
@Composable
fun ShelfBoard(modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    if (colors.isEink) {
        Box(modifier.fillMaxWidth().height(2.dp).background(colors.ink))
        return
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(BoardTop).background(colors.shelf))
        Box(Modifier.fillMaxWidth().height(BoardEdge).background(colors.shelfEdge))
        Box(
            Modifier
                .fillMaxWidth()
                .height(BoardShadow)
                .background(Brush.verticalGradient(listOf(colors.shadow.copy(alpha = colors.shadow.alpha * 0.6f), Color.Transparent))),
        )
    }
}

/** The full height [ShelfBoard] takes in the current theme. */
private val shelfBoardHeight: Dp
    @Composable get() = if (MorningIssueTheme.isEink) 2.dp else BoardTop + BoardEdge + BoardShadow

/** A cover that opens its edition. */
@Composable
fun CoverButton(edition: EditionSummary, onOpenEdition: (Long) -> Unit, modifier: Modifier = Modifier) {
    EditionCoverWithTick(
        edition = edition,
        modifier = modifier.clickable(
            onClickLabel = stringResource(R.string.cover_open),
            role = Role.Button,
            onClick = { onOpenEdition(edition.id) },
        ),
    )
}

@Composable
private fun CoverCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MorningIssueTheme.typography.caption,
        color = MorningIssueTheme.colors.inkSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The tile at the end of the rack that opens the back-issues calendar. */
@Composable
private fun OlderIssuesTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    Column(
        modifier = modifier
            .aspectRatio(COVER_ASPECT)
            .background(if (colors.isEink) colors.paper else colors.shelf.copy(alpha = 0.55f))
            .border(1.dp, boxOutlineColor)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(PaperIcons.Calendar, contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(R.string.newsstand_older_issues),
            style = MorningIssueTheme.typography.label,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The phone's single shelf: covers overlap slightly, newest on top, and slide sideways along a
 * board that stays put.
 */
@Composable
fun ScrollingShelf(
    editions: List<EditionSummary>,
    margin: Dp,
    onOpenEdition: (Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    coverWidth: Dp = 104.dp,
) {
    val coverHeight = coverWidth / COVER_ASPECT
    val boardHeight = shelfBoardHeight
    Box(Modifier.fillMaxWidth()) {
        ShelfBoard(Modifier.padding(top = coverHeight))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = margin),
            // The overlap stays inside a cover's own margin, so no type is hidden.
            horizontalArrangement = Arrangement.spacedBy((-5).dp),
        ) {
            editions.forEachIndexed { index, edition ->
                Column(
                    Modifier.width(coverWidth).zIndex((editions.size - index).toFloat()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverButton(edition, onOpenEdition, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(boardHeight + 2.dp))
                    CoverCaption(PaperFormat.shortDate(edition.date))
                }
            }
            Spacer(Modifier.width(22.dp))
            OlderIssuesTile(onOpenBackIssues, Modifier.width(coverWidth))
        }
    }
}

/** Shelves that wrap: as many covers per board as fit, the Older issues tile last. */
@Composable
fun ShelfGrid(
    editions: List<EditionSummary>,
    onOpenEdition: (Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    targetCoverWidth: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 16.dp
        val inset = 8.dp
        val usable = maxWidth - inset * 2
        val perRow = ((usable + gap) / (targetCoverWidth + gap)).toInt().coerceIn(2, 8)
        val coverWidth = ((usable - gap * (perRow - 1)) / perRow).coerceAtMost(targetCoverWidth * 1.3f)
        // Null stands for the Older issues tile.
        val items: List<EditionSummary?> = editions + listOf(null)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(perRow).forEach { shelf ->
                Column(Modifier.stepAnchor()) {
                    Row(
                        Modifier.padding(horizontal = inset),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        shelf.forEach { edition ->
                            if (edition != null) {
                                CoverButton(edition, onOpenEdition, Modifier.width(coverWidth))
                            } else {
                                OlderIssuesTile(onOpenBackIssues, Modifier.width(coverWidth))
                            }
                        }
                    }
                    ShelfBoard()
                    Row(Modifier.padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        shelf.forEach { edition ->
                            CoverCaption(edition?.let { PaperFormat.shortDate(it.date) }.orEmpty(), Modifier.width(coverWidth))
                        }
                    }
                }
            }
        }
    }
}

// --- The button bar ------------------------------------------------------------------------

class StandButton(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The plain labelled bar along the bottom of the newsstand on phones and small tablets. */
@Composable
fun StandButtonBar(buttons: List<StandButton>, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val eink = colors.isEink
    Column(modifier.fillMaxWidth().background(colors.paper)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = if (eink) 12.dp else 4.dp, vertical = if (eink) 8.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (eink) 8.dp else 0.dp),
        ) {
            buttons.forEach { StandButtonItem(it, Modifier.weight(1f)) }
        }
    }
}

/** The same buttons as a rail down the left edge, for tablets in landscape. */
@Composable
fun StandButtonRail(buttons: List<StandButton>, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    Row(modifier.fillMaxHeight().background(colors.paper)) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(104.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            buttons.forEach { StandButtonItem(it, Modifier.fillMaxWidth()) }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.rule))
    }
}

@Composable
private fun StandButtonItem(button: StandButton, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = 60.dp)
            // E-ink has no press feedback, so each button is a drawn box.
            .then(if (colors.isEink) Modifier.border(1.5.dp, colors.ink) else Modifier)
            .clickable(role = Role.Button, onClick = button.onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(button.icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
        Text(
            text = button.label,
            style = MorningIssueTheme.typography.byline,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

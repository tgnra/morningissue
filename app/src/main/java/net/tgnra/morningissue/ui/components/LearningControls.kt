package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.learning.SliderSteps
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

// --- The slider ----------------------------------------------------------------------------

@Composable
fun stepLabel(step: SliderStep): String = stringResource(
    when (step) {
        SliderStep.RARELY -> R.string.slider_rarely
        SliderStep.LESS -> R.string.slider_less
        SliderStep.AUTO -> R.string.slider_auto
        SliderStep.MORE -> R.string.slider_more
        SliderStep.FAVOURITE -> R.string.slider_favourite
    },
)

/** One sentence on what a step does. */
@Composable
fun stepDescription(step: SliderStep): String = stringResource(
    when (step) {
        SliderStep.RARELY -> R.string.slider_rarely_detail
        SliderStep.LESS -> R.string.slider_less_detail
        SliderStep.AUTO -> R.string.slider_auto_detail
        SliderStep.MORE -> R.string.slider_more_detail
        SliderStep.FAVOURITE -> R.string.slider_favourite_detail
    },
)

private val SliderTrackHeight = 48.dp

/**
 * The per-feed 5-step slider: Rarely, Less, Auto, More, Favourite, with Auto in the centre.
 * Five fixed stops on a rule; the chosen one is a block of ink, and a bar runs from the centre
 * to it so an override reads at a glance as "less than" or "more than" what the paper would
 * learn. A tap or a drag picks a stop; nothing animates, so it behaves the same in E-ink.
 *
 * To accessibility services it is a single seek bar whose value is the step's name.
 * [description] says what it adjusts ("How much of Daring Fireball to print").
 *
 * With [showLabels] every stop is named underneath; without, only the chosen step is named,
 * beside the track, which suits a list of feeds.
 */
@Composable
fun AffinitySlider(
    step: SliderStep,
    onStepChange: (SliderStep) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current = rememberUpdatedState(step)
    val change = rememberUpdatedState(onStepChange)
    val name = stepLabel(step)
    val moreLabel = stringResource(R.string.story_show_more)
    val lessLabel = stringResource(R.string.story_show_less)
    val select: (SliderStep) -> Boolean = { target ->
        if (target == current.value) false else {
            change.value(target)
            true
        }
    }

    // With labels each stop sits over its label, mid-cell. Without, the end stops go out to the
    // edges so the track lines up with the text above it.
    val edgeInset = with(LocalDensity.current) { 8.dp.toPx() }
    val inset: (Float) -> Float = { width -> if (showLabels) width / (2 * SliderSteps.count) else edgeInset }

    val control = Modifier
        .clearAndSetSemantics {
            contentDescription = description
            stateDescription = name
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = step.ordinal.toFloat(),
                range = 0f..(SliderSteps.count - 1).toFloat(),
                steps = SliderSteps.count - 2,
            )
            setProgress { value -> select(SliderSteps.fromIndex(value.roundToInt())) }
            customActions = listOfNotNull(
                CustomAccessibilityAction(moreLabel) { select(current.value.stepUp()) }.takeIf { SliderSteps.canShowMore(step) },
                CustomAccessibilityAction(lessLabel) { select(current.value.stepDown()) }.takeIf { SliderSteps.canShowLess(step) },
            )
        }
        .pointerInput(rtl, showLabels) {
            detectTapGestures { select(SliderSteps.stepAt(it.x, size.width.toFloat(), rtl, inset(size.width.toFloat()))) }
        }
        .pointerInput(rtl, showLabels) {
            detectHorizontalDragGestures { pointer, _ ->
                pointer.consume()
                select(SliderSteps.stepAt(pointer.position.x, size.width.toFloat(), rtl, inset(size.width.toFloat())))
            }
        }
        .drawBehind {
            val count = SliderSteps.count
            val y = SliderTrackHeight.toPx() / 2f
            val edge = inset(size.width)
            fun x(index: Int): Float = SliderSteps.positionOf(index, size.width, count, rtl, edge)
            val centre = SliderStep.AUTO.ordinal
            val chosen = step.ordinal
            drawLine(colors.inkSecondary, Offset(x(0), y), Offset(x(count - 1), y), 1.dp.toPx())
            // The neutral point, so the eye finds the centre even when the block has left it.
            drawLine(colors.inkSecondary, Offset(x(centre), y - 9.dp.toPx()), Offset(x(centre), y + 9.dp.toPx()), 1.dp.toPx())
            if (chosen != centre) drawLine(colors.ink, Offset(x(centre), y), Offset(x(chosen), y), 4.dp.toPx())
            for (index in 0 until count) {
                val side = (if (index == chosen) 16.dp else 6.dp).toPx()
                drawRect(
                    color = if (index == chosen) colors.ink else colors.inkSecondary,
                    topLeft = Offset(x(index) - side / 2f, y - side / 2f),
                    size = Size(side, side),
                )
            }
        }

    if (showLabels) {
        Column(modifier.fillMaxWidth().then(control)) {
            Spacer(Modifier.height(SliderTrackHeight - 8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                SliderStep.entries.forEach { entry ->
                    val chosen = entry == step
                    Text(
                        text = stepLabel(entry),
                        style = type.caption.copy(fontWeight = if (chosen) FontWeight.Bold else type.caption.fontWeight),
                        color = if (chosen) colors.ink else colors.inkSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        // As wide as the longest step name, so every row's track ends at the same place and no
        // name is clipped at any font scale or in any language.
        val measurer = rememberTextMeasurer()
        val labels = SliderStep.entries.map { stepLabel(it) }
        val density = LocalDensity.current
        val nameWidth = remember(labels, type.label, density) {
            with(density) { labels.maxOf { measurer.measure(it, type.label, maxLines = 1).size.width }.toDp() + 10.dp }
        }
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(SliderTrackHeight).then(control))
            Text(
                text = name,
                style = type.label,
                color = if (step == SliderStep.AUTO) colors.inkSecondary else colors.ink,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(nameWidth).clearAndSetSemantics { },
            )
        }
    }
}

/** Five pips with the chosen one enlarged: the slider's position where there is no room for the slider. */
@Composable
fun StepPips(step: SliderStep, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    Row(modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        SliderStep.entries.forEach { entry ->
            val chosen = entry == step
            Box(Modifier.size(if (chosen) 11.dp else 5.dp).background(if (chosen) colors.ink else colors.inkSecondary))
        }
    }
}

/**
 * "Show less" and "Show more" with the feed's resulting step between them: the slider moved
 * one step at a time, for the long-press menu and "Why is this here?".
 */
@Composable
fun AffinityStepper(
    step: SliderStep,
    onStepChange: (SliderStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val name = stepLabel(step)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.5.dp, colors.ink)) {
        SegmentCell(
            text = stringResource(R.string.story_show_less),
            enabled = SliderSteps.canShowLess(step),
            onClick = { onStepChange(step.stepDown()) },
        )
        Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            StepPips(step)
            Text(name, style = MorningIssueTheme.typography.label.copy(fontWeight = FontWeight.Bold), color = colors.ink, maxLines = 1)
        }
        Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
        SegmentCell(
            text = stringResource(R.string.story_show_more),
            enabled = SliderSteps.canShowMore(step),
            onClick = { onStepChange(step.stepUp()) },
        )
    }
}

/** One pressable cell of a boxed row. */
@Composable
private fun RowScope.SegmentCell(text: String, enabled: Boolean, onClick: () -> Unit, description: String? = null) {
    val colors = MorningIssueTheme.colors
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MorningIssueTheme.typography.label,
            // E-ink has no grey: a spent end is struck out instead of dimmed.
            color = if (enabled || colors.isEink) colors.ink else colors.hairline,
            textDecoration = if (!enabled && colors.isEink) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            textAlign = TextAlign.Center,
        )
    }
}

// --- Mutes ---------------------------------------------------------------------------------

/**
 * Mute for 1, 3, 7 or 30 days; once muted, when the mute ends and a way to lift it. [now] is
 * passed in so the caller decides the clock.
 */
@Composable
fun MuteControl(
    muteUntil: Instant?,
    now: Instant,
    onMute: (days: Int) -> Unit,
    onUnmute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val until = Mutes.activeUntil(muteUntil, now)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (until != null) {
            Row(
                Modifier.fillMaxWidth().border(1.5.dp, colors.ink).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(PaperIcons.Muted, contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.mute_until, PaperFormat.shortDate(until.atZone(ZoneId.systemDefault()).toLocalDate())),
                    style = type.label,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                Box(Modifier.width(1.5.dp).height(48.dp).background(colors.ink))
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onUnmute)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.mute_unmute), style = type.label.copy(fontWeight = FontWeight.Bold), color = colors.ink)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.5.dp, colors.ink)) {
                Mutes.DAYS.forEachIndexed { index, days ->
                    if (index > 0) Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
                    SegmentCell(
                        text = pluralStringResource(R.plurals.mute_days, days, days),
                        enabled = true,
                        onClick = { onMute(days) },
                        description = pluralStringResource(R.plurals.mute_for_days, days, days),
                    )
                }
            }
        }
        Text(
            text = stringResource(if (until != null) R.string.mute_active_detail else R.string.mute_detail),
            style = type.caption,
            color = colors.inkSecondary,
        )
    }
}

// --- Marks, toggles and chips ----------------------------------------------------------------

/**
 * The small marks in front of a byline: the read tick, a heart once Liked, a bookmark once
 * Saved. They are how a page shows the reader's state without moving anything.
 */
@Composable
fun StoryMarks(isRead: Boolean, isLiked: Boolean, isSaved: Boolean, modifier: Modifier = Modifier, size: Dp = 13.dp) {
    if (!isRead && !isLiked && !isSaved) return
    val colors = MorningIssueTheme.colors
    Row(modifier.padding(end = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isRead) ReadTick(size = size + 1.dp)
        if (isLiked) Icon(Icons.Filled.Favorite, stringResource(R.string.reader_liked), tint = colors.accent, modifier = Modifier.size(size))
        if (isSaved) Icon(PaperIcons.Bookmark, stringResource(R.string.reader_saved), tint = colors.accent, modifier = Modifier.size(size))
    }
}

/** A [PaperButton] that stays pressed: filled with ink while [checked]. */
@Composable
fun PaperToggleButton(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MorningIssueTheme.colors
    val content = if (checked) colors.paper else colors.ink
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(if (checked) Modifier.background(colors.ink) else Modifier.border(1.5.dp, colors.ink))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MorningIssueTheme.typography.label, color = content, textAlign = TextAlign.Center)
    }
}

/** A boxed small-caps choice in a row of filters; the chosen one is filled with ink. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = MorningIssueTheme.colors
    val content = if (selected) colors.paper else colors.ink
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .then(if (selected) Modifier.background(colors.ink) else Modifier.border(1.dp, boxOutlineColor))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text.uppercase(), style = MorningIssueTheme.typography.sectionLabel, color = content, maxLines = 1)
    }
}

/**
 * A tag as a pressable token with an action icon: filled with ink for a tag the feed carries
 * (press to remove), outlined for one on offer (press to add).
 */
@Composable
fun TagToken(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    val content: Color = if (filled) colors.paper else colors.ink
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .then(if (filled) Modifier.background(colors.ink) else Modifier.border(1.dp, boxOutlineColor))
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = MorningIssueTheme.typography.sectionLabel, color = content, maxLines = 1)
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
    }
}

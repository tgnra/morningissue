package net.tgnra.morningissue.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

// --- Rules ---------------------------------------------------------------------------------

/** A thin divider between stories and lines. */
@Composable
fun HairlineRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MorningIssueTheme.colors.hairline))
}

/** A heavy rule, such as those around a lead story. */
@Composable
fun HeavyRule(modifier: Modifier = Modifier, thickness: Dp = 2.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(MorningIssueTheme.colors.rule))
}

/** The masthead's rule: heavy over thin. */
@Composable
fun DoubleRule(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        HeavyRule(thickness = 3.dp)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MorningIssueTheme.colors.rule))
    }
}

/** A column rule: fills the height of a `Row` with `IntrinsicSize.Min`. */
@Composable
fun ColumnRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(MorningIssueTheme.colors.hairline))
}

// --- Depth ---------------------------------------------------------------------------------

/**
 * The soft shadow under a cover or a floating sheet, in the theme's shadow colour. Draws
 * nothing where the theme has no shadows (E-ink).
 */
@Composable
fun Modifier.paperShadow(radius: Dp = 10.dp, offsetY: Dp = 4.dp): Modifier {
    val color = MorningIssueTheme.colors.shadow
    if (color.alpha == 0f) return this
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val offsetPx = with(density) { offsetY.toPx() }
    val paint = remember(color, radiusPx, offsetPx) {
        Paint().also {
            val framework = it.asFrameworkPaint()
            framework.color = android.graphics.Color.TRANSPARENT
            framework.setShadowLayer(radiusPx, 0f, offsetPx, color.toArgb())
        }
    }
    return drawBehind {
        drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}

/** The outline of anything boxed: black in E-ink, the quiet shelf-edge colour otherwise. */
val boxOutlineColor: Color
    @Composable get() = if (MorningIssueTheme.isEink) MorningIssueTheme.colors.ink else MorningIssueTheme.colors.shelfEdge

// --- Text furniture ------------------------------------------------------------------------

/** A small-caps style label: section names, column heads, page furniture. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MorningIssueTheme.colors.inkSecondary,
    heading: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        style = MorningIssueTheme.typography.sectionLabel,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (heading) modifier.semantics { heading() } else modifier,
    )
}

/** The small tick that marks a read story or edition. */
@Composable
fun ReadTick(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = stringResource(R.string.read_tick),
        tint = MorningIssueTheme.colors.accent,
        modifier = modifier.size(size),
    )
}

/** A boxed small-caps tag, such as a feed's cadence tier. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MorningIssueTheme.typography.sectionLabel,
        color = MorningIssueTheme.colors.inkSecondary,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, boxOutlineColor)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** One line of status under a header: sync results, import results, errors. */
@Composable
fun NoticeLine(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    val colors = MorningIssueTheme.colors
    Text(
        text = text,
        style = MorningIssueTheme.typography.label,
        color = if (isError) colors.accent else colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (isError) colors.accent else boxOutlineColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A thin, unanimated progress bar. [fraction] is 0..1. */
@Composable
fun ThinProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val colors = MorningIssueTheme.colors
    // E-ink has one ink, so its track is a thin line under the filled part rather than a tint.
    val track = if (colors.isEink) {
        Modifier.drawBehind { drawRect(colors.ink, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
    } else {
        Modifier.background(colors.hairline)
    }
    Box(modifier.fillMaxWidth().height(height).then(track)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(colors.accent),
        )
    }
}

// --- Buttons -------------------------------------------------------------------------------

/**
 * A square, outlined button with a label; [primary] fills it with ink. Flat in every theme, so
 * it is already the "outlined box with a label" E-ink wants.
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MorningIssueTheme.colors
    val content = when {
        !enabled -> colors.inkSecondary
        primary -> colors.paper
        else -> colors.ink
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (primary && enabled) Modifier.background(colors.ink)
                else Modifier.border(if (enabled) 1.5.dp else 1.dp, if (enabled) colors.ink else colors.hairline),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MorningIssueTheme.typography.label, color = content, textAlign = TextAlign.Center)
    }
}

/** An underlined text link with a full-size touch target. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = MorningIssueTheme.typography.label) {
    val colors = MorningIssueTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = style,
            color = colors.accent,
            modifier = Modifier.drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(colors.accent, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        )
    }
}

@Composable
fun PaperIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MorningIssueTheme.colors.ink,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else MorningIssueTheme.colors.hairline)
    }
}

/** A row of mutually exclusive choices: boxed, the chosen one filled with ink. */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MorningIssueTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .border(1.5.dp, colors.ink)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, (value, label) ->
            val isSelected = value == selected
            if (index > 0) Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(if (isSelected) colors.ink else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MorningIssueTheme.typography.label,
                    color = if (isSelected) colors.paper else colors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun PaperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isUrl: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    /** Hides what is typed, and asks for the password keyboard. */
    isPassword: Boolean = false,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        textStyle = type.label.copy(fontSize = type.body.fontSize),
        placeholder = { Text(placeholder, style = type.label.copy(fontSize = type.body.fontSize), color = colors.inkSecondary) },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = colors.inkSecondary) } },
        shape = RectangleShape,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                isPassword -> KeyboardType.Password
                isUrl -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            imeAction = imeAction,
            autoCorrectEnabled = !isUrl && !isPassword,
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.ink,
            unfocusedBorderColor = boxOutlineColor,
            disabledBorderColor = colors.hairline,
            cursorColor = colors.ink,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            focusedContainerColor = colors.paperRaised,
            unfocusedContainerColor = colors.paperRaised,
            disabledContainerColor = colors.paperRaised,
        ),
    )
}

// --- Screen furniture ----------------------------------------------------------------------

/** The header of every screen below the newsstand: back, a title, actions, and a rule. */
@Composable
fun PaperTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MorningIssueTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MorningIssueTheme.typography.headline,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
    }
}

/**
 * The frame of a secondary screen: paper background, system-bar padding, top bar, content.
 * [overlay] is drawn over everything, for [PaperDialog]s.
 */
@Composable
fun PaperScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // How many [PaperDialog]s the overlay is showing. While there is one, the screen beneath it
    // is not there for screen readers: the dialog is in the same window, so nothing else hides it.
    val openDialogs = remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize().background(MorningIssueTheme.colors.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (openDialogs.intValue > 0) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            PaperTopBar(title, onBack, actions = actions)
            content()
        }
        CompositionLocalProvider(LocalOpenDialogs provides openDialogs) { overlay() }
    }
}

/** The enclosing [PaperScreenFrame]'s count of open dialogs; null outside one. */
private val LocalOpenDialogs = compositionLocalOf<MutableIntState?> { null }

/** Centres content and keeps it to a comfortable width on tablets. */
fun Modifier.readableWidth(max: Dp = 680.dp): Modifier = fillMaxWidth().then(Modifier.widthIn(max = max))

/** Centres [content] in a column no wider than [max]. */
@Composable
fun CenteredColumn(modifier: Modifier = Modifier, max: Dp = 680.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = max).fillMaxWidth(), content = content)
    }
}

// --- Dialogs and menus ---------------------------------------------------------------------

/**
 * A dialog drawn inside the screen rather than in its own window, so it appears and goes
 * without any window animation (E-ink) and is themed like everything else. Compose it last in
 * the screen's root `Box` (see [PaperScreenFrame]'s `overlay`).
 */
@Composable
fun PaperDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MorningIssueTheme.colors
    val eink = MorningIssueTheme.isEink
    BackHandler(onBack = onDismiss)
    val openDialogs = LocalOpenDialogs.current
    DisposableEffect(openDialogs) {
        openDialogs?.let { it.intValue++ }
        onDispose { openDialogs?.let { it.intValue-- } }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // E-ink has no tints to dim with, so the dialog stands alone on a blank page.
            .background(if (eink) colors.paper else Color.Black.copy(alpha = if (colors.isDark) 0.7f else 0.4f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .paperShadow(radius = 24.dp, offsetY = 8.dp)
                .background(colors.paperRaised)
                .border(if (eink) 2.dp else 1.dp, if (eink) colors.ink else colors.hairline)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MorningIssueTheme.typography.headline,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/** The row of buttons at the foot of a [PaperDialog]. */
@Composable
fun DialogButtons(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

data class MenuItem(val label: String, val onClick: () -> Unit)

/** A "more" button that drops a plain list of actions; unanimated, so it suits E-ink too. */
@Composable
fun OverflowMenu(items: List<MenuItem>, contentDescription: String, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    var open by remember { mutableStateOf(false) }
    val offsetY = with(LocalDensity.current) { 44.dp.roundToPx() }
    Box(modifier) {
        PaperIconButton(Icons.Filled.MoreVert, contentDescription, onClick = { open = true })
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                // Padding leaves the shadow room inside the popup window.
                Box(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 20.dp)) {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 200.dp)
                            .paperShadow()
                            .background(colors.paperRaised)
                            .border(1.dp, boxOutlineColor),
                    ) {
                        items.forEachIndexed { index, item ->
                            if (index > 0) HairlineRule()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) {
                                        open = false
                                        item.onClick()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(item.label, style = MorningIssueTheme.typography.label, color = colors.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A message for an empty list or a screen with nothing to show yet. */
@Composable
fun EmptyMessage(title: String, detail: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(48.dp).height(2.dp).background(colors.rule))
        Text(title, style = type.headline, color = colors.ink, textAlign = TextAlign.Center)
        Text(detail, style = type.standfirst, color = colors.inkSecondary, textAlign = TextAlign.Center)
        action()
    }
}

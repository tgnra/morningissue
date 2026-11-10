package net.tgnra.morningissue.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colour tokens. Screens are built from these (through `MorningIssueTheme.colors`),
 * not from literal colours, so Light, Dark and E-ink all work everywhere.
 */
@Immutable
data class PaperColors(
    /** The page. */
    val paper: Color,
    /** A sheet lying on something else: covers on the stand, menus, dialogs. */
    val paperRaised: Color,
    /** Headlines and body text. */
    val ink: Color,
    /** Bylines, labels, captions. */
    val inkSecondary: Color,
    /** Heavy rules, such as those around a lead story or under the masthead. */
    val rule: Color,
    /** Thin dividers between stories and brief lines. */
    val hairline: Color,
    /** Links, the progress bar, selected controls. */
    val accent: Color,
    val onAccent: Color,
    /** The newsstand background behind the rack. */
    val stand: Color,
    /** A shelf's surface. */
    val shelf: Color,
    /** A shelf's front edge, and outlines of boxed buttons. */
    val shelfEdge: Color,
    /** Soft shadow under covers; fully transparent in E-ink. */
    val shadow: Color,
    val isDark: Boolean,
    val isEink: Boolean,
)

/** Warm off-white paper. */
val LightPaperColors = PaperColors(
    paper = Color(0xFFF7F3EA),
    paperRaised = Color(0xFFFCFAF4),
    ink = Color(0xFF1B1A17),
    inkSecondary = Color(0xFF5F5A50),
    rule = Color(0xFF1B1A17),
    hairline = Color(0xFFD6CEBD),
    accent = Color(0xFF9A2B1E),
    onAccent = Color(0xFFFFFFFF),
    stand = Color(0xFFE9E1D0),
    shelf = Color(0xFFD8CDB6),
    shelfEdge = Color(0xFFB5A88D),
    shadow = Color(0x40000000),
    isDark = false,
    isEink = false,
)

/** Night edition: true black. */
val DarkPaperColors = PaperColors(
    paper = Color(0xFF000000),
    paperRaised = Color(0xFF141413),
    ink = Color(0xFFE9E5DB),
    inkSecondary = Color(0xFFA29C90),
    rule = Color(0xFFE9E5DB),
    hairline = Color(0xFF2F2D29),
    accent = Color(0xFFE5796B),
    onAccent = Color(0xFF000000),
    stand = Color(0xFF000000),
    shelf = Color(0xFF1B1A18),
    shelfEdge = Color(0xFF34312C),
    shadow = Color(0x99000000),
    isDark = true,
    isEink = false,
)

/** Pure black on white: no greys, tints or shadows. */
val EinkPaperColors = PaperColors(
    paper = Color.White,
    paperRaised = Color.White,
    ink = Color.Black,
    inkSecondary = Color.Black,
    rule = Color.Black,
    hairline = Color.Black,
    accent = Color.Black,
    onAccent = Color.White,
    stand = Color.White,
    shelf = Color.White,
    shelfEdge = Color.Black,
    shadow = Color.Transparent,
    isDark = false,
    isEink = true,
)

val LocalPaperColors = staticCompositionLocalOf { LightPaperColors }

/** A Material 3 scheme derived from the tokens, so stock components match the paper. */
internal fun PaperColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val container = if (isEink) paper else shelf
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = ink,
        inversePrimary = accent,
        secondary = ink,
        onSecondary = paper,
        secondaryContainer = container,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = container,
        onTertiaryContainer = ink,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = stand,
        onSurfaceVariant = inkSecondary,
        surfaceTint = if (isEink) Color.Transparent else accent,
        inverseSurface = ink,
        inverseOnSurface = paper,
        outline = if (isEink) ink else shelfEdge,
        outlineVariant = hairline,
        scrim = if (isEink) Color.Transparent else Color.Black,
        surfaceBright = paperRaised,
        surfaceDim = stand,
        surfaceContainerLowest = paper,
        surfaceContainerLow = paperRaised,
        surfaceContainer = paperRaised,
        surfaceContainerHigh = if (isEink) paper else stand,
        surfaceContainerHighest = if (isEink) paper else stand,
    )
}
